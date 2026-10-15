package edu.abc.berkeley;

import static edu.abc.berkeley.ParallelReadNative.TIFF_BITS;
import static edu.abc.berkeley.ParallelReadNative.TIFF_HEIGHT;
import static edu.abc.berkeley.ParallelReadNative.TIFF_SAMPLES;
import static edu.abc.berkeley.ParallelReadNative.TIFF_SAMPLE_FORMAT;
import static edu.abc.berkeley.ParallelReadNative.TIFF_SLICES;
import static edu.abc.berkeley.ParallelReadNative.TIFF_WIDTH;

import java.io.File;
import java.util.Arrays;

/**
 * A TIFF file as ImageJ planes, read with cpp-tiff from one description of
 * the file (getTiffInfo), so that reading a few slices of a deep stack, as the
 * virtual stack does, does not walk its directories again. 8-bit color shows
 * as ImageJ RGB; other images with several samples per pixel (16-bit or
 * float color, for example) as one channel per sample, as ImageJ shows them.
 */
public final class TiffImage {

	public final String path;
	/** The file's name, for the window title. */
	public final String title;
	public final int width, height;
	/** The file's slices: its directories, or the images of an ImageJ stack. */
	public final long slices;
	/** Bits and samples per pixel, and the sample format (1 = unsigned, 2 = signed, 3 = float). */
	public final int bits, samples, sampleFormat;
	private final long[] info;

	/**
	 * @throws RuntimeException when the file cannot be read as a TIFF
	 */
	public static TiffImage open(String path) {
		return new TiffImage(path);
	}

	private TiffImage(String path) {
		this.path = path;
		this.title = new File(path).getName();
		this.info = new ParallelReadNative().getTiffInfo(path);
		long w = info[TIFF_WIDTH], h = info[TIFF_HEIGHT];
		if (w * h > Integer.MAX_VALUE - 8)
			throw new IllegalArgumentException(title + " is too large for ImageJ: its slices are " + w + " x " + h + " pixels");
		this.width = (int) w;
		this.height = (int) h;
		this.slices = info[TIFF_SLICES];
		this.bits = (int) info[TIFF_BITS];
		this.samples = (int) info[TIFF_SAMPLES];
		this.sampleFormat = (int) info[TIFF_SAMPLE_FORMAT];
		if (bits != 8 && bits != 16 && bits != 32 && bits != 64)
			throw new IllegalArgumentException(title + " has " + bits + "-bit samples, which the plugin cannot read");
		if (slices * channels() > Integer.MAX_VALUE)
			throw new IllegalArgumentException(title + " has more planes than ImageJ can show");
	}

	/** 8-bit color, shown as ImageJ RGB. */
	public boolean isRgb() {
		return bits == 8 && (samples == 3 || samples == 4);
	}

	/** ImageJ channels per slice: one per sample, except for 8-bit color. */
	public int channels() {
		return isRgb() ? 1 : samples;
	}

	/** ImageJ planes: a slice's channels, then the next slice's. */
	public long planes() {
		return slices * channels();
	}

	/** ImageJ's bit depth: 24 for color, 32 for the types ImageJ shows as float. */
	public int displayBits() {
		return isRgb() ? 24 : bits <= 16 ? bits : 32;
	}

	/** Signed integers, which are read shifted into ImageJ's unsigned range. */
	public boolean isSigned() {
		return sampleFormat == 2 && bits <= 16;
	}

	/** ImageJ planes [first, last), as pixel arrays. */
	public Object[] read(long first, long last) {
		int c = channels();
		long z0 = first / c, z1 = (last + c - 1) / c;
		Object[] planes = readSlices(z0, z1);
		int from = (int) (first - z0 * c);
		if (from == 0 && planes.length == last - first) return planes;
		return Arrays.copyOfRange(planes, from, from + (int) (last - first));
	}

	/** {@link #read(long, long)}, showing the progress bar labeled with what. */
	public Object[] read(long first, long last, String what) {
		int c = channels();
		long z = (last + c - 1) / c - first / c;
		ProgressEstimator progress = ProgressEstimator.begin("readTiff", z * width * height * samples * (bits / 8), z, what);
		try {
			Object[] planes = read(first, last);
			progress.finish();
			return planes;
		}
		finally {
			progress.abort();
		}
	}

	private Object[] readSlices(long z0, long z1) {
		ParallelReadNative r = new ParallelReadNative();
		long n = (long) width * height, planes = (z1 - z0) * channels();
		if (isRgb()) return r.parallelReadTiffRGB8(path, info, z0, z1, ParallelReadNative.newIntPlanes(planes, n));
		switch (bits) {
			case 8:
				// Signed bytes are shifted to 0-255; a calibration maps them back
				if (sampleFormat == 2) return r.parallelReadTiffINT8(path, info, z0, z1, ParallelReadNative.newBytePlanes(planes, n));
				return r.parallelReadTiffUINT8(path, info, z0, z1, ParallelReadNative.newBytePlanes(planes, n));
			case 16:
				if (sampleFormat == 2) return r.parallelReadTiffINT16(path, info, z0, z1, ParallelReadNative.newShortPlanes(planes, n));
				return r.parallelReadTiffUINT16(path, info, z0, z1, ParallelReadNative.newShortPlanes(planes, n));
			case 32:
				if (sampleFormat == 2) return r.parallelReadTiffINT32(path, info, z0, z1, ParallelReadNative.newFloatPlanes(planes, n));
				if (sampleFormat == 1) return r.parallelReadTiffUINT32(path, info, z0, z1, ParallelReadNative.newFloatPlanes(planes, n));
				return r.parallelReadTiffFLOAT(path, info, z0, z1, ParallelReadNative.newFloatPlanes(planes, n));
			default:
				// ImageJ has no double or 64-bit integer type; all become float
				if (sampleFormat == 2) return r.parallelReadTiffINT64(path, info, z0, z1, ParallelReadNative.newFloatPlanes(planes, n));
				if (sampleFormat == 1) return r.parallelReadTiffUINT64(path, info, z0, z1, ParallelReadNative.newFloatPlanes(planes, n));
				return r.parallelReadTiffDOUBLE(path, info, z0, z1, ParallelReadNative.newFloatPlanes(planes, n));
		}
	}
}
