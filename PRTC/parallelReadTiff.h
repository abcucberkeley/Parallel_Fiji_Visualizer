#ifndef PARALLELREADTIFF_H
#define PARALLELREADTIFF_H
#include "tiffio.h"
#include <stdio.h>
#include <stdint.h>
#include "omp.h"

void readTiffParallel(uint64_t x, uint64_t y, uint64_t z, const char* fileName, void* tiff, uint64_t bits, uint64_t startSlice, uint64_t stripSize, uint8_t flipXY);

void readTiffParallelImageJ(uint64_t x, uint64_t y, uint64_t z, const char* fileName, void* tiff, uint64_t bits, uint64_t startSlice, uint64_t stripSize, uint8_t flipXY);

uint8_t isImageJIm(const char* fileName);

uint64_t imageJImGetZ(const char* fileName);

void* readTiffParallelWrapper(const char* fileName);

void* readTiffParallelWrapperNoXYFlip(const char* fileName);

void readTiffParallelWrapperSet(const char* fileName, void* tiff);

uint64_t* getImageSize(const char* fileName);

uint64_t getDataType(const char* fileName);

#endif // PARALLELREADTIFF_H
