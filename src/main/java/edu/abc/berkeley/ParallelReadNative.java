package edu.abc.berkeley;

import java.util.stream.IntStream;

/**
 * JNI bindings for the parallel TIFF and Zarr readers (libParallelReadC,
 * backed by the cpp-tiff and cpp-zarr libraries).
 */
public class ParallelReadNative {

	static {
		NativeLibraries.load();
	}

	// --- Plane allocation ---
	// The JVM zero-fills every new array on the allocating thread, and for a
	// multi-gigabyte image that alone takes longer than the native read (9 GB
	// of planes: ~3 s on one core, ~0.6 s from all cores on Fiji's Java 8).
	// Planes are therefore allocated from all cores and handed to the natives
	// to fill in place; the natives verify they match the image first.

	/** Allocate {@code z} planes of {@code n} elements each, from all cores. */
	public static byte[][] newBytePlanes(long z, long n) {
		final byte[][] p = new byte[(int) z][];
		IntStream.range(0, (int) z).parallel().forEach(i -> p[i] = new byte[(int) n]);
		return p;
	}

	public static short[][] newShortPlanes(long z, long n) {
		final short[][] p = new short[(int) z][];
		IntStream.range(0, (int) z).parallel().forEach(i -> p[i] = new short[(int) n]);
		return p;
	}

	public static float[][] newFloatPlanes(long z, long n) {
		final float[][] p = new float[(int) z][];
		IntStream.range(0, (int) z).parallel().forEach(i -> p[i] = new float[(int) n]);
		return p;
	}

	public static int[][] newIntPlanes(long z, long n) {
		final int[][] p = new int[(int) z][];
		IntStream.range(0, (int) z).parallel().forEach(i -> p[i] = new int[(int) n]);
		return p;
	}

	// --- TIFF ---
	// Each reader fills {@code dest}, one plane of rows*columns elements per
	// slice (see getTiffDims), and returns it. The overloads without dest
	// allocate the planes themselves.

	public native byte[][] parallelReadTiffUINT8(String fileName, byte[][] dest);

	public byte[][] parallelReadTiffUINT8(String fileName) {
		long[] d = getTiffDims(fileName);
		return parallelReadTiffUINT8(fileName, newBytePlanes(d[2], d[0] * d[1]));
	}

	public native short[][] parallelReadTiffUINT16(String fileName, short[][] dest);

	public short[][] parallelReadTiffUINT16(String fileName) {
		long[] d = getTiffDims(fileName);
		return parallelReadTiffUINT16(fileName, newShortPlanes(d[2], d[0] * d[1]));
	}

	public native float[][] parallelReadTiffFLOAT(String fileName, float[][] dest);

	public float[][] parallelReadTiffFLOAT(String fileName) {
		long[] d = getTiffDims(fileName);
		return parallelReadTiffFLOAT(fileName, newFloatPlanes(d[2], d[0] * d[1]));
	}

	/** 64-bit images are converted to float: ImageStack has no double support. */
	public native float[][] parallelReadTiffDOUBLE(String fileName, float[][] dest);

	public float[][] parallelReadTiffDOUBLE(String fileName) {
		long[] d = getTiffDims(fileName);
		return parallelReadTiffDOUBLE(fileName, newFloatPlanes(d[2], d[0] * d[1]));
	}

	/** Signed 8-bit images, shifted to 0-255; a calibration maps values back. */
	public native byte[][] parallelReadTiffINT8(String fileName, byte[][] dest);

	public byte[][] parallelReadTiffINT8(String fileName) {
		long[] d = getTiffDims(fileName);
		return parallelReadTiffINT8(fileName, newBytePlanes(d[2], d[0] * d[1]));
	}

	/** Signed 16-bit images, shifted to 0-65535 (ImageJ's signed-16 convention). */
	public native short[][] parallelReadTiffINT16(String fileName, short[][] dest);

	public short[][] parallelReadTiffINT16(String fileName) {
		long[] d = getTiffDims(fileName);
		return parallelReadTiffINT16(fileName, newShortPlanes(d[2], d[0] * d[1]));
	}

	/** 32-bit integer images are converted to float: ImageStack has no int display. */
	public native float[][] parallelReadTiffINT32(String fileName, float[][] dest);

	public float[][] parallelReadTiffINT32(String fileName) {
		long[] d = getTiffDims(fileName);
		return parallelReadTiffINT32(fileName, newFloatPlanes(d[2], d[0] * d[1]));
	}

	/** 32-bit unsigned integer images are converted to float. */
	public native float[][] parallelReadTiffUINT32(String fileName, float[][] dest);

	public float[][] parallelReadTiffUINT32(String fileName) {
		long[] d = getTiffDims(fileName);
		return parallelReadTiffUINT32(fileName, newFloatPlanes(d[2], d[0] * d[1]));
	}

	/** 64-bit integer images are converted to float (precision loss above 2^24). */
	public native float[][] parallelReadTiffINT64(String fileName, float[][] dest);

	public float[][] parallelReadTiffINT64(String fileName) {
		long[] d = getTiffDims(fileName);
		return parallelReadTiffINT64(fileName, newFloatPlanes(d[2], d[0] * d[1]));
	}

	/** 64-bit unsigned integer images are converted to float (precision loss above 2^24). */
	public native float[][] parallelReadTiffUINT64(String fileName, float[][] dest);

	public float[][] parallelReadTiffUINT64(String fileName) {
		long[] d = getTiffDims(fileName);
		return parallelReadTiffUINT64(fileName, newFloatPlanes(d[2], d[0] * d[1]));
	}

	/** Chunky 8-bit RGB/RGBA, packed into ImageJ int-based RGB pixels. */
	public native int[][] parallelReadTiffRGB8(String fileName, int[][] dest);

	public int[][] parallelReadTiffRGB8(String fileName) {
		long[] d = getTiffDims(fileName);
		return parallelReadTiffRGB8(fileName, newIntPlanes(d[2], d[0] * d[1]));
	}

	/** Bits per pixel of the image on disk. */
	public native long getTiffDataType(String fileName);

	/** Sample format of the image on disk: 1 = unsigned int, 2 = signed int, 3 = float. */
	public native long getTiffSampleFormat(String fileName);

	/** Samples per pixel of the image on disk: 1 = grayscale, 3 = RGB, 4 = RGBA. */
	public native long getTiffSamplesPerPixel(String fileName);

	/** {rows, columns, slices} of the image on disk. */
	public native long[] getTiffDims(String fileName);

	// --- Zarr ---
	// Windows are [start, end) in each dimension; dest holds (endZ-startZ)
	// planes of (endX-startX)*(endY-startY) elements.

	public native byte[][] parallelReadZarrUINT8(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, byte[][] dest);

	public byte[][] parallelReadZarrUINT8(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrUINT8(fileName, startX, startY, startZ, endX, endY, endZ,
			newBytePlanes(endZ - startZ, (endX - startX) * (endY - startY)));
	}

	public native short[][] parallelReadZarrUINT16(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, short[][] dest);

	public short[][] parallelReadZarrUINT16(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrUINT16(fileName, startX, startY, startZ, endX, endY, endZ,
			newShortPlanes(endZ - startZ, (endX - startX) * (endY - startY)));
	}

	public native float[][] parallelReadZarrFLOAT(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, float[][] dest);

	public float[][] parallelReadZarrFLOAT(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrFLOAT(fileName, startX, startY, startZ, endX, endY, endZ,
			newFloatPlanes(endZ - startZ, (endX - startX) * (endY - startY)));
	}

	/** 64-bit images are converted to float: ImageStack has no double support. */
	public native float[][] parallelReadZarrDOUBLE(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, float[][] dest);

	public float[][] parallelReadZarrDOUBLE(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrDOUBLE(fileName, startX, startY, startZ, endX, endY, endZ,
			newFloatPlanes(endZ - startZ, (endX - startX) * (endY - startY)));
	}

	/** Signed 8-bit zarrs, shifted to 0-255; a calibration maps values back. */
	public native byte[][] parallelReadZarrINT8(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, byte[][] dest);

	public byte[][] parallelReadZarrINT8(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrINT8(fileName, startX, startY, startZ, endX, endY, endZ,
			newBytePlanes(endZ - startZ, (endX - startX) * (endY - startY)));
	}

	/** Signed 16-bit zarrs, shifted to 0-65535 (ImageJ's signed-16 convention). */
	public native short[][] parallelReadZarrINT16(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, short[][] dest);

	public short[][] parallelReadZarrINT16(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrINT16(fileName, startX, startY, startZ, endX, endY, endZ,
			newShortPlanes(endZ - startZ, (endX - startX) * (endY - startY)));
	}

	/** 32-bit integer zarrs are converted to float: ImageStack has no int display. */
	public native float[][] parallelReadZarrINT32(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, float[][] dest);

	public float[][] parallelReadZarrINT32(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrINT32(fileName, startX, startY, startZ, endX, endY, endZ,
			newFloatPlanes(endZ - startZ, (endX - startX) * (endY - startY)));
	}

	/** 32-bit unsigned integer zarrs are converted to float. */
	public native float[][] parallelReadZarrUINT32(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, float[][] dest);

	public float[][] parallelReadZarrUINT32(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrUINT32(fileName, startX, startY, startZ, endX, endY, endZ,
			newFloatPlanes(endZ - startZ, (endX - startX) * (endY - startY)));
	}

	/** 64-bit integer zarrs are converted to float (precision loss above 2^24). */
	public native float[][] parallelReadZarrINT64(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, float[][] dest);

	public float[][] parallelReadZarrINT64(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrINT64(fileName, startX, startY, startZ, endX, endY, endZ,
			newFloatPlanes(endZ - startZ, (endX - startX) * (endY - startY)));
	}

	/** 64-bit unsigned integer zarrs are converted to float (precision loss above 2^24). */
	public native float[][] parallelReadZarrUINT64(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, float[][] dest);

	public float[][] parallelReadZarrUINT64(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrUINT64(fileName, startX, startY, startZ, endX, endY, endZ,
			newFloatPlanes(endZ - startZ, (endX - startX) * (endY - startY)));
	}

	/** Bits per pixel of the zarr on disk. */
	public native long getZarrDataType(String fileName);

	/** The numpy-style dtype of the zarr on disk, e.g. "&lt;u2" or "&lt;i4". */
	public native String getZarrDtype(String fileName);

	/** Shape of the zarr on disk. */
	public native long[] getZarrDims(String fileName);
}
