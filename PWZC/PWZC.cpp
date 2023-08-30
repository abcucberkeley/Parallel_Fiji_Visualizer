#include <stdlib.h>
#include <stdint.h>
#include <string.h>
//#include "parallelWriteZarr.h"
#include <vector>
#include "parallelwritezarr.h"
#include "zarr.h"
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
	const char* jfName = env->GetStringUTFChars(fileName, NULL);
	const char* jcname = env->GetStringUTFChars(jCname, NULL);
	char* fName = strdup(jfName);
	char* cname = strdup(jcname);
	void* zarrArr = malloc(x*y*z*bytes);
	uint64_t zSize = x*y;
	void* cArr;
	for(uint64_t i = 0; i < z; i++){
		jobject cDim = env->GetObjectArrayElement(jZarr, i);
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
		memcpy(zarrArr+(zSize*i*bytes),cArr,zSize*bytes);
		// Memory leak?
		//(*env)->ReleaseByteArrayElements(env, cDim, cArr, 0);
		//(*env)->DeleteLocalRef(env, cDim);
	}
	std::string dtypeString;
	switch(bits){
		case 8:
			dtypeString = "<u1";
			break;
		case 16:
			dtypeString = "<u2";
			break;
		case 32:
			dtypeString = "<f4";
			break;
	}
    const std::vector<uint64_t> startCoords = {startX,startY,startZ};
    const std::vector<uint64_t> endCoords = {endX,endY,endZ};
    const std::vector<uint64_t> writeShape = {endX-startX,endY-startY,endZ-startZ};
	zarr Zarr(fName,{chunkXSize,chunkYSize,chunkZSize},0,5,cname,"blosc",1,".",dtypeString,0,{},"F",writeShape,2,{0,0,0},false,{1,1,1});
	void* zarrArrC = malloc(Zarr.get_shape(0)*Zarr.get_shape(1)*Zarr.get_shape(2)*Zarr.dtypeBytes());
	#pragma omp parallel for
	for(uint64_t k = 0; k < Zarr.get_shape(2); k++){
		for(uint64_t j = 0; j < Zarr.get_shape(1); j++){
			for(uint64_t i = 0; i < Zarr.get_shape(0); i++){
				switch(Zarr.dtypeBytes()){
					case 1:
						((uint8_t*)zarrArrC)[j+(i*Zarr.get_shape(1))+(k*Zarr.get_shape(1)*Zarr.get_shape(0))] = ((uint8_t*)zarrArr)[i+(j*Zarr.get_shape(0))+(k*Zarr.get_shape(1)*Zarr.get_shape(0))];
						break;
					case 2:
						((uint16_t*)zarrArrC)[j+(i*Zarr.get_shape(1))+(k*Zarr.get_shape(1)*Zarr.get_shape(0))] = ((uint16_t*)zarrArr)[i+(j*Zarr.get_shape(0))+(k*Zarr.get_shape(1)*Zarr.get_shape(0))];
						break;
					case 4:
						((float*)zarrArrC)[j+(i*Zarr.get_shape(1))+(k*Zarr.get_shape(1)*Zarr.get_shape(0))] = ((float*)zarrArr)[i+(j*Zarr.get_shape(0))+(k*Zarr.get_shape(1)*Zarr.get_shape(0))];
						break;
					case 8:
						((double*)zarrArrC)[j+(i*Zarr.get_shape(1))+(k*Zarr.get_shape(1)*Zarr.get_shape(0))] = ((double*)zarrArr)[i+(j*Zarr.get_shape(0))+(k*Zarr.get_shape(1)*Zarr.get_shape(0))];
						break;
				}
			}
		}
	}
	Zarr.write_zarray();
	Zarr.set_chunkInfo(startCoords, endCoords);
	parallelWriteZarr(Zarr, zarrArrC, startCoords, endCoords, writeShape, bits, useUuid, crop, true);
	//writeZarrParallelHelper(fName, zarr, startX, startY, startZ, endX, endY, endZ, chunkXSize, chunkYSize, chunkZSize, crop, cname, useUuid, bytes, 1);

}
