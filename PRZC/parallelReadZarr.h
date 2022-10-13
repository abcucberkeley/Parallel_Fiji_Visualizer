#include <stdint.h>

uint64_t getDataType(const char* fileName);

uint64_t* getImageSize(const char* fileName);

void setValuesFromJSON(const char* fileName,uint64_t *chunkXSize,uint64_t *chunkYSize,uint64_t *chunkZSize,char* dtype,char* order,uint64_t *shapeX,uint64_t *shapeY,uint64_t *shapeZ,char** cname);

void* readZarrParallelHelper(const char* folderName, uint64_t startX, uint64_t startY, uint64_t startZ, uint64_t endX, uint64_t endY, uint64_t endZ, uint8_t imageJIm);

void* readZarrParallelWrapper(const char* folderName);

void* readZarrParallelWrapperImageJ(const char* fileName);
