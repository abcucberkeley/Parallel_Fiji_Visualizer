package edu.abc.berkeley;

import java.io.File;

import javax.swing.JFileChooser;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;

public class PWZ {
	PWZ(){
		PWZC pwzc = new PWZC();
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
		ImageStack cImageStack = cImagePlus.getImageStack();
		Object[] cImageObj = cImageStack.getImageArray();
	
		// May need to get bits another way
		int bits = cImageStack.getBitDepth();
		String fileName = f.getPath();
		int x = cImageStack.getWidth();
		int y = cImageStack.getHeight();
		int z = cImageStack.getSize();
		pwzc.parallelWriteZarr(fileName, cImageObj, 0, 0, 0, y, x, z, 256, 256, 256, 1, "lz4", 1, bits);
	}
}
