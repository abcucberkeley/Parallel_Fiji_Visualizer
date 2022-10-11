#include <jni.h>            // JNI header provided by JDK
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <limits.h>
#include <omp.h>
#include "parallelReadTiff.h"
#include "edu_abc_berkeley_PRTC.h"

JNIEXPORT void JNICALL Java_edu_abc_berkeley_PRTC_parallelReadTiffC (JNIEnv *env, jobject thisObj, jstring fileName, jobject tiffJ){
	const char* fName = (*env)->GetStringUTFChars(env,fileName, NULL);
	void* tiff = (*env)->GetDirectBufferAddress(env,tiffJ);
	if(!tiff){
		printf("NULLPTR RECIEVED FROM JAVA\n");
		return;
	}
	//((uint8_t*)tiff)[0] = 1;
	readTiffParallelWrapperSet(fName,tiff);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_PRTC_parallelReadTiffUINT8 (JNIEnv *env, jobject thisObj, jstring fileName){
	const char* fName = (*env)->GetStringUTFChars(env,fileName, NULL);
	uint8_t* arrP = (uint8_t*)readTiffParallelWrapperNoXYFlip(fName);
	uint64_t* arrDims = getImageSize(fName);
	if(isImageJIm(fName)){
        uint64_t tempZ = imageJImGetZ(fName);
        if(tempZ) arrDims[2] = tempZ;
    }
	(*env)->ReleaseStringUTFChars(env,fileName, fName);
	uint64_t zSize = arrDims[0]*arrDims[1];
	// Get the char array class
	jclass cls = (*env)->FindClass(env,"[B");

	jcharArray iniVal = (*env)->NewByteArray(env,arrDims[2]);

	// Main Array to be returned
	jobjectArray outer = (*env)->NewObjectArray(env,arrDims[2], cls, iniVal);

	for(uint64_t i = 0; i < arrDims[2]; i++){
		jcharArray inner = (*env)->NewByteArray(env,zSize);
		if(zSize <= INT_MAX/2){
			(*env)->SetByteArrayRegion(env,inner, 0, zSize, arrP+(i*zSize));
		}
		else{
			//ImageJ can only do half of INTMAX into a region at a time maybe?
			int32_t batchSize = (zSize-1)/3+1;
			int32_t cSize = batchSize;
			for(int j = 0; j < 3; j++){
				if((j+1)*batchSize > zSize) cSize = zSize-(j*batchSize);
				(*env)->SetByteArrayRegion(env,inner, j*batchSize, cSize, arrP+(i*zSize)+(j*batchSize));
			}
		}

		(*env)->SetObjectArrayElement(env, outer, i, inner);
		(*env)->DeleteLocalRef(env,inner);

	}
	free(arrP);
	free(arrDims);
	return outer;

}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_PRTC_parallelReadTiffUINT16 (JNIEnv *env, jobject thisObj, jstring fileName){
	const char* fName = (*env)->GetStringUTFChars(env,fileName, NULL);
	uint16_t* arrP = (uint16_t*)readTiffParallelWrapperNoXYFlip(fName);
	uint64_t* arrDims = getImageSize(fName);
	if(isImageJIm(fName)){
		uint64_t tempZ = imageJImGetZ(fName);
		if(tempZ) arrDims[2] = tempZ;
	}
	(*env)->ReleaseStringUTFChars(env,fileName, fName); 	
	uint64_t zSize = arrDims[0]*arrDims[1];
	// Get the short array class
	jclass cls = (*env)->FindClass(env,"[S");

	jshortArray iniVal = (*env)->NewShortArray(env,arrDims[2]);

	// Main Array to be returned
	jobjectArray outer = (*env)->NewObjectArray(env,arrDims[2], cls, iniVal);
	(*env)->DeleteLocalRef(env,iniVal);
	(*env)->DeleteLocalRef(env,cls);
	for(uint64_t i = 0; i < arrDims[2]; i++){
		jshortArray inner = (*env)->NewShortArray(env,zSize);
		if(zSize <= INT_MAX/2){
			(*env)->SetShortArrayRegion(env,inner, 0, zSize, arrP+(i*zSize));
		}
		else{
			//ImageJ can only do half of INTMAX into a region at a time maybe?
			int32_t batchSize = (zSize-1)/3+1;
			int32_t cSize = batchSize;
			for(int j = 0; j < 3; j++){
				if((j+1)*batchSize > zSize) cSize = zSize-(j*batchSize);
				(*env)->SetShortArrayRegion(env,inner, j*batchSize, cSize, arrP+(i*zSize)+(j*batchSize));
			}
		}
		(*env)->SetObjectArrayElement(env, outer, i, inner);
		(*env)->DeleteLocalRef(env,inner);

	}
	free(arrP);
	free(arrDims);
	return outer;

}


JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_PRTC_parallelReadTiffFLOAT (JNIEnv *env, jobject thisObj, jstring fileName){
	const char* fName = (*env)->GetStringUTFChars(env,fileName, NULL);
	float* arrP = (float*)readTiffParallelWrapperNoXYFlip(fName);
	uint64_t* arrDims = getImageSize(fName);
	if(isImageJIm(fName)){
        uint64_t tempZ = imageJImGetZ(fName);
        if(tempZ) arrDims[2] = tempZ;
    }
	(*env)->ReleaseStringUTFChars(env,fileName, fName);
	uint64_t zSize = arrDims[0]*arrDims[1];
	// Get the float array class
	jclass cls = (*env)->FindClass(env,"[F");

	jfloatArray iniVal = (*env)->NewFloatArray(env,arrDims[2]);

	// Main Array to be returned
	jobjectArray outer = (*env)->NewObjectArray(env,arrDims[2], cls, iniVal);


	for(uint64_t i = 0; i < arrDims[2]; i++){
		jfloatArray inner = (*env)->NewFloatArray(env,zSize);
		if(zSize <= INT_MAX/2){
			(*env)->SetFloatArrayRegion(env,inner, 0, zSize, arrP+(i*zSize));
		}
		else{
			//ImageJ can only do half of INTMAX into a region at a time maybe?
			int32_t batchSize = (zSize-1)/3+1;
			int32_t cSize = batchSize;
			for(int j = 0; j < 3; j++){
				if((j+1)*batchSize > zSize) cSize = zSize-(j*batchSize);
				(*env)->SetFloatArrayRegion(env,inner, j*batchSize, cSize, arrP+(i*zSize)+(j*batchSize));
			}
		}

		(*env)->SetObjectArrayElement(env, outer, i, inner);
		(*env)->DeleteLocalRef(env,inner);

	}
	free(arrP);
	free(arrDims);
	return outer;

}

// Image stack does not accept double so we convert to a float for now
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_PRTC_parallelReadTiffDOUBLE (JNIEnv *env, jobject thisObj, jstring fileName){
	const char* fName = (*env)->GetStringUTFChars(env,fileName, NULL);
	double* arrPT = (double*)readTiffParallelWrapperNoXYFlip(fName);
	uint64_t* arrDims = getImageSize(fName);
	if(isImageJIm(fName)){
        uint64_t tempZ = imageJImGetZ(fName);
        if(tempZ) arrDims[2] = tempZ;
    }
	(*env)->ReleaseStringUTFChars(env, fileName, fName);
	uint64_t zSize = arrDims[0]*arrDims[1];
	uint64_t arrSize = zSize*arrDims[2];
	
	// convert the double array to float
	float* arrP = (float*)malloc(arrSize*sizeof(float));
	#pragma omp parallel for
	for(uint64_t i = 0; i < arrSize; i++){
		arrP[i] = (float)arrPT[i];
	}

	free(arrPT);

	// Get the float array class
	jclass cls = (*env)->FindClass(env,"[F");

	jfloatArray iniVal = (*env)->NewFloatArray(env,arrDims[2]);

	// Main Array to be returned
	jobjectArray outer = (*env)->NewObjectArray(env,arrDims[2], cls, iniVal);

	for(uint64_t i = 0; i < arrDims[2]; i++){
		jfloatArray inner = (*env)->NewFloatArray(env,zSize);
		if(zSize <= INT_MAX/2){
			(*env)->SetFloatArrayRegion(env,inner, 0, zSize, arrP+(i*zSize));
		}
		else{
			//ImageJ can only do half of INTMAX into a region at a time maybe?
			int32_t batchSize = (zSize-1)/3+1;
			int32_t cSize = batchSize;
			for(int j = 0; j < 3; j++){
				if((j+1)*batchSize > zSize) cSize = zSize-(j*batchSize);
				(*env)->SetFloatArrayRegion(env,inner, j*batchSize, cSize, arrP+(i*zSize)+(j*batchSize));
			}
		}

		(*env)->SetObjectArrayElement(env, outer, i, inner);
		(*env)->DeleteLocalRef(env,inner);

	}
	free(arrP);
	free(arrDims);
	return outer;

}

JNIEXPORT jlong JNICALL Java_edu_abc_berkeley_PRTC_getDataType (JNIEnv *env, jobject thisObj,jstring fileName)
{
	const char* fName = (*env)->GetStringUTFChars(env,fileName, NULL);
	uint64_t bits = getDataType(fName);
	(*env)->ReleaseStringUTFChars(env,fileName, fName);
	return bits;

}

JNIEXPORT jlongArray JNICALL Java_edu_abc_berkeley_PRTC_getImageDims (JNIEnv *env, jobject thisObj, jstring fileName)
{
	const char* fName = (*env)->GetStringUTFChars(env,fileName, NULL);
	uint64_t* dims = getImageSize(fName);
	(*env)->ReleaseStringUTFChars(env,fileName, fName);
	jlongArray rval = (*env)->NewLongArray(env,3);
	(*env)->SetLongArrayRegion(env,rval,0,3,dims);
	free(dims); 
	return rval;

}
