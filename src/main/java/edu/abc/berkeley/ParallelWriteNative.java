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
	 * Writes a new 3D zarr (rows, columns, planes) of shape {@code end - start};
	 * {@code im} holds one primitive array per slice and {@code cname} is the
	 * blosc codec name (e.g. "lz4"). crop is ignored.
	 */
	public native void parallelWriteZarr(String fileName, Object[] im, long startX, long startY, long startZ,
		long endX, long endY, long endZ, long chunkXSize, long chunkYSize, long chunkZSize,
		int crop, String cname, int useUuid, long bits);

	/**
	 * Writes a new Zarr v2 array of any shape (rows first, then columns, as
	 * MATLAB orders axes) and chunks, with nested chunk folders. {@code im}
	 * holds ImageJ planes of shape[1] x shape[0] pixels; {@code strides} gives,
	 * in pixels, how far apart neighbors along each axis are in those planes
	 * laid end to end: {width, 1, ...} for rows and columns. bits is 8, 16 or 32.
	 */
	public native void parallelWriteZarrArray(String fileName, Object[] im, long[] shape, long[] chunks,
		long[] strides, String cname, long bits);
}
