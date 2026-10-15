package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * How folders are recognized as Zarr (by their metadata files, not their
 * names) and how the axes of an array are found. Needs no natives.
 */
public class ZarrMetadataTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	// --- JSON ---

	@Test
	public void jsonParsesZarrMetadata() {
		Map<String, Object> m = Json.object(Json.parse(
			" {\"shape\": [10, 2.5e1, -3], \"name\": \"a\\\"b\\u00e9\\n\", \"ok\": true, \"no\": false, \"none\": null,"
			+ " \"nested\": {\"list\": [[], {}]}, \"fill\": NaN, \"big\": -Infinity, \"inf\": Infinity}\n"));
		assertArrayEquals(new long[] { 10, 25, -3 }, Json.longs(m.get("shape")));
		assertEquals("a\"bé\n", m.get("name"));
		assertEquals(Boolean.TRUE, m.get("ok"));
		assertEquals(Boolean.FALSE, m.get("no"));
		assertTrue(m.containsKey("none") && m.get("none") == null);
		List<Object> list = Json.list(Json.object(m.get("nested")).get("list"));
		assertEquals(2, list.size());
		assertTrue(Double.isNaN((Double) m.get("fill")));
		assertEquals(Double.NEGATIVE_INFINITY, (Double) m.get("big"), 0);
		assertEquals(Double.POSITIVE_INFINITY, (Double) m.get("inf"), 0);
		assertNull("fractions are not whole numbers", Json.longs(Json.parse("[1.5]")));
	}

	@Test
	public void invalidJsonIsRejected() {
		for (String bad : new String[] { "", "{", "{\"a\" 1}", "[1,]", "[1 2]", "\"open", "{} x", "tru", "{\"a\": 1,}" }) {
			try {
				Json.parse(bad);
				fail("parsed " + bad);
			}
			catch (IllegalArgumentException e) {
				assertTrue(e.getMessage(), e.getMessage().startsWith("Invalid JSON"));
			}
		}
	}

	// --- What is a Zarr ---

	@Test
	public void zarrIsToldByItsMetadataNotItsName() throws Exception {
		File v2 = ZarrFixture.writeV2(new File(tmp.getRoot(), "plainFolderName"), new long[] { 4, 5 }, new long[] { 2, 2 }, 'C', null);
		File v3 = ZarrFixture.writeV3(new File(tmp.getRoot(), "v3array"), new long[] { 4, 5 }, new long[] { 2, 2 }, 'C', null);
		File g2 = ZarrFixture.writeV2Group(new File(tmp.getRoot(), "group2"), null);
		File g3 = ZarrFixture.writeV3Group(new File(tmp.getRoot(), "group3"), null);
		File named = tmp.newFolder("empty.zarr");

		ZarrMetadata m = ZarrMetadata.read(v2);
		assertEquals(2, m.format);
		assertTrue(m.array);
		m = ZarrMetadata.read(v3);
		assertEquals(3, m.format);
		assertTrue(m.array);
		m = ZarrMetadata.read(g2);
		assertEquals(2, m.format);
		assertFalse(m.array);
		m = ZarrMetadata.read(g3);
		assertEquals(3, m.format);
		assertFalse(m.array);

		assertTrue(ZarrMetadata.isZarr(v2) && ZarrMetadata.isZarr(v3) && ZarrMetadata.isZarr(g2) && ZarrMetadata.isZarr(g3));
		assertFalse("a .zarr name alone is not a Zarr", ZarrMetadata.isZarr(named));
		assertNull(ZarrMetadata.read(named));
		assertFalse(ZarrMetadata.isZarr(tmp.newFolder("other")));

		// Arrays are images; plain groups hold images but are not one
		assertTrue(ZarrMetadata.isImage(v2) && ZarrMetadata.isImage(v3));
		assertFalse(ZarrMetadata.isImage(g2) || ZarrMetadata.isImage(g3) || ZarrMetadata.isImage(named));
	}

	@Test
	public void zarrayWinsOverZarrJsonAsInCppZarr() throws Exception {
		File dir = ZarrFixture.writeV2(new File(tmp.getRoot(), "both"), new long[] { 4, 5 }, new long[] { 2, 2 }, 'C', null);
		ZarrFixture.write(new File(dir, "zarr.json"), "{\"zarr_format\": 3, \"node_type\": \"group\"}");
		ZarrMetadata m = ZarrMetadata.read(dir);
		assertEquals(2, m.format);
		assertTrue(m.array);
	}

	@Test
	public void zarrJsonWithoutANodeTypeIsRejected() throws Exception {
		File dir = tmp.newFolder("bad");
		ZarrFixture.write(new File(dir, "zarr.json"), "{\"zarr_format\": 3}");
		try {
			ZarrMetadata.read(dir);
			fail("read a zarr.json without node_type");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("node_type"));
		}
		assertFalse(ZarrMetadata.isImage(dir));
	}

	@Test
	public void omeZarrGroupsAreImages() throws Exception {
		File ome04 = ZarrFixture.writeV2Group(new File(tmp.getRoot(), "ome04"), "{\"multiscales\": [{\"version\": \"0.4\", \"datasets\": [{\"path\": \"0\"}]}]}");
		File ome05 = ZarrFixture.writeV3Group(new File(tmp.getRoot(), "ome05"), "{\"ome\": {\"version\": \"0.5\", \"multiscales\": [{\"datasets\": [{\"path\": \"0\"}]}]}}");
		File b2r = ZarrFixture.writeV2Group(new File(tmp.getRoot(), "b2r"), "{\"bioformats2raw.layout\": 3}");
		assertTrue(ZarrMetadata.isImage(ome04));
		assertTrue(ZarrMetadata.isImage(ome05));
		assertTrue(ZarrMetadata.isImage(b2r));
		assertEquals("0", Json.object(Json.list(ZarrMetadata.read(ome05).multiscales().get("datasets")).get(0)).get("path"));
	}

	@Test
	public void storageOrderAndAxisNamesAreRead() throws Exception {
		File v2f = ZarrFixture.writeV2(new File(tmp.getRoot(), "v2f"), new long[] { 4, 5 }, new long[] { 2, 2 }, 'F',
			"{\"_ARRAY_DIMENSIONS\": [\"y\", \"x\"]}");
		assertEquals('F', ZarrMetadata.read(v2f).order());
		assertArrayEquals(new String[] { "y", "x" }, ZarrMetadata.read(v2f).dimensionNames());
		assertArrayEquals(new long[] { 2, 2 }, ZarrMetadata.read(v2f).chunks());

		File v3f = ZarrFixture.writeV3(new File(tmp.getRoot(), "v3f"), new long[] { 4, 5, 6 }, new long[] { 2, 2, 3 }, 'F',
			new String[] { "z", null, "x" });
		assertEquals('F', ZarrMetadata.read(v3f).order());
		assertArrayEquals(new String[] { "z", null, "x" }, ZarrMetadata.read(v3f).dimensionNames());
		assertArrayEquals(new long[] { 2, 2, 3 }, ZarrMetadata.read(v3f).chunks());
		assertArrayEquals(new long[] { 4, 5, 6 }, ZarrMetadata.read(v3f).shape());

		File v3c = ZarrFixture.writeV3(new File(tmp.getRoot(), "v3c"), new long[] { 4, 5 }, new long[] { 2, 2 }, 'C',
			new String[] { null, null });
		assertEquals('C', ZarrMetadata.read(v3c).order());
		assertNull("names that are all null are no names", ZarrMetadata.read(v3c).dimensionNames());

		// A sharded array's transpose is inside its sharding codec
		File sharded = tmp.newFolder("sharded");
		ZarrFixture.write(new File(sharded, "zarr.json"), "{\"zarr_format\": 3, \"node_type\": \"array\", \"shape\": [4, 5],"
			+ " \"data_type\": \"uint16\", \"chunk_grid\": {\"name\": \"regular\", \"configuration\": {\"chunk_shape\": [4, 5]}},"
			+ " \"codecs\": [{\"name\": \"sharding_indexed\", \"configuration\": {\"chunk_shape\": [2, 5], \"codecs\": ["
			+ "{\"name\": \"transpose\", \"configuration\": {\"order\": [1, 0]}}, {\"name\": \"bytes\"}]}}]}");
		assertEquals('F', ZarrMetadata.read(sharded).order());
	}

	// --- Axes ---

	private static int[] roles(String order) {
		return ZarrAxisOrder.roles(order);
	}

	private static int[] axes(long[] shape, long[] chunks, char order, int format, String[] names) {
		return ZarrImage.axisRoles(shape, chunks, order, format, names, null, ZarrAxisOrder.AUTO, "test");
	}

	@Test
	public void namedAxesAreReadAsNamed() {
		long[] shape = { 2, 3, 4, 5, 6 };
		assertArrayEquals(roles("tczyx"), axes(shape, null, 'F', 3, new String[] { "t", "c", "z", "y", "x" }));
		assertArrayEquals(roles("tzyxc"), axes(shape, null, 'C', 2, new String[] { "Time", "Z", "Y", "X", "Channel" }));
		// OME-Zarr axis types name axes whose names are not standard
		assertArrayEquals(roles("tczyx"), ZarrImage.axisRoles(shape, null, 'C', 3, new String[] { "frame", "wavelength", "z", "y", "x" },
			new String[] { "time", "channel", "space", "space", "space" }, ZarrAxisOrder.AUTO, "test"));
		// Names win over the user's order
		assertArrayEquals(roles("tczyx"), ZarrImage.axisRoles(shape, null, 'C', 3, new String[] { "t", "c", "z", "y", "x" }, null,
			"tzyxc", "test"));
	}

	@Test
	public void otherNamedAxesGoToFreeHyperstackDimensions() {
		// An unknown axis of extent 1 is dropped, a longer one takes a free dimension
		assertArrayEquals(roles("zyx"), shifted(axes(new long[] { 1, 4, 5, 6 }, null, 'C', 3, new String[] { "view", "z", "y", "x" }), 1));
		int[] r = axes(new long[] { 3, 4, 5, 6 }, null, 'C', 3, new String[] { "angle", "z", "y", "x" });
		assertEquals(0, r[ZarrImage.T]);
		assertEquals(1, r[ZarrImage.Z]);
		r = axes(new long[] { 3, 5, 6 }, null, 'C', 3, new String[] { "plane", "y", "x" });
		assertEquals(0, r[ZarrImage.Z]);
		try {
			axes(new long[] { 2, 2, 2, 2, 5, 6 }, null, 'C', 3, new String[] { "a", "b", "c", "d", "y", "x" });
			fail("opened an array with more axes than ImageJ has");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("more axes"));
		}
	}

	/** roles with every axis moved offset axes down. */
	private static int[] shifted(int[] roles, int offset) {
		int[] r = roles.clone();
		for (int i = 0; i < 5; i++)
			if (r[i] >= 0) r[i] -= offset;
		return r;
	}

	@Test
	public void namesWithoutXAndYAreNoHelp() {
		// xarray's defaults, or a duplicate: guessed as if unnamed
		assertArrayEquals(roles("yxz"), axes(new long[] { 4, 5, 6 }, null, 'C', 2, new String[] { "dim_0", "dim_1", "dim_2" }));
		assertArrayEquals(roles("yxz"), axes(new long[] { 4, 5, 6 }, null, 'C', 2, new String[] { "x", "x", "y" }));
	}

	@Test
	public void unnamedZarrV2ArraysAreGuessedFromTheirChunks() {
		// The plugin's and PetaKit5D's arrays: MATLAB's order, as before
		assertArrayEquals(roles("yxz"), axes(new long[] { 1800, 512, 500 }, new long[] { 256, 256, 256 }, 'F', 2, null));
		assertArrayEquals(roles("yxz"), axes(new long[] { 500, 1800, 512 }, new long[] { 256, 256, 256 }, 'C', 2, null));
		assertArrayEquals(roles("yx"), axes(new long[] { 300, 400 }, new long[] { 256, 256 }, 'C', 2, null));
		// cpp-zarr's chunks of 1 beyond the spatial axes: last in MATLAB, first in NumPy
		assertArrayEquals(roles("yxzt"), axes(new long[] { 64, 64, 32, 10 }, new long[] { 64, 64, 32, 1 }, 'F', 2, null));
		assertArrayEquals(roles("yxzct"), axes(new long[] { 64, 64, 32, 2, 10 }, new long[] { 64, 64, 32, 1, 1 }, 'C', 2, null));
		assertArrayEquals(roles("tczyx"), axes(new long[] { 10, 2, 32, 64, 64 }, new long[] { 1, 1, 32, 64, 64 }, 'F', 2, null));
		assertArrayEquals(roles("zyx"), axes(new long[] { 100, 512, 512 }, new long[] { 1, 512, 512 }, 'C', 2, null));
		// No sign in the chunks: 4D and 5D by the storage order
		assertArrayEquals(roles("tzyx"), axes(new long[] { 10, 32, 64, 64 }, new long[] { 5, 32, 64, 64 }, 'C', 2, null));
		assertArrayEquals(roles("yxzt"), axes(new long[] { 64, 64, 32, 10 }, new long[] { 64, 64, 32, 5 }, 'F', 2, null));
	}

	@Test
	public void unnamedZarrV3ArraysPutSmallLastAxesInChannels() {
		assertArrayEquals(roles("tzyxc"), axes(new long[] { 10, 32, 64, 64, 2 }, new long[] { 1, 32, 64, 64, 2 }, 'C', 3, null));
		assertArrayEquals(roles("tzyxc"), axes(new long[] { 10, 32, 64, 64, 1 }, null, 'C', 3, null));
		assertArrayEquals(roles("tczyx"), axes(new long[] { 10, 2, 32, 64, 64 }, null, 'C', 3, null));
		assertArrayEquals(roles("zyxc"), axes(new long[] { 32, 64, 64, 3 }, null, 'C', 3, null));
		assertArrayEquals(roles("tzyx"), axes(new long[] { 10, 32, 64, 64 }, null, 'C', 3, null));
		assertArrayEquals(roles("yxc"), axes(new long[] { 64, 64, 3 }, null, 'C', 3, null));
		assertArrayEquals(roles("zyx"), axes(new long[] { 32, 64, 64 }, null, 'F', 3, null));
		assertArrayEquals(roles("yx"), axes(new long[] { 64, 4 }, null, 'C', 3, null));
	}

	@Test
	public void theUsersOrderReplacesTheGuessForItsNumberOfAxes() {
		long[] five = { 10, 2, 32, 64, 64 }, three = { 1800, 512, 500 };
		assertArrayEquals(roles("tzyxc"), ZarrImage.axisRoles(five, null, 'C', 3, null, null, "tzyxc", "test"));
		assertArrayEquals(roles("tzyxc"), ZarrImage.axisRoles(five, null, 'C', 2, null, null, "yxz, tzyxc", "test"));
		assertArrayEquals(roles("zyx"), ZarrImage.axisRoles(three, null, 'F', 2, null, null, "zyx, tzyxc", "test"));
		// No order for this many axes: guessed
		assertArrayEquals(roles("yxz"), ZarrImage.axisRoles(three, null, 'F', 2, null, null, "tzyxc", "test"));
	}

	@Test
	public void moreThanFiveAxesAreSqueezed() {
		int[] r = axes(new long[] { 1, 10, 1, 32, 64, 64 }, null, 'C', 3, null);
		assertEquals(1, r[ZarrImage.T]);
		assertEquals(3, r[ZarrImage.Z]);
		assertEquals(4, r[ZarrImage.Y]);
		assertEquals(5, r[ZarrImage.X]);
		try {
			axes(new long[] { 2, 2, 2, 2, 2, 2 }, null, 'C', 3, null);
			fail("opened a 6D array without names");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("6D"));
		}
	}

	@Test
	public void oneDimensionalArraysAreRefused() {
		try {
			axes(new long[] { 100 }, null, 'C', 3, null);
			fail("opened a 1D array");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("1D"));
		}
	}

	// --- The user's axis order setting ---

	@Test
	public void axisOrdersAreValidatedAndNormalized() {
		assertEquals(ZarrAxisOrder.AUTO, ZarrAxisOrder.normalize(""));
		assertEquals(ZarrAxisOrder.AUTO, ZarrAxisOrder.normalize(" auto "));
		assertEquals("tzyxc", ZarrAxisOrder.normalize("TZYXC"));
		assertEquals("yxz, tzyxc", ZarrAxisOrder.normalize("tzyxc;yxz"));
		assertEquals("yx, zyx", ZarrAxisOrder.normalize("zyx  yx"));
		assertArrayEquals(new int[] { 3, 2, 1, 4, 0 }, ZarrAxisOrder.roles("yxz, tzyxc", 5));
		assertNull(ZarrAxisOrder.roles("yxz", 5));
		for (String bad : new String[] { "tzyxq", "zzyx", "tzc", "zyx, xyz", "auto, zyx" }) {
			try {
				ZarrAxisOrder.normalize(bad);
				fail("accepted " + bad);
			}
			catch (IllegalArgumentException e) {
				assertTrue(e.getMessage(), e.getMessage().length() > 10);
			}
		}
	}
}
