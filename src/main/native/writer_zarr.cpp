// Zarr half of libParallelWriteC: JNI bindings for edu.abc.berkeley.ParallelWriteNative.
// Delegates to cpp-zarr; this translation unit must only see cpp-zarr headers
// (see CMakeLists.txt -- helperfunctions.h clashes between the upstreams).

#include <jni.h>
#include <chrono>
#include <cstdint>
#include <cstdio>
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

// With the environment variable PFV_TIMING set, every write of a region
// prints the seconds of its parts on stderr (for measurements; the plugin
// itself never sets it)
bool timingWanted() {
	static const bool on = getenv("PFV_TIMING") != nullptr;
	return on;
}

double now() {
	return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

uint64_t chunkCount(const std::vector<uint64_t>& chunks, const std::vector<uint64_t>& start, const std::vector<uint64_t>& end) {
	uint64_t n = 1;
	for (size_t d = 0; d < chunks.size(); d++) n *= (end[d] - start[d] + chunks[d] - 1) / chunks[d];
	return n;
}

// The NumPy data type of the plugin's arrays of bits bits, or "" if none
std::string dtypeOf(uint64_t bits) {
	switch (bits) {
		// One-byte types have no byte order: NumPy names them "|u1", and
		// zarr-python 3 refuses arrays that say "<u1".
		case 8: return "|u1";
		case 16: return "<u2";
		case 32: return "<f4";
		default: return "";
	}
}

// A new Zarr v2 array of the given shape and chunks as the plugin writes
// them: F order, blosc with cname at level 5 and byte shuffle, and nested
// chunk folders (0/0/0): cpp-zarr's default since 1.7.0, and much faster to
// write on network file systems than one folder of them all.
zarr newArray(const std::string& file, const std::vector<uint64_t>& shape, const std::vector<uint64_t>& chunks,
              const std::string& cname, uint64_t bits) {
	const size_t n = shape.size();
	return zarr(file, chunks, 0, 5, cname, "blosc", 1, "/", dtypeOf(bits), "0", {}, "F", shape, 2,
		std::vector<uint64_t>(n, 0), false, std::vector<uint64_t>(n, 1));
}

// Runs write, turning what cpp-zarr throws into a RuntimeException. cpp-zarr
// reports problems by throwing std::string, and nothing may escape a JNI
// function: an uncaught C++ exception aborts the JVM, taking Fiji with it.
template <typename Write>
void guarded(JNIEnv* env, Write write) {
	try {
		write();
	}
	catch (const std::string& why) {
		pfv::throwRuntime(env, pfv::zarrError(why).c_str());
	}
	catch (const std::exception& e) {
		pfv::throwRuntime(env, e.what());
	}
	catch (...) {
		pfv::throwRuntime(env, "Writing the Zarr failed");
	}
}

// Writes the region [start, end) of a new Zarr v2 array (see newArray) from
// data, where element i of the region (i[d] counted from start[d]) is at sum
// i[d] * inStrides[d] elements. inStrides lets ImageJ's layout (x fastest,
// then y, then the stack) be written as it is, with no transposed copy. With
// metadata, the array's .zarray is written first. A region of whole chunks
// (or ending at the array's edge) writes those chunk files exactly as a write
// of the whole array does. Throws a RuntimeException if it fails.
void writeZarrRegion(JNIEnv* env, const std::string& file, const void* data,
                     const std::vector<uint64_t>& shape, const std::vector<uint64_t>& chunks,
                     const std::vector<uint64_t>& start, const std::vector<uint64_t>& end,
                     const std::vector<uint64_t>& inStrides, const std::string& cname, uint64_t bits,
                     bool useUuid, bool metadata)
{
	guarded(env, [&]() {
		zarr Zarr = newArray(file, shape, chunks, cname, bits);
		if (metadata) Zarr.write_zarray();
		Zarr.set_chunkInfo(start, end);
		std::vector<uint64_t> region(shape.size());
		for (size_t d = 0; d < region.size(); d++) region[d] = end[d] - start[d];
		const uint8_t failed = parallelWriteZarr(Zarr, const_cast<void*>(data), start, end, region, inStrides, bits, useUuid, false, true);
		if (failed) pfv::throwRuntime(env, "Writing the Zarr failed (is the disk full or the folder not writable?)");
	});
}

// Whether shape, chunks and strides have one value per axis (at least two)
// for an array of bits bits; if not, a RuntimeException is pending.
bool checkArray(JNIEnv* env, const std::vector<uint64_t>& shape, const std::vector<uint64_t>& chunks,
                const std::vector<uint64_t>& inStrides, uint64_t bits) {
	if (dtypeOf(bits).empty()) {
		pfv::throwRuntime(env, "Unsupported bit depth for Zarr writing (expected 8, 16 or 32)");
		return false;
	}
	const size_t n = shape.size();
	if (n < 2 || chunks.size() != n || inStrides.size() != n) {
		pfv::throwRuntime(env, "The shape, chunks and strides of a Zarr write must have one value per axis");
		return false;
	}
	return true;
}

// Both strings as std::string, or false (an exception pending) if the JVM has no memory for them
bool strings(JNIEnv* env, jstring a, std::string& sa, jstring b, std::string& sb) {
	const char* ca = env->GetStringUTFChars(a, nullptr);
	const char* cb = ca == nullptr ? nullptr : env->GetStringUTFChars(b, nullptr);
	if (ca != nullptr) {
		sa = ca;
		env->ReleaseStringUTFChars(a, ca);
	}
	if (cb != nullptr) {
		sb = cb;
		env->ReleaseStringUTFChars(b, cb);
	}
	return ca != nullptr && cb != nullptr;
}

// Writes a new Zarr v2 array (see writeZarrRegion) of the given shape and
// chunks from ImageJ planes of planeElems elements, laid end to end as
// inStrides says.
void writeZarr(JNIEnv* env, jstring fileName, jobjectArray slices, uint64_t planeElems,
               const std::vector<uint64_t>& shape, const std::vector<uint64_t>& chunks,
               const std::vector<uint64_t>& inStrides, jstring jCname, uint64_t bits, bool useUuid)
{
	if (!checkArray(env, shape, chunks, inStrides, bits)) return;
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
	std::string file, cname;
	if (strings(env, fileName, file, jCname, cname))
		writeZarrRegion(env, file, raw, shape, chunks, std::vector<uint64_t>(shape.size(), 0), shape, inStrides, cname, bits, useUuid, true);
	free(raw);
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

// The metadata (.zarray) of a new Zarr v2 array of the given shape and
// chunks, as writeZarrRegion writes it, ahead of the regions written by
// writeZarrPlanes (two of which may be under way at once).
JNIEXPORT void JNICALL Java_edu_abc_berkeley_ParallelWriteNative_writeZarrMetadata
	(JNIEnv* env, jobject, jstring fileName, jlongArray jShape, jlongArray jChunks, jstring jCname, jint jBits)
{
	const std::vector<uint64_t> shape = toVector(env, jShape), chunks = toVector(env, jChunks);
	if (!checkArray(env, shape, chunks, shape, (uint64_t)jBits)) return;
	std::string file, cname;
	if (!strings(env, fileName, file, jCname, cname)) return;
	guarded(env, [&]() { newArray(file, shape, chunks, cname, (uint64_t)jBits).write_zarray(); });
}

// The region [start, end) of a Zarr v2 array whose metadata is written, from
// the native buffer at address (of capacity bytes) that
// ParallelWriteNative.PlaneBuffer filled; see writeZarrRegion.
JNIEXPORT void JNICALL Java_edu_abc_berkeley_ParallelWriteNative_writeZarrPlanes
	(JNIEnv* env, jobject, jstring fileName, jlong address, jlong capacity, jlongArray jShape, jlongArray jChunks,
	 jlongArray jStart, jlongArray jEnd, jlongArray jStrides, jstring jCname, jint jBits)
{
	const std::vector<uint64_t> shape = toVector(env, jShape), chunks = toVector(env, jChunks);
	const std::vector<uint64_t> start = toVector(env, jStart), end = toVector(env, jEnd), strides = toVector(env, jStrides);
	if (!checkArray(env, shape, chunks, strides, (uint64_t)jBits)) return;
	const size_t n = shape.size();
	// The region: inside the array, of whole chunks (a chunk it covered only
	// in part would be written with the fill value in the rest), and the last
	// element it reads inside the buffer (summed without overflowing)
	const uint64_t elements = capacity > 0 ? (uint64_t)capacity / ((uint64_t)jBits / 8) : 0;
	bool ok = address != 0 && elements > 0 && start.size() == n && end.size() == n;
	uint64_t last = 0;
	for (size_t d = 0; ok && d < n; d++) {
		ok = start[d] < end[d] && end[d] <= shape[d] && chunks[d] > 0 && start[d] % chunks[d] == 0 &&
		     (end[d] % chunks[d] == 0 || end[d] == shape[d]);
		const uint64_t steps = ok ? end[d] - start[d] - 1 : 0;
		ok = ok && (steps == 0 || strides[d] <= (elements - 1) / steps);
		const uint64_t term = ok ? steps * strides[d] : 0;
		ok = ok && last <= elements - 1 - term;
		last += term;
	}
	if (!ok) {
		pfv::throwRuntime(env, "The region to write is not whole chunks of the array, or not all in its native buffer");
		return;
	}
	std::string file, cname;
	if (!strings(env, fileName, file, jCname, cname)) return;
	const double began = now();
	writeZarrRegion(env, file, (const void*)(intptr_t)address, shape, chunks, start, end, strides, cname,
		(uint64_t)jBits, true, false);
	if (timingWanted())
		fprintf(stderr, "    [native zarr write] %llu chunks in %.3f s (threads %d)\n",
			(unsigned long long)chunkCount(chunks, start, end), now() - began, omp_get_max_threads());
}

// Runs when Java loads this library; see primeUnwinder in jni_marshal.h.
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*)
{
	pfv::primeUnwinder();
	return JNI_VERSION_1_6;
}
