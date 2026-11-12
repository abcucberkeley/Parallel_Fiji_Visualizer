package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.ImagePlus;
import ij.ImageStack;
import ij.ParallelVirtualStack;
import ij.VirtualStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

/**
 * Saves go in runs of planes, each copied to native memory and written on its
 * own (TIFF: a run of slices, appended to the file; Zarr: whole chunk rows
 * along z, then channels, then time points). Written in runs of a few planes,
 * every save is the file, or the chunk files, of a save in one piece, byte for
 * byte, for 8-, 16- and 32-bit stacks and hyperstacks, and of a virtual stack
 * too, whose read-ahead is off while it is saved; a save that fails leaves
 * nothing under the name it was to have, and an older file of that name as it
 * was.
 */
public class SlabSaveTest {

	/** The stack's read-ahead threads */
	private static final String READ_AHEAD = "PFV block read-ahead";

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@BeforeClass
	public static void nativesRequired() {
		Natives.required();
	}

	@After
	public void defaultRuns() {
		ParallelWriteNative.setRunBytes(0);
		System.clearProperty("pfv.forceVirtual");
	}

	/** Value i of plane n (0-based): distinct, with every plane in [zeroFrom, zeroTo) zero (chunks of zeros are not written). */
	private static double value(int bits, int n, int i, int zeroFrom, int zeroTo) {
		if (n >= zeroFrom && n < zeroTo) return 0;
		if (bits == 8) return (n * 37 + i * 11) % 251 + 1;
		if (bits == 16) return (n * 1009 + i * 7) % 65000 + 1;
		return n * 1009 + i * 0.25 + 0.125;
	}

	/** A stack of c * z * t planes of w x h pixels in ImageJ's czt order. */
	private static ImagePlus image(int bits, int w, int h, int c, int z, int t, int zeroFrom, int zeroTo) {
		ImageStack stack = new ImageStack(w, h);
		for (int n = 0; n < c * z * t; n++) {
			ImageProcessor ip = bits == 8 ? new ByteProcessor(w, h) : bits == 16 ? new ShortProcessor(w, h) : new FloatProcessor(w, h);
			for (int i = 0; i < w * h; i++) ip.setf(i, (float) value(bits, n, i, zeroFrom, zeroTo));
			stack.addSlice(ip);
		}
		ImagePlus imp = new ImagePlus("image", stack);
		imp.setDimensions(c, z, t);
		return imp;
	}

	private static Object[] planes(ImagePlus imp) {
		Object[] p = new Object[imp.getStackSize()];
		for (int n = 0; n < p.length; n++) p[n] = imp.getStack().getPixels(n + 1);
		return p;
	}

	private static long planeBytes(ImagePlus imp) {
		return (long) imp.getWidth() * imp.getHeight() * (imp.getBitDepth() / 8);
	}

	private static void assertSamePlanes(String what, ImagePlus expected, ImagePlus read) {
		assertNotNull(what + ": nothing read back", read);
		assertEquals(what + ": planes", expected.getStackSize(), read.getStackSize());
		assertEquals(what + ": width", expected.getWidth(), read.getWidth());
		assertEquals(what + ": height", expected.getHeight(), read.getHeight());
		for (int n = 1; n <= expected.getStackSize(); n++) {
			Object a = expected.getStack().getPixels(n), b = read.getStack().getPixels(n);
			if (a instanceof byte[]) assertArrayEquals(what + ": plane " + n, (byte[]) a, (byte[]) b);
			else if (a instanceof short[]) assertArrayEquals(what + ": plane " + n, (short[]) a, (short[]) b);
			else assertArrayEquals(what + ": plane " + n, (float[]) a, (float[]) b, 0f);
		}
	}

	/** Every file under dir, by its path relative to dir ('/' between names), and its bytes. */
	private static Map<String, byte[]> files(File dir) throws IOException {
		Map<String, byte[]> files = new TreeMap<>();
		Path root = dir.toPath();
		try (Stream<Path> all = Files.walk(root)) {
			for (Path p : (Iterable<Path>) all::iterator)
				if (Files.isRegularFile(p)) files.put(root.relativize(p).toString().replace(File.separatorChar, '/'), Files.readAllBytes(p));
		}
		return files;
	}

	private static void assertSameFiles(String what, Map<String, byte[]> expected, Map<String, byte[]> actual) {
		assertEquals(what + ": the files", expected.keySet(), actual.keySet());
		for (Map.Entry<String, byte[]> e : expected.entrySet())
			assertArrayEquals(what + ": " + e.getKey(), e.getValue(), actual.get(e.getKey()));
	}

	/** What a folder holds, its parts left by saves included (they are hidden, named after the file). */
	private static List<String> contents(File dir) {
		String[] names = dir.list();
		List<String> l = new ArrayList<>(names == null ? Arrays.<String>asList() : Arrays.asList(names));
		java.util.Collections.sort(l);
		return l;
	}

	// ---- TIFF -----------------------------------------------------------

	/** A TIFF saved by PWT in runs of perRun planes, its bytes and planes checked against the one-piece write. */
	private void assertTiffInRunsIsOnePiece(String name, ImagePlus imp, int perRun) throws IOException {
		File one = new File(tmp.getRoot(), name + "_one.tif");
		new ParallelWriteNative().parallelWriteTiff(one.getPath(), planes(imp), imp.getWidth(), imp.getHeight(), imp.getStackSize(), imp.getBitDepth());
		File runs = new File(tmp.getRoot(), name + "_runs.tif");
		ParallelWriteNative.setRunBytes(perRun * planeBytes(imp));
		PWT.write(imp, runs.getPath());
		ParallelWriteNative.setRunBytes(0);
		assertArrayEquals(name + " in runs of " + perRun + ": the file's bytes", Files.readAllBytes(one.toPath()), Files.readAllBytes(runs.toPath()));
		assertSamePlanes(name + " in runs of " + perRun, imp, new PRT(runs.getPath(), false).getImp());
	}

	@Test
	public void tiffsInRunsAreTheFileInOnePiece() throws Exception {
		for (int bits : new int[] { 8, 16, 32 }) {
			// 2048 x 600: the rows per strip cpp-tiff picks depend on the slice
			// count (24 rows for 10 slices of 16 bits, 16 for 3), so every run
			// must be given the whole image's
			ImagePlus imp = image(bits, 2048, 600, 1, 10, 1, 4, 5);
			for (int perRun : new int[] { 1, 3, 10 })
				assertTiffInRunsIsOnePiece("stack" + bits, imp, perRun);
		}
		// Slices of one strip, runs of one slice and a partly filled last run
		ImagePlus small = image(16, 40, 30, 1, 7, 1, -1, -1);
		for (int perRun : new int[] { 1, 2, 3, 7 })
			assertTiffInRunsIsOnePiece("small", small, perRun);
	}

	/** cpp-tiff writes a hyperstack's planes in ImageJ's order, as a plain stack. */
	@Test
	public void tiffHyperstacksInRunsAreTheFileInOnePiece() throws Exception {
		ImagePlus imp = image(16, 64, 48, 2, 3, 2, -1, -1);
		for (int perRun : new int[] { 1, 5, 12 })
			assertTiffInRunsIsOnePiece("hyperstack", imp, perRun);
	}

	/** An image that fits one run is written by the same call as before (which picks the strips itself). */
	@Test
	public void aTiffOfOneRunIsWrittenAsBefore() throws Exception {
		ImagePlus imp = image(16, 2048, 600, 1, 10, 1, -1, -1);
		File one = tmp.newFile("one.tif");
		new ParallelWriteNative().parallelWriteTiff(one.getPath(), planes(imp), 2048, 600, 10, 16);
		File saved = new File(tmp.getRoot(), "saved.tif");
		PWT.write(imp, saved.getPath());
		assertArrayEquals(Files.readAllBytes(one.toPath()), Files.readAllBytes(saved.toPath()));
		assertEquals("nothing else is left in the folder", Arrays.asList("one.tif", "saved.tif"), contents(tmp.getRoot()));
	}

	/** As cpp-tiff did, the folders of the file are made when missing. */
	@Test
	public void tiffsAreSavedIntoNewFolders() throws Exception {
		ImagePlus imp = image(8, 20, 10, 1, 5, 1, -1, -1);
		File file = new File(tmp.getRoot(), "new" + File.separator + "folder" + File.separator + "saved.tif");
		ParallelWriteNative.setRunBytes(2 * planeBytes(imp));
		PWT.write(imp, file.getPath());
		assertSamePlanes("in a new folder", imp, new PRT(file.getPath(), false).getImp());
		assertEquals(Arrays.asList("saved.tif"), contents(file.getParentFile()));
	}

	// ---- Zarr -----------------------------------------------------------

	/** The chunk files and metadata of imp written in one piece by the writer as it was before runs. */
	private Map<String, byte[]> zarrInOnePieceAsBefore(ImagePlus imp, String name) throws IOException {
		File dir = new File(tmp.getRoot(), name + "_before.zarr");
		if (dir.exists()) return files(dir);
		int w = imp.getWidth(), h = imp.getHeight(), c = imp.getNChannels(), z = imp.getNSlices(), t = imp.getNFrames();
		long plane = (long) w * h;
		boolean hyper = c > 1 || t > 1;
		long[] shape = hyper ? new long[] { h, w, z, c, t } : new long[] { h, w, z };
		long[] chunks = hyper ? new long[] { Math.min(256, h), Math.min(256, w), Math.min(256, z), 1, 1 }
			: new long[] { Math.min(256, h), Math.min(256, w), Math.min(256, z) };
		long[] strides = hyper ? new long[] { w, 1, plane * c, plane, plane * c * z } : new long[] { w, 1, plane };
		new ParallelWriteNative().parallelWriteZarrArray(dir.getPath(), planes(imp), shape, chunks, strides, "zstd", imp.getBitDepth());
		return files(dir);
	}

	/** imp saved by PWZ in runs of runBytes: the files of a save in one piece, and of the writer before runs. */
	private void assertZarrInRunsIsOnePiece(String what, ImagePlus imp, long runBytes) throws Exception {
		File one = new File(tmp.getRoot(), what + "_one.zarr");
		if (!one.exists()) PWZ.write(imp, one);
		Map<String, byte[]> onePiece = files(one);
		File runs = new File(tmp.getRoot(), what + "_" + runBytes + ".zarr");
		ParallelWriteNative.setRunBytes(runBytes);
		PWZ.write(imp, runs);
		ParallelWriteNative.setRunBytes(0);
		Map<String, byte[]> inRuns = files(runs);
		assertSameFiles(what + " in runs of " + runBytes + " bytes", onePiece, inRuns);
		// The same chunks and metadata as before (the axis names were always written apart)
		Map<String, byte[]> before = zarrInOnePieceAsBefore(imp, what);
		inRuns.remove(ZarrMetadata.V2_ATTRIBUTES);
		assertSameFiles(what + " against the writer before runs", before, inRuns);
		assertSamePlanes(what + " in runs of " + runBytes + " bytes", imp, new PRZ(runs.getPath(), false).getImp());
	}

	@Test
	public void zarrStacksInRunsAreTheChunksInOnePiece() throws Exception {
		for (int bits : new int[] { 8, 16, 32 }) {
			// Three chunk rows along z (256, 256 and 8 planes), two chunks along x
			// (one of them 1 pixel wide), and the second row zero: chunks of
			// zeros are not written at all
			ImagePlus imp = image(bits, 257, 9, 1, 520, 1, 256, 512);
			long row = 256 * planeBytes(imp);
			for (long runBytes : new long[] { 1, row, 2 * row - 1, 2 * row, 520 * planeBytes(imp) })
				assertZarrInRunsIsOnePiece("stack" + bits, imp, runBytes);
		}
		// Two chunks along y
		ImagePlus tall = image(16, 20, 257, 1, 300, 1, -1, -1);
		assertZarrInRunsIsOnePiece("tall", tall, 1);
	}

	/** Runs of chunk rows of one channel, all of z, several channels, several time points. */
	@Test
	public void zarrHyperstacksInRunsAreTheChunksInOnePiece() throws Exception {
		final int c = 3, z = 300, t = 2;
		ImagePlus imp = image(16, 12, 10, c, z, t, 2 * c * 10, 3 * c * 10);
		long p = planeBytes(imp);
		for (long planes : new long[] { 1, 256, 300, 2 * 300, 3 * 300 + 299, 2 * 3 * 300 })
			assertZarrInRunsIsOnePiece("hyperstack", imp, planes * p);
		// Fewer planes than a chunk row
		ImagePlus shallow = image(32, 12, 10, 2, 5, 3, -1, -1);
		for (long planes : new long[] { 1, 5, 10, 11, 30 })
			assertZarrInRunsIsOnePiece("shallow", shallow, planes * planeBytes(shallow));
	}

	@Test
	public void runsGrowAlongZThenChannelsThenTimePoints() {
		assertArrayEquals(new int[] { 256, 1, 1 }, PWZ.runOf(256, 256, 600, 3, 2));
		assertArrayEquals(new int[] { 512, 1, 1 }, PWZ.runOf(599, 256, 600, 3, 2));
		assertArrayEquals(new int[] { 600, 1, 1 }, PWZ.runOf(600, 256, 600, 3, 2));
		assertArrayEquals(new int[] { 600, 2, 1 }, PWZ.runOf(1799, 256, 600, 3, 2));
		assertArrayEquals(new int[] { 600, 3, 1 }, PWZ.runOf(1800, 256, 600, 3, 2));
		assertArrayEquals(new int[] { 600, 3, 2 }, PWZ.runOf(1L << 40, 256, 600, 3, 2));
		assertArrayEquals(new int[] { 5, 2, 3 }, PWZ.runOf(30, 5, 5, 2, 3));
	}

	/** An empty folder of the name (made in the file chooser) gives way; an array saved before is written over. */
	@Test
	public void zarrsReplaceEmptyFoldersAndArraysSavedBefore() throws Exception {
		ImagePlus first = image(16, 30, 20, 1, 300, 1, -1, -1), second = image(16, 30, 20, 1, 300, 1, 0, 256);
		ParallelWriteNative.setRunBytes(1);
		File dir = tmp.newFolder("chosen.zarr");
		PWZ.write(first, dir);
		assertSamePlanes("into an empty folder", first, new PRZ(dir.getPath(), false).getImp());
		PWZ.write(second, dir);
		assertSamePlanes("over an array saved before", second, new PRZ(dir.getPath(), false).getImp());
		assertEquals("nothing else is left in the folder", Arrays.asList("chosen.zarr"), contents(tmp.getRoot()));
		File nested = new File(tmp.getRoot(), "new" + File.separator + "saved.zarr");
		PWZ.write(first, nested);
		assertSamePlanes("into a new folder", first, new PRZ(nested.getPath(), false).getImp());
		assertEquals(Arrays.asList("saved.zarr"), contents(nested.getParentFile()));
	}

	// ---- Virtual stacks -------------------------------------------------

	/** Threads named name, alive now. */
	private static List<Thread> threads(String name) {
		List<Thread> l = new ArrayList<>();
		for (Thread th : Thread.getAllStackTraces().keySet())
			if (th.getName().equals(name) && th.isAlive()) l.add(th);
		return l;
	}

	/** Waits for the stack's reads ahead to end, before the test's folder (its file) is deleted: Windows deletes no open file. */
	private static void awaitReadsAhead() throws InterruptedException {
		for (Thread th : threads(READ_AHEAD)) {
			th.join(60000);
			assertFalse("a read ahead did not end", th.isAlive());
		}
	}

	/**
	 * The plugin's virtual stack of a TIFF saved as TIFF and as Zarr in runs
	 * (its planes fetched for each run, its blocks of 2 planes crossed): the
	 * files of the image in memory, with no read ahead during either save.
	 */
	@Test
	public void virtualStacksSaveInRuns() throws Exception {
		ImagePlus inMemory = image(16, 30, 20, 1, 300, 1, -1, -1);
		File source = new File(tmp.getRoot(), "source.tif");
		new ParallelWriteNative().parallelWriteTiff(source.getPath(), planes(inMemory), 30, 20, 300, 16);
		AtomicInteger readsAhead = new AtomicInteger();
		Semaphore aheadRead = new Semaphore(0);
		ParallelVirtualStack stack = new ParallelVirtualStack(TiffImage.open(source.getPath())) {
			@Override
			protected Object[] readBlock(int c, int t, int start, int end, boolean showProgress) {
				Object[] planes = super.readBlock(c, t, start, end, showProgress);
				if (!showProgress) {
					readsAhead.incrementAndGet();
					aheadRead.release();
				}
				return planes;
			}

			@Override
			protected long freeHeapBytes() {
				return Long.MAX_VALUE / 2;   // a read ahead is never left out for the heap the test JVM has free
			}
		};
		stack.setMaxBlockPlanes(2);
		ImagePlus virtual = new ImagePlus("virtual", stack);
		try {
			File tiffOne = new File(tmp.getRoot(), "one.tif"), tiffRuns = new File(tmp.getRoot(), "runs.tif");
			PWT.write(inMemory, tiffOne.getPath());
			ParallelWriteNative.setRunBytes(7 * planeBytes(inMemory));
			PWT.write(virtual, tiffRuns.getPath());
			assertArrayEquals("TIFF", Files.readAllBytes(tiffOne.toPath()), Files.readAllBytes(tiffRuns.toPath()));

			File zarrOne = new File(tmp.getRoot(), "one.zarr"), zarrRuns = new File(tmp.getRoot(), "runs.zarr");
			ParallelWriteNative.setRunBytes(0);
			PWZ.write(inMemory, zarrOne);
			ParallelWriteNative.setRunBytes(1);
			PWZ.write(virtual, zarrRuns);
			assertSameFiles("Zarr", files(zarrOne), files(zarrRuns));
			assertEquals("reads ahead during the saves", 0, readsAhead.get());

			// Scrolling reads ahead again after the saves
			stack.getProcessor(1);
			stack.getProcessor(2);
			assertTrue("no read ahead after the saves", aheadRead.tryAcquire(60, TimeUnit.SECONDS));
		}
		finally {
			awaitReadsAhead();
		}
	}

	/** A virtual stack (ImageJ's class) of the planes of inMemory, which fails to give plane failAt (0: never). */
	private static ImagePlus failingVirtual(ImagePlus inMemory, int failAt) {
		ImageStack stack = new VirtualStack(inMemory.getWidth(), inMemory.getHeight(), null, null) {
			@Override
			public int getSize() {
				return inMemory.getStackSize();
			}

			@Override
			public int getBitDepth() {
				return inMemory.getBitDepth();
			}

			@Override
			public ImageProcessor getProcessor(int n) {
				if (n == failAt) throw new IllegalStateException("plane " + n + " fails on purpose");
				return inMemory.getStack().getProcessor(n).duplicate();
			}
		};
		return new ImagePlus("virtual", stack);
	}

	/** ImageJ's own virtual stacks save in runs too. */
	@Test
	public void imageJsVirtualStacksSaveInRuns() throws Exception {
		ImagePlus inMemory = image(32, 30, 20, 1, 300, 1, -1, -1);
		ImagePlus virtual = failingVirtual(inMemory, 0);
		ParallelWriteNative.setRunBytes(1);
		File tiff = new File(tmp.getRoot(), "virtual.tif"), zarr = new File(tmp.getRoot(), "virtual.zarr");
		PWT.write(virtual, tiff.getPath());
		PWZ.write(virtual, zarr);
		assertSamePlanes("TIFF", inMemory, new PRT(tiff.getPath(), false).getImp());
		assertSamePlanes("Zarr", inMemory, new PRZ(zarr.getPath(), false).getImp());
	}

	// ---- Failures -------------------------------------------------------

	private static void assertFails(Runnable save, String what) {
		try {
			save.run();
			fail(what + " did not fail");
		}
		catch (RuntimeException e) {
			// as it should
		}
	}

	/** A save that fails after runs were written leaves no file under its name, and no part of it. */
	@Test
	public void aFailedSaveLeavesNoFile() throws Exception {
		ImagePlus inMemory = image(16, 6, 5, 1, 600, 1, -1, -1);
		ParallelWriteNative.setRunBytes(1);
		File tiff = new File(tmp.getRoot(), "failed.tif"), zarr = new File(tmp.getRoot(), "failed.zarr");
		// TIFF: in runs of one slice, the 400th fails; Zarr: in chunk rows of 256 planes
		assertFails(() -> PWT.write(failingVirtual(inMemory, 400), tiff.getPath()), "the TIFF save");
		assertFails(() -> PWZ.write(failingVirtual(inMemory, 400), zarr), "the Zarr save");
		assertEquals("what the failed saves left", Arrays.<String>asList(), contents(tmp.getRoot()));
	}

	/** A plane the writers refuse (of the wrong size) fails the save in the native copy: nothing is left either. */
	@Test
	public void aRefusedPlaneLeavesNoFile() throws Exception {
		ImagePlus inMemory = image(16, 6, 5, 1, 300, 1, -1, -1);
		ImageStack bad = new VirtualStack(6, 5, null, null) {
			@Override
			public int getSize() {
				return 300;
			}

			@Override
			public int getBitDepth() {
				return 16;
			}

			@Override
			public ImageProcessor getProcessor(int n) {
				return inMemory.getStack().getProcessor(n);
			}

			@Override
			public Object getPixels(int n) {
				return n == 280 ? new short[7] : inMemory.getStack().getPixels(n);
			}
		};
		ParallelWriteNative.setRunBytes(1);
		File tiff = new File(tmp.getRoot(), "refused.tif"), zarr = new File(tmp.getRoot(), "refused.zarr");
		try {
			PWT.write(new ImagePlus("bad", bad), tiff.getPath());
			fail("a TIFF was written from a plane of the wrong size");
		}
		catch (RuntimeException e) {
			assertTrue(String.valueOf(e), String.valueOf(e.getMessage()).startsWith("Plane 280 of the image is not 30 values of 16 bits"));
		}
		assertFails(() -> PWZ.write(new ImagePlus("bad", bad), zarr), "the Zarr save");
		assertEquals("what the refused saves left", Arrays.<String>asList(), contents(tmp.getRoot()));
	}

	private static void assertRefused(Runnable call, String what, String says) {
		try {
			call.run();
			fail(what + " was not refused");
		}
		catch (RuntimeException e) {
			assertTrue(what + ": " + e, String.valueOf(e.getMessage()).contains(says));
		}
	}

	/** The native copy and writes refuse planes they were not given and what lies past their buffer, not read or written past. */
	@Test
	public void theNativeCopyAndWritesStayInTheirBuffer() {
		try (ParallelWriteNative.PlaneBuffer b = ParallelWriteNative.PlaneBuffer.allocate(30, 16, 4, 1)) {
			ParallelWriteNative n = b.natives;
			assertEquals(4, b.planes);
			assertEquals(240, b.capacity());
			short[] ok = new short[30];
			File f = new File(tmp.getRoot(), "refused.tif"), dir = new File(tmp.getRoot(), "refused.zarr");
			assertRefused(() -> n.putPlanes(b.address(), b.capacity(), new Object[] { ok, new short[7] }, 2, 30, 16, 0, 1), "a short plane", "Plane 2 to write");
			assertRefused(() -> n.putPlanes(b.address(), b.capacity(), new Object[] { ok, new byte[30] }, 2, 30, 16, 0, 1), "a plane of bytes", "Plane 2 to write");
			assertRefused(() -> n.putPlanes(b.address(), b.capacity(), new Object[] { ok }, 2, 30, 16, 0, 1), "a plane missing", "Expected 2 planes");
			assertRefused(() -> n.putPlanes(b.address(), b.capacity(), new Object[] { ok, ok }, 2, 30, 16, 3, 1), "a slot past the end", "do not fit");
			assertRefused(() -> n.putPlanes(b.address(), b.capacity(), new Object[] { ok, ok }, 2, 30, 16, 0, 4), "a stride past the end", "do not fit");
			assertRefused(() -> n.putPlanes(b.address(), b.capacity(), new Object[] { ok, ok }, 2, 30, 16, 1, Long.MAX_VALUE / 2), "a stride that overflows", "do not fit");
			n.putPlanes(b.address(), b.capacity(), new Object[] { ok, ok }, 2, 30, 16, 1, 2);
			assertRefused(() -> n.writeTiffPlanes(f.getPath(), b.address(), b.capacity(), 6, 5, 5, 16, false, 5), "slices past the end", "not all in their native buffer");
			assertRefused(() -> n.writeZarrPlanes(dir.getPath(), b.address(), b.capacity(), new long[] { 5, 6, 8 }, new long[] { 5, 6, 2 },
				new long[] { 0, 0, 0 }, new long[] { 5, 6, 5 }, new long[] { 6, 1, 30 }, "zstd", 16), "a region past the buffer", "not all in its native buffer");
			assertRefused(() -> n.writeZarrPlanes(dir.getPath(), b.address(), b.capacity(), new long[] { 5, 6, 8 }, new long[] { 5, 6, 2 },
				new long[] { 0, 0, 1 }, new long[] { 5, 6, 3 }, new long[] { 6, 1, 30 }, "zstd", 16), "a region of parts of chunks", "not whole chunks");
			assertRefused(() -> n.writeZarrPlanes(dir.getPath(), b.address(), b.capacity(), new long[] { 5, 6, 8 }, new long[] { 5, 6, 2 },
				new long[] { 0, 0, 0 }, new long[] { 5, 6, 2 }, new long[] { 6, 1, Long.MAX_VALUE / 4 }, "zstd", 16), "a stride that overflows", "not all in its native buffer");
			assertEquals("nothing was written", Arrays.<String>asList(), contents(tmp.getRoot()));
			b.close();
			assertRefused(() -> b.address(), "a freed buffer", "freed");
		}
	}

	/** A failed save over a file saved before leaves that file as it was. */
	@Test
	public void aFailedSaveKeepsTheFileSavedBefore() throws Exception {
		ImagePlus inMemory = image(16, 6, 5, 1, 20, 1, -1, -1);
		File tiff = new File(tmp.getRoot(), "kept.tif");
		PWT.write(inMemory, tiff.getPath());
		byte[] before = Files.readAllBytes(tiff.toPath());
		ParallelWriteNative.setRunBytes(1);
		assertFails(() -> PWT.write(failingVirtual(image(16, 6, 5, 1, 20, 1, 0, 20), 15), tiff.getPath()), "the save over it");
		assertArrayEquals("the file saved before", before, Files.readAllBytes(tiff.toPath()));
		assertEquals(Arrays.asList("kept.tif"), contents(tmp.getRoot()));
	}
}
