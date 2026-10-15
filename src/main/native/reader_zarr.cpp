// Zarr half of libParallelReadC: JNI bindings for edu.abc.berkeley.ParallelReadNative.
// Delegates to cpp-zarr; this translation unit must only see cpp-zarr headers
// (see CMakeLists.txt -- helperfunctions.h clashes between the upstreams).
//
// cpp-zarr reports problems by throwing std::string. Nothing may escape a JNI
// function: an uncaught C++ exception aborts the JVM, taking Fiji with it. So
// every call into cpp-zarr goes through readToJava() or withZarr() below.
//
// A read takes a window [start, end) with one value per axis of the array,
// and which axes are the image's x, y, z, channel and time ({x, y, z, c, t};
// x and y are required, the others are -1 when absent). Every other axis must
// have extent 1 in the window. The result is ImageJ planes in its czt order:
// plane c + C*(z + Z*t) holds the rows y, each holding the x values in order.

#include <jni.h>
#include <cstdint>
#include <cstdlib>
#include <algorithm>
#include <cstring>
#include <exception>
#include <string>
#include <vector>

#include "helperfunctions.h" // cpp-zarr's
#include "parallelreadzarr.h"
#include "zarr.h"

#include "jni_marshal.h"
#include "edu_abc_berkeley_ParallelReadNative.h"

namespace {

std::vector<uint64_t> toVector(JNIEnv* env, jlongArray a) {
	const jsize n = a == nullptr ? 0 : env->GetArrayLength(a);
	std::vector<jlong> tmp((size_t)n);
	if (n > 0) env->GetLongArrayRegion(a, 0, n, tmp.data());
	return std::vector<uint64_t>(tmp.begin(), tmp.end());
}

enum { X, Y, Z, C, T };

// A read's window [start, end), which of the array's axes are x, y, z, c and t
// (and the image's size along them), the memory order cpp-zarr writes the
// window in, and the slabs it is read in: [cuts[i], cuts[i + 1]) along image
// axis slabAxis (window-relative), or all of it in one read when slabAxis < 0.
struct Window {
	std::vector<uint64_t> start, end;
	jint axes[5];
	uint64_t size[5];
	bool cOrder = false;
	int slabAxis = -1;
	std::vector<uint64_t> cuts;
	uint64_t planes() const { return size[Z] * size[C] * size[T]; }
};

// Slabs hold at least this much: per-read costs stay small next to the read.
// (Tests set it lower to read small arrays in slabs.)
uint64_t minSlabBytes = (uint64_t)1 << 30;

// Checks a read and plans it; returns why it cannot be done, or "".
std::string plan(zarr& Zarr, const std::vector<uint64_t>& start, const std::vector<uint64_t>& end,
                 const jint* axes, uint64_t elemBytes, Window& w)
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
	w.start = start;
	w.end = end;
	for (int i = 0; i < 5; i++) {
		w.axes[i] = axes[i];
		w.size[i] = axes[i] >= 0 ? extent[axes[i]] : 1;
	}

	// Read in the memory order that keeps each plane one compact block: F when
	// x and y come before the other axes read (MATLAB's y, x, z, c, t), C when
	// they come after them (NumPy's t, c, z, y, x); otherwise the array's own
	// order, which cpp-zarr copies chunks into fastest
	const int64_t lo = axes[X] < axes[Y] ? axes[X] : axes[Y];
	const int64_t hi = axes[X] < axes[Y] ? axes[Y] : axes[X];
	bool first = true, last = true;
	for (int64_t d = 0; d < n; d++) {
		if (d == axes[X] || d == axes[Y] || extent[d] == 1) continue;
		if (d < hi) first = false;
		if (d > lo) last = false;
	}
	w.cOrder = last && !first ? true : first && !last ? false : Zarr.get_order() == "C";

	// Big windows are read in slabs through one buffer. A fresh buffer of the
	// whole window spends up to half the read having its pages zeroed by the
	// kernel; a reused one is zeroed once, and only a slab is held. Slabs run
	// along the slowest of z, c and t in the read's memory order, start and end
	// on chunk boundaries (no chunk is decoded twice) and hold at least
	// minSlabBytes, so windows under twice that are read at once.
	const uint64_t bytes = w.size[X] * w.size[Y] * w.planes() * elemBytes;
	if (bytes < 2 * minSlabBytes) return "";
	int axis = -1;
	for (int i = Z; i <= T; i++) {
		if (axes[i] < 0 || w.size[i] < 2) continue;
		if (axis < 0 || (w.cOrder ? axes[i] < axes[axis] : axes[i] > axes[axis])) axis = i;
	}
	if (axis < 0) return "";
	const uint64_t a = (uint64_t)axes[axis], chunk = Zarr.get_chunks(a);
	const uint64_t perUnit = bytes / w.size[axis];
	const uint64_t rows = (minSlabBytes + perUnit * chunk - 1) / (perUnit * chunk);
	const uint64_t step = chunk * (rows > 0 ? rows : 1);
	w.cuts.push_back(0);
	for (uint64_t p = start[a]; p < end[a];) {
		const uint64_t next = (p / step + 1) * step < end[a] ? (p / step + 1) * step : end[a];
		w.cuts.push_back(next - start[a]);
		p = next;
	}
	if (w.cuts.size() > 2) w.slabAxis = axis;
	else w.cuts.clear();
	return "";
}

// Reads slab [s0, s1) of the window (all of it when the window is not cut)
// into buf, missing chunks as the fill value, and fills its planes into
// Java's, each element converted by conv. Returns why the read failed, or ""
// (also when a Java exception is pending).
template <typename S, typename D, typename Conv>
std::string readSlab(JNIEnv* env, zarr& Zarr, const Window& w, uint64_t s0, uint64_t s1, void* buf,
                     const uint8_t* fillElem, jobjectArray dest, Conv conv)
{
	const size_t n = w.start.size();
	std::vector<uint64_t> start = w.start, end = w.end, shape(n);
	uint64_t lo[5] = { 0, 0, 0, 0, 0 }, hi[5];
	for (int i = 0; i < 5; i++) hi[i] = w.size[i];
	if (w.slabAxis >= 0) {
		const jint a = w.axes[w.slabAxis];
		start[a] = w.start[a] + s0;
		end[a] = w.start[a] + s1;
		lo[w.slabAxis] = s0;
		hi[w.slabAxis] = s1;
	}
	for (size_t d = 0; d < n; d++) shape[d] = end[d] - start[d];
	Zarr.set_chunkInfo(start, end);
	if (parallelReadZarr(Zarr, buf, start, end, shape, sizeof(S) * 8, true, false, w.cOrder, fillElem)) {
		std::string why = Zarr.get_errString();
		while (!why.empty() && why.back() == '\n') why.pop_back();
		return why.empty() ? "Parallel Zarr read failed" : "Reading the Zarr failed: " + why;
	}

	std::vector<uint64_t> stride(n, 1);
	if (w.cOrder) for (int64_t d = (int64_t)n - 2; d >= 0; d--) stride[d] = stride[d + 1] * shape[d + 1];
	else for (size_t d = 1; d < n; d++) stride[d] = stride[d - 1] * shape[d - 1];
	uint64_t step[5];
	for (int i = 0; i < 5; i++) step[i] = w.axes[i] >= 0 ? stride[w.axes[i]] : 0;
	// The slab's planes: Java plane c + C*(z + Z*t), at its place in buf
	std::vector<uint64_t> idx, base;
	for (uint64_t t = lo[T]; t < hi[T]; t++)
		for (uint64_t z = lo[Z]; z < hi[Z]; z++)
			for (uint64_t c = lo[C]; c < hi[C]; c++) {
				idx.push_back(c + w.size[C] * (z + w.size[Z] * t));
				base.push_back((z - lo[Z]) * step[Z] + (c - lo[C]) * step[C] + (t - lo[T]) * step[T]);
			}
	const pfv::PlaneLayout layout = { w.size[X], w.size[Y], step[X], step[Y], base.data() };
	const S* src = (const S*)buf;
	pfv::fillJavaPlanes<D>(env, dest, w.size[X] * w.size[Y], idx.data(), idx.size(),
		[&](D* const* dst, uint64_t k0, uint64_t count) { pfv::fillPlanes<S, D>(dst, k0, count, src, layout, conv); });
	return "";
}

// Reads a window of an array of S elements into dest, Java planes of D, each
// element converted by conv. On failure a Java exception is pending and
// nullptr is returned.
template <typename S, typename D, typename Conv>
jobjectArray readToJava(JNIEnv* env, jstring fileName, jlongArray jStart, jlongArray jEnd, jintArray jAxes,
                        jobjectArray dest, Conv conv)
{
	if (jAxes == nullptr || env->GetArrayLength(jAxes) != 5) {
		pfv::throwRuntime(env, "The axes of a Zarr read must be {x, y, z, c, t}");
		return nullptr;
	}
	if (dest == nullptr) {
		pfv::throwRuntime(env, "No planes to read the Zarr into");
		return nullptr;
	}
	jint axes[5];
	env->GetIntArrayRegion(jAxes, 0, 5, axes);
	const std::vector<uint64_t> start = toVector(env, jStart);
	const std::vector<uint64_t> end = toVector(env, jEnd);
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return nullptr;
	std::string problem;
	void* buf = nullptr;
	try {
		zarr Zarr(fName);
		Window w;
		problem = plan(Zarr, start, end, axes, sizeof(S), w);
		if (problem.empty() && (uint64_t)env->GetArrayLength(dest) != w.planes())
			problem = "Destination planes do not match the image slice count";
		if (problem.empty()) {
			uint8_t fillElem[16];
			fillValueElement(Zarr.get_fill_value(), Zarr.get_dtype(), fillElem);
			uint64_t thickest = 0;
			for (size_t i = 0; i + 1 < w.cuts.size(); i++) thickest = std::max(thickest, w.cuts[i + 1] - w.cuts[i]);
			const uint64_t slabPlanes = w.slabAxis >= 0 ? w.planes() / w.size[w.slabAxis] * thickest : w.planes();
			buf = malloc(w.size[X] * w.size[Y] * slabPlanes * sizeof(S));
			if (buf == nullptr) problem = "Out of native memory reading the Zarr";
			const size_t slabs = w.slabAxis >= 0 ? w.cuts.size() - 1 : 1;
			for (size_t i = 0; i < slabs && problem.empty() && !env->ExceptionCheck(); i++)
				problem = readSlab<S, D>(env, Zarr, w, w.slabAxis >= 0 ? w.cuts[i] : 0, w.slabAxis >= 0 ? w.cuts[i + 1] : 0,
					buf, fillElem, dest, conv);
		}
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
	free(buf);
	env->ReleaseStringUTFChars(fileName, fName);
	if (env->ExceptionCheck()) return nullptr;
	if (!problem.empty()) {
		pfv::throwRuntime(env, problem.c_str());
		return nullptr;
	}
	return dest;
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

template <typename JavaT>
jobjectArray readZarr(JNIEnv* env, jstring fileName, jlongArray start, jlongArray end,
                      jintArray axes, jobjectArray dest)
{
	return readToJava<JavaT, JavaT>(env, fileName, start, end, axes, dest, pfv::Cast<JavaT, JavaT>());
}

// Signed integers: XOR-shift the sign bit into the unsigned range ImageJ
// expects; a Java-side calibration maps the values back.
template <typename UnsignedT, typename JavaT>
jobjectArray readZarrShifted(JNIEnv* env, jstring fileName, jlongArray start, jlongArray end,
	jintArray axes, UnsignedT mask, jobjectArray dest)
{
	return readToJava<UnsignedT, JavaT>(env, fileName, start, end, axes, dest, pfv::XorSign<UnsignedT, JavaT>{ mask });
}

// 32- and 64-bit integers and doubles are converted to float: ImageJ shows
// no other numeric types.
template <typename NativeT>
jobjectArray readZarrAsFloat(JNIEnv* env, jstring fileName, jlongArray start, jlongArray end,
	jintArray axes, jobjectArray dest)
{
	return readToJava<NativeT, float>(env, fileName, start, end, axes, dest, pfv::Cast<NativeT, float>());
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
	return readToJava<uint16_t, float>(env, fileName, start, end, axes, dest, pfv::HalfToFloat{ bfloat16 != JNI_FALSE });
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

// For tests: the least a slab of a big read holds (see plan).
JNIEXPORT void JNICALL Java_edu_abc_berkeley_ParallelReadNative_setZarrSlabBytes
	(JNIEnv*, jclass, jlong bytes)
{
	minSlabBytes = bytes > 0 ? (uint64_t)bytes : 1;
}

// Runs when Java loads this library; see primeUnwinder in jni_marshal.h.
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*)
{
	pfv::primeUnwinder();
	return JNI_VERSION_1_6;
}
