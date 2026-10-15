// TIFF half of libParallelReadC: JNI bindings for edu.abc.berkeley.ParallelReadNative.
// Delegates to cpp-tiff; this translation unit must only see cpp-tiff headers
// (see CMakeLists.txt -- helperfunctions.h clashes between the upstreams).
//
// getTiffInfo describes a file from one open and one walk of its directories
// (cpp-tiff's TiffInfo, as a long[] for Java). Every read takes that
// description back, so reading a few slices of a deep stack does not walk the
// directories again. A read returns ImageJ planes for the file's slices
// [zStart, zEnd): one per slice, or for images with several samples per pixel
// one per sample, a slice's samples one after another (as ImageJ orders
// channels). 8-bit color is instead packed into ImageJ RGB pixels by RGB8.

#include <jni.h>
#include <cstdint>
#include <cstdlib>
#include <vector>

#include "parallelreadtiff.h"
#include "helperfunctions.h" // cpp-tiff's

#include "jni_marshal.h"
#include "edu_abc_berkeley_ParallelReadNative.h"

namespace {

// TiffInfo's fields in the order of Java's long[] (ParallelReadNative.TIFF_*)
enum { I_WIDTH, I_HEIGHT, I_SLICES, I_DIRECTORIES, I_BITS, I_SAMPLES, I_SAMPLE_FORMAT, I_STRIP_SIZE,
	I_COMPRESSION, I_PLANAR, I_TILED, I_IMAGEJ, I_IMAGEJ_IMAGES, I_COUNT };

bool infoFromJava(JNIEnv* env, jlongArray a, TiffInfo& info) {
	if (a == nullptr || env->GetArrayLength(a) != I_COUNT) {
		pfv::throwRuntime(env, "Invalid TIFF description: get it from getTiffInfo");
		return false;
	}
	jlong v[I_COUNT];
	env->GetLongArrayRegion(a, 0, I_COUNT, v);
	info.x = (uint64_t)v[I_WIDTH];
	info.y = (uint64_t)v[I_HEIGHT];
	info.z = (uint64_t)v[I_SLICES];
	info.directories = (uint64_t)v[I_DIRECTORIES];
	info.bits = (uint64_t)v[I_BITS];
	info.samples = (uint64_t)v[I_SAMPLES];
	info.sampleFormat = (uint16_t)v[I_SAMPLE_FORMAT];
	info.stripSize = (uint64_t)v[I_STRIP_SIZE];
	info.compression = (uint16_t)v[I_COMPRESSION];
	info.planar = v[I_PLANAR] != 0;
	info.tiled = v[I_TILED] != 0;
	info.imageJ = v[I_IMAGEJ] != 0;
	info.imageJImages = (uint64_t)v[I_IMAGEJ_IMAGES];
	return true;
}

// Threads for reading slices [zStart, zEnd): about one per MiB. Every core on
// a small file is slower than a few (300 2048x2048 16-bit files: 1.08 s with
// 48 threads, 0.32 s with 6), as each thread opens the file.
uint64_t threadsFor(const TiffInfo& info, jlong zStart, jlong zEnd) {
	const uint64_t slices = zEnd > zStart ? (uint64_t)(zEnd - zStart) : 1;
	const uint64_t bytes = slices * info.x * info.y * info.samples * (info.bits / 8);
	return (bytes + (1u << 20) - 1) >> 20;
}

// Reads the slices [zStart, zEnd) as stored, (z, y, x[, sample]) row-major,
// after checking that their samples are elemBytes wide. On failure a Java
// exception is pending and nullptr is returned.
void* readSlices(JNIEnv* env, jstring fileName, const TiffInfo& info, jlong zStart, jlong zEnd, uint64_t elemBytes)
{
	if (zStart < 0 || zEnd <= zStart || (uint64_t)zEnd > info.z) {
		pfv::throwRuntime(env, "The slices to read lie outside the TIFF");
		return nullptr;
	}
	if (info.bits != elemBytes * 8) {
		pfv::throwRuntime(env, "The TIFF's data type does not match the read");
		return nullptr;
	}
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return nullptr;
	void* raw = readTiffParallelInfo(fName, info, nullptr, 0, { (uint64_t)zStart, (uint64_t)zEnd });
	env->ReleaseStringUTFChars(fileName, fName);
	if (raw == nullptr) pfv::throwRuntime(env, "Parallel TIFF read failed (is the file damaged?)");
	return raw;
}

// Hands slices read as stored ((z, y, x[, sample]) row-major) to Java as D,
// one plane per slice, or one per sample of each slice (plane s + samples*z),
// each element converted by conv on its way; frees raw. The samples are split
// straight into Java's arrays, with no intermediate copy of the image.
template <typename S, typename D, typename Conv>
jobjectArray toJava(JNIEnv* env, const TiffInfo& info, void* raw, uint64_t slices, jobjectArray dest, Conv conv) {
	const uint64_t samples = info.samples, n = info.x * info.y;
	std::vector<uint64_t> base(slices * samples);
	for (uint64_t p = 0; p < base.size(); p++) base[p] = (p / samples) * n * samples + p % samples;
	const pfv::PlaneLayout layout = { info.x, info.y, samples, info.x * samples, base.data() };
	const S* src = (const S*)raw;
	jobjectArray out = pfv::slicesToJavaWith<D>(env, n, base.size(), dest,
		[&](D* const* dst, uint64_t first, uint64_t count) { pfv::fillPlanes<S, D>(dst, first, count, src, layout, conv); });
	free(raw);
	return out;
}

template <typename T>
jobjectArray readTiff(JNIEnv* env, jstring fileName, jlongArray jInfo, jlong zStart, jlong zEnd, jobjectArray dest) {
	TiffInfo info;
	if (!infoFromJava(env, jInfo, info)) return nullptr;
	const pfv::ThreadCap cap(threadsFor(info, zStart, zEnd));
	void* raw = readSlices(env, fileName, info, zStart, zEnd, sizeof(T));
	if (raw == nullptr) return nullptr;
	return toJava<T, T>(env, info, raw, (uint64_t)(zEnd - zStart), dest, pfv::Cast<T, T>());
}

// Signed integers: XOR-shift the sign bit into the unsigned range ImageJ
// expects; a Java-side calibration maps the values back.
template <typename UnsignedT, typename JavaT>
jobjectArray readTiffShifted(JNIEnv* env, jstring fileName, jlongArray jInfo, jlong zStart, jlong zEnd,
	UnsignedT mask, jobjectArray dest)
{
	TiffInfo info;
	if (!infoFromJava(env, jInfo, info)) return nullptr;
	const pfv::ThreadCap cap(threadsFor(info, zStart, zEnd));
	void* raw = readSlices(env, fileName, info, zStart, zEnd, sizeof(UnsignedT));
	if (raw == nullptr) return nullptr;
	return toJava<UnsignedT, JavaT>(env, info, raw, (uint64_t)(zEnd - zStart), dest, pfv::XorSign<UnsignedT, JavaT>{ mask });
}

// 32- and 64-bit integers and doubles are converted to float: ImageJ shows
// no other numeric types.
template <typename NativeT>
jobjectArray readTiffAsFloat(JNIEnv* env, jstring fileName, jlongArray jInfo, jlong zStart, jlong zEnd, jobjectArray dest) {
	TiffInfo info;
	if (!infoFromJava(env, jInfo, info)) return nullptr;
	const pfv::ThreadCap cap(threadsFor(info, zStart, zEnd));
	void* raw = readSlices(env, fileName, info, zStart, zEnd, sizeof(NativeT));
	if (raw == nullptr) return nullptr;
	return toJava<NativeT, float>(env, info, raw, (uint64_t)(zEnd - zStart), dest, pfv::Cast<NativeT, float>());
}

} // namespace

#define PFV_TIFF_READ_ARGS (JNIEnv* env, jobject, jstring fileName, jlongArray info, jlong zStart, jlong zEnd, jobjectArray dest)

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffUINT8 PFV_TIFF_READ_ARGS
{
	return readTiff<int8_t>(env, fileName, info, zStart, zEnd, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffUINT16 PFV_TIFF_READ_ARGS
{
	return readTiff<int16_t>(env, fileName, info, zStart, zEnd, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffFLOAT PFV_TIFF_READ_ARGS
{
	return readTiff<float>(env, fileName, info, zStart, zEnd, dest);
}

// 64-bit images are converted to float: ImageStack has no double support.
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffDOUBLE PFV_TIFF_READ_ARGS
{
	return readTiffAsFloat<double>(env, fileName, info, zStart, zEnd, dest);
}

// Signed 8-bit images, shifted into 0-255 (a Java-side calibration maps back).
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffINT8 PFV_TIFF_READ_ARGS
{
	return readTiffShifted<uint8_t, int8_t>(env, fileName, info, zStart, zEnd, (uint8_t)0x80u, dest);
}

// Signed 16-bit images, shifted into 0-65535 (ImageJ's signed-16 convention).
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffINT16 PFV_TIFF_READ_ARGS
{
	return readTiffShifted<uint16_t, int16_t>(env, fileName, info, zStart, zEnd, (uint16_t)0x8000u, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffINT32 PFV_TIFF_READ_ARGS
{
	return readTiffAsFloat<int32_t>(env, fileName, info, zStart, zEnd, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffUINT32 PFV_TIFF_READ_ARGS
{
	return readTiffAsFloat<uint32_t>(env, fileName, info, zStart, zEnd, dest);
}

// ImageJ has no 64-bit integer type; converted to float like doubles are.
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffINT64 PFV_TIFF_READ_ARGS
{
	return readTiffAsFloat<int64_t>(env, fileName, info, zStart, zEnd, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffUINT64 PFV_TIFF_READ_ARGS
{
	return readTiffAsFloat<uint64_t>(env, fileName, info, zStart, zEnd, dest);
}

// 8-bit RGB/RGBA (interleaved or planar), packed into ImageJ int-based RGB
// pixels: one plane per slice.
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffRGB8 PFV_TIFF_READ_ARGS
{
	TiffInfo tinfo;
	if (!infoFromJava(env, info, tinfo)) return nullptr;
	const pfv::ThreadCap cap(threadsFor(tinfo, zStart, zEnd));
	uint8_t* raw = (uint8_t*)readSlices(env, fileName, tinfo, zStart, zEnd, 1);
	if (raw == nullptr) return nullptr;
	if (tinfo.samples < 3) {
		free(raw);
		pfv::throwRuntime(env, "The TIFF is not an RGB image");
		return nullptr;
	}
	// Packed into ImageJ RGB pixels (0xff000000 | r<<16 | g<<8 | b; alpha, if
	// present, dropped) straight into Java's arrays
	const uint64_t slices = (uint64_t)(zEnd - zStart), n = tinfo.x * tinfo.y, samples = tinfo.samples;
	const uint8_t* src = raw;
	jobjectArray out = pfv::slicesToJavaWith<int32_t>(env, n, slices, dest, [&](int32_t* const* dst, uint64_t first, uint64_t count) {
		const uint64_t block = 1u << 16, per = (n + block - 1) / block;
		const int64_t total = (int64_t)(count * per);
		#pragma omp parallel for schedule(static) if(count * n * 4 > (1u << 20))
		for (int64_t k = 0; k < total; k++) {
			const uint64_t p = (uint64_t)k / per, i0 = ((uint64_t)k % per) * block, i1 = i0 + block < n ? i0 + block : n;
			const uint8_t* s = src + (first + p) * n * samples;
			int32_t* d = dst[p];
			for (uint64_t i = i0; i < i1; i++) {
				const uint8_t* q = s + i * samples;
				d[i] = (int32_t)(0xFF000000u | ((uint32_t)q[0] << 16) | ((uint32_t)q[1] << 8) | (uint32_t)q[2]);
			}
		}
	});
	free(raw);
	return out;
}

// One open and one walk of the file's directories; see the top of this file.
JNIEXPORT jlongArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_getTiffInfo
	(JNIEnv* env, jobject, jstring fileName)
{
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return nullptr;
	TiffInfo info;
	const uint8_t failed = getTiffInfo(fName, info);
	env->ReleaseStringUTFChars(fileName, fName);
	if (failed) {
		pfv::throwRuntime(env, "The file cannot be opened as a TIFF");
		return nullptr;
	}
	jlong v[I_COUNT];
	v[I_WIDTH] = (jlong)info.x;
	v[I_HEIGHT] = (jlong)info.y;
	v[I_SLICES] = (jlong)info.z;
	v[I_DIRECTORIES] = (jlong)info.directories;
	v[I_BITS] = (jlong)info.bits;
	v[I_SAMPLES] = (jlong)info.samples;
	v[I_SAMPLE_FORMAT] = (jlong)info.sampleFormat;
	v[I_STRIP_SIZE] = (jlong)info.stripSize;
	v[I_COMPRESSION] = (jlong)info.compression;
	v[I_PLANAR] = info.planar ? 1 : 0;
	v[I_TILED] = info.tiled ? 1 : 0;
	v[I_IMAGEJ] = info.imageJ ? 1 : 0;
	v[I_IMAGEJ_IMAGES] = (jlong)info.imageJImages;
	jlongArray out = env->NewLongArray(I_COUNT);
	if (out != nullptr) env->SetLongArrayRegion(out, 0, I_COUNT, v);
	return out;
}
