package edu.abc.berkeley;

import java.io.File;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.ParallelVirtualStack;
import ij.VirtualStack;
import ij.plugin.Memory;

public class PRZ {
	private ImagePlus imp;
	private ImageStack stack;
	private VirtualStack vStack;

	public PRZ(String fileName, boolean showImage){
		PRZC przc = new PRZC();
		long bits = przc.getDataType(fileName);
		long[] dims = przc.getImageDims(fileName);

		File f = new File(fileName);


		Memory mem = new Memory();
		long memNeeded = dims[0]*dims[1]*dims[2]*(bits/8);
		boolean isVirtual = memNeeded > mem.maxMemory();
		
		//TESTING
		//isVirtual = true;

		int tBits = (int)bits;
		if (tBits > 32) tBits = 32;

		//ImageStack stack = ImageStack.create((int)dims[1],(int)dims[0],0,tBits);
		if(!isVirtual) {
			stack = new ImageStack((int)dims[1],(int)dims[0]);
			stack.setBitDepth(tBits);
			if(bits == 8) {
				byte im[][] = przc.parallelReadZarrUINT8(fileName,0,0,0,dims[0],dims[1],dims[2]);
				for(int i = 0; i < dims[2]; i++){
					stack.addSlice(null, im[i]);
				}
			}
			else if (bits == 16) {
				
				short im[][] = przc.parallelReadZarrUINT16(fileName,0,0,0,dims[0],dims[1],dims[2]);
				for(int i = 0; i < dims[2]; i++){
					stack.addSlice(null, im[i]);
				}
			}
			else if (bits == 32) {
				float im[][] = przc.parallelReadZarrFLOAT(fileName,0,0,0,dims[0],dims[1],dims[2]); 
				for(int i = 0; i < dims[2]; i++){
					stack.addSlice(null, im[i]);
				}
			}
			else if(bits == 64) {
				float im[][] = przc.parallelReadZarrDOUBLE(fileName,0,0,0,dims[0],dims[1],dims[2]);
				for(int i = 0; i < dims[2]; i++){
					stack.addSlice(null, im[i]);
				}
			}
			else {
				IJ.log("Data type not supported\n");
				return;
			}

			ImagePlus imp = new ImagePlus(f.getName(),stack);
			if(showImage) imp.show();
			else this.imp = imp;
		}
		else {
			ParallelVirtualStack pVStack = new ParallelVirtualStack((int)dims[1],(int)dims[0],null,fileName);
			pVStack.setBitDepth(tBits);
			/*
			vStack = new VirtualStack((int)dims[1],(int)dims[0],null,fileName);
			vStack.setBitDepth(tBits);
			vStack.addSlice(fileName);
			ImagePlus imp = new ImagePlus(f.getName(),vStack);
			imp.show();
			*/

		}
	}

	public PRZ(String fileName){
		this(fileName,true);
	}

	public ImagePlus getImp() {
		return this.imp;
	}


}
