#include <jni.h>            // JNI header provided by JDK
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <omp.h>
#include "parallelwritetiff.h"
#include "edu_abc_berkeley_PWTC.h"

JNIEXPORT void JNICALL Java_edu_abc_berkeley_PWTC_parallelWriteTiff (JNIEnv *env, jobject thisobj, jstring fileName, jobjectArray jTiff, jint jX, jint jY, jint jZ, jint jBits){
	uint64_t x = (uint64_t)jX;
	uint64_t y = (uint64_t)jY;
	uint64_t z = (uint64_t)jZ;
	uint64_t bits = (uint64_t)jBits;
	uint64_t bytes = bits/8;
    const char* fName = env->GetStringUTFChars(fileName, NULL);
	void* tiff = malloc(x*y*z*bytes);
	uint64_t zSize = x*y;
	void* cArr;
	for(uint64_t i = 0; i < z; i++){
		jobject cDim = env->GetObjectArrayElement(jTiff, i);
		switch(bits){
			case 8:
				cArr = (void*)env->GetByteArrayElements((jbyteArray)cDim, 0);
				break;
			case 16:
				cArr = (void*)env->GetShortArrayElements((jshortArray)cDim, 0);
				break;
			case 32:
				cArr = (void*)env->GetFloatArrayElements((jfloatArray)cDim, 0);
				break;
		}
		memcpy(tiff+(zSize*i*bytes),cArr,zSize*bytes);
		// Memory leak?
		//(*env)->ReleaseByteArrayElements(env, cDim, cArr, 0);
    	//(*env)->DeleteLocalRef(env, cDim);
	}
	writeTiffParallelHelper(fName, tiff, bits, "w", x, y, z, 0, 0);
}

