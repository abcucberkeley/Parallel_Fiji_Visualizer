package edu.abc.berkeley;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Random;

import javax.swing.JFileChooser;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.ParallelVirtualStack;
import ij.WindowManager;
import ij.io.FileInfo;
import ij.io.FileSaver;
import ij.io.SaveDialog;

public class PWT {

	/**
	 * Save as Tiff: asks where to save the current image, on Swing's event
	 * thread (the button's), then saves it on a thread of its own.
	 */
	PWT(){
		ImagePlus cImagePlus = WindowManager.getCurrentImage();
		if(cImagePlus == null) {
			IJ.noImage();
			return;
		}
		JFileChooser chooser = new JFileChooser();
		chooser.setApproveButtonText("Save");
		chooser.setDialogTitle("Save");
		File f = null;
		int returnValue = chooser.showDialog(null,"Save");
		if(returnValue == JFileChooser.APPROVE_OPTION) {
			f = chooser.getSelectedFile() ;
		}
		if(f == null) return;
		save(cImagePlus, tiffFile(f));
	}

	/** Saves imp to file on a thread of its own, the image locked meanwhile (see Background.save). */
	static Thread save(ImagePlus imp, File file) {
		return Background.save(imp, file.getName(), () -> write(imp, file.getPath()));
	}

	/**
	 * The file chosen, named .tif in case users didn't name it correctly: any
	 * other extension is replaced, as ImageJ names the files it saves. Only
	 * the name changes, never the folders it is in.
	 */
	static File tiffFile(File f) {
		String name = f.getName(), lower = name.toLowerCase(Locale.ROOT);
		if(lower.endsWith(".tif") || lower.endsWith(".tiff")) return f;
		return new File(f.getParentFile(), SaveDialog.setExtension(name, ".tif"));
	}

	/**
	 * Writes imp to the TIFF fileName, on the calling thread. A virtual
	 * stack's read-ahead is off meanwhile (see pauseReadAhead).
	 */
	static void write(ImagePlus cImagePlus, String fileName) {
		ImageStack stack = cImagePlus.getImageStack();
		boolean paused = pauseReadAhead(stack);
		try {
			writeTiff(cImagePlus, stack, fileName);
		}
		finally {
			if (paused) ((ParallelVirtualStack) stack).setReadAhead(true);
		}
	}

	private static void writeTiff(ImagePlus cImagePlus, ImageStack cImageStack, String fileName) {
		// Special case for ImageJ images
		FileInfo info = cImagePlus.getOriginalFileInfo();
		if(info!=null) {
			if(info.description!=null) {
				if(info.description.contains("ImageJ")) {
					saveWithImageJ(cImagePlus, fileName);
					return;
				}
			}
		}

		// RGB images: cpp-tiff's writer is grayscale-only, use ImageJ's writer
		if(cImagePlus.getType() == ImagePlus.COLOR_RGB) {
			saveWithImageJ(cImagePlus, fileName);
			return;
		}

		// May need to get bits another way
		int bits = cImageStack.getBitDepth();
		int x = cImageStack.getWidth();
		int y = cImageStack.getHeight();
		int z = cImageStack.getSize();
		if(bits != 8 && bits != 16 && bits != 32) throw new RuntimeException("Unsupported bit depth for TIFF writing (expected 8, 16 or 32)");
		final long planeElems = (long)x*y, planeBytes = planeElems*(bits/8);
		final long totalBytes = planeBytes*z;
		final File outFile = new File(fileName).getAbsoluteFile();
		// Written under a name of its own beside the file, which becomes the
		// file only once complete: a save that fails leaves the folder as it
		// was, a file of that name included
		final File part = partOf(outFile);
		// The heuristic estimate is raised by the real growth of the output file.
		ProgressEstimator progress = ProgressEstimator.begin("writeTiff", totalBytes,
			z, "Writing "+outFile.getName(), () -> part.length() / (double)totalBytes);
		try {
			makeFolders(outFile);
			// Runs of slices, each copied to native memory and written (the
			// first as a new file, the others appended to it): the image is not
			// held twice, and a virtual stack's planes are read as they go.
			int perRun = (int)Math.max(1, Math.min(z, ParallelWriteNative.tiffRunBytes() / planeBytes));
			try (ParallelWriteNative.PlaneBuffer run = ParallelWriteNative.PlaneBuffer.allocate(planeElems, bits, perRun, 1)) {
				perRun = run.planes;
				for(int first = 0; first < z; first += perRun) {
					int n = Math.min(perRun, z - first);
					run.put(cImageStack, first + 1, 1, n, 0, 1);
					run.natives.writeTiffPlanes(part.getPath(), run.address(), run.capacity(), x, y, n, bits, first > 0, z);
				}
			}
			moveIntoPlace(part, outFile);
			progress.finish();
		}
		catch(IOException e) {
			throw new RuntimeException("Could not save " + outFile.getName() + ": " + e, e);
		}
		catch(RuntimeException e) {
			throw naming(e, part, outFile);
		}
		finally {
			progress.abort();
			// After a failure (the writers have closed it by now: Windows deletes no open file)
			if(part.exists() && !part.delete()) IJ.log("Could not remove the incomplete " + part);
		}
	}

	/**
	 * Saves with ImageJ's FileSaver, which reads a virtual stack's planes one
	 * by one. A file it could not complete is removed, unless it was there
	 * before (ImageJ refuses to save a virtual stack over its own file).
	 */
	private static void saveWithImageJ(ImagePlus imp, String fileName) {
		File file = new File(fileName);
		boolean existed = file.exists();
		if(new FileSaver(imp).saveAsTiff(fileName)) return;
		if(!existed && file.exists() && !file.delete()) IJ.log("Could not remove the incomplete " + file);
		throw new RuntimeException("ImageJ could not save " + file.getName() + " (see its message)");
	}

	/**
	 * Turns the read-ahead of a ParallelVirtualStack off for a save, which
	 * reads its planes in runs rather than as scrolling does: reads ahead
	 * would read planes the save is not after, and one still under way when
	 * the save ends would hold the file, which Windows then neither replaces
	 * nor deletes. Returns whether it did; the switch has no getter, and only
	 * tests ever turn it off, so the save turns it on again after.
	 */
	static boolean pauseReadAhead(ImageStack stack) {
		if(!(stack instanceof ParallelVirtualStack)) return false;
		((ParallelVirtualStack) stack).setReadAhead(false);
		return true;
	}

	/**
	 * The name a save writes file under until it is complete: hidden, beside
	 * it (so the rename stays on one file system), and of this save alone.
	 */
	static File partOf(File file) {
		File f = file.getAbsoluteFile();
		return new File(f.getParentFile(), "." + f.getName() + "." + Long.toHexString(PART_IDS.nextLong() & Long.MAX_VALUE) + ".part");
	}

	private static final Random PART_IDS = new Random();

	/**
	 * e, or for a message that names the part a save writes (as the natives'
	 * do when they cannot pass its path on), the same message naming the file
	 * the user chose instead: the part is hidden, and its name means nothing
	 * to them.
	 */
	static RuntimeException naming(RuntimeException e, File part, File file) {
		String msg = e.getMessage();
		if(msg == null || part.equals(file) || !msg.contains(part.getPath())) return e;
		return new RuntimeException(msg.replace(part.getPath(), file.getPath()), e);
	}

	/** Makes the folders file is to go in, as cpp-tiff did for the files it wrote. */
	static void makeFolders(File file) throws IOException {
		File parent = file.getAbsoluteFile().getParentFile();
		if(parent != null) Files.createDirectories(parent.toPath());
	}

	/**
	 * Renames the complete part to file, replacing a file of that name (in one
	 * step where the file system allows: no reader ever sees half a file).
	 */
	static void moveIntoPlace(File part, File file) throws IOException {
		try {
			Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE);
		}
		catch(AtomicMoveNotSupportedException e) {
			Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
