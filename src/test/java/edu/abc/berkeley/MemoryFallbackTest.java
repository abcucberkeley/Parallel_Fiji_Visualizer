package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.ParallelVirtualStack;
import ij.WindowManager;
import ij.io.FileSaver;
import ij.macro.Interpreter;
import ij.measure.Calibration;
import ij.process.ShortProcessor;

/**
 * An image whose planes do not fit in the heap opens as a virtual stack, and
 * Fiji survives finding that out: HeapFit's layout of planes in G1's regions;
 * images that cannot fit open as virtual stacks at once, with one line saying
 * so; images whose allocation runs the heap out (a test hook fails the n-th
 * plane, and a JVM of its own with a small heap runs out for real) open as
 * the same virtual image as the forced one, with every plane allocated before
 * dropped and no thread dead; opens in progress and images ImageJ holds count
 * against the heap; and the read-ahead stops for an image that will be
 * virtual.
 */
public class MemoryFallbackTest {

	private static final int W = 16, H = 12;
	private static final String FORCE_VIRTUAL = "pfv.forceVirtual";
	private static final long MB = 1 << 20;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final List<String> deaths = Collections.synchronizedList(new ArrayList<String>());
	private Thread.UncaughtExceptionHandler handler;

	@BeforeClass
	public static void nativesRequired() {
		Natives.required();
	}

	@Before
	public void watchForDeadThreads() {
		handler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((t, e) -> deaths.add(t.getName() + ": " + e));
	}

	@After
	public void asOutsideTests() {
		HeapFit.heapForTests = -1;
		HeapFit.failAtForTests = -1;
		HeapFit.allocatedForTests = null;
		System.clearProperty(FORCE_VIRTUAL);
		Thread.setDefaultUncaughtExceptionHandler(handler);
		assertEquals("threads died", new ArrayList<String>(), new ArrayList<String>(deaths));
		assertEquals("heap still claimed", 0, HeapFit.claimed());
		// The common pool still runs every task
		assertEquals(499500L, IntStream.range(0, 1000).parallel().asLongStream().sum());
	}

	// --- Files ---

	/** A cpp-tiff stack of z planes (a BigTIFF without ImageJ metadata). */
	private String plainTiff(String name, int w, int h, int z) {
		short[][] planes = new short[z][];
		for (int i = 0; i < z; i++) planes[i] = TiffFixture.plane(w, h, i, 0);
		String path = new File(tmp.getRoot(), name).getPath();
		new ParallelWriteNative().parallelWriteTiff(path, planes, w, h, z, 16);
		return path;
	}

	/** An ImageJ hyperstack TIFF of 2 channels, 5 slices and 2 frames, calibrated. */
	private String imageJHyperstack(String name) {
		ImageStack stack = new ImageStack(W, H);
		for (int n = 0; n < 20; n++) {
			ShortProcessor ip = new ShortProcessor(W, H);
			for (int i = 0; i < W * H; i++) ip.set(i, 100 + i * 7 + n * 211);
			stack.addSlice(null, ip);
		}
		ImagePlus src = new ImagePlus("hyper", stack);
		src.setDimensions(2, 5, 2);
		CompositeImage comp = new CompositeImage(src, IJ.COMPOSITE);
		Calibration cal = comp.getCalibration();
		cal.pixelWidth = 0.104;
		cal.pixelHeight = 0.104;
		cal.pixelDepth = 0.25;
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

	// --- Helpers ---

	private static ImagePlus open(String path) {
		ImagePlus imp = PRT.isTiff(path) ? new PRT(path, false).getImp() : new PRZ(path, false).getImp();
		assertNotNull(path + " did not open", imp);
		return imp;
	}

	private static ImagePlus openForcedVirtual(String path) {
		System.setProperty(FORCE_VIRTUAL, "true");
		try {
			return open(path);
		}
		finally {
			System.clearProperty(FORCE_VIRTUAL);
		}
	}

	/** path opened, with what IJ.log printed meanwhile (it prints to System.out without ImageJ's window). */
	private static ImagePlus open(String path, List<String> logged) {
		PrintStream out = System.out;
		ByteArrayOutputStream lines = new ByteArrayOutputStream();
		System.setOut(new PrintStream(lines, true));
		try {
			return open(path);
		}
		finally {
			System.setOut(out);
			for (String line : lines.toString().split("\\R")) if (!line.isEmpty()) logged.add(line);
		}
	}

	/** actual is the same image as expected: ImageJ's ImagePlus on a virtual stack, with the same planes and description. */
	private static void assertSameVirtualImage(ImagePlus expected, ImagePlus actual) {
		String what = expected.getTitle() + ": ";
		assertSame(what + "class", ImagePlus.class, actual.getClass());
		assertTrue(what + "not a virtual stack", actual.getStack() instanceof ParallelVirtualStack);
		assertEquals(what + "title", expected.getTitle(), actual.getTitle());
		assertEquals(what + "width", expected.getWidth(), actual.getWidth());
		assertEquals(what + "height", expected.getHeight(), actual.getHeight());
		assertEquals(what + "channels", expected.getNChannels(), actual.getNChannels());
		assertEquals(what + "slices", expected.getNSlices(), actual.getNSlices());
		assertEquals(what + "frames", expected.getNFrames(), actual.getNFrames());
		assertEquals(what + "hyperstack", expected.isHyperStack(), actual.isHyperStack());
		assertEquals(what + "type", expected.getType(), actual.getType());
		assertEquals(what + "calibration", expected.getCalibration().toString(), actual.getCalibration().toString());
		assertEquals(what + "pixel depth", expected.getCalibration().pixelDepth, actual.getCalibration().pixelDepth, 0);
		assertEquals(what + "frame interval", expected.getCalibration().frameInterval, actual.getCalibration().frameInterval, 0);
		assertEquals(what + "display minimum", expected.getDisplayRangeMin(), actual.getDisplayRangeMin(), 0);
		assertEquals(what + "display maximum", expected.getDisplayRangeMax(), actual.getDisplayRangeMax(), 0);
		assertEquals(what + "current slice", 1, actual.getCurrentSlice());
		assertArrayEquals(what + "plane shown", (short[]) expected.getProcessor().getPixels(), (short[]) actual.getProcessor().getPixels());
		assertEquals(what + "planes", expected.getStackSize(), actual.getStackSize());
		for (int n = 1; n <= expected.getStackSize(); n++)
			assertArrayEquals(what + "plane " + n, (short[]) expected.getStack().getPixels(n), (short[]) actual.getStack().getPixels(n));
	}

	/** Collects until every plane tracked is gone but those of keep (by identity); fails after 10 s. */
	private static void assertOnlyAlive(List<WeakReference<Object>> tracked, List<Object> keep) throws InterruptedException {
		Map<Object, Boolean> kept = new IdentityHashMap<Object, Boolean>();
		for (Object o : keep) kept.put(o, Boolean.TRUE);
		long until = System.nanoTime() + 10_000_000_000L;
		for (;;) {
			int alive = 0, others = 0;
			synchronized (tracked) {
				for (WeakReference<Object> r : tracked) {
					Object o = r.get();
					if (o == null) continue;
					alive++;
					if (!kept.containsKey(o)) others++;
				}
			}
			if (others == 0) return;
			if (System.nanoTime() > until) fail(others + " of " + alive + " planes still reachable that should have been dropped");
			System.gc();
			Thread.sleep(20);
		}
	}

	private static void await(String what, BooleanSupplier done) throws InterruptedException {
		long until = System.nanoTime() + 10_000_000_000L;
		while (!done.getAsBoolean()) {
			if (System.nanoTime() > until) fail("waited 10 s for: " + what);
			Thread.sleep(2);
		}
	}

	// --- The layout of planes in the heap ---

	@Test
	public void planesTakeWholeRegionsFromHalfARegionOn() {
		for (long region = MB; region <= 32 * MB; region *= 2) {
			String what = "regions of " + region / MB + " MB: ";
			// Half a region with the header still packs two to a region (each takes its half)
			assertEquals(what + "half a region", 5 * region, HeapFit.needed(10, region / 2 - 16, region, 16));
			assertEquals(what + "half a region, odd count", 11 * region / 2, HeapFit.needed(11, region / 2 - 16, region, 16));
			// A byte more takes a region of its own (the array rounds up to 8 bytes)
			assertEquals(what + "past half a region", 10 * region, HeapFit.needed(10, region / 2 - 15, region, 16));
			assertEquals(what + "past half a region", 10 * region, HeapFit.needed(10, region / 2 - 8, region, 16));
			assertEquals(what + "a whole region", 10 * region, HeapFit.needed(10, region - 16, region, 16));
			// A plane of exactly a region (2048 x 2048 16-bit planes in 8 MB regions) needs two with its header
			assertEquals(what + "a region of pixels", 20 * region, HeapFit.needed(10, region, region, 16));
			assertEquals(what + "three regions and a byte", 40 * region, HeapFit.needed(10, 3 * region - 15, region, 16));
			// Small planes pack: a region holds region / 1016 arrays of 1000 bytes and a header (rounded to 8),
			// and each takes its share of the region
			long perRegion = region / 1016;
			assertEquals(what + "small planes", (1000 * region + perRegion - 1) / perRegion, HeapFit.needed(1000, 1000, region, 16));
			assertEquals(what + "a small plane", (region + perRegion - 1) / perRegion, HeapFit.needed(1, 1000, region, 16));
			assertEquals(what + "a third of a region", 4 * region, HeapFit.needed(12, region / 3 - 64, region, 16));
		}
		// The header of a JVM without compressed class pointers (24 bytes)
		assertEquals(10 * 4 * MB, HeapFit.needed(10, 2 * MB - 16, 4 * MB, 24));
		assertEquals(5 * 4 * MB, HeapFit.needed(10, 2 * MB - 24, 4 * MB, 24));
		// Other collectors: each array its own size, with its header, in 8-byte steps
		assertEquals(10 * (16 + 8 * MB), HeapFit.needed(10, 8 * MB, 0, 16));
		assertEquals(3 * 24, HeapFit.needed(3, 1, 0, 16));
		assertEquals(0, HeapFit.needed(0, 8 * MB, 16 * MB, 16));
		// The case of the plan: 1000 planes of 2048 x 2048 16-bit pixels (8.39 GB)
		assertEquals(1000 * 16 * MB, HeapFit.needed(1000, 8 * MB, 16 * MB, 16));
		assertEquals(1000 * 12 * MB, HeapFit.needed(1000, 8 * MB, 4 * MB, 16));
		assertEquals((1000 * 32 * MB + 2) / 3, HeapFit.needed(1000, 8 * MB, 32 * MB, 16));
	}

	// --- The allocation ---

	@Test
	public void allocatesEveryPlaneOfEachType() {
		byte[][] b = HeapFit.bytePlanes(37, 1000);
		short[][] s = HeapFit.shortPlanes(500, 77);
		float[][] f = HeapFit.floatPlanes(3, 4096);
		int[][] i = HeapFit.intPlanes(1, 10);
		assertEquals(37, b.length);
		assertEquals(500, s.length);
		assertEquals(3, f.length);
		assertEquals(1, i.length);
		Map<Object, Boolean> distinct = new IdentityHashMap<Object, Boolean>();
		for (byte[] p : b) { assertEquals(1000, p.length); distinct.put(p, true); for (byte v : p) assertEquals(0, v); }
		for (short[] p : s) { assertEquals(77, p.length); distinct.put(p, true); }
		for (float[] p : f) { assertEquals(4096, p.length); distinct.put(p, true); }
		for (int[] p : i) { assertEquals(10, p.length); distinct.put(p, true); }
		assertEquals("planes shared", 37 + 500 + 3 + 1, distinct.size());
		assertEquals(0, HeapFit.shortPlanes(0, 100).length);
	}

	/** The n-th plane fails: none of the planes allocated stays reachable, and the next allocation works. */
	@Test
	public void allocationThatRunsOutDropsEveryPlane() throws Exception {
		for (int failAt : new int[] { 0, 1, 30, 199 }) {
			List<WeakReference<Object>> tracked = Collections.synchronizedList(new ArrayList<WeakReference<Object>>());
			HeapFit.allocatedForTests = tracked;
			HeapFit.failAtForTests = failAt;
			try {
				HeapFit.shortPlanes(200, 32 * 1024);
				fail("the allocation did not fail at plane " + failAt);
			}
			catch (HeapFit.DoesNotFit e) {
				assertTrue("not an OutOfMemoryError", e instanceof OutOfMemoryError);
			}
			HeapFit.allocatedForTests = null;
			assertEquals("the failure was not taken back", -1, HeapFit.failAtForTests);
			assertTrue("planes allocated before plane " + failAt + ": " + tracked.size(), tracked.size() >= failAt);
			assertOnlyAlive(tracked, Collections.emptyList());
			assertEquals(200, HeapFit.shortPlanes(200, 32 * 1024).length);
		}
	}

	// --- The opens ---

	/** A TIFF whose read runs the heap out opens as the same virtual image as the forced one, and the dropped planes go. */
	@Test
	public void tiffThatRunsTheHeapOutOpensAsTheVirtualImage() throws Exception {
		for (String path : new String[] { plainTiff("plain.tif", W, H, 40), imageJHyperstack("hyper.tif") }) {
			ImagePlus forced = openForcedVirtual(path);
			List<WeakReference<Object>> tracked = Collections.synchronizedList(new ArrayList<WeakReference<Object>>());
			HeapFit.allocatedForTests = tracked;
			HeapFit.failAtForTests = 7;
			List<String> logged = new ArrayList<String>();
			ImagePlus imp = open(path, logged);
			HeapFit.allocatedForTests = null;
			assertEquals("the read did not run out", -1, HeapFit.failAtForTests);
			assertSameVirtualImage(forced, imp);
			assertEquals("lines logged: " + logged, 1, logged.size());
			assertTrue(logged.get(0), logged.get(0).startsWith(new File(path).getName() + ": opened as a virtual stack"));
			assertTrue(logged.get(0), logged.get(0).contains("ran short"));

			// What stays allocated is the virtual stack's blocks of the planes
			// shown, and the plane the image shows
			List<Object> shown = new ArrayList<Object>();
			for (int n = 1; n <= imp.getStackSize(); n++) shown.add(imp.getStack().getPixels(n));
			shown.add(imp.getProcessor().getPixels());
			assertOnlyAlive(tracked, shown);

			// And it still opens into memory when the heap allows
			ImagePlus mem = open(path);
			assertFalse(path + " did not open into memory", mem.getStack().isVirtual());
			for (int n = 1; n <= mem.getStackSize(); n++)
				assertArrayEquals("plane " + n, (short[]) mem.getStack().getPixels(n), (short[]) imp.getStack().getPixels(n));
		}
	}

	@Test
	public void zarrThatRunsTheHeapOutOpensAsTheVirtualImage() throws Exception {
		String path = omeZarr("image.ome.zarr");
		ImagePlus forced = openForcedVirtual(path);
		List<WeakReference<Object>> tracked = Collections.synchronizedList(new ArrayList<WeakReference<Object>>());
		HeapFit.allocatedForTests = tracked;
		HeapFit.failAtForTests = 3;
		List<String> logged = new ArrayList<String>();
		ImagePlus imp = open(path, logged);
		HeapFit.allocatedForTests = null;
		assertEquals("the read did not run out", -1, HeapFit.failAtForTests);
		assertSameVirtualImage(forced, imp);
		assertEquals(0.125, imp.getCalibration().pixelWidth, 0);
		assertEquals("lines logged: " + logged, 1, logged.size());
		assertTrue(logged.get(0), logged.get(0).startsWith("image.ome.zarr: opened as a virtual stack"));

		// What stays allocated is the virtual stack's blocks of the planes
		// shown, and the plane the image shows
		List<Object> shown = new ArrayList<Object>();
		for (int n = 1; n <= imp.getStackSize(); n++) shown.add(imp.getStack().getPixels(n));
		shown.add(imp.getProcessor().getPixels());
		assertOnlyAlive(tracked, shown);
	}

	/** An image that cannot fit is not read whole: it opens as a virtual stack at once, saying so in one line. */
	@Test
	public void imageThatCannotFitOpensVirtualAtOnce() throws Exception {
		for (String path : new String[] { plainTiff("big.tif", W, H, 40), omeZarr("big.zarr") }) {
			ImagePlus forced = openForcedVirtual(path);
			List<WeakReference<Object>> tracked = Collections.synchronizedList(new ArrayList<WeakReference<Object>>());
			HeapFit.allocatedForTests = tracked;
			HeapFit.heapForTests = 0;
			List<String> logged = new ArrayList<String>();
			ImagePlus imp = open(path, logged);
			HeapFit.heapForTests = -1;
			HeapFit.allocatedForTests = null;
			assertSameVirtualImage(forced, imp);
			// Only the block on show was read: 16 of the TIFF's 40 planes, 5 of the Zarr's 20
			assertTrue(path + ": " + tracked.size() + " planes allocated", tracked.size() <= 16);
			assertEquals("lines logged: " + logged, 1, logged.size());
			assertTrue(logged.get(0), logged.get(0).startsWith(new File(path).getName() + ": opened as a virtual stack"));
			assertTrue(logged.get(0), logged.get(0).contains("which has 0 MB free"));
		}
	}

	/** Opens in progress (their claims) and the images ImageJ holds count against the heap; virtual ones do not. */
	@Test
	public void opensInProgressAndOpenImagesCount() throws Exception {
		long planes = 99, bytes = 3 * MB;
		long need = HeapFit.needed(planes, bytes), third = HeapFit.needed(planes / 3, bytes);
		long headroom = HeapFit.headroom(planes, bytes), headroomThird = HeapFit.headroom(planes / 3, bytes);
		assertTrue(headroomThird <= headroom);
		collectAll();
		// Room for the image and a third of it, beside what is in the heap
		HeapFit.heapForTests = HeapFit.held() + HeapFit.tracked() + need + third + headroom;
		assertTrue(HeapFit.fits(planes, bytes));
		HeapFit.Claim first = HeapFit.claim(planes, bytes);
		assertNotNull(first);
		assertEquals(need, HeapFit.claimed());
		assertFalse("fits beside an open in progress", HeapFit.fits(planes, bytes));
		assertNull("claimed beside an open in progress", HeapFit.claim(planes, bytes));
		HeapFit.Claim small = HeapFit.claim(planes / 3, bytes);
		assertNotNull("a third of it did not fit beside it", small);
		first.release();
		first.release();
		assertEquals("a second release gave back more", third, HeapFit.claimed());
		small.release();
		HeapFit.Claim again = HeapFit.claim(planes, bytes);
		assertNotNull("not claimed again once given back", again);
		again.release();

		// An image ImageJ holds (a macro's, in batch mode) counts with its planes; a virtual one does not
		ImageStack stack = new ImageStack(1024, 768);
		for (int i = 0; i < 2; i++) stack.addSlice(null, new short[1024 * 768]);
		ImagePlus held = new ImagePlus("held", stack);
		ImagePlus virtual = openForcedVirtual(plainTiff("held.tif", W, H, 40));
		long heldBytes = HeapFit.needed(2, 1024 * 768 * 2);
		assertEquals(heldBytes, HeapFit.heldBy(held));
		assertEquals(0, HeapFit.heldBy(virtual));
		boolean batch = Interpreter.batchMode;
		Interpreter.batchMode = true;
		try {
			collectAll();
			long before = HeapFit.held();
			HeapFit.heapForTests = before + heldBytes + HeapFit.tracked() + need + headroom - 1;
			assertTrue(HeapFit.fits(planes, bytes));
			Interpreter.addBatchModeImage(held);
			Interpreter.addBatchModeImage(virtual);
			assertEquals(heldBytes, HeapFit.held() - before);
			assertFalse("fits beside the image ImageJ holds", HeapFit.fits(planes, bytes));
			Interpreter.removeBatchModeImage(held);
			assertTrue("does not fit once ImageJ let the image go", HeapFit.fits(planes, bytes));
		}
		finally {
			Interpreter.removeBatchModeImage(held);
			Interpreter.removeBatchModeImage(virtual);
			Interpreter.batchMode = batch;
			WindowManager.setTempCurrentImage(null);
		}
	}

	/** The read-ahead of a cold TIFF stops when the image will open as a virtual stack, and goes on when it fits. */
	@Test
	public void readAheadStopsForAnImageThatDoesNotFit() throws Exception {
		String path = plainTiff("ahead.tif", W, H, 40);
		TiffReadAhead.minBytes = 0;
		TiffReadAhead.delayNanos = 300_000_000L;
		TiffReadAhead.coldForTests = Boolean.TRUE;
		TiffReadAhead.availableForTests = 1L << 50;
		try {
			HeapFit.heapForTests = 0;
			int before = TiffReadAhead.begun.get();
			TiffImage.open(path);
			Thread.sleep(600);
			assertEquals("read ahead for an image that does not fit", before, TiffReadAhead.begun.get());
			HeapFit.heapForTests = -1;

			before = TiffReadAhead.begun.get();
			TiffImage tiff = TiffImage.open(path);
			final int b = before;
			await("a read-ahead began", () -> TiffReadAhead.begun.get() > b);
			assertEquals(40, tiff.read(0, 40).length);
		}
		finally {
			TiffReadAhead.minBytes = TiffReadAhead.MIN_BYTES;
			TiffReadAhead.delayNanos = TiffReadAhead.DELAY_NANOS;
			TiffReadAhead.coldForTests = null;
			TiffReadAhead.availableForTests = -1;
		}
		await("read-ahead threads ended", () -> {
			for (Thread t : Thread.getAllStackTraces().keySet())
				if (t.isAlive() && t.getName().startsWith(TiffReadAhead.THREAD_NAME)) return false;
			return true;
		});
	}

	/** An image the plugin opened and that is kept without being shown counts against the next open, until it is dropped. */
	@Test
	public void keptImagesCountUntilDropped() throws Exception {
		String path = plainTiff("kept.tif", W, H, 40);
		long bytes = W * H * 2, need = HeapFit.needed(40, bytes), headroom = HeapFit.headroom(40, bytes);
		collectAll();
		HeapFit.heapForTests = HeapFit.held() + HeapFit.tracked() + 2 * need + headroom - 1;
		ImagePlus kept = open(path);
		assertFalse("the first did not open into memory", kept.getStack().isVirtual());
		List<String> logged = new ArrayList<String>();
		ImagePlus beside = open(path, logged);
		assertTrue("opened into memory beside the one kept", beside.getStack().isVirtual());
		assertEquals("lines logged: " + logged, 1, logged.size());
		assertTrue(logged.get(0), logged.get(0).startsWith("kept.tif: opened as a virtual stack"));
		assertTrue(logged.get(0), logged.get(0).contains(" free"));
		for (int n = 1; n <= kept.getStackSize(); n++)
			assertArrayEquals("plane " + n, (short[]) kept.getStack().getPixels(n), (short[]) beside.getStack().getPixels(n));
		kept = null;
		beside = null;
		// The open's collection finds them gone
		ImagePlus again = open(path);
		assertFalse("did not open into memory once the others were dropped", again.getStack().isVirtual());
	}

	/** Collects until the plugin's planes that are garbage are gone. */
	private static void collectAll() throws InterruptedException {
		for (int i = 0; i < 3; i++) {
			System.gc();
			Thread.sleep(20);
		}
	}

	// --- A heap that runs short for real ---

	/**
	 * In a JVM of its own with a 96 MB heap, a TIFF of 48 planes of 512 KB
	 * (each in a 1 MB region of its own) opens into memory and is kept; let
	 * try to open into memory beside it, the second runs the heap short for
	 * real and its allocation stops before the end; decided by the heap, the
	 * third opens virtual at once; once the others are dropped, the fourth
	 * opens into memory. Fiji's other threads live on all along: a Swing
	 * timer, a thread that keeps allocating, the pool's workers.
	 */
	@Test
	public void heapThatRunsShortForRealLeavesFijiRunning() throws Exception {
		String path = plainTiff("real.tif", 512, 512, 48);
		File bin = new File(System.getProperty("java.home"), "bin");
		File java = new File(bin, "java.exe").isFile() ? new File(bin, "java.exe") : new File(bin, "java");
		List<String> command = new ArrayList<String>(Arrays.asList(java.getPath(), "-Xmx96m", "-XX:+IgnoreUnrecognizedVMOptions",
			"-XX:+UseG1GC", "-Djava.awt.headless=true"));
		for (String p : new String[] { "pfv.natives.dir", "pfv.requireNatives" })
			if (System.getProperty(p) != null) command.add("-D" + p + "=" + System.getProperty(p));
		command.addAll(Arrays.asList("-cp", System.getProperty("java.class.path"), MemoryFallbackTest.class.getName(), path));
		Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		Thread drain = new Thread(() -> {
			try (InputStream in = child.getInputStream()) {
				byte[] buf = new byte[8192];
				for (int n; (n = in.read(buf)) > 0;) output.write(buf, 0, n);
			}
			catch (Exception e) {
				// the child is gone
			}
		});
		drain.start();
		if (!child.waitFor(120, TimeUnit.SECONDS)) {
			child.destroyForcibly();
			fail("the child JVM did not end in 120 s:\n" + output);
		}
		drain.join(10_000);
		String out = output.toString();
		assertEquals("the child JVM failed:\n" + out, 0, child.exitValue());
		assertTrue(out, out.contains("RESULT ok"));
		assertTrue(out, out.contains("ran short"));
		assertFalse(out, out.contains("Exception in thread"));
		assertFalse(out, out.contains("OutOfMemoryError"));
	}

	/**
	 * The child JVM of {@link #heapThatRunsShortForRealLeavesFijiRunning}:
	 * opens the TIFF args[0] four times and checks each image and the other
	 * threads; prints "RESULT ok" and exits 0 when all is well.
	 */
	public static void main(String[] args) throws Exception {
		final List<String> died = Collections.synchronizedList(new ArrayList<String>());
		Thread.setDefaultUncaughtExceptionHandler((t, e) -> died.add(t.getName() + ": " + e));
		final AtomicInteger ticks = new AtomicInteger();
		final Object[] painted = new Object[1];
		javax.swing.Timer timer = new javax.swing.Timer(2, e -> {
			// as a progress bar would: a little garbage on every tick
			painted[0] = new int[1024];
			ticks.incrementAndGet();
		});
		timer.start();
		final AtomicBoolean done = new AtomicBoolean();
		Thread busy = new Thread(() -> {
			byte[][] kept = new byte[64][];
			for (int i = 0; !done.get(); i++) {
				kept[i & 63] = new byte[16 << 10];
				LockSupport.parkNanos(200_000);
			}
		}, "busy");
		busy.start();
		while (ticks.get() < 5) Thread.sleep(2);
		List<String> problems = new ArrayList<String>();
		String path = args[0];

		ImagePlus first = new PRT(path, false).getImp();
		if (first.getStack().isVirtual()) problems.add("the first did not open into memory");
		alive(problems, "after the first", ticks, busy, died);

		// Let it try: the heap itself has to stop it
		HeapFit.heapForTests = Long.MAX_VALUE / 4;
		ImagePlus second = new PRT(path, false).getImp();
		HeapFit.heapForTests = -1;
		if (!second.getStack().isVirtual()) problems.add("the second opened into memory");
		alive(problems, "after the second", ticks, busy, died);
		problems.addAll(differences("the second", first, second));
		second = null;

		ImagePlus third = new PRT(path, false).getImp();
		if (!third.getStack().isVirtual()) problems.add("the third opened into memory");
		alive(problems, "after the third", ticks, busy, died);
		problems.addAll(differences("the third", first, third));
		third = null;

		int[] hashes = new int[first.getStackSize()];
		for (int n = 1; n <= hashes.length; n++) hashes[n - 1] = Arrays.hashCode((short[]) first.getStack().getPixels(n));
		first = null;
		ImagePlus fourth = new PRT(path, false).getImp();
		if (fourth.getStack().isVirtual()) problems.add("the fourth did not open into memory");
		for (int n = 1; n <= hashes.length; n++)
			if (hashes[n - 1] != Arrays.hashCode((short[]) fourth.getStack().getPixels(n))) problems.add("the fourth, plane " + n);
		alive(problems, "after the fourth", ticks, busy, died);

		done.set(true);
		timer.stop();
		System.out.println("RESULT " + (problems.isEmpty() ? "ok" : "failed: " + problems));
		System.out.flush();
		System.exit(problems.isEmpty() ? 0 : 1);
	}

	/** Notes in problems what of Fiji's other threads no longer runs. */
	private static void alive(List<String> problems, String when, AtomicInteger ticks, Thread busy, List<String> died) throws InterruptedException {
		int ticked = ticks.get();
		Thread.sleep(300);
		if (ticks.get() <= ticked + 10) problems.add("the timer stopped " + when);
		if (!busy.isAlive()) problems.add("the allocating thread died " + when);
		if (IntStream.range(0, 1000).parallel().asLongStream().sum() != 499500) problems.add("the pool failed " + when);
		if (!died.isEmpty()) problems.add("threads died " + when + ": " + died);
	}

	/** How a virtual image differs from the one in memory. */
	private static List<String> differences(String what, ImagePlus mem, ImagePlus imp) {
		List<String> d = new ArrayList<String>();
		if (imp.getStackSize() != mem.getStackSize()) d.add(what + ": planes " + imp.getStackSize());
		for (int n = 1; d.isEmpty() && n <= mem.getStackSize(); n++)
			if (!Arrays.equals((short[]) mem.getStack().getPixels(n), (short[]) imp.getStack().getPixels(n))) d.add(what + ", plane " + n);
		return d;
	}
}
