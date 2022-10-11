package edu.abc.berkeley;

import java.awt.image.ColorModel;
import java.io.File;
import java.util.Properties;
import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.io.FileInfo;
import ij.io.Opener;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ij.io.FileOpener;

public class PRT {
	private ImagePlus imp;
	private static boolean showConflictMessage = true;
	
	//private static final Object lock = new Object();
	
	public PRT(String fileName, boolean showImage){
		//synchronized(lock) {
		//IJ.showProgress(0);
		FileInfo[] info = Opener.getTiffFileInfo(fileName);
		
		// Back up method if no file info is available
		if(info == null) {
			PRT_NoFileInfo(fileName);
			return;
		}
		PRTC prtc = new PRTC();
		long bits = prtc.getDataType(fileName);
		long[] dims = prtc.getImageDims(fileName);


		FileOpener fo = new FileOpener(null);
		ColorModel cm = fo.createColorModel(info[0]);
		ImageStack stack = new ImageStack((int)dims[1],(int)dims[0],cm);
		File f = new File(fileName);
		// If the image is an ImageJ image then set the number of stacks to nImages
		if(info[0].description!=null) {
			if(info[0].description.contains("ImageJ")) {
				if(info[0].nImages>0) {
					dims[2] = info[0].nImages;
				}
			}
		}
		// For now product of first two dims cannot be greater than INTMAX
		try {
			if(dims[0]*dims[1] > (long)Integer.MAX_VALUE) throw new Exception("Product of first two dimensions cannot be greater than 2147483647");
		}
		catch(Exception e) {
			System.out.println(e);
			return;
		}
		if(bits == 8) {
			byte im[][] = prtc.parallelReadTiffUINT8(fileName);
			for(int i = 0; i < dims[2]; i++){
				stack.addSlice(null, im[i]);
			}
		}
		else if (bits == 16) {
			short im[][] = prtc.parallelReadTiffUINT16(fileName);
			for(int i = 0; i < dims[2]; i++){
				stack.addSlice(null, im[i]);
			}
		}
		else if (bits == 32) {
			float im[][] = prtc.parallelReadTiffFLOAT(fileName); 
			for(int i = 0; i < dims[2]; i++){
				stack.addSlice(null, im[i]);
			}
		}
		else if(bits == 64) {
			float im[][] = prtc.parallelReadTiffDOUBLE(fileName);
			for(int i = 0; i < dims[2]; i++){
				stack.addSlice(null, im[i]);
			}
		}
		else {
			IJ.log("Data type not supported\n");
			return;
		}
		
		new Opener();

		ImagePlus imp = new ImagePlus(f.getName(),stack);
		if (info[0].info!=null)
			imp.setProperty("Info", info[0].info);
		if (info[0].properties!=null)
			imp.setProperties(info[0].properties);
		imp.setFileInfo(info[0]);


		if (info[0].sliceLabels!=null && info[0].sliceLabels.length<=stack.size()) {
			for (int i=0; i<info[0].sliceLabels.length; i++)
				stack.setSliceLabel(info[0].sliceLabels[i], i+1);
		}

		//Properties props = fo.decodeDescriptionString(info[0]);
		try {
			setCalibration(imp,info[0],fo);
		}
		catch(Exception e){
			System.out.println(e);
			return;
		}

		imp = makeComposite(imp, info[0]);
		//IJ.showProgress(1.0);
		
		if(showImage) imp.show();
		else this.imp = imp;
		//}
		

	}
	
	public PRT(String fileName){
		this(fileName,true);
	}
	
	public ImagePlus getImp() {
		return this.imp;
	}
	
	void PRT_NoFileInfo(String fileName){
		PRTC prtc = new PRTC();
		long bits = prtc.getDataType(fileName);
		long[] dims = prtc.getImageDims(fileName);

		ImageStack stack = new ImageStack((int)dims[1],(int)dims[0]);
		File f = new File(fileName);

		// For now product of first two dims cannot be greater than INTMAX
		try {
			if(dims[0]*dims[1] > (long)Integer.MAX_VALUE) throw new Exception("Product of first two dimensions cannot be greater than 2147483647");
		}
		catch(Exception e) {
			System.out.println(e);
			return;
		}

		if(bits == 8) {
			byte im[][] = prtc.parallelReadTiffUINT8(fileName);
			for(int i = 0; i < dims[2]; i++){
				stack.addSlice(null, im[i]);
			}
		}
		else if (bits == 16) {
			short im[][] = prtc.parallelReadTiffUINT16(fileName);
			for(int i = 0; i < dims[2]; i++){
				stack.addSlice(null, im[i]);
			}
		}
		else if (bits == 32) {
			float im[][] = prtc.parallelReadTiffFLOAT(fileName); 
			for(int i = 0; i < dims[2]; i++){
				stack.addSlice(null, im[i]);
			}
		}
		else if(bits == 64) {
			float im[][] = prtc.parallelReadTiffDOUBLE(fileName);
			for(int i = 0; i < dims[2]; i++){
				stack.addSlice(null, im[i]);
			}
		}
		else {
			IJ.log("Data type not supported\n");
			return;
		}

		ImagePlus imp = new ImagePlus(f.getName(),stack);
		imp.show();
		
	}
	
	private ImagePlus makeComposite(ImagePlus imp, FileInfo fi) {
		int c = imp.getNChannels();
		boolean composite = c>1 && fi.description!=null && fi.description.indexOf("mode=")!=-1;
		if (c>1 && (imp.getOpenAsHyperStack()||composite) && !imp.isComposite() && imp.getType()!=ImagePlus.COLOR_RGB) {
			int mode = IJ.COLOR;
			if (fi.description!=null) {
				if (fi.description.indexOf("mode=composite")!=-1)
					mode = IJ.COMPOSITE;
				else if (fi.description.indexOf("mode=gray")!=-1)
					mode = IJ.GRAYSCALE;
			}
			imp = new CompositeImage(imp, mode);
		}
		return imp;
	}

	void setCalibration(ImagePlus imp, FileInfo fi, FileOpener fo) {
		if (fi.fileType==FileInfo.GRAY16_SIGNED) {
			if (IJ.debugMode) IJ.log("16-bit signed");
			imp.getLocalCalibration().setSigned16BitCalibration();
		}
		Properties props = fo.decodeDescriptionString(fi);
		Calibration cal = imp.getCalibration();
		boolean calibrated = false;
		if (fi.pixelWidth>0.0 && fi.unit!=null) {
			if (Prefs.convertToMicrons && fi.pixelWidth<=0.0001 && fi.unit.equals("cm")) {
				fi.pixelWidth *= 10000.0;
				fi.pixelHeight *= 10000.0;
				if (fi.pixelDepth!=1.0)
					fi.pixelDepth *= 10000.0;
				fi.unit = "um";
			}
			cal.pixelWidth = fi.pixelWidth;
			cal.pixelHeight = fi.pixelHeight;
			cal.pixelDepth = fi.pixelDepth;
			cal.setUnit(fi.unit);
			calibrated = true;
		}

		if (fi.valueUnit!=null) {
			if (imp.getBitDepth()==32)
				cal.setValueUnit(fi.valueUnit);
			else {
				int f = fi.calibrationFunction;
				if ((f>=Calibration.STRAIGHT_LINE && f<=Calibration.EXP_RECOVERY && fi.coefficients!=null)
						|| f==Calibration.UNCALIBRATED_OD) {
					boolean zeroClip = props!=null && props.getProperty("zeroclip", "false").equals("true");	
					cal.setFunction(f, fi.coefficients, fi.valueUnit, zeroClip);
					calibrated = true;
				}
			}
		}

		if (calibrated)
			checkForCalibrationConflict(imp, cal);

		if (fi.frameInterval!=0.0)
			cal.frameInterval = fi.frameInterval;

		if (props==null)
			return;

		cal.xOrigin = getDouble(props,"xorigin");
		cal.yOrigin = getDouble(props,"yorigin");
		cal.zOrigin = getDouble(props,"zorigin");
		cal.setInvertY(getBoolean(props, "inverty"));
		cal.info = props.getProperty("info");		

		cal.fps = getDouble(props,"fps");
		cal.loop = getBoolean(props, "loop");
		cal.frameInterval = getDouble(props,"finterval");
		cal.setTimeUnit(props.getProperty("tunit", "sec"));
		cal.setYUnit(props.getProperty("yunit"));
		cal.setZUnit(props.getProperty("zunit"));

		double displayMin = getDouble(props,"min");
		double displayMax = getDouble(props,"max");
		if (!(displayMin==0.0&&displayMax==0.0)) {
			int type = imp.getType();
			ImageProcessor ip = imp.getProcessor();
			if (type==ImagePlus.GRAY8 || type==ImagePlus.COLOR_256)
				ip.setMinAndMax(displayMin, displayMax);
			else if (type==ImagePlus.GRAY16 || type==ImagePlus.GRAY32) {
				if (ip.getMin()!=displayMin || ip.getMax()!=displayMax)
					ip.setMinAndMax(displayMin, displayMax);
			}
		}

		if (getBoolean(props, "8bitcolor"))
			imp.setTypeToColor256(); // set type to COLOR_256

		int stackSize = imp.getStackSize();
		if (stackSize>1) {
			int channels = (int)getDouble(props,"channels");
			int slices = (int)getDouble(props,"slices");
			int frames = (int)getDouble(props,"frames");
			if (channels==0) channels = 1;
			if (slices==0) slices = 1;
			if (frames==0) frames = 1;
			//IJ.log("setCalibration: "+channels+"  "+slices+"  "+frames);
			if (channels*slices*frames==stackSize) {
				imp.setDimensions(channels, slices, frames);
				if (getBoolean(props, "hyperstack"))
					imp.setOpenAsHyperStack(true);
			}
		}
	}
	void checkForCalibrationConflict(ImagePlus imp, Calibration cal) {
		Calibration gcal = imp.getGlobalCalibration();
		if  (gcal==null || !showConflictMessage || IJ.isMacro())
			return;
		if (cal.pixelWidth==gcal.pixelWidth && cal.getUnit().equals(gcal.getUnit()))
			return;
		GenericDialog gd = new GenericDialog(imp.getTitle());
		gd.addMessage("The calibration of this image conflicts\nwith the current global calibration.");
		gd.addCheckbox("Disable_Global Calibration", true);
		gd.addCheckbox("Disable_these Messages", false);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		boolean disable = gd.getNextBoolean();
		if (disable) {
			imp.setGlobalCalibration(null);
			imp.setCalibration(cal);
			WindowManager.repaintImageWindows();
		}
		boolean dontShow = gd.getNextBoolean();
		if (dontShow) showConflictMessage = false;
	}
	private Double getNumber(Properties props, String key) {
		String s = props.getProperty(key);
		if (s!=null) {
			try {
				return Double.valueOf(s);
			} catch (NumberFormatException e) {}
		}	
		return null;
	}

	private double getDouble(Properties props, String key) {
		Double n = getNumber(props, key);
		return n!=null?n.doubleValue():0.0;
	}

	private boolean getBoolean(Properties props, String key) {
		String s = props.getProperty(key);
		return s!=null&&s.equals("true")?true:false;
	}

}
