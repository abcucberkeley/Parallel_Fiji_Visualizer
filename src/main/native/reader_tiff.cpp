// TIFF half of libParallelReadC: JNI bindings for edu.abc.berkeley.ParallelReadNative.
// Delegates to cpp-tiff; this translation unit must only see cpp-tiff headers
// (see CMakeLists.txt -- helperfunctions.h clashes between the upstreams).

#include <jni.h>
#include <cstdint>
#include <cstdlib>

#include "parallelreadtiff.h"
#include "helperfunctions.h" // cpp-tiff's

#include "jni_marshal.h"
#include "edu_abc_berkeley_ParallelReadNative.h"

namespace {

// dims[0]*dims[1] = elements per slice, dims[2] = slice count. Stacks written
// by ImageJ keep the true Z in their metadata instead of the directory count.
uint64_t* readDims(const char* fName) {
	uint64_t* dims = getImageSize(fName);
	if (dims != nullptr && isImageJIm(fName)) {
		const uint64_t z = imageJImGetZ(fName);
		if (z) dims[2] = z;
	}
	return dims;
}

template <typename JavaT>
jobjectArray readTiff(JNIEnv* env, jstring fileName) {
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return nullptr;
	void* data = readTiffParallelWrapperNoXYFlip(fName);
	uint64_t* dims = readDims(fName);
	env->ReleaseStringUTFChars(fileName, fName);
	if (data == nullptr || dims == nullptr) {
		free(data);
		free(dims);
		pfv::throwRuntime(env, "Parallel TIFF read failed");
		return nullptr;
	}
	jobjectArray out = pfv::slicesToJava<JavaT>(env, (const JavaT*)data, dims[0] * dims[1], dims[2]);
	free(data);
	free(dims);
	return out;
}

} // namespace

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffUINT8
	(JNIEnv* env, jobject, jstring fileName)
{
	return readTiff<int8_t>(env, fileName);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffUINT16
	(JNIEnv* env, jobject, jstring fileName)
{
	return readTiff<int16_t>(env, fileName);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffFLOAT
	(JNIEnv* env, jobject, jstring fileName)
{
	return readTiff<float>(env, fileName);
}

// 64-bit images are converted to float: ImageStack has no double support.
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffDOUBLE
	(JNIEnv* env, jobject, jstring fileName)
{
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return nullptr;
	double* raw = (double*)readTiffParallelWrapperNoXYFlip(fName);
	uint64_t* dims = readDims(fName);
	env->ReleaseStringUTFChars(fileName, fName);
	if (raw == nullptr || dims == nullptr) {
		free(raw);
		free(dims);
		pfv::throwRuntime(env, "Parallel TIFF read failed");
		return nullptr;
	}
	float* data = pfv::doubleToFloat(raw, dims[0] * dims[1] * dims[2]);
	free(raw);
	if (data == nullptr) {
		free(dims);
		pfv::throwRuntime(env, "Out of native memory converting double image to float");
		return nullptr;
	}
	jobjectArray out = pfv::slicesToJava<float>(env, data, dims[0] * dims[1], dims[2]);
	free(data);
	free(dims);
	return out;
}

// Signed 8-bit images, shifted into 0-255 (a Java-side calibration maps back).
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffINT8
	(JNIEnv* env, jobject, jstring fileName)
{
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return nullptr;
	void* data = readTiffParallelWrapperNoXYFlip(fName);
	uint64_t* dims = readDims(fName);
	env->ReleaseStringUTFChars(fileName, fName);
	if (data == nullptr || dims == nullptr) {
		free(data);
		free(dims);
		pfv::throwRuntime(env, "Parallel TIFF read failed");
		return nullptr;
	}
	pfv::xorShiftInPlace<uint8_t>((uint8_t*)data, dims[0] * dims[1] * dims[2], (uint8_t)0x80u);
	jobjectArray out = pfv::slicesToJava<int8_t>(env, (const int8_t*)data, dims[0] * dims[1], dims[2]);
	free(data);
	free(dims);
	return out;
}

// Signed 16-bit images, shifted into 0-65535 (ImageJ's signed-16 convention).
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffINT16
	(JNIEnv* env, jobject, jstring fileName)
{
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return nullptr;
	void* data = readTiffParallelWrapperNoXYFlip(fName);
	uint64_t* dims = readDims(fName);
	env->ReleaseStringUTFChars(fileName, fName);
	if (data == nullptr || dims == nullptr) {
		free(data);
		free(dims);
		pfv::throwRuntime(env, "Parallel TIFF read failed");
		return nullptr;
	}
	pfv::xorShiftInPlace<uint16_t>((uint16_t*)data, dims[0] * dims[1] * dims[2], (uint16_t)0x8000u);
	jobjectArray out = pfv::slicesToJava<int16_t>(env, (const int16_t*)data, dims[0] * dims[1], dims[2]);
	free(data);
	free(dims);
	return out;
}

namespace {

// 32-bit integer images are converted to float for ImageJ display.
template <typename NativeT>
jobjectArray readTiffAsFloat(JNIEnv* env, jstring fileName) {
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return nullptr;
	NativeT* raw = (NativeT*)readTiffParallelWrapperNoXYFlip(fName);
	uint64_t* dims = readDims(fName);
	env->ReleaseStringUTFChars(fileName, fName);
	if (raw == nullptr || dims == nullptr) {
		free(raw);
		free(dims);
		pfv::throwRuntime(env, "Parallel TIFF read failed");
		return nullptr;
	}
	float* data = pfv::convertToFloat<NativeT>(raw, dims[0] * dims[1] * dims[2]);
	free(raw);
	if (data == nullptr) {
		free(dims);
		pfv::throwRuntime(env, "Out of native memory converting image to float");
		return nullptr;
	}
	jobjectArray out = pfv::slicesToJava<float>(env, data, dims[0] * dims[1], dims[2]);
	free(data);
	free(dims);
	return out;
}

} // namespace

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffINT32
	(JNIEnv* env, jobject, jstring fileName)
{
	return readTiffAsFloat<int32_t>(env, fileName);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffUINT32
	(JNIEnv* env, jobject, jstring fileName)
{
	return readTiffAsFloat<uint32_t>(env, fileName);
}

// ImageJ has no 64-bit integer type; converted to float like doubles are.
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffINT64
	(JNIEnv* env, jobject, jstring fileName)
{
	return readTiffAsFloat<int64_t>(env, fileName);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffUINT64
	(JNIEnv* env, jobject, jstring fileName)
{
	return readTiffAsFloat<uint64_t>(env, fileName);
}

// Chunky (interleaved) 8-bit RGB/RGBA, packed into ImageJ int-based RGB pixels.
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffRGB8
	(JNIEnv* env, jobject, jstring fileName)
{
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return nullptr;
	uint8_t* raw = (uint8_t*)readTiffParallelWrapperNoXYFlip(fName);
	uint64_t* dims = readDims(fName);
	const uint64_t spp = getSamplesPerPixel(fName);
	env->ReleaseStringUTFChars(fileName, fName);
	if (raw == nullptr || dims == nullptr || spp < 3) {
		free(raw);
		free(dims);
		pfv::throwRuntime(env, "Parallel RGB TIFF read failed");
		return nullptr;
	}
	const uint64_t nPixels = dims[0] * dims[1] * dims[2];
	int32_t* data = pfv::packRgbToArgb(raw, nPixels, spp);
	free(raw);
	if (data == nullptr) {
		free(dims);
		pfv::throwRuntime(env, "Out of native memory packing RGB image");
		return nullptr;
	}
	jobjectArray out = pfv::slicesToJava<int32_t>(env, data, dims[0] * dims[1], dims[2]);
	free(data);
	free(dims);
	return out;
}

// 1 = unsigned int, 2 = signed int, 3 = IEEE float.
JNIEXPORT jlong JNICALL Java_edu_abc_berkeley_ParallelReadNative_getTiffSampleFormat
	(JNIEnv* env, jobject, jstring fileName)
{
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return 1;
	const uint64_t fmt = getSampleFormat(fName);
	env->ReleaseStringUTFChars(fileName, fName);
	return (jlong)fmt;
}

// 1 = grayscale, 3 = RGB, 4 = RGBA.
JNIEXPORT jlong JNICALL Java_edu_abc_berkeley_ParallelReadNative_getTiffSamplesPerPixel
	(JNIEnv* env, jobject, jstring fileName)
{
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return 1;
	const uint64_t spp = getSamplesPerPixel(fName);
	env->ReleaseStringUTFChars(fileName, fName);
	return (jlong)spp;
}

JNIEXPORT jlong JNICALL Java_edu_abc_berkeley_ParallelReadNative_getTiffDataType
	(JNIEnv* env, jobject, jstring fileName)
{
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return 0;
	const uint64_t bits = getDataType(fName);
	env->ReleaseStringUTFChars(fileName, fName);
	return (jlong)bits;
}

JNIEXPORT jlongArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_getTiffDims
	(JNIEnv* env, jobject, jstring fileName)
{
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return nullptr;
	uint64_t* dims = getImageSize(fName);
	env->ReleaseStringUTFChars(fileName, fName);
	if (dims == nullptr) {
		pfv::throwRuntime(env, "Could not read TIFF dimensions");
		return nullptr;
	}
	jlongArray rval = env->NewLongArray(3);
	if (rval != nullptr) {
		env->SetLongArrayRegion(rval, 0, 3, reinterpret_cast<const jlong*>(dims));
	}
	free(dims);
	return rval;
}
