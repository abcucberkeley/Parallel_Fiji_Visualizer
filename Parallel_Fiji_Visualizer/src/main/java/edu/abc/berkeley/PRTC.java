package edu.abc.berkeley;

import java.nio.ByteBuffer;

import org.apache.commons.lang.SystemUtils;


public class PRTC {
    // Load system libraries
    static { 	
    	if(SystemUtils.IS_OS_WINDOWS) {
        	helperFunctions.loadLib("libzlib");
        	helperFunctions.loadLib("libzstd");
        	helperFunctions.loadLib("libdeflate");
        	helperFunctions.loadLib("libtiff");
    	}
    	helperFunctions.loadLib("libpReadTiffC");
    }
    
    // Declare native method
    public native void parallelReadTiffC(String fileName, ByteBuffer tiff);
    
    public native byte[][] parallelReadTiffUINT8(String fileName);
    
    public native short[][] parallelReadTiffUINT16(String fileName);
    
    public native float[][] parallelReadTiffFLOAT(String fileName);
    
    public native float[][] parallelReadTiffDOUBLE(String fileName);
    
    public native long getDataType(String fileName);
    
    public native long[] getImageDims(String fileName);

}
