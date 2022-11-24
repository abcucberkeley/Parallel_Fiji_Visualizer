#include <stdlib.h>
#include <math.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>
#include <limits.h>
//#include <byteswap.h>
#include "parallelReadTiff.h"
//mex -v COPTIMFLAGS="-O3 -fwrapv -DNDEBUG" CFLAGS='$CFLAGS -O3 -fopenmp' LDFLAGS='$LDFLAGS -O3 -fopenmp' '-I/global/home/groups/software/sl-7.x86_64/modules/libtiff/4.1.0/libtiff/' '-L/global/home/groups/software/sl-7.x86_64/modules/libtiff/4.1.0/libtiff/' -ltiff /clusterfs/fiona/matthewmueller/parallelTiffTesting/main.c

void DummyHandler(const char* module, const char* fmt, va_list ap)
{
	// ignore errors and warnings
}

void readTiffParallel(uint64_t x, uint64_t y, uint64_t z, const char* fileName, void* tiff, uint64_t bits, uint64_t startSlice, uint64_t stripSize, uint8_t flipXY){
	int32_t numWorkers = omp_get_max_threads();
	int32_t batchSize = (z-1)/numWorkers+1;
	uint64_t bytes = bits/8;

	int32_t w;
	uint8_t err = 0;
	char errString[10000];
	#pragma omp parallel for
	for(w = 0; w < numWorkers; w++){

		TIFF* tif = TIFFOpen(fileName, "r");
		if(!tif){
			#pragma omp critical
			{
				err = 1;
				sprintf(errString,"Thread %d: File \"%s\" cannot be opened\n",w,fileName);
			}
		}
		void* buffer = malloc(x*stripSize*bytes);
		for(int64_t dir = startSlice+(w*batchSize); dir < startSlice+((w+1)*batchSize); dir++){
			if(dir>=z+startSlice || err) break;

			uint8_t counter = 0;
			while(!TIFFSetDirectory(tif, (uint64_t)dir) && counter<3){
				printf("Thread %d: File \"%s\" Directory \"%ld\" failed to open. Try %d\n",w,fileName,dir,counter+1);
				counter++;
				if(counter == 3){
					#pragma omp critical
					{
						err = 1;
						sprintf(errString,"Thread %d: File \"%s\" cannot be opened\n",w,fileName);
					}
				}
			}
			if(err) break;
			for (int64_t i = 0; i*stripSize < y; i++)
			{

				//loading the data into a buffer
				int64_t cBytes = TIFFReadEncodedStrip(tif, i, buffer, stripSize*x*bytes);
				if(cBytes < 0){
					#pragma omp critical
					{
						//err = 1;
						sprintf(errString,"Thread %d: Strip %ld cannot be read\n",w,i);
					}
					//break;
					//continue;
				}
				if(!flipXY){
					if(cBytes < 0) cBytes = stripSize*x*bytes;
					else memcpy(tiff+(((i*stripSize*x)+((dir-startSlice)*(x*y)))*bytes),buffer,cBytes);
					continue;
				}
				switch(bits){

					case 8:
						// Map Values to flip x and y for MATLAB
						for(int64_t k = 0; k < stripSize; k++){
							if((k+(i*stripSize)) >= y) break;
							for(int64_t j = 0; j < x; j++){
								((uint8_t*)tiff)[((j*y)+(k+(i*stripSize)))+((dir-startSlice)*(x*y))] = ((uint8_t*)buffer)[j+(k*x)];
							}
						}
						break;
					case 16:
						// Map Values to flip x and y for MATLAB
						for(int64_t k = 0; k < stripSize; k++){
							if((k+(i*stripSize)) >= y) break;
							for(int64_t j = 0; j < x; j++){
								((uint16_t*)tiff)[((j*y)+(k+(i*stripSize)))+((dir-startSlice)*(x*y))] = ((uint16_t*)buffer)[j+(k*x)];
							}
						}
						break;
					case 32:
						// Map Values to flip x and y for MATLAB
						for(int64_t k = 0; k < stripSize; k++){
							if((k+(i*stripSize)) >= y) break;
							for(int64_t j = 0; j < x; j++){
								((float*)tiff)[((j*y)+(k+(i*stripSize)))+((dir-startSlice)*(x*y))] = ((float*)buffer)[j+(k*x)];
							}
						}
						break;
					case 64:
						// Map Values to flip x and y for MATLAB
						for(int64_t k = 0; k < stripSize; k++){
							if((k+(i*stripSize)) >= y) break;
							for(int64_t j = 0; j < x; j++){
								((double*)tiff)[((j*y)+(k+(i*stripSize)))+((dir-startSlice)*(x*y))] = ((double*)buffer)[j+(k*x)];
							}
						}
						break;
				}
			}
		}
		free(buffer);
		TIFFClose(tif);
	}
	if(err) printf("%s\n", errString);
}

void readTiffParallel2D(uint64_t x, uint64_t y, uint64_t z, const char* fileName, void* tiff, uint64_t bits, uint64_t startSlice, uint64_t stripSize, uint8_t flipXY){
	int32_t numWorkers = omp_get_max_threads();
	uint64_t stripsPerDir = (uint64_t)ceil((double)y/(double)stripSize);
	int32_t batchSize = (stripsPerDir-1)/numWorkers+1;
	uint64_t bytes = bits/8;

	int32_t w;
	uint8_t err = 0;
	char errString[10000];


	#pragma omp parallel for
	for(w = 0; w < numWorkers; w++){

		TIFF* tif = TIFFOpen(fileName, "r");
		if(!tif){
			#pragma omp critical
			{
				err = 1;
				sprintf(errString,"Thread %d: File \"%s\" cannot be opened\n",w,fileName);
			}
		}

		void* buffer = malloc(x*stripSize*bytes);


		uint8_t counter = 0;
		while(!TIFFSetDirectory(tif, 0) && counter<3){
			printf("Thread %d: File \"%s\" Directory \"%d\" failed to open. Try %d\n",w,fileName,0,counter+1);
			counter++;
			if(counter == 3){
				#pragma omp critical
				{
					err = 1;
					sprintf(errString,"Thread %d: File \"%s\" cannot be opened\n",w,fileName);
				}
			}
		}
		for (int64_t i = (w*batchSize); i < (w+1)*batchSize; i++)
		{
			if(i*stripSize >= y || err) break;
			//loading the data into a buffer
			int64_t cBytes = TIFFReadEncodedStrip(tif, i, buffer, stripSize*x*bytes);
			if(cBytes < 0){
				#pragma omp critical
				{
					err = 1;
					sprintf(errString,"Thread %d: Strip %ld cannot be read\n",w,i);
				}
				break;
			}
			if(!flipXY){
				//uint64_t cBytes = x*stripSize*bytes;
				//if((i+1)*stripSize >= y) cBytes = x*(y-(i*stripSize))*bytes;
				memcpy(tiff+((i*stripSize*x)*bytes),buffer,cBytes);
				continue;
			}
			switch(bits){
				case 8:
					// Map Values to flip x and y for MATLAB
					for(int64_t k = 0; k < stripSize; k++){
						if((k+(i*stripSize)) >= y) break;
						for(int64_t j = 0; j < x; j++){
							((uint8_t*)tiff)[((j*y)+(k+(i*stripSize)))] = ((uint8_t*)buffer)[j+(k*x)];
						}
					}
					break;
				case 16:
					// Map Values to flip x and y for MATLAB
					for(int64_t k = 0; k < stripSize; k++){
						if((k+(i*stripSize)) >= y) break;
						for(int64_t j = 0; j < x; j++){
							((uint16_t*)tiff)[((j*y)+(k+(i*stripSize)))] = ((uint16_t*)buffer)[j+(k*x)];
						}
					}
					break;
				case 32:
					// Map Values to flip x and y for MATLAB
					for(int64_t k = 0; k < stripSize; k++){
						if((k+(i*stripSize)) >= y) break;
						for(int64_t j = 0; j < x; j++){
							((float*)tiff)[((j*y)+(k+(i*stripSize)))] = ((float*)buffer)[j+(k*x)];
						}
					}
					break;
				case 64:
					// Map Values to flip x and y for MATLAB
					for(int64_t k = 0; k < stripSize; k++){
						if((k+(i*stripSize)) >= y) break;
						for(int64_t j = 0; j < x; j++){
							((double*)tiff)[((j*y)+(k+(i*stripSize)))] = ((double*)buffer)[j+(k*x)];
						}
					}
					break;
			}
		}
		free(buffer);
		TIFFClose(tif);
	}
	if(err) printf("%s\n", errString);
}

// Reading images saved by ImageJ
void readTiffParallelImageJ(uint64_t x, uint64_t y, uint64_t z, const char* fileName, void* tiff, uint64_t bits, uint64_t startSlice, uint64_t stripSize, uint8_t flipXY){
	#ifdef __linux__
	int fd = open(fileName,O_RDONLY);
	#endif
	#ifdef _WIN32
	int fd = open(fileName,O_RDONLY | O_BINARY);
	#endif
	TIFF* tif = TIFFOpen(fileName, "r");
	uint64_t offset = 0;
	uint64_t* offsets = NULL;
	TIFFGetField(tif, TIFFTAG_STRIPOFFSETS, &offsets);
	if(offsets) offset = offsets[0];

	TIFFClose(tif);
	lseek(fd, offset, SEEK_SET);
	uint64_t bytes = bits/8;
	//#pragma omp parallel for
	/*
	   for(uint64_t i = 0; i < z; i++){
	   uint64_t cOffset = x*y*bytes*i;
	//pread(fd,tiff+cOffset,x*y*bytes,offset+cOffset);
	read(fd,tiff+cOffset,x*y*bytes);
	}*/
	uint64_t chunk = 0;
	uint64_t tBytes = x*y*z*bytes;
	uint64_t bytesRead;
	uint64_t rBytes = tBytes;
	if(tBytes < INT_MAX) bytesRead = read(fd,tiff,tBytes);
	else{
		while(chunk < tBytes){
			rBytes = tBytes-chunk;
			if(rBytes > INT_MAX) bytesRead = read(fd,tiff+chunk,INT_MAX);
			else bytesRead = read(fd,tiff+chunk,rBytes);
			chunk += bytesRead;
		}
	}
	close(fd);
	// Swap endianess for types greater than 8 bits
	// TODO: May need to change later because we may not always need to swap
	if(bits > 8){
	#pragma omp parallel for
		for(uint64_t i = 0; i < x*y*z; i++){
			switch(bits){
				case 16:
					//((uint16_t*)tiff)[i] = ((((uint16_t*)tiff)[i] & 0xff) >> 8) | (((uint16_t*)tiff)[i] << 8);
					//((uint16_t*)tiff)[i] = bswap_16(((uint16_t*)tiff)[i]);
					((uint16_t*)tiff)[i] = ((((uint16_t*)tiff)[i] << 8) & 0xff00) | ((((uint16_t*)tiff)[i] >> 8) & 0x00ff);
					break;
				case 32:
					//((num & 0xff000000) >> 24) | ((num & 0x00ff0000) >> 8) | ((num & 0x0000ff00) << 8) | (num << 24)
					//((float*)tiff)[i] = bswap_32(((float*)tiff)[i]);
					((uint32_t*)tiff)[i] = ((((uint32_t*)tiff)[i] << 24) & 0xff000000 ) |
						((((uint32_t*)tiff)[i] <<  8) & 0x00ff0000 ) |
						((((uint32_t*)tiff)[i] >>  8) & 0x0000ff00 ) |
						((((uint32_t*)tiff)[i] >> 24) & 0x000000ff );
					break;
				case 64:
					//((double*)tiff)[i] = bswap_64(((double*)tiff)[i]);
					((uint64_t*)tiff)[i] = ( (((uint64_t*)tiff)[i] << 56) & 0xff00000000000000UL ) |
						( (((uint64_t*)tiff)[i] << 40) & 0x00ff000000000000UL ) |
						( (((uint64_t*)tiff)[i] << 24) & 0x0000ff0000000000UL ) |
						( (((uint64_t*)tiff)[i] <<  8) & 0x000000ff00000000UL ) |
						( (((uint64_t*)tiff)[i] >>  8) & 0x00000000ff000000UL ) |
						( (((uint64_t*)tiff)[i] >> 24) & 0x0000000000ff0000UL ) |
						( (((uint64_t*)tiff)[i] >> 40) & 0x000000000000ff00UL ) |
						( (((uint64_t*)tiff)[i] >> 56) & 0x00000000000000ffUL );
					break;
			}

		}
	}
}

uint8_t isImageJIm(const char* fileName){
	TIFF* tif = TIFFOpen(fileName, "r");
	if(!tif) return 0;
	char* tiffDesc = NULL;
	char* software = NULL;
	if(TIFFGetField(tif, TIFFTAG_IMAGEDESCRIPTION, &tiffDesc)){
		if(strstr(tiffDesc, "ImageJ")){
			if(TIFFGetField(tif, TIFFTAG_SOFTWARE, &software)){
				if(strstr(software,"Bio-Formats")) return 0;
			}
			return 1;
		}
	}
	return 0;
}

uint64_t imageJImGetZ(const char* fileName){
	TIFF* tif = TIFFOpen(fileName, "r");
	if(!tif) return 0;
	char* tiffDesc = NULL;
	if(TIFFGetField(tif, TIFFTAG_IMAGEDESCRIPTION, &tiffDesc)){
		if(strstr(tiffDesc, "ImageJ")){
			char* nZ = strstr(tiffDesc,"images=");
			if(nZ){
				nZ+=7;
				char* temp;
				return strtol(nZ,&temp,10);
			}
		}
	}
	return 0;
}

// tiff pointer guaranteed to be NULL or the correct size array for the tiff file
void* readTiffParallelWrapperHelper(const char* fileName, void* tiff, uint8_t flipXY)
{
	TIFFSetWarningHandler(DummyHandler);
	TIFF* tif = TIFFOpen(fileName, "r");
	if(!tif) return NULL;

	uint64_t x = 1,y = 1,z = 1,bits = 1, startSlice = 0;
	TIFFGetField(tif, TIFFTAG_IMAGEWIDTH, &x);
	TIFFGetField(tif, TIFFTAG_IMAGELENGTH, &y);

	uint16_t s = 0, m = 0, t = 1;
	while(TIFFSetDirectory(tif,t)){
		s = t;
		t *= 8;
		if(s > t){
			t = 65535;
			printf("Number of slices > 32768\n");
			break;
		}
	}
	while(s != t){
		m = (s+t+1)/2;
		if(TIFFSetDirectory(tif,m)){
			s = m;
		}
		else{
			if(m > 0) t = m-1;
			else t = m;
		}
	}
	z = s+1;

	TIFFGetField(tif, TIFFTAG_BITSPERSAMPLE, &bits);
	uint64_t stripSize = 1;
	TIFFGetField(tif, TIFFTAG_ROWSPERSTRIP, &stripSize);
	TIFFClose(tif);

	// Check if image is an imagej image with imagej metadata
	// Get the correct
	uint8_t imageJIm = 0;
	if(isImageJIm(fileName)){
		imageJIm = 1;
		uint64_t tempZ = imageJImGetZ(fileName);
		if(tempZ) z = tempZ;
	}


	if(imageJIm){
		if(bits == 8){
			if(!tiff) tiff = (uint8_t*)malloc(x*y*z*sizeof(uint8_t));
			readTiffParallelImageJ(x,y,z,fileName, (void*)tiff, bits, startSlice, stripSize,flipXY);
			return (void*)tiff;
		}
		else if(bits == 16){
			if(!tiff) tiff = (uint16_t*)malloc(x*y*z*sizeof(uint16_t));
			readTiffParallelImageJ(x,y,z,fileName, (void*)tiff, bits, startSlice, stripSize, flipXY);
			return (void*)tiff;
		}
		else if(bits == 32){
			if(!tiff) tiff = (float*)malloc(x*y*z*sizeof(float));
			readTiffParallelImageJ(x,y,z,fileName, (void*)tiff, bits, startSlice, stripSize, flipXY);
			return (void*)tiff;
		}
		else if(bits == 64){
			if(!tiff) tiff = (double*)malloc(x*y*z*sizeof(double));
			readTiffParallelImageJ(x,y,z,fileName, (void*)tiff, bits, startSlice, stripSize, flipXY);
			return (void*)tiff;
		}
		else{
			return NULL;
		}
	}
	else if(z <= 1){
		if(bits == 8){
			if(!tiff) tiff = (uint8_t*)malloc(x*y*z*sizeof(uint8_t));
			readTiffParallel2D(x,y,z,fileName, (void*)tiff, bits, startSlice, stripSize,flipXY);
			return (void*)tiff;
		}
		else if(bits == 16){
			if(!tiff) tiff = (uint16_t*)malloc(x*y*z*sizeof(uint16_t));
			readTiffParallel2D(x,y,z,fileName, (void*)tiff, bits, startSlice, stripSize, flipXY);
			return (void*)tiff;
		}
		else if(bits == 32){
			if(!tiff) tiff = (float*)malloc(x*y*z*sizeof(float));
			readTiffParallel2D(x,y,z,fileName, (void*)tiff, bits, startSlice, stripSize, flipXY);
			return (void*)tiff;
		}
		else if(bits == 64){
			if(!tiff) tiff = (double*)malloc(x*y*z*sizeof(double));
			readTiffParallel2D(x,y,z,fileName, (void*)tiff, bits, startSlice, stripSize, flipXY);
			return (void*)tiff;
		}
		else{
			return NULL;
		}
	}
	else{
		if(bits == 8){
			if(!tiff) tiff = (uint8_t*)malloc(x*y*z*sizeof(uint8_t));
			readTiffParallel(x,y,z,fileName, (void*)tiff, bits, startSlice, stripSize, flipXY);
			return (void*)tiff;
		}
		else if(bits == 16){
			if(!tiff) tiff = (uint16_t*)malloc(x*y*z*sizeof(uint16_t));
			readTiffParallel(x,y,z,fileName, (void*)tiff, bits, startSlice, stripSize, flipXY);
			return (void*)tiff;
		}
		else if(bits == 32){
			if(!tiff) tiff = (float*)malloc(x*y*z*sizeof(float));
			readTiffParallel(x,y,z,fileName, (void*)tiff, bits, startSlice, stripSize, flipXY);
			return (void*)tiff;
		}
		else if(bits == 64){
			if(!tiff) tiff = (double*)malloc(x*y*z*sizeof(double));
			readTiffParallel(x,y,z,fileName, (void*)tiff, bits, startSlice, stripSize,flipXY);
			return (void*)tiff;
		}
		else{
			return NULL;
		}
	}

	// Should never get here but return NULL if we do
	return NULL;
}

void* readTiffParallelWrapper(const char* fileName)
{
	return readTiffParallelWrapperHelper(fileName,NULL,1);
}

void* readTiffParallelWrapperNoXYFlip(const char* fileName)
{
	return readTiffParallelWrapperHelper(fileName,NULL,0);
}

// tTiff doesn't matter as tiff is set in the function
void readTiffParallelWrapperSet(const char* fileName, void* tiff){
	void* tTiff = readTiffParallelWrapperHelper(fileName,tiff,0);
}

uint64_t* getImageSize(const char* fileName){

	TIFFSetWarningHandler(DummyHandler);
	TIFF* tif = TIFFOpen(fileName, "r");
	if(!tif) printf("File \"%s\" cannot be opened",fileName);

	uint64_t x = 1,y = 1,z = 1;
	TIFFGetField(tif, TIFFTAG_IMAGEWIDTH, &x);
	TIFFGetField(tif, TIFFTAG_IMAGELENGTH, &y);
	uint16_t s = 0, m = 0, t = 1;
	while(TIFFSetDirectory(tif,t)){
		s = t;
		t *= 8;
		if(s > t){
			t = 65535;
			printf("Number of slices > 32768\n");
			break;
		}
	}
	while(s != t){
		m = (s+t+1)/2;
		if(TIFFSetDirectory(tif,m)){
			s = m;
		}
		else{
			if(m > 0) t = m-1;
			else t = m;
		}
	}
	z = s+1;

	TIFFClose(tif);
	uint64_t* dims = (uint64_t*)malloc(3*sizeof(uint64_t));
	dims[0] = y;
	dims[1] = x;
	dims[2] = z;
	return dims;
}

// Returns number of bits the tiff file is.
uint64_t getDataType(const char* fileName){
	TIFFSetWarningHandler(DummyHandler);
	TIFF* tif = TIFFOpen(fileName, "r");
	if(!tif) printf("File \"%s\" cannot be opened",fileName);

	uint64_t bits = 1;
	TIFFGetField(tif, TIFFTAG_BITSPERSAMPLE, &bits);
	TIFFClose(tif);

	return bits;


}
