package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.ImagePlus;
import ij.ParallelVirtualStack;
import ij.measure.Calibration;
import ij.plugin.ParallelFolderOpener;

/**
 * Zarr arrays of 2 to 5 dimensions, v2 and v3, in the layouts MATLAB, NumPy,
 * OME-Zarr and tensorstore write, opened through PRZ and the virtual stack:
 * every pixel must come from the right element of the array.
 */
public class ZarrImageTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@BeforeClass
	public static void nativesRequired() {
		Natives.required();
	}

	@After
	public void autoAxisOrder() {
		ZarrAxisOrder.set(ZarrAxisOrder.AUTO);
	}

	private File folder(String name) {
		return new File(tmp.getRoot(), name);
	}

	/** imp must hold exactly the planes of the array with these x, y, z, c and t axes, czt order. */
	private static void assertImage(ImagePlus imp, long[] shape, int[] axes) {
		assertNotNull("no image", imp);
		int w = (int) shape[axes[0]], h = (int) shape[axes[1]];
		int z = axes[2] >= 0 ? (int) shape[axes[2]] : 1, c = axes[3] >= 0 ? (int) shape[axes[3]] : 1, t = axes[4] >= 0 ? (int) shape[axes[4]] : 1;
		assertEquals("width", w, imp.getWidth());
		assertEquals("height", h, imp.getHeight());
		assertEquals("channels", c, imp.getNChannels());
		assertEquals("slices", z, imp.getNSlices());
		assertEquals("frames", t, imp.getNFrames());
		for (int ti = 0; ti < t; ti++)
			for (int zi = 0; zi < z; zi++)
				for (int ci = 0; ci < c; ci++)
					assertArrayEquals("c" + ci + " z" + zi + " t" + ti, ZarrFixture.plane(shape, axes, ci, zi, ti),
						(short[]) imp.getStack().getPixels(imp.getStackIndex(ci + 1, zi + 1, ti + 1)));
	}

	private static ImagePlus open(File dir) {
		return new PRZ(dir.getPath(), false).getImp();
	}

	private static int[] roles(String order) {
		return ZarrAxisOrder.roles(order);
	}

	// --- Layouts ---

	@Test
	public void namedFiveDZarrV3OpensAsHyperstack() throws Exception {
		long[] shape = { 2, 3, 4, 5, 6 };
		File dir = ZarrFixture.writeV3(folder("tczyx"), shape, new long[] { 1, 2, 3, 4, 4 }, 'C', new String[] { "t", "c", "z", "y", "x" });
		ImagePlus imp = open(dir);
		assertImage(imp, shape, roles("tczyx"));
		assertTrue("channels are shown as a composite", imp.isComposite());
		assertEquals("tczyx", imp.getTitle());
	}

	@Test
	public void unnamedZarrV3WithChannelsLastOpensAsTzyxc() throws Exception {
		long[] shape = { 2, 3, 5, 6, 2 };
		File dir = ZarrFixture.writeV3(folder("tzyxc"), shape, new long[] { 1, 3, 5, 6, 2 }, 'C', null);
		assertImage(open(dir), shape, roles("tzyxc"));
	}

	@Test
	public void unnamedMatlabFiveDZarrV2OpensAsYxzct() throws Exception {
		// MATLAB's layout: F order, chunks of 1 after the spatial axes
		long[] shape = { 5, 6, 3, 2, 2 };
		File dir = ZarrFixture.writeV2(folder("yxzct"), shape, new long[] { 4, 4, 3, 1, 1 }, 'F', null);
		assertImage(open(dir), shape, roles("yxzct"));
	}

	@Test
	public void numpyZarrV2ChunkedByPlaneOpensAsZyx() throws Exception {
		long[] shape = { 3, 5, 6 };
		File dir = ZarrFixture.writeV2(folder("zyx"), shape, new long[] { 1, 5, 6 }, 'C', null);
		assertImage(open(dir), shape, roles("zyx"));
	}

	@Test
	public void unnamedThreeDZarrV2StillOpensAsYxz() throws Exception {
		// The plugin's own layout, which every unnamed 3D array was read as before
		long[] shape = { 5, 6, 3 };
		File dir = ZarrFixture.writeV2(folder("yxz"), shape, new long[] { 4, 4, 2 }, 'C', null);
		assertImage(open(dir), shape, roles("yxz"));
	}

	@Test
	public void transposedZarrV3OpensByItsNames() throws Exception {
		// F-order chunks (tensorstore's transpose codec) of a (z, y, x) array
		long[] shape = { 3, 5, 6 };
		File dir = ZarrFixture.writeV3(folder("zyxF"), shape, new long[] { 2, 4, 4 }, 'F', new String[] { "z", "y", "x" });
		assertImage(open(dir), shape, roles("zyx"));
	}

	@Test
	public void twoDZarrOpensAsOnePlane() throws Exception {
		long[] shape = { 5, 6 };
		File dir = ZarrFixture.writeV2(folder("yx"), shape, new long[] { 4, 4 }, 'C', null);
		assertImage(open(dir), shape, roles("yx"));
	}

	@Test
	public void xarrayDimensionNamesAreUsed() throws Exception {
		long[] shape = { 2, 5, 6 };
		File dir = ZarrFixture.writeV2(folder("xarray"), shape, new long[] { 1, 5, 6 }, 'C', "{\"_ARRAY_DIMENSIONS\": [\"time\", \"y\", \"x\"]}");
		assertImage(open(dir), shape, roles("tyx"));
	}

	@Test
	public void theUsersAxisOrderReplacesTheGuess() throws Exception {
		long[] shape = { 2, 3, 4, 5, 6 };
		File dir = ZarrFixture.writeV3(folder("unnamed"), shape, new long[] { 1, 3, 4, 5, 6 }, 'C', null);
		// A small last axis: guessed to be channels
		assertImage(open(dir), shape, roles("tzyxc"));
		ZarrAxisOrder.set("yxz, tczyx");
		assertImage(open(dir), shape, roles("tczyx"));
	}

	// --- OME-Zarr and groups ---

	private static final String OME_AXES = "[{\"name\": \"t\", \"type\": \"time\", \"unit\": \"second\"},"
		+ " {\"name\": \"c\", \"type\": \"channel\"}, {\"name\": \"z\", \"type\": \"space\", \"unit\": \"micrometer\"},"
		+ " {\"name\": \"y\", \"type\": \"space\", \"unit\": \"micrometer\"}, {\"name\": \"x\", \"type\": \"space\", \"unit\": \"micrometer\"}]";

	private static void assertOmeCalibration(ImagePlus imp) {
		Calibration cal = imp.getCalibration();
		assertEquals(0.125, cal.pixelWidth, 0);
		assertEquals(0.25, cal.pixelHeight, 0);
		assertEquals(0.5, cal.pixelDepth, 0);
		assertEquals(2.0, cal.frameInterval, 0);
		assertEquals("micron", cal.getUnit());
		assertEquals("sec", cal.getTimeUnit());
	}

	@Test
	public void omeZarr04ImageOpensItsFullResolutionLevel() throws Exception {
		long[] shape = { 2, 2, 3, 5, 6 };
		File image = ZarrFixture.writeV2Group(folder("image.ome.zarr"), "{\"multiscales\": [{\"version\": \"0.4\", \"axes\": " + OME_AXES
			+ ", \"datasets\": [{\"path\": \"0\", \"coordinateTransformations\": [{\"type\": \"scale\", \"scale\": [2.0, 1.0, 0.5, 0.25, 0.125]}]},"
			+ " {\"path\": \"1\", \"coordinateTransformations\": [{\"type\": \"scale\", \"scale\": [2.0, 1.0, 1.0, 0.5, 0.25]}]}]}]}");
		ZarrFixture.writeV2(new File(image, "0"), shape, new long[] { 1, 1, 3, 5, 6 }, 'C', null);
		ImagePlus imp = open(image);
		assertImage(imp, shape, roles("tczyx"));
		assertOmeCalibration(imp);
		assertEquals("image.ome.zarr", imp.getTitle());

		// A level opened by itself has the image's axes and pixel size too
		imp = open(new File(image, "0"));
		assertImage(imp, shape, roles("tczyx"));
		assertOmeCalibration(imp);
	}

	@Test
	public void omeZarr05ImageOnZarrV3UsesItsAxes() throws Exception {
		// Unnamed, this array would be guessed to be (z, y, x, c)
		long[] shape = { 2, 3, 5, 6 };
		File image = ZarrFixture.writeV3Group(folder("image05"), "{\"ome\": {\"version\": \"0.5\", \"multiscales\": [{\"axes\": ["
			+ "{\"name\": \"c\", \"type\": \"channel\"}, {\"name\": \"z\", \"type\": \"space\"}, {\"name\": \"y\", \"type\": \"space\"},"
			+ " {\"name\": \"x\", \"type\": \"space\"}], \"datasets\": [{\"path\": \"s0\", \"coordinateTransformations\": [{\"type\": \"scale\","
			+ " \"scale\": [1, 1, 1, 1]}]}]}]}}");
		ZarrFixture.writeV3(new File(image, "s0"), shape, new long[] { 1, 3, 5, 6 }, 'C', null);
		assertImage(open(image), shape, roles("czyx"));
	}

	@Test
	public void bioformats2rawContainerOpensItsFirstImage() throws Exception {
		long[] shape = { 3, 5, 6 };
		File root = ZarrFixture.writeV2Group(folder("b2r.zarr"), "{\"bioformats2raw.layout\": 3}");
		File image = ZarrFixture.writeV2Group(new File(root, "0"), "{\"multiscales\": [{\"version\": \"0.4\", \"axes\": ["
			+ "{\"name\": \"z\", \"type\": \"space\"}, {\"name\": \"y\", \"type\": \"space\"}, {\"name\": \"x\", \"type\": \"space\"}],"
			+ " \"datasets\": [{\"path\": \"0\"}]}]}");
		ZarrFixture.writeV2(new File(image, "0"), shape, new long[] { 3, 5, 6 }, 'C', null);
		assertImage(open(root), shape, roles("zyx"));
	}

	@Test
	public void groupOfOneArrayOpensIt() throws Exception {
		long[] shape = { 5, 6, 3 };
		File group = ZarrFixture.writeV3Group(folder("group1"), null);
		ZarrFixture.writeV2(new File(group, "raw"), shape, new long[] { 5, 6, 3 }, 'F', null);
		assertImage(open(group), shape, roles("yxz"));
	}

	@Test
	public void groupOfSeveralArraysSaysWhichToOpen() throws Exception {
		File group = ZarrFixture.writeV2Group(folder("group2"), null);
		ZarrFixture.writeV2(new File(group, "raw"), new long[] { 5, 6 }, new long[] { 5, 6 }, 'C', null);
		ZarrFixture.writeV3(new File(group, "labels"), new long[] { 5, 6 }, new long[] { 5, 6 }, 'C', null);
		try {
			open(group);
			fail("opened a group of two arrays");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("labels, raw"));
		}
	}

	@Test
	public void zarrNamedFolderWithoutMetadataIsRefusedClearly() throws Exception {
		try {
			open(tmp.newFolder("nothing.zarr"));
			fail("opened a folder without Zarr metadata");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("no Zarr metadata"));
		}
	}

	/** Folders are listed as images by their metadata, whatever their names. */
	@Test
	public void folderOfZarrArraysWithoutTheSuffixOpensAsAStack() throws Exception {
		File dir = tmp.newFolder("arrays");
		long[] shape = { 5, 6, 3 };
		ZarrFixture.writeV2(new File(dir, "a"), shape, new long[] { 5, 6, 3 }, 'F', null);
		ZarrFixture.writeV3(new File(dir, "b"), shape, new long[] { 5, 6, 3 }, 'F', null);
		new File(dir, "notAnImage.zarr").mkdir();
		ZarrFixture.writeV2Group(new File(dir, "plainGroup"), null);
		ImagePlus imp = ParallelFolderOpener.open(dir.getPath(), "", null);
		assertNotNull("the folder did not open", imp);
		assertEquals(2 * 3, imp.getStackSize());
		assertArrayEquals(ZarrFixture.plane(shape, roles("yxz"), 0, 0, 0), (short[]) imp.getStack().getPixels(1));
	}

	// --- The virtual stack ---

	@Test
	public void virtualStackReadsEveryChannelAndTimePoint() throws Exception {
		long[] shape = { 2, 3, 4, 5, 6 };
		File dir = ZarrFixture.writeV3(folder("virtual"), shape, new long[] { 1, 1, 2, 5, 6 }, 'C', new String[] { "t", "c", "z", "y", "x" });
		ZarrImage zarr = ZarrImage.open(dir);
		ParallelVirtualStack stack = new ParallelVirtualStack(zarr, 0, 0, 0, 6, 5, 4);
		stack.setMaxBlockPlanes(2);
		assertEquals(2 * 3 * 4, stack.getSize());
		int[] axes = roles("tczyx");
		int n = 0;
		for (int t = 0; t < 2; t++)
			for (int z = 0; z < 4; z++)
				for (int c = 0; c < 3; c++)
					assertArrayEquals("c" + c + " z" + z + " t" + t, ZarrFixture.plane(shape, axes, c, z, t),
						(short[]) stack.getProcessor(++n).getPixels());
		// Scrolling z in one channel stays within its blocks: z 0-1 and 2-3
		int reads = stack.getBlockReads();
		for (int z = 0; z < 4; z++) stack.getProcessor(1 + 3 * z);
		assertEquals(reads + 2, stack.getBlockReads());
	}

	@Test
	public void croppedVirtualStackReadsItsWindow() throws Exception {
		long[] shape = { 6, 7, 5 };
		File dir = ZarrFixture.writeV2(folder("crop"), shape, new long[] { 4, 4, 2 }, 'F', null);
		ZarrImage zarr = ZarrImage.open(dir);
		// x from 2, y from 1, z from 1: 4 x 3 x 3
		ParallelVirtualStack stack = new ParallelVirtualStack(zarr, 2, 1, 1, 4, 3, 3);
		int[] axes = roles("yxz");
		for (int z = 0; z < 3; z++) {
			short[] full = ZarrFixture.plane(shape, axes, 0, z + 1, 0);
			short[] expected = new short[4 * 3];
			for (int y = 0; y < 3; y++)
				for (int x = 0; x < 4; x++)
					expected[y * 4 + x] = full[(y + 1) * 7 + (x + 2)];
			assertArrayEquals("z " + z, expected, (short[]) stack.getProcessor(z + 1).getPixels());
		}
	}

	@Test
	public void windowOfAFiveDArrayThroughPRZ() throws Exception {
		long[] shape = { 2, 2, 4, 5, 6 };
		File dir = ZarrFixture.writeV3(folder("window"), shape, new long[] { 1, 1, 2, 2, 2 }, 'C', new String[] { "t", "c", "z", "y", "x" });
		// Rows (X) 1-4, columns (Y) 2-5, planes 1-3, of every channel and time point
		ImagePlus imp = new PRZ(dir.getPath(), 1, 2, 1, 4, 5, 3, false).getImp();
		assertEquals(3, imp.getWidth());
		assertEquals(3, imp.getHeight());
		assertEquals(2, imp.getNSlices());
		assertEquals(2, imp.getNChannels());
		assertEquals(2, imp.getNFrames());
		int[] axes = roles("tczyx");
		short[] full = ZarrFixture.plane(shape, axes, 1, 2, 1);
		short[] px = (short[]) imp.getStack().getPixels(imp.getStackIndex(2, 2, 2));
		for (int y = 0; y < 3; y++)
			for (int x = 0; x < 3; x++)
				assertEquals("x " + x + " y " + y, full[(y + 1) * 6 + (x + 2)], px[y * 3 + x]);
	}

	// --- The native reads refuse what they cannot do ---

	@Test
	public void nativeReadsCheckTheirWindowAndType() throws Exception {
		File dir = ZarrFixture.writeV2(folder("checks"), new long[] { 2, 5, 6 }, new long[] { 2, 5, 6 }, 'C', null);
		ParallelReadNative r = new ParallelReadNative();
		String path = dir.getPath();
		String[] expected = { "not x, y, z, c or t", "data type", "Invalid axes", "outside", "has 3" };
		Runnable[] reads = {
			// spans axis 0, which has no role
			() -> r.parallelReadZarrUINT16(path, new long[] { 0, 0, 0 }, new long[] { 2, 5, 6 }, new int[] { 2, 1, -1, -1, -1 }),
			// 8-bit read of a 16-bit array
			() -> r.parallelReadZarrUINT8(path, new long[] { 0, 0, 0 }, new long[] { 1, 5, 6 }, new int[] { 2, 1, -1, -1, -1 }),
			// the same axis twice
			() -> r.parallelReadZarrUINT16(path, new long[] { 0, 0, 0 }, new long[] { 1, 5, 6 }, new int[] { 2, 2, -1, -1, -1 }),
			() -> r.parallelReadZarrUINT16(path, new long[] { 0, 0, 0 }, new long[] { 1, 5, 7 }, new int[] { 2, 1, -1, -1, -1 }),
			() -> r.parallelReadZarrUINT16(path, new long[] { 0, 0 }, new long[] { 5, 6 }, new int[] { 1, 0, -1, -1, -1 }),
		};
		for (int i = 0; i < reads.length; i++) {
			try {
				reads[i].run();
				fail("read " + i + " was not refused");
			}
			catch (RuntimeException e) {
				assertTrue(i + ": " + e.getMessage(), e.getMessage().contains(expected[i]));
			}
		}
		// What they can do: every plane of the array, in czt order (here c is axis 0)
		short[][] planes = r.parallelReadZarrUINT16(path, new long[] { 0, 0, 0 }, new long[] { 2, 5, 6 }, new int[] { 2, 1, -1, 0, -1 });
		assertEquals(2, planes.length);
		for (int c = 0; c < 2; c++)
			assertArrayEquals(ZarrFixture.plane(new long[] { 2, 5, 6 }, roles("cyx"), c, 0, 0), planes[c]);
	}
}
