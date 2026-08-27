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
import ij.measure.Calibration;

public class PRZ {
	private ImagePlus imp;
	private ImageStack stack;
	private VirtualStack vStack;

	public PRZ(String fileName, long startX, long startY, long startZ, long endX, long endY, long endZ, boolean showImage){
		ParallelReadNative przc = new ParallelReadNative();
		long bits = przc.getZarrDataType(fileName);
		long[] dims = przc.getZarrDims(fileName);
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
			// The dtype string distinguishes signed/unsigned/float, which the bit
			// count alone cannot (e.g. "<i2" vs "<u2").
			String dtype = przc.getZarrDtype(fileName);
			char dtypeKind = (dtype != null && dtype.length() == 3) ? dtype.charAt(1) : 'u';
			boolean int8Cal = false, int16Cal = false;
			// Zarr reads parallelize over chunks; estimate the count assuming the
			// 256^3 chunk shape our writer uses.
			long chunkEstimate = ((dims[0]+255)/256) * ((dims[1]+255)/256) * ((dims[2]+255)/256);
			ProgressEstimator progress = ProgressEstimator.begin("readZarr",
				dims[0]*dims[1]*dims[2]*(bits/8), chunkEstimate, "Reading "+f.getName());
			try {
				if(bits == 8) {
					byte im[][];
					if(dtypeKind == 'i') {
						// Signed bytes are shifted to 0-255; a calibration maps them back
						im = przc.parallelReadZarrINT8(fileName,startX,startY,startZ,dims[0],dims[1],dims[2]);
						int8Cal = true;
					}
					else {
						im = przc.parallelReadZarrUINT8(fileName,startX,startY,startZ,dims[0],dims[1],dims[2]);
					}
					for(int i = 0; i < dims[2]; i++){
						stack.addSlice(null, im[i]);
					}
				}
				else if (bits == 16) {
					short im[][];
					if(dtypeKind == 'i') {
						// Shifted to 0-65535; the signed-16 calibration maps back
						im = przc.parallelReadZarrINT16(fileName,startX,startY,startZ,dims[0],dims[1],dims[2]);
						int16Cal = true;
					}
					else {
						im = przc.parallelReadZarrUINT16(fileName,startX,startY,startZ,dims[0],dims[1],dims[2]);
					}
					for(int i = 0; i < dims[2]; i++){
						stack.addSlice(null, im[i]);
					}
				}
				else if (bits == 32) {
					float im[][];
					if(dtypeKind == 'i') im = przc.parallelReadZarrINT32(fileName,startX,startY,startZ,dims[0],dims[1],dims[2]);
					else if(dtypeKind == 'u') im = przc.parallelReadZarrUINT32(fileName,startX,startY,startZ,dims[0],dims[1],dims[2]);
					else im = przc.parallelReadZarrFLOAT(fileName,startX,startY,startZ,dims[0],dims[1],dims[2]);
					for(int i = 0; i < dims[2]; i++){
						stack.addSlice(null, im[i]);
					}
				}
				else if(bits == 64) {
					// ImageJ has no double or 64-bit integer type; all become float
					float im[][];
					if(dtypeKind == 'i') im = przc.parallelReadZarrINT64(fileName,startX,startY,startZ,dims[0],dims[1],dims[2]);
					else if(dtypeKind == 'u') im = przc.parallelReadZarrUINT64(fileName,startX,startY,startZ,dims[0],dims[1],dims[2]);
					else im = przc.parallelReadZarrDOUBLE(fileName,startX,startY,startZ,dims[0],dims[1],dims[2]);
					for(int i = 0; i < dims[2]; i++){
						stack.addSlice(null, im[i]);
					}
				}
				else {
					IJ.log("Data type not supported\n");
					return;
				}
				progress.finish();
			} finally {
				progress.abort();
			}

			ImagePlus imp = new ImagePlus(f.getName(),stack);
			FileInfo fileInfo = imp.getOriginalFileInfo();
			if (fileInfo == null) {
			    fileInfo = new FileInfo(); // Create a new FileInfo if none exists
			}
			fileInfo.directory = f.getParent();
			fileInfo.fileName = f.getName();
			imp.setFileInfo(fileInfo);
			if(int8Cal) {
				// Map the shifted signed bytes back to their real values
				imp.getLocalCalibration().setFunction(Calibration.STRAIGHT_LINE, new double[]{-128.0,1.0}, "gray value");
			}
			if(int16Cal) imp.getLocalCalibration().setSigned16BitCalibration();
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
