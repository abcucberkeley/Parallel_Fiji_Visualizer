// Zarr half of libParallelReadC: JNI bindings for edu.abc.berkeley.ParallelReadNative.
// Delegates to cpp-zarr; this translation unit must only see cpp-zarr headers
// (see CMakeLists.txt -- helperfunctions.h clashes between the upstreams).
//
// cpp-zarr reports problems by throwing std::string. Nothing may escape a JNI
// function: an uncaught C++ exception aborts the JVM, taking Fiji with it. So
// every call into cpp-zarr goes through readPlanes() or withZarr() below.
//
// A read takes a window [start, end) with one value per axis of the array,
// and which axes are the image's x, y, z, channel and time ({x, y, z, c, t};
// x and y are required, the others are -1 when absent). Every other axis must
// have extent 1 in the window. The result is ImageJ planes in its czt order:
// plane c + C*(z + Z*t) holds the rows y, each holding the x values in order.

#include <jni.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <string>
#include <vector>

#include "parallelreadzarr.h"
#include "zarr.h"

#include "jni_marshal.h"
#include "edu_abc_berkeley_ParallelReadNative.h"

namespace {

// A read's planes: data holds planes() * planeElems elements, plane i
// starting at element offsets[i]
struct Planes {
	void* data = nullptr; // malloc'd; the caller frees it
	uint64_t planeElems = 0;
	std::vector<uint64_t> offsets;
	uint64_t elems() const { return planeElems * offsets.size(); }
};

struct Elem16 { uint64_t lo, hi; }; // complex128

// Copy each plane (x stride sx and y stride sy from its base) into ImageJ
// order. Tiles of 64x64 keep the reads of a transposed plane (MATLAB's y, x
// layout: sx = H) in cache instead of touching a new cache line per element.
template <typename T>
void gatherPlanes(T* dst, const T* src, uint64_t W, uint64_t H,
                  const std::vector<uint64_t>& base, uint64_t sx, uint64_t sy)
{
	const uint64_t B = 64;
	const int64_t nPlanes = (int64_t)base.size();
	const int64_t rowTiles = (int64_t)((H + B - 1) / B);
	#pragma omp parallel for collapse(2) schedule(static)
	for (int64_t p = 0; p < nPlanes; p++) {
		for (int64_t r = 0; r < rowTiles; r++) {
			const T* s = src + base[(size_t)p];
			T* d = dst + (uint64_t)p * W * H;
			const uint64_t y0 = (uint64_t)r * B, y1 = y0 + B < H ? y0 + B : H;
			for (uint64_t x0 = 0; x0 < W; x0 += B) {
				const uint64_t x1 = x0 + B < W ? x0 + B : W;
				for (uint64_t y = y0; y < y1; y++) {
					T* row = d + y * W;
					const T* sr = s + y * sy;
					for (uint64_t x = x0; x < x1; x++) row[x] = sr[x * sx];
				}
			}
		}
	}
}

std::vector<uint64_t> toVector(JNIEnv* env, jlongArray a) {
	const jsize n = a == nullptr ? 0 : env->GetArrayLength(a);
	std::vector<jlong> tmp((size_t)n);
	if (n > 0) env->GetLongArrayRegion(a, 0, n, tmp.data());
	return std::vector<uint64_t>(tmp.begin(), tmp.end());
}

// The body of readPlanes; returns why the read failed, or "" on success.
std::string readPlanesFrom(zarr& Zarr, const std::vector<uint64_t>& start, const std::vector<uint64_t>& end,
                           const jint* axes, uint64_t elemBytes, Planes& out)
{
	const int64_t n = (int64_t)Zarr.get_ndims();
	if ((int64_t)start.size() != n || (int64_t)end.size() != n)
		return "The read window has " + std::to_string(start.size()) + " axes but the Zarr array has " + std::to_string(n);
	if (Zarr.dtypeBytes() != elemBytes)
		return "The Zarr array's data type (" + Zarr.get_dtype() + ") does not match the read";
	const std::string compressor = Zarr.get_compressor();
	if (compressor != "none" && compressor != "blosc" && compressor != "gzip" && compressor != "zstd")
		return "This Zarr array uses the \"" + compressor + "\" compressor, which is not supported";
	for (int i = 0; i < 5; i++) {
		if ((i < 2 && axes[i] < 0) || axes[i] < -1 || axes[i] >= n) return "Invalid axes for the Zarr array";
		for (int j = 0; j < i; j++)
			if (axes[i] >= 0 && axes[i] == axes[j]) return "Invalid axes for the Zarr array";
	}
	std::vector<uint64_t> extent((size_t)n);
	for (int64_t d = 0; d < n; d++) {
		if (end[d] < start[d] || end[d] > Zarr.get_shape(d)) return "The read window lies outside the Zarr array";
		extent[d] = end[d] - start[d];
		bool role = false;
		for (int i = 0; i < 5; i++) role = role || axes[i] == d;
		if (!role && extent[d] != 1) return "The read window spans an axis that is not x, y, z, c or t";
	}

	// Read in the memory order that keeps each plane one compact block: F when
	// x and y come before the other axes read (MATLAB's y, x, z, c, t), C when
	// they come after them (NumPy's t, c, z, y, x); otherwise the array's own
	// order, which cpp-zarr copies chunks into fastest
	const int64_t lo = axes[0] < axes[1] ? axes[0] : axes[1];
	const int64_t hi = axes[0] < axes[1] ? axes[1] : axes[0];
	bool first = true, last = true;
	for (int64_t d = 0; d < n; d++) {
		if (d == axes[0] || d == axes[1] || extent[d] == 1) continue;
		if (d < hi) first = false;
		if (d > lo) last = false;
	}
	const bool cOrder = last && !first ? true : first && !last ? false : Zarr.get_order() == "C";

	void* raw = parallelReadZarrWriteWrapper(Zarr, true, start, end, cOrder);
	if (raw == nullptr) return "Parallel Zarr read failed";

	std::vector<uint64_t> stride((size_t)n, 1);
	if (cOrder) for (int64_t d = n - 2; d >= 0; d--) stride[d] = stride[d + 1] * extent[d + 1];
	else for (int64_t d = 1; d < n; d++) stride[d] = stride[d - 1] * extent[d - 1];
	uint64_t size[5], step[5];
	for (int i = 0; i < 5; i++) {
		size[i] = axes[i] >= 0 ? extent[axes[i]] : 1;
		step[i] = axes[i] >= 0 ? stride[axes[i]] : 0;
	}
	const uint64_t W = size[0], H = size[1], Z = size[2], C = size[3], T = size[4];
	out.planeElems = W * H;
	out.offsets.resize(Z * C * T);
	for (uint64_t t = 0; t < T; t++)
		for (uint64_t z = 0; z < Z; z++)
			for (uint64_t c = 0; c < C; c++)
				out.offsets[c + C * (z + Z * t)] = z * step[2] + c * step[3] + t * step[4];

	if ((W == 1 || step[0] == 1) && (H == 1 || step[1] == W)) {
		out.data = raw; // every plane is already in ImageJ order
		return "";
	}
	out.data = malloc(out.elems() * elemBytes);
	if (out.data == nullptr) {
		free(raw);
		return "Out of native memory reordering the Zarr read";
	}
	switch (elemBytes) {
		case 1: gatherPlanes((uint8_t*)out.data, (const uint8_t*)raw, W, H, out.offsets, step[0], step[1]); break;
		case 2: gatherPlanes((uint16_t*)out.data, (const uint16_t*)raw, W, H, out.offsets, step[0], step[1]); break;
		case 4: gatherPlanes((uint32_t*)out.data, (const uint32_t*)raw, W, H, out.offsets, step[0], step[1]); break;
		case 8: gatherPlanes((uint64_t*)out.data, (const uint64_t*)raw, W, H, out.offsets, step[0], step[1]); break;
		default: gatherPlanes((Elem16*)out.data, (const Elem16*)raw, W, H, out.offsets, step[0], step[1]); break;
	}
	free(raw);
	for (size_t p = 0; p < out.offsets.size(); p++) out.offsets[p] = p * out.planeElems;
	return "";
}

// Read a window as ImageJ planes in the array's own element type, which must
// be elemBytes wide. On failure a Java exception is pending and false is
// returned.
bool readPlanes(JNIEnv* env, jstring fileName, jlongArray jStart, jlongArray jEnd, jintArray jAxes,
                uint64_t elemBytes, Planes& out)
{
	if (jAxes == nullptr || env->GetArrayLength(jAxes) != 5) {
		pfv::throwRuntime(env, "The axes of a Zarr read must be {x, y, z, c, t}");
		return false;
	}
	jint axes[5];
	env->GetIntArrayRegion(jAxes, 0, 5, axes);
	const std::vector<uint64_t> start = toVector(env, jStart);
	const std::vector<uint64_t> end = toVector(env, jEnd);
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return false;
	std::string problem;
	try {
		zarr Zarr(fName);
		problem = readPlanesFrom(Zarr, start, end, axes, elemBytes, out);
	}
	catch (const std::string& why) {
		problem = pfv::zarrError(why);
	}
	catch (const std::exception& e) {
		problem = e.what();
	}
	catch (...) {
		problem = "Parallel Zarr read failed";
	}
	env->ReleaseStringUTFChars(fileName, fName);
	if (!problem.empty()) {
		free(out.data);
		out.data = nullptr;
		pfv::throwRuntime(env, problem.c_str());
		return false;
	}
	return true;
}

// Open a zarr's metadata and hand it to fn, turning every error into a Java
// exception; fn may itself throw std::string codes.
template <typename Fn>
void withZarr(JNIEnv* env, jstring fileName, Fn fn) {
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return;
	try {
		zarr Zarr(fName);
		fn(Zarr);
	}
	catch (const std::string& why) {
		pfv::throwRuntime(env, pfv::zarrError(why).c_str());
	}
	catch (const std::exception& e) {
		pfv::throwRuntime(env, e.what());
	}
	catch (...) {
		pfv::throwRuntime(env, "Reading the Zarr metadata failed");
	}
	env->ReleaseStringUTFChars(fileName, fName);
}

template <typename T>
jobjectArray toJava(JNIEnv* env, const Planes& p, const T* data, jobjectArray dest) {
	return pfv::slicesToJava<T>(env, data, p.planeElems, p.offsets.size(), dest, p.offsets.data());
}

template <typename JavaT>
jobjectArray readZarr(JNIEnv* env, jstring fileName, jlongArray start, jlongArray end,
                      jintArray axes, jobjectArray dest)
{
	Planes p;
	if (!readPlanes(env, fileName, start, end, axes, sizeof(JavaT), p)) return nullptr;
	jobjectArray out = toJava<JavaT>(env, p, (const JavaT*)p.data, dest);
	free(p.data);
	return out;
}

// Signed integers: XOR-shift the sign bit into the unsigned range ImageJ
// expects; a Java-side calibration maps the values back.
template <typename UnsignedT, typename JavaT>
jobjectArray readZarrShifted(JNIEnv* env, jstring fileName, jlongArray start, jlongArray end,
	jintArray axes, UnsignedT mask, jobjectArray dest)
{
	Planes p;
	if (!readPlanes(env, fileName, start, end, axes, sizeof(UnsignedT), p)) return nullptr;
	pfv::xorShiftInPlace<UnsignedT>((UnsignedT*)p.data, p.elems(), mask);
	jobjectArray out = toJava<JavaT>(env, p, (const JavaT*)p.data, dest);
	free(p.data);
	return out;
}

// 32- and 64-bit integers and doubles are converted to float: ImageJ shows
// no other numeric types.
template <typename NativeT>
jobjectArray readZarrAsFloat(JNIEnv* env, jstring fileName, jlongArray start, jlongArray end,
	jintArray axes, jobjectArray dest)
{
	Planes p;
	if (!readPlanes(env, fileName, start, end, axes, sizeof(NativeT), p)) return nullptr;
	float* data = pfv::convertToFloat<NativeT>((const NativeT*)p.data, p.elems());
	free(p.data);
	if (data == nullptr) {
		pfv::throwRuntime(env, "Out of native memory converting zarr to float");
		return nullptr;
	}
	jobjectArray out = toJava<float>(env, p, data, dest);
	free(data);
	return out;
}

} // namespace

#define PFV_ZARR_READ_ARGS (JNIEnv* env, jobject, jstring fileName, jlongArray start, jlongArray end, \
	jintArray axes, jobjectArray dest)

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrUINT8 PFV_ZARR_READ_ARGS
{
	return readZarr<int8_t>(env, fileName, start, end, axes, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrUINT16 PFV_ZARR_READ_ARGS
{
	return readZarr<int16_t>(env, fileName, start, end, axes, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrFLOAT PFV_ZARR_READ_ARGS
{
	return readZarr<float>(env, fileName, start, end, axes, dest);
}

// 64-bit images are converted to float: ImageStack has no double support.
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrDOUBLE PFV_ZARR_READ_ARGS
{
	return readZarrAsFloat<double>(env, fileName, start, end, axes, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrINT8 PFV_ZARR_READ_ARGS
{
	return readZarrShifted<uint8_t, int8_t>(env, fileName, start, end, axes, (uint8_t)0x80u, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrINT16 PFV_ZARR_READ_ARGS
{
	return readZarrShifted<uint16_t, int16_t>(env, fileName, start, end, axes, (uint16_t)0x8000u, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrINT32 PFV_ZARR_READ_ARGS
{
	return readZarrAsFloat<int32_t>(env, fileName, start, end, axes, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrUINT32 PFV_ZARR_READ_ARGS
{
	return readZarrAsFloat<uint32_t>(env, fileName, start, end, axes, dest);
}

// ImageJ has no 64-bit integer type; converted to float like doubles are.
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrINT64 PFV_ZARR_READ_ARGS
{
	return readZarrAsFloat<int64_t>(env, fileName, start, end, axes, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrUINT64 PFV_ZARR_READ_ARGS
{
	return readZarrAsFloat<uint64_t>(env, fileName, start, end, axes, dest);
}

// float16 and bfloat16 zarrs, converted to float (exact for both).
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrFLOAT16
	(JNIEnv* env, jobject, jstring fileName, jlongArray start, jlongArray end,
	 jintArray axes, jboolean bfloat16, jobjectArray dest)
{
	Planes p;
	if (!readPlanes(env, fileName, start, end, axes, 2, p)) return nullptr;
	float* data = pfv::float16ToFloat((const uint16_t*)p.data, p.elems(), bfloat16 != JNI_FALSE);
	free(p.data);
	if (data == nullptr) {
		pfv::throwRuntime(env, "Out of native memory converting zarr to float");
		return nullptr;
	}
	jobjectArray out = toJava<float>(env, p, data, dest);
	free(data);
	return out;
}

// The numpy-style dtype string, e.g. "<u2", "|u1" or "<f2". cpp-zarr gives
// Zarr v3 arrays ("uint16", ...) the same names; bfloat16 is "bfloat16".
JNIEXPORT jstring JNICALL Java_edu_abc_berkeley_ParallelReadNative_getZarrDtype
	(JNIEnv* env, jobject, jstring fileName)
{
	jstring rval = nullptr;
	withZarr(env, fileName, [&](zarr& Zarr) {
		rval = env->NewStringUTF(Zarr.get_dtype().c_str());
	});
	return rval;
}

JNIEXPORT jlong JNICALL Java_edu_abc_berkeley_ParallelReadNative_getZarrDataType
	(JNIEnv* env, jobject, jstring fileName)
{
	jlong bits = 0;
	withZarr(env, fileName, [&](zarr& Zarr) {
		bits = (jlong)(Zarr.dtypeBytes() * 8);
	});
	return bits;
}

// The array's shape, one value per axis.
JNIEXPORT jlongArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_getZarrDims
	(JNIEnv* env, jobject, jstring fileName)
{
	jlongArray rval = nullptr;
	withZarr(env, fileName, [&](zarr& Zarr) {
		const uint64_t n = Zarr.get_ndims();
		std::vector<jlong> dims((size_t)n);
		for (uint64_t d = 0; d < n; d++) dims[(size_t)d] = (jlong)Zarr.get_shape(d);
		rval = env->NewLongArray((jsize)n);
		if (rval != nullptr) env->SetLongArrayRegion(rval, 0, (jsize)n, dims.data());
	});
	return rval;
}

// Runs when Java loads this library; see primeUnwinder in jni_marshal.h.
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*)
{
	pfv::primeUnwinder();
	return JNI_VERSION_1_6;
}
