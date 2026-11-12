package edu.abc.berkeley;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads a cold TIFF into the page cache while it is opened, so that cpp-tiff's
 * walk of its directories and its read of the pixels find the file there. A
 * cold walk is one small read per directory, each waiting for the one before
 * (about 1 ms from network storage: 10 s for 10,000 directories), and nothing
 * is read at all while the planes are allocated; a few sequential streams
 * meanwhile read the file at what the storage delivers. On a 48-core node
 * reading network storage this made cold opens of 8 to 67 GB TIFFs 1.5 to
 * 3.4 times faster (the most for many directories: 10,000 slices of 1.8 MB
 * opened in 5.9 s instead of 20).
 * <p>
 * It begins 150 ms after the open starts, unless the image has been read by
 * then, and only where it helps and is safe: the file has at least 256 MB
 * (smaller ones are walked and read before it would matter); most of it is
 * not in the page cache; the page cache can hold it beside what the image
 * adds to the memory; and the image is read whole, not as a virtual stack
 * (which reads only what is shown) and not by a folder's readers (which read
 * several files at once, each with a few cores). It ends with the image's
 * first read, at the end of the file, or when the image is not read after
 * all. The system property {@code pfv.tiffReadAhead=false} turns it off.
 */
final class TiffReadAhead implements AutoCloseable {

	/** System property: false turns reading ahead off. */
	static final String PROPERTY = "pfv.tiffReadAhead";
	/** The names of its threads start with this. */
	static final String THREAD_NAME = "PFV read-ahead";

	static final long MIN_BYTES = 256L << 20;
	static final long DELAY_NANOS = 150_000_000L;
	/**
	 * An image described this long ago whose read has not begun is not read
	 * (the plugin reads its images as soon as they are described).
	 */
	private static final long ABANDONED_NANOS = 1_000_000_000L;
	/** How long close() waits for the reads in flight. */
	private static final long CLOSE_MILLIS = 2000;
	private static final int PIECE = 4 << 20;
	/**
	 * The cold check reads 4 KB at places spread over the file: microseconds
	 * from the page cache (1-25 us measured), 0.1 ms or more from any storage
	 * (0.25-5 ms from network storage). A read is slow when it takes 10 times
	 * as long as rereads of a page in the cache, and at least 50 us. Two
	 * passes of 8 reads, each at other places, must both find most reads
	 * slow: a thread held up now and then (a busy machine, a collection)
	 * slows a few reads, not most of 16, and a machine whose cache reads are
	 * slow raises the bar.
	 */
	private static final int SAMPLES = 8, REREADS = 5, SLOWER = 10;
	private static final long MIN_SLOW_NANOS = 50_000L;
	private static final boolean TIMING = System.getenv("PFV_TIMING") != null;

	// For tests: the smallest file, the delay, the cold check's answer (null:
	// measured), the memory the system can still give (negative: measured),
	// and a pause before every piece read, which keeps a read-ahead running
	static volatile long minBytes = MIN_BYTES;
	static volatile long delayNanos = DELAY_NANOS;
	static volatile Boolean coldForTests = null;
	static volatile long availableForTests = -1;
	static volatile long pauseMillis = 0;
	/** How many read-aheads have begun reading. */
	static final AtomicInteger begun = new AtomicInteger();

	private final Path file;
	private final String name;
	private final long length, available, maxHeap;
	private final long armed = System.nanoTime();
	private final Object lock = new Object();
	/** Every thread started (guarded by lock). */
	private final List<Thread> threads = new ArrayList<Thread>();
	private final AtomicLong bytesRead = new AtomicLong();
	private volatile boolean stopped = false, reading = false, described = false;
	private volatile long describedAt, readingAt;
	/** What the cold check found, for the timing line. */
	private String check = "cold check skipped";

	/**
	 * Read-ahead for an open of path that is starting, or null where it cannot
	 * help or is not safe by what is known before the file's directories.
	 */
	static TiffReadAhead arm(String path) {
		if ("false".equalsIgnoreCase(System.getProperty(PROPERTY, "true").trim())) return null;
		if (ProgressEstimator.isQuietThread()) return null;
		try {
			File f = new File(path);
			long length = f.length(), maxHeap = Runtime.getRuntime().maxMemory();
			// Most files are ruled out before the memory is looked up
			if (length < minBytes || length > maxHeap) return null;
			long available = availableForTests >= 0 ? availableForTests : available();
			if (!worthReading(length, available, maxHeap)) return null;
			TiffReadAhead a = new TiffReadAhead(f.toPath(), f.getName(), length, available, maxHeap);
			a.start(THREAD_NAME, a::run);
			return a;
		}
		catch (RuntimeException e) {
			// A path Java cannot take, say: the file is read as without
			return null;
		}
	}

	/**
	 * Whether reading ahead may begin, before the directories are known: the
	 * file has at least 256 MB, is not larger than the heap (its image would
	 * not fit in it either), and has at most a quarter of the memory the
	 * system can still give.
	 */
	static boolean worthReading(long length, long available, long maxHeap) {
		return length >= minBytes && length <= maxHeap && length <= available / 4;
	}

	/**
	 * Whether reading ahead may go on once the directories are known: the
	 * image is read whole (its planes fit in the heap, as PRT decides), and the
	 * page cache can hold the file beside the planes and the native read's
	 * buffer of the slices as stored.
	 */
	static boolean fits(long length, long planeBytes, long storedBytes, long available, long maxHeap) {
		return planeBytes <= maxHeap && length <= available - planeBytes - storedBytes;
	}

	private TiffReadAhead(Path file, String name, long length, long available, long maxHeap) {
		this.file = file;
		this.name = name;
		this.length = length;
		this.available = available;
		this.maxHeap = maxHeap;
	}

	/**
	 * The file's directories are known: goes on only if the image fits (see
	 * {@link #fits}).
	 *
	 * @return whether it goes on; if not, it has ended
	 */
	boolean described(long planeBytes, long storedBytes) {
		if (!fits(length, planeBytes, storedBytes, available, maxHeap)) {
			close();
			report("stopped: the image is not read whole, or the memory cannot hold the file beside it");
			return false;
		}
		describedAt = System.nanoTime();
		described = true;
		return true;
	}

	/** The image's first read begins. */
	void reading() {
		readingAt = System.nanoTime();
		reading = true;
	}

	/** Ends reading ahead, waiting a little for the reads in flight. */
	@Override
	public void close() {
		List<Thread> started;
		synchronized (lock) {
			stopped = true;
			lock.notifyAll();
			started = new ArrayList<Thread>(threads);
		}
		long until = System.currentTimeMillis() + CLOSE_MILLIS;
		try {
			for (Thread t : started) {
				long left = until - System.currentTimeMillis();
				if (left > 0) t.join(left);
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (TIMING && reading && bytesRead.get() > 0)
			report(String.format("directories known %.3f s after the open, the read from %.3f to %.3f s, %.2f GB read ahead by then",
				(describedAt - armed) / 1e9, (readingAt - armed) / 1e9, (System.nanoTime() - armed) / 1e9, bytesRead.get() / 1e9));
	}

	private boolean stopping() {
		return stopped || described && !reading && System.nanoTime() - describedAt > ABANDONED_NANOS;
	}

	private Thread start(String threadName, Runnable task) {
		Thread t = new Thread(task, threadName);
		t.setDaemon(true);
		synchronized (lock) {
			if (stopped) return null;
			threads.add(t);
			t.start();
		}
		return t;
	}

	/** After the delay, if the file is cold: reads it with the reader threads, this one among them. */
	private void run() {
		try {
			synchronized (lock) {
				for (long left; !stopped && (left = armed + delayNanos - System.nanoTime()) > 0;)
					lock.wait(Math.max(1, left / 1_000_000));
			}
			if (stopping()) {
				report("not begun: the image was read, or will not be");
				return;
			}
			Boolean forced = coldForTests;
			if (!(forced != null ? forced : cold())) {
				report("not begun: the file is in the page cache (" + check + ")");
				return;
			}
			begun.incrementAndGet();
			long t0 = System.nanoTime();
			int n = readers();
			List<Thread> others = new ArrayList<Thread>();
			for (int i = 1; i < n; i++) {
				final int k = i;
				Thread t = start(THREAD_NAME + " " + (i + 1), () -> readQuietly(k, n));
				if (t != null) others.add(t);
			}
			readQuietly(0, n);
			for (Thread t : others) t.join();
			if (TIMING) {
				double s = (System.nanoTime() - t0) / 1e9;
				report(String.format("%.2f of %.2f GB in %.3f s (%.2f GB/s), from %.3f s after the open on, %d threads (%s)",
					bytesRead.get() / 1e9, length / 1e9, s, bytesRead.get() / 1e9 / s, (t0 - armed) / 1e9, n, check));
			}
		}
		catch (IOException e) {
			// Only time is lost: the open reads the file itself
			report("not begun: " + e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Up to 24, or half the cores: on a 48-core node, 24 streams read a file
	 * from network storage at 5.3 GB/s, 12 at 4.7 and 6 at 3.4 (a 10,000
	 * directory TIFF then opened cold in 5.9, 6.1 and 6.6-7.0 s).
	 */
	private static int readers() {
		return Math.max(1, Math.min(24, Runtime.getRuntime().availableProcessors() / 2));
	}

	/** Whether most of the file is not in the page cache, by the cold check's reads (see SAMPLES). */
	private boolean cold() throws IOException {
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
			long[][] t = sampleReads(ch, length);
			if (TIMING) {
				long slow = slowNanos(t[0]), fastest = Long.MAX_VALUE, slowest = 0;
				for (int k = 1; k < 3; k++)
					for (long x : t[k]) {
						fastest = Math.min(fastest, x);
						slowest = Math.max(slowest, x);
					}
				check = String.format("%d and %d of %d reads slower than %.0f us, %.0f-%.0f us", slowReads(t[1], slow), slowReads(t[2], slow),
					SAMPLES, slow / 1e3, fastest / 1e3, slowest / 1e3);
			}
			return judgedCold(t[0], t[1], t[2]);
		}
	}

	/**
	 * The cold check's reads, in nanoseconds: rereads of the file's first page
	 * (which the walk has read; read once more here first, untimed, so that
	 * the code of the reads is loaded), then two passes of reads spread over
	 * the file, the second between the places of the first.
	 */
	static long[][] sampleReads(FileChannel ch, long length) throws IOException {
		ByteBuffer b = ByteBuffer.allocateDirect(4096);
		timedRead(ch, b, 0);
		long[] cached = new long[REREADS], first = new long[SAMPLES], second = new long[SAMPLES];
		for (int i = 0; i < REREADS; i++) cached[i] = timedRead(ch, b, 0);
		for (int i = 0; i < SAMPLES; i++) first[i] = timedRead(ch, b, (length / SAMPLES * i + length / SAMPLES / 2) & ~4095L);
		for (int i = 0; i < SAMPLES; i++) second[i] = timedRead(ch, b, (length / SAMPLES * i + length / SAMPLES / 4) & ~4095L);
		return new long[][] { cached, first, second };
	}

	private static long timedRead(FileChannel ch, ByteBuffer b, long at) throws IOException {
		b.clear();
		long t0 = System.nanoTime();
		ch.read(b, at);
		return System.nanoTime() - t0;
	}

	/**
	 * The cold check's verdict on the times of its reads (see SAMPLES): the
	 * rereads of a cached page, and the two passes.
	 */
	static boolean judgedCold(long[] cached, long[] first, long[] second) {
		long slow = slowNanos(cached);
		return 2 * slowReads(first, slow) > first.length && 2 * slowReads(second, slow) > second.length;
	}

	/** The time above which a read did not come from the page cache: 10 times the median reread, at least 50 us. */
	static long slowNanos(long[] cached) {
		long[] c = cached.clone();
		Arrays.sort(c);
		return Math.max(MIN_SLOW_NANOS, SLOWER * c[c.length / 2]);
	}

	private static int slowReads(long[] nanos, long slow) {
		int n = 0;
		for (long t : nanos)
			if (t > slow) n++;
		return n;
	}

	private void readQuietly(int k, int n) {
		try {
			read((length / n * k) & ~4095L, k + 1 == n ? length : (length / n * (k + 1)) & ~4095L);
		}
		catch (IOException | RuntimeException e) {
			// The file changed or went away: the open will say so if it matters
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Reads [from, to) front to back into a buffer it discards. Each reader
	 * has its own part of the file and its own channel, so the system sees
	 * sequential streams and reads ahead of each, and the walk finds each
	 * part's first directories cached early. (Readers taking the next piece
	 * of the file in turn, filling it from its start, made the walk wait for
	 * them: cold opens took 0.2-0.9 s longer.)
	 */
	private void read(long from, long to) throws IOException, InterruptedException {
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer buf = ByteBuffer.allocateDirect(PIECE);
			for (long at = from; at < to && goOn();) {
				buf.clear();
				if (to - at < PIECE) buf.limit((int) (to - at));
				int r = ch.read(buf, at);
				if (r <= 0) return;
				at += r;
				bytesRead.addAndGet(r);
			}
		}
	}

	/** Whether to read the next piece, after the tests' pause if they set one. */
	private boolean goOn() throws InterruptedException {
		long until = System.currentTimeMillis() + pauseMillis;
		for (long left; !stopping() && (left = until - System.currentTimeMillis()) > 0;) {
			synchronized (lock) {
				if (!stopped) lock.wait(Math.min(left, 50));
			}
		}
		return !stopping();
	}

	private void report(String what) {
		if (TIMING) System.err.println("    [read-ahead] " + name + ": " + what);
	}

	// --- Memory ---

	/**
	 * The memory the system can still give: on Linux its MemAvailable, within
	 * the room its cgroups leave the process (a batch job's memory limit);
	 * elsewhere the free physical memory the JVM reports; 0 if unknown.
	 */
	static long available() {
		long kb = -1;
		try {
			for (String line : Files.readAllLines(Paths.get("/proc/meminfo"))) {
				if (line.startsWith("MemAvailable:")) kb = Long.parseLong(line.replaceAll("[^0-9]", ""));
			}
		}
		catch (IOException | RuntimeException e) {
			// not Linux
		}
		if (kb >= 0) {
			long room = cgroupRoom();
			return room >= 0 ? Math.min(kb << 10, room) : kb << 10;
		}
		try {
			Class<?> bean = Class.forName("com.sun.management.OperatingSystemMXBean");
			Object os = ManagementFactory.getOperatingSystemMXBean();
			if (bean.isInstance(os)) return (Long) bean.getMethod("getFreePhysicalMemorySize").invoke(os);
		}
		catch (ReflectiveOperationException | RuntimeException e) {
			// not a JVM that reports it
		}
		return 0;
	}

	/**
	 * Memory left under the limits of the process's cgroup and those above it,
	 * their page cache counted as free (as MemAvailable counts it), or -1 when
	 * none has a limit.
	 */
	static long cgroupRoom() {
		long room = -1;
		try {
			for (String line : Files.readAllLines(Paths.get("/proc/self/cgroup"))) {
				String[] f = line.split(":", 3);
				if (f.length < 3) continue;
				if (f[0].equals("0") && f[1].isEmpty()) {
					// cgroup v2: any level may set a limit
					Path root = Paths.get("/sys/fs/cgroup");
					for (Path d = root.resolve("." + f[2]).normalize(); d != null && d.startsWith(root); d = d.getParent()) {
						long max = number(d.resolve("memory.max")), current = number(d.resolve("memory.current"));
						if (max >= 0 && current >= 0) room = least(room, max - current + Math.max(0, stat(d, "file")));
					}
				}
				else if (Arrays.asList(f[1].split(",")).contains("memory")) {
					// cgroup v1: the limit of the hierarchy is in memory.stat
					Path d = Paths.get("/sys/fs/cgroup/memory").resolve("." + f[2]).normalize();
					long max = stat(d, "hierarchical_memory_limit"), used = number(d.resolve("memory.usage_in_bytes"));
					if (max >= 0 && max < 1L << 60 && used >= 0) room = least(room, max - used + Math.max(0, stat(d, "total_cache")));
				}
			}
		}
		catch (IOException | RuntimeException e) {
			// no cgroups
		}
		return room;
	}

	private static long least(long room, long r) {
		return room < 0 ? r : Math.min(room, r);
	}

	/** The number a cgroup file holds, or -1 ("max", missing). */
	private static long number(Path p) {
		try {
			return Long.parseLong(new String(Files.readAllBytes(p), "US-ASCII").trim());
		}
		catch (IOException | NumberFormatException e) {
			return -1;
		}
	}

	/** A value of a cgroup's memory.stat, or -1. */
	private static long stat(Path dir, String key) {
		try {
			for (String line : Files.readAllLines(dir.resolve("memory.stat"))) {
				if (line.startsWith(key + " ")) return Long.parseLong(line.substring(key.length() + 1).trim());
			}
		}
		catch (IOException | NumberFormatException e) {
			// not there
		}
		return -1;
	}
}
