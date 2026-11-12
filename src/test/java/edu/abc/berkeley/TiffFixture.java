package edu.abc.berkeley;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes small uncompressed TIFFs for the tests, in layouts the plugin's
 * writer and ImageJ's do not produce: several samples per pixel at 16 bits,
 * and ImageJ stacks stored as one directory followed by every plane in one
 * block (how ImageJ saves stacks over 4 GB), in either byte order, classic or
 * BigTIFF, of every pixel type the plugin reads ({@link Layout}).
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

	// --- Any pixel type, byte order and directory layout ---

	/** How {@link #write(File, Layout)} stores an image. */
	static final class Layout {
		int width = 13, height = 7, slices = 4, samples = 1;
		/** Bits per sample (8, 16, 32 or 64) and the sample format (1 = unsigned, 2 = signed, 3 = float). */
		int bits = 16, format = 1;
		ByteOrder order = ByteOrder.LITTLE_ENDIAN;
		boolean bigTiff;
		/**
		 * One directory with an ImageJ description (images=slices), then the
		 * slices one after another, as ImageJ saves stacks over 4 GB; otherwise
		 * a directory per slice and no description, which cpp-tiff reads.
		 */
		boolean oneBlock = true;
		/** Rows per strip (0: the whole slice in one strip). */
		int rowsPerStrip;
		/** The strip offsets' type: 3 (SHORT), 4 (LONG) or 16 (LONG8, BigTIFF only). */
		int offsetType = 4;

		Layout(int bits, int format, int samples) {
			this.bits = bits;
			this.format = format;
			this.samples = samples;
		}

		Layout size(int width, int height, int slices) {
			this.width = width;
			this.height = height;
			this.slices = slices;
			return this;
		}

		Layout order(ByteOrder order) {
			this.order = order;
			return this;
		}

		Layout bigTiff(boolean bigTiff, int offsetType) {
			this.bigTiff = bigTiff;
			this.offsetType = offsetType;
			return this;
		}

		Layout strips(int rowsPerStrip, int offsetType) {
			this.rowsPerStrip = rowsPerStrip;
			this.offsetType = offsetType;
			return this;
		}

		Layout oneBlock(boolean oneBlock) {
			this.oneBlock = oneBlock;
			return this;
		}

		/** 8-bit color, which the plugin packs into ImageJ RGB pixels. */
		boolean rgb() {
			return bits == 8 && (samples == 3 || samples == 4);
		}

		/** The image's ImageJ planes: one per slice for color, else one per sample of each slice. */
		int planes() {
			return rgb() ? slices : slices * samples;
		}

		long sliceBytes() {
			return (long) width * height * samples * bits / 8;
		}

		@Override
		public String toString() {
			return bits + "-bit " + (format == 3 ? "float" : format == 2 ? "signed" : "unsigned") + ", " + samples + " sample(s), "
				+ (order == ByteOrder.BIG_ENDIAN ? "big" : "little") + "-endian" + (bigTiff ? " BigTIFF" : "")
				+ (oneBlock ? ", one block" : ", a directory per slice") + (rowsPerStrip > 0 ? ", " + rowsPerStrip + " rows per strip" : "")
				+ ", offsets of type " + offsetType + ", " + width + "x" + height + "x" + slices;
		}
	}

	/**
	 * The bits stored for sample s of pixel p of slice z: distinct, with every
	 * byte of the sample in use (so swapped bytes show), and negative values
	 * for the signed types.
	 */
	static long stored(Layout l, int z, int s, int p) {
		final long v = z * 1000L + s * 300L + p + 1;
		switch (l.bits) {
			case 8:
				return (v * 7) & 0xFF;
			case 16:
				return (v * 37) & 0xFFFF;
			case 32:
				return l.format == 3 ? Float.floatToIntBits(v * 0.37f - 50) & 0xFFFFFFFFL : (v * 1000003L) & 0xFFFFFFFFL;
			default:
				return l.format == 3 ? Double.doubleToLongBits(v * 0.37 - 50.123456789) : l.format == 2 ? -v * 123456789123L : v * 123456789123L;
		}
	}

	/**
	 * ImageJ plane n (0-based) of an image written with l as the plugin shows
	 * it: signed integers shifted into ImageJ's unsigned range, 32- and 64-bit
	 * integers and doubles as float, 8-bit color packed into RGB pixels.
	 */
	static Object plane(Layout l, int n) {
		final int px = l.width * l.height;
		if (l.rgb()) {
			int[] p = new int[px];
			for (int i = 0; i < px; i++)
				p[i] = 0xFF000000 | (int) stored(l, n, 0, i) << 16 | (int) stored(l, n, 1, i) << 8 | (int) stored(l, n, 2, i);
			return p;
		}
		final int z = n / l.samples, s = n % l.samples;
		switch (l.bits) {
			case 8: {
				byte[] p = new byte[px];
				for (int i = 0; i < px; i++) p[i] = (byte) (stored(l, z, s, i) ^ (l.format == 2 ? 0x80 : 0));
				return p;
			}
			case 16: {
				short[] p = new short[px];
				for (int i = 0; i < px; i++) p[i] = (short) (stored(l, z, s, i) ^ (l.format == 2 ? 0x8000 : 0));
				return p;
			}
			default: {
				float[] p = new float[px];
				for (int i = 0; i < px; i++) {
					final long v = stored(l, z, s, i);
					if (l.bits == 32) p[i] = l.format == 3 ? Float.intBitsToFloat((int) v) : l.format == 2 ? (float) (int) v : (float) v;
					else p[i] = l.format == 3 ? (float) Double.longBitsToDouble(v) : (float) v;
				}
				return p;
			}
		}
	}

	/** Writes the image l describes, its samples interleaved, uncompressed. */
	static File write(File f, Layout l) throws IOException {
		final byte[] directories = directories(l);
		final long total = directories.length + l.slices * l.sliceBytes();
		if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("too large for a test image: " + l);
		ByteBuffer b = ByteBuffer.allocate((int) total).order(l.order);
		b.put(directories);
		for (int z = 0; z < l.slices; z++)
			for (int p = 0; p < l.width * l.height; p++)
				for (int s = 0; s < l.samples; s++) {
					final long v = stored(l, z, s, p);
					if (l.bits == 8) b.put((byte) v);
					else if (l.bits == 16) b.putShort((short) v);
					else if (l.bits == 32) b.putInt((int) v);
					else b.putLong(v);
				}
		Files.write(f.toPath(), b.array());
		return f;
	}

	/**
	 * The header and directories of the image l describes: the file's bytes
	 * up to its first slice, which starts right after them (so a test can
	 * write the slices itself, a few of them in a sparse file).
	 */
	static byte[] directories(Layout l) {
		final boolean big = l.bigTiff;
		final int rows = l.rowsPerStrip > 0 ? Math.min(l.rowsPerStrip, l.height) : l.height;
		final int strips = (l.height + rows - 1) / rows;
		final long rowBytes = (long) l.width * l.samples * l.bits / 8;
		final int ifds = l.oneBlock ? 1 : l.slices;
		final String description = l.oneBlock ? "ImageJ=1.54f\nimages=" + l.slices + "\nslices=" + l.slices + "\nloop=false\n\0" : null;
		// A directory: its entries, then the values that do not fit in an entry
		final List<Entry> entries = new ArrayList<Entry>();
		entries.add(new Entry(256, 4, l.width));
		entries.add(new Entry(257, 4, l.height));
		long[] bits = new long[l.samples];
		Arrays.fill(bits, l.bits);
		entries.add(new Entry(258, 3, bits));
		entries.add(new Entry(259, 3, 1));                                   // no compression
		entries.add(new Entry(262, 3, l.samples >= 3 ? 2 : 1));              // RGB or gray
		if (description != null) entries.add(new Entry(270, 2, description.getBytes(StandardCharsets.US_ASCII)));
		final Entry offsets = new Entry(273, l.offsetType, new long[strips]);
		entries.add(offsets);
		entries.add(new Entry(277, 3, l.samples));
		entries.add(new Entry(278, 4, rows));
		long[] counts = new long[strips];
		for (int s = 0; s < strips; s++) counts[s] = Math.min(rows, l.height - s * rows) * rowBytes;
		entries.add(new Entry(279, 4, counts));
		entries.add(new Entry(284, 3, 1));                                   // interleaved samples
		entries.add(new Entry(339, 3, l.format));
		final int countBytes = big ? 8 : 2, entryBytes = big ? 20 : 12, inline = big ? 8 : 4;
		long extra = 0;
		for (Entry e : entries)
			if (e.bytes() > inline) extra += e.bytes() + (e.bytes() & 1);
		final long ifdBytes = countBytes + entries.size() * (long) entryBytes + inline + extra;
		final long header = big ? 16 : 8, dataStart = header + ifds * ifdBytes;
		ByteBuffer b = ByteBuffer.allocate((int) dataStart).order(l.order);
		final byte order = (byte) (l.order == ByteOrder.BIG_ENDIAN ? 'M' : 'I');
		b.put(order).put(order);
		if (big) b.putShort((short) 43).putShort((short) 8).putShort((short) 0).putLong(header);
		else b.putShort((short) 42).putInt((int) header);
		for (int i = 0; i < ifds; i++) {
			final long ifd = header + i * ifdBytes;
			for (int s = 0; s < strips; s++) offsets.values[s] = dataStart + i * l.sliceBytes() + s * rows * rowBytes;
			if (l.offsetType == 3 && offsets.values[strips - 1] > 0xFFFF) throw new IllegalArgumentException("offsets too large for SHORT: " + l);
			b.position((int) ifd);
			if (big) b.putLong(entries.size());
			else b.putShort((short) entries.size());
			long at = ifd + countBytes + entries.size() * (long) entryBytes + inline;
			for (Entry e : entries) {
				b.putShort((short) e.tag).putShort((short) e.type);
				if (big) b.putLong(e.count());
				else b.putInt(e.count());
				final int value = b.position();
				if (e.bytes() <= inline) e.put(b);
				else {
					if (big) b.putLong(at);
					else b.putInt((int) at);
					b.position((int) at);
					e.put(b);
					at += e.bytes() + (e.bytes() & 1);
				}
				b.position(value + inline);
			}
			final long next = i + 1 < ifds ? ifd + ifdBytes : 0;
			if (big) b.putLong(next);
			else b.putInt((int) next);
		}
		return b.array();
	}

	/** A directory entry: its tag, type (2 ASCII, 3 SHORT, 4 LONG, 16 LONG8) and values. */
	private static final class Entry {
		final int tag, type;
		final long[] values;
		final byte[] text;

		Entry(int tag, int type, long... values) {
			this.tag = tag;
			this.type = type;
			this.values = values;
			this.text = null;
		}

		Entry(int tag, int type, byte[] text) {
			this.tag = tag;
			this.type = type;
			this.values = null;
			this.text = text;
		}

		int count() {
			return text != null ? text.length : values.length;
		}

		long bytes() {
			return (long) count() * (type == 2 ? 1 : type == 3 ? 2 : type == 4 ? 4 : 8);
		}

		void put(ByteBuffer b) {
			if (text != null) b.put(text);
			else for (long v : values) {
				if (type == 3) b.putShort((short) v);
				else if (type == 4) b.putInt((int) v);
				else b.putLong(v);
			}
		}
	}
}
