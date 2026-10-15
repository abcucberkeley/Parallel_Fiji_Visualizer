package edu.abc.berkeley;

import java.io.File;
import java.util.Locale;

import javax.swing.JFileChooser;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
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

	/** Writes imp to the TIFF fileName, on the calling thread. */
	static void write(ImagePlus cImagePlus, String fileName) {
		// Special case for ImageJ images
		FileInfo info = cImagePlus.getOriginalFileInfo();
		if(info!=null) {
			if(info.description!=null) {
				if(info.description.contains("ImageJ")) {
					FileSaver fs = new FileSaver(cImagePlus);
			        fs.saveAsTiff(fileName);
					return;
				}
			}
		}

		
		
		// RGB images: cpp-tiff's writer is grayscale-only, use ImageJ's writer
		if(cImagePlus.getType() == ImagePlus.COLOR_RGB) {
			FileSaver fs = new FileSaver(cImagePlus);
			fs.saveAsTiff(fileName);
			return;
		}

		ImageStack cImageStack = cImagePlus.getImageStack();

		// May need to get bits another way
		int bits = cImageStack.getBitDepth();
		int x = cImageStack.getWidth();
		int y = cImageStack.getHeight();
		int z = cImageStack.getSize();
		// The planes themselves, not the stack's array of them: a virtual stack
		// has no such array (its planes are read here, one by one)
		Object[] cImageObj = new Object[z];
		for(int i = 0; i < z; i++) cImageObj[i] = cImageStack.getPixels(i+1);
		final long totalBytes = (long)x*y*z*(bits/8);
		final File outFile = new File(fileName);
		// The heuristic estimate is raised by the real growth of the output file.
		ProgressEstimator progress = ProgressEstimator.begin("writeTiff", totalBytes,
			z, "Writing "+outFile.getName(), () -> outFile.length() / (double)totalBytes);
		try {
			new ParallelWriteNative().parallelWriteTiff(fileName, cImageObj, x, y, z, bits);
			progress.finish();
		} finally {
			progress.abort();
		}

		/*
		if(bits == 8) {

		}
		else if(bits == 16) {

		}
		else if(bits == 32) {

		}
		else if(bits == 64) {

		}
		else {
			IJ.log("Data type not supported\n");
			return;
		}*/
		//currIm.show();
	}
}
