package edu.abc.berkeley;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import ij.ImagePlus;

/**
 * Opens a folder's TIFFs and Zarrs several at a time, ahead of the folder
 * opener, which takes them in order. A small file read alone keeps few cores
 * busy: most of its time goes to starting the read (opening the file, waking
 * the threads) and building its image, which files read at once overlap.
 * <p>
 * Each reader's native reads get an equal share of the cores, files are read
 * at most a window ahead of the opener, and they show no progress of their own
 * (the opener counts files as it takes them). A file opens to the same image
 * whichever thread opens it, and the opener takes them in its own order, so
 * the folder opens exactly as it does one file at a time. A file that cannot
 * be opened throws when the opener takes it, as it would have then.
 */
public final class FolderPrefetch implements AutoCloseable {

	/**
	 * For tests and benchmarks: how many files to read at once, instead of the
	 * number chosen from their size (1: none ahead).
	 */
	public static final String READERS_PROPERTY = "pfv.folderReaders";

	private final String directory;
	private final String[] files;
	private final boolean[] ours;
	private final ExecutorService pool;
	private final List<Future<ImagePlus>> pending;
	private final int window;
	private final int threadsEach;
	private int submitted = 0;

	/**
	 * Reads ahead for the folder's files (in the order the opener takes them),
	 * or returns null when one at a time is as fast: for big files, or fewer
	 * than two the plugin opens itself.
	 */
	public static FolderPrefetch start(String directory, String[] files) {
		boolean[] ours = new boolean[files.length];
		int count = 0;
		for (int i = 0; i < files.length; i++) {
			String path = directory + File.separator + files[i];
			ours[i] = !"RoiSet.zip".equals(files[i]) && (PRT.isTiff(path) || ZarrMetadata.isZarr(new File(path)));
			if (ours[i]) count++;
		}
		if (count < 2) return null;
		int cores = Runtime.getRuntime().availableProcessors();
		long size = typicalSize(directory, files, ours);
		// A TIFF much bigger than the folder's typical file is read by the
		// opener itself, with every core, rather than by a reader given a few
		long biggest = Math.max(4 * size, 64L << 20);
		for (int i = 0; i < files.length; i++) {
			if (!ours[i]) continue;
			File f = new File(directory, files[i]);
			if (!f.isDirectory() && f.length() > biggest) {
				ours[i] = false;
				count--;
			}
		}
		if (count < 2) return null;
		int readers = readers(size, cores, Runtime.getRuntime().maxMemory());
		String forced = System.getProperty(READERS_PROPERTY);
		if (forced != null) {
			try {
				readers = Integer.parseInt(forced.trim());
			}
			catch (NumberFormatException e) {
				// ignored: the size decides
			}
		}
		if (readers <= 1) return null;
		// The opener's thread loads the natives, as its first read would: when
		// they cannot be loaded it reads the files itself and fails on the
		// first, with the loader's explanation, as it always has
		try {
			NativeLibraries.load();
		}
		catch (Throwable e) {
			return null;
		}
		return new FolderPrefetch(directory, files, ours, readers, Math.max(1, cores / readers));
	}

	/**
	 * How many files of this size to read at once: up to 8, or half the
	 * cores, while the files read ahead (two per reader) stay within 4 GiB and
	 * an eighth of the heap. Measured on 20- to 48-core nodes, 8 at once opened
	 * folders of 2 to 128 MB files 1.5 to 4 times faster than one at a time,
	 * 4 at once 512 MB files 1.3 to 1.7 times faster; 2 GB files gained little
	 * and more than 2 at once could be slower.
	 */
	static int readers(long size, int cores, long maxHeap) {
		if (size <= 0) return 1;
		long readers = Math.min(8, cores / 2);
		readers = Math.min(readers, (2L << 30) / size);
		readers = Math.min(readers, maxHeap / 16 / size);
		return (int) Math.max(1, readers);
	}

	/** The median size of the first 16 files the plugin opens (a Zarr's from its metadata). */
	static long typicalSize(String directory, String[] files, boolean[] ours) {
		List<Long> sizes = new ArrayList<Long>();
		for (int i = 0; i < files.length && sizes.size() < 16; i++) {
			if (!ours[i]) continue;
			File f = new File(directory, files[i]);
			if (f.isDirectory()) {
				try {
					ZarrMetadata m = ZarrMetadata.read(f);
					sizes.add(m != null ? m.bytes() : 0L);
				}
				catch (IllegalArgumentException e) {
					sizes.add(0L);
				}
			}
			else sizes.add(f.length());
		}
		if (sizes.isEmpty()) return 0;
		Long[] s = sizes.toArray(new Long[0]);
		Arrays.sort(s);
		return s[s.length / 2];
	}

	private FolderPrefetch(String directory, String[] files, boolean[] ours, int readers, int threadsEach) {
		this.directory = directory;
		this.files = files;
		this.ours = ours;
		this.window = 2 * readers;
		this.threadsEach = threadsEach;
		this.pending = new ArrayList<Future<ImagePlus>>(java.util.Collections.<Future<ImagePlus>>nCopies(files.length, null));
		final AtomicInteger n = new AtomicInteger();
		this.pool = Executors.newFixedThreadPool(readers, r -> {
			Thread t = new Thread(r, "PFV folder reader " + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}

	/** Whether the file at index i is opened here (a TIFF or Zarr). */
	public boolean handles(int i) {
		return ours[i];
	}

	/**
	 * The file at index i, opened (waiting for it if need be); also hands the
	 * files after it to the readers, up to the window.
	 *
	 * @throws RuntimeException or Error what opening the file threw
	 */
	public ImagePlus take(int i) {
		while (submitted < files.length && submitted <= i + window) {
			final int j = submitted++;
			if (ours[j]) pending.set(j, pool.submit(() -> read(j)));
		}
		Future<ImagePlus> f = pending.set(i, null);
		if (f == null) return open(i);
		try {
			return f.get();
		}
		catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException) throw (RuntimeException) cause;
			if (cause instanceof Error) throw (Error) cause;
			throw new RuntimeException(cause);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted while opening " + files[i], e);
		}
	}

	/**
	 * Opens the file in a reader, quietly and with the reader's share of the
	 * cores. This is set up for each file rather than once per thread, so that
	 * whatever fails here fails the file, and the opener throws it when it
	 * takes the file (a reader thread that ended before its first file would
	 * leave the opener waiting for it).
	 */
	private ImagePlus read(int i) {
		ProgressEstimator.quietInThisThread();
		ParallelReadNative.limitThreads(threadsEach);
		return open(i);
	}

	/** Exactly how the folder opener opens a TIFF or Zarr. */
	private ImagePlus open(int i) {
		String path = directory + File.separator + files[i];
		if (PRT.isTiff(path)) return new PRT(path, false).getImp();
		return new PRZ(path, false).getImp();
	}

	/** Stops reading ahead: files being read are finished and dropped. */
	@Override
	public void close() {
		pool.shutdownNow();
		for (int i = 0; i < pending.size(); i++) {
			Future<ImagePlus> f = pending.get(i);
			if (f != null) f.cancel(false);
			pending.set(i, null);
		}
	}
}
