package edu.abc.berkeley;

import java.io.File;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.ParallelImagePlus;
import ij.ParallelVirtualStack;
import ij.VirtualStack;
import ij.plugin.Memory;
import ij.io.FileInfo;

public class PRZ {
	private ImagePlus imp;
	private ImageStack stack;
	private VirtualStack vStack;

	public PRZ(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, boolean showImage){
		PRZC przc = new PRZC();
		long bits = przc.getDataType(fileName);
		long[] dims = przc.getImageDims(fileName);
		if(endX == -1) endX = dims[0];
		if(endY == -1) endY = dims[1];
		if(endZ == -1) endZ = dims[2];
		
		dims[0] = endX-startX;
		dims[1] = endY-startY;
		dims[2] = endZ-startZ;
		
		File f = new File(fileName);


		Memory mem = new Memory();
		long memNeeded = dims[0]*dims[1]*dims[2]*(bits/8);
		boolean isVirtual = memNeeded > mem.maxMemory();
		
		//----------TESTING----------
		//isVirtual = memNeeded > 1843201;
		//isVirtual = true;

		int tBits = (int)bits;
		if (tBits > 32) tBits = 32;

		//ImageStack stack = ImageStack.create((int)dims[1],(int)dims[0],0,tBits);
		if(!isVirtual) {
			stack = new ImageStack((int)dims[1],(int)dims[0]);
			stack.setBitDepth(tBits);
			if(bits == 8) {
				byte im[][] = przc.parallelReadZarrUINT8(fileName,startX,startY,startZ,dims[0],dims[1],dims[2]);
				for(int i = 0; i < dims[2]; i++){
					stack.addSlice(null, im[i]);
				}
			}
			else if (bits == 16) {
				
				short im[][] = przc.parallelReadZarrUINT16(fileName,startX,startY,startZ,dims[0],dims[1],dims[2]);
				for(int i = 0; i < dims[2]; i++){
					stack.addSlice(null, im[i]);
				}
			}
			else if (bits == 32) {
				float im[][] = przc.parallelReadZarrFLOAT(fileName,startX,startY,startZ,dims[0],dims[1],dims[2]); 
				for(int i = 0; i < dims[2]; i++){
					stack.addSlice(null, im[i]);
				}
			}
			else if(bits == 64) {
				float im[][] = przc.parallelReadZarrDOUBLE(fileName,startX,startY,startZ,dims[0],dims[1],dims[2]);
				for(int i = 0; i < dims[2]; i++){
					stack.addSlice(null, im[i]);
				}
			}
			else {
				IJ.log("Data type not supported\n");
				return;
			}

			ImagePlus imp = new ImagePlus(f.getName(),stack);
			FileInfo fileInfo = imp.getOriginalFileInfo();
			if (fileInfo == null) {
			    fileInfo = new FileInfo(); // Create a new FileInfo if none exists
			}
			fileInfo.directory = f.getParent();
			fileInfo.fileName = f.getName();
			imp.setFileInfo(fileInfo);
			if(showImage) imp.show();
			else this.imp = imp;
		}
		else {
			ParallelVirtualStack pVStack = new ParallelVirtualStack((int)dims[1],(int)dims[0],(int)dims[2],null,fileName);
			pVStack.setBitDepth(tBits);
			ParallelImagePlus imp = new ParallelImagePlus(f.getName(),pVStack);
			imp.show();
			/*
			vStack = new VirtualStack((int)dims[1],(int)dims[0],null,fileName);
			vStack.setBitDepth(tBits);
			vStack.addSlice(fileName);
			ImagePlus imp = new ImagePlus(f.getName(),vStack);
			imp.show();
			*/

		}
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
