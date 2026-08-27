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

	/** Signed 8-bit images, shifted to 0-255; a calibration maps values back. */
	public native byte[][] parallelReadTiffINT8(String fileName);

	/** Signed 16-bit images, shifted to 0-65535 (ImageJ's signed-16 convention). */
	public native short[][] parallelReadTiffINT16(String fileName);

	/** 32-bit integer images are converted to float: ImageStack has no int display. */
	public native float[][] parallelReadTiffINT32(String fileName);

	/** 32-bit unsigned integer images are converted to float. */
	public native float[][] parallelReadTiffUINT32(String fileName);

	/** 64-bit integer images are converted to float (precision loss above 2^24). */
	public native float[][] parallelReadTiffINT64(String fileName);

	/** 64-bit unsigned integer images are converted to float (precision loss above 2^24). */
	public native float[][] parallelReadTiffUINT64(String fileName);

	/** Chunky 8-bit RGB/RGBA, packed into ImageJ int-based RGB pixels. */
	public native int[][] parallelReadTiffRGB8(String fileName);

	/** Bits per pixel of the image on disk. */
	public native long getTiffDataType(String fileName);

	/** Sample format of the image on disk: 1 = unsigned int, 2 = signed int, 3 = float. */
	public native long getTiffSampleFormat(String fileName);

	/** Samples per pixel of the image on disk: 1 = grayscale, 3 = RGB, 4 = RGBA. */
	public native long getTiffSamplesPerPixel(String fileName);

	/** {rows, columns, slices} of the image on disk. */
	public native long[] getTiffDims(String fileName);

	// --- Zarr ---

	public native byte[][] parallelReadZarrUINT8(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);

	public native short[][] parallelReadZarrUINT16(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);

	public native float[][] parallelReadZarrFLOAT(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);

	/** 64-bit images are converted to float: ImageStack has no double support. */
	public native float[][] parallelReadZarrDOUBLE(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);

	/** Signed 8-bit zarrs, shifted to 0-255; a calibration maps values back. */
	public native byte[][] parallelReadZarrINT8(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);

	/** Signed 16-bit zarrs, shifted to 0-65535 (ImageJ's signed-16 convention). */
	public native short[][] parallelReadZarrINT16(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);

	/** 32-bit integer zarrs are converted to float: ImageStack has no int display. */
	public native float[][] parallelReadZarrINT32(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);

	/** 32-bit unsigned integer zarrs are converted to float. */
	public native float[][] parallelReadZarrUINT32(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);

	/** 64-bit integer zarrs are converted to float (precision loss above 2^24). */
	public native float[][] parallelReadZarrINT64(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);

	/** 64-bit unsigned integer zarrs are converted to float (precision loss above 2^24). */
	public native float[][] parallelReadZarrUINT64(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ);

	/** Bits per pixel of the zarr on disk. */
	public native long getZarrDataType(String fileName);

	/** The numpy-style dtype of the zarr on disk, e.g. "&lt;u2" or "&lt;i4". */
	public native String getZarrDtype(String fileName);

	/** Shape of the zarr on disk. */
	public native long[] getZarrDims(String fileName);
}
