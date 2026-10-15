package edu.abc.berkeley;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.DoubleSupplier;

import javax.swing.JFileChooser;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;

public class PWZ {

	PWZ(){
		JFileChooser chooser = new JFileChooser();
		chooser.setApproveButtonText("Save");
		chooser.setDialogTitle("Save as Zarr");
		File f = null;
		int returnValue = chooser.showDialog(null,"Save");
		if(returnValue == JFileChooser.APPROVE_OPTION) {
			f = chooser.getSelectedFile() ;
		}
		if(f == null) return;
		ImagePlus cImagePlus = IJ.getImage();
		//WindowManager.getCurrentImage();
		if(cImagePlus == null) return;
		if(cImagePlus.getType() == ImagePlus.COLOR_RGB) {
			IJ.log("RGB images cannot be saved as zarr\n");
			return;
		}
		write(cImagePlus, f);
	}

	/**
	 * Writes imp as a new Zarr v2 array in dir, in MATLAB's axis order as
	 * PetaKit5D writes them: (y, x, z), or (y, x, z, c, t) for a hyperstack,
	 * whose channels and time points would otherwise end up along z. The axis
	 * names go in xarray's _ARRAY_DIMENSIONS attribute, which the plugin reads
	 * back. Chunks are 256 pixels (or the image's size, if smaller) along y, x
	 * and z, and one plane along channels and time.
	 */
	static void write(ImagePlus imp, File dir) {
		ImageStack stack = imp.getImageStack();
		int n = stack.getSize();
		Object[] planes = new Object[n];
		for(int i = 0; i < n; i++) planes[i] = stack.getPixels(i+1);
		int bits = stack.getBitDepth();
		int w = stack.getWidth(), h = stack.getHeight();
		int c = imp.getNChannels(), z = imp.getNSlices(), t = imp.getNFrames();
		if((long)c*z*t != n) {
			c = 1;
			z = n;
			t = 1;
		}
		boolean hyperstack = c > 1 || t > 1;
		long plane = (long)w*h;
		long[] shape, chunks, strides;
		String[] names;
		if(hyperstack) {
			shape = new long[] { h, w, z, c, t };
			chunks = new long[] { Math.min(256, h), Math.min(256, w), Math.min(256, z), 1, 1 };
			// ImageJ's planes are in czt order: plane c + C*(z + Z*t)
			strides = new long[] { w, 1, plane*c, plane, plane*c*z };
			names = new String[] { "y", "x", "z", "c", "t" };
		}
		else {
			shape = new long[] { h, w, z };
			chunks = new long[] { Math.min(256, h), Math.min(256, w), Math.min(256, z) };
			strides = new long[] { w, 1, plane };
			names = new String[] { "y", "x", "z" };
		}

		final ChunkSampler sampler = new ChunkSampler(dir, shape, chunks);
		ProgressEstimator progress = ProgressEstimator.begin("writeZarr", plane*n*(bits/8),
			sampler.chunks, "Writing "+dir.getName(), sampler);
		try {
			new ParallelWriteNative().parallelWriteZarrArray(dir.getPath(), planes, shape, chunks, strides, "zstd", bits);
			StringBuilder json = new StringBuilder("{\"_ARRAY_DIMENSIONS\": [");
			for(int i = 0; i < names.length; i++) json.append(i > 0 ? ", " : "").append('"').append(names[i]).append('"');
			Files.write(new File(dir, ZarrMetadata.V2_ATTRIBUTES).toPath(), json.append("]}\n").toString().getBytes(StandardCharsets.UTF_8));
			progress.finish();
		}
		catch(IOException e) {
			throw new RuntimeException("Could not write the axis names of " + dir.getName() + ": " + e.getMessage(), e);
		}
		finally {
			progress.abort();
			sampler.stop();
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
