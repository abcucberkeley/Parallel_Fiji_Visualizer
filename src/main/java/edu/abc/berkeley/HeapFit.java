package edu.abc.berkeley;

import java.lang.management.ManagementFactory;
import java.lang.management.PlatformManagedObject;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;

/**
 * Whether an image's planes fit in Java's heap, and their allocation from all
 * cores in a way that does not run the heap dry for the rest of Fiji.
 * <p>
 * G1, the collector Fiji starts Java with, gives an array of more than half a
 * region (1 to 32 MB, set by the heap's size) whole regions of its own, and
 * packs smaller arrays into regions. Planes of 2048 x 2048 16-bit pixels (8 MB
 * and an array header) take 16 MB in 16 MB regions and 12 MB in 4 MB ones:
 * such an image of 8.4 GB needs 12.6 to 16.8 GB of heap.
 * <p>
 * An image fits when its planes, laid out so, and room to allocate and show
 * them fit beside what is known to be in the heap: the images being opened,
 * the images ImageJ has open (in windows, or a macro's in batch mode), and
 * the planes this plugin allocated that have not been collected (an image a
 * script keeps without showing it, the blocks of virtual stacks; or garbage,
 * such as an image closed since the last collection). When only the last
 * stand in the way, a collection tells. Nothing else can be known without one:
 * the heap's use counts garbage, and the heap pools' use after the last
 * collection is not updated by most of G1's (on Java 8 and 17 a young
 * collection does not update it for the old generation: it stayed 0 with 8 to
 * 17 GB of planes held, and after a mixed one it went on counting planes since
 * dropped).
 * <p>
 * So the allocation watches the heap too, and stops before it runs out: at a
 * margin that covers the planes being allocated at that moment and a reserve,
 * after a collection that tells garbage from what is alive. Then it drops
 * every plane and throws {@link DoesNotFit}, and the image opens as a virtual
 * stack.
 */
final class HeapFit {

	/** G1's region size, or 0 for other collectors (whose arrays take their own size). */
	static final long REGION = region();
	/** The bytes of an array before its elements: 16 with compressed class pointers, else 24. */
	static final int HEADER = "true".equals(vmOption("UseCompressedClassPointers")) ? 16 : 24;
	/**
	 * Java 8's G1 counts an array of more than half a region by its size in
	 * the heap's use, not by the regions it takes (as Java 11 and later do).
	 */
	private static final boolean SIZES_ONLY = REGION > 0 && System.getProperty("java.specification.version", "").startsWith("1.");

	// For tests: the heap to decide by (negative: the JVM's maximum); the
	// plane allocation that fails, counted from 0 in the order they start,
	// once (negative: none); and a list that is given every plane allocated
	static volatile long heapForTests = -1;
	static volatile int failAtForTests = -1;
	static volatile List<WeakReference<Object>> allocatedForTests = null;

	/** The heap claimed by images being opened (guarded by HeapFit.class). */
	private static long claimed = 0;
	/** The claim of the image the calling thread opens, which its allocation of planes takes over. */
	private static final ThreadLocal<Claim> CLAIM = new ThreadLocal<Claim>();
	/** The plugin's allocations of planes not collected yet (guarded by HeapFit.class). */
	private static final List<Allocation> allocations = new ArrayList<Allocation>();
	/**
	 * Memory held only softly, which the JVM frees before it lets any thread's
	 * allocation fail: an allocation of planes that finds it gone stops
	 * (guarded by HeapFit.class).
	 */
	private static SoftReference<byte[]> reserve = new SoftReference<byte[]>(null);

	private HeapFit() {}

	/**
	 * Thrown when the heap ran short while an image's planes were allocated;
	 * they have all been dropped. An OutOfMemoryError, so that the code that
	 * handles those (the virtual stack's block reads, ImageJ's commands)
	 * handles it too.
	 */
	static final class DoesNotFit extends OutOfMemoryError {
		private static final long serialVersionUID = 1L;

		DoesNotFit(String message) {
			super(message);
		}
	}

	/** Planes the plugin allocated, known by the first of them while it lives. */
	private static final class Allocation {
		final WeakReference<Object> first;
		/** Their heap, and the part of it Java 8 leaves out of the heap's use. */
		final long need, unseen;

		Allocation(Object first, long need, long unseen) {
			this.first = new WeakReference<Object>(first);
			this.need = need;
			this.unseen = unseen;
		}
	}

	// --- Size ---

	/** The heap that planes of planeBytes each take in this JVM. */
	static long needed(long planes, long planeBytes) {
		return needed(planes, planeBytes, REGION, HEADER);
	}

	/**
	 * The heap that planes of planeBytes each take, as arrays of header bytes
	 * before their elements, in G1 regions of region bytes (0: another
	 * collector). An array of more than half a region takes whole regions of
	 * its own (none of them shared), smaller ones their share of a region
	 * holding as many as fit.
	 */
	static long needed(long planes, long planeBytes, long region, int header) {
		if (planes <= 0) return 0;
		long array = (header + planeBytes + 7) & ~7L;
		if (region <= 0) return planes * array;
		if (2 * array > region) return planes * ((array + region - 1) / region) * region;
		long perRegion = region / array;
		return (planes * region + perRegion - 1) / perRegion;
	}

	/** The heap an image's planes take: none for a virtual stack's, which are read as they are shown. */
	static long heldBy(ImagePlus imp) {
		int n = imp.getStackSize();
		if (n > 1 && imp.getStack().isVirtual()) return 0;
		return needed(n, (long) imp.getWidth() * imp.getHeight() * imp.getBytesPerPixel());
	}

	/** The heap the images ImageJ has open take (in windows, or a macro's in batch mode). */
	static long held() {
		long held = 0;
		for (ImagePlus imp : images()) held += heldBy(imp);
		return held;
	}

	private static List<ImagePlus> images() {
		List<ImagePlus> images = new ArrayList<ImagePlus>();
		try {
			int[] ids = WindowManager.getIDList();
			if (ids == null) return images;
			for (int id : ids) {
				ImagePlus imp = WindowManager.getImage(id);
				if (imp != null) images.add(imp);
			}
		}
		catch (RuntimeException e) {
			// An image closed while the list was read: the rest stands
		}
		return images;
	}

	/**
	 * Of the plugin's allocations not collected yet, the heap of those that
	 * are not planes of an image in images (which held() counts); all of them
	 * when images is null. HeapFit.class held.
	 */
	private static long tracked(Map<Object, Boolean> imagePlanes) {
		long sum = 0;
		for (Iterator<Allocation> it = allocations.iterator(); it.hasNext();) {
			Allocation a = it.next();
			Object first = a.first.get();
			if (first == null) it.remove();
			else if (imagePlanes == null || !imagePlanes.containsKey(first)) sum += a.need;
		}
		return sum;
	}

	/** The first planes of the plugin's allocations that are planes of ImageJ's images. */
	private static Map<Object, Boolean> planesOfImages() {
		Map<Object, Boolean> firsts = new IdentityHashMap<Object, Boolean>();
		synchronized (HeapFit.class) {
			for (Allocation a : allocations) {
				Object first = a.first.get();
				if (first != null) firsts.put(first, Boolean.FALSE);
			}
		}
		for (ImagePlus imp : images()) {
			try {
				if (imp.getStackSize() > 1) {
					ImageStack stack = imp.getStack();
					if (stack.isVirtual()) continue;
					Object[] planes = stack.getImageArray();
					for (int i = 0, n = Math.min(planes.length, stack.getSize()); i < n; i++)
						if (firsts.containsKey(planes[i])) firsts.put(planes[i], Boolean.TRUE);
				}
				else if (imp.getProcessor() != null) {
					Object p = imp.getProcessor().getPixels();
					if (p != null && firsts.containsKey(p)) firsts.put(p, Boolean.TRUE);
				}
			}
			catch (RuntimeException e) {
				// closed meanwhile: its planes count
			}
		}
		firsts.values().removeIf(found -> !found);
		return firsts;
	}

	// --- Fit ---

	/**
	 * Whether planes of planeBytes each fit in the heap now (see the class
	 * comment), without a collection.
	 */
	static boolean fits(long planes, long planeBytes) {
		return decide(planes, planeBytes, false, false) != null;
	}

	/**
	 * The heap for an image's planes, claimed until it is released (other
	 * opens count it meanwhile; the calling thread's allocation of them takes
	 * it over), or null when they do not fit (see the class comment).
	 */
	static Claim claim(long planes, long planeBytes) {
		return decide(planes, planeBytes, true, true);
	}

	/** A claim (an empty one unless claim) when the planes fit; with collect, a collection may tell. */
	private static Claim decide(long planes, long planeBytes, boolean claim, boolean collect) {
		long need = needed(planes, planeBytes), room = need + headroom(planes, planeBytes);
		// First with every plane the plugin allocated counted, then those of
		// ImageJ's images counted once, then after a collection
		for (int step = 0; step < (collect ? 3 : 2); step++) {
			if (step == 2) System.gc();
			long held = held();
			Map<Object, Boolean> imagePlanes = step == 0 ? null : planesOfImages();
			synchronized (HeapFit.class) {
				long free = heap() - held - claimed;
				if (room > free) return null;
				if (room <= free - tracked(imagePlanes)) {
					Claim c = new Claim(claim ? need : 0);
					claimed += c.bytes;
					if (claim) CLAIM.set(c);
					return c;
				}
			}
		}
		return null;
	}

	/** Heap claimed for an image being opened. */
	static final class Claim {
		private long bytes;

		private Claim(long bytes) {
			this.bytes = bytes;
		}

		/** Gives the heap back (again: nothing). */
		void release() {
			synchronized (HeapFit.class) {
				claimed -= bytes;
				bytes = 0;
			}
			if (CLAIM.get() == this) CLAIM.remove();
		}
	}

	/** The heap claimed by images being opened. */
	static synchronized long claimed() {
		return claimed;
	}

	/** The heap of the plugin's allocations not collected yet. */
	static synchronized long tracked() {
		return tracked(null);
	}

	/**
	 * The line that tells the user an image opens as a virtual stack, and why:
	 * it does not fit (see the class comment), or the heap ran short while its
	 * planes were allocated.
	 */
	static String virtualMessage(String title, long planes, long planeBytes, boolean ranShort) {
		String virtual = title + ": opened as a virtual stack, which reads its planes as they are shown: ";
		if (ranShort) return virtual + "Fiji's memory ran short while its " + size(planes * planeBytes) + " were read";
		long held = held(), free;
		Map<Object, Boolean> imagePlanes = planesOfImages();
		synchronized (HeapFit.class) {
			free = Math.max(0, heap() - held - claimed - tracked(imagePlanes));
		}
		return virtual + "its " + size(planes * planeBytes) + " need " + size(needed(planes, planeBytes) + headroom(planes, planeBytes))
			+ " of Fiji's memory, which has " + size(free) + " free";
	}

	/** Bytes as "8.4 GB", or "35 MB" under a GB. */
	private static String size(long bytes) {
		return bytes >= 1e9 ? String.format(Locale.ROOT, "%.1f GB", bytes / 1e9) : String.format(Locale.ROOT, "%.0f MB", bytes / 1e6);
	}

	private static long heap() {
		long h = heapForTests;
		return h >= 0 ? h : Runtime.getRuntime().maxMemory();
	}

	/**
	 * Room beside an image's planes: for the planes being allocated at once
	 * (where the allocation stops short of the heap's end), for ImageJ's
	 * screen and undo copies of a plane, and the reserve.
	 */
	static long headroom(long planes, long planeBytes) {
		return Allocator.of(planes, planeBytes).margin + 2 * needed(1, planeBytes);
	}

	/** Tens of MB: 32 MB, or a 64th of a small heap (at least 1 MB). */
	private static long reserveBytes() {
		return Math.max(1L << 20, Math.min(32L << 20, Runtime.getRuntime().maxMemory() / 64));
	}

	// --- Allocation ---

	static byte[][] bytePlanes(long z, long n) {
		final int length = (int) n;
		return planes(z, n, byte[][]::new, i -> new byte[length]);
	}

	static short[][] shortPlanes(long z, long n) {
		final int length = (int) n;
		return planes(z, 2 * n, short[][]::new, i -> new short[length]);
	}

	static float[][] floatPlanes(long z, long n) {
		final int length = (int) n;
		return planes(z, 4 * n, float[][]::new, i -> new float[length]);
	}

	static int[][] intPlanes(long z, long n) {
		final int length = (int) n;
		return planes(z, 4 * n, int[][]::new, i -> new int[length]);
	}

	/**
	 * How planes are allocated: by how many cores at once, in runs of how
	 * many from a shared count, and how close to the heap's end the
	 * allocation may come (the planes all cores may be allocating, and the
	 * reserve). The cores are fewer where their planes at once would take
	 * more than a 16th of the heap.
	 */
	private static final class Allocator {
		final int workers, run;
		final long perPlane, margin;

		private Allocator(int workers, int run, long perPlane, long margin) {
			this.workers = workers;
			this.run = run;
			this.perPlane = perPlane;
			this.margin = margin;
		}

		static Allocator of(long planes, long planeBytes) {
			long array = (HEADER + planeBytes + 7) & ~7L;
			// a plane being allocated: whole regions, or its own size when it shares one
			long perPlane = REGION > 0 && 2 * array > REGION ? needed(1, planeBytes) : array;
			long budget = Math.max(perPlane, Runtime.getRuntime().maxMemory() / 16);
			long n = Math.max(1, planes);
			int workers = (int) Math.max(1, Math.min(Math.min(n, ForkJoinPool.getCommonPoolParallelism() + 1), budget / perPlane));
			// Runs of small planes: few turns at the count
			int run = perPlane >= 1 << 20 ? 1 : (int) Math.max(1, Math.min(n / (workers * 16L), budget / workers / perPlane));
			return new Allocator(workers, run, perPlane, reserveBytes() + (long) workers * run * perPlane);
		}
	}

	/**
	 * z planes of planeBytes each, made by plane, in an array made by array,
	 * from all cores (the JVM zero-fills every new array on the allocating
	 * thread, which for a multi-gigabyte image takes longer than reading it).
	 * Nothing is thrown inside the parallel stream, which in Java 17 took the
	 * pool's threads, and then Swing's and AWT's, down with the heap.
	 * <p>
	 * The cores look at the heap's use as they go (each time after half the
	 * planes that fit before a margin): once it comes within the margin of the
	 * heap's end (before each run then), a collection (two at most) tells
	 * garbage from what is alive, and the allocation goes on only if every
	 * plane left still fits before the margin. Each plane is made under a catch of
	 * OutOfMemoryError, and the reserve is looked at before each: a failure,
	 * or the reserve gone (the JVM freed it for a thread whose allocation would
	 * have failed), stops the others as well. When it stops, every plane made
	 * is dropped, for the next collection to give back to the rest of Fiji.
	 *
	 * @throws DoesNotFit when the heap ran short: no plane is held any more
	 */
	static <T> T[] planes(long z, long planeBytes, IntFunction<T[]> array, IntFunction<T> plane) {
		final SoftReference<byte[]> canary;
		final T[] p;
		// What Java 8 leaves out of the heap's use for the plugin's planes allocated before
		final long[] unseenBefore = { 0 };
		try {
			canary = reserve();
			p = array.apply((int) z);
			unseenBefore[0] = unseen();
		}
		catch (OutOfMemoryError e) {
			throw new DoesNotFit("The heap is full: " + z + " planes cannot be allocated");
		}
		final int n = p.length;
		final Allocator how = Allocator.of(n, planeBytes);
		final int run = how.run;
		// What Java 8 leaves out of the heap's use for each plane of more than half a region
		final long unseenEach = SIZES_ONLY && how.perPlane > ((HEADER + planeBytes + 7) & ~7L) ? how.perPlane - ((HEADER + planeBytes + 7) & ~7L) : 0;
		final int failAt = failAtForTests;
		final List<WeakReference<Object>> track = allocatedForTests;
		final AtomicInteger next = new AtomicInteger(), started = new AtomicInteger();
		// The plane from which on the heap is looked at again (the look takes
		// the JVM's heap lock, which the allocations take too)
		final AtomicInteger lookAt = new AtomicInteger();
		final AtomicBoolean ranShort = new AtomicBoolean();
		final int[] collections = { 0 };
		final Object wall = new Object();
		IntConsumer work = w -> {
			while (!ranShort.get()) {
				int at = next.get();
				if (at >= n || at < 0) break;
				if (at >= lookAt.get()) {
					long want = Math.min(run, n - at) * how.perPlane;
					long room = room(unseenBefore[0] + unseenEach * at);
					if (room < how.margin + want) {
						// Near the end of the heap: one core collects (twice at most)
						// and the others wait for what it finds
						boolean goOn = false;
						synchronized (wall) {
							if (!ranShort.get()) {
								at = Math.min(n, next.get());
								room = room(unseenBefore[0] + unseenEach * at);
								goOn = room >= how.margin + want;
								if (!goOn && collections[0] < 2) {
									collections[0]++;
									System.gc();
									unseenBefore[0] = unseen();
									at = Math.min(n, next.get());
									room = room(unseenBefore[0] + unseenEach * at);
									goOn = room >= how.margin + (n - at) * how.perPlane;
								}
							}
						}
						if (!goOn) {
							ranShort(p, ranShort, canary);
							break;
						}
					}
					// Until the next look: half the planes that fit before the margin
					long ahead = Math.max(run, (room - how.margin) / how.perPlane / 2);
					int until = (int) Math.min(n, at + ahead);
					lookAt.accumulateAndGet(until, Math::max);
				}
				int i0 = next.getAndAdd(run);
				if (i0 >= n || i0 < 0) break;
				for (int i = i0, end = Math.min(n, i0 + run); i < end && !ranShort.get(); i++) {
					try {
						if (canary.get() == null) {
							ranShort(p, ranShort, canary);
							break;
						}
						if (failAt >= 0 && started.getAndIncrement() == failAt) {
							failAtForTests = -1;
							throw new OutOfMemoryError("failed for tests");
						}
						T t = plane.apply(i);
						if (track != null) track.add(new WeakReference<Object>(t));
						p[i] = t;
					}
					catch (OutOfMemoryError e) {
						ranShort(p, ranShort, canary);
					}
				}
			}
		};
		if (how.workers == 1) work.accept(0);
		else IntStream.range(0, how.workers).parallel().forEach(work);
		if (ranShort.get()) {
			// Planes a core stored after another dropped them
			Arrays.fill(p, null);
			throw new DoesNotFit("The heap ran short while " + n + " planes were allocated; they have been dropped");
		}
		if (n > 0) {
			long need = needed(n, planeBytes);
			synchronized (HeapFit.class) {
				for (Iterator<Allocation> it = allocations.iterator(); it.hasNext();)
					if (it.next().first.get() == null) it.remove();
				allocations.add(new Allocation(p[0], need, unseenEach * n));
				// The image's claim, if this thread opens one, becomes these planes
				Claim c = CLAIM.get();
				if (c != null) {
					long k = Math.min(c.bytes, need);
					c.bytes -= k;
					claimed -= k;
				}
			}
		}
		return p;
	}

	/** The heap's room: its maximum less its use and less unseen, what Java 8 leaves out of the use. */
	private static long room(long unseen) {
		Runtime rt = Runtime.getRuntime();
		return rt.maxMemory() - rt.totalMemory() + rt.freeMemory() - unseen;
	}

	/** Stops an allocation of planes: drops them, and frees the reserve for the rest of Fiji. */
	private static void ranShort(Object[] p, AtomicBoolean ranShort, SoftReference<byte[]> canary) {
		ranShort.set(true);
		canary.clear();
		Arrays.fill(p, null);
	}

	/** What Java 8 leaves out of the heap's use for the plugin's planes not collected yet. */
	private static synchronized long unseen() {
		if (!SIZES_ONLY) return 0;
		long sum = 0;
		for (Allocation a : allocations)
			if (a.first.get() != null) sum += a.unseen;
		return sum;
	}

	/** The reserve, allocated anew if the JVM freed it (made a little short of whole regions). */
	private static synchronized SoftReference<byte[]> reserve() {
		if (reserve.get() == null) reserve = new SoftReference<byte[]>(new byte[(int) reserveBytes() - 64]);
		return reserve;
	}

	// --- The JVM ---

	private static long region() {
		if (!"true".equals(vmOption("UseG1GC"))) return 0;
		try {
			return Long.parseLong(vmOption("G1HeapRegionSize"));
		}
		catch (NumberFormatException e) {
			return 0;
		}
	}

	/** A HotSpot flag's value, or null from JVMs that do not tell. */
	private static String vmOption(String name) {
		try {
			Class<?> bean = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
			Object diagnostic = ManagementFactory.getPlatformMXBean(bean.asSubclass(PlatformManagedObject.class));
			Object option = bean.getMethod("getVMOption", String.class).invoke(diagnostic, name);
			return (String) option.getClass().getMethod("getValue").invoke(option);
		}
		catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}
}
