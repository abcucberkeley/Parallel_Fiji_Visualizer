// Zarr half of libParallelWriteC: JNI bindings for edu.abc.berkeley.ParallelWriteNative.
// Delegates to cpp-zarr; this translation unit must only see cpp-zarr headers
// (see CMakeLists.txt -- helperfunctions.h clashes between the upstreams).

#include <jni.h>
#include <cstdint>
#include <cstdlib>
#include <exception>
#include <string>
#include <vector>

#include "parallelwritezarr.h"
#include "zarr.h"

#include "jni_marshal.h"
#include "edu_abc_berkeley_ParallelWriteNative.h"

namespace {

// ImageJ hands us slices in row-major order; the zarr is written in "F" order,
// so flip the first two axes of every slice.
template <typename T>
void transposeXY(T* dst, const T* src, uint64_t s0, uint64_t s1, uint64_t s2) {
	#pragma omp parallel for
	for (uint64_t k = 0; k < s2; k++) {
		for (uint64_t j = 0; j < s1; j++) {
			for (uint64_t i = 0; i < s0; i++) {
				dst[i + (j * s0) + (k * s1 * s0)] = src[j + (i * s1) + (k * s1 * s0)];
			}
		}
	}
}

} // namespace

JNIEXPORT void JNICALL Java_edu_abc_berkeley_ParallelWriteNative_parallelWriteZarr
	(JNIEnv* env, jobject, jstring fileName, jobjectArray slices,
	 jlong jStartX, jlong jStartY, jlong jStartZ, jlong jEndX, jlong jEndY, jlong jEndZ,
	 jlong jChunkX, jlong jChunkY, jlong jChunkZ, jint jCrop, jstring jCname,
	 jint jUseUuid, jlong jBits)
{
	const uint64_t bits = (uint64_t)jBits;
	std::string dtype;
	switch (bits) {
		case 8: dtype = "<u1"; break;
		case 16: dtype = "<u2"; break;
		case 32: dtype = "<f4"; break;
		default:
			pfv::throwRuntime(env, "Unsupported bit depth for Zarr writing (expected 8, 16 or 32)");
			return;
	}

	const uint64_t x = (uint64_t)(jEndX - jStartX);
	const uint64_t y = (uint64_t)(jEndY - jStartY);
	const uint64_t z = (uint64_t)(jEndZ - jStartZ);
	const uint64_t bytes = bits / 8;

	void* raw = pfv::slicesToNative(env, slices, x * y, z, bytes);
	if (raw == nullptr) {
		pfv::throwRuntime(env, "Out of native memory assembling Zarr write buffer");
		return;
	}

	const char* fNameC = env->GetStringUTFChars(fileName, nullptr);
	const char* cnameC = env->GetStringUTFChars(jCname, nullptr);
	if (fNameC == nullptr || cnameC == nullptr) {
		if (fNameC != nullptr) env->ReleaseStringUTFChars(fileName, fNameC);
		if (cnameC != nullptr) env->ReleaseStringUTFChars(jCname, cnameC);
		free(raw);
		return;
	}
	const std::string file(fNameC);
	const std::string cname(cnameC);
	env->ReleaseStringUTFChars(fileName, fNameC);
	env->ReleaseStringUTFChars(jCname, cnameC);

	const std::vector<uint64_t> startCoords = { (uint64_t)jStartX, (uint64_t)jStartY, (uint64_t)jStartZ };
	const std::vector<uint64_t> endCoords = { (uint64_t)jEndX, (uint64_t)jEndY, (uint64_t)jEndZ };
	const std::vector<uint64_t> writeShape = { x, y, z };

	void* ordered = nullptr;
	try {
		zarr Zarr(file, { (uint64_t)jChunkX, (uint64_t)jChunkY, (uint64_t)jChunkZ },
			0, 5, cname, "blosc", 1, ".", dtype, "0", {}, "F", writeShape, 2,
			{ 0, 0, 0 }, false, { 1, 1, 1 });

		const uint64_t s0 = Zarr.get_shape(0);
		const uint64_t s1 = Zarr.get_shape(1);
		const uint64_t s2 = Zarr.get_shape(2);
		ordered = malloc(s0 * s1 * s2 * Zarr.dtypeBytes());
		if (ordered == nullptr) {
			free(raw);
			pfv::throwRuntime(env, "Out of native memory reordering Zarr write buffer");
			return;
		}
		switch (Zarr.dtypeBytes()) {
			case 1: transposeXY((uint8_t*)ordered, (const uint8_t*)raw, s0, s1, s2); break;
			case 2: transposeXY((uint16_t*)ordered, (const uint16_t*)raw, s0, s1, s2); break;
			case 4: transposeXY((float*)ordered, (const float*)raw, s0, s1, s2); break;
			case 8: transposeXY((double*)ordered, (const double*)raw, s0, s1, s2); break;
		}
		free(raw);
		raw = nullptr;

		Zarr.write_zarray();
		Zarr.set_chunkInfo(startCoords, endCoords);
		parallelWriteZarr(Zarr, ordered, startCoords, endCoords, writeShape, bits,
			jUseUuid != 0, jCrop != 0, true);
		free(ordered);
		ordered = nullptr;
	}
	catch (const std::exception& e) {
		free(raw);
		free(ordered);
		pfv::throwRuntime(env, e.what());
	}
}
