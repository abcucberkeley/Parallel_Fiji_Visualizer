
#include "parallelWriteZarr.h"
#include "parallelReadZarr.h"
#include "helperFunctions.h"
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <dirent.h>
#include <string.h>
#include <blosc.h>
#include <cjson/cJSON.h>
#include <omp.h>
#ifdef __linux__
#include <uuid/uuid.h>
#endif
#ifdef _WIN32
#include <sys/time.h>
#endif
#include <sys/stat.h>

#include "zlib.h"

//compile
//mex -v COPTIMFLAGS="-DNDEBUG -O3" CFLAGS='$CFLAGS -fopenmp -O3' LDFLAGS='$LDFLAGS -fopenmp -O3' '-I/global/home/groups/software/sl-7.x86_64/modules/cBlosc/2.0.4/include/' '-I/global/home/groups/software/sl-7.x86_64/modules/cBlosc/zarr/include/' '-I/global/home/groups/software/sl-7.x86_64/modules/cJSON/1.7.15/include/' '-L/global/home/groups/software/sl-7.x86_64/modules/cBlosc/zarr/lib' -lblosc '-L/global/home/groups/software/sl-7.x86_64/modules/cBlosc/2.0.4/lib64' -lblosc2 '-L/global/home/groups/software/sl-7.x86_64/modules/cJSON/1.7.15/lib64' -lcjson -luuid parallelWriteZarr.c helperFunctions.c parallelReadZarr.c

//With zlib
//mex -v COPTIMFLAGS="-DNDEBUG -O3" CFLAGS='$CFLAGS -fopenmp -O3' LDFLAGS='$LDFLAGS -fopenmp -O3' '-I/global/home/groups/software/sl-7.x86_64/modules/cBlosc/2.0.4/include/' '-I/global/home/groups/software/sl-7.x86_64/modules/cBlosc/zarr/include/' '-I/global/home/groups/software/sl-7.x86_64/modules/cJSON/1.7.15/include/' '-L/global/home/groups/software/sl-7.x86_64/modules/cBlosc/zarr/lib' -lblosc '-L/global/home/groups/software/sl-7.x86_64/modules/cBlosc/2.0.4/lib64' -lblosc2 '-L/global/home/groups/software/sl-7.x86_64/modules/cJSON/1.7.15/lib64' -lcjson -luuid -lz parallelWriteZarr.c helperFunctions.c parallelReadZarr.c

//mex -v COPTIMFLAGS="-O3 -fwrapv -DNDEBUG" CFLAGS='$CFLAGS -O3 -fopenmp' LDFLAGS='$LDFLAGS -O3 -fopenmp' '-I/global/home/groups/software/sl-7.x86_64/modules/cBlosc/2.0.4/include/' '-L/global/home/groups/software/sl-7.x86_64/modules/cBlosc/2.0.4/lib64' -lblosc2 zarrMex.c
//
//Windows
//mex -v COPTIMFLAGS="-O3 -DNDEBUG" CFLAGS='$CFLAGS -O3 -fopenmp' LDFLAGS='$LDFLAGS -O3 -fopenmp' '-IC:\Program Files (x86)\bloscZarr\include' '-LC:\Program Files (x86)\bloscZarr\lib' -lblosc '-IC:\Program Files (x86)\cJSON\include\' '-LC:\Program Files (x86)\cJSON\lib' -lcjson '-IC:\Program Files (x86)\blosc\include' '-LC:\Program Files (x86)\blosc\lib' -lblosc2 parallelWriteZarr.c parallelReadZarr.c helperFunctions.c
void parallelWriteZarr(void* zarr, char* folderName,uint64_t startX, uint64_t startY, uint64_t startZ, uint64_t endX, uint64_t endY,uint64_t endZ,uint64_t chunkXSize,uint64_t chunkYSize,uint64_t chunkZSize,uint64_t shapeX,uint64_t shapeY,uint64_t shapeZ,uint64_t origShapeX,uint64_t origShapeY,uint64_t origShapeZ, uint64_t bits, char order, uint8_t useUuid, uint8_t crop, char* cname){
	char fileSepS[2];
	const char fileSep =
		#ifdef _WIN32
		'\\';
	#else
	'/';
	#endif
	fileSepS[0] = fileSep;
	fileSepS[1] = '\0';

	uint64_t bytes = (bits/8);

	/* Initialize the Blosc compressor */
	int32_t numWorkers = omp_get_max_threads();

	struct chunkInfo cI = getChunkInfo(folderName,startX,startY,startZ,endX,endY,endZ,chunkXSize,chunkYSize,chunkZSize);
	//if(!cI.chunkNames) mexErrMsgIdAndTxt("zarr:inputError","File \"%s\" cannot be opened",folderName);
	char** chunkNamesUuid = malloc(cI.numChunks*sizeof(char*));

	int32_t batchSize = (cI.numChunks-1)/numWorkers+1;
	uint64_t s = chunkXSize*chunkYSize*chunkZSize;
	uint64_t sB = s*bytes;
	int32_t w;

	uint64_t xRest = 0;
	uint64_t yRest = 0;
	uint64_t zRest = 0;

	uint64_t uuidLen;
	#ifdef __linux__
	uuidLen = 36;
	uuid_t binuuid;
	uuid_generate_random(binuuid);
	char *uuid = malloc(uuidLen+1);
	uuid_unparse(binuuid, uuid);
	#endif
	#ifdef _WIN32
	uuidLen = 5;
	char *uuid = malloc(uuidLen+1);
	char *seedArr = malloc(1000);
	struct timeval cSeed;
	gettimeofday(&cSeed,NULL);
	int nChars = sprintf(seedArr,"%d%d",cSeed.tv_sec,cSeed.tv_usec);
	int aSeed = 0;
	char* ptr;
	if(nChars > 9)
		aSeed = strtol(seedArr+nChars-10, &ptr, 9);
	else aSeed = strtol(seedArr, &ptr, 9);
	srand(aSeed);
	sprintf(uuid,"%.5d",rand() % 99999);
	#endif
	int err = 0;
	char errString[10000];
	#pragma omp parallel for if(numWorkers<=cI.numChunks)
	for(w = 0; w < numWorkers; w++){
		void* chunkUnC = mallocDynamic(s,bits);
		void* chunkC = malloc(sB+BLOSC_MAX_OVERHEAD);
		for(int64_t f = w*batchSize; f < (w+1)*batchSize; f++){
			if(f>=cI.numChunks || err) break;
			struct chunkAxisVals cAV = getChunkAxisVals(cI.chunkNames[f]);
			void* cRegion = NULL;
			if(crop && ((((cAV.x)*chunkXSize) < startX || ((cAV.x+1)*chunkXSize > endX && endX < origShapeX))
						|| (((cAV.y)*chunkYSize) < startY || ((cAV.y+1)*chunkYSize > endY && endY < origShapeY))
						|| (((cAV.z)*chunkZSize) < startZ || ((cAV.z+1)*chunkZSize > endZ && endZ < origShapeZ)))){
				cRegion = parallelReadZarrWrapper(folderName, crop, ((cAV.x)*chunkXSize)+1, ((cAV.y)*chunkYSize)+1, ((cAV.z)*chunkZSize)+1, (cAV.x+1)*chunkXSize, (cAV.y+1)*chunkYSize, (cAV.z+1)*chunkZSize);
			}
			if(order == 'F'){
				for(int64_t z = cAV.z*chunkZSize; z < (cAV.z+1)*chunkZSize; z++){
					if(z>=endZ){
						if(crop){
							if((cAV.z+1)*chunkZSize > origShapeZ){
								memcpy((uint8_t*)chunkUnC+((((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(uint8_t*)cRegion+((((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),((origShapeZ-z)*chunkXSize*chunkYSize)*bytes);
								uint64_t zRest = ((cAV.z+1)*chunkZSize)-origShapeZ;
								memset((uint8_t*)chunkUnC+(((z%chunkZSize)*chunkXSize*chunkYSize)*bytes),0,(zRest*(chunkXSize*chunkYSize))*bytes);
							}
							else{
								memcpy((uint8_t*)chunkUnC+((((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(uint8_t*)cRegion+((((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),((((cAV.z+1)*chunkZSize)-z)*chunkXSize*chunkYSize)*bytes);
							}
						}
						else{
							uint64_t zRest = ((cAV.z+1)*chunkZSize)-z;
							memset((uint8_t*)chunkUnC+(((z%chunkZSize)*chunkXSize*chunkYSize)*bytes),0,(zRest*(chunkXSize*chunkYSize))*bytes);
						}
						break;
					}
					else if(z<startZ){
						if(crop){
							memcpy((uint8_t*)chunkUnC+(((z%chunkZSize)*chunkXSize*chunkYSize)*bytes),(uint8_t*)cRegion+(((z%chunkZSize)*chunkXSize*chunkYSize)*bytes),((startZ-z)*chunkXSize*chunkYSize)*bytes);
						}
						else{
							memset((uint8_t*)chunkUnC+(((z%chunkZSize)*chunkXSize*chunkYSize)*bytes),0,((startZ-z)*(chunkXSize*chunkYSize))*bytes);
						}
						z = startZ-1;
						continue;
					}
					for(int64_t y = cAV.y*chunkYSize; y < (cAV.y+1)*chunkYSize; y++){
						if(y>=endY){
							if(crop){
								if((cAV.y+1)*chunkYSize > origShapeY){
									memcpy((uint8_t*)chunkUnC+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(uint8_t*)cRegion+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),((origShapeY-y)*chunkXSize)*bytes);
									uint64_t yRest = ((cAV.y+1)*chunkYSize)-origShapeY;
									memset((uint8_t*)chunkUnC+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),0,(yRest*(chunkXSize))*bytes);
								}
								else{
									memcpy((uint8_t*)chunkUnC+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(uint8_t*)cRegion+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),((((cAV.y+1)*chunkYSize)-y)*chunkXSize)*bytes);
								}
							}
							else{
								uint64_t yRest = ((cAV.y+1)*chunkYSize)-y;
								memset((uint8_t*)chunkUnC+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),0,(yRest*chunkXSize)*bytes);
							}
							break;
						}
						else if(y<startY){
							if(crop){
								memcpy((uint8_t*)chunkUnC+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(uint8_t*)cRegion+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),((startY-y)*chunkXSize)*bytes);
							}
							else{
								memset((uint8_t*)chunkUnC+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),0,(startY-y)*bytes);
							}
							y = startY-1;
							continue;
						}

						if(((cAV.x*chunkXSize) < startX && ((cAV.x+1)*chunkXSize) > startX) || (cAV.x+1)*chunkXSize>endX){
							if(((cAV.x*chunkXSize) < startX && ((cAV.x+1)*chunkXSize) > startX) && (cAV.x+1)*chunkXSize>endX){
								if(crop){
									memcpy((uint8_t*)chunkUnC+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(uint8_t*)cRegion+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(startX%chunkXSize)*bytes);
									memcpy((uint8_t*)chunkUnC+(((startX%chunkXSize)+((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(uint8_t*)zarr+((((cAV.x*chunkXSize)-startX+(startX%chunkXSize))+((y-startY)*shapeX)+((z-startZ)*shapeX*shapeY))*bytes),((endX%chunkXSize)-(startX%chunkXSize))*bytes);
									memcpy((uint8_t*)chunkUnC+(((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))+(endX%chunkXSize))*bytes),(uint8_t*)cRegion+(((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))+(endX%chunkXSize))*bytes),(chunkXSize-(endX%chunkXSize))*bytes);
								}
								else{
									memset((uint8_t*)chunkUnC+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),0,(startX%chunkXSize)*bytes);
									memcpy((uint8_t*)chunkUnC+(((startX%chunkXSize)+((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(uint8_t*)zarr+((((cAV.x*chunkXSize)-startX+(startX%chunkXSize))+((y-startY)*shapeX)+((z-startZ)*shapeX*shapeY))*bytes),((endX%chunkXSize)-(startX%chunkXSize))*bytes);
									memset((uint8_t*)chunkUnC+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))+(endX%chunkXSize)*bytes),0,(chunkXSize-(endX%chunkXSize))*bytes);
								}
							}
							else if((cAV.x+1)*chunkXSize>endX){
								if(crop){
									memcpy((uint8_t*)chunkUnC+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(uint8_t*)zarr+((((cAV.x*chunkXSize)-startX)+((y-startY)*shapeX)+((z-startZ)*shapeX*shapeY))*bytes),(endX-(cAV.x*chunkXSize))*bytes);

									if((cAV.x+1)*chunkXSize > origShapeX){
										memcpy((uint8_t*)chunkUnC+((((endX-(cAV.x*chunkXSize)))+((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(uint8_t*)cRegion+((((endX-(cAV.x*chunkXSize)))+((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(origShapeX-endX)*bytes);
										uint64_t xRest = ((cAV.x+1)*chunkXSize)-origShapeX;
										memset((uint8_t*)chunkUnC+(((origShapeX-(cAV.x*chunkXSize))+((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),0,(xRest)*bytes);
									}
									else{
										memcpy((uint8_t*)chunkUnC+((((endX-(cAV.x*chunkXSize)))+((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(uint8_t*)cRegion+((((endX-(cAV.x*chunkXSize)))+((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(((cAV.x+1)*chunkXSize)-endX)*bytes);
									}
								}
								else{
									memcpy((uint8_t*)chunkUnC+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(uint8_t*)zarr+((((cAV.x*chunkXSize)-startX)+((y-startY)*shapeX)+((z-startZ)*shapeX*shapeY))*bytes),(endX%chunkXSize)*bytes);
									memset((uint8_t*)chunkUnC+(((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))+(endX%chunkXSize))*bytes),0,(chunkXSize-(endX%chunkXSize))*bytes);
								}
							}
							else if((cAV.x*chunkXSize) < startX && ((cAV.x+1)*chunkXSize) > startX){
								if(crop){
									memcpy((uint8_t*)chunkUnC+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(uint8_t*)cRegion+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(startX%chunkXSize)*bytes);
									memcpy((uint8_t*)chunkUnC+(((startX%chunkXSize)+((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(uint8_t*)zarr+((((cAV.x*chunkXSize)-startX+(startX%chunkXSize))+((y-startY)*shapeX)+((z-startZ)*shapeX*shapeY))*bytes),(chunkXSize-(startX%chunkXSize))*bytes);
								}
								else{
									memset((uint8_t*)chunkUnC+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),0,(startX%chunkXSize)*bytes);
									memcpy((uint8_t*)chunkUnC+(((startX%chunkXSize)+((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(uint8_t*)zarr+((((cAV.x*chunkXSize)-startX+(startX%chunkXSize))+((y-startY)*shapeX)+((z-startZ)*shapeX*shapeY))*bytes),(chunkXSize-(startX%chunkXSize))*bytes);
								}
							}
						}
						else{
							memcpy((uint8_t*)chunkUnC+((((y%chunkYSize)*chunkXSize)+((z%chunkZSize)*chunkXSize*chunkYSize))*bytes),(uint8_t*)zarr+((((cAV.x*chunkXSize)-startX)+((y-startY)*shapeX)+((z-startZ)*shapeX*shapeY))*bytes),chunkXSize*bytes);
						}
					}
				}
			}
			else if (order == 'C'){
				for(int64_t x = cAV.x*chunkZSize; x < (cAV.x+1)*chunkXSize; x++){
					for(int64_t y = cAV.y*chunkYSize; y < (cAV.y+1)*chunkYSize; y++){
						for(int64_t z = cAV.z*chunkZSize; z < (cAV.z+1)*chunkZSize; z++){
							switch(bytes){
								case 1:
									if(x>=endX || x<startX || y>= endY || y<startY || z>=endZ || z<startZ){
										((uint8_t*)chunkUnC)[(((z%chunkZSize)+((y%chunkYSize)*chunkZSize)+((x%chunkXSize)*chunkZSize*chunkYSize))*bytes)] = 0;
										continue;
									}
									((uint8_t*)chunkUnC)[(((z%chunkZSize)+((y%chunkYSize)*chunkZSize)+((x%chunkXSize)*chunkZSize*chunkYSize))*bytes)] = ((uint8_t*)zarr)[((x+(y*shapeX)+(z*shapeX*shapeY))*bytes)];
									break;
								case 2:
									if(x>=endX || x<startX || y>= endY || y<startY || z>=endZ || z<startZ){
										((uint16_t*)chunkUnC)[(((z%chunkZSize)+((y%chunkYSize)*chunkZSize)+((x%chunkXSize)*chunkZSize*chunkYSize))*bytes)] = 0;
										continue;
									}
									((uint16_t*)chunkUnC)[(((z%chunkZSize)+((y%chunkYSize)*chunkZSize)+((x%chunkXSize)*chunkZSize*chunkYSize))*bytes)] = ((uint16_t*)zarr)[((x+(y*shapeX)+(z*shapeX*shapeY))*bytes)];                                    
									break;
								case 4:
									if(x>=endX || x<startX || y>= endY || y<startY || z>=endZ || z<startZ){
										((float*)chunkUnC)[(((z%chunkZSize)+((y%chunkYSize)*chunkZSize)+((x%chunkXSize)*chunkZSize*chunkYSize))*bytes)] = 0;
										continue;
									}
									((float*)chunkUnC)[(((z%chunkZSize)+((y%chunkYSize)*chunkZSize)+((x%chunkXSize)*chunkZSize*chunkYSize))*bytes)] = ((float*)zarr)[((x+(y*shapeX)+(z*shapeX*shapeY))*bytes)];                                    
									break;
								case 8:
									if(x>=endX || x<startX || y>= endY || y<startY || z>=endZ || z<startZ){
										((double*)chunkUnC)[(((z%chunkZSize)+((y%chunkYSize)*chunkZSize)+((x%chunkXSize)*chunkZSize*chunkYSize))*bytes)] = 0;
										continue;
									}
									((double*)chunkUnC)[(((z%chunkZSize)+((y%chunkYSize)*chunkZSize)+((x%chunkXSize)*chunkZSize*chunkYSize))*bytes)] = ((double*)zarr)[((x+(y*shapeX)+(z*shapeX*shapeY))*bytes)];                                   
									break;
							}

						}
					}
				}
			}
			//char* compressor = blosc_get_compressor();
			//printf("Thread: %d Compressor: %s\n",w,compressor);

			// Use the same blosc compress as Zarr
			//int64_t csize = blosc_compress(5, BLOSC_SHUFFLE, bytes, sB, chunkUnC, chunkC, sB+BLOSC_MAX_OVERHEAD);
			int64_t csize = 0;
			if(strcmp(cname,"gzip")){
				if(numWorkers<=cI.numChunks){
					csize = blosc_compress_ctx(5, BLOSC_SHUFFLE, bytes, sB, chunkUnC, chunkC, sB+BLOSC_MAX_OVERHEAD,cname,0,1);
				}
				else{
					csize = blosc_compress_ctx(5, BLOSC_SHUFFLE, bytes, sB, chunkUnC, chunkC, sB+BLOSC_MAX_OVERHEAD,cname,0,numWorkers);
				}
			}
			else{
				uint64_t sLength = sB;
				csize = sB+BLOSC_MAX_OVERHEAD;
				//compress2((Bytef*)chunkC,&csize,(Bytef*)chunkUnC,sB,1);
				z_stream stream;
				//memset(&stream, 0, sizeof(stream));
				stream.zalloc = Z_NULL;
				stream.zfree = Z_NULL;
				stream.opaque = Z_NULL;

				char dummy = '\0';  // zlib does not like NULL output buffers (even if the uncompressed data is empty)
				stream.next_in = (uint8_t*)chunkUnC;
				stream.next_out = (uint8_t*)chunkC;

				stream.avail_in = sB;
				stream.avail_out = csize;
				int uncErr = deflateInit2(&stream, 1, Z_DEFLATED, MAX_WBITS + 16, MAX_MEM_LEVEL, Z_DEFAULT_STRATEGY);
				if(uncErr){
					#pragma omp critical
					{
						err = 1;
						sprintf(errString,"Decompression error. Error code: %d ChunkName: %s/%s\n",uncErr,folderName,cI.chunkNames[f]);
					}
					break;
				}

				uncErr = deflate(&stream, Z_FINISH);

				if(uncErr != Z_STREAM_END){
					#pragma omp critical
					{
						err = 1;
						sprintf(errString,"Decompression error. Error code: %d ChunkName: %s/%s\n",uncErr,folderName,cI.chunkNames[f]);
					}
					break;
				}

				if(deflateEnd(&stream)){
					#pragma omp critical
					{
						err = 1;
						sprintf(errString,"Decompression error. Error code: %d ChunkName: %s/%s\n",uncErr,folderName,cI.chunkNames[f]);
					}
					break;
				}
				csize = csize - stream.avail_out;
			}
			//malloc +2 for null term and filesep
			char *fileName = malloc(strlen(folderName)+1+strlen(cI.chunkNames[f])+uuidLen+1);
			fileName[0] = '\0';
			strcat(fileName,folderName);
			strcat(fileName,fileSepS);
			strcat(fileName,cI.chunkNames[f]);

			if(useUuid){
				strcat(fileName,uuid);
				char* fileNameFinal = strndup(fileName,strlen(folderName)+1+strlen(cI.chunkNames[f]));
				FILE *fileptr = fopen(fileName, "w+b");
				fwrite(chunkC,csize,1,fileptr);
				fclose(fileptr);
				rename(fileName,fileNameFinal);
				free(fileNameFinal);
			}
			else{

				FILE *fileptr = fopen(fileName, "w+b");
				fwrite(chunkC,csize,1,fileptr);
				fclose(fileptr);
			}
			free(fileName);
			free(cRegion);
		}
		free(chunkUnC);
		free(chunkC);

	}

	free(uuid);
	#pragma omp parallel for
	for(int i = 0; i < cI.numChunks; i++){
		free(cI.chunkNames[i]);
	}
	free(cI.chunkNames);
	if(err){
		printf("%s",errString);
	} //mexErrMsgIdAndTxt("zarr:threadError",errString);

	/* After using it, destroy the Blosc environment */
	//blosc_destroy();
}

// TODO: FIX MEMORY LEAKS
void writeZarrParallelHelper(char* folderName, void* zarr, uint64_t startX, uint64_t startY, uint64_t startZ, uint64_t endX, uint64_t endY, uint64_t endZ, uint64_t chunkXSize, uint64_t chunkYSize, uint64_t chunkZSize, uint8_t crop, char* cname, uint8_t useUuid, uint64_t bytes, uint8_t imageJIm)
{	
	uint64_t shapeX = 0;
	uint64_t shapeY = 0;
	uint64_t shapeZ = 0;
	char dtype[4];
	char order;
	void* zarrC = NULL;


	dtype[0] = '<';
	if(bytes == 1){
		dtype[1] = 'u';
		dtype[2] = '1';

	}
	else if(bytes == 2){
		dtype[1] = 'u';
		dtype[2] = '2';

	}
	else if(bytes == 4){
		dtype[1] = 'f';
		dtype[2] = '4';

	}
	else if(bytes == 8){
		dtype[1] = 'f';
		dtype[2] = '8';

	}
	dtype[3] = '\0';
	chunkXSize = 256;
	chunkYSize = 256;
	chunkZSize = 256;
	order = 'F';

	char* zArray = ".zarray";
	char* fnFull = (char*)malloc(strlen(folderName)+9);
	fnFull[0] = '\0';
	char fileSepS[2];
	fileSepS[0] = '/';
	fileSepS[1] = '\0';

	strcat(fnFull,folderName);
	strcat(fnFull,fileSepS);
	strcat(fnFull,zArray);

	// lz4 for the compressor if none is specified
	if(!cname) cname = "lz4";
	if(!crop){
		//uint8_t nDims = (uint8_t)mxGetNumberOfDimensions(prhs[1]);
		//if(nDims < 2 || nDims > 3) mexErrMsgIdAndTxt("zarr:inputError","Input data must be 2D or 3D");
		//uint64_t* dims = (uint64_t*)mxGetDimensions(prhs[1]);
		shapeX = endX;
		shapeY = endY;
		shapeZ = endZ;
		
		//else shapeZ = 1;
		//chunkXSize = (uint64_t)*(mxGetPr(prhs[3]));
		//chunkYSize = (uint64_t)*((mxGetPr(prhs[3])+1));
		//chunkZSize = (uint64_t)*((mxGetPr(prhs[3])+2));

		FILE* f = fopen(fnFull,"r");
		if(f) fclose(f);
		else{
			#ifdef __linux__
			mkdir(folderName, 0775);
			#endif
			#ifdef _WIN32
			mkdir(folderName);
			#endif
			chmod(folderName, 0775);
		}


		setJSONValues(folderName,&chunkXSize,&chunkYSize,&chunkZSize,dtype,&order,&shapeX,&shapeY,&shapeZ,cname);
		setValuesFromJSON(folderName,&chunkXSize,&chunkYSize,&chunkZSize,dtype,&order,&shapeX,&shapeY,&shapeZ,&cname);

	}
	else{
		shapeX = endX;
		shapeY = endY;
		shapeZ = endZ;

		FILE* f = fopen(fnFull,"r");
		if(f) fclose(f);
		else {
			#ifdef __linux__
			mkdir(folderName, 0775);
			#endif
			#ifdef _WIN32
			mkdir(folderName);
			#endif
			chmod(folderName, 0775);
			setJSONValues(folderName,&chunkXSize,&chunkYSize,&chunkZSize,dtype,&order,&shapeX,&shapeY, &shapeZ,cname);
		}

		char dtypeT[4];
		for(int i = 0; i < 4; i++) dtypeT[i] = dtype[i];

		setValuesFromJSON(folderName,&chunkXSize,&chunkYSize,&chunkZSize,dtype,&order,&shapeX,&shapeY,&shapeZ,&cname);
		
		if(dtypeT[2] != dtype[2]){
			uint64_t size = (endX-startX)*(endY-startY)*(endZ-startZ);

			uint64_t bitsT = 0;
			if(dtypeT[1] == 'u' && dtypeT[2] == '1') bitsT = 8;
			else if(dtypeT[1] == 'u' && dtypeT[2] == '2') bitsT = 16;
			else if(dtypeT[1] == 'f' && dtypeT[2] == '4') bitsT = 32;
			else if(dtypeT[1] == 'f' && dtypeT[2] == '8') bitsT = 64;
			else{
				printf("%s\n","Cannont convert to passed in data type. Data type not suppported."); 
				return;//mexErrMsgIdAndTxt("tiff:dataTypeError","Cannont convert to passed in data type. Data type not suppported");
			}


			if(dtype[1] == 'u' && dtype[2] == '1'){
				zarrC = malloc(size*sizeof(uint8_t));
				if(bitsT == 16){
					uint16_t* zarrT = (uint16_t*)zarr;
					#pragma omp parallel for
					for(uint64_t i = 0; i < size; i++){
						((uint8_t*)zarrC)[i] = (uint8_t)zarrT[i];
					}
				}
				else if(bitsT == 32){
					float* zarrT = (float*)zarr;
					#pragma omp parallel for
					for(uint64_t i = 0; i < size; i++){
						((uint8_t*)zarrC)[i] = (uint8_t)zarrT[i];
					}
				}
				else if(bitsT == 64){
					double* zarrT = (double*)zarr;
					#pragma omp parallel for
					for(uint64_t i = 0; i < size; i++){
						((uint8_t*)zarrC)[i] = (uint8_t)zarrT[i];
					}
				}
			}
			else if(dtype[1] == 'u' && dtype[2] == '2'){
				zarrC = malloc(size*sizeof(uint16_t));
				if(bitsT == 8){
					uint8_t* zarrT = (uint8_t*)zarr;
					#pragma omp parallel for
					for(uint64_t i = 0; i < size; i++){
						((uint16_t*)zarrC)[i] = (uint16_t)zarrT[i];
					}
				}
				else if (bitsT == 32){
					float* zarrT = (float*)zarr;
					#pragma omp parallel for
					for(uint64_t i = 0; i < size; i++){
						((uint16_t*)zarrC)[i] = (uint16_t)zarrT[i];
					}
				}
				else if (bitsT == 64){
					double* zarrT = (double*)zarr;
					#pragma omp parallel for
					for(uint64_t i = 0; i < size; i++){
						((uint16_t*)zarrC)[i] = (uint16_t)zarrT[i];
					}
				}
			}
			else if(dtype[1] == 'f' && dtype[2] == '4'){
				zarrC = malloc(size*sizeof(float));
				if(bitsT == 8){
					uint8_t* zarrT = (uint8_t*)zarr;
					#pragma omp parallel for
					for(uint64_t i = 0; i < size; i++){
						((float*)zarrC)[i] = (float)zarrT[i];
					}
				}
				else if(bitsT == 16){
					uint16_t* zarrT = (uint16_t*)zarr;
					#pragma omp parallel for
					for(uint64_t i = 0; i < size; i++){
						((float*)zarrC)[i] = (float)zarrT[i];
					}
				}
				else if(bitsT == 64){
					double* zarrT = (double*)zarr;
					#pragma omp parallel for
					for(uint64_t i = 0; i < size; i++){
						((float*)zarrC)[i] = (float)zarrT[i];
					}
				}
			}
			else if(dtype[1] == 'f' && dtype[2] == '8'){
				zarrC = malloc(size*sizeof(double));
				if(bitsT == 8){
					uint8_t* zarrT = (uint8_t*)zarr;
					#pragma omp parallel for
					for(uint64_t i = 0; i < size; i++){
						((double*)zarrC)[i] = (double)zarrT[i];
					}
				}
				else if(bitsT == 16){
					uint16_t* zarrT = (uint16_t*)zarr;
					#pragma omp parallel for
					for(uint64_t i = 0; i < size; i++){
						((double*)zarrC)[i] = (double)zarrT[i];
					}
				}
				else if(bitsT == 32){
					float* zarrT = (float*)zarr;
					#pragma omp parallel for
					for(uint64_t i = 0; i < size; i++){
						((double*)zarrC)[i] = (double)zarrT[i];
					}
				}
			}
			else{
				printf("%s\n","Cannont convert to passed in data type. Data type not suppported");
				return;
				//mexErrMsgIdAndTxt("zarr:dataTypeError","Cannont convert to passed in data type. Data type not suppported");
			}
		}
	}

	free(fnFull);
	uint64_t origShapeX = shapeX;
	uint64_t origShapeY = shapeY;
	uint64_t origShapeZ = shapeZ;
	if(endX > shapeX || endY > shapeY || endZ > shapeZ){
		printf("%s\n","Upper bound is invalid");
		return;
	}
	if(!crop){
		endX = shapeX;
		endY = shapeY;
		endZ = shapeZ;
		startX = 0;
		startY = 0;
		startZ = 0;
	}
	uint64_t dim[3];
	shapeX = endX-startX;
	shapeY = endY-startY;
	shapeZ = endZ-startZ;
	dim[0] = shapeX;
	dim[1] = shapeY;
	dim[2] = shapeZ;
	if(imageJIm && (order == 'F' || order == 'f')){
        free(zarrC); 
		zarrC = malloc(dim[0]*dim[1]*dim[2]*(bytes));
        #pragma omp parallel for
        for(uint64_t k = 0; k < dim[2]; k++){
            for(uint64_t j = 0; j < dim[1]; j++){
                for(uint64_t i = 0; i < dim[0]; i++){
                    switch(bytes){
                        case 1:
                            ((uint8_t*)zarrC)[i+(j*dim[0])+(k*dim[1]*dim[0])] = ((uint8_t*)zarr)[j+(i*dim[1])+(k*dim[1]*dim[0])];
                            break;
                        case 2:
                            ((uint16_t*)zarrC)[i+(j*dim[0])+(k*dim[1]*dim[0])] = ((uint16_t*)zarr)[j+(i*dim[1])+(k*dim[1]*dim[0])];
                            break;
                        case 4:
                            ((float*)zarrC)[i+(j*dim[0])+(k*dim[1]*dim[0])] = ((float*)zarr)[j+(i*dim[1])+(k*dim[1]*dim[0])];
                            break;
                        case 8:
                            ((double*)zarrC)[i+(j*dim[0])+(k*dim[1]*dim[0])] = ((double*)zarr)[j+(i*dim[1])+(k*dim[1]*dim[0])];
                            break;
                    }
                }
            }
        }
       // free(zarr);
        //return zarrC;
    }

	
	if(dtype[1] == 'u' && dtype[2] == '1'){
		uint64_t bits = 8;
		if(zarrC) zarr = zarrC;
		parallelWriteZarr(zarr,folderName,startX,startY,startZ,endX,endY,endZ,chunkXSize,chunkYSize,chunkZSize,shapeX,shapeY,shapeZ, origShapeX, origShapeY,origShapeZ, bits,order,useUuid,crop,cname);
	}
	else if(dtype[1] == 'u' && dtype[2] == '2'){
		uint64_t bits = 16;
		if(zarrC) zarr = zarrC;
		parallelWriteZarr(zarr,folderName,startX,startY,startZ,endX,endY,endZ,chunkXSize,chunkYSize,chunkZSize,shapeX,shapeY,shapeZ, origShapeX, origShapeY,origShapeZ, bits,order,useUuid,crop,cname);
	}
	else if(dtype[1] == 'f' && dtype[2] == '4'){
		uint64_t bits = 32;
		if(zarrC) zarr = zarrC;
		parallelWriteZarr(zarr,folderName,startX,startY,startZ,endX,endY,endZ,chunkXSize,chunkYSize,chunkZSize,shapeX,shapeY,shapeZ, origShapeX, origShapeY,origShapeZ, bits,order,useUuid,crop,cname);
	}
	else if(dtype[1] == 'f' && dtype[2] == '8'){
		uint64_t bits = 64;
		if(zarrC) zarr = zarrC;
		parallelWriteZarr(zarr,folderName,startX,startY,startZ,endX,endY,endZ,chunkXSize,chunkYSize,chunkZSize,shapeX,shapeY,shapeZ, origShapeX, origShapeY,origShapeZ, bits,order,useUuid,crop,cname);
	}
	else{
		printf("%s\n","Data type not suppported");
		return;
		//mexErrMsgIdAndTxt("tiff:dataTypeError","Data type not suppported");
	}


}


void writeZarrParallelWrapper(const char* folderName){
	//writeZarrParallelHelper(folderName);
}
