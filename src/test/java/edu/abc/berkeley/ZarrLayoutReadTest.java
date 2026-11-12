package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.ImagePlus;

/**
 * Arrays whose planes the read order interleaves, like the channels of the
 * (t, z, y, x, c) arrays Zarr v3 data usually has, are read one channel at a
 * time when no chunk spans two channels or the chunks store the channels
 * apart (channel-planar), and big reads are cut along several axes. With the
 * slab size set tiny, small arrays take those paths: every layout, storage
 * order, Zarr version, sharding, window and data type must give exactly the
 * stored values, and arrays whose chunks interleave the channels must keep
 * the read that gathers them.
 */
public class ZarrLayoutReadTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final ParallelReadNative reader = new ParallelReadNative();

	@BeforeClass
	public static void nativesRequired() {
		Natives.required();
	}

	@After
	public void defaultSlabs() {
		ParallelReadNative.setZarrSlabBytes(1L << 30);
	}

	// Sizes and chunks of the test arrays along x, y, z, c and t (shards hold
	// two chunks along every axis): no chunk or shard shape divides its axis,
	// and few chunks along x and y keep the files few
	private static final long[] SIZE = { 12, 10, 7, 3, 3 }, CHUNK = { 7, 6, 3, 1, 1 };
	private static final int X = 0, Y = 1, Z = 2, C = 3, T = 4;

	/** An array with the axes of an order such as "tzyxc", written by the fixtures. */
	private static final class Layout {
		final String what;
		final File dir;
		final long[] shape;
		final int[] axes;

		Layout(String what, File dir, long[] shape, int[] axes) {
			this.what = what;
			this.dir = dir;
			this.shape = shape;
			this.axes = axes;
		}

		long[] start() {
			return new long[shape.length];
		}

		long[] end() {
			return shape.clone();
		}
	}

	/**
	 * The array with the axes of order and the sizes above, in storage order
	 * C or F, Zarr v2 or v3 (sharded or not), with chunks of cChunk along c.
	 */
	private Layout write(String order, int version, char storage, boolean sharded, long cChunk) throws IOException {
		int n = order.length();
		long[] shape = new long[n], chunks = new long[n], shards = new long[n];
		String[] names = new String[n];
		for (int d = 0; d < n; d++) {
			int role = "xyzct".indexOf(order.charAt(d));
			shape[d] = SIZE[role];
			chunks[d] = role == C ? cChunk : CHUNK[role];
			shards[d] = 2 * chunks[d];
			names[d] = order.substring(d, d + 1);
		}
		String what = order + ", Zarr v" + version + (sharded ? " in shards" : "") + ", " + storage + " order"
			+ (order.indexOf('c') >= 0 ? ", chunks of " + cChunk + " along c" : "");
		File dir = new File(tmp.getRoot(), what.replaceAll("[^A-Za-z0-9]+", "_"));
		if (version == 2) ZarrFixture.writeV2(dir, shape, chunks, storage, null);
		else if (sharded) ZarrFixture.writeV3Sharded(dir, shape, shards, chunks, storage, names);
		else ZarrFixture.writeV3(dir, shape, chunks, storage, names);
		return new Layout(what, dir, shape, ZarrAxisOrder.roles(order));
	}

	/** Every array the fixtures write with the axes of order. */
	private List<Layout> every(String order) throws IOException {
		List<Layout> all = new ArrayList<Layout>();
		long[] cChunks = order.indexOf('c') >= 0 ? new long[] { 1, 2 } : new long[] { 1 };
		for (long cChunk : cChunks)
			for (char storage : new char[] { 'C', 'F' }) {
				all.add(write(order, 2, storage, false, cChunk));
				all.add(write(order, 3, storage, false, cChunk));
				all.add(write(order, 3, storage, true, cChunk));
			}
		return all;
	}

	private String describe(Layout l, long[] start, long[] end) {
		String how = ParallelReadNative.describeZarrRead(l.dir.getPath(), start, end, l.axes);
		assertNotNull(how);
		return how;
	}

	/**
	 * Reads the window [start, end) at once and in pieces of every size down to
	 * single chunks; each must give the stored values. Returns how the read in
	 * the smallest pieces was cut.
	 */
	private String assertReads(Layout l, long[] start, long[] end) {
		short[][] expected = ZarrFixture.planes(l.shape, l.axes, start, end);
		long bytes = 2L * expected.length * expected[0].length;
		String how = null;
		for (long slab : new long[] { 1L << 40, bytes / 3, bytes / 20, 1 }) {
			ParallelReadNative.setZarrSlabBytes(slab);
			how = describe(l, start, end);
			if (slab == 1L << 40) assertTrue(l.what + ": a small window is read at once, not " + how, how.endsWith(" 1 read"));
			short[][] got = reader.parallelReadZarrUINT16(l.dir.getPath(), start, end, l.axes);
			assertEquals(l.what + ", " + how, expected.length, got.length);
			for (int p = 0; p < expected.length; p++)
				assertArrayEquals(l.what + ", window " + ZarrFixture.list(start) + " to " + ZarrFixture.list(end) + ", read as "
					+ how + ": plane " + p, expected[p], got[p]);
		}
		return how;
	}

	/**
	 * The windows of l that are read: all of it; a box starting inside chunks
	 * along x, y and z, with every channel and two time points; and one channel
	 * at one time point, as the virtual stack reads them.
	 */
	private static List<long[][]> windows(Layout l) {
		List<long[][]> windows = new ArrayList<long[][]>();
		windows.add(new long[][] { l.start(), l.end() });
		long[] start = l.start(), end = l.end();
		long[][] box = { { 3, 11 }, { 1, 9 }, { 2, 6 }, { 0, 3 }, { 1, 3 } };
		for (int role = 0; role < 5; role++)
			if (l.axes[role] >= 0) {
				start[l.axes[role]] = box[role][0];
				end[l.axes[role]] = Math.min(box[role][1], l.shape[l.axes[role]]);
			}
		windows.add(new long[][] { start, end });
		if (l.axes[C] >= 0 || l.axes[T] >= 0) {
			start = l.start();
			end = l.end();
			if (l.axes[C] >= 0) {
				start[l.axes[C]] = Math.min(1, l.shape[l.axes[C]] - 1);
				end[l.axes[C]] = start[l.axes[C]] + 1;
			}
			if (l.axes[T] >= 0) {
				start[l.axes[T]] = Math.min(2, l.shape[l.axes[T]] - 1);
				end[l.axes[T]] = start[l.axes[T]] + 1;
			}
			windows.add(new long[][] { start, end });
		}
		return windows;
	}

	/**
	 * Reads every window of every array with the axes of order, and checks
	 * that the whole array is read one channel at a time exactly for the
	 * layouts in split (x stored after y, c after both, chunks of one channel)
	 * and cut along several axes when it has them.
	 */
	private void assertLayout(String order, String... split) throws IOException {
		List<Layout> layouts = every(order);
		for (Layout l : layouts) {
			for (long[][] w : windows(l)) assertReads(l, w[0], w[1]);
			String how = assertReads(l, l.start(), l.end());
			boolean expected = false;
			for (String s : split) expected |= l.what.startsWith(s);
			assertEquals(l.what + " is read as " + how, expected, how.contains("c by index"));
			assertEquals(l.what + " is read as " + how, expected, how.contains("by index"));
			int cut = how.split(" in ", -1).length - 1;
			assertTrue(l.what + ": several axes cut, not " + how, cut >= Math.min(2, order.length() - 2 - (expected ? 1 : 0)));
		}
		// One channel of one time point (the virtual stack's read) has nothing to interleave
		for (Layout l : layouts)
			if (l.axes[C] >= 0) {
				long[][] w = windows(l).get(2);
				ParallelReadNative.setZarrSlabBytes(1);
				assertTrue(l.what, !describe(l, w[0], w[1]).contains("by index"));
			}
	}

	// --- Layouts ---

	@Test
	public void channelsLastFiveD() throws Exception {
		assertLayout("tzyxc", "tzyxc, Zarr v2, C order, chunks of 1", "tzyxc, Zarr v3, C order, chunks of 1",
			"tzyxc, Zarr v3 in shards, C order, chunks of 1");
	}

	/** Reads of one channel each must hold a 64th of a slab: smaller windows are read at once. */
	@Test
	public void smallWindowsAreReadAtOnce() throws Exception {
		Layout l = write("tzyxc", 3, 'C', false, 1);
		long channel = 2L * SIZE[X] * SIZE[Y] * SIZE[Z] * SIZE[T];
		ParallelReadNative.setZarrSlabBytes(64 * channel);
		assertEquals("C order, 3 reads: c by index", describe(l, l.start(), l.end()));
		ParallelReadNative.setZarrSlabBytes(64 * channel + 64);
		assertEquals("C order, 1 read", describe(l, l.start(), l.end()));
	}

	@Test
	public void channelsLastFourD() throws Exception {
		assertLayout("zyxc", "zyxc, Zarr v2, C order, chunks of 1", "zyxc, Zarr v3, C order, chunks of 1",
			"zyxc, Zarr v3 in shards, C order, chunks of 1");
	}

	@Test
	public void numpyFiveD() throws Exception {
		assertLayout("tczyx");
	}

	@Test
	public void channelsFirstFourD() throws Exception {
		assertLayout("czyx");
	}

	@Test
	public void matlabFiveD() throws Exception {
		assertLayout("yxzct");
	}

	@Test
	public void matlabThreeD() throws Exception {
		assertLayout("yxz");
	}

	@Test
	public void numpyThreeD() throws Exception {
		assertLayout("zyx");
	}

	/** The mirror image in F order: channels stored fastest, then x, then y. */
	@Test
	public void channelsFirstInFOrderAreReadOneAtATime() throws Exception {
		for (Layout l : every("cxyzt")) {
			for (long[][] w : windows(l)) assertReads(l, w[0], w[1]);
			String how = assertReads(l, l.start(), l.end());
			boolean split = l.what.contains("F order, chunks of 1");
			assertEquals(l.what + " is read as " + how, split, how.contains("c by index"));
			if (split) assertTrue(l.what + " is read as " + how, how.startsWith("F order"));
		}
	}

	/** A Zarr v3 transpose codec may store the axes in any order. */
	@Test
	public void transposedChunksOfOneChannel() throws Exception {
		long[] shape = { 3, 7, 10, 12, 3 }, chunks = { 1, 3, 4, 5, 1 };
		// Stored (z, t, y, c, x): c between y and x, read one channel at a time
		Layout between = new Layout("tzyxc stored as zt y c x", ZarrFixture.writeV3(new File(tmp.getRoot(), "ztycx"), shape, chunks,
			new int[] { 1, 0, 2, 4, 3 }, null), shape, ZarrAxisOrder.roles("tzyxc"));
		for (long[][] w : windows(between)) assertReads(between, w[0], w[1]);
		String how = assertReads(between, between.start(), between.end());
		assertTrue(how, how.startsWith("C order") && how.contains("c by index"));
		// Stored (t, z, c, x, y): x before y, so the planes would be transposed: gathered as before
		Layout transposed = new Layout("tzyxc stored as t z c x y", ZarrFixture.writeV3(new File(tmp.getRoot(), "tzcxy"), shape, chunks,
			new int[] { 0, 1, 4, 3, 2 }, null), shape, ZarrAxisOrder.roles("tzyxc"));
		for (long[][] w : windows(transposed)) assertReads(transposed, w[0], w[1]);
		how = assertReads(transposed, transposed.start(), transposed.end());
		assertTrue(how, !how.contains("by index"));
	}

	/**
	 * The lab's sharded (t, z, y, x, c) arrays, scaled down: shards of [16,
	 * 128, 128, 128, 2] holding inner chunks of [1, 32, 32, 32, 2], the shard
	 * index checked by a CRC32C; and the candidates for its next format,
	 * channel-planar inner chunks (a transpose to t, c, z, y, x) and (t, c, z,
	 * y, x) arrays with shards of [16, 2, 128, 128, 128] and inner chunks of
	 * [1, 2, 64, 64, 64]. Their chunks hold both channels: the channel-planar
	 * ones are read one channel at a time (each read decodes its channel's
	 * part of every chunk), the others keep the read that gathers the
	 * channels (or need none); all are cut along several axes.
	 */
	@Test
	public void labShardsOfBothChannels() throws Exception {
		long[] tzyxc = { 3, 7, 10, 12, 2 }, tczyx = { 3, 2, 7, 10, 12 };
		Layout today = new Layout("tzyxc in shards of both channels", ZarrFixture.writeV3Sharded(new File(tmp.getRoot(), "today"),
			tzyxc, new long[] { 2, 6, 8, 10, 2 }, new long[] { 1, 3, 4, 5, 2 }, ZarrFixture.stored(5, 'C'), true, null),
			tzyxc, ZarrAxisOrder.roles("tzyxc"));
		Layout planar = new Layout("tzyxc in shards of channel-planar chunks", ZarrFixture.writeV3Sharded(new File(tmp.getRoot(), "planar"),
			tzyxc, new long[] { 2, 6, 8, 10, 2 }, new long[] { 1, 3, 4, 5, 2 }, new int[] { 0, 4, 1, 2, 3 }, true, null),
			tzyxc, ZarrAxisOrder.roles("tzyxc"));
		Layout numpy = new Layout("tczyx in shards of both channels", ZarrFixture.writeV3Sharded(new File(tmp.getRoot(), "tczyx"),
			tczyx, new long[] { 2, 2, 6, 8, 10 }, new long[] { 1, 2, 3, 4, 5 }, ZarrFixture.stored(5, 'C'), true, null),
			tczyx, ZarrAxisOrder.roles("tczyx"));
		for (Layout l : new Layout[] { today, planar, numpy }) {
			for (long[][] w : windows(l)) assertReads(l, w[0], w[1]);
			String how = assertReads(l, l.start(), l.end());
			assertTrue(l.what + " is read as " + how, how.startsWith("C order"));
			assertTrue(l.what + " is read as " + how, how.endsWith(l == planar ? "t in 3, z in 3, c by index" : "t in 3, z in 3"));
		}
		// How the planar chunks would be read compressed (only planned: the
		// fixtures write no compressed chunks): by blosc, whose reads decode a
		// part of a chunk, one channel at a time; by zstd, which decodes whole
		// chunks only, gathered
		String bytes = "{\"name\": \"bytes\", \"configuration\": {\"endian\": \"little\"}}], \"index_codecs\"";
		String blosc = "{\"name\": \"blosc\", \"configuration\": {\"cname\": \"zstd\", \"clevel\": 1, \"shuffle\": \"shuffle\", "
			+ "\"typesize\": 2, \"blocksize\": 0}}";
		String zstd = "{\"name\": \"zstd\", \"configuration\": {\"level\": 1, \"checksum\": false}}";
		ParallelReadNative.setZarrSlabBytes(1);
		edit(planar.dir, bytes, bytes.replace("}], ", "}, " + blosc + "], "));
		assertTrue(describe(planar, planar.start(), planar.end()), describe(planar, planar.start(), planar.end()).contains("c by index"));
		edit(planar.dir, blosc, zstd);
		assertTrue(describe(planar, planar.start(), planar.end()), !describe(planar, planar.start(), planar.end()).contains("by index"));
	}

	/**
	 * The training data's layout with its own chunk shapes: (t, z, y, x, c)
	 * uint16 with two channels, inner chunks of [1, 32, 32, 32, 2] stored (t,
	 * c, z, y, x) by a transpose codec, in shards of [16, 128, 128, 128, 2]
	 * (bigger than this array), the index checked by a CRC32C, fill value 0;
	 * uncompressed here (the fixtures have no blosc).
	 */
	@Test
	public void trainingDataLayout() throws Exception {
		long[] shape = { 2, 40, 36, 44, 2 };
		Layout l = new Layout("training data layout", ZarrFixture.writeV3Sharded(new File(tmp.getRoot(), "training"), shape,
			new long[] { 16, 128, 128, 128, 2 }, new long[] { 1, 32, 32, 32, 2 }, new int[] { 0, 4, 1, 2, 3 }, true, null),
			shape, ZarrAxisOrder.roles("tzyxc"));
		for (long[][] w : windows(l)) assertReads(l, w[0], w[1]);
		assertEquals("C order, 8 reads: t in 2, z in 2, c by index", assertReads(l, l.start(), l.end()));
		// As a virtual stack reads it: a block of planes of one channel and time point
		long[] start = { 1, 8, 0, 0, 1 }, end = { 2, 24, 36, 44, 2 };
		assertReads(l, start, end);
	}

	// --- Data types and missing chunks ---

	private static void edit(File dir, String from, String to) throws IOException {
		Path meta = new File(dir, new File(dir, "zarr.json").exists() ? "zarr.json" : ".zarray").toPath();
		String json = new String(Files.readAllBytes(meta), StandardCharsets.UTF_8);
		assertTrue(meta + " has " + from, json.contains(from));
		Files.write(meta, json.replace(from, to).getBytes(StandardCharsets.UTF_8));
	}

	/** IEEE 754 half precision to float. */
	private static float half(short h) {
		int bits = h & 0xffff, sign = (bits >>> 15) << 31, exponent = (bits >>> 10) & 0x1f, mantissa = bits & 0x3ff;
		if (exponent == 0x1f) return Float.intBitsToFloat(sign | 0x7f800000 | (mantissa << 13));
		if (exponent != 0) return Float.intBitsToFloat(sign | ((exponent + 112) << 23) | (mantissa << 13));
		float v = mantissa * 0x1p-24f;
		return sign != 0 ? -v : v;
	}

	/** Signed and half-float arrays go through the conversions, read one channel at a time too. */
	@Test
	public void signedAndHalfFloatChannelsLast() throws Exception {
		for (Layout l : new Layout[] { write("tzyxc", 2, 'C', false, 1), write("tzyxc", 3, 'C', true, 1) }) {
			boolean v3 = l.what.contains("v3");
			for (long[][] w : windows(l)) {
				short[][] stored = ZarrFixture.planes(l.shape, l.axes, w[0], w[1]);
				edit(l.dir, v3 ? "\"uint16\"" : "\"<u2\"", v3 ? "\"int16\"" : "\"<i2\"");
				for (long slab : new long[] { 1L << 40, 1 }) {
					ParallelReadNative.setZarrSlabBytes(slab);
					String how = describe(l, w[0], w[1]);
					short[][] got = reader.parallelReadZarrINT16(l.dir.getPath(), w[0], w[1], l.axes);
					for (int p = 0; p < stored.length; p++)
						for (int i = 0; i < stored[p].length; i++)
							assertEquals(l.what + " as int16, read as " + how + ": plane " + p + " element " + i,
								(short) (stored[p][i] ^ 0x8000), got[p][i]);
				}
				edit(l.dir, v3 ? "\"int16\"" : "\"<i2\"", v3 ? "\"float16\"" : "\"<f2\"");
				for (long slab : new long[] { 1L << 40, 1 }) {
					ParallelReadNative.setZarrSlabBytes(slab);
					String how = describe(l, w[0], w[1]);
					float[][] got = reader.parallelReadZarrFLOAT16(l.dir.getPath(), w[0], w[1], l.axes, false);
					for (int p = 0; p < stored.length; p++) {
						float[] want = new float[stored[p].length];
						for (int i = 0; i < want.length; i++) want[i] = half(stored[p][i]);
						assertArrayEquals(l.what + " as float16, read as " + how + ": plane " + p, want, got[p], 0f);
					}
				}
				edit(l.dir, v3 ? "\"float16\"" : "\"<f2\"", v3 ? "\"uint16\"" : "\"<u2\"");
			}
			ParallelReadNative.setZarrSlabBytes(1);
			assertTrue(l.what, describe(l, l.start(), l.end()).contains("c by index"));
		}
	}

	/**
	 * The buffer reused for every read still holds the last one: chunks
	 * missing from the array, inner chunks missing from their shards and
	 * missing shards must get the fill value in every read.
	 */
	@Test
	public void missingChunksGetTheFillValueReadByChannel() throws Exception {
		long[] shape = { 3, 7, 10, 12, 3 }, chunks = { 1, 3, 4, 5, 1 }, shards = { 2, 6, 8, 10, 3 };
		int[] axes = ZarrAxisOrder.roles("tzyxc");
		File v2 = ZarrFixture.writeV2(new File(tmp.getRoot(), "v2"), shape, chunks, 'C', null);
		// (t, z, y, x, c) chunks: of channel 0 in the first read, of channels 1 and 2 in later ones
		for (String chunk : new String[] { "0/0/0/0/0", "1/2/1/2/1", "2/1/2/0/2" })
			assertTrue(chunk, new File(v2, chunk).delete());
		edit(v2, "\"fill_value\": 0", "\"fill_value\": 7");
		// Inner chunks left out of two shards, and the shard holding t 2, z 6, y 8-9, x 10-11 removed
		File v3 = ZarrFixture.writeV3Sharded(new File(tmp.getRoot(), "v3"), shape, shards, chunks, 'C', null,
			new long[] { 0, 0, 0, 0, 0 }, new long[] { 1, 2, 1, 2, 1 });
		assertTrue(new File(v3, "c/1/1/1/1/0").delete());
		edit(v3, "\"fill_value\": 0", "\"fill_value\": 7");
		long[][] missingV2 = { { 0, 0, 0, 0, 0 }, { 1, 2, 1, 2, 1 }, { 2, 1, 2, 0, 2 } };
		long[][] missingV3 = { { 0, 0, 0, 0, 0 }, { 1, 2, 1, 2, 1 } };

		for (File dir : new File[] { v2, v3 }) {
			Layout l = new Layout(dir.getName(), dir, shape, axes);
			short[][] want = ZarrFixture.planes(shape, axes, l.start(), l.end());
			int filled = 0;
			long[] index = new long[5];
			for (int t = 0; t < 3; t++)
				for (int z = 0; z < 7; z++)
					for (int c = 0; c < 3; c++)
						for (int y = 0; y < 10; y++)
							for (int x = 0; x < 12; x++) {
								index[0] = t;
								index[1] = z;
								index[2] = y;
								index[3] = x;
								index[4] = c;
								boolean missing = dir == v3 && t == 2 && z == 6 && y >= 8 && x >= 10;
								for (long[] m : dir == v2 ? missingV2 : missingV3) {
									boolean in = true;
									for (int d = 0; d < 5; d++) in &= index[d] / chunks[d] == m[d];
									missing |= in;
								}
								if (missing) {
									want[c + 3 * (z + 7 * t)][y * 12 + x] = 7;
									filled++;
								}
							}
			assertTrue(dir.getName(), filled > 0);
			for (long slab : new long[] { 1L << 40, 1, 5000 }) {
				ParallelReadNative.setZarrSlabBytes(slab);
				String how = describe(l, l.start(), l.end());
				short[][] got = reader.parallelReadZarrUINT16(dir.getPath(), l.start(), l.end(), axes);
				for (int p = 0; p < want.length; p++)
					assertArrayEquals(dir.getName() + ", read as " + how + ": plane " + p, want[p], got[p]);
				if (slab == 1) assertTrue(how, how.contains("c by index"));
			}
		}
	}

	// --- The plugin's open ---

	/** A named (t, z, y, x, c) Zarr v3 array in shards opens through PRZ as a hyperstack with the stored values. */
	@Test
	public void channelsLastHyperstackThroughPRZ() throws Exception {
		Layout l = write("tzyxc", 3, 'C', true, 1);
		ParallelReadNative.setZarrSlabBytes(1);
		assertTrue(describe(l, l.start(), l.end()).contains("c by index"));
		ImagePlus imp = new PRZ(l.dir.getPath(), false).getImp();
		assertNotNull("no image", imp);
		assertEquals("width", SIZE[X], imp.getWidth());
		assertEquals("height", SIZE[Y], imp.getHeight());
		assertEquals("channels", SIZE[C], imp.getNChannels());
		assertEquals("slices", SIZE[Z], imp.getNSlices());
		assertEquals("frames", SIZE[T], imp.getNFrames());
		for (int t = 0; t < SIZE[T]; t++)
			for (int z = 0; z < SIZE[Z]; z++)
				for (int c = 0; c < SIZE[C]; c++)
					assertArrayEquals("c" + c + " z" + z + " t" + t, ZarrFixture.plane(l.shape, l.axes, c, z, t),
						(short[]) imp.getStack().getPixels(imp.getStackIndex(c + 1, z + 1, t + 1)));
	}
}
