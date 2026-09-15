#ifndef PFV_JNI_MARSHAL_H
#define PFV_JNI_MARSHAL_H

#include <jni.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <vector>

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
};

template <> struct JniArrayTraits<int16_t> {
	using ArrayType = jshortArray;
	static constexpr const char* sliceClass = "[S";
	static ArrayType newArray(JNIEnv* env, jsize n) { return env->NewShortArray(n); }
};

template <> struct JniArrayTraits<float> {
	using ArrayType = jfloatArray;
	static constexpr const char* sliceClass = "[F";
	static ArrayType newArray(JNIEnv* env, jsize n) { return env->NewFloatArray(n); }
};

template <> struct JniArrayTraits<int32_t> {
	using ArrayType = jintArray;
	static constexpr const char* sliceClass = "[I";
	static ArrayType newArray(JNIEnv* env, jsize n) { return env->NewIntArray(n); }
};

// Throw a java.lang.RuntimeException (no-op if an exception is already pending).
inline void throwRuntime(JNIEnv* env, const char* msg) {
	if (env->ExceptionCheck()) return;
	jclass cls = env->FindClass("java/lang/RuntimeException");
	if (cls != nullptr) env->ThrowNew(cls, msg);
}

// Copy n equally sized planes with all cores in ONE parallel region. Forking
// a region costs a thread wake-up (milliseconds once the OpenMP threads have
// gone to sleep), which dwarfed the copy of a 2 MB plane when done per plane:
// a 10000-slice stack spent ~30 s in wake-ups. Callers therefore batch planes.
// One core here copies at ~1.7 GB/s while 32 reach ~15 GB/s; tiny batches
// stay serial.
inline void copyPlanes(void* const* dst, const void* const* src, int64_t n, uint64_t bytes) {
	if (n <= 0 || bytes == 0) return;
	if ((uint64_t)n * bytes <= (1u << 20)) {
		for (int64_t k = 0; k < n; k++) memcpy(dst[k], src[k], (size_t)bytes);
		return;
	}
	const uint64_t chunk = 256u << 10;
	const int64_t chunksPerPlane = (int64_t)((bytes + chunk - 1) / chunk);
	const int64_t total = n * chunksPerPlane;
	#pragma omp parallel for schedule(static)
	for (int64_t c = 0; c < total; c++) {
		const int64_t k = c / chunksPerPlane;
		const uint64_t a = (uint64_t)(c % chunksPerPlane) * chunk;
		const uint64_t b = a + chunk < bytes ? a + chunk : bytes;
		memcpy((char*)dst[k] + a, (const char*)src[k] + a, (size_t)(b - a));
	}
}

// Planes per batch: about 256 MB, at least one plane, at most 4096 planes
// (each batch member holds a JNI local reference while pinned).
inline uint64_t planesPerBatch(uint64_t sliceBytes) {
	const uint64_t target = 256u << 20;
	uint64_t n = sliceBytes == 0 ? 4096 : target / sliceBytes;
	if (n < 1) n = 1;
	if (n > 4096) n = 4096;
	return n;
}

// Build a T[nSlices][sliceElems] Java array from a contiguous native buffer.
// Slices are allocated a batch at a time, pinned with GetPrimitiveArrayCritical
// (nesting is allowed), filled by one parallel copy and released; nothing but
// the critical get/release runs while a batch is pinned. Set*ArrayRegion was
// single-threaded and could not take more than INT_MAX/2 elements per call.
// With dest (a T[nSlices][sliceElems] allocated by the caller, e.g. from all
// cores at once) the planes are filled in place; a dest that does not match
// the image is rejected with an exception rather than written past.
template <typename T>
jobjectArray slicesToJava(JNIEnv* env, const T* data, uint64_t sliceElems, uint64_t nSlices,
                          jobjectArray dest = nullptr) {
	using Traits = JniArrayTraits<T>;
	jobjectArray outer = dest;
	if (dest == nullptr) {
		jclass sliceCls = env->FindClass(Traits::sliceClass);
		if (sliceCls == nullptr) return nullptr;
		outer = env->NewObjectArray((jsize)nSlices, sliceCls, nullptr);
		env->DeleteLocalRef(sliceCls);
		if (outer == nullptr) return nullptr;
	}
	else if ((uint64_t)env->GetArrayLength(dest) != nSlices) {
		throwRuntime(env, "Destination planes do not match the image slice count");
		return nullptr;
	}
	const uint64_t sliceBytes = sliceElems * sizeof(T);
	const uint64_t perBatch = planesPerBatch(sliceBytes);
	std::vector<typename Traits::ArrayType> arrays;
	std::vector<void*> dst;
	std::vector<const void*> src;
	for (uint64_t start = 0; start < nSlices; start += perBatch) {
		const uint64_t end = start + perBatch < nSlices ? start + perBatch : nSlices;
		arrays.clear(); dst.clear(); src.clear();
		if (env->EnsureLocalCapacity((jint)(end - start) + 4) < 0) return nullptr;
		for (uint64_t i = start; i < end; i++) {
			typename Traits::ArrayType slice;
			if (dest == nullptr) {
				slice = Traits::newArray(env, (jsize)sliceElems);
				if (slice == nullptr) { // OutOfMemoryError already pending
					for (auto a : arrays) env->DeleteLocalRef(a);
					return nullptr;
				}
				env->SetObjectArrayElement(outer, (jsize)i, slice);
			}
			else {
				slice = (typename Traits::ArrayType)env->GetObjectArrayElement(dest, (jsize)i);
				if (slice == nullptr || (uint64_t)env->GetArrayLength(slice) != sliceElems) {
					if (slice != nullptr) env->DeleteLocalRef(slice);
					for (auto a : arrays) env->DeleteLocalRef(a);
					throwRuntime(env, "Destination plane has the wrong size for the image");
					return nullptr;
				}
			}
			arrays.push_back(slice);
		}
		for (size_t k = 0; k < arrays.size(); k++) {
			void* p = env->GetPrimitiveArrayCritical(arrays[k], nullptr);
			if (p == nullptr) {
				for (size_t j = 0; j < dst.size(); j++) env->ReleasePrimitiveArrayCritical(arrays[j], dst[j], 0);
				for (auto a : arrays) env->DeleteLocalRef(a);
				return nullptr;
			}
			dst.push_back(p);
			src.push_back(data + (start + k) * sliceElems);
		}
		copyPlanes(dst.data(), src.data(), (int64_t)dst.size(), sliceBytes);
		for (size_t k = 0; k < dst.size(); k++) env->ReleasePrimitiveArrayCritical(arrays[k], dst[k], 0);
		for (auto a : arrays) env->DeleteLocalRef(a);
	}
	return outer;
}

// Copy a Java T[nSlices][sliceElems] into one contiguous native buffer
// (malloc'd; the caller frees). elemBytes is the element size (1, 2 or 4);
// the copy itself is type-agnostic. Batched and pinned like slicesToJava.
inline void* slicesToNative(JNIEnv* env, jobjectArray slices, uint64_t sliceElems,
                            uint64_t nSlices, uint64_t elemBytes) {
	const uint64_t sliceBytes = sliceElems * elemBytes;
	void* buf = malloc(sliceBytes * nSlices);
	if (buf == nullptr) return nullptr;
	const uint64_t perBatch = planesPerBatch(sliceBytes);
	std::vector<jarray> arrays;
	std::vector<const void*> src;
	std::vector<void*> dst;
	for (uint64_t start = 0; start < nSlices; start += perBatch) {
		const uint64_t end = start + perBatch < nSlices ? start + perBatch : nSlices;
		arrays.clear(); src.clear(); dst.clear();
		if (env->EnsureLocalCapacity((jint)(end - start) + 4) < 0) { free(buf); return nullptr; }
		for (uint64_t i = start; i < end; i++) {
			jarray slice = (jarray)env->GetObjectArrayElement(slices, (jsize)i);
			if (slice == nullptr) {
				for (auto a : arrays) env->DeleteLocalRef(a);
				free(buf);
				return nullptr;
			}
			arrays.push_back(slice);
		}
		for (size_t k = 0; k < arrays.size(); k++) {
			void* p = env->GetPrimitiveArrayCritical(arrays[k], nullptr);
			if (p == nullptr) {
				for (size_t j = 0; j < src.size(); j++) env->ReleasePrimitiveArrayCritical(arrays[j], (void*)src[j], JNI_ABORT);
				for (auto a : arrays) env->DeleteLocalRef(a);
				free(buf);
				return nullptr;
			}
			src.push_back(p);
			dst.push_back((char*)buf + (start + k) * sliceBytes);
		}
		copyPlanes(dst.data(), src.data(), (int64_t)src.size(), sliceBytes);
		for (size_t k = 0; k < src.size(); k++) env->ReleasePrimitiveArrayCritical(arrays[k], (void*)src[k], JNI_ABORT);
		for (auto a : arrays) env->DeleteLocalRef(a);
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
