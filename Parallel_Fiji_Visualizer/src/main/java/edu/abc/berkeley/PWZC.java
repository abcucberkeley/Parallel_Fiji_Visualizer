package edu.abc.berkeley;

import org.apache.commons.lang.SystemUtils;

public class PWZC {
	static {
    	if(SystemUtils.IS_OS_WINDOWS) {
    		System.loadLibrary("zlib");
    		System.loadLibrary("libblosc2");
    	}
		System.loadLibrary("cjson");
		if(!SystemUtils.IS_OS_WINDOWS) {
			System.loadLibrary("blosc2");
		}
		System.loadLibrary("blosc");
        System.loadLibrary("pWriteZarrC");
    }
	
	public native void parallelWriteZarr(String fileName, Object[] im, long startX, long startY, long startZ, long endX, long endY, long chunkXSize, long chunkYSize, long chunkZSize, long endZ, int crop, String cname, int useUuid,  long bits);
}
