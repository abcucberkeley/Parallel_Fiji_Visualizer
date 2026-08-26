package edu.abc.berkeley;

/**
 * JNI bindings for the parallel TIFF and Zarr writers (libParallelWriteC,
 * backed by the cpp-tiff and cpp-zarr libraries).
 */
public class ParallelWriteNative {

	static {
		NativeLibraries.load();
	}

	/** Writes an LZW-compressed TIFF; {@code im} holds one primitive array per slice. */
	public native void parallelWriteTiff(String fileName, Object[] im, int x, int y, int z, int bits);

	/**
	 * Writes a zarr of shape {@code end - start}; {@code im} holds one
	 * primitive array per slice and {@code cname} is the blosc codec name
	 * (e.g. "lz4").
	 */
	public native void parallelWriteZarr(String fileName, Object[] im, long startX, long startY, long startZ,
		long endX, long endY, long endZ, long chunkXSize, long chunkYSize, long chunkZSize,
		int crop, String cname, int useUuid, long bits);
}
