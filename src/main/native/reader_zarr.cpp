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

namespace {

// Shared body for the signed integer variants: read raw, XOR-shift the sign
// bit into the unsigned range ImageJ expects, marshal. Requires cpp-zarr
// >= 1.5.2, whose read helper handles every u/i/f dtype generically.
template <typename UnsignedT, typename JavaT>
jobjectArray readZarrShifted(JNIEnv* env, jstring fileName, jlong startX, jlong startY,
	jlong startZ, jlong endX, jlong endY, jlong endZ, UnsignedT mask)
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
	pfv::xorShiftInPlace<UnsignedT>((UnsignedT*)data, dims[0] * dims[1] * dims[2], mask);
	jobjectArray out = pfv::slicesToJava<JavaT>(env, (const JavaT*)data, dims[0] * dims[1], dims[2]);
	free(data);
	return out;
}

// 32-bit integer zarrs are converted to float for ImageJ display.
template <typename NativeT>
jobjectArray readZarrAsFloat(JNIEnv* env, jstring fileName, jlong startX, jlong startY,
	jlong startZ, jlong endX, jlong endY, jlong endZ)
{
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return nullptr;
	NativeT* raw = (NativeT*)readZarrParallelHelper(fName, (uint64_t)startX, (uint64_t)startY,
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
	float* data = pfv::convertToFloat<NativeT>(raw, dims[0] * dims[1] * dims[2]);
	free(raw);
	if (data == nullptr) {
		pfv::throwRuntime(env, "Out of native memory converting zarr to float");
		return nullptr;
	}
	jobjectArray out = pfv::slicesToJava<float>(env, data, dims[0] * dims[1], dims[2]);
	free(data);
	return out;
}

} // namespace

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrINT8
	(JNIEnv* env, jobject, jstring fileName, jlong startX, jlong startY, jlong startZ,
	 jlong endX, jlong endY, jlong endZ)
{
	return readZarrShifted<uint8_t, int8_t>(env, fileName, startX, startY, startZ,
		endX, endY, endZ, (uint8_t)0x80u);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrINT16
	(JNIEnv* env, jobject, jstring fileName, jlong startX, jlong startY, jlong startZ,
	 jlong endX, jlong endY, jlong endZ)
{
	return readZarrShifted<uint16_t, int16_t>(env, fileName, startX, startY, startZ,
		endX, endY, endZ, (uint16_t)0x8000u);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrINT32
	(JNIEnv* env, jobject, jstring fileName, jlong startX, jlong startY, jlong startZ,
	 jlong endX, jlong endY, jlong endZ)
{
	return readZarrAsFloat<int32_t>(env, fileName, startX, startY, startZ, endX, endY, endZ);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrUINT32
	(JNIEnv* env, jobject, jstring fileName, jlong startX, jlong startY, jlong startZ,
	 jlong endX, jlong endY, jlong endZ)
{
	return readZarrAsFloat<uint32_t>(env, fileName, startX, startY, startZ, endX, endY, endZ);
}

// ImageJ has no 64-bit integer type; converted to float like doubles are.
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrINT64
	(JNIEnv* env, jobject, jstring fileName, jlong startX, jlong startY, jlong startZ,
	 jlong endX, jlong endY, jlong endZ)
{
	return readZarrAsFloat<int64_t>(env, fileName, startX, startY, startZ, endX, endY, endZ);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrUINT64
	(JNIEnv* env, jobject, jstring fileName, jlong startX, jlong startY, jlong startZ,
	 jlong endX, jlong endY, jlong endZ)
{
	return readZarrAsFloat<uint64_t>(env, fileName, startX, startY, startZ, endX, endY, endZ);
}

// The numpy-style dtype string from the .zarray metadata, e.g. "<u2" or "<i4".
JNIEXPORT jstring JNICALL Java_edu_abc_berkeley_ParallelReadNative_getZarrDtype
	(JNIEnv* env, jobject, jstring fileName)
{
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return nullptr;
	jstring rval = nullptr;
	try {
		zarr Zarr(fName);
		rval = env->NewStringUTF(Zarr.get_dtype().c_str());
	}
	catch (const std::exception& e) {
		pfv::throwRuntime(env, e.what());
	}
	env->ReleaseStringUTFChars(fileName, fName);
	return rval;
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
