package edu.abc.berkeley;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.DoubleSupplier;

import javax.swing.JFileChooser;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.ParallelVirtualStack;
import ij.WindowManager;

public class PWZ {

	/**
	 * Save as Zarr: asks where to save the current image, on Swing's event
	 * thread (the button's), then saves it on a thread of its own.
	 */
	PWZ(){
		ImagePlus cImagePlus = WindowManager.getCurrentImage();
		if(cImagePlus == null) {
			IJ.noImage();
			return;
		}
		if(cImagePlus.getType() == ImagePlus.COLOR_RGB) {
			IJ.log("RGB images cannot be saved as zarr\n");
			return;
		}
		JFileChooser chooser = new JFileChooser();
		chooser.setApproveButtonText("Save");
		chooser.setDialogTitle("Save as Zarr");
		File f = null;
		int returnValue = chooser.showDialog(null,"Save");
		if(returnValue == JFileChooser.APPROVE_OPTION) {
			f = chooser.getSelectedFile() ;
		}
		if(f == null) return;
		save(cImagePlus, f);
	}

	/** Saves imp as the Zarr dir on a thread of its own, the image locked meanwhile (see Background.save). */
	static Thread save(ImagePlus imp, File dir) {
		return Background.save(imp, dir.getName(), () -> write(imp, dir));
	}

	/**
	 * Writes imp as a new Zarr v2 array in dir, in MATLAB's axis order as
	 * PetaKit5D writes them: (y, x, z), or (y, x, z, c, t) for a hyperstack,
	 * whose channels and time points would otherwise end up along z. The axis
	 * names go in xarray's _ARRAY_DIMENSIONS attribute, which the plugin reads
	 * back. Chunks are 256 pixels (or the image's size, if smaller) along y, x
	 * and z, and one plane along channels and time.
	 *
	 * The array is written in runs of whole chunk rows along z (of a channel
	 * and time point, or of several), each copied to native memory and written
	 * on its own: the image is not held twice, and a virtual stack's planes are
	 * read as they go (with its read-ahead off, see PWT.pauseReadAhead). A new
	 * array is written under a name of its own beside dir, which becomes dir
	 * once complete, so that a save that fails leaves nothing under that name;
	 * an existing folder of that name (an array saved before) is written in
	 * place, as it always was.
	 */
	static void write(ImagePlus imp, File dir) {
		ImageStack stack = imp.getImageStack();
		boolean paused = PWT.pauseReadAhead(stack);
		try {
			writeZarr(imp, stack, dir.getAbsoluteFile());
		}
		finally {
			if (paused) ((ParallelVirtualStack) stack).setReadAhead(true);
		}
	}

	private static void writeZarr(ImagePlus imp, ImageStack stack, File dir) {
		int n = stack.getSize();
		int bits = stack.getBitDepth();
		int w = stack.getWidth(), h = stack.getHeight();
		int c = imp.getNChannels(), z = imp.getNSlices(), t = imp.getNFrames();
		if((long)c*z*t != n) {
			c = 1;
			z = n;
			t = 1;
		}
		if(bits != 8 && bits != 16 && bits != 32) throw new RuntimeException("Unsupported bit depth for Zarr writing (expected 8, 16 or 32)");
		boolean hyperstack = c > 1 || t > 1;
		long plane = (long)w*h, planeBytes = plane*(bits/8);
		long[] shape, chunks;
		String[] names;
		if(hyperstack) {
			shape = new long[] { h, w, z, c, t };
			chunks = new long[] { Math.min(256, h), Math.min(256, w), Math.min(256, z), 1, 1 };
			names = new String[] { "y", "x", "z", "c", "t" };
		}
		else {
			shape = new long[] { h, w, z };
			chunks = new long[] { Math.min(256, h), Math.min(256, w), Math.min(256, z) };
			names = new String[] { "y", "x", "z" };
		}

		// A run: the planes of z rows [z0, z0 + zr) of channels [c0, c0 + cr)
		// and time points [t0, t0 + tr), in whole chunk rows along z, as many as
		// zarrRunBytes holds (at least one chunk row of one channel and time point).
		// They grow along z first, then over the channels, then the time points,
		// so that each is a box of whole chunks.
		int depth = (int)chunks[2];
		int[] run = runOf(Math.max(depth, ParallelWriteNative.zarrRunBytes() / planeBytes), depth, z, c, t);
		boolean inPlace = dir.isDirectory() && !isEmptyFolder(dir);
		File out = inPlace ? dir : PWT.partOf(dir);
		final ChunkSampler sampler = new ChunkSampler(out, shape, chunks);
		ProgressEstimator progress = ProgressEstimator.begin("writeZarr", planeBytes*n,
			sampler.chunks, "Writing "+dir.getName(), sampler);
		boolean done = false;
		try {
			try (ParallelWriteNative.PlaneBuffer buffer = ParallelWriteNative.PlaneBuffer.allocate(plane, bits,
				run[0]*run[1]*run[2], depth)) {
				// (runs of fewer chunk rows when there is less native memory than asked)
				run = runOf(buffer.planes, depth, z, c, t);
				int zr = run[0], cr = run[1], tr = run[2];
				buffer.natives.writeZarrMetadata(out.getPath(), shape, chunks, "zstd", bits);
				for(int t0 = 0; t0 < t; t0 += tr)
					for(int c0 = 0; c0 < c; c0 += cr)
						for(int z0 = 0; z0 < z; z0 += zr) {
							int zn = Math.min(zr, z - z0), cn = Math.min(cr, c - c0), tn = Math.min(tr, t - t0);
							// In the run's buffer, as in ImageJ's stack (plane c + C*(z +
							// Z*t)), channels go fastest, then z, then time; the planes
							// are fetched a channel and time point at a time, in order
							// along z, as a virtual stack reads them best
							for(int ti = 0; ti < tn; ti++)
								for(int ci = 0; ci < cn; ci++)
									buffer.put(stack, 1 + (c0 + ci) + c*(z0 + z*(t0 + ti)), c, zn, ci + cn*zn*ti, cn);
							long[] from, to, strides;
							if(hyperstack) {
								from = new long[] { 0, 0, z0, c0, t0 };
								to = new long[] { h, w, z0 + zn, c0 + cn, t0 + tn };
								strides = new long[] { w, 1, plane*cn, plane, plane*cn*zn };
							}
							else {
								from = new long[] { 0, 0, z0 };
								to = new long[] { h, w, z0 + zn };
								strides = new long[] { w, 1, plane };
							}
							buffer.natives.writeZarrPlanes(out.getPath(), buffer.address(), buffer.capacity(), shape, chunks,
								from, to, strides, "zstd", bits);
						}
			}
			StringBuilder json = new StringBuilder("{\"_ARRAY_DIMENSIONS\": [");
			for(int i = 0; i < names.length; i++) json.append(i > 0 ? ", " : "").append('"').append(names[i]).append('"');
			Files.write(new File(out, ZarrMetadata.V2_ATTRIBUTES).toPath(), json.append("]}\n").toString().getBytes(StandardCharsets.UTF_8));
			sampler.stop();
			if(!inPlace) {
				// An empty folder of that name (made in the file chooser) gives way
				if(dir.isDirectory()) Files.delete(dir.toPath());
				PWT.moveIntoPlace(out, dir);
			}
			done = true;
			progress.finish();
		}
		catch(IOException e) {
			throw new RuntimeException("Could not save " + dir.getName() + ": " + e, e);
		}
		finally {
			progress.abort();
			sampler.stop();
			// After a failure (the writer has closed every file by now: Windows deletes no open file)
			if(!done && !inPlace && out.exists() && !deleteTree(out)) IJ.log("Could not remove the incomplete " + out);
		}
	}

	/**
	 * The z rows, channels and time points of a run of at most budget planes
	 * (at least depth): whole chunk rows along z, or all of z and then
	 * several channels, or all of those and several time points.
	 */
	static int[] runOf(long budget, int depth, int z, int c, int t) {
		int zr = budget >= z ? z : (int)(budget / depth * depth);
		int cr = zr < z ? 1 : (int)Math.min(c, budget / z);
		int tr = zr < z || cr < c ? 1 : (int)Math.min(t, budget / ((long)z*c));
		return new int[] { zr, cr, tr };
	}

	private static boolean isEmptyFolder(File dir) {
		String[] names = dir.list();
		return names != null && names.length == 0;
	}

	/** Deletes folder and everything in it (a save's own part); false if something is left. */
	static boolean deleteTree(File folder) {
		try {
			Files.walkFileTree(folder.toPath(), new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
					if(e != null) throw e;
					Files.delete(d);
					return FileVisitResult.CONTINUE;
				}
			});
			return true;
		}
		catch(IOException e) {
			return false;
		}
	}

	/**
	 * The progress of a write, from whether a spread of its chunk files exist
	 * yet. Chunk files sit in nested folders (0/0/0), too many to list while
	 * writing, so up to 64 of them, evenly spaced through the chunk grid, are
	 * checked twice a second on a thread of their own, keeping the file system
	 * off the Swing thread that polls the fraction. Chunks appear under their
	 * names only once written; chunks of zeros are not written at all, so the
	 * fraction can only fall short, and the bar is then paced by time.
	 */
	private static final class ChunkSampler implements DoubleSupplier {
		final long chunks;
		private final File[] samples;
		private volatile double fraction = 0;
		private volatile boolean running = true;

		ChunkSampler(File dir, long[] shape, long[] chunkShape) {
			int nd = shape.length;
			long[] grid = new long[nd];
			long count = 1;
			for(int d = 0; d < nd; d++) {
				grid[d] = (shape[d] + chunkShape[d] - 1) / chunkShape[d];
				count *= grid[d];
			}
			chunks = count;
			int k = (int)Math.min(64, count);
			samples = new File[k];
			for(int s = 0; s < k; s++) {
				// The (s + 1/2)-th of k even steps through the chunks, last axis fastest
				long index = (long)((s + 0.5) * count / k);
				StringBuilder key = new StringBuilder();
				long[] g = new long[nd];
				for(int d = nd-1; d >= 0; d--) {
					g[d] = index % grid[d];
					index /= grid[d];
				}
				for(int d = 0; d < nd; d++) key.append(d > 0 ? "/" : "").append(g[d]);
				samples[s] = new File(dir, key.toString());
			}
			Thread t = new Thread(() -> {
				while(running) {
					int done = 0;
					for(File f : samples) if(f.isFile()) done++;
					fraction = done / (double)samples.length;
					try {
						Thread.sleep(500);
					}
					catch(InterruptedException e) {
						return;
					}
				}
			}, "PFV Zarr write progress");
			t.setDaemon(true);
			t.start();
		}

		@Override
		public double getAsDouble() {
			return fraction;
		}

		void stop() {
			running = false;
		}
	}
}
