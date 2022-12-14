#ifndef PARALLELWRITEZARR_H
#define PARALLELWRITEZARR_H
#include <stdint.h>

void parallelWriteZarr(void* zarr, char* folderName,uint64_t startX, uint64_t startY, uint64_t startZ, uint64_t endX, uint64_t endY,uint64_t endZ,uint64_t chunkXSize,uint64_t chunkYSize,uint64_t chunkZSize,uint64_t shapeX,uint64_t shapeY,uint64_t shapeZ,uint64_t origShapeX,uint64_t origShapeY,uint64_t origShapeZ, uint64_t bits, char order, uint8_t useUuid, uint8_t crop, char* cname);

void writeZarrParallelHelper(char* folderName, void* zarr, uint64_t startX, uint64_t startY, uint64_t startZ, uint64_t endX, uint64_t endY, uint64_t endZ, uint64_t chunkXSize, uint64_t chunkYSize, uint64_t chunkZSize, uint8_t crop, char* cname, uint8_t useUuid, uint64_t bytes, uint8_t imageJIm);

#endif //PARALLELWRITEZARR_H
