package edu.abc.berkeley;

/**
 * JNI bindings for the parallel TIFF and Zarr readers (libParallelReadC,
 * backed by the cpp-tiff and cpp-zarr libraries).
 */
public class ParallelReadNative {

	static {
		NativeLibraries.load();
	}

	// --- TIFF ---

	public native byte[][] parallelReadTiffUINT8(String fileName);

	public native short[][] parallelReadTiffUINT16(String fileName);

	public native float[][] parallelReadTiffFLOAT(String fileName);

	/** 64-bit images are converted to float: ImageStack has no double support. */
	public native float[][] parallelReadTiffDOUBLE(String fileName);

	/** Bits per pixel of the image on disk. */
	public native long getTiffDataType(String fileName);

	/** {rows, columns, slices} of the image on disk. */
	public native long[] getTiffDims(String fileName);

	// --- Zarr ---

	public native byte[][] parallelReadZarrUINT8(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);

	public native short[][] parallelReadZarrUINT16(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);

	public native float[][] parallelReadZarrFLOAT(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);

	/** 64-bit images are converted to float: ImageStack has no double support. */
	public native float[][] parallelReadZarrDOUBLE(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);

	/** Bits per pixel of the zarr on disk. */
	public native long getZarrDataType(String fileName);

	/** Shape of the zarr on disk. */
	public native long[] getZarrDims(String fileName);
}
