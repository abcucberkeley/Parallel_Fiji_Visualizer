package edu.abc.berkeley;

import org.apache.commons.lang.SystemUtils;

public class PWTC {
    // Load system libraries
    static {
    	if(SystemUtils.IS_OS_WINDOWS) {
    		helperFunctions.loadLib("libtiff");
    	}
        helperFunctions.loadLib("libpWriteTiffC");
    }
    
    // Declare native method
    public native void parallelWriteTiff(String fileName, Object[] im, int x, int y, int z, int bits);
    
}
