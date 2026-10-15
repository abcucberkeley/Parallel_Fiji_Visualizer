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

std::vector<uint64_t> toVector(JNIEnv* env, jlongArray a) {
	const jsize n = a == nullptr ? 0 : env->GetArrayLength(a);
	std::vector<jlong> tmp((size_t)n);
	if (n > 0) env->GetLongArrayRegion(a, 0, n, tmp.data());
	return std::vector<uint64_t>(tmp.begin(), tmp.end());
}

// Writes a new Zarr v2 array (F order, nested chunk folders, blosc with
// cname) of the given shape and chunks from ImageJ planes of planeElems
// elements. inStrides gives, in elements, how far apart neighbors along each
// axis of the array are in the planes laid end to end, so ImageJ's layout
// (x fastest, then y, then the stack) is written as it is, with no transposed
// copy.
void writeZarr(JNIEnv* env, jstring fileName, jobjectArray slices, uint64_t planeElems,
               const std::vector<uint64_t>& shape, const std::vector<uint64_t>& chunks,
               const std::vector<uint64_t>& inStrides, jstring jCname, uint64_t bits, bool useUuid)
{
	std::string dtype;
	switch (bits) {
		// One-byte types have no byte order: NumPy names them "|u1", and
		// zarr-python 3 refuses arrays that say "<u1".
		case 8: dtype = "|u1"; break;
		case 16: dtype = "<u2"; break;
		case 32: dtype = "<f4"; break;
		default:
			pfv::throwRuntime(env, "Unsupported bit depth for Zarr writing (expected 8, 16 or 32)");
			return;
	}
	const size_t n = shape.size();
	if (n < 2 || chunks.size() != n || inStrides.size() != n) {
		pfv::throwRuntime(env, "The shape, chunks and strides of a Zarr write must have one value per axis");
		return;
	}
	uint64_t elements = 1;
	for (uint64_t s : shape) elements *= s;
	const jsize nPlanes = slices == nullptr ? 0 : env->GetArrayLength(slices);
	if (planeElems == 0 || elements != planeElems * (uint64_t)nPlanes) {
		pfv::throwRuntime(env, "The planes do not hold the Zarr array's shape");
		return;
	}

	void* raw = pfv::slicesToNative(env, slices, planeElems, (uint64_t)nPlanes, bits / 8);
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

	const std::vector<uint64_t> start(n, 0);
	try {
		// Nested chunk folders (0/0/0): cpp-zarr's default since 1.7.0, and much
		// faster to write on network file systems than one folder of them all
		zarr Zarr(file, chunks, 0, 5, cname, "blosc", 1, "/", dtype, "0", {}, "F", shape, 2,
			std::vector<uint64_t>(n, 0), false, std::vector<uint64_t>(n, 1));
		Zarr.write_zarray();
		Zarr.set_chunkInfo(start, shape);
		const uint8_t failed = parallelWriteZarr(Zarr, raw, start, shape, shape, inStrides, bits, useUuid, false, true);
		free(raw);
		raw = nullptr;
		if (failed) pfv::throwRuntime(env, "Writing the Zarr failed (is the disk full or the folder not writable?)");
	}
	// cpp-zarr reports problems by throwing std::string. Nothing may escape a
	// JNI function: an uncaught C++ exception aborts the JVM, taking Fiji with it.
	catch (const std::string& why) {
		free(raw);
		pfv::throwRuntime(env, pfv::zarrError(why).c_str());
	}
	catch (const std::exception& e) {
		free(raw);
		pfv::throwRuntime(env, e.what());
	}
	catch (...) {
		free(raw);
		pfv::throwRuntime(env, "Writing the Zarr failed");
	}
}

} // namespace

// A 3D array (rows, columns, planes) of shape end - start.
JNIEXPORT void JNICALL Java_edu_abc_berkeley_ParallelWriteNative_parallelWriteZarr
	(JNIEnv* env, jobject, jstring fileName, jobjectArray slices,
	 jlong jStartX, jlong jStartY, jlong jStartZ, jlong jEndX, jlong jEndY, jlong jEndZ,
	 jlong jChunkX, jlong jChunkY, jlong jChunkZ, jint, jstring jCname,
	 jint jUseUuid, jlong jBits)
{
	const uint64_t h = (uint64_t)(jEndX - jStartX), w = (uint64_t)(jEndY - jStartY), d = (uint64_t)(jEndZ - jStartZ);
	writeZarr(env, fileName, slices, h * w, { h, w, d }, { (uint64_t)jChunkX, (uint64_t)jChunkY, (uint64_t)jChunkZ },
		{ w, 1, w * h }, jCname, (uint64_t)jBits, jUseUuid != 0);
}

// An array of any shape, rows first; see ParallelWriteNative.
JNIEXPORT void JNICALL Java_edu_abc_berkeley_ParallelWriteNative_parallelWriteZarrArray
	(JNIEnv* env, jobject, jstring fileName, jobjectArray slices, jlongArray jShape, jlongArray jChunks,
	 jlongArray jStrides, jstring jCname, jlong jBits)
{
	const std::vector<uint64_t> shape = toVector(env, jShape);
	writeZarr(env, fileName, slices, shape.size() >= 2 ? shape[0] * shape[1] : 0, shape, toVector(env, jChunks),
		toVector(env, jStrides), jCname, (uint64_t)jBits, true);
}

// Runs when Java loads this library; see primeUnwinder in jni_marshal.h.
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*)
{
	pfv::primeUnwinder();
	return JNI_VERSION_1_6;
}
