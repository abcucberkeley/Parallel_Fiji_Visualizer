package edu.abc.berkeley;

import org.apache.commons.lang.SystemUtils;

public class PRZC {
    // Load system libraries
    static {
    	if(SystemUtils.IS_OS_WINDOWS) {
    		helperFunctions.loadLib("libzlib");
    		helperFunctions.loadLib("libblosc2");
    	}
    	helperFunctions.loadLib("cjson");
		if(!SystemUtils.IS_OS_WINDOWS) {
			helperFunctions.loadLib("blosc2");
		}
		helperFunctions.loadLib("pReadZarrC");
    }
    
    // Declare native method
    
    public native byte[][] parallelReadZarrUINT8(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);
    
    public native short[][] parallelReadZarrUINT16(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);
    
    public native float[][] parallelReadZarrFLOAT(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);
    
    public native float[][] parallelReadZarrDOUBLE(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);
    
    public native long getDataType(String fileName);
    
    public native long[] getImageDims(String fileName);
    //public native void setValuesFromJSON(String fileName,long chunkXSize,long chunkYSize,long chunkZSize,String dtype,String order,long shapeX,long shapeY,long shapeZ,String cname);
}
