package edu.abc.berkeley;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Writes small uncompressed little-endian TIFFs for the tests, in layouts
 * the plugin's writer and ImageJ's do not produce: several samples per pixel
 * at 16 bits, and ImageJ stacks stored as one directory followed by every
 * plane in one block (how ImageJ saves stacks over 4 GB).
 */
final class TiffFixture {

	private TiffFixture() {
	}

	/** The value the fixtures store: distinct for every slice, sample and pixel of a small image. */
	static short value(int slice, int sample, int pixel) {
		return (short) (slice * 1000 + sample * 300 + pixel + 1);
	}

	/**
	 * A 16-bit TIFF of the given slices, samples interleaved; with oneBlock,
	 * one directory with an ImageJ description of images=slices, the planes
	 * one after another.
	 */
	static File write(File f, int w, int h, int slices, int samples, boolean oneBlock) throws IOException {
		final int ifds = oneBlock ? 1 : slices;
		final String description = oneBlock ? "ImageJ=1.54f\nimages=" + slices + "\nslices=" + slices + "\nloop=false\n\0" : null;
		final int entries = 11 + (description != null ? 1 : 0);
		final int ifdBytes = 2 + entries * 12 + 4;
		final int extraBytes = (samples > 2 ? samples * 2 : 0) + (description != null ? description.length() : 0);
		final int perIfd = ifdBytes + extraBytes + (extraBytes % 2);
		final int sliceBytes = w * h * samples * 2;
		final int dataStart = 8 + ifds * perIfd;
		ByteBuffer b = ByteBuffer.allocate(dataStart + slices * sliceBytes).order(ByteOrder.LITTLE_ENDIAN);
		b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
		for (int i = 0; i < ifds; i++) {
			final int ifd = 8 + i * perIfd, extra = ifd + ifdBytes;
			final int bitsAt = extra, descriptionAt = extra + (samples > 2 ? samples * 2 : 0);
			b.position(ifd);
			b.putShort((short) entries);
			entry(b, 256, 4, 1, w);
			entry(b, 257, 4, 1, h);
			entry(b, 258, 3, samples, samples > 2 ? bitsAt : 16 | (samples == 2 ? 16 << 16 : 0));
			entry(b, 259, 3, 1, 1);                               // no compression
			entry(b, 262, 3, 1, samples >= 3 ? 2 : 1);            // RGB or gray
			if (description != null) entry(b, 270, 2, description.length(), descriptionAt);
			entry(b, 273, 4, 1, dataStart + i * sliceBytes);      // one strip
			entry(b, 277, 3, 1, samples);
			entry(b, 278, 4, 1, h);
			entry(b, 279, 4, 1, sliceBytes);
			entry(b, 284, 3, 1, 1);                               // interleaved samples
			entry(b, 339, 3, 1, 1);                               // unsigned integers
			b.putInt(i + 1 < ifds ? ifd + perIfd : 0);
			if (samples > 2) for (int s = 0; s < samples; s++) b.putShort((short) 16);
			if (description != null) b.put(description.getBytes(StandardCharsets.US_ASCII));
		}
		b.position(dataStart);
		for (int z = 0; z < slices; z++)
			for (int p = 0; p < w * h; p++)
				for (int s = 0; s < samples; s++)
					b.putShort(value(z, s, p));
		Files.write(f.toPath(), b.array());
		return f;
	}

	/** A plane as the plugin shows it: slice z's sample s. */
	static short[] plane(int w, int h, int z, int s) {
		short[] p = new short[w * h];
		for (int i = 0; i < p.length; i++) p[i] = value(z, s, i);
		return p;
	}

	private static void entry(ByteBuffer b, int tag, int type, int count, int value) {
		b.putShort((short) tag).putShort((short) type).putInt(count);
		if (type == 3 && count == 1) b.putShort((short) value).putShort((short) 0);
		else b.putInt(value);
	}
}
