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

	// --- Threads ---

	/**
	 * The most threads the native reads of the calling thread use from now on
	 * (0 or less: every core). Readers of files opened at once share the cores.
	 */
	public static native void limitThreads(int threads);

	/** The most threads the native reads of the calling thread use. */
	public static native int threadLimit();

	// --- TIFF ---
	// getTiffInfo describes a file from one open: cpp-tiff's TiffInfo, as a
	// long[] indexed by the TIFF_ constants. The reads take it back, so reading
	// some slices of a deep stack does not walk its directories again, and read
	// the file's slices [zStart, zEnd) into dest: one plane of width*height
	// elements per slice, or for images with several samples per pixel one per
	// sample, a slice's samples one after another (except RGB8, which packs
	// 8-bit color into ImageJ RGB pixels). The overloads without info read the
	// whole file.

	public static final int TIFF_WIDTH = 0, TIFF_HEIGHT = 1, TIFF_SLICES = 2, TIFF_DIRECTORIES = 3,
		TIFF_BITS = 4, TIFF_SAMPLES = 5, TIFF_SAMPLE_FORMAT = 6, TIFF_STRIP_SIZE = 7, TIFF_COMPRESSION = 8,
		TIFF_PLANAR = 9, TIFF_TILED = 10, TIFF_IMAGEJ = 11, TIFF_IMAGEJ_IMAGES = 12;

	/**
	 * The file's width, height, slices (its directories, or the images of an
	 * ImageJ stack stored as one block), bits per sample, samples per pixel,
	 * sample format (1 = unsigned int, 2 = signed int, 3 = float) and layout.
	 */
	public native long[] getTiffInfo(String fileName);

	/** Bits per sample of the image on disk. */
	public long getTiffDataType(String fileName) {
		return getTiffInfo(fileName)[TIFF_BITS];
	}

	/** Sample format of the image on disk: 1 = unsigned int, 2 = signed int, 3 = float. */
	public long getTiffSampleFormat(String fileName) {
		return getTiffInfo(fileName)[TIFF_SAMPLE_FORMAT];
	}

	/** Samples per pixel of the image on disk: 1 = grayscale, 3 = RGB, 4 = RGBA. */
	public long getTiffSamplesPerPixel(String fileName) {
		return getTiffInfo(fileName)[TIFF_SAMPLES];
	}

	/** {rows, columns, slices} of the image on disk. */
	public long[] getTiffDims(String fileName) {
		long[] i = getTiffInfo(fileName);
		return new long[] { i[TIFF_HEIGHT], i[TIFF_WIDTH], i[TIFF_SLICES] };
	}

	public native byte[][] parallelReadTiffUINT8(String fileName, long[] info, long zStart, long zEnd, byte[][] dest);

	public byte[][] parallelReadTiffUINT8(String fileName, byte[][] dest) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffUINT8(fileName, i, 0, i[TIFF_SLICES], dest);
	}

	public byte[][] parallelReadTiffUINT8(String fileName) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffUINT8(fileName, i, 0, i[TIFF_SLICES], newBytePlanes(i[TIFF_SLICES] * i[TIFF_SAMPLES], i[TIFF_WIDTH] * i[TIFF_HEIGHT]));
	}

	public native short[][] parallelReadTiffUINT16(String fileName, long[] info, long zStart, long zEnd, short[][] dest);

	public short[][] parallelReadTiffUINT16(String fileName, short[][] dest) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffUINT16(fileName, i, 0, i[TIFF_SLICES], dest);
	}

	public short[][] parallelReadTiffUINT16(String fileName) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffUINT16(fileName, i, 0, i[TIFF_SLICES], newShortPlanes(i[TIFF_SLICES] * i[TIFF_SAMPLES], i[TIFF_WIDTH] * i[TIFF_HEIGHT]));
	}

	public native float[][] parallelReadTiffFLOAT(String fileName, long[] info, long zStart, long zEnd, float[][] dest);

	public float[][] parallelReadTiffFLOAT(String fileName, float[][] dest) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffFLOAT(fileName, i, 0, i[TIFF_SLICES], dest);
	}

	public float[][] parallelReadTiffFLOAT(String fileName) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffFLOAT(fileName, i, 0, i[TIFF_SLICES], newFloatPlanes(i[TIFF_SLICES] * i[TIFF_SAMPLES], i[TIFF_WIDTH] * i[TIFF_HEIGHT]));
	}

	/** 64-bit images are converted to float: ImageStack has no double support. */
	public native float[][] parallelReadTiffDOUBLE(String fileName, long[] info, long zStart, long zEnd, float[][] dest);

	public float[][] parallelReadTiffDOUBLE(String fileName, float[][] dest) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffDOUBLE(fileName, i, 0, i[TIFF_SLICES], dest);
	}

	public float[][] parallelReadTiffDOUBLE(String fileName) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffDOUBLE(fileName, i, 0, i[TIFF_SLICES], newFloatPlanes(i[TIFF_SLICES] * i[TIFF_SAMPLES], i[TIFF_WIDTH] * i[TIFF_HEIGHT]));
	}

	/** Signed 8-bit images, shifted to 0-255; a calibration maps values back. */
	public native byte[][] parallelReadTiffINT8(String fileName, long[] info, long zStart, long zEnd, byte[][] dest);

	public byte[][] parallelReadTiffINT8(String fileName, byte[][] dest) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffINT8(fileName, i, 0, i[TIFF_SLICES], dest);
	}

	public byte[][] parallelReadTiffINT8(String fileName) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffINT8(fileName, i, 0, i[TIFF_SLICES], newBytePlanes(i[TIFF_SLICES] * i[TIFF_SAMPLES], i[TIFF_WIDTH] * i[TIFF_HEIGHT]));
	}

	/** Signed 16-bit images, shifted to 0-65535 (ImageJ's signed-16 convention). */
	public native short[][] parallelReadTiffINT16(String fileName, long[] info, long zStart, long zEnd, short[][] dest);

	public short[][] parallelReadTiffINT16(String fileName, short[][] dest) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffINT16(fileName, i, 0, i[TIFF_SLICES], dest);
	}

	public short[][] parallelReadTiffINT16(String fileName) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffINT16(fileName, i, 0, i[TIFF_SLICES], newShortPlanes(i[TIFF_SLICES] * i[TIFF_SAMPLES], i[TIFF_WIDTH] * i[TIFF_HEIGHT]));
	}

	/** 32-bit integer images are converted to float: ImageStack has no int display. */
	public native float[][] parallelReadTiffINT32(String fileName, long[] info, long zStart, long zEnd, float[][] dest);

	public float[][] parallelReadTiffINT32(String fileName, float[][] dest) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffINT32(fileName, i, 0, i[TIFF_SLICES], dest);
	}

	public float[][] parallelReadTiffINT32(String fileName) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffINT32(fileName, i, 0, i[TIFF_SLICES], newFloatPlanes(i[TIFF_SLICES] * i[TIFF_SAMPLES], i[TIFF_WIDTH] * i[TIFF_HEIGHT]));
	}

	/** 32-bit unsigned integer images are converted to float. */
	public native float[][] parallelReadTiffUINT32(String fileName, long[] info, long zStart, long zEnd, float[][] dest);

	public float[][] parallelReadTiffUINT32(String fileName, float[][] dest) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffUINT32(fileName, i, 0, i[TIFF_SLICES], dest);
	}

	public float[][] parallelReadTiffUINT32(String fileName) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffUINT32(fileName, i, 0, i[TIFF_SLICES], newFloatPlanes(i[TIFF_SLICES] * i[TIFF_SAMPLES], i[TIFF_WIDTH] * i[TIFF_HEIGHT]));
	}

	/** 64-bit integer images are converted to float (precision loss above 2^24). */
	public native float[][] parallelReadTiffINT64(String fileName, long[] info, long zStart, long zEnd, float[][] dest);

	public float[][] parallelReadTiffINT64(String fileName, float[][] dest) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffINT64(fileName, i, 0, i[TIFF_SLICES], dest);
	}

	public float[][] parallelReadTiffINT64(String fileName) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffINT64(fileName, i, 0, i[TIFF_SLICES], newFloatPlanes(i[TIFF_SLICES] * i[TIFF_SAMPLES], i[TIFF_WIDTH] * i[TIFF_HEIGHT]));
	}

	/** 64-bit unsigned integer images are converted to float (precision loss above 2^24). */
	public native float[][] parallelReadTiffUINT64(String fileName, long[] info, long zStart, long zEnd, float[][] dest);

	public float[][] parallelReadTiffUINT64(String fileName, float[][] dest) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffUINT64(fileName, i, 0, i[TIFF_SLICES], dest);
	}

	public float[][] parallelReadTiffUINT64(String fileName) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffUINT64(fileName, i, 0, i[TIFF_SLICES], newFloatPlanes(i[TIFF_SLICES] * i[TIFF_SAMPLES], i[TIFF_WIDTH] * i[TIFF_HEIGHT]));
	}

	/**
	 * 8-bit RGB/RGBA (interleaved or planar), packed into ImageJ int-based RGB pixels:
	 * one plane per slice.
	 */
	public native int[][] parallelReadTiffRGB8(String fileName, long[] info, long zStart, long zEnd, int[][] dest);

	public int[][] parallelReadTiffRGB8(String fileName, int[][] dest) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffRGB8(fileName, i, 0, i[TIFF_SLICES], dest);
	}

	public int[][] parallelReadTiffRGB8(String fileName) {
		long[] i = getTiffInfo(fileName);
		return parallelReadTiffRGB8(fileName, i, 0, i[TIFF_SLICES], newIntPlanes(i[TIFF_SLICES], i[TIFF_WIDTH] * i[TIFF_HEIGHT]));
	}

	// --- Zarr ---
	// A read takes the window [start, end), one value per axis of the array,
	// and axes = {x, y, z, c, t}: the axes that are the image's columns, rows,
	// planes, channels and time points (-1 for the last three when absent).
	// Every other axis must have extent 1 in the window. dest holds the planes
	// in ImageJ's czt order (plane c + C*(z + Z*t)), each of x*y elements.
	//
	// The overloads taking startX..endZ read 3D arrays laid out as the plugin
	// writes them (MATLAB's order): X is axis 0 (rows), Y axis 1 (columns) and
	// Z axis 2 (planes).

	/** The {x, y, z, c, t} axes of a 3D array in MATLAB's (rows, columns, planes) order. */
	private static final int[] MATLAB_3D_AXES = { 1, 0, 2, -1, -1 };

	/** The number of planes a window holds: its extent along z, c and t. */
	public static long planes(long[] start, long[] end, int[] axes) {
		long n = 1;
		for (int i = 2; i < 5; i++)
			if (axes[i] >= 0) n *= end[axes[i]] - start[axes[i]];
		return n;
	}

	/** The elements of each of a window's planes: its extent along x times y. */
	public static long planeElements(long[] start, long[] end, int[] axes) {
		return (end[axes[0]] - start[axes[0]]) * (end[axes[1]] - start[axes[1]]);
	}

	public native byte[][] parallelReadZarrUINT8(String fileName, long[] start, long[] end, int[] axes, byte[][] dest);

	public byte[][] parallelReadZarrUINT8(String fileName, long[] start, long[] end, int[] axes) {
		return parallelReadZarrUINT8(fileName, start, end, axes, newBytePlanes(planes(start, end, axes), planeElements(start, end, axes)));
	}

	public byte[][] parallelReadZarrUINT8(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, byte[][] dest) {
		return parallelReadZarrUINT8(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES, dest);
	}

	public byte[][] parallelReadZarrUINT8(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrUINT8(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES);
	}

	public native short[][] parallelReadZarrUINT16(String fileName, long[] start, long[] end, int[] axes, short[][] dest);

	public short[][] parallelReadZarrUINT16(String fileName, long[] start, long[] end, int[] axes) {
		return parallelReadZarrUINT16(fileName, start, end, axes, newShortPlanes(planes(start, end, axes), planeElements(start, end, axes)));
	}

	public short[][] parallelReadZarrUINT16(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, short[][] dest) {
		return parallelReadZarrUINT16(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES, dest);
	}

	public short[][] parallelReadZarrUINT16(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrUINT16(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES);
	}

	public native float[][] parallelReadZarrFLOAT(String fileName, long[] start, long[] end, int[] axes, float[][] dest);

	public float[][] parallelReadZarrFLOAT(String fileName, long[] start, long[] end, int[] axes) {
		return parallelReadZarrFLOAT(fileName, start, end, axes, newFloatPlanes(planes(start, end, axes), planeElements(start, end, axes)));
	}

	public float[][] parallelReadZarrFLOAT(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, float[][] dest) {
		return parallelReadZarrFLOAT(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES, dest);
	}

	public float[][] parallelReadZarrFLOAT(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrFLOAT(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES);
	}

	/** 64-bit images are converted to float: ImageStack has no double support. */
	public native float[][] parallelReadZarrDOUBLE(String fileName, long[] start, long[] end, int[] axes, float[][] dest);

	public float[][] parallelReadZarrDOUBLE(String fileName, long[] start, long[] end, int[] axes) {
		return parallelReadZarrDOUBLE(fileName, start, end, axes, newFloatPlanes(planes(start, end, axes), planeElements(start, end, axes)));
	}

	public float[][] parallelReadZarrDOUBLE(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, float[][] dest) {
		return parallelReadZarrDOUBLE(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES, dest);
	}

	public float[][] parallelReadZarrDOUBLE(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrDOUBLE(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES);
	}

	/**
	 * float16 or (with bfloat16) bfloat16 zarrs, converted to float: ImageJ has
	 * no 16-bit float type. The conversion is exact.
	 */
	public native float[][] parallelReadZarrFLOAT16(String fileName, long[] start, long[] end, int[] axes, boolean bfloat16, float[][] dest);

	public float[][] parallelReadZarrFLOAT16(String fileName, long[] start, long[] end, int[] axes, boolean bfloat16) {
		return parallelReadZarrFLOAT16(fileName, start, end, axes, bfloat16, newFloatPlanes(planes(start, end, axes), planeElements(start, end, axes)));
	}

	public float[][] parallelReadZarrFLOAT16(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, boolean bfloat16, float[][] dest) {
		return parallelReadZarrFLOAT16(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES, bfloat16, dest);
	}

	public float[][] parallelReadZarrFLOAT16(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, boolean bfloat16) {
		return parallelReadZarrFLOAT16(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES, bfloat16);
	}

	/** Signed 8-bit zarrs, shifted to 0-255; a calibration maps values back. */
	public native byte[][] parallelReadZarrINT8(String fileName, long[] start, long[] end, int[] axes, byte[][] dest);

	public byte[][] parallelReadZarrINT8(String fileName, long[] start, long[] end, int[] axes) {
		return parallelReadZarrINT8(fileName, start, end, axes, newBytePlanes(planes(start, end, axes), planeElements(start, end, axes)));
	}

	public byte[][] parallelReadZarrINT8(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, byte[][] dest) {
		return parallelReadZarrINT8(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES, dest);
	}

	public byte[][] parallelReadZarrINT8(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrINT8(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES);
	}

	/** Signed 16-bit zarrs, shifted to 0-65535 (ImageJ's signed-16 convention). */
	public native short[][] parallelReadZarrINT16(String fileName, long[] start, long[] end, int[] axes, short[][] dest);

	public short[][] parallelReadZarrINT16(String fileName, long[] start, long[] end, int[] axes) {
		return parallelReadZarrINT16(fileName, start, end, axes, newShortPlanes(planes(start, end, axes), planeElements(start, end, axes)));
	}

	public short[][] parallelReadZarrINT16(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, short[][] dest) {
		return parallelReadZarrINT16(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES, dest);
	}

	public short[][] parallelReadZarrINT16(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrINT16(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES);
	}

	/** 32-bit integer zarrs are converted to float: ImageStack has no int display. */
	public native float[][] parallelReadZarrINT32(String fileName, long[] start, long[] end, int[] axes, float[][] dest);

	public float[][] parallelReadZarrINT32(String fileName, long[] start, long[] end, int[] axes) {
		return parallelReadZarrINT32(fileName, start, end, axes, newFloatPlanes(planes(start, end, axes), planeElements(start, end, axes)));
	}

	public float[][] parallelReadZarrINT32(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, float[][] dest) {
		return parallelReadZarrINT32(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES, dest);
	}

	public float[][] parallelReadZarrINT32(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrINT32(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES);
	}

	/** 32-bit unsigned integer zarrs are converted to float. */
	public native float[][] parallelReadZarrUINT32(String fileName, long[] start, long[] end, int[] axes, float[][] dest);

	public float[][] parallelReadZarrUINT32(String fileName, long[] start, long[] end, int[] axes) {
		return parallelReadZarrUINT32(fileName, start, end, axes, newFloatPlanes(planes(start, end, axes), planeElements(start, end, axes)));
	}

	public float[][] parallelReadZarrUINT32(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, float[][] dest) {
		return parallelReadZarrUINT32(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES, dest);
	}

	public float[][] parallelReadZarrUINT32(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrUINT32(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES);
	}

	/** 64-bit integer zarrs are converted to float (precision loss above 2^24). */
	public native float[][] parallelReadZarrINT64(String fileName, long[] start, long[] end, int[] axes, float[][] dest);

	public float[][] parallelReadZarrINT64(String fileName, long[] start, long[] end, int[] axes) {
		return parallelReadZarrINT64(fileName, start, end, axes, newFloatPlanes(planes(start, end, axes), planeElements(start, end, axes)));
	}

	public float[][] parallelReadZarrINT64(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, float[][] dest) {
		return parallelReadZarrINT64(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES, dest);
	}

	public float[][] parallelReadZarrINT64(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrINT64(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES);
	}

	/** 64-bit unsigned integer zarrs are converted to float (precision loss above 2^24). */
	public native float[][] parallelReadZarrUINT64(String fileName, long[] start, long[] end, int[] axes, float[][] dest);

	public float[][] parallelReadZarrUINT64(String fileName, long[] start, long[] end, int[] axes) {
		return parallelReadZarrUINT64(fileName, start, end, axes, newFloatPlanes(planes(start, end, axes), planeElements(start, end, axes)));
	}

	public float[][] parallelReadZarrUINT64(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, float[][] dest) {
		return parallelReadZarrUINT64(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES, dest);
	}

	public float[][] parallelReadZarrUINT64(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ) {
		return parallelReadZarrUINT64(fileName, new long[] { startX, startY, startZ }, new long[] { endX, endY, endZ }, MATLAB_3D_AXES);
	}

	/**
	 * For tests: the least a slab of a big Zarr read holds (1 GiB by default;
	 * reads of less than twice that are done at once).
	 */
	static native void setZarrSlabBytes(long bytes);

	/** Bits per pixel of the zarr on disk. */
	public native long getZarrDataType(String fileName);

	/** The numpy-style dtype of the zarr on disk, e.g. "&lt;u2" or "&lt;i4". */
	public native String getZarrDtype(String fileName);

	/** Shape of the zarr on disk, one value per axis. */
	public native long[] getZarrDims(String fileName);
}
