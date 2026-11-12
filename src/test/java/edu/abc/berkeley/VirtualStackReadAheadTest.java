package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.ImagePlus;
import ij.ParallelVirtualStack;

/**
 * The virtual stack reads the block after the current one (before it, when
 * scrolling back) in the background: every plane stays what it was without
 * reading ahead, in any order of requests and after slices are deleted; a
 * request for the block under way waits for it instead of reading it again;
 * reads never overlap; a read ahead is left out when the heap is short and
 * stops for good when it runs out; and no thread outlives its read.
 */
public class VirtualStackReadAheadTest {

	private static final int W = 6, H = 5;
	/** The stack's read-ahead threads (TiffReadAhead's are "PFV read-ahead ...") */
	private static final String THREAD = "PFV block read-ahead";
	/** t, c, z, y, x */
	private static final long[] SHAPE = { 2, 2, 7, H, W };
	private static final int TIFF_SLICES = 13;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@BeforeClass
	public static void nativesRequired() {
		Natives.required();
	}

	private ZarrImage zarr() throws Exception {
		File dir = new File(tmp.getRoot(), "ahead.zarr");
		if (!dir.exists()) ZarrFixture.writeV3(dir, SHAPE, new long[] { 1, 1, 2, H, W }, 'C', new String[] { "t", "c", "z", "y", "x" });
		return ZarrImage.open(dir);
	}

	private TiffImage tiff() throws Exception {
		File f = new File(tmp.getRoot(), "ahead.tif");
		if (!f.exists()) TiffFixture.write(f, W, H, TIFF_SLICES, 1, false);
		return TiffImage.open(f.getPath());
	}

	/** Plane n (1-based, czt order) as the files hold it. */
	private static short[] expected(boolean zarr, int n) {
		if (!zarr) return TiffFixture.plane(W, H, n - 1, 0);
		int c = (n - 1) % 2, z = (n - 1) / 2 % 7, t = (n - 1) / 14;
		return ZarrFixture.plane(SHAPE, ZarrAxisOrder.roles("tczyx"), c, z, t);
	}

	/**
	 * A stack whose reads are counted and checked never to overlap, optionally
	 * gated (each read ahead waits for a permit, to keep it under way while
	 * the test asks for planes), short of heap, or out of memory in the reads
	 * ahead.
	 */
	private static final class Probe {
		final AtomicInteger inFlight = new AtomicInteger(), overlaps = new AtomicInteger();
		final AtomicInteger onRequest = new AtomicInteger(), ahead = new AtomicInteger(), aheadOffThread = new AtomicInteger();
		volatile Semaphore aheadPermits;                // null: reads ahead go at once
		final Semaphore aheadEntered = new Semaphore(0);  // a permit per read ahead begun
		volatile boolean heapShort, aheadOutOfMemory;

		ParallelVirtualStack stack(ZarrImage zarr) {
			return new ParallelVirtualStack(zarr, 0, 0, 0, W, H, (int) SHAPE[2]) {
				@Override
				protected Object[] readBlock(int c, int t, int start, int end, boolean showProgress) {
					return probe(showProgress, () -> super.readBlock(c, t, start, end, showProgress));
				}

				@Override
				protected long freeHeapBytes() {
					// The heap as the test sets it, not as earlier tests left it
					return heapShort ? 0 : Long.MAX_VALUE / 4;
				}
			};
		}

		ParallelVirtualStack stack(TiffImage tiff) {
			return new ParallelVirtualStack(tiff) {
				@Override
				protected Object[] readBlock(int c, int t, int start, int end, boolean showProgress) {
					return probe(showProgress, () -> super.readBlock(c, t, start, end, showProgress));
				}

				@Override
				protected long freeHeapBytes() {
					// The heap as the test sets it, not as earlier tests left it
					return heapShort ? 0 : Long.MAX_VALUE / 4;
				}
			};
		}

		interface Read {
			Object[] run();
		}

		Object[] probe(boolean onRequestRead, Read read) {
			if (inFlight.incrementAndGet() > 1) overlaps.incrementAndGet();
			try {
				if (onRequestRead) onRequest.incrementAndGet();
				else {
					ahead.incrementAndGet();
					if (!Thread.currentThread().getName().equals(THREAD)) aheadOffThread.incrementAndGet();
					if (aheadOutOfMemory) throw new OutOfMemoryError("test");
					aheadEntered.release();
					Semaphore permits = aheadPermits;
					if (permits != null && !permits.tryAcquire(60, TimeUnit.SECONDS)) throw new IllegalStateException("the test never let the read ahead go");
				}
				return read.run();
			}
			catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
			finally {
				inFlight.decrementAndGet();
			}
		}
	}

	private static short[] pixels(ParallelVirtualStack stack, int n) {
		return (short[]) stack.getProcessor(n).getPixels();
	}

	private static List<Thread> readersAlive() {
		List<Thread> alive = new ArrayList<Thread>();
		for (Thread t : Thread.getAllStackTraces().keySet())
			if (t.getName().equals(THREAD) && t.isAlive()) alive.add(t);
		return alive;
	}

	/** Waits (up to 30 s) for every read-ahead thread of the stacks to end; true if they did. */
	private static boolean readersEnd() throws InterruptedException {
		long until = System.currentTimeMillis() + 30000;
		while (System.currentTimeMillis() < until) {
			List<Thread> alive = readersAlive();
			if (alive.isEmpty()) return true;
			for (Thread t : alive) t.join(100);
		}
		return readersAlive().isEmpty();
	}

	/**
	 * The scroll of PluginReadTest.zarrVirtualStackBlockCache with reading
	 * ahead (blocks {1,2} {3,4} {5,6} {7,...} of one channel): only the first
	 * block and the one after the jump at the end are read on request, the
	 * others ahead of the scroll, once each but for {1,2}, pushed out by
	 * {7,8} and read again (behind) on the way back.
	 */
	@Test
	public void scrollingReadsEachBlockOnceAhead() throws Exception {
		for (boolean isZarr : new boolean[] { true, false }) {
			Probe probe = new Probe();
			ParallelVirtualStack stack = isZarr ? probe.stack(zarr()) : probe.stack(tiff());
			stack.setMaxBlockPlanes(2);
			// Slices of channel 0, time point 0 (zarr), or the first six (TIFF)
			int[] order         = { 1, 2, 3, 4, 5, 6,   6, 5, 4, 3, 2, 1,   5 };
			int[] expectedReads = { 1, 2, 3, 3, 4, 4,   4, 4, 5, 5, 5, 5,   6 };
			for (int i = 0; i < order.length; i++) {
				int n = isZarr ? 1 + 2 * (order[i] - 1) : order[i];
				assertArrayEquals("slice " + n, expected(isZarr, n), pixels(stack, n));
				assertEquals("block reads after step " + i + " (slice " + n + ")", expectedReads[i], stack.getBlockReads());
				// Scrolling slower than the reads: each read ahead ends before the next plane is asked for
				assertTrue("read-ahead threads still running", readersEnd());
			}
			assertEquals("reads ahead", 4, probe.ahead.get());
			assertEquals("reads that overlapped", 0, probe.overlaps.get());
			assertEquals("reads ahead not on the read-ahead thread", 0, probe.aheadOffThread.get());
			assertEquals("reads on request", 2, probe.onRequest.get());
		}
	}

	/**
	 * Random walks (steps of one or two planes either way, within a channel
	 * and time point), jumps and channel switches, over blocks of 1, 2 and 4
	 * planes: every plane equals the file's and the stack's without reading
	 * ahead, also after slices are deleted from the view; reads never overlap.
	 */
	@Test
	public void randomOrdersReadTheSamePlanes() throws Exception {
		for (boolean isZarr : new boolean[] { true, false }) {
			for (int blockPlanes : new int[] { 1, 2, 3, 4 }) {
				for (boolean deleted : new boolean[] { false, true }) {
					Probe probe = new Probe();
					ParallelVirtualStack ahead = isZarr ? probe.stack(zarr()) : probe.stack(tiff());
					ParallelVirtualStack plain = isZarr ? new ParallelVirtualStack(zarr(), 0, 0, 0, W, H, (int) SHAPE[2]) : new ParallelVirtualStack(tiff());
					plain.setReadAhead(false);
					List<Integer> planes = new ArrayList<Integer>();
					for (int n = 1; n <= ahead.getSize(); n++) planes.add(n);
					if (deleted) {
						// Slices deleted from the view: the image's own planes are read ahead
						for (int n : new int[] { 9, 4, 1 }) {
							ahead.deleteSlice(n);
							plain.deleteSlice(n);
							planes.remove(n - 1);
						}
					}
					int size = ahead.getSize(), step = isZarr ? 2 : 1;
					ahead.setMaxBlockPlanes(blockPlanes);
					plain.setMaxBlockPlanes(blockPlanes);
					Random random = new Random(blockPlanes * 31 + (isZarr ? 7 : 0) + (deleted ? 1 : 0));
					int s = 1;
					for (int i = 0; i < 150; i++) {
						int r = random.nextInt(20);
						if (r == 0) s = 1 + random.nextInt(size);                         // a jump
						else if (r == 1 && isZarr) s = s % 2 == 0 ? s - 1 : Math.min(size, s + 1);  // the other channel
						else s += (r % 2 == 0 ? 1 : -1) * step * (r < 15 ? 1 : 2);        // scrolling
						s = Math.max(1, Math.min(size, s));
						String what = (isZarr ? "zarr" : "tiff") + ", blocks of " + blockPlanes + (deleted ? ", slices deleted" : "") + ", step " + i + ", slice " + s;
						short[] p = pixels(ahead, s);
						assertArrayEquals(what, expected(isZarr, planes.get(s - 1)), p);
						assertArrayEquals(what + " without reading ahead", pixels(plain, s), p);
					}
					assertEquals("reads that overlapped", 0, probe.overlaps.get());
					assertTrue("nothing was read ahead", ahead.getBlockReads() > probe.onRequest.get());
				}
			}
		}
		assertTrue("read-ahead threads still running", readersEnd());
	}

	/** pixels(stack, n) on a thread of its own, its result or error in the references. */
	private static Thread request(final ParallelVirtualStack stack, final int n, final AtomicReference<short[]> result, final AtomicReference<Throwable> error) {
		Thread t = new Thread(() -> {
			try {
				result.set(pixels(stack, n));
			}
			catch (Throwable e) {
				error.set(e);
			}
		}, "request for plane " + n);
		t.setDaemon(true);
		t.start();
		return t;
	}

	/** Lets the next read ahead go and checks that the request then ends with the right plane. */
	private static void release(Probe probe, Thread request, AtomicReference<short[]> result, AtomicReference<Throwable> error, int n) throws Exception {
		probe.aheadPermits.release();
		request.join(60000);
		assertFalse("the request for plane " + n + " did not end", request.isAlive());
		if (error.get() != null) throw new AssertionError("the request for plane " + n + " failed", error.get());
		assertArrayEquals("plane " + n, expected(false, n), result.get());
	}

	/**
	 * A request for the block being read ahead waits for that read, and a
	 * request elsewhere waits for it too rather than reading beside it. The
	 * reads ahead are held on permits, so nothing depends on how fast the
	 * machine is: a request is checked to be still waiting while one is held.
	 */
	@Test
	public void requestsWaitForTheReadUnderWay() throws Exception {
		Probe probe = new Probe();
		probe.aheadPermits = new Semaphore(0);
		ParallelVirtualStack stack = probe.stack(tiff());
		stack.setMaxBlockPlanes(4);   // {1-4} {5-8} {9-12} {13}
		try {
			pixels(stack, 1);
			pixels(stack, 2);             // reads 5-8 ahead
			assertTrue("5-8 was not read ahead", probe.aheadEntered.tryAcquire(60, TimeUnit.SECONDS));
			assertEquals(2, stack.getBlockReads());

			// Plane 5, in the block being read, waits for that read
			AtomicReference<short[]> result = new AtomicReference<short[]>();
			AtomicReference<Throwable> error = new AtomicReference<Throwable>();
			Thread wants5 = request(stack, 5, result, error);
			wants5.join(300);
			assertTrue("the request for plane 5 did not wait for the read ahead of 5-8", wants5.isAlive());
			assertEquals("5-8 was read on request beside the read ahead", 1, probe.onRequest.get());
			release(probe, wants5, result, error, 5);
			assertEquals("5-8 was read again", 1, probe.onRequest.get());
			// 1-4 on request, 5-8 ahead, and now 9-12 ahead (from 2 to 5 is scrolling)
			assertTrue("9-12 was not read ahead", probe.aheadEntered.tryAcquire(60, TimeUnit.SECONDS));
			assertEquals(3, stack.getBlockReads());

			// Plane 13, outside it, waits for the read of 9-12 too, then is read on request
			result.set(null);
			Thread wants13 = request(stack, 13, result, error);
			wants13.join(300);
			assertTrue("the request for plane 13 did not wait for the read ahead of 9-12", wants13.isAlive());
			assertEquals("13 was read beside the read ahead", 1, probe.onRequest.get());
			release(probe, wants13, result, error, 13);
			assertEquals(4, stack.getBlockReads());
			assertEquals(2, probe.onRequest.get());

			// Both 9-12 (read ahead) and 13 are held now
			for (int n = 13; n >= 9; n--) assertArrayEquals(expected(false, n), pixels(stack, n));
			assertEquals("planes held were read again", 2, probe.onRequest.get());
			assertEquals("reads that overlapped", 0, probe.overlaps.get());
		}
		finally {
			probe.aheadPermits.release(1000);   // the read behind 9-12, started on the way back, may go
		}
		assertTrue("read-ahead threads still running", readersEnd());
		assertEquals("reads that overlapped", 0, probe.overlaps.get());
	}

	/** With the heap short, nothing is read ahead; every block is still read when asked for. */
	@Test
	public void nothingIsReadAheadWhenTheHeapIsShort() throws Exception {
		for (boolean isZarr : new boolean[] { true, false }) {
			Probe probe = new Probe();
			probe.heapShort = true;
			ParallelVirtualStack stack = isZarr ? probe.stack(zarr()) : probe.stack(tiff());
			stack.setMaxBlockPlanes(2);
			int planes = isZarr ? 7 : TIFF_SLICES;
			for (int z = 1; z <= planes; z++) {
				int n = isZarr ? 1 + 2 * (z - 1) : z;
				assertArrayEquals("plane " + n, expected(isZarr, n), pixels(stack, n));
			}
			assertEquals("reads ahead", 0, probe.ahead.get());
			assertEquals("block reads", (planes + 1) / 2, stack.getBlockReads());
		}
	}

	/** A read ahead that runs out of heap is dropped quietly, and no more are tried. */
	@Test
	public void aReadAheadOutOfMemoryEndsReadingAhead() throws Exception {
		Probe probe = new Probe();
		probe.aheadOutOfMemory = true;
		ParallelVirtualStack stack = probe.stack(tiff());
		stack.setMaxBlockPlanes(2);
		for (int n = 1; n <= TIFF_SLICES; n++)
			assertArrayEquals("plane " + n, expected(false, n), pixels(stack, n));
		for (int n = TIFF_SLICES; n >= 1; n--)
			assertArrayEquals("plane " + n, expected(false, n), pixels(stack, n));
		assertEquals("reads ahead tried", 1, probe.ahead.get());
		assertTrue("read-ahead threads still running", readersEnd());
	}

	/**
	 * Closing an image whose stack is reading ahead leaves no thread behind,
	 * and the stack becomes garbage: the thread does not hold on to it.
	 */
	@Test
	public void noThreadOutlivesTheImage() throws Exception {
		// More slices than the block of 16 read on opening
		String path = new File(tmp.getRoot(), "closed.tif").getPath();
		TiffFixture.write(new File(path), W, H, 40, 1, false);
		System.setProperty("pfv.forceVirtual", "true");
		ImagePlus imp;
		try {
			imp = new PRT(path, false).getImp();
		}
		finally {
			System.clearProperty("pfv.forceVirtual");
		}
		ParallelVirtualStack stack = (ParallelVirtualStack) imp.getStack();
		stack.setMaxBlockPlanes(2);
		// 19-20 and 21-22 read on request, 23-24 ahead
		for (int n = 20; n <= 24; n++) {
			imp.setSlice(n);
			assertArrayEquals("plane " + n, expected(false, n), (short[]) imp.getProcessor().getPixels());
		}
		assertTrue("nothing was read ahead", stack.getBlockReads() >= 4);
		WeakReference<ParallelVirtualStack> ref = new WeakReference<ParallelVirtualStack>(stack);
		stack = null;
		imp.close();
		imp = null;
		assertTrue("read-ahead threads still running", readersEnd());
		for (int i = 0; i < 500 && ref.get() != null; i++) {
			System.gc();
			Thread.sleep(20);
		}
		assertNull("the closed image's stack is still reachable", ref.get());
	}

	/** Without reading ahead the stack reads as it always did: only the blocks asked for, no threads. */
	@Test
	public void readAheadCanBeTurnedOff() throws Exception {
		assertTrue("read-ahead threads still running", readersEnd());
		Probe probe = new Probe();
		ParallelVirtualStack stack = probe.stack(tiff());
		stack.setReadAhead(false);
		stack.setMaxBlockPlanes(2);
		List<Integer> order = new ArrayList<Integer>();
		for (int n = 1; n <= TIFF_SLICES; n++) order.add(n);
		Collections.reverse(order);
		for (int n : order) assertArrayEquals("plane " + n, expected(false, n), pixels(stack, n));
		assertEquals("reads ahead", 0, probe.ahead.get());
		assertEquals("block reads", 7, stack.getBlockReads());
		assertFalse("a read-ahead thread was started", !readersAlive().isEmpty());
	}
}
