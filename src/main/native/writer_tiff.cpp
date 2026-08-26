// TIFF half of libParallelWriteC: JNI bindings for edu.abc.berkeley.ParallelWriteNative.
// Delegates to cpp-tiff; this translation unit must only see cpp-tiff headers
// (see CMakeLists.txt -- helperfunctions.h clashes between the upstreams).

#include <jni.h>
#include <cstdint>
#include <cstdlib>

#include "parallelwritetiff.h"

#include "jni_marshal.h"
#include "edu_abc_berkeley_ParallelWriteNative.h"

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
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) {
		free(buf);
		return;
	}
	// "lzw" preserves the plugin's historical output; cpp-tiff also accepts
	// "zstd" and "none" should we ever expose a compression choice in the UI.
	writeTiffParallelHelper(fName, buf, (uint64_t)bits, "w", ux, uy, uz, 0, false, "lzw");
	env->ReleaseStringUTFChars(fileName, fName);
	free(buf);
}
