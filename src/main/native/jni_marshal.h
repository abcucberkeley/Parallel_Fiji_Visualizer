#ifndef PFV_JNI_MARSHAL_H
#define PFV_JNI_MARSHAL_H

#include <jni.h>
#include <climits>
#include <cstdint>
#include <cstdlib>
#include <cstring>

// Shared helpers for moving image data between JNI arrays and native buffers.
// Header-only on purpose: it must not include anything from cpp-tiff or
// cpp-zarr -- both install a helperfunctions.h with the same include guard, so
// each translation unit may only see one upstream's headers (see CMakeLists).

namespace pfv {

template <typename T> struct JniArrayTraits;

template <> struct JniArrayTraits<int8_t> {
	using ArrayType = jbyteArray;
	static constexpr const char* sliceClass = "[B";
	static ArrayType newArray(JNIEnv* env, jsize n) { return env->NewByteArray(n); }
	static void setRegion(JNIEnv* env, ArrayType a, jsize start, jsize len, const int8_t* src) {
		env->SetByteArrayRegion(a, start, len, src);
	}
};

template <> struct JniArrayTraits<int16_t> {
	using ArrayType = jshortArray;
	static constexpr const char* sliceClass = "[S";
	static ArrayType newArray(JNIEnv* env, jsize n) { return env->NewShortArray(n); }
	static void setRegion(JNIEnv* env, ArrayType a, jsize start, jsize len, const int16_t* src) {
		env->SetShortArrayRegion(a, start, len, src);
	}
};

template <> struct JniArrayTraits<float> {
	using ArrayType = jfloatArray;
	static constexpr const char* sliceClass = "[F";
	static ArrayType newArray(JNIEnv* env, jsize n) { return env->NewFloatArray(n); }
	static void setRegion(JNIEnv* env, ArrayType a, jsize start, jsize len, const float* src) {
		env->SetFloatArrayRegion(a, start, len, src);
	}
};

template <> struct JniArrayTraits<int32_t> {
	using ArrayType = jintArray;
	static constexpr const char* sliceClass = "[I";
	static ArrayType newArray(JNIEnv* env, jsize n) { return env->NewIntArray(n); }
	static void setRegion(JNIEnv* env, ArrayType a, jsize start, jsize len, const int32_t* src) {
		env->SetIntArrayRegion(a, start, len, src);
	}
};

// Throw a java.lang.RuntimeException (no-op if an exception is already pending).
inline void throwRuntime(JNIEnv* env, const char* msg) {
	if (env->ExceptionCheck()) return;
	jclass cls = env->FindClass("java/lang/RuntimeException");
	if (cls != nullptr) env->ThrowNew(cls, msg);
}

// Build a T[nSlices][sliceElems] Java array from a contiguous native buffer.
// Very large slices are copied in three batches: a single Set*ArrayRegion of
// more than INT_MAX/2 elements fails, so the historical batching is kept.
template <typename T>
jobjectArray slicesToJava(JNIEnv* env, const T* data, uint64_t sliceElems, uint64_t nSlices) {
	using Traits = JniArrayTraits<T>;
	jclass sliceCls = env->FindClass(Traits::sliceClass);
	if (sliceCls == nullptr) return nullptr;
	jobjectArray outer = env->NewObjectArray((jsize)nSlices, sliceCls, nullptr);
	env->DeleteLocalRef(sliceCls);
	if (outer == nullptr) return nullptr;
	for (uint64_t i = 0; i < nSlices; i++) {
		typename Traits::ArrayType slice = Traits::newArray(env, (jsize)sliceElems);
		if (slice == nullptr) return nullptr; // OutOfMemoryError already pending
		const T* src = data + i * sliceElems;
		if (sliceElems <= (uint64_t)(INT_MAX / 2)) {
			Traits::setRegion(env, slice, 0, (jsize)sliceElems, src);
		}
		else {
			const int32_t batch = (int32_t)((sliceElems - 1) / 3 + 1);
			for (int32_t j = 0; j < 3; j++) {
				int32_t len = batch;
				if ((uint64_t)(j + 1) * (uint64_t)batch > sliceElems) {
					len = (int32_t)(sliceElems - (uint64_t)j * (uint64_t)batch);
				}
				Traits::setRegion(env, slice, j * batch, len, src + (uint64_t)j * (uint64_t)batch);
			}
		}
		env->SetObjectArrayElement(outer, (jsize)i, slice);
		env->DeleteLocalRef(slice);
	}
	return outer;
}

// Copy a Java T[nSlices][sliceElems] into one contiguous native buffer
// (malloc'd; the caller frees). elemBytes is the element size (1, 2 or 4);
// the copy itself is type-agnostic.
inline void* slicesToNative(JNIEnv* env, jobjectArray slices, uint64_t sliceElems,
                            uint64_t nSlices, uint64_t elemBytes) {
	const uint64_t sliceBytes = sliceElems * elemBytes;
	void* buf = malloc(sliceBytes * nSlices);
	if (buf == nullptr) return nullptr;
	for (uint64_t i = 0; i < nSlices; i++) {
		jarray slice = (jarray)env->GetObjectArrayElement(slices, (jsize)i);
		if (slice == nullptr) { free(buf); return nullptr; }
		void* elems = env->GetPrimitiveArrayCritical(slice, nullptr);
		if (elems == nullptr) { env->DeleteLocalRef(slice); free(buf); return nullptr; }
		memcpy((char*)buf + i * sliceBytes, elems, sliceBytes);
		env->ReleasePrimitiveArrayCritical(slice, elems, JNI_ABORT);
		env->DeleteLocalRef(slice);
	}
	return buf;
}

// Convert a numeric buffer to float (ImageStack displays 32-bit data as float).
template <typename S>
inline float* convertToFloat(const S* src, uint64_t n) {
	float* dst = (float*)malloc(n * sizeof(float));
	if (dst == nullptr) return nullptr;
	#pragma omp parallel for
	for (int64_t i = 0; i < (int64_t)n; i++) {
		dst[i] = (float)src[i];
	}
	return dst;
}

// Convert a double buffer to float (ImageStack has no double support).
inline float* doubleToFloat(const double* src, uint64_t n) {
	return convertToFloat<double>(src, n);
}

// XOR-shift signed samples into the unsigned range ImageJ expects: +128 for
// int8 (mask 0x80) and +32768 for int16 (mask 0x8000) are bitwise-identical
// to flipping the sign bit. A calibration on the Java side maps values back.
template <typename T>
inline void xorShiftInPlace(T* data, uint64_t n, T mask) {
	#pragma omp parallel for
	for (int64_t i = 0; i < (int64_t)n; i++) {
		data[i] ^= mask;
	}
}

// Pack chunky (interleaved) 8-bit RGB or RGBA samples into ImageJ's int-based
// RGB pixels (0xff000000 | r<<16 | g<<8 | b); alpha, if present, is dropped.
inline int32_t* packRgbToArgb(const uint8_t* src, uint64_t nPixels, uint64_t samplesPerPixel) {
	int32_t* dst = (int32_t*)malloc(nPixels * sizeof(int32_t));
	if (dst == nullptr) return nullptr;
	#pragma omp parallel for
	for (int64_t i = 0; i < (int64_t)nPixels; i++) {
		const uint8_t* p = src + i * samplesPerPixel;
		dst[i] = (int32_t)(0xFF000000u | ((uint32_t)p[0] << 16) | ((uint32_t)p[1] << 8) | (uint32_t)p[2]);
	}
	return dst;
}

} // namespace pfv

#endif
