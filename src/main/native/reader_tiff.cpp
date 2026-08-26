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
