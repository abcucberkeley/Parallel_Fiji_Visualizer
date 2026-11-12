package edu.abc.berkeley;

import java.lang.reflect.Array;
import java.util.Arrays;

import ij.ImageStack;

/**
 * JNI bindings for the parallel TIFF and Zarr writers (libParallelWriteC,
 * backed by the cpp-tiff and cpp-zarr libraries).
 */
public class ParallelWriteNative {

	static {
		NativeLibraries.load();
	}

	/** Writes an LZW-compressed TIFF; {@code im} holds one primitive array per slice. */
	public native void parallelWriteTiff(String fileName, Object[] im, int x, int y, int z, int bits);

	/**
	 * Writes a new 3D zarr (rows, columns, planes) of shape {@code end - start};
	 * {@code im} holds one primitive array per slice and {@code cname} is the
	 * blosc codec name (e.g. "lz4"). crop is ignored.
	 */
	public native void parallelWriteZarr(String fileName, Object[] im, long startX, long startY, long startZ,
		long endX, long endY, long endZ, long chunkXSize, long chunkYSize, long chunkZSize,
		int crop, String cname, int useUuid, long bits);

	/**
	 * Writes a new Zarr v2 array of any shape (rows first, then columns, as
	 * MATLAB orders axes) and chunks, with nested chunk folders. {@code im}
	 * holds ImageJ planes of shape[1] x shape[0] pixels; {@code strides} gives,
	 * in pixels, how far apart neighbors along each axis are in those planes
	 * laid end to end: {width, 1, ...} for rows and columns. bits is 8, 16 or 32.
	 */
	public native void parallelWriteZarrArray(String fileName, Object[] im, long[] shape, long[] chunks,
		long[] strides, String cname, long bits);

	// The saves' writes from a PlaneBuffer (see there). Each is given the
	// buffer's size and refuses what would read or write past it.

	/** A native buffer of bytes bytes (not zeroed), or 0 if there is not that much memory. */
	native long allocPlanes(long bytes);

	native void freePlanes(long address);

	/** Copies the first count of planes (planeElems values of bits bits each) to slots first, first + stride, ... */
	native void putPlanes(long address, long capacity, Object[] planes, int count, long planeElems, int bits,
		long first, long stride);

	/**
	 * Writes the first slices planes of the buffer to the TIFF fileName as
	 * parallelWriteTiff does, as a new file or appended to it, with the rows
	 * per strip of an image of totalSlices slices (so that a TIFF written in
	 * runs is the file written in one piece).
	 */
	native void writeTiffPlanes(String fileName, long address, long capacity, int x, int y, int slices, int bits,
		boolean append, int totalSlices);

	/** Writes the metadata of a new Zarr v2 array as parallelWriteZarrArray does, ahead of its regions. */
	native void writeZarrMetadata(String fileName, long[] shape, long[] chunks, String cname, int bits);

	/**
	 * Writes the region [start, end) of whole chunks of the Zarr v2 array
	 * whose metadata is written, as parallelWriteZarrArray does, from the
	 * buffer laid out as strides says.
	 */
	native void writeZarrPlanes(String fileName, long address, long capacity, long[] shape, long[] chunks,
		long[] start, long[] end, long[] strides, String cname, int bits);

	/** The most bytes of planes a TIFF save holds in native memory at once (one run of slices) */
	static final long TIFF_RUN_BYTES = 1L << 30;

	/**
	 * The most bytes of planes a Zarr save holds in native memory at once, if
	 * the heap is at least 8 times as large. More than a TIFF's: each run of
	 * chunk rows ends with cores idle while the last chunks are compressed
	 * (cpp-zarr gives each thread whole chunks, of over half a second each for
	 * 256^3 at zstd level 5) and allocates every thread's chunk buffers anew. A
	 * 2048 x 2048 x 1000 image saved in runs of one chunk row (2 GiB) took
	 * 1.06-1.18 times as long as in one piece on 24 cores and 1.33-1.35 times on
	 * 48; in runs of two, 0.92-1.05 and 1.09-1.10 times. (Cutting runs so that
	 * their last chunks get blosc's threads, or giving them more threads than
	 * cores, gained nothing.)
	 */
	static final long ZARR_RUN_BYTES = 4L << 30;

	private static volatile long runBytes = 0;

	/** The most bytes of planes a TIFF save holds in native memory at once, unless a plane is more. */
	static long tiffRunBytes() {
		return runBytes > 0 ? runBytes : TIFF_RUN_BYTES;
	}

	/**
	 * The most bytes of planes a Zarr save holds in native memory at once,
	 * unless a chunk row of them is more: ZARR_RUN_BYTES, or an eighth of the
	 * heap if that is less, but at least TIFF_RUN_BYTES (beside the heap of a
	 * small machine, the native memory is short too).
	 */
	static long zarrRunBytes() {
		if (runBytes > 0) return runBytes;
		return Math.min(ZARR_RUN_BYTES, Math.max(TIFF_RUN_BYTES, Runtime.getRuntime().maxMemory() / 8));
	}

	/** Sets the run bytes of both formats (the tests make runs of a few planes); 0 restores the defaults. */
	static void setRunBytes(long bytes) {
		runBytes = Math.max(0, bytes);
	}

	/**
	 * The planes of a save in native memory, one run of them at a time: a save
	 * copies a run of planes here, writes it, then copies the next. So it holds
	 * a run beside the heap rather than a second copy of the whole image, and a
	 * virtual stack, whose planes are read as they are copied, never has more
	 * than a batch of them in the heap.
	 */
	static final class PlaneBuffer implements AutoCloseable {
		/** The most bytes of a virtual stack's planes fetched before they are copied */
		private static final long VIRTUAL_BATCH_BYTES = 256L << 20;

		final ParallelWriteNative natives;
		final long planeElems;
		final int bits;
		/** Planes the buffer holds */
		final int planes;
		private long address;

		private PlaneBuffer(ParallelWriteNative natives, long address, long planeElems, int bits, int planes) {
			this.natives = natives;
			this.address = address;
			this.planeElems = planeElems;
			this.bits = bits;
			this.planes = planes;
		}

		/**
		 * A buffer of planes planes of planeElems values of bits bits, or of
		 * fewer, a multiple of unit (at least unit), when there is not that
		 * much native memory.
		 */
		static PlaneBuffer allocate(long planeElems, int bits, int planes, int unit) {
			ParallelWriteNative natives = new ParallelWriteNative();
			long planeBytes = planeElems * (bits / 8);
			for (int k = Math.max(planes, unit);; k = Math.max(unit, k / 2 / unit * unit)) {
				long address = natives.allocPlanes(planeBytes * k);
				if (address != 0) return new PlaneBuffer(natives, address, planeElems, bits, k);
				if (k <= unit) throw new RuntimeException("Not enough native memory to save " + unit + " plane(s) of "
					+ planeBytes + " bytes at a time");
			}
		}

		long capacity() {
			return planeElems * (bits / 8) * planes;
		}

		long address() {
			if (address == 0) throw new IllegalStateException("The planes' buffer was freed");
			return address;
		}

		/**
		 * Copies count planes of stack, numbers first, first + step, ...
		 * (1-based), to slots slot, slot + slotStep, ... A plane the stack
		 * cannot give (a virtual stack whose file went missing) fails the copy.
		 */
		void put(ImageStack stack, int first, int step, int count, int slot, int slotStep) {
			long planeBytes = planeElems * (bits / 8);
			// An image in memory is copied a run at a time (the copy pins it in
			// batches itself), a virtual stack's planes a batch at a time, as
			// they are read, so that few of them are in the heap at once
			long batchBytes = stack.isVirtual() ? Math.min(VIRTUAL_BATCH_BYTES, Runtime.getRuntime().maxMemory() / 16) : Long.MAX_VALUE;
			int batch = (int) Math.max(1, Math.min(count, batchBytes / Math.max(1, planeBytes)));
			Object[] fetched = new Object[batch];
			for (int done = 0; done < count;) {
				int k = Math.min(batch, count - done);
				for (int i = 0; i < k; i++) {
					int n = first + (done + i) * step;
					fetched[i] = stack.getPixels(n);
					if (fetched[i] == null) throw new IllegalStateException("Plane " + n + " of the image could not be read");
					// (the copy refuses them too, but cannot tell which plane of the image it is)
					if (!isPlane(fetched[i], planeElems, bits))
						throw new IllegalStateException("Plane " + n + " of the image is not " + planeElems + " values of " + bits + " bits");
				}
				natives.putPlanes(address(), capacity(), fetched, k, planeElems, bits, slot + (long) done * slotStep, slotStep);
				Arrays.fill(fetched, null);
				done += k;
			}
		}

		/** Whether p is an array of elements values of bits bits, as the copy takes them. */
		private static boolean isPlane(Object p, long elements, int bits) {
			boolean typed = bits == 8 ? p instanceof byte[] : bits == 16 ? p instanceof short[] || p instanceof char[]
				: p instanceof float[] || p instanceof int[];
			return typed && Array.getLength(p) == elements;
		}

		/** Frees the buffer (once; writes from it after this are refused). */
		@Override
		public void close() {
			if (address != 0) natives.freePlanes(address);
			address = 0;
		}
	}
}
