package edu.abc.berkeley;

import java.io.File;

import javax.swing.JFileChooser;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;

public class PWZ {
	PWZ(){
		ParallelWriteNative pwzc = new ParallelWriteNative();
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
		final long totalBytes = (long)x*y*z*(bits/8);
		final long expectedChunks = (((long)y+255)/256) * (((long)x+255)/256) * (((long)z+255)/256);
		final File zarrDir = f;
		// Real progress is available for zarr writes: chunk files appear flat in
		// the zarr folder ("x.y.z" names) as they complete.
		ProgressEstimator progress = ProgressEstimator.begin("writeZarr", totalBytes,
			expectedChunks, "Writing "+f.getName(), () -> {
				String[] names = zarrDir.list();
				if(names == null) return 0.0;
				int n = 0;
				for(String name : names) {
					if(!name.startsWith(".")) n++;
				}
				return n / (double)expectedChunks;
			});
		try {
			pwzc.parallelWriteZarr(fileName, cImageObj, 0, 0, 0, y, x, z, 256, 256, 256, 1, "zstd", 1, bits);
			progress.finish();
		} finally {
			progress.abort();
		}
	}
}
