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

// Reads the slices [zStart, zEnd) as stored, (z, y, x[, sample]) row-major,
// after checking that their samples are elemBytes wide. On failure a Java
// exception is pending and nullptr is returned.
void* readSlices(JNIEnv* env, jstring fileName, jlongArray jInfo, jlong zStart, jlong zEnd,
                 uint64_t elemBytes, TiffInfo& info)
{
	if (!infoFromJava(env, jInfo, info)) return nullptr;
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

// Interleaved samples as one plane per sample (plane s + samples*z), in blocks
// so that a single big slice still keeps every core busy
template <typename T>
T* splitSamples(const T* src, uint64_t planeElems, uint64_t slices, uint64_t samples) {
	T* dst = (T*)malloc(planeElems * slices * samples * sizeof(T));
	if (dst == nullptr) return nullptr;
	const uint64_t block = 1u << 16;
	const uint64_t perPlane = (planeElems + block - 1) / block;
	const int64_t total = (int64_t)(slices * samples * perPlane);
	#pragma omp parallel for schedule(static)
	for (int64_t k = 0; k < total; k++) {
		const uint64_t p = (uint64_t)k / perPlane, b = (uint64_t)k % perPlane;
		const uint64_t z = p / samples, s = p % samples;
		const uint64_t i0 = b * block, i1 = i0 + block < planeElems ? i0 + block : planeElems;
		const T* in = src + z * planeElems * samples + s;
		T* out = dst + p * planeElems;
		for (uint64_t i = i0; i < i1; i++) out[i] = in[i * samples];
	}
	return dst;
}

// Hands slices of elements already in Java's type to Java, one plane per
// sample; frees data
template <typename J>
jobjectArray toJava(JNIEnv* env, const TiffInfo& info, J* data, uint64_t slices, jobjectArray dest) {
	const uint64_t planeElems = info.x * info.y;
	if (info.samples > 1) {
		J* split = splitSamples(data, planeElems, slices, info.samples);
		free(data);
		if (split == nullptr) {
			pfv::throwRuntime(env, "Out of native memory splitting the TIFF's samples");
			return nullptr;
		}
		data = split;
	}
	jobjectArray out = pfv::slicesToJava<J>(env, data, planeElems, slices * info.samples, dest);
	free(data);
	return out;
}

template <typename T>
jobjectArray readTiff(JNIEnv* env, jstring fileName, jlongArray jInfo, jlong zStart, jlong zEnd, jobjectArray dest) {
	TiffInfo info;
	T* data = (T*)readSlices(env, fileName, jInfo, zStart, zEnd, sizeof(T), info);
	if (data == nullptr) return nullptr;
	return toJava<T>(env, info, data, (uint64_t)(zEnd - zStart), dest);
}

// Signed integers: XOR-shift the sign bit into the unsigned range ImageJ
// expects; a Java-side calibration maps the values back.
template <typename UnsignedT, typename JavaT>
jobjectArray readTiffShifted(JNIEnv* env, jstring fileName, jlongArray jInfo, jlong zStart, jlong zEnd,
	UnsignedT mask, jobjectArray dest)
{
	TiffInfo info;
	UnsignedT* data = (UnsignedT*)readSlices(env, fileName, jInfo, zStart, zEnd, sizeof(UnsignedT), info);
	if (data == nullptr) return nullptr;
	const uint64_t slices = (uint64_t)(zEnd - zStart);
	pfv::xorShiftInPlace<UnsignedT>(data, info.x * info.y * info.samples * slices, mask);
	return toJava<JavaT>(env, info, (JavaT*)data, slices, dest);
}

// 32- and 64-bit integers and doubles are converted to float: ImageJ shows
// no other numeric types.
template <typename NativeT>
jobjectArray readTiffAsFloat(JNIEnv* env, jstring fileName, jlongArray jInfo, jlong zStart, jlong zEnd, jobjectArray dest) {
	TiffInfo info;
	NativeT* raw = (NativeT*)readSlices(env, fileName, jInfo, zStart, zEnd, sizeof(NativeT), info);
	if (raw == nullptr) return nullptr;
	const uint64_t slices = (uint64_t)(zEnd - zStart);
	float* data = pfv::convertToFloat<NativeT>(raw, info.x * info.y * info.samples * slices);
	free(raw);
	if (data == nullptr) {
		pfv::throwRuntime(env, "Out of native memory converting the TIFF to float");
		return nullptr;
	}
	return toJava<float>(env, info, data, slices, dest);
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
	uint8_t* raw = (uint8_t*)readSlices(env, fileName, info, zStart, zEnd, 1, tinfo);
	if (raw == nullptr) return nullptr;
	if (tinfo.samples < 3) {
		free(raw);
		pfv::throwRuntime(env, "The TIFF is not an RGB image");
		return nullptr;
	}
	const uint64_t slices = (uint64_t)(zEnd - zStart);
	int32_t* data = pfv::packRgbToArgb(raw, tinfo.x * tinfo.y * slices, tinfo.samples);
	free(raw);
	if (data == nullptr) {
		pfv::throwRuntime(env, "Out of native memory packing RGB image");
		return nullptr;
	}
	jobjectArray out = pfv::slicesToJava<int32_t>(env, data, tinfo.x * tinfo.y, slices, dest);
	free(data);
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
