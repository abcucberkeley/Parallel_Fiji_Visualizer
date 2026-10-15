package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Big Zarr reads go in chunk-aligned slabs through one reused buffer. With the
 * slab size set tiny, small arrays take that path: every read must give
 * exactly the planes a read at once gives, and the stored values, whatever
 * the layout, the window, the data type, or chunks missing from the array.
 */
public class ZarrSlabTest {

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

	private static int[] roles(String order) {
		return ZarrAxisOrder.roles(order);
	}

	/** The planes of the window [start, end), czt order, as the fixtures store them. */
	private static short[][] expected(long[] shape, int[] axes, long[] start, long[] end) {
		int[] n = new int[5];
		for (int i = 0; i < 5; i++) n[i] = axes[i] >= 0 ? (int) (end[axes[i]] - start[axes[i]]) : 1;
		short[][] planes = new short[n[2] * n[3] * n[4]][n[0] * n[1]];
		long[] index = new long[shape.length];
		for (int t = 0; t < n[4]; t++)
			for (int z = 0; z < n[2]; z++)
				for (int c = 0; c < n[3]; c++)
					for (int y = 0; y < n[1]; y++)
						for (int x = 0; x < n[0]; x++) {
							int[] at = { x, y, z, c, t };
							for (int d = 0; d < shape.length; d++) index[d] = start[d];
							for (int i = 0; i < 5; i++)
								if (axes[i] >= 0) index[axes[i]] += at[i];
							planes[c + n[3] * (z + n[2] * t)][y * n[0] + x] = ZarrFixture.value(shape, index);
						}
		return planes;
	}

	private short[][] read(File dir, long[] start, long[] end, int[] axes, long slabBytes) {
		ParallelReadNative.setZarrSlabBytes(slabBytes);
		return reader.parallelReadZarrUINT16(dir.getPath(), start, end, axes);
	}

	/** Slabs of every size give the read done at once, and that is what is stored. */
	private void assertSlabsMatch(File dir, long[] shape, int[] axes, long[] start, long[] end) {
		short[][] once = read(dir, start, end, axes, 1L << 40);
		assertArrayEquals("read at once", expected(shape, axes, start, end), once);
		for (long slab : new long[] { 1, 60000, 150000 }) {
			short[][] slabs = read(dir, start, end, axes, slab);
			assertEquals(once.length, slabs.length);
			for (int p = 0; p < once.length; p++)
				assertArrayEquals("slabs of " + slab + " bytes, plane " + p, once[p], slabs[p]);
		}
	}

	private static long[] zeros(int n) {
		return new long[n];
	}

	@Test
	public void matlabLayoutInSlabs() throws Exception {
		long[] shape = { 50, 60, 40 };
		File dir = ZarrFixture.writeV2(new File(tmp.getRoot(), "yxz"), shape, new long[] { 16, 16, 8 }, 'F', null);
		assertSlabsMatch(dir, shape, roles("yxz"), zeros(3), shape);
		// A window starting between chunk boundaries
		assertSlabsMatch(dir, shape, roles("yxz"), new long[] { 5, 7, 3 }, new long[] { 45, 50, 37 });
	}

	@Test
	public void numpyLayoutInSlabs() throws Exception {
		long[] shape = { 40, 50, 60 };
		File dir = ZarrFixture.writeV2(new File(tmp.getRoot(), "zyx"), shape, new long[] { 8, 16, 16 }, 'C', null);
		assertSlabsMatch(dir, shape, roles("zyx"), zeros(3), shape);
		assertSlabsMatch(dir, shape, roles("zyx"), new long[] { 3, 2, 9 }, new long[] { 33, 47, 58 });
	}

	@Test
	public void fiveDInSlabs() throws Exception {
		long[] tczyx = { 3, 2, 10, 20, 24 };
		File v3 = ZarrFixture.writeV3(new File(tmp.getRoot(), "tczyx"), tczyx, new long[] { 1, 1, 4, 8, 8 }, 'C',
			new String[] { "t", "c", "z", "y", "x" });
		assertSlabsMatch(v3, tczyx, roles("tczyx"), zeros(5), tczyx);
		assertSlabsMatch(v3, tczyx, roles("tczyx"), new long[] { 1, 0, 3, 2, 5 }, new long[] { 3, 2, 9, 19, 22 });

		long[] yxzct = { 20, 24, 10, 2, 3 };
		File v2 = ZarrFixture.writeV2(new File(tmp.getRoot(), "yxzct"), yxzct, new long[] { 8, 8, 4, 1, 1 }, 'F', null);
		assertSlabsMatch(v2, yxzct, roles("yxzct"), zeros(5), yxzct);
	}

	/** A reused buffer still holds the last slab: missing chunks must get the fill value. */
	@Test
	public void missingChunksGetTheFillValueInEverySlab() throws Exception {
		long[] shape = { 32, 32, 32 };
		File dir = ZarrFixture.writeV2(new File(tmp.getRoot(), "missing"), shape, new long[] { 16, 16, 8 }, 'F', null);
		Path zarray = Paths.get(dir.getPath(), ".zarray");
		String json = new String(Files.readAllBytes(zarray), StandardCharsets.UTF_8);
		Files.write(zarray, json.replace("\"fill_value\": 0", "\"fill_value\": 7").getBytes(StandardCharsets.UTF_8));
		// Chunks (y, x, z): one in the first slab, two in later ones
		for (String chunk : new String[] { "0/0/0", "1/0/2", "1/1/3" })
			assertTrue(chunk, new File(dir, chunk).delete());
		int[] axes = roles("yxz");
		short[][] once = read(dir, zeros(3), shape, axes, 1L << 40);
		short[][] stored = expected(shape, axes, zeros(3), shape);
		int filled = 0;
		for (int z = 0; z < 32; z++)
			for (int y = 0; y < 32; y++)
				for (int x = 0; x < 32; x++) {
					boolean missing = (y < 16 && x < 16 && z < 8) || (y >= 16 && x < 16 && z >= 16 && z < 24)
						|| (y >= 16 && x >= 16 && z >= 24);
					assertEquals("z " + z + " y " + y + " x " + x, missing ? 7 : stored[z][y * 32 + x], once[z][y * 32 + x]);
					if (missing) filled++;
				}
		assertEquals(3 * 16 * 16 * 8, filled);
		for (long slab : new long[] { 1, 5000 }) {
			short[][] slabs = read(dir, zeros(3), shape, axes, slab);
			for (int p = 0; p < 32; p++)
				assertArrayEquals("slabs of " + slab + " bytes, plane " + p, once[p], slabs[p]);
		}
	}

	/** Conversions on the way into Java's arrays (signed shift, half floats) in slabs. */
	@Test
	public void convertedTypesInSlabs() throws Exception {
		long[] shape = { 30, 20, 24 };
		File dir = ZarrFixture.writeV2(new File(tmp.getRoot(), "typed"), shape, new long[] { 8, 8, 4 }, 'F', null);
		Path zarray = Paths.get(dir.getPath(), ".zarray");
		String json = new String(Files.readAllBytes(zarray), StandardCharsets.UTF_8);
		int[] axes = roles("yxz");
		long[] start = { 2, 1, 3 }, end = { 28, 19, 23 };

		Files.write(zarray, json.replace("<u2", "<i2").getBytes(StandardCharsets.UTF_8));
		ParallelReadNative.setZarrSlabBytes(1L << 40);
		short[][] once = reader.parallelReadZarrINT16(dir.getPath(), start, end, axes);
		ParallelReadNative.setZarrSlabBytes(1);
		short[][] slabs = reader.parallelReadZarrINT16(dir.getPath(), start, end, axes);
		short[][] stored = expected(shape, axes, start, end);
		for (int p = 0; p < once.length; p++) {
			assertArrayEquals("int16 plane " + p, once[p], slabs[p]);
			for (int i = 0; i < once[p].length; i++)
				assertEquals((short) (stored[p][i] ^ 0x8000), once[p][i]);
		}

		Files.write(zarray, json.replace("<u2", "<f2").getBytes(StandardCharsets.UTF_8));
		ParallelReadNative.setZarrSlabBytes(1L << 40);
		float[][] halfOnce = reader.parallelReadZarrFLOAT16(dir.getPath(), start, end, axes, false);
		ParallelReadNative.setZarrSlabBytes(1);
		float[][] halfSlabs = reader.parallelReadZarrFLOAT16(dir.getPath(), start, end, axes, false);
		for (int p = 0; p < halfOnce.length; p++)
			assertArrayEquals("float16 plane " + p, halfOnce[p], halfSlabs[p], 0f);
	}
}
