// TIFF half of libParallelWriteC: JNI bindings for edu.abc.berkeley.ParallelWriteNative.
// Delegates to cpp-tiff; this translation unit must only see cpp-tiff headers
// (see CMakeLists.txt -- helperfunctions.h clashes between the upstreams).

#include <jni.h>
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>

#include "parallelwritetiff.h"

#include "jni_marshal.h"
#include "edu_abc_berkeley_ParallelWriteNative.h"

namespace {

// Rows per strip as cpp-tiff 1.7.3 chooses them for a write of nSlices slices
// (autoStripRows in parallelwritetiff.cpp, which it does not export): 512,
// fewer when the write would have fewer than 256 strips, but never under 64
// KB. A save in runs passes the rows of the whole image to every run, so that
// its file is the one a write of the image in one piece gives (SlabSaveTest
// compares the two byte for byte, which catches a change upstream).
uint64_t stripRows(uint64_t x, uint64_t y, uint64_t nSlices, uint64_t pixelBytes) {
	const uint64_t kRows = 512, kStrips = 256, kMinBytes = (uint64_t)64 << 10;
	const uint64_t slices = std::max<uint64_t>(nSlices, 1);
	const uint64_t perSlice = (kStrips + slices - 1) / slices;
	const uint64_t rowBytes = std::max<uint64_t>(x * pixelBytes, 1);
	const uint64_t rows = std::max((y + perSlice - 1) / perSlice, (kMinBytes + rowBytes - 1) / rowBytes);
	return std::max<uint64_t>(1, std::min(rows, kRows));
}

// With the environment variable PFV_TIMING set, every write of a run prints
// its seconds on stderr (for measurements; the plugin itself never sets it)
bool timingWanted() {
	static const bool on = getenv("PFV_TIMING") != nullptr;
	return on;
}

double now() {
	return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

const char* const writeFailed = "Writing the TIFF failed (is the disk full or the folder not writable?)";

} // namespace

JNIEXPORT void JNICALL Java_edu_abc_berkeley_ParallelWriteNative_parallelWriteTiff
	(JNIEnv* env, jobject, jstring fileName, jobjectArray slices, jint x, jint y,
	 jint z, jint bits)
{
	if (bits != 8 && bits != 16 && bits != 32) {
		pfv::throwRuntime(env, "Unsupported bit depth for TIFF writing (expected 8, 16 or 32)");
		return;
	}
	const uint64_t ux = (uint64_t)x;
	const uint64_t uy = (uint64_t)y;
	const uint64_t uz = (uint64_t)z;
	void* buf = pfv::slicesToNative(env, slices, ux * uy, uz, (uint64_t)bits / 8);
	if (buf == nullptr) {
		pfv::throwRuntime(env, "Out of native memory assembling TIFF write buffer");
		return;
	}
	pfv::NativePath path;
	if (!pfv::nativePath(env, fileName, path)) {
		free(buf);
		return;
	}
	const char* fName = path.native.c_str();
	// "lzw" preserves the plugin's historical output; cpp-tiff also accepts
	// "zstd" and "none" should we ever expose a compression choice in the UI.
	// Since cpp-tiff 1.7.0 a failed write (full disk, quota, unwritable path)
	// is reported with a nonzero return instead of leaving a partial file.
	const uint8_t failed = writeTiffParallelHelper(fName, buf, (uint64_t)bits, "w", ux, uy, uz, 0, false, "lzw");
	free(buf);
	if (failed) pfv::throwRuntime(env, writeFailed);
}

// The native buffers of planes the saves fill a run at a time (see
// ParallelWriteNative.PlaneBuffer), for the TIFF and the Zarr writer alike.
// malloc'd, not zeroed: every byte a write reads is copied in first.
JNIEXPORT jlong JNICALL Java_edu_abc_berkeley_ParallelWriteNative_allocPlanes
	(JNIEnv*, jobject, jlong bytes)
{
	if (bytes <= 0 || (uint64_t)bytes > (uint64_t)SIZE_MAX) return 0;
	return (jlong)(intptr_t)malloc((size_t)bytes);
}

JNIEXPORT void JNICALL Java_edu_abc_berkeley_ParallelWriteNative_freePlanes
	(JNIEnv*, jobject, jlong address)
{
	free((void*)(intptr_t)address);
}

// Copies the first count planes of slices (planeElems values of bits bits
// each) into the buffer at address, of capacity bytes, plane k to the
// plane-sized slot first + k * stride.
JNIEXPORT void JNICALL Java_edu_abc_berkeley_ParallelWriteNative_putPlanes
	(JNIEnv* env, jobject, jlong address, jlong capacity, jobjectArray slices, jint count, jlong planeElems,
	 jint bits, jlong first, jlong stride)
{
	if ((bits != 8 && bits != 16 && bits != 32) || count < 0 || capacity < 0 || planeElems <= 0 || first < 0 || stride < 0) {
		pfv::throwRuntime(env, "Bad arguments for copying planes to write");
		return;
	}
	pfv::slicesIntoNative(env, slices, (uint64_t)planeElems, (uint64_t)count, (uint64_t)bits / 8,
		(void*)(intptr_t)address, (uint64_t)capacity, (uint64_t)first, (uint64_t)stride);
}

// Writes the slices x * y * slices in the buffer at address (of capacity
// bytes) to fileName: as a new file, or appended to the file a run before
// wrote, with the rows per strip of an image of totalSlices slices. A run
// that is the whole image is written exactly as parallelWriteTiff writes it.
JNIEXPORT void JNICALL Java_edu_abc_berkeley_ParallelWriteNative_writeTiffPlanes
	(JNIEnv* env, jobject, jstring fileName, jlong address, jlong capacity, jint x, jint y, jint slices,
	 jint bits, jboolean append, jint totalSlices)
{
	if (bits != 8 && bits != 16 && bits != 32) {
		pfv::throwRuntime(env, "Unsupported bit depth for TIFF writing (expected 8, 16 or 32)");
		return;
	}
	if (address == 0 || x <= 0 || y <= 0 || slices <= 0 || totalSlices < slices || capacity < 0 ||
	    (uint64_t)x * (uint64_t)y * (uint64_t)slices * (uint64_t)(bits / 8) > (uint64_t)capacity) {
		pfv::throwRuntime(env, "The slices to write are not all in their native buffer");
		return;
	}
	pfv::NativePath path;
	if (!pfv::nativePath(env, fileName, path)) return;
	const char* fName = path.native.c_str();
	const uint64_t ux = (uint64_t)x, uy = (uint64_t)y, uz = (uint64_t)slices;
	const void* buf = (const void*)(intptr_t)address;
	uint8_t failed;
	const double began = now();
	if (!append && slices == totalSlices) {
		failed = writeTiffParallelHelper(fName, buf, (uint64_t)bits, "w", ux, uy, uz, 0, false, "lzw");
	}
	else {
		// libtiff's append mode links each new directory to the last one,
		// which it finds by walking the file's directories: once a run.
		const uint64_t rows = stripRows(ux, uy, (uint64_t)totalSlices, (uint64_t)bits / 8);
		failed = writeTiffParallelWrapper(ux, uy, uz, fName, buf, (uint64_t)bits, 0, rows, append ? "a" : "w", false, "lzw");
	}
	if (timingWanted())
		fprintf(stderr, "    [native tiff write] %d slices%s in %.3f s\n", (int)slices, append ? " appended" : "", now() - began);
	if (failed) pfv::throwRuntime(env, writeFailed);
}
