package edu.abc.berkeley;

import java.io.File;

import javax.swing.JFileChooser;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileInfo;
import ij.io.FileSaver;

public class PWT {

	PWT(){
		PWTC pwtc = new PWTC();
		JFileChooser chooser = new JFileChooser();
		chooser.setApproveButtonText("Save");
		chooser.setDialogTitle("Save");
		File f = null;
		int returnValue = chooser.showDialog(null,"Save");
		if(returnValue == JFileChooser.APPROVE_OPTION) {
			f = chooser.getSelectedFile() ;
		}
		if(f == null) return;
		String fileName = f.getPath();
		// Clean filename in case users didn't name it correctly
		// Check if the tiff extension is correct
		if(!(fileName.endsWith(".tif") || fileName.endsWith(".tiff"))) {
			// If the user didn't do the extension correctly then cut it off
			if(fileName.contains(".")) {
				fileName = fileName.substring(0, fileName.indexOf("."));
			}
			fileName = fileName.concat(".tif");
		}
		
		ImagePlus cImagePlus = IJ.getImage();
		if(cImagePlus == null) {
			IJ.log("No current image to save");
			return;
		}
		
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

		
		
		ImageStack cImageStack = cImagePlus.getImageStack();
		Object[] cImageObj = cImageStack.getImageArray();

		// May need to get bits another way
		int bits = cImageStack.getBitDepth();
		int x = cImageStack.getWidth();
		int y = cImageStack.getHeight();
		int z = cImageStack.getSize();
		pwtc.parallelWriteTiff(fileName, cImageObj, x, y, z, bits);
		
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
