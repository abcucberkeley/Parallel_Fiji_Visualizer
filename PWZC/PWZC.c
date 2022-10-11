#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include "parallelWriteZarr.h"
#include "edu_abc_berkeley_PWZC.h"

JNIEXPORT void JNICALL Java_edu_abc_berkeley_PWZC_parallelWriteZarr
  (JNIEnv *env, jobject thisObj, jstring fileName, jobjectArray jZarr, jlong jStartX, jlong jStartY, jlong jStartZ, jlong jEndX, jlong jEndY, jlong jEndZ, jlong jchunkXSize, jlong jchunkYSize, jlong jchunkZSize, jint jCrop, jstring jCname, jint jUseUuid, jlong jBits){
	uint64_t startX = (uint64_t)jStartX;
    uint64_t startY = (uint64_t)jStartY;
    uint64_t startZ = (uint64_t)jStartZ;
	uint64_t endX = (uint64_t)jEndX;
    uint64_t endY = (uint64_t)jEndY;
    uint64_t endZ = (uint64_t)jEndZ;
	uint64_t chunkXSize = (uint64_t)jchunkXSize;
	uint64_t chunkYSize = (uint64_t)jchunkYSize;
	uint64_t chunkZSize = (uint64_t)jchunkZSize;

	uint64_t x = (endX-startX);
	uint64_t y = (endY-startY);
	uint64_t z = (endZ-startZ);

	uint8_t crop = (uint8_t)jCrop;
	uint8_t useUuid = (uint8_t)jUseUuid;
	
    uint64_t bits = (uint64_t)jBits;
    uint64_t bytes = bits/8;
    const char* jfName = (*env)->GetStringUTFChars(env,fileName, NULL);
   	const char* jcname = (*env)->GetStringUTFChars(env,jCname, NULL);
	char* fName = strdup(jfName);
	char* cname = strdup(jcname);
	void* zarr = malloc(x*y*z*bytes);
    uint64_t zSize = x*y;
    void* cArr;
    for(uint64_t i = 0; i < z; i++){
        jobjectArray cDim = (*env)->GetObjectArrayElement(env,jZarr, i);
        switch(bits){
            case 8:
                cArr = (void*)(*env)->GetByteArrayElements(env,cDim, 0);
                break;
            case 16:
                cArr = (void*)(*env)->GetShortArrayElements(env,cDim, 0);
                break;
            case 32:
                cArr = (void*)(*env)->GetFloatArrayElements(env,cDim, 0);
                break;
        }
        memcpy(zarr+(zSize*i*bytes),cArr,zSize*bytes);
        // Memory leak?
        //(*env)->ReleaseByteArrayElements(env, cDim, cArr, 0);
        //(*env)->DeleteLocalRef(env, cDim);
    }
    writeZarrParallelHelper(fName, zarr, startX, startY, startZ, endX, endY, endZ, chunkXSize, chunkYSize, chunkZSize, crop, cname, useUuid, bytes, 1);

}
