package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import edu.abc.berkeley.TiffFixture.Layout;

/**
 * Reads through cpp-tiff a slab of slices at a time, through one reused
 * buffer: every pixel type, sample count and layout (strips, samples in
 * separate planes, tiles, LZW) read in slabs of several sizes equals the read
 * in one piece and the values written; slice ranges; and a damaged file fails
 * as it does in one piece. setTiffSlabBytes(b) makes the reads cut into
 * slabs of at least b bytes, at most 8 of them, so a read of n slices with b
 * = k slices goes in min(n / k, 8) slabs of equal size but the last.
 */
public class TiffSlabTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@BeforeClass
	public static void nativesRequired() {
		Natives.required();
	}

	@After
	public void defaultSlabs() {
		ParallelReadNative.setTiffSlabBytes(0);
	}

	/** Every type the plugin reads: bits, sample format and samples per pixel. */
	private static final int[][] TYPES = {
		{ 8, 1, 1 }, { 8, 2, 1 }, { 16, 1, 1 }, { 16, 2, 1 }, { 32, 3, 1 }, { 32, 2, 1 }, { 32, 1, 1 }, { 64, 3, 1 },
		{ 64, 2, 1 }, { 64, 1, 1 }, { 8, 1, 3 }, { 8, 1, 4 }, { 16, 1, 2 }, { 16, 1, 3 }, { 32, 3, 3 }
	};

	private int files;

	private File file() {
		return new File(tmp.getRoot(), "slabs" + files++ + ".tif");
	}

	/** l a directory per slice, its samples interleaved in strips: what cpp-tiff reads. */
	private String write(Layout l) throws IOException {
		return TiffFixture.write(file(), l.oneBlock(false)).getPath();
	}

	private static Object[] readAll(String path) {
		TiffImage t = TiffImage.open(path);
		return t.read(0, t.planes());
	}

	private static Object[] expected(Layout l) {
		Object[] planes = new Object[l.planes()];
		for (int n = 0; n < planes.length; n++) planes[n] = TiffFixture.plane(l, n);
		return planes;
	}

	private static void assertPlanesEqual(String what, Object[] expected, Object[] actual) {
		assertEquals(what + ": planes", expected.length, actual.length);
		for (int n = 0; n < expected.length; n++) assertPlaneEquals(what + ", plane " + n, expected[n], actual[n]);
	}

	private static void assertPlaneEquals(String what, Object expected, Object actual) {
		assertNotNull(what + ": no plane", actual);
		assertEquals(what + ": type", expected.getClass(), actual.getClass());
		if (expected instanceof byte[]) assertArrayEquals(what, (byte[]) expected, (byte[]) actual);
		else if (expected instanceof short[]) assertArrayEquals(what, (short[]) expected, (short[]) actual);
		else if (expected instanceof int[]) assertArrayEquals(what, (int[]) expected, (int[]) actual);
		// Bit for bit: NaN and -0 as well
		else {
			float[] e = (float[]) expected, a = (float[]) actual;
			assertEquals(what + ": length", e.length, a.length);
			for (int i = 0; i < e.length; i++)
				if (Float.floatToRawIntBits(e[i]) != Float.floatToRawIntBits(a[i])) fail(what + ": pixel " + i + " is " + a[i] + ", not " + e[i]);
		}
	}

	/**
	 * The file read in one piece and in slabs of 1 to 7 slices, of sizes that
	 * divide its slices and that do not, and of a few bytes (a slice per slab).
	 */
	private static void assertSlabsReadAsOnePiece(String what, String path, Object[] expected, long sliceBytes, int slices) {
		ParallelReadNative.setTiffSlabBytes(0);
		Object[] whole = readAll(path);
		assertPlanesEqual(what + ", in one piece", expected, whole);
		for (long slab : new long[] { 1, sliceBytes, 2 * sliceBytes, 3 * sliceBytes - 1, 3 * sliceBytes, slices / 2 * sliceBytes, slices * sliceBytes }) {
			ParallelReadNative.setTiffSlabBytes(slab);
			assertPlanesEqual(what + ", slabs of at least " + slab + " bytes", whole, readAll(path));
		}
	}

	/** Every type: the conversions and the RGB packing empty every slab into the right planes. */
	@Test
	public void everyTypeInSlabs() throws Exception {
		for (int[] type : TYPES) {
			Layout l = new Layout(type[0], type[1], type[2]).size(9, 5, 7);
			String path = write(l);
			long[] info = new ParallelReadNative().getTiffInfo(path);
			assertEquals(l + ": read by cpp-tiff", 0, info[ParallelReadNative.TIFF_IMAGEJ]);
			assertSlabsReadAsOnePiece(l.toString(), path, expected(l), l.sliceBytes(), l.slices);
		}
	}

	/** More slices than 8 slabs of one slice: the slabs grow, the last one shorter. */
	@Test
	public void atMostEightSlabs() throws Exception {
		for (int[] type : new int[][] { { 16, 1, 1 }, { 16, 1, 3 }, { 8, 1, 3 } }) {
			Layout l = new Layout(type[0], type[1], type[2]).size(6, 4, 30);
			String path = write(l);
			assertSlabsReadAsOnePiece(l.toString(), path, expected(l), l.sliceBytes(), l.slices);
		}
	}

	/** Strips of a few rows, compressed (cpp-tiff's own LZW files), on planes several threads decode. */
	@Test
	public void lzwStrips() throws Exception {
		for (int[] type : new int[][] { { 8, 1, 1 }, { 16, 1, 1 }, { 32, 3, 1 } }) {
			Layout l = new Layout(type[0], type[1], type[2]).size(301, 203, 11);
			Object[] planes = expected(l);
			String path = file().getPath();
			new ParallelWriteNative().parallelWriteTiff(path, planes, l.width, l.height, l.slices, l.bits);
			long[] info = new ParallelReadNative().getTiffInfo(path);
			assertEquals(l + ": LZW", 5, info[ParallelReadNative.TIFF_COMPRESSION]);
			assertSlabsReadAsOnePiece(l + ", LZW", path, planes, l.sliceBytes(), l.slices);
		}
	}

	/** Samples stored as separate planes, and tiles (partial at the right and bottom edges). */
	@Test
	public void planarAndTiledLayouts() throws Exception {
		for (int[] type : new int[][] { { 16, 1, 3 }, { 8, 1, 3 }, { 16, 1, 2 }, { 32, 3, 3 } }) {
			Layout l = new Layout(type[0], type[1], type[2]).size(21, 9, 7);
			String path = writeSeparate(file(), l, true, 0).getPath();
			assertEquals(l + ": planar", 1, new ParallelReadNative().getTiffInfo(path)[ParallelReadNative.TIFF_PLANAR]);
			assertSlabsReadAsOnePiece(l + ", planar", path, expected(l), l.sliceBytes(), l.slices);
		}
		for (int[] type : new int[][] { { 16, 1, 1 }, { 8, 1, 3 }, { 16, 1, 3 }, { 32, 3, 1 } }) {
			Layout l = new Layout(type[0], type[1], type[2]).size(37, 20, 7);
			String path = writeSeparate(file(), l, false, 16).getPath();
			assertEquals(l + ": tiled", 1, new ParallelReadNative().getTiffInfo(path)[ParallelReadNative.TIFF_TILED]);
			assertSlabsReadAsOnePiece(l + ", tiles", path, expected(l), l.sliceBytes(), l.slices);
		}
	}

	/** Every range of planes in slabs, also ones that start or end inside a slice's samples. */
	@Test
	public void planeRanges() throws Exception {
		for (int[] type : new int[][] { { 16, 1, 1 }, { 16, 2, 1 }, { 16, 1, 3 }, { 8, 1, 3 }, { 32, 3, 1 } }) {
			Layout l = new Layout(type[0], type[1], type[2]).size(11, 6, 9);
			TiffImage tiff = TiffImage.open(write(l));
			Object[] all = expected(l);
			ParallelReadNative.setTiffSlabBytes(2 * l.sliceBytes());
			for (int first = 0; first < all.length; first++)
				for (int last = first + 1; last <= all.length; last++)
					assertPlanesEqual(l + ", planes " + first + "-" + last, Arrays.copyOfRange(all, first, last), tiff.read(first, last));
		}
	}

	/** Slice ranges through the natives, into planes the caller gives and into new ones. */
	@Test
	public void sliceRangesAndNewPlanes() throws Exception {
		ParallelReadNative r = new ParallelReadNative();
		Layout gray = new Layout(16, 1, 1).size(10, 4, 12), signed = new Layout(16, 2, 1).size(10, 4, 12), rgb = new Layout(8, 1, 3).size(10, 4, 12);
		String grayPath = write(gray), signedPath = write(signed), rgbPath = write(rgb);
		long[] grayInfo = r.getTiffInfo(grayPath), signedInfo = r.getTiffInfo(signedPath), rgbInfo = r.getTiffInfo(rgbPath);
		Object[] g = expected(gray), s = expected(signed), c = expected(rgb);
		ParallelReadNative.setTiffSlabBytes(gray.sliceBytes());
		for (int z0 = 0; z0 < 12; z0 += 5) {
			int z1 = Math.min(12, z0 + 7), n = z1 - z0;
			assertPlanesEqual("gray " + z0 + "-" + z1, Arrays.copyOfRange(g, z0, z1), r.parallelReadTiffUINT16(grayPath, grayInfo, z0, z1, null));
			assertPlanesEqual("gray " + z0 + "-" + z1 + " into planes given", Arrays.copyOfRange(g, z0, z1),
				r.parallelReadTiffUINT16(grayPath, grayInfo, z0, z1, ParallelReadNative.newShortPlanes(n, 40)));
			assertPlanesEqual("signed " + z0 + "-" + z1, Arrays.copyOfRange(s, z0, z1), r.parallelReadTiffINT16(signedPath, signedInfo, z0, z1, null));
			assertPlanesEqual("color " + z0 + "-" + z1, Arrays.copyOfRange(c, z0, z1), r.parallelReadTiffRGB8(rgbPath, rgbInfo, z0, z1, null));
		}
		try {
			r.parallelReadTiffUINT16(grayPath, grayInfo, 0, 12, new short[12][39]);
			fail("read into planes that do not fit");
		}
		catch (RuntimeException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("Destination"));
		}
	}

	/** A file whose last slices are cut off (an interrupted copy): the slices left read, any read of the others fails. */
	@Test
	public void damagedFile() throws Exception {
		for (int[] type : new int[][] { { 16, 1, 1 }, { 16, 2, 1 }, { 8, 1, 3 }, { 32, 3, 1 } }) {
			Layout l = new Layout(type[0], type[1], type[2]).size(13, 7, 9);
			File f = new File(write(l));
			try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
				raf.setLength(f.length() - 2 * l.sliceBytes() - 3);
			}
			TiffImage tiff = TiffImage.open(f.getPath());
			assertEquals(l + ": slices", 9, tiff.slices);
			final int perSlice = l.planes() / l.slices;
			Object[] all = expected(l);
			for (long slab : new long[] { 0, 1, 2 * l.sliceBytes(), 3 * l.sliceBytes() }) {
				ParallelReadNative.setTiffSlabBytes(slab);
				assertPlanesEqual(l + ", the slices left, slabs of " + slab, Arrays.copyOfRange(all, 0, 6 * perSlice), tiff.read(0, 6 * perSlice));
				for (long[] range : new long[][] { { 0, l.planes() }, { 4 * perSlice, 8 * perSlice }, { 8 * perSlice, 9 * perSlice } }) {
					try {
						tiff.read(range[0], range[1]);
						fail(l + ", slabs of " + slab + ": read planes " + range[0] + "-" + range[1] + " of a file cut short");
					}
					catch (RuntimeException e) {
						assertTrue(e.getMessage(), e.getMessage().contains("damaged"));
					}
				}
			}
		}
	}

	/**
	 * Writes l uncompressed, little-endian, a directory per slice: with planar,
	 * each sample in a plane of its own (one strip per sample); else the
	 * samples interleaved in tiles of tile x tile pixels, padded at the edges.
	 */
	private static File writeSeparate(File f, Layout l, boolean planar, int tile) throws IOException {
		final int bytes = l.bits / 8, w = l.width, h = l.height, px = w * h;
		final int across = tile > 0 ? (w + tile - 1) / tile : 0, down = tile > 0 ? (h + tile - 1) / tile : 0;
		final int pieces = planar ? l.samples : across * down;
		final long pieceBytes = planar ? (long) px * bytes : (long) tile * tile * l.samples * bytes;
		// The directories after the pixels, each on a word boundary
		final long data = 8, ifds = (data + l.slices * pieces * pieceBytes + 1) & ~1L;
		ByteBuffer b = ByteBuffer.allocate((int) (ifds + l.slices * (512L + 8L * pieces))).order(ByteOrder.LITTLE_ENDIAN);
		b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt((int) ifds);
		for (int z = 0; z < l.slices; z++) {
			if (planar) {
				for (int s = 0; s < l.samples; s++)
					for (int p = 0; p < px; p++) put(b, l.bits, TiffFixture.stored(l, z, s, p));
			}
			else {
				for (int ty = 0; ty < down; ty++)
					for (int tx = 0; tx < across; tx++)
						for (int yy = 0; yy < tile; yy++)
							for (int xx = 0; xx < tile; xx++) {
								final int x = tx * tile + xx, y = ty * tile + yy;
								for (int s = 0; s < l.samples; s++) put(b, l.bits, x < w && y < h ? TiffFixture.stored(l, z, s, y * w + x) : 0);
							}
			}
		}
		b.position((int) ifds);
		for (int z = 0; z < l.slices; z++) {
			long[] offsets = new long[pieces], counts = new long[pieces];
			for (int k = 0; k < pieces; k++) {
				offsets[k] = data + (z * (long) pieces + k) * pieceBytes;
				counts[k] = pieceBytes;
			}
			long[] bits = new long[l.samples];
			Arrays.fill(bits, l.bits);
			// {tag, type (3 SHORT, 4 LONG), values...}, in the order of their tags
			List<long[]> entries = new ArrayList<long[]>();
			entries.add(new long[] { 256, 4, w });
			entries.add(new long[] { 257, 4, h });
			entries.add(entry(258, 3, bits));
			entries.add(new long[] { 259, 3, 1 });
			entries.add(new long[] { 262, 3, l.samples >= 3 ? 2 : 1 });
			if (planar) entries.add(entry(273, 4, offsets));
			entries.add(new long[] { 277, 3, l.samples });
			if (planar) {
				entries.add(new long[] { 278, 4, h });
				entries.add(entry(279, 4, counts));
				entries.add(new long[] { 284, 3, 2 });
			}
			else {
				entries.add(new long[] { 284, 3, 1 });
				entries.add(new long[] { 322, 3, tile });
				entries.add(new long[] { 323, 3, tile });
				entries.add(entry(324, 4, offsets));
				entries.add(entry(325, 4, counts));
			}
			entries.add(new long[] { 339, 3, l.format });
			final int ifd = b.position();
			int extra = ifd + 2 + 12 * entries.size() + 4;
			b.putShort((short) entries.size());
			for (long[] e : entries) {
				final int count = e.length - 2, size = e[1] == 3 ? 2 : 4;
				b.putShort((short) e[0]).putShort((short) e[1]).putInt(count);
				final int value = b.position();
				if (count * size > 4) {
					b.putInt(extra);
					b.position(extra);
				}
				for (int i = 2; i < e.length; i++) {
					if (size == 2) b.putShort((short) e[i]);
					else b.putInt((int) e[i]);
				}
				if (count * size > 4) {
					extra = b.position();
					b.position(value + 4);
				}
				else b.position(value + 4);
			}
			final boolean last = z + 1 == l.slices;
			b.putInt(last ? 0 : extra + (extra & 1));
			b.position(extra + (extra & 1));
		}
		Files.write(f.toPath(), Arrays.copyOf(b.array(), b.position()));
		return f;
	}

	private static long[] entry(int tag, int type, long[] values) {
		long[] e = new long[values.length + 2];
		e[0] = tag;
		e[1] = type;
		System.arraycopy(values, 0, e, 2, values.length);
		return e;
	}

	private static void put(ByteBuffer b, int bits, long v) {
		if (bits == 8) b.put((byte) v);
		else if (bits == 16) b.putShort((short) v);
		else if (bits == 32) b.putInt((int) v);
		else b.putLong(v);
	}
}
