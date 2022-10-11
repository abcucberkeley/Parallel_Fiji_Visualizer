#include <stdint.h>
#include <string.h>
//mex -v COPTIMFLAGS="-O3 -DNDEBUG" CFLAGS='$CFLAGS -O3 -fopenmp' LDFLAGS='$LDFLAGS -O3 -fopenmp' '-I/global/home/groups/software/sl-7.x86_64/modules/libtiff/4.1.0/libtiff/' '-L/global/home/groups/software/sl-7.x86_64/modules/libtiff/4.1.0/libtiff/' -ltiff /clusterfs/fiona/matthewmueller/parallelTiffTesting/main.c
//mex COMPFLAGS='$COMPFLAGS /openmp' '-IC:\Program Files (x86)\tiff\include\' '-LC:\Program Files (x86)\tiff\lib\' -ltiffd.lib C:\Users\Matt\Documents\parallelTiff\main.cpp

//zlib
//mex -v COPTIMFLAGS="-O3 -DNDEBUG" CFLAGS='$CFLAGS -O3 -fopenmp' LDFLAGS='$LDFLAGS -O3 -fopenmp' '-I/global/home/groups/software/sl-7.x86_64/modules/libtiff/4.1.0/libtiff/' '-I/global/home/groups/consultsw/sl-7.x86_64/modules/zlib/1.2.11/include/' '-L/global/home/groups/consultsw/sl-7.x86_64/modules/zlib/1.2.11/lib' -lz '-L/global/home/groups/software/sl-7.x86_64/modules/libtiff/4.1.0/libtiff/' -ltiff parallelWriteTiff.c

//lzw
//mex -v CXXOPTIMFLAGS="-O3 -DNDEBUG" CXXFLAGS='$CXXFLAGS -O3 -fopenmp' LDFLAGS='$LDFLAGS -O3 -fopenmp' '-I/global/home/groups/software/sl-7.x86_64/modules/libtiff/4.1.0/libtiff/' '-L/global/home/groups/software/sl-7.x86_64/modules/libtiff/4.1.0/libtiff/' -ltiff parallelWriteTiff.c lzw.c

//mex -v COPTIMFLAGS="-O3 -DNDEBUG" CFLAGS='$CFLAGS -O3 -fopenmp' LDFLAGS='$LDFLAGS -O3 -fopenmp' '-I/global/home/groups/software/sl-7.x86_64/modules/libtiff/4.1.0/libtiff/' '-L/global/home/groups/software/sl-7.x86_64/modules/libtiff/4.1.0/libtiff/' -ltiff parallelWriteTiff.c lzwEncode.c

void writeTiffParallel(uint64_t x, uint64_t y, uint64_t z, const char* fileName, void* tiff, const void* tiffOld, uint64_t bits, uint64_t startSlice, uint64_t stripSize, uint64_t stripsPerDir, uint64_t* cSizes, const char* mode, uint8_t flipXY);

void writeTiffParallelWrapper(const char* fileName, void* tiffOld, uint64_t bits, const char* mode, uint64_t x, uint64_t y, uint64_t z, uint64_t startSlice, uint8_t flipXY);
