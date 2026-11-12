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

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.ImagePlus;

/**
 * Planes far bigger than a slab (one chunk row of them holding several slabs)
 * are read in bricks: pieces cut along y and x too, at chunk boundaries, each
 * filling its rectangle of the planes. With the slab size set tiny, small
 * arrays take that path: transposed (MATLAB's y, x, z) and compact (NumPy's
 * z, y, x) layouts, Zarr v2 and v3, sharded or not, windows starting inside
 * chunks, conversions on the way and chunks missing from the array must all
 * give exactly the stored values, and planes only a few chunks wide must not
 * be cut.
 */
public class ZarrBrickTest {

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

	private static String describe(File dir, long[] start, long[] end, int[] axes) {
		String how = ParallelReadNative.describeZarrRead(dir.getPath(), start, end, axes);
		assertNotNull(how);
		return how;
	}

	/**
	 * Reads [start, end) at once and with every slab size of slabs; each read
	 * must give the stored values. Returns how the read with the last slab size
	 * was cut.
	 */
	private String assertReads(String what, File dir, long[] shape, int[] axes, long[] start, long[] end, long... slabs) {
		short[][] expected = ZarrFixture.planes(shape, axes, start, end);
		String how = null;
		for (long slab : slabs) {
			ParallelReadNative.setZarrSlabBytes(slab);
			how = describe(dir, start, end, axes);
			short[][] got = reader.parallelReadZarrUINT16(dir.getPath(), start, end, axes);
			assertEquals(what + ", " + how, expected.length, got.length);
			for (int p = 0; p < expected.length; p++)
				assertArrayEquals(what + ", window " + ZarrFixture.list(start) + " to " + ZarrFixture.list(end) + ", read as " + how
					+ ": plane " + p, expected[p], got[p]);
		}
		return how;
	}

	private static long[] zeros(int n) {
		return new long[n];
	}

	// (y, x, z) of 45 x 70 x 20 in chunks of 8: 6 chunks along y, 9 along x
	private static final long[] YXZ = { 45, 70, 20 }, YXZ_CHUNKS = { 8, 8, 8 };

	/**
	 * How the transposed array is cut: a z chunk row of whole planes holds
	 * 50400 bytes, one x chunk of it 5760 and one y chunk of that 1024. Bricks
	 * start once the row holds 4 slabs, and hold at least a slab each.
	 */
	@Test
	public void bricksAreCutFromTheSlowerPlaneAxis() throws Exception {
		File dir = ZarrFixture.writeV2(new File(tmp.getRoot(), "yxz"), YXZ, YXZ_CHUNKS, 'F', null);
		int[] axes = ZarrAxisOrder.roles("yxz");
		String[] expected = {
			// a row of 3.9 slabs: slabs only
			"F order, 3 reads: z in 3",
			// of 4.2 slabs: bricks of 3 x chunks (17280 bytes) along x, the slower plane axis
			"F order, 9 reads: z in 3, x in 3",
			// bricks of single x chunks, too small to cut along y
			"F order, 27 reads: z in 3, x in 9",
			// single chunks
			"F order, 162 reads: z in 3, x in 9, y in 6" };
		long[] slabs = { 13000, 12000, 3000, 1000 };
		for (int k = 0; k < slabs.length; k++)
			assertEquals("slabs of " + slabs[k], expected[k],
				assertReads("yxz", dir, YXZ, axes, zeros(3), YXZ, 1L << 40, slabs[k]));
	}

	/** Every brick size, in every window, of the MATLAB layout (y fastest in F order: transposed planes). */
	@Test
	public void transposedPlanesInBricks() throws Exception {
		int[] axes = ZarrAxisOrder.roles("yxz");
		File v2 = ZarrFixture.writeV2(new File(tmp.getRoot(), "v2"), YXZ, YXZ_CHUNKS, 'F', null);
		File v3 = ZarrFixture.writeV3(new File(tmp.getRoot(), "v3"), YXZ, YXZ_CHUNKS, 'F', null);
		File shards = ZarrFixture.writeV3Sharded(new File(tmp.getRoot(), "shards"), YXZ, new long[] { 16, 24, 16 }, YXZ_CHUNKS,
			'F', null);
		for (File dir : new File[] { v2, v3, shards }) {
			assertTrue(assertReads(dir.getName(), dir, YXZ, axes, zeros(3), YXZ, 1L << 40, 12000, 3000, 1000, 1)
				.endsWith("x in 9, y in 6"));
			// Starting and ending inside chunks along every axis
			long[] start = { 3, 5, 2 }, end = { 41, 66, 19 };
			assertTrue(assertReads(dir.getName(), dir, YXZ, axes, start, end, 1L << 40, 12000, 3000, 1)
				.endsWith("x in 9, y in 6"));
			// A block of planes inside one z chunk, as a virtual stack reads them
			start = new long[] { 0, 0, 9 };
			end = new long[] { 45, 70, 13 };
			assertEquals("F order, 54 reads: x in 9, y in 6", assertReads(dir.getName(), dir, YXZ, axes, start, end, 1L << 40, 1));
			// One plane
			start = new long[] { 0, 0, 17 };
			end = new long[] { 45, 70, 18 };
			assertReads(dir.getName(), dir, YXZ, axes, start, end, 1L << 40, 1000, 1);
		}
	}

	/** NumPy's (z, y, x) in C order: rows of the planes are compact, y is cut first. */
	@Test
	public void compactPlanesInBricks() throws Exception {
		long[] shape = { 20, 45, 70 }, chunks = { 8, 8, 8 };
		int[] axes = ZarrAxisOrder.roles("zyx");
		File v2 = ZarrFixture.writeV2(new File(tmp.getRoot(), "v2"), shape, chunks, 'C', null);
		File shards = ZarrFixture.writeV3Sharded(new File(tmp.getRoot(), "shards"), shape, new long[] { 16, 16, 24 }, chunks,
			'C', null);
		for (File dir : new File[] { v2, shards }) {
			// Bricks of whole rows (one block of each plane), then of single chunks
			assertEquals("C order, 18 reads: z in 3, y in 6", assertReads(dir.getName(), dir, shape, axes, zeros(3), shape, 1L << 40, 5000));
			assertEquals("C order, 162 reads: z in 3, y in 6, x in 9",
				assertReads(dir.getName(), dir, shape, axes, zeros(3), shape, 1L << 40, 1));
			long[] start = { 2, 3, 5 }, end = { 19, 41, 66 };
			assertReads(dir.getName(), dir, shape, axes, start, end, 1L << 40, 12000, 3000, 1);
		}
		// The same array stored in F order (x stored slowest): read in C order, gathered
		File f = ZarrFixture.writeV2(new File(tmp.getRoot(), "f"), shape, chunks, 'F', null);
		assertTrue(assertReads("F", f, shape, axes, zeros(3), shape, 1L << 40, 1).contains("y in 6, x in 9"));
	}

	/** Planes of 2 or 3 chunks along an axis are not cut along it; of 4 chunks they are. */
	@Test
	public void planesOfFewChunksAreLeftWhole() throws Exception {
		int[] axes = ZarrAxisOrder.roles("yxz");
		long[] three = { 24, 70, 20 };
		File dir = ZarrFixture.writeV2(new File(tmp.getRoot(), "three"), three, YXZ_CHUNKS, 'F', null);
		assertEquals("F order, 27 reads: z in 3, x in 9", assertReads("3 chunks along y", dir, three, axes, zeros(3), three, 1L << 40, 1));

		long[] four = { 26, 70, 20 };
		dir = ZarrFixture.writeV2(new File(tmp.getRoot(), "four"), four, YXZ_CHUNKS, 'F', null);
		assertEquals("F order, 108 reads: z in 3, x in 9, y in 4",
			assertReads("4 chunks along y", dir, four, axes, zeros(3), four, 1L << 40, 1));
		// Windows of 18 rows touching 4 chunks, of 11 rows touching 3 and of 16 rows touching 2
		long[] start = { 7, 0, 0 }, end = { 25, 70, 20 };
		assertEquals("F order, 108 reads: z in 3, x in 9, y in 4", assertReads("window of 4 chunks", dir, four, axes, start, end, 1));
		start[0] = 6;
		end[0] = 17;
		assertEquals("F order, 27 reads: z in 3, x in 9", assertReads("window of 3 chunks", dir, four, axes, start, end, 1));
		start[0] = 8;
		end[0] = 24;
		assertEquals("F order, 27 reads: z in 3, x in 9", assertReads("window of 2 chunks", dir, four, axes, start, end, 1));
	}

	/** Channels last, read one channel at a time, in bricks; and five axes in F order. */
	@Test
	public void hyperstacksInBricks() throws Exception {
		long[] tzyxc = { 2, 6, 40, 36, 2 };
		File v3 = ZarrFixture.writeV3(new File(tmp.getRoot(), "tzyxc"), tzyxc, new long[] { 1, 3, 8, 8, 1 }, 'C', null);
		int[] axes = ZarrAxisOrder.roles("tzyxc");
		assertEquals("C order, 200 reads: t in 2, z in 2, c by index, y in 5, x in 5",
			assertReads("tzyxc", v3, tzyxc, axes, zeros(5), tzyxc, 1L << 40, 1));
		assertReads("tzyxc", v3, tzyxc, axes, new long[] { 1, 1, 3, 2, 0 }, new long[] { 2, 5, 37, 35, 2 }, 1L << 40, 3000, 1);

		long[] yxzct = { 40, 36, 6, 2, 2 };
		File v2 = ZarrFixture.writeV2(new File(tmp.getRoot(), "yxzct"), yxzct, new long[] { 8, 8, 3, 1, 1 }, 'F', null);
		axes = ZarrAxisOrder.roles("yxzct");
		assertTrue(assertReads("yxzct", v2, yxzct, axes, zeros(5), yxzct, 1L << 40, 3000, 1).endsWith("y in 5"));
	}

	private static void edit(File dir, String from, String to) throws IOException {
		Path meta = new File(dir, ".zarray").toPath();
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

	/** Signed and half-float values are converted into each brick's rectangle, transposed or row by row. */
	@Test
	public void convertedTypesInBricks() throws Exception {
		File yxz = ZarrFixture.writeV2(new File(tmp.getRoot(), "yxz"), YXZ, YXZ_CHUNKS, 'F', null);
		long[] zyx = { 20, 45, 70 };
		File c = ZarrFixture.writeV2(new File(tmp.getRoot(), "zyx"), zyx, YXZ_CHUNKS, 'C', null);
		for (File dir : new File[] { yxz, c }) {
			long[] shape = dir == yxz ? YXZ : zyx;
			int[] axes = ZarrAxisOrder.roles(dir.getName());
			long[] start = dir == yxz ? new long[] { 3, 5, 2 } : new long[] { 2, 3, 5 };
			long[] end = dir == yxz ? new long[] { 41, 66, 19 } : new long[] { 19, 41, 66 };
			short[][] stored = ZarrFixture.planes(shape, axes, start, end);
			edit(dir, "<u2", "<i2");
			for (long slab : new long[] { 1L << 40, 3000, 1 }) {
				ParallelReadNative.setZarrSlabBytes(slab);
				String how = describe(dir, start, end, axes);
				short[][] got = reader.parallelReadZarrINT16(dir.getPath(), start, end, axes);
				for (int p = 0; p < stored.length; p++)
					for (int i = 0; i < stored[p].length; i++)
						assertEquals(dir.getName() + " as int16, read as " + how + ": plane " + p + " element " + i,
							(short) (stored[p][i] ^ 0x8000), got[p][i]);
			}
			edit(dir, "<i2", "<f2");
			for (long slab : new long[] { 1L << 40, 3000, 1 }) {
				ParallelReadNative.setZarrSlabBytes(slab);
				String how = describe(dir, start, end, axes);
				assertTrue(how, slab > 3000 || how.contains(" x in ") || how.contains(" y in "));
				float[][] got = reader.parallelReadZarrFLOAT16(dir.getPath(), start, end, axes, false);
				for (int p = 0; p < stored.length; p++) {
					float[] want = new float[stored[p].length];
					for (int i = 0; i < want.length; i++) want[i] = half(stored[p][i]);
					assertArrayEquals(dir.getName() + " as float16, read as " + how + ": plane " + p, want, got[p], 0f);
				}
			}
		}
	}

	/** The buffer reused for every brick still holds the last one: missing chunks must get the fill value. */
	@Test
	public void missingChunksGetTheFillValueInEveryBrick() throws Exception {
		File dir = ZarrFixture.writeV2(new File(tmp.getRoot(), "missing"), YXZ, YXZ_CHUNKS, 'F', null);
		edit(dir, "\"fill_value\": 0", "\"fill_value\": 7");
		// Chunks (y, x, z) of several bricks, also the first
		long[][] missing = { { 0, 0, 0 }, { 3, 4, 1 }, { 5, 8, 2 }, { 2, 4, 1 } };
		for (long[] m : missing) assertTrue(new File(dir, m[0] + "/" + m[1] + "/" + m[2]).delete());
		int[] axes = ZarrAxisOrder.roles("yxz");
		short[][] want = ZarrFixture.planes(YXZ, axes, zeros(3), YXZ);
		int filled = 0;
		for (int z = 0; z < 20; z++)
			for (int y = 0; y < 45; y++)
				for (int x = 0; x < 70; x++)
					for (long[] m : missing)
						if (y / 8 == m[0] && x / 8 == m[1] && z / 8 == m[2]) {
							want[z][y * 70 + x] = 7;
							filled++;
						}
		assertTrue(filled > 0);
		for (long slab : new long[] { 1L << 40, 3000, 1 }) {
			ParallelReadNative.setZarrSlabBytes(slab);
			String how = describe(dir, zeros(3), YXZ, axes);
			short[][] got = reader.parallelReadZarrUINT16(dir.getPath(), zeros(3), YXZ, axes);
			for (int p = 0; p < want.length; p++)
				assertArrayEquals("read as " + how + ": plane " + p, want[p], got[p]);
		}
	}

	/** The plugin's open of a MATLAB-layout array read in bricks. */
	@Test
	public void bricksThroughPRZ() throws Exception {
		File dir = ZarrFixture.writeV2(new File(tmp.getRoot(), "prz"), YXZ, YXZ_CHUNKS, 'F', null);
		int[] axes = ZarrAxisOrder.roles("yxz");
		ParallelReadNative.setZarrSlabBytes(1);
		assertTrue(describe(dir, zeros(3), YXZ, axes).contains("x in 9, y in 6"));
		ImagePlus imp = new PRZ(dir.getPath(), false).getImp();
		assertNotNull("no image", imp);
		assertEquals("width", 70, imp.getWidth());
		assertEquals("height", 45, imp.getHeight());
		assertEquals("slices", 20, imp.getStackSize());
		for (int z = 0; z < 20; z++)
			assertArrayEquals("z" + z, ZarrFixture.plane(YXZ, axes, 0, z, 0), (short[]) imp.getStack().getPixels(z + 1));
	}
}
