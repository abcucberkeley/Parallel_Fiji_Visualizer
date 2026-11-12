package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.EventQueue;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.CompositeImage;
import ij.IJ;
import ij.ImageListener;
import ij.ImagePlus;
import ij.ImageStack;
import ij.ParallelVirtualStack;
import ij.VirtualStack;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.Duplicator;
import ij.plugin.SubstackMaker;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;

/**
 * Images too large for memory open as ImageJ's own ImagePlus over an
 * ij.VirtualStack (pfv.forceVirtual takes that path with small files): they
 * show plane 1 first, have an image ID of their own, reach ImageJ's image
 * listeners, save with ImageJ's FileSaver, and equal the same file opened
 * into memory: dimensions, calibration and every plane, also across the
 * stack's block borders. Shown for a TIFF, an ImageJ hyperstack TIFF and a
 * Zarr with channels and time points.
 */
public class VirtualImageTest {

	private static final int W = 16, H = 12;
	private static final String FORCE_VIRTUAL = "pfv.forceVirtual";

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@BeforeClass
	public static void nativesRequired() {
		Natives.required();
	}

	@After
	public void inMemoryAgain() {
		System.clearProperty(FORCE_VIRTUAL);
	}

	/** The plugin's open of path, as a virtual stack or into memory. */
	private static ImagePlus open(String path, boolean virtual) {
		if (virtual) System.setProperty(FORCE_VIRTUAL, "true");
		else System.clearProperty(FORCE_VIRTUAL);
		try {
			ImagePlus imp = PRT.isTiff(path) ? new PRT(path, false).getImp() : new PRZ(path, false).getImp();
			assertNotNull(path + " did not open", imp);
			assertEquals(path + " opened " + (virtual ? "in memory" : "as a virtual stack"), virtual, imp.getStack().isVirtual());
			return imp;
		}
		finally {
			System.clearProperty(FORCE_VIRTUAL);
		}
	}

	private static short[] plane(ImagePlus imp, int n) {
		return (short[]) imp.getStack().getPixels(n);
	}

	/** A cpp-tiff stack of z planes (a BigTIFF without ImageJ metadata). */
	private String plainTiff(String name, int z) {
		short[][] planes = new short[z][];
		for (int i = 0; i < z; i++) planes[i] = TiffFixture.plane(W, H, i, 0);
		String path = new File(tmp.getRoot(), name).getPath();
		new ParallelWriteNative().parallelWriteTiff(path, planes, W, H, z, 16);
		return path;
	}

	/** An ImageJ hyperstack TIFF of 2 channels, 5 slices and 2 frames, calibrated, as ImageJ saves composites. */
	private String imageJHyperstack(String name) {
		final int c = 2, z = 5, t = 2;
		ImageStack stack = new ImageStack(W, H);
		for (int n = 0; n < c * z * t; n++) {
			ShortProcessor ip = new ShortProcessor(W, H);
			for (int i = 0; i < W * H; i++) ip.set(i, 100 + i * 7 + n * 211);
			stack.addSlice(null, ip);
		}
		ImagePlus src = new ImagePlus("hyper", stack);
		src.setDimensions(c, z, t);
		CompositeImage comp = new CompositeImage(src, IJ.COMPOSITE);
		Calibration cal = comp.getCalibration();
		cal.pixelWidth = 0.104;
		cal.pixelHeight = 0.104;
		cal.pixelDepth = 0.25;
		cal.frameInterval = 3.5;
		cal.setUnit("micron");
		String path = new File(tmp.getRoot(), name).getPath();
		assertTrue(new FileSaver(comp).saveAsTiff(path));
		return path;
	}

	/** An OME-Zarr image of (t, c, z, y, x) = (2, 2, 5, H, W), in chunks of 2 planes, with pixel sizes. */
	private String omeZarr(String name) throws Exception {
		File image = ZarrFixture.writeV2Group(new File(tmp.getRoot(), name), "{\"multiscales\": [{\"version\": \"0.4\", \"axes\": ["
			+ "{\"name\": \"t\", \"type\": \"time\", \"unit\": \"second\"}, {\"name\": \"c\", \"type\": \"channel\"},"
			+ " {\"name\": \"z\", \"type\": \"space\", \"unit\": \"micrometer\"}, {\"name\": \"y\", \"type\": \"space\", \"unit\": \"micrometer\"},"
			+ " {\"name\": \"x\", \"type\": \"space\", \"unit\": \"micrometer\"}], \"datasets\": [{\"path\": \"0\","
			+ " \"coordinateTransformations\": [{\"type\": \"scale\", \"scale\": [2.0, 1.0, 0.5, 0.25, 0.125]}]}]}]}");
		ZarrFixture.writeV2(new File(image, "0"), new long[] { 2, 2, 5, H, W }, new long[] { 1, 1, 2, H, W }, 'C', null);
		return image.getPath();
	}

	/**
	 * path opened as a virtual stack is ImageJ's ImagePlus over a VirtualStack
	 * and equals path opened into memory; returns both, the virtual one first.
	 */
	private static ImagePlus[] assertVirtualLikeInMemory(String path) throws Exception {
		String what = new File(path).getName() + ": ";
		ImagePlus mem = open(path, false);
		ImagePlus before = new ImagePlus("before", new ByteProcessor(1, 1));
		ImagePlus imp = open(path, true);
		ImagePlus after = new ImagePlus("after", new ByteProcessor(1, 1));

		// Plane 1 is shown first (its processor is the stack's plane 1, as ImageJ sets it up)
		assertEquals(what + "current slice", 1, imp.getCurrentSlice());
		assertArrayEquals(what + "plane shown on opening", plane(mem, 1), (short[]) imp.getProcessor().getPixels());
		assertFalse(what + "plane 1 and 2 are the same", Arrays.equals(plane(mem, 1), plane(mem, 2)));
		if (!mem.isComposite()) {
			assertEquals(what + "display minimum", mem.getDisplayRangeMin(), imp.getDisplayRangeMin(), 0);
			assertEquals(what + "display maximum", mem.getDisplayRangeMax(), imp.getDisplayRangeMax(), 0);
		}

		// ImageJ's own classes, with ImageJ's state: an ID of its own, a stack ImageJ knows is virtual
		assertSame(what + "class", ImagePlus.class, imp.getClass());
		assertTrue(what + "not an ij.VirtualStack", imp.getStack() instanceof VirtualStack);
		assertEquals(what + "hasImageStack", Boolean.TRUE, ImagePlus.class.getMethod("hasImageStack").invoke(imp));
		assertNotEquals(what + "ID of the image before", before.getID(), imp.getID());
		assertNotEquals(what + "ID of the image after", after.getID(), imp.getID());
		assertNotEquals(what + "ID of the in-memory image", mem.getID(), imp.getID());

		assertEquals(what + "width", mem.getWidth(), imp.getWidth());
		assertEquals(what + "height", mem.getHeight(), imp.getHeight());
		assertEquals(what + "channels", mem.getNChannels(), imp.getNChannels());
		assertEquals(what + "slices", mem.getNSlices(), imp.getNSlices());
		assertEquals(what + "frames", mem.getNFrames(), imp.getNFrames());
		assertEquals(what + "planes", mem.getStackSize(), imp.getStackSize());
		assertEquals(what + "hyperstack", mem.isHyperStack(), imp.isHyperStack());
		assertEquals(what + "type", mem.getType(), imp.getType());
		assertEquals(what + "bit depth", mem.getBitDepth(), imp.getBitDepth());
		Calibration a = mem.getCalibration(), b = imp.getCalibration();
		assertEquals(what + "calibration", a.toString(), b.toString());
		assertEquals(what + "pixel depth", a.pixelDepth, b.pixelDepth, 0);
		assertEquals(what + "frame interval", a.frameInterval, b.frameInterval, 0);
		assertEquals(what + "time unit", a.getTimeUnit(), b.getTimeUnit());
		assertEquals(what + "title", mem.getTitle(), imp.getTitle());

		// Every plane as the window shows it (setSlice) and as commands read it,
		// forward, backward and jumping, over blocks of 3 planes (the block
		// read on opening, of up to 16, is cached until it is pushed out)
		ParallelVirtualStack stack = (ParallelVirtualStack) imp.getStack();
		stack.setMaxBlockPlanes(3);
		int n = imp.getStackSize(), reads = stack.getBlockReads();
		List<Integer> order = new ArrayList<Integer>();
		for (int s = 1; s <= n; s++) order.add(s);
		for (int s = n; s >= 1; s--) order.add(s);
		Collections.addAll(order, n / 2, 1, n, 2, n - 1, n / 2 + 1);
		for (int s : order) {
			imp.setSlice(s);
			assertEquals(what + "slice", s, imp.getCurrentSlice());
			assertArrayEquals(what + "slice " + s + " shown", plane(mem, s), (short[]) imp.getProcessor().getPixels());
			assertArrayEquals(what + "plane " + s, plane(mem, s), plane(imp, s));
		}
		assertTrue(what + "no block border was crossed", stack.getBlockReads() > reads + 2);
		return new ImagePlus[] { imp, mem };
	}

	/** Closing and flushing the image work, and ImageJ's image listeners hear of it. */
	private static void assertClosesAndTellsListeners(ImagePlus imp) throws Exception {
		final List<ImagePlus> closed = Collections.synchronizedList(new ArrayList<ImagePlus>());
		ImageListener listener = new ImageListener() {
			public void imageOpened(ImagePlus i) {}
			public void imageClosed(ImagePlus i) { closed.add(i); }
			public void imageUpdated(ImagePlus i) {}
		};
		ImagePlus.addImageListener(listener);
		try {
			imp.close();
			imp.flush();
			// ImageJ tells its listeners on the event thread
			EventQueue.invokeAndWait(() -> {});
			assertTrue(imp.getTitle() + ": the image listener was not told", closed.contains(imp));
		}
		finally {
			ImagePlus.removeImageListener(listener);
		}
	}

	@Test
	public void tiffOpensAsVirtualStackOfImageJ() throws Exception {
		ImagePlus[] images = assertVirtualLikeInMemory(plainTiff("plain.tif", 40));
		assertClosesAndTellsListeners(images[0]);
	}

	/** ImageJ's Save As Tiff of the virtual stack writes a file that reads back as the original. */
	@Test
	public void imageJHyperstackOpensAsVirtualStackAndSavesWithImageJ() throws Exception {
		ImagePlus[] images = assertVirtualLikeInMemory(imageJHyperstack("hyper.tif"));
		ImagePlus imp = images[0], mem = images[1];
		assertTrue("hyperstack", imp.isHyperStack());
		assertEquals(0.104, imp.getCalibration().pixelWidth, 1e-6);

		String saved = new File(tmp.getRoot(), "saved.tif").getPath();
		assertTrue("not saved", new FileSaver(imp).saveAsTiffStack(saved));
		ImagePlus back = IJ.openImage(saved);
		assertNotNull("the saved file does not open", back);
		assertEquals("channels", mem.getNChannels(), back.getNChannels());
		assertEquals("slices", mem.getNSlices(), back.getNSlices());
		assertEquals("frames", mem.getNFrames(), back.getNFrames());
		assertEquals("pixel width", mem.getCalibration().pixelWidth, back.getCalibration().pixelWidth, 0);
		assertEquals("pixel depth", mem.getCalibration().pixelDepth, back.getCalibration().pixelDepth, 0);
		assertEquals("frame interval", mem.getCalibration().frameInterval, back.getCalibration().frameInterval, 0);
		for (int n = 1; n <= mem.getStackSize(); n++)
			assertArrayEquals("saved plane " + n, plane(mem, n), plane(back, n));
		assertClosesAndTellsListeners(imp);
	}

	/** A Zarr with channels and time points, and ImageJ's Duplicate of a range of it. */
	@Test
	public void zarrOpensAsVirtualHyperstackOfImageJ() throws Exception {
		ImagePlus[] images = assertVirtualLikeInMemory(omeZarr("image.ome.zarr"));
		ImagePlus imp = images[0], mem = images[1];
		assertEquals(0.125, imp.getCalibration().pixelWidth, 0);
		assertEquals(2.0, imp.getCalibration().frameInterval, 0);

		ImagePlus dup = new Duplicator().run(imp, 2, 2, 2, 4, 1, 2);
		assertFalse("the duplicate is virtual", dup.getStack().isVirtual());
		assertEquals(1, dup.getNChannels());
		assertEquals(3, dup.getNSlices());
		assertEquals(2, dup.getNFrames());
		for (int t = 1; t <= 2; t++)
			for (int z = 2; z <= 4; z++)
				assertArrayEquals("duplicate c2 z" + z + " t" + t, plane(mem, mem.getStackIndex(2, z, t)),
					plane(dup, dup.getStackIndex(1, z - 1, t)));
		assertClosesAndTellsListeners(imp);
	}

	/**
	 * Deleting slices (Make Substack's delete, and the hyperstack delete of
	 * Delete Slice, which then checks the stack's size) removes them from the
	 * stack; the others still show their own planes.
	 */
	@Test
	public void deletedSlicesLeaveTheOthersInPlace() throws Exception {
		String path = plainTiff("delete.tif", 8);
		ImagePlus mem = open(path, false), imp = open(path, true);
		SubstackMaker.run(imp, "delete 3-4");
		imp.getStack().deleteLastSlice();
		imp.setStack(imp.getStack());
		int[] kept = { 1, 2, 5, 6, 7 };
		assertEquals(kept.length, imp.getStackSize());
		for (int i = 0; i < kept.length; i++) {
			imp.setSlice(i + 1);
			assertArrayEquals("slice " + (i + 1), plane(mem, kept[i]), (short[]) imp.getProcessor().getPixels());
		}

		// Slice 2 of every channel and time point, as Image > Stacks > Delete Slice does it
		path = omeZarr("delete.zarr");
		mem = open(path, false);
		imp = open(path, true);
		int c = imp.getNChannels(), z = imp.getNSlices(), t = imp.getNFrames();
		ImageStack stack = imp.getStack();
		for (int ti = t; ti >= 1; ti--) {
			int index = imp.getStackIndex(c, 2, ti);
			for (int i = 0; i < c; i++) stack.deleteSlice(index - i);
		}
		imp.setStack(stack, c, z - 1, t);
		for (int ti = 1; ti <= t; ti++)
			for (int zi = 1; zi < z; zi++)
				for (int ci = 1; ci <= c; ci++)
					assertArrayEquals("c" + ci + " z" + zi + " t" + ti, plane(mem, mem.getStackIndex(ci, zi < 2 ? zi : zi + 1, ti)),
						plane(imp, imp.getStackIndex(ci, zi, ti)));
	}
}
