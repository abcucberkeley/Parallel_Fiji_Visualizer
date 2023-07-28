package edu.abc.berkeley;

import org.apache.commons.lang.SystemUtils;

public class PWZC {
	static {
    	if(SystemUtils.IS_OS_WINDOWS) {
    		helperFunctions.loadLib("zlib");
    		helperFunctions.loadLib("libblosc2");
    		helperFunctions.loadLib("cjson");
    	}
		//if(!SystemUtils.IS_OS_WINDOWS) {
		//	helperFunctions.loadLib("blosc2");
		//}
		//helperFunctions.loadLib("blosc");
		helperFunctions.loadLib("libpWriteZarrC");
    }
	
	public native void parallelWriteZarr(String fileName, Object[] im, long startX, long startY, long startZ, long endX, long endY, long chunkXSize, long chunkYSize, long chunkZSize, long endZ, int crop, String cname, int useUuid,  long bits);
}
