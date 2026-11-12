package ij;
import ij.process.*;
import ij.io.*;
import ij.gui.ImageCanvas;
import ij.util.Tools;
import java.io.*;
import java.lang.reflect.Array;
import java.awt.*;
import java.awt.image.ColorModel;
import java.util.Arrays;
import java.util.Properties;

import edu.abc.berkeley.TiffImage;
import edu.abc.berkeley.ZarrImage;

/**
 * The planes of a Zarr or TIFF image too large for memory, read as they are
 * shown. An ImageJ VirtualStack, so that ImageJ's own commands (Save As Tiff,
 * Duplicate, Substack) treat it as one. Every public method of VirtualStack
 * is overridden, as VirtualStack's own fields describe a folder of files and
 * stay empty here; so must be any that a later ImageJ adds.
 */
public class ParallelVirtualStack extends VirtualStack {
	private static final int INITIAL_SIZE = 100;
	private String path;
	private int nSlices;
	private String[] names;
	private String[] labels;
	private int bitDepth;
	private Properties  properties;
	private boolean generateData;
	private int[] indexes;  // used to translate non-CZT hyperstack slice numbers
	private int[] planeOfSlice;  // once slices are deleted: the image's plane (1-based) each slice shows
	private int width;
	private int height;

	// --- Block cache ---------------------------------------------------------
	// Reading K consecutive planes costs little more than reading one: the
	// chunk row (256 planes deep for typical zarrs) must be decompressed either
	// way, and each extra plane only adds its copy-out. Measured on a
	// 11985x48696x1100 uint16 zarr with 32 cores: 20 s for 1 plane, 28.6 s for
	// 8, 80 s for 32. So planes are fetched in aligned blocks along z (of one
	// channel and time point) and served from memory while the user scrolls.
	// The previous block is kept as well, so stepping back across a block
	// boundary, or between two channels, is free. TIFFs are read the same way,
	// in blocks of consecutive planes: one read of several slices keeps every
	// core busy, where a slice alone often has too few strips to.
	//
	// While the user scrolls (or a command walks the planes in order), one
	// background thread reads the block after the current one (before it,
	// scrolling back), so that crossing into it does not stall the event
	// thread for a whole block read. Reads stay one at a time: a request
	// outside the blocks held waits for the read under way, which is often
	// the very block it needs.
	private ZarrImage zarr;
	private TiffImage tiff;
	private long x0, y0, z0;          // the window's corner in the image
	private int depth = 1, channels = 1;
	private int maxBlockPlanes = 16;   // caps the stall of a single fetch
	private Block block, previousBlock, aheadBlock;
	private int blockReads = 0;
	private boolean readAhead = true;   // the user's switch
	private boolean aheadFailed;        // a read ahead ran out of heap: no more for this stack
	private Thread aheadThread;         // the read ahead under way, or null
	private int lastC = -1, lastT = -1, lastZ = -1;   // the plane served last

	/** Planes [start, start + planes.length) along z of one channel and time point (0-based). */
	private static final class Block {
		final int c, t, start;
		final Object[] planes;

		Block(int c, int t, int start, Object[] planes) {
			this.c = c;
			this.t = t;
			this.start = start;
			this.planes = planes;
		}

		boolean holds(int c, int t, int z) {
			return this.c == c && this.t == t && z >= start && z < start + planes.length;
		}
	}

	/** Upper limit on planes fetched per block read (default 16); free memory may lower it. */
	public void setMaxBlockPlanes(int planes) {
		maxBlockPlanes = Math.max(1, planes);
	}

	/** Number of block reads performed so far, read ahead or on request (diagnostics and tests). */
	public synchronized int getBlockReads() {
		return blockReads;
	}

	/**
	 * Whether the next block is read in the background while planes are
	 * fetched in order (default on). Off, every block is read when a plane
	 * of it is first asked for, as the tests that count reads expect.
	 */
	public synchronized void setReadAhead(boolean on) {
		readAhead = on;
	}

	/** Bytes of one plane as held in the heap. */
	private long planeBytes() {
		long bytesPerPixel = bitDepth == 24 ? 4 : Math.max(1, bitDepth / 8);
		return (long) getWidth() * getHeight() * bytesPerPixel;
	}

	/**
	 * Planes per block: a fixed fraction of the heap, rounded down to a power
	 * of two so aligned blocks never straddle a chunk row. Sizing from the
	 * maximum heap (not the free heap) keeps the block size constant for the
	 * life of the stack, so blocks keep lining up and the previous block stays
	 * reusable. Three blocks may be held (the current, the previous and the
	 * one read ahead, or the one being read in place of either): an eighth of
	 * the heap each keeps them under half of it.
	 */
	private int planesPerBlock() {
		long budget = Runtime.getRuntime().maxMemory() / 8;
		long k = Math.max(1, Math.min(maxBlockPlanes, budget / Math.max(1, planeBytes())));
		return Integer.highestOneBit((int) k);
	}

	/**
	 * The heap free right now, garbage counted as used: a read ahead only
	 * starts when the heap can take its block without a collection, so it
	 * never competes with the event thread (or another image) for the last
	 * of it. Overridable for tests.
	 */
	protected long freeHeapBytes() {
		Runtime r = Runtime.getRuntime();
		return r.maxMemory() - (r.totalMemory() - r.freeMemory());
	}

	/**
	 * Reads planes [start, end) along z of channel c and time point t
	 * (0-based), with a progress bar when the user waits for them.
	 * Overridable for tests.
	 */
	protected Object[] readBlock(int c, int t, int start, int end, boolean showProgress) {
		if (zarr != null) {
			long[] from = { x0, y0, z0 + start, c, t }, to = { x0 + getWidth(), y0 + getHeight(), z0 + end, c + 1, t + 1 };
			return showProgress ? zarr.read(from, to, "Reading " + zarr.title) : zarr.read(from, to);
		}
		return showProgress ? tiff.read(start, end, "Reading " + tiff.title) : tiff.read(start, end);
	}

	/** The pixels of plane n (1-based, in czt order), reading its block if needed. Called holding this stack's lock. */
	private Object pixelsOf(int n) {
		if (zarr == null && tiff == null) throw new IllegalStateException("This virtual stack has no image to read");
		int i = n - 1;
		int c = i % channels, z = (i / channels) % depth, t = i / (channels * depth);
		Object pixels = cachedPixels(c, t, z);
		if (pixels == null && aheadThread != null) {
			// One read at a time: wait for the one under way, then look again
			awaitReadAhead();
			pixels = cachedPixels(c, t, z);
		}
		if (pixels == null) pixels = readOnRequest(c, t, z);
		readAheadFrom(c, t, z);
		return pixels;
	}

	/** Plane z of channel c and time point t from the blocks held, its block becoming the current one; null if none holds it. */
	private Object cachedPixels(int c, int t, int z) {
		if (block != null && block.holds(c, t, z))
			return block.planes[z - block.start];
		if (previousBlock != null && previousBlock.holds(c, t, z)) {
			Block b = previousBlock;
			previousBlock = block;
			block = b;
			return block.planes[z - block.start];
		}
		if (aheadBlock != null && aheadBlock.holds(c, t, z)) {
			previousBlock = block;
			block = aheadBlock;
			aheadBlock = null;
			return block.planes[z - block.start];
		}
		return null;
	}

	/** Reads the block of plane z of channel c and time point t while the caller waits. */
	private Object readOnRequest(int c, int t, int z) {
		previousBlock = block;   // the block evicted here becomes garbage before the read allocates
		block = null;
		blockReads++;
		for (;;) {
			int k = planesPerBlock();
			int start = (z / k) * k;
			int end = Math.min(start + k, depth);
			try {
				block = new Block(c, t, start, readBlock(c, t, start, end, true));
				return block.planes[z - start];
			}
			catch (OutOfMemoryError e) {
				if (k == 1) throw e;
				// Not enough heap for blocks this size: halve it for the rest
				// of the session and try again.
				maxBlockPlanes = k / 2;
				previousBlock = null;
				aheadBlock = null;
				System.gc();
			}
		}
	}

	/** Waits for the read ahead under way to end. */
	private void awaitReadAhead() {
		boolean interrupted = false;
		while (aheadThread != null) {
			try {
				wait();
			}
			catch (InterruptedException e) {
				// The read cannot be stopped, and the next must not overlap it
				interrupted = true;
			}
		}
		if (interrupted) Thread.currentThread().interrupt();
	}

	/**
	 * After plane z of channel c and time point t was served: if it is a step
	 * of at most a block from the plane served before it, of the same channel
	 * and time point, reads the block beyond the current one in that direction
	 * in the background, unless it is held already or the heap is short.
	 * Jumps (a plane picked far away) and channels shown together, which
	 * alternate between channels, read nothing ahead.
	 */
	private void readAheadFrom(int c, int t, int z) {
		int step = c == lastC && t == lastT ? z - lastZ : 0;
		lastC = c;
		lastT = t;
		lastZ = z;
		if (!readAhead || aheadFailed || step == 0 || aheadThread != null || block == null) return;
		final int k = planesPerBlock();
		if (Math.abs(step) > k) return;
		int beyond = step > 0 ? block.start + block.planes.length : block.start - 1;
		if (beyond < 0 || beyond >= depth) return;
		if ((previousBlock != null && previousBlock.holds(c, t, beyond)) || (aheadBlock != null && aheadBlock.holds(c, t, beyond))) return;
		final int start = (beyond / k) * k, end = Math.min(start + k, depth);
		// Leave an eighth of the heap free beside the block, garbage counted as used
		if (freeHeapBytes() - (end - start) * planeBytes() < Runtime.getRuntime().maxMemory() / 8) return;
		final int ac = c, at = t;
		Thread reader = new Thread(new Runnable() {
			public void run() {
				readBlockAhead(ac, at, start, end);
			}
		}, "PFV block read-ahead");   // not TiffReadAhead's "PFV read-ahead"
		reader.setDaemon(true);
		try {
			reader.start();
		}
		catch (Throwable e) {
			return;   // no thread to spare: read it when asked for
		}
		// Read with the current and the previous block held: the one read ahead earlier gives way
		aheadBlock = null;
		aheadThread = reader;
		blockReads++;
	}

	/** The background thread's work: reads the block and offers it to the requests. */
	private void readBlockAhead(int c, int t, int start, int end) {
		Object[] planes = null;
		boolean outOfMemory = false;
		try {
			planes = readBlock(c, t, start, end, false);
		}
		catch (OutOfMemoryError e) {
			// The planes read so far are dropped with the error
			outOfMemory = true;
		}
		catch (Throwable e) {
			// The read on request will report it
		}
		synchronized (this) {
			if (outOfMemory) aheadFailed = true;
			if (planes != null) aheadBlock = new Block(c, t, start, planes);
			aheadThread = null;
			notifyAll();
		}
	}

	/** Default constructor. */
	public ParallelVirtualStack() { }
	
	public ParallelVirtualStack(int width, int height) {
		super(width, height);
	}

	/** Creates an empty virtual stack.
	 * @param width		image width
	 * @param height	image height
	 * @param cm	ColorModel or null
	 * @param path	file path of directory containing the images
	 * @see #addSlice(String)
	 * @see <a href="http://wsr.imagej.net/macros/js/OpenAsVirtualStack.js">OpenAsVirtualStack.js</a>
	*/
	public ParallelVirtualStack(int width, int height, ColorModel cm, String path) {
		super(width, height);
		setColorModel(cm);
		path = IJ.addSeparator(path);
		this.path = path;
		names = new String[INITIAL_SIZE];
		labels = new String[INITIAL_SIZE];
	}

	/**
	 * The Zarr image in the folder path: its planes [0, depth) of every channel
	 * and time point, width x height from the corner.
	 */
	public ParallelVirtualStack(int width, int height, int depth, ColorModel cm, String path) {
		this(ZarrImage.open(new File(path)), 0, 0, 0, width, height, depth);
	}

	/**
	 * A window of a Zarr image, read as it is shown: width x height from
	 * (x0, y0), the planes [z0, z0 + depth) of every channel and time point,
	 * in ImageJ's czt order.
	 */
	public ParallelVirtualStack(ZarrImage zarr, long x0, long y0, long z0, int width, int height, int depth) {
		super(width, height);
		this.zarr = zarr;
		this.x0 = x0;
		this.y0 = y0;
		this.z0 = z0;
		this.width = width;
		this.height = height;
		this.depth = depth;
		this.channels = (int) zarr.channels();
		this.path = IJ.addSeparator(zarr.path);
		this.bitDepth = zarr.displayBits();
		nSlices = channels * depth * (int) zarr.frames();
		names = new String[Math.max(1, nSlices)];
		labels = new String[Math.max(1, nSlices)];
		Arrays.fill(names, "");
	}

	/**
	 * Every plane of a TIFF file, in its order (for an ImageJ hyperstack, its
	 * czt order; for several samples per pixel, a slice's samples together).
	 */
	public ParallelVirtualStack(TiffImage tiff) {
		super(tiff.width, tiff.height);
		this.tiff = tiff;
		this.width = tiff.width;
		this.height = tiff.height;
		// Blocks are runs of planes: one channel and time point of tiff.planes() planes
		this.depth = (int) tiff.planes();
		File f = new File(tiff.path);
		this.path = IJ.addSeparator(f.getAbsoluteFile().getParent());
		this.bitDepth = tiff.displayBits();
		nSlices = depth;
		names = new String[Math.max(1, nSlices)];
		labels = new String[Math.max(1, nSlices)];
		Arrays.fill(names, "");
	}

	/** Creates a virtual stack with no backing storage.
	This example creates a one million slice virtual
	stack that uses just 1MB of RAM:
	<pre>
    stack = new VirtualStack(1024,1024,1000000);
    new ImagePlus("No Backing Store Virtual Stack",stack).show();
	*/
	public ParallelVirtualStack(int width, int height, int slices) {
		this(width, height, slices, "8-bit");
	}

	public ParallelVirtualStack(int width, int height, int slices, String options) {
		super(width, height);
		nSlices = slices;
		int depth = 8;
  		if (options.contains("16-bit")) depth=16;
 	    if (options.contains("RGB")) depth=24;
        if (options.contains("32-bit")) depth=32;
        this.generateData = options.contains("fill");
		this.bitDepth = depth;
	}

	/** Whether the planes come from an image (a Zarr or a TIFF) rather than from files or nothing. */
	private boolean hasImage() {
		return zarr != null || tiff != null;
	}

	/** Adds an image to the end of the stack. The argument 
	 * can be a full file path (e.g., "C:/Users/wayne/dir1/image.tif")
	 * if the 'path' argument in the constructor is "". File names
	 * that start with '.' are ignored. Does nothing when the planes
	 * come from an image.
	*/
	@Override
	public void addSlice(String fileName) {
		if (fileName==null) 
			throw new IllegalArgumentException("'fileName' is null!");
		if (fileName.startsWith(".") || hasImage())
			return;
		nSlices++;
	   if (nSlices==names.length) {
			String[] tmp = new String[nSlices*2];
			System.arraycopy(names, 0, tmp, 0, nSlices);
			names = tmp;
			tmp = new String[nSlices*2];
			System.arraycopy(labels, 0, tmp, 0, nSlices);
			labels = tmp;
		}
		names[nSlices-1] = fileName;
	}

   /** Does nothing. */
	@Override
	public void addSlice(String sliceLabel, Object pixels) {
	}

	/** Does nothing.. */
	@Override
	public void addSlice(String sliceLabel, ImageProcessor ip) {
	}
	
	/** Does noting. */
	@Override
	public void addSlice(String sliceLabel, ImageProcessor ip, int n) {
	}

	/**
	 * Deletes the specified slice, were 1<=n<=nslices, from the stack
	 * only (the file is not changed): the slices after it move up with
	 * their planes. ImageJ's Delete Slice and Make Substack delete through
	 * here, and its hyperstack delete then checks that the stack shrank.
	 */
	@Override
	public void deleteSlice(int n) {
		if (nSlices<1)
			return;
		if (n<1 || n>nSlices)
			throw new IllegalArgumentException("Argument out of range: "+n);
		if (hasImage()) {
			if (planeOfSlice == null) {
				planeOfSlice = new int[nSlices];
				for (int i=0; i<nSlices; i++) planeOfSlice[i] = i+1;
			}
			System.arraycopy(planeOfSlice, n, planeOfSlice, n-1, nSlices-n);
		}
		if (names!=null) {
			for (int i=n; i<nSlices; i++) {
				names[i-1] = names[i];
				labels[i-1] = labels[i];
			}
			names[nSlices-1] = null;
			labels[nSlices-1] = null;
		}
		nSlices--;
	}
	
	/** Deletes the last slice in the stack. */
	@Override
	public void deleteLastSlice() {
		int n = size();
		if (n>0)
			deleteSlice(n);
	}
	   
   /** Returns the pixel array for the specified slice, were 1<=n<=nslices. */
	@Override
	public Object getPixels(int n) {
		ImageProcessor ip = getProcessor(n);
		if (ip!=null)
			return ip.getPixels();
		else
			return null;
	}		
	
	 /** Assigns a pixel array to the specified slice,
		were 1<=n<=nslices. */
	@Override
	public void setPixels(Object pixels, int n) {
	}

   /** Returns an ImageProcessor for the specified slice,
		were 1<=n<=nslices. Returns null if the stack is empty.
	*/
	@Override
	public ImageProcessor getProcessor(int n) {
		if (path==null) {  //Help>Examples?JavaScript>Terabyte VirtualStack
			ImageProcessor ip = null;
			int w=getWidth(), h=getHeight();
			switch (bitDepth) {
				case 8: ip = new ByteProcessor(w,h); break;
				case 16: ip = new ShortProcessor(w,h); break;
				case 24: ip = new ColorProcessor(w,h); break;
				case 32: ip = new FloatProcessor(w,h); break;
			}
			if (generateData) {
				int value = 0;
				ImagePlus img = WindowManager.getCurrentImage();
				if (img!=null && img.getStackSize()==nSlices)
					value = img.getCurrentSlice()-1;
				if (bitDepth==16)
					value *= 256;
				if (bitDepth!=32) {
					for (int i=0; i<ip.getPixelCount(); i++)
						ip.set(i,value++);
				}
			}
			label(ip, ""+n, Color.white);
			return ip;
		}
		n = translate(n);  // update n for hyperstacks not in the default CZT order
		if (planeOfSlice != null) n = planeOfSlice[n-1];
		Object pixels;
		synchronized (this) {
			pixels = pixelsOf(n);
		}
		int w = getWidth(), h = getHeight();
		if (pixels instanceof byte[])
			return new ByteProcessor(w, h, (byte[]) pixels, getColorModel());
		if (pixels instanceof short[])
			return new ShortProcessor(w, h, (short[]) pixels, getColorModel());
		if (pixels instanceof int[])
			return new ColorProcessor(w, h, (int[]) pixels);
		return new FloatProcessor(w, h, (float[]) pixels, getColorModel());
	 }
	 private void label(ImageProcessor ip, String msg, Color color) {
		int size = getHeight()/20;
		if (size<9) size=9;
		Font font = new Font("Helvetica", Font.PLAIN, size);
		ip.setFont(font);
		ip.setAntialiasedText(true);
		ip.setColor(color);
		ip.drawString(msg, size, size*2);
	}
 
	/** Currently not implemented */
	@Override
	public int saveChanges(int n) {
		return -1;
	}
	
	/** Returns the number of slices in this stack. */
	@Override
	public int size() {
		return getSize();
	}

	@Override
	public int getSize() {
		return nSlices;
	}

	/** Returns the label of the Nth image. */
	@Override
	public String getSliceLabel(int n) {
		if (labels==null)
			return null;
		String label = labels[n-1];
		if (label==null)
			return names[n-1];
		else {
			if (label.startsWith("Label: "))  // slice label
				return label.substring(7,label.length());
			else
				return names[n-1]+"\n"+label;
		}
	}
	
	/** Returns null. */
	@Override
	public Object[] getImageArray() {
		return null;
	}

   /** Does nothing. */
	@Override
	public void setSliceLabel(String label, int n) {
	}

	/** Always return true. */
	@Override
	public boolean isVirtual() {
		return true;
	}

   /** Does nothing. */
	@Override
	public void trim() {
	}
	
	/** Returns the path to the directory containing the images. */
	@Override
	public String getDirectory() {
		return IJ.addSeparator(path);
	}
		
	/** Returns the file name of the specified slice, were 1<=n<=nslices. */
	@Override
	public String getFileName(int n) {
		return names[n-1];
	}
	
	/** Sets the bit depth (8, 16, 24 or 32). */
	@Override
	public void setBitDepth(int bitDepth) {
		this.bitDepth = bitDepth;
	}

	/** Returns the bit depth (8, 16, 24 or 32), or 0 if the bit depth is not known. */
	@Override
	public int getBitDepth() {
		return bitDepth;
	}
	
	/** Sorts a stack of DICOM files by image number; the planes of an image keep their order. */
	@Override
	public ImageStack sortDicom(String[] strings, String[] info, int maxDigits) {
		if (hasImage())
			return this;
		int n = size();
		String[] names2 = new String[n];
		for (int i=0; i<n; i++)
			names2[i] = names[i];
		for (int i=0; i<n; i++) {
			int slice = (int)Tools.parseDouble(strings[i].substring(strings[i].length()-maxDigits), 0.0);
			if (slice==0) return null;
			names[i] = names2[slice-1];
			labels[i] = info[slice-1];
		}
		return this;
	}
	
	/** Returns the ImagePlus Properties assoctated with the current slice, or null. */
	@Override
	public Properties getProperties() {
		return properties;
	}
	
	/** Sets the table that translates slice numbers of hyperstacks not in default CZT order. */
	@Override
	public void setIndexes(int[] indexes) {
		this.indexes = indexes;
	}
	
	/** Translates slice numbers of hyperstacks not in default CZT order. */
	@Override
	public int translate(int n) {
		int n2 = (indexes!=null&&indexes.length==getSize()) ? indexes[n-1]+1 : n;
		//IJ.log("translate: "+n+" "+n2+" "+getSize()+" "+(indexes!=null?indexes.length:null));
		return n2;
	}

	/**
	 * Does nothing: ImageJ's Reduce then makes a reduced copy of the stack
	 * in memory, as it does for any stack.
	 */
	@Override
	public void reduce(int factor) {
	}

} 

