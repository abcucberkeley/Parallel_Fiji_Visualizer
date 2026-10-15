package edu.abc.berkeley;

import java.io.File;

import ij.ImagePlus;
import ij.ImageStack;
import ij.ParallelImagePlus;
import ij.ParallelVirtualStack;
import ij.io.FileInfo;
import ij.plugin.HyperStackConverter;
import ij.plugin.Memory;

/**
 * Opens a Zarr array, or an OME-Zarr image, as an ImageJ image: a hyperstack
 * when it has several channels or time points, and a virtual stack when it
 * does not fit in memory. {@link ZarrImage} finds the array and its axes.
 */
public class PRZ {
	private ImagePlus imp;

	/**
	 * Opens the window [start, end) of the image in the folder fileName: X is
	 * its rows (y), Y its columns (x) and Z its planes, as in the plugin's
	 * (rows, columns, planes) arrays, and an end of -1 is the end of the image.
	 * Every channel and time point is read.
	 */
	public PRZ(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, boolean showImage){
		File f = new File(fileName);
		ZarrImage zarr = ZarrImage.open(f);
		long y0 = startX, y1 = endX == -1 ? zarr.height() : endX;
		long x0 = startY, x1 = endY == -1 ? zarr.width() : endY;
		long z0 = startZ, z1 = endZ == -1 ? zarr.depth() : endZ;
		if(x0 < 0 || y0 < 0 || z0 < 0 || x1 > zarr.width() || y1 > zarr.height() || z1 > zarr.depth() || x0 >= x1 || y0 >= y1 || z0 >= z1)
			throw new IllegalArgumentException("The window to read lies outside " + f.getName());
		long w = x1-x0, h = y1-y0, d = z1-z0, c = zarr.channels(), t = zarr.frames();

		// ImageJ shows at most 32 bits per pixel: wider types become float
		int tBits = zarr.displayBits();
		long memNeeded = w*h*d*c*t*(Math.max(zarr.bits, tBits)/8);
		boolean isVirtual = memNeeded > new Memory().maxMemory();

		ImagePlus imp;
		if(!isVirtual) {
			Object[] planes = zarr.read(new long[] { x0, y0, z0, 0, 0 }, new long[] { x1, y1, z1, c, t }, "Reading "+f.getName());
			ImageStack stack = new ImageStack((int)w, (int)h);
			stack.setBitDepth(tBits);
			for(Object plane : planes) stack.addSlice(null, plane);
			imp = new ImagePlus(zarr.title, stack);
			// The planes are in ImageJ's czt order
			if(c > 1 || t > 1) imp = HyperStackConverter.toHyperStack(imp, (int)c, (int)d, (int)t, "xyczt", c <= 7 ? "Composite" : "Color");
			FileInfo fileInfo = imp.getOriginalFileInfo();
			if (fileInfo == null) {
			    fileInfo = new FileInfo(); // Create a new FileInfo if none exists
			}
			fileInfo.directory = f.getParent();
			fileInfo.fileName = f.getName();
			imp.setFileInfo(fileInfo);
		}
		else {
			ParallelVirtualStack pVStack = new ParallelVirtualStack(zarr, x0, y0, z0, (int)w, (int)h, (int)d);
			imp = new ParallelImagePlus(zarr.title, pVStack);
			if(c > 1 || t > 1) {
				imp.setDimensions((int)c, (int)d, (int)t);
				imp.setOpenAsHyperStack(true);
			}
		}
		zarr.calibrate(imp);
		this.imp = imp;
		if(showImage) imp.show();
	}

	// Open the entire image
	public PRZ(String fileName, boolean showImage){
		this(fileName, 0, 0, 0, -1, -1, -1, showImage);
	}

	// Open the entire image and always show the image after
	public PRZ(String fileName){
		this(fileName, 0, 0, 0, -1, -1, -1, true);
	}

	public ImagePlus getImp() {
		return this.imp;
	}


}
