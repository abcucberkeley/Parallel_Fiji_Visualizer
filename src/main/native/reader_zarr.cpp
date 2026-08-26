// Zarr half of libParallelReadC: JNI bindings for edu.abc.berkeley.ParallelReadNative.
// Delegates to cpp-zarr; this translation unit must only see cpp-zarr headers
// (see CMakeLists.txt -- helperfunctions.h clashes between the upstreams).

#include <jni.h>
#include <cstdint>
#include <cstdlib>
#include <exception>

#include "parallelreadzarr.h"
#include "zarr.h"

#include "jni_marshal.h"
#include "edu_abc_berkeley_ParallelReadNative.h"

namespace {

template <typename JavaT>
jobjectArray readZarr(JNIEnv* env, jstring fileName, jlong startX, jlong startY,
                      jlong startZ, jlong endX, jlong endY, jlong endZ)
{
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return nullptr;
	void* data = readZarrParallelHelper(fName, (uint64_t)startX, (uint64_t)startY,
		(uint64_t)startZ, (uint64_t)endX, (uint64_t)endY, (uint64_t)endZ, 1);
	env->ReleaseStringUTFChars(fileName, fName);
	const uint64_t dims[3] = {
		(uint64_t)(endX - startX),
		(uint64_t)(endY - startY),
		(uint64_t)(endZ - startZ)
	};
	if (data == nullptr) {
		pfv::throwRuntime(env, "Parallel Zarr read failed");
		return nullptr;
	}
	jobjectArray out = pfv::slicesToJava<JavaT>(env, (const JavaT*)data, dims[0] * dims[1], dims[2]);
	free(data);
	return out;
}

} // namespace

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrUINT8
	(JNIEnv* env, jobject, jstring fileName, jlong startX, jlong startY, jlong startZ,
	 jlong endX, jlong endY, jlong endZ)
{
	return readZarr<int8_t>(env, fileName, startX, startY, startZ, endX, endY, endZ);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrUINT16
	(JNIEnv* env, jobject, jstring fileName, jlong startX, jlong startY, jlong startZ,
	 jlong endX, jlong endY, jlong endZ)
{
	return readZarr<int16_t>(env, fileName, startX, startY, startZ, endX, endY, endZ);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrFLOAT
	(JNIEnv* env, jobject, jstring fileName, jlong startX, jlong startY, jlong startZ,
	 jlong endX, jlong endY, jlong endZ)
{
	return readZarr<float>(env, fileName, startX, startY, startZ, endX, endY, endZ);
}

// 64-bit images are converted to float: ImageStack has no double support.
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrDOUBLE
	(JNIEnv* env, jobject, jstring fileName, jlong startX, jlong startY, jlong startZ,
	 jlong endX, jlong endY, jlong endZ)
{
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return nullptr;
	double* raw = (double*)readZarrParallelHelper(fName, (uint64_t)startX, (uint64_t)startY,
		(uint64_t)startZ, (uint64_t)endX, (uint64_t)endY, (uint64_t)endZ, 1);
	env->ReleaseStringUTFChars(fileName, fName);
	const uint64_t dims[3] = {
		(uint64_t)(endX - startX),
		(uint64_t)(endY - startY),
		(uint64_t)(endZ - startZ)
	};
	if (raw == nullptr) {
		pfv::throwRuntime(env, "Parallel Zarr read failed");
		return nullptr;
	}
	float* data = pfv::doubleToFloat(raw, dims[0] * dims[1] * dims[2]);
	free(raw);
	if (data == nullptr) {
		pfv::throwRuntime(env, "Out of native memory converting double image to float");
		return nullptr;
	}
	jobjectArray out = pfv::slicesToJava<float>(env, data, dims[0] * dims[1], dims[2]);
	free(data);
	return out;
}

JNIEXPORT jlong JNICALL Java_edu_abc_berkeley_ParallelReadNative_getZarrDataType
	(JNIEnv* env, jobject, jstring fileName)
{
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return 0;
	jlong bits = 0;
	try {
		zarr Zarr(fName);
		bits = (jlong)(Zarr.dtypeBytes() * 8);
	}
	catch (const std::exception& e) {
		pfv::throwRuntime(env, e.what());
	}
	env->ReleaseStringUTFChars(fileName, fName);
	return bits;
}

JNIEXPORT jlongArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_getZarrDims
	(JNIEnv* env, jobject, jstring fileName)
{
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return nullptr;
	jlongArray rval = nullptr;
	try {
		zarr Zarr(fName);
		const uint64_t dims[3] = { Zarr.get_shape(0), Zarr.get_shape(1), Zarr.get_shape(2) };
		rval = env->NewLongArray(3);
		if (rval != nullptr) {
			env->SetLongArrayRegion(rval, 0, 3, reinterpret_cast<const jlong*>(dims));
		}
	}
	catch (const std::exception& e) {
		pfv::throwRuntime(env, e.what());
	}
	env->ReleaseStringUTFChars(fileName, fName);
	return rval;
}
