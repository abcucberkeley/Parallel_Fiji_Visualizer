#ifdef __linux__
	#define _GNU_SOURCE
#endif
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include "blosc2.h"
#include "cjson/cJSON.h"
#include <omp.h>
#include <stdlib.h>
#include "zlib.h"

//mex -v COPTIMFLAGS="-O3 -DNDEBUG" CFLAGS='$CFLAGS -O3 -fopenmp' LDFLAGS='$LDFLAGS -O3 -fopenmp' '-I/global/home/groups/software/sl-7.x86_64/modules/cBlosc/2.0.4/include/' '-I/global/home/groups/software/sl-7.x86_64/modules/cJSON/1.7.15/include/' '-L/global/home/groups/software/sl-7.x86_64/modules/cBlosc/2.0.4/lib64' -lblosc2 -L'/global/home/groups/software/sl-7.x86_64/modules/cJSON/1.7.15/lib64' -lcjson -lz parallelReadZarr.c


const char fileSep =
#ifdef _WIN32
'\\';
#else
'/';
#endif

#ifdef _WIN32
char* strndup (const char *s, size_t n)
{
	size_t len = strnlen (s, n);
	char *new = (char *) malloc (len + 1);
	if (new == NULL)
		return NULL;
	new[len] = '\0';
	return (char *) memcpy (new, s, len);
}

int _vscprintf_so(const char * format, va_list pargs) {
	int retval;
	va_list argcopy;
	va_copy(argcopy, pargs);
	retval = vsnprintf(NULL, 0, format, argcopy);
	va_end(argcopy);
	return retval;
}

int vasprintf(char **strp, const char *fmt, va_list ap) {
	int len = _vscprintf_so(fmt, ap);
	if (len == -1) return -1;
	char *str = malloc((size_t) len + 1);
	if (!str) return -1;
	int r = vsnprintf(str, len + 1, fmt, ap); /* "secure" version of vsprintf */
	if (r == -1) return free(str), -1;
	*strp = str;
	return r;
}

int asprintf(char *strp[], const char *fmt, ...) {
	va_list ap;
	va_start(ap, fmt);
	int r = vasprintf(strp, fmt, ap);
	va_end(ap);
	return r;
}
#endif
/*
   void decompErr(){
#pragma omp critical
{
err = 1;
sprintf(errString,"Decompression error. Error code: %d ChunkName: %s/%s\n",uncErr,folderName,cI.chunkNames[f]);
}
}*/

struct chunkInfo{
	char** chunkNames;
	int64_t numChunks;
};

struct chunkAxisVals{
	uint64_t x;
	uint64_t y;
	uint64_t z;
};

struct chunkAxisVals getChunkAxisVals(char* fileName){
	struct chunkAxisVals cAV;
	char* ptr;
	cAV.x = strtol(fileName, &ptr, 10);
	ptr++;
	cAV.y = strtol(ptr, &ptr, 10);
	ptr++;
	cAV.z = strtol(ptr, &ptr, 10);
	return cAV;
}

struct chunkInfo getChunkInfo(const char* folderName, uint64_t startX, uint64_t startY, uint64_t startZ, uint64_t endX, uint64_t endY,uint64_t endZ,uint64_t chunkXSize,uint64_t chunkYSize,uint64_t chunkZSize){
	struct chunkInfo cI;
	cI.numChunks = 0;
	cI.chunkNames = NULL;

	uint64_t xStartAligned = startX-(startX%chunkXSize);
	uint64_t yStartAligned = startY-(startY%chunkYSize);
	uint64_t zStartAligned = startZ-(startZ%chunkZSize);
	uint64_t xStartChunk = (xStartAligned/chunkXSize);
	uint64_t yStartChunk = (yStartAligned/chunkYSize);
	uint64_t zStartChunk = (zStartAligned/chunkZSize);

	uint64_t xEndAligned = endX;
	uint64_t yEndAligned = endY;
	uint64_t zEndAligned = endZ;

	if(xEndAligned%chunkXSize) xEndAligned = endX-(endX%chunkXSize)+chunkXSize;
	if(yEndAligned%chunkYSize) yEndAligned = endY-(endY%chunkYSize)+chunkYSize;
	if(zEndAligned%chunkZSize) zEndAligned = endZ-(endZ%chunkZSize)+chunkZSize;
	uint64_t xEndChunk = (xEndAligned/chunkXSize);
	uint64_t yEndChunk = (yEndAligned/chunkYSize);
	uint64_t zEndChunk = (zEndAligned/chunkZSize);

	uint64_t xChunks = (xEndChunk-xStartChunk);
	uint64_t yChunks = (yEndChunk-yStartChunk);
	uint64_t zChunks = (zEndChunk-zStartChunk);

	uint64_t file_count = xChunks*yChunks*zChunks;

	char** chunkNames = malloc(file_count*sizeof(char*));
	#pragma omp parallel for collapse(3)
	for(uint64_t x = xStartChunk; x < xEndChunk; x++){
		for(uint64_t y = yStartChunk; y < yEndChunk; y++){
			for(uint64_t z = zStartChunk; z < zEndChunk; z++){
				uint64_t currFile = (z-zStartChunk)+((y-yStartChunk)*zChunks)+((x-xStartChunk)*yChunks*zChunks);
				asprintf(&chunkNames[currFile],"%llu.%llu.%llu",x,y,z);
			}
		}
	}
	cI.chunkNames = chunkNames;
	cI.numChunks = file_count;
	return cI;
}

void setChunkShapeFromJSON(cJSON *json, uint64_t *x, uint64_t *y, uint64_t *z){
	*x = json->child->valueint;
	*y = json->child->next->valueint;
	*z = json->child->next->next->valueint;
}

void setDTypeFromJSON(cJSON *json, char* dtype){
	dtype[0] = json->valuestring[0];
	dtype[1] = json->valuestring[1];
	dtype[2] = json->valuestring[2];
	dtype[3] = json->valuestring[3];
}

void setOrderFromJSON(cJSON *json, char* order){
	*order = json->valuestring[0];
}

void setShapeFromJSON(cJSON *json, uint64_t *x, uint64_t *y, uint64_t *z){
	*x = json->child->valueint;
	*y = json->child->next->valueint;
	*z = json->child->next->next->valueint;
}
void setCnameFromJSON(cJSON *json, char** cname){
	cJSON *jsonItem = json->child;


	while(jsonItem){
		if(!strcmp(jsonItem->string,"cname")){
			*cname = strdup(jsonItem->valuestring);
			return;
		}
		// For gzip
		if(!strcmp(jsonItem->string,"id") && !strcmp(jsonItem->valuestring,"gzip")){     
			*cname = strdup(jsonItem->valuestring);
			return;
		}
		jsonItem = jsonItem->next;
	} 
	printf("Compressor: \"%s\" is not currently supported\n",*cname);

}

uint64_t* getImageSize(const char* fileName){
	uint64_t* dim = (uint64_t*)malloc(3*sizeof(uint64_t));
	char* zArray = ".zarray";
    char* fnFull = (char*)malloc(strlen(fileName)+9);
    fnFull[0] = '\0';
    char fileSepS[2];
    fileSepS[0] = fileSep;
    fileSepS[1] = '\0';

    strcat(fnFull,fileName);
    strcat(fnFull,fileSepS);
    strcat(fnFull,zArray);

    FILE *fileptr = fopen(fnFull, "rb");
    if(!fileptr){
		printf("Failed to open JSON File: %s\n",fnFull);
		return NULL;
	}
    free(fnFull);

    fseek(fileptr, 0, SEEK_END);
    long filelen = ftell(fileptr);
    rewind(fileptr);
    char* buffer = (char *)malloc((filelen));
    fread(buffer, filelen, 1, fileptr);
    fclose(fileptr);
    cJSON *json = cJSON_ParseWithLength(buffer,filelen);
    uint8_t flag = 0;

    while(!flag){
        if(!json->string){
            json = json->child;
            continue;
        }
        if(!strcmp(json->string,"shape")){
            setShapeFromJSON(json, dim, dim+1, dim+2);
            flag = 1;
        }
        json = json->next;
    }
    cJSON_Delete(json);
	return dim;

}

uint64_t getDataType(const char* fileName){
	char* dtype = malloc(4*sizeof(char));
	char* zArray = ".zarray";
    char* fnFull = (char*)malloc(strlen(fileName)+9);
    fnFull[0] = '\0';
    char fileSepS[2];
    fileSepS[0] = fileSep;
    fileSepS[1] = '\0';

    strcat(fnFull,fileName);
    strcat(fnFull,fileSepS);
    strcat(fnFull,zArray);

    FILE *fileptr = fopen(fnFull, "rb");
    if(!fileptr){
        printf("Failed to open JSON File: %s\n",fnFull);
        return 0;
    }
    free(fnFull);

    fseek(fileptr, 0, SEEK_END);
    long filelen = ftell(fileptr);
    rewind(fileptr);
    char* buffer = (char *)malloc((filelen));
    fread(buffer, filelen, 1, fileptr);
    fclose(fileptr);
    cJSON *json = cJSON_ParseWithLength(buffer,filelen);
    uint8_t flag = 0;

    while(!flag){
        if(!json->string){
            json = json->child;
            continue;
        }
        if(!strcmp(json->string,"dtype")){
            setDTypeFromJSON(json, dtype);
            flag = 1;
        }
        json = json->next;
    }
    cJSON_Delete(json);
	
	uint64_t bits = 0;
    if(dtype[1] == 'u' && dtype[2] == '1'){
        bits = 8;
    }
    else if(dtype[1] == 'u' && dtype[2] == '2'){
        bits = 16;
    }
    else if(dtype[1] == 'f' && dtype[2] == '4'){
        bits = 32;
    }
    else if(dtype[1] == 'f' && dtype[2] == '8'){
        bits = 64;
    }
	free(dtype);
	return bits;

}

void setValuesFromJSON(const char* fileName,uint64_t *chunkXSize,uint64_t *chunkYSize,uint64_t *chunkZSize,char* dtype,char* order,uint64_t *shapeX,uint64_t *shapeY,uint64_t *shapeZ,char** cname){
	char* zArray = ".zarray";
	char* fnFull = (char*)malloc(strlen(fileName)+9);
	fnFull[0] = '\0';
	char fileSepS[2];
	fileSepS[0] = fileSep;
	fileSepS[1] = '\0';

	strcat(fnFull,fileName);
	strcat(fnFull,fileSepS);
	strcat(fnFull,zArray);

	FILE *fileptr = fopen(fnFull, "rb");
	if(!fileptr) printf("Failed to open JSON File: %s\n",fnFull);
	free(fnFull);

	fseek(fileptr, 0, SEEK_END);
	long filelen = ftell(fileptr);
	rewind(fileptr);
	char* buffer = (char *)malloc((filelen));
	fread(buffer, filelen, 1, fileptr);
	fclose(fileptr);
	cJSON *json = cJSON_ParseWithLength(buffer,filelen);
	uint8_t flags[5] = {0,0,0,0,0};

	while(!(flags[0] && flags[1] && flags[2] && flags[3] && flags[4])){
		if(!json->string){
			json = json->child;
			continue;
		}
		else if(!strcmp(json->string,"chunks")){
			setChunkShapeFromJSON(json, chunkXSize,chunkYSize,chunkZSize);
			flags[0] = 1;
		}
		else if(!strcmp(json->string,"dtype")){
			setDTypeFromJSON(json, dtype);
			flags[1] = 1;
		}
		else if(!strcmp(json->string,"order")){
			setOrderFromJSON(json, order);
			flags[2] = 1;
		}
		else if(!strcmp(json->string,"shape")){
			setShapeFromJSON(json, shapeX,shapeY,shapeZ);
			flags[3] = 1;
		}
		else if(!strcmp(json->string,"compressor")){
			setCnameFromJSON(json, cname);
			flags[4] = 1;
		}
		json = json->next;
	}
	cJSON_Delete(json);
}

void parallelReadZarr(void* zarr, const char* folderName,uint64_t startX, uint64_t startY, uint64_t startZ, uint64_t endX, uint64_t endY,uint64_t endZ,uint64_t chunkXSize,uint64_t chunkYSize,uint64_t chunkZSize,uint64_t shapeX,uint64_t shapeY,uint64_t shapeZ, uint64_t bits, char order, char* cname){
	uint64_t bytes = (bits/8);

	char fileSepS[2];
	fileSepS[0] = fileSep;
	fileSepS[1] = '\0';

	/* Initialize the Blosc compressor */
	int32_t numWorkers = omp_get_max_threads();
	blosc2_init();
	blosc2_set_nthreads(numWorkers);

	struct chunkInfo cI = getChunkInfo(folderName, startX, startY, startZ, endX, endY, endZ,chunkXSize,chunkYSize,chunkZSize);
	if(!cI.chunkNames) printf("File \"%s\" cannot be opened",folderName);

	int32_t batchSize = (cI.numChunks-1)/numWorkers+1;
	uint64_t s = chunkXSize*chunkYSize*chunkZSize;
	uint64_t sB = s*bytes;
	int32_t w;
	int err = 0;
	char errString[10000];
	#pragma omp parallel for if(numWorkers<=cI.numChunks)
	for(w = 0; w < numWorkers; w++){
		/*
		   if(strcmp(cname,"gzip")){
		   bufferDest = mallocDynamic(s,bits);
		   }
		   else{
		   bufferDest = calloc(sB,1);
		   }
		 */
		void* bufferDest = malloc(sB);
		uint64_t lastFileLen = 0;
		char *buffer = NULL;
		for(int64_t f = w*batchSize; f < (w+1)*batchSize; f++){
			if(f>=cI.numChunks || err) break;
			struct chunkAxisVals cAV = getChunkAxisVals(cI.chunkNames[f]);

			//malloc +2 for null term and filesep
			char *fileName = malloc(strlen(folderName)+strlen(cI.chunkNames[f])+2);
			fileName[0] = '\0';
			strcat(fileName,folderName);
			strcat(fileName,fileSepS);
			strcat(fileName,cI.chunkNames[f]);

			FILE *fileptr = fopen(fileName, "rb");
			if(!fileptr){
				#pragma omp critical
				{
					err = 1;
					sprintf(errString,"Could not open file: %s\n",fileName);
				}
				break;
			}
			free(fileName);

			fseek(fileptr, 0, SEEK_END);
			long filelen = ftell(fileptr);
			rewind(fileptr);
			if(lastFileLen < filelen){
				free(buffer);
				buffer = (char*) malloc(filelen);
				lastFileLen = filelen;
			}
			fread(buffer, filelen, 1, fileptr);
			fclose(fileptr);


			// Decompress
			int64_t dsize = -1;
			int uncErr = 0;
			if(strcmp(cname,"gzip")){
				dsize = blosc2_decompress(buffer, filelen, bufferDest, sB);
			}
			else{
				dsize = sB;
				z_stream stream;
				stream.zalloc = Z_NULL;
				stream.zfree = Z_NULL;
				stream.opaque = Z_NULL;
				stream.avail_in = (uInt)filelen;
				stream.avail_out = (uInt)dsize;
				while(stream.avail_in > 0){

					dsize = sB;

					stream.next_in = (uint8_t*)buffer+(filelen-stream.avail_in);
					stream.next_out = (uint8_t*)bufferDest+(sB-stream.avail_out);

					uncErr = inflateInit2(&stream, 32);
					if(uncErr){
						#pragma omp critical
						{
							err = 1;
							sprintf(errString,"Decompression error. Error code: %d ChunkName: %s/%s\n",uncErr,folderName,cI.chunkNames[f]);
						}
						break;
					}

					uncErr = inflate(&stream, Z_NO_FLUSH);

					if(uncErr != Z_STREAM_END){
						#pragma omp critical
						{
							err = 1;
							sprintf(errString,"Decompression error. Error code: %d ChunkName: %s/%s\n",uncErr,folderName,cI.chunkNames[f]);
						}
						break;
					}
				}
				if(inflateEnd(&stream)){
					#pragma omp critical
					{
						err = 1;
						sprintf(errString,"Decompression error. Error code: %d ChunkName: %s/%s\n",uncErr,folderName,cI.chunkNames[f]);
					}
					break;
				}
			}


			if(dsize < 0){
				#pragma omp critical
				{
					err = 1;
					sprintf(errString,"Decompression error. Error code: %lu ChunkName: %s/%s\n",dsize,folderName,cI.chunkNames[f]);
				}
				break;
			}

			//printf("ChunkName: %s\n",cI.chunkNames[f]);
			//printf("w: %d b: %d\n",w,f);
			if(order == 'F'){
				for(int64_t z = cAV.z*chunkZSize; z < (cAV.z+1)*chunkZSize; z++){
					if(z>=endZ) break;
					else if(z<startZ) continue;
					for(int64_t y = cAV.y*chunkYSize; y < (cAV.y+1)*chunkYSize; y++){
						if(y>=endY) break;
						else if(y<startY) continue;
						if(((cAV.x*chunkXSize) < startX && ((cAV.x+1)*chunkXSize) > startX) || (cAV.x+1)*chunkXSize>endX){
							if(((cAV.x*chunkXSize) < startX && ((cAV.x+1)*chunkXSize) > startX) && (cAV.x+1)*chunkXSize>endX){
								memcpy((uint8_t*)zarr+((((cAV.x*chunkXSize)-startX+(startX%chunkXSize))+((y-startY)*shapeX)+((z-startZ)*shapeX*shapeY))*bytes),(uint8_t*)bufferDest+(((startX%chunkXSize)+((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),((endX%chunkXSize)-(startX%chunkXSize))*bytes);
							}
							else if((cAV.x+1)*chunkXSize>endX){
								memcpy((uint8_t*)zarr+((((cAV.x*chunkXSize)-startX)+((y-startY)*shapeX)+((z-startZ)*shapeX*shapeY))*bytes),(uint8_t*)bufferDest+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(endX%chunkXSize)*bytes);
							}
							else if((cAV.x*chunkXSize) < startX && ((cAV.x+1)*chunkXSize) > startX){
								memcpy((uint8_t*)zarr+((((cAV.x*chunkXSize-startX+(startX%chunkXSize)))+((y-startY)*shapeX)+((z-startZ)*shapeX*shapeY))*bytes),(uint8_t*)bufferDest+(((startX%chunkXSize)+((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(chunkXSize-(startX%chunkXSize))*bytes);
							}
						}
						else{
							memcpy((uint8_t*)zarr+((((cAV.x*chunkXSize)-startX)+((y-startY)*shapeX)+((z-startZ)*shapeX*shapeY))*bytes),(uint8_t*)bufferDest+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),chunkXSize*bytes);
						}
					}
				}

			}
			else if (order == 'C'){
				for(int64_t x = cAV.x*chunkXSize; x < (cAV.x+1)*chunkXSize; x++){
					if(x>=endX) break;
					else if(x<startX) continue;
					for(int64_t y = cAV.y*chunkYSize; y < (cAV.y+1)*chunkYSize; y++){
						if(y>=endY) break;
						else if(y<startY) continue;
						for(int64_t z = cAV.z*chunkZSize; z < (cAV.z+1)*chunkZSize; z++){
							if(z>=endZ) break;
							else if(z<startZ) continue;
							switch(bytes){
								case 1:
									((uint8_t*)zarr)[((x-startX)+((y-startY)*shapeX)+((z-startZ)*shapeX*shapeY))] = ((uint8_t*)bufferDest)[((z%chunkZSize)+((y%chunkYSize)*chunkZSize)+((x%chunkXSize)*chunkZSize*chunkYSize))];
									break;
								case 2:
									((uint16_t*)zarr)[((x-startX)+((y-startY)*shapeX)+((z-startZ)*shapeX*shapeY))] = ((uint16_t*)bufferDest)[((z%chunkZSize)+((y%chunkYSize)*chunkZSize)+((x%chunkXSize)*chunkZSize*chunkYSize))];
									break;
								case 4:
									((float*)zarr)[((x-startX)+((y-startY)*shapeX)+((z-startZ)*shapeX*shapeY))] = ((float*)bufferDest)[((z%chunkZSize)+((y%chunkYSize)*chunkZSize)+((x%chunkXSize)*chunkZSize*chunkYSize))];
									break;
								case 8:
									((double*)zarr)[((x-startX)+((y-startY)*shapeX)+((z-startZ)*shapeX*shapeY))] = ((double*)bufferDest)[((z%chunkZSize)+((y%chunkYSize)*chunkZSize)+((x%chunkXSize)*chunkZSize*chunkYSize))];
									break;
							}

						}
					}
				}

			}

		}
		free(bufferDest);
		free(buffer);
	}
	#pragma omp parallel for
	for(int i = 0; i < cI.numChunks; i++){
		free(cI.chunkNames[i]);
	}
	free(cI.chunkNames);

	/* After using it, destroy the Blosc environment */
	blosc2_destroy();

	if(err) printf("%s",errString);
}

// TODO: FIX MEMORY LEAKS
void* readZarrParallelHelper(const char* folderName, uint64_t startX, uint64_t startY, uint64_t startZ, uint64_t endX, uint64_t endY, uint64_t endZ, uint8_t imageJIm)
{
	char* cname = NULL;
	
	uint64_t shapeX = 0;
	uint64_t shapeY = 0;
	uint64_t shapeZ = 0;
	uint64_t chunkXSize = 0;
	uint64_t chunkYSize = 0;
	uint64_t chunkZSize = 0;
	char dtype[4];
	char order;
	setValuesFromJSON(folderName,&chunkXSize,&chunkYSize,&chunkZSize,dtype,&order,&shapeX,&shapeY,&shapeZ,&cname);
	//if(endX > shapeX || endY > shapeY || endZ > shapeZ) mexErrMsgIdAndTxt("zarr:inputError","Upper bound is invalid");
	//if(nrhs == 1){
	// If the ends are 0 then default to setting them to the shape from the .zarray file
	if(!endX) endX = shapeX;
	if(!endY) endY = shapeY;
	if(!endZ) endZ = shapeZ;
	//}
	uint64_t dim[3];
	shapeX = endX-startX;
	shapeY = endY-startY;
	shapeZ = endZ-startZ;
	dim[0] = shapeX;
	dim[1] = shapeY;
	dim[2] = shapeZ;

	uint64_t bits = 0;
	if(dtype[1] == 'u' && dtype[2] == '1'){
		bits = 8;
	}
	else if(dtype[1] == 'u' && dtype[2] == '2'){
		bits = 16;
	}
	else if(dtype[1] == 'f' && dtype[2] == '4'){
		bits = 32;
	}
	else if(dtype[1] == 'f' && dtype[2] == '8'){
		bits = 64;
	}
	else{
		//mexErrMsgIdAndTxt("tiff:dataTypeError","Data type not suppported");
		return NULL;
	}
	void* zarr = malloc(dim[0]*dim[1]*dim[2]*(bits/8));
	parallelReadZarr(zarr,folderName,startX,startY,startZ,endX,endY,endZ,chunkXSize,chunkYSize,chunkZSize,shapeX,shapeY,shapeZ,bits,order,cname);
	// May need to add a check for if the data is f order or c order for ImageJ
	// For the c order images I have tested, we also have to do this flip for now
	if(imageJIm /*&& (order == 'F' || order == 'f')*/){
		void* zarrC = malloc(dim[0]*dim[1]*dim[2]*(bits/8));
		#pragma omp parallel for
		for(uint64_t k = 0; k < dim[2]; k++){
			for(uint64_t j = 0; j < dim[1]; j++){
				for(uint64_t i = 0; i < dim[0]; i++){
					switch(bits){
                        case 8:
                            ((uint8_t*)zarrC)[j+(i*dim[1])+(k*dim[1]*dim[0])] = ((uint8_t*)zarr)[i+(j*dim[0])+(k*dim[1]*dim[0])];
                            break;
                        case 16:
                            ((uint16_t*)zarrC)[j+(i*dim[1])+(k*dim[1]*dim[0])] = ((uint16_t*)zarr)[i+(j*dim[0])+(k*dim[1]*dim[0])];
                            break;
                        case 32:
                            ((float*)zarrC)[j+(i*dim[1])+(k*dim[1]*dim[0])] = ((float*)zarr)[i+(j*dim[0])+(k*dim[1]*dim[0])];
                            break;
                        case 64:
                            ((double*)zarrC)[j+(i*dim[1])+(k*dim[1]*dim[0])] = ((double*)zarr)[i+(j*dim[0])+(k*dim[1]*dim[0])];
                            break;
                    }
				}
			}	
		}
		free(zarr);
		return zarrC;
	}
	return zarr;

}

void* readZarrParallelWrapper(const char* fileName){
	return readZarrParallelHelper(fileName,0,0,0,0,0,0,0);
}

void* readZarrParallelWrapperImageJ(const char* fileName){
	return readZarrParallelHelper(fileName,0,0,0,0,0,0,1);
}
