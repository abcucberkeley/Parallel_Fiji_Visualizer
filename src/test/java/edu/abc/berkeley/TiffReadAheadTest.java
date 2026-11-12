package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.ImagePlus;
import ij.ImageStack;

/**
 * Reading a cold TIFF ahead (TiffReadAhead) changes nothing but the time it
 * takes: the image equals the one opened without it, its threads end with the
 * image's read, with close() and with a read that fails, and its guard keeps
 * it off where it would not help or is not safe. The test files are small and
 * in the page cache, so the tests make it take them as cold files of any size.
 */
public class TiffReadAheadTest {

	private static final int W = 64, H = 48;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@BeforeClass
	public static void nativesRequired() {
		Natives.required();
	}

	@Before
	public void anyFileIsCold() {
		TiffReadAhead.minBytes = 0;
		TiffReadAhead.delayNanos = 0;
		TiffReadAhead.coldForTests = Boolean.TRUE;
		TiffReadAhead.availableForTests = 1L << 50;
	}

	@After
	public void asOutsideTests() throws Exception {
		TiffReadAhead.minBytes = TiffReadAhead.MIN_BYTES;
		TiffReadAhead.delayNanos = TiffReadAhead.DELAY_NANOS;
		TiffReadAhead.coldForTests = null;
		TiffReadAhead.availableForTests = -1;
		TiffReadAhead.pauseMillis = 0;
		System.clearProperty(TiffReadAhead.PROPERTY);
		// A test that failed must not leave its threads to the next
		await("read-ahead threads ended", () -> threads().isEmpty());
	}

	/** The live threads of read-aheads. */
	private static List<String> threads() {
		List<String> names = new ArrayList<String>();
		for (Thread t : Thread.getAllStackTraces().keySet())
			if (t.isAlive() && t.getName().startsWith(TiffReadAhead.THREAD_NAME)) names.add(t.getName());
		return names;
	}

	private static void assertNoThreads(String when) {
		assertEquals("read-ahead threads alive " + when, new ArrayList<String>(), threads());
	}

	private static void await(String what, BooleanSupplier done) throws InterruptedException {
		long until = System.nanoTime() + 10_000_000_000L;
		while (!done.getAsBoolean()) {
			if (System.nanoTime() > until) fail("waited 10 s for: " + what);
			Thread.sleep(2);
		}
	}

	/** Waits until another read-ahead than the ones before has begun reading. */
	private static void awaitBegun(int before) throws InterruptedException {
		await("a read-ahead began", () -> TiffReadAhead.begun.get() > before);
	}

	/** A deep stack as cpp-tiff writes it (LZW, a directory per slice). */
	private String cppTiffStack(String name, int slices) {
		short[][] planes = new short[slices][];
		for (int z = 0; z < slices; z++) planes[z] = TiffFixture.plane(W, H, z, 0);
		String path = new File(tmp.getRoot(), name).getPath();
		new ParallelWriteNative().parallelWriteTiff(path, planes, W, H, slices, 16);
		return path;
	}

	/** A stack as ImageJ saves stacks over 4 GB: one directory, then every plane in one block. */
	private String imageJStack(String name, int slices, int samples) throws Exception {
		return TiffFixture.write(new File(tmp.getRoot(), name), W, H, slices, samples, true).getPath();
	}

	private static Object[] readWhole(TiffImage tiff) {
		return tiff.read(0, tiff.planes());
	}

	@Test
	public void imageEqualsTheOneOpenedWithout() throws Exception {
		String[] files = { cppTiffStack("deep.tif", 40), imageJStack("block.tif", 12, 1), imageJStack("block3.tif", 5, 3) };
		for (String path : files) {
			String what = new File(path).getName() + ": ";
			int before = TiffReadAhead.begun.get();
			TiffImage ahead = TiffImage.open(path);
			awaitBegun(before);
			Object[] with = readWhole(ahead);
			assertNoThreads("after the read");

			System.setProperty(TiffReadAhead.PROPERTY, "false");
			int now = TiffReadAhead.begun.get();
			TiffImage plain = TiffImage.open(path);
			Object[] without = readWhole(plain);
			assertEquals(what + "read ahead although switched off", now, TiffReadAhead.begun.get());
			System.clearProperty(TiffReadAhead.PROPERTY);

			assertEquals(what + "planes", without.length, with.length);
			for (int i = 0; i < with.length; i++)
				assertArrayEquals(what + "plane " + (i + 1), (short[]) without[i], (short[]) with[i]);
			for (int i = 0; i < with.length; i++)
				assertArrayEquals(what + "plane " + (i + 1) + " as written", TiffFixture.plane(W, H, i / ahead.samples, i % ahead.samples), (short[]) with[i]);
		}
	}

	/** The plugin's own open, as Fiji calls it, with the read-ahead running beside it. */
	@Test
	public void pluginOpenEqualsTheOneWithout() throws Exception {
		for (String path : new String[] { cppTiffStack("plugin.tif", 30), imageJStack("pluginBlock.tif", 9, 1) }) {
			ImagePlus with = new PRT(path, false).getImp();
			assertNoThreads("after the open");
			System.setProperty(TiffReadAhead.PROPERTY, "false");
			ImagePlus without = new PRT(path, false).getImp();
			System.clearProperty(TiffReadAhead.PROPERTY);
			String what = new File(path).getName() + ": ";
			assertEquals(what + "width", without.getWidth(), with.getWidth());
			assertEquals(what + "height", without.getHeight(), with.getHeight());
			assertEquals(what + "dimensions", without.getNChannels() + "," + without.getNSlices() + "," + without.getNFrames(),
				with.getNChannels() + "," + with.getNSlices() + "," + with.getNFrames());
			assertEquals(what + "calibration", without.getCalibration().toString(), with.getCalibration().toString());
			ImageStack a = without.getStack(), b = with.getStack();
			assertEquals(what + "planes", a.getSize(), b.getSize());
			for (int n = 1; n <= a.getSize(); n++)
				assertArrayEquals(what + "plane " + n, (short[]) a.getPixels(n), (short[]) b.getPixels(n));
		}
	}

	/** Readers that are still at it when the image's read returns are stopped by it, without waiting for them. */
	@Test
	public void noThreadOutlivesTheRead() throws Exception {
		String path = cppTiffStack("read.tif", 20);
		TiffReadAhead.pauseMillis = 60_000;
		int before = TiffReadAhead.begun.get();
		TiffImage tiff = TiffImage.open(path);
		awaitBegun(before);
		assertFalse("no read-ahead thread", threads().isEmpty());
		long t0 = System.nanoTime();
		readWhole(tiff);
		assertNoThreads("after the read");
		assertTrue("the read waited for the readers' pause", System.nanoTime() - t0 < 5_000_000_000L);
	}

	@Test
	public void noThreadOutlivesClose() throws Exception {
		String path = imageJStack("close.tif", 6, 1);
		TiffReadAhead.pauseMillis = 60_000;
		int before = TiffReadAhead.begun.get();
		TiffReadAhead ahead = TiffReadAhead.arm(path);
		assertNotNull("not armed", ahead);
		awaitBegun(before);
		ahead.close();
		assertNoThreads("after close()");
		ahead.close();

		// Closed before it began: it never does
		TiffReadAhead.delayNanos = 60_000_000_000L;
		before = TiffReadAhead.begun.get();
		ahead = TiffReadAhead.arm(path);
		assertNotNull("not armed", ahead);
		ahead.close();
		assertNoThreads("after close() before it began");
		assertEquals("began after close()", before, TiffReadAhead.begun.get());
	}

	/** The file deleted while it is read ahead: the read fails as it does without, and the threads end. */
	@Test
	public void fileDeletedWhileReadAhead() throws Exception {
		String path = cppTiffStack("deleted.tif", 20);
		TiffReadAhead.pauseMillis = 100;
		int before = TiffReadAhead.begun.get();
		TiffImage tiff = TiffImage.open(path);
		awaitBegun(before);
		assertTrue("could not delete the file", new File(path).delete());
		try {
			readWhole(tiff);
			fail("read a deleted file");
		}
		catch (RuntimeException e) {
			// as without read-ahead
		}
		assertNoThreads("after a failed read");
	}

	/** An ImageJ stack whose block of planes is cut short: the read fails, and the threads end. */
	@Test
	public void noThreadOutlivesAFailedRead() throws Exception {
		String path = imageJStack("short.tif", 10, 1);
		try (RandomAccessFile f = new RandomAccessFile(path, "rw")) {
			f.setLength(f.length() - W * H);
		}
		TiffReadAhead.pauseMillis = 60_000;
		int before = TiffReadAhead.begun.get();
		TiffImage tiff = TiffImage.open(path);
		awaitBegun(before);
		try {
			readWhole(tiff);
			fail("read a file cut short");
		}
		catch (RuntimeException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("damaged"));
		}
		assertNoThreads("after a failed read");
	}

	/** An image that is described but then not read (the folder opener only looks at its first file) is not read ahead for long. */
	@Test
	public void imageNotReadEndsIt() throws Exception {
		String path = cppTiffStack("unread.tif", 10);
		TiffReadAhead.pauseMillis = 60_000;
		int before = TiffReadAhead.begun.get();
		TiffImage.open(path);
		awaitBegun(before);
		assertFalse("no read-ahead thread", threads().isEmpty());
		await("read-ahead of an image not read ended", () -> threads().isEmpty());
	}

	/** A file that is not a TIFF: the open fails as it does without, and the threads end. */
	@Test
	public void noThreadOutlivesAFailedOpen() throws Exception {
		File f = tmp.newFile("text.tif");
		Files.write(f.toPath(), new byte[1 << 16]);
		TiffReadAhead.pauseMillis = 60_000;
		try {
			TiffImage.open(f.getPath());
			fail("opened a file that is not a TIFF");
		}
		catch (RuntimeException e) {
			// as without read-ahead
		}
		assertNoThreads("after a failed open");
	}

	@Test
	public void guardKeepsItOff() throws Exception {
		String path = cppTiffStack("guard.tif", 8);
		long length = new File(path).length();

		// Not more than a quarter of the memory the system can still give
		TiffReadAhead.availableForTests = 4 * length - 1;
		assertNull("armed for a file larger than a quarter of the memory", TiffReadAhead.arm(path));
		TiffReadAhead.availableForTests = 4 * length;
		TiffReadAhead ahead = TiffReadAhead.arm(path);
		assertNotNull("not armed for a file of a quarter of the memory", ahead);
		ahead.close();
		TiffReadAhead.availableForTests = 1L << 50;

		// Switched off, or on a folder's reader thread
		System.setProperty(TiffReadAhead.PROPERTY, "false");
		assertNull("armed although switched off", TiffReadAhead.arm(path));
		System.setProperty(TiffReadAhead.PROPERTY, "true");
		ahead = TiffReadAhead.arm(path);
		assertNotNull("not armed when switched on", ahead);
		ahead.close();
		System.clearProperty(TiffReadAhead.PROPERTY);
		final TiffReadAhead[] quiet = { null };
		final boolean[] ran = { false };
		Thread reader = new Thread(() -> {
			ProgressEstimator.quietInThisThread();
			quiet[0] = TiffReadAhead.arm(path);
			ran[0] = true;
		});
		reader.start();
		reader.join();
		assertTrue(ran[0]);
		assertNull("armed on a folder's reader thread", quiet[0]);

		// Files under 256 MB
		TiffReadAhead.minBytes = TiffReadAhead.MIN_BYTES;
		assertNull("armed for a small file", TiffReadAhead.arm(path));
		TiffReadAhead.minBytes = 0;

		// A file in the page cache is armed for, but not read
		Files.readAllBytes(new File(path).toPath());
		TiffReadAhead.coldForTests = null;
		int before = TiffReadAhead.begun.get();
		ahead = TiffReadAhead.arm(path);
		assertNotNull("not armed", ahead);
		await("cold check done", () -> threads().isEmpty());
		assertEquals("read ahead a file in the page cache", before, TiffReadAhead.begun.get());
		ahead.close();
	}

	/** The rules by numbers: what the file and its image take, what the system can give, the heap. */
	@Test
	public void guardRules() {
		long gb = 1L << 30, min = TiffReadAhead.MIN_BYTES;
		TiffReadAhead.minBytes = min;
		// before the directories are known
		assertTrue(TiffReadAhead.worthReading(min, 4 * min, 1000 * gb));
		assertFalse("a file under 256 MB", TiffReadAhead.worthReading(min - 1, 1000 * gb, 1000 * gb));
		assertTrue(TiffReadAhead.worthReading(40 * gb, 160 * gb, 750 * gb));
		assertFalse("more than a quarter of the memory", TiffReadAhead.worthReading(40 * gb, 160 * gb - 1, 750 * gb));
		assertFalse("a file larger than the heap", TiffReadAhead.worthReading(36 * gb, 1000 * gb, 32 * gb));
		assertTrue(TiffReadAhead.worthReading(32 * gb, 1000 * gb, 32 * gb));
		assertFalse("memory unknown", TiffReadAhead.worthReading(min, 0, 1000 * gb));
		// once they are
		assertTrue(TiffReadAhead.fits(36 * gb, 67 * gb, 67 * gb, 170 * gb, 750 * gb));
		assertFalse("file, planes and buffer beyond the memory", TiffReadAhead.fits(36 * gb, 67 * gb, 67 * gb, 170 * gb - 1, 750 * gb));
		assertFalse("planes larger than the heap: a virtual stack", TiffReadAhead.fits(4 * gb, 67 * gb, 67 * gb, 1000 * gb, 64 * gb));
		assertTrue(TiffReadAhead.fits(4 * gb, 64 * gb, 64 * gb, 1000 * gb, 64 * gb));
	}

	/** The walk shows an image that the memory cannot hold beside the file: stopped before the open returns. */
	@Test
	public void imageTooLargeForTheMemoryStopsIt() throws Exception {
		// Blank planes: LZW makes the file a small part of the image
		String path = new File(tmp.getRoot(), "blank.tif").getPath();
		int w = 512, h = 512, z = 16;
		short[][] planes = new short[z][w * h];
		new ParallelWriteNative().parallelWriteTiff(path, planes, w, h, z, 16);
		long length = new File(path).length();
		assertTrue("the planes compressed to " + length + " bytes", length * 8 < 2L * w * h * z);
		TiffReadAhead.availableForTests = 4 * length;
		TiffReadAhead.pauseMillis = 60_000;
		TiffImage tiff = TiffImage.open(path);
		assertNoThreads("after the open of an image the memory cannot hold");
		int before = TiffReadAhead.begun.get();
		Object[] back = readWhole(tiff);
		assertEquals(z, back.length);
		assertEquals("began with the read", before, TiffReadAhead.begun.get());
	}

	@Test
	public void memoryTheSystemCanGive() {
		long available = TiffReadAhead.available();
		assertTrue("available memory " + available, available >= 0);
		if (new File("/proc/meminfo").exists()) assertTrue("no available memory on Linux", available > 0);
	}
}
