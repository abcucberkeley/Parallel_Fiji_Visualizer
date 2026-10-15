package edu.abc.berkeley;

import java.awt.image.ColorModel;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.ParallelImagePlus;
import ij.ParallelVirtualStack;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.io.FileInfo;
import ij.io.Opener;
import ij.measure.Calibration;
import ij.plugin.Memory;
import ij.process.ImageProcessor;
import ij.io.FileOpener;

/**
 * Opens a TIFF file: the pixels with cpp-tiff ({@link TiffImage}), as a
 * virtual stack when they do not fit in memory, and the calibration,
 * hyperstack dimensions, LUTs and labels from ImageJ's own reading of the
 * file's metadata.
 */
public class PRT {
	private ImagePlus imp;
	private static boolean showConflictMessage = true;
	
	/** Whether a file name has a TIFF extension: .tif or .tiff, in any case. */
	public static boolean isTiff(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		return n.endsWith(".tif") || n.endsWith(".tiff");
	}

	public PRT(String fileName, boolean showImage){
		File f = new File(fileName);
		// Metadata (ImageJ's IFD scan and cpp-tiff's) can take seconds on a
		// many-slice tiff; show life before the size is known
		ProgressEstimator.starting("Reading " + f.getName());
		TiffImage tiff = TiffImage.open(fileName);
		// ImageJ's description of the file (cpp-tiff's sizes are the ones the
		// pixels are read with). ImageJ walks every directory of a plain stack
		// (~0.1 s per 1000 slices), so it reads it on its own thread while
		// cpp-tiff reads the pixels. It cannot read BigTIFFs (cpp-tiff writes
		// nothing else): 1.54f misreads them into nonsense, for some files
		// taking half a minute, and later versions refuse them, so they go
		// without.
		FutureTask<FileInfo[]> imageJInfo = null;
		if(!isBigTiff(fileName)) {
			imageJInfo = new FutureTask<FileInfo[]>(() -> Opener.getTiffFileInfo(fileName));
			Thread metadata = new Thread(imageJInfo, "PFV TIFF metadata");
			metadata.setDaemon(true);
			metadata.start();
		}

		int planes = (int)tiff.planes();
		long bytesPerPixel = tiff.isRgb() ? 4 : Math.min(tiff.bits, 32) / 8;
		boolean isVirtual = (long)tiff.width*tiff.height*planes*bytesPerPixel > new Memory().maxMemory();
		Object[] pixels = isVirtual ? null : tiff.read(0, planes, "Reading "+f.getName());
		FileInfo fi = imageJInfo != null ? firstInfo(imageJInfo) : null;
		FileOpener fo = new FileOpener(null);
		ColorModel cm = fi != null ? fo.createColorModel(fi) : null;
		ImageStack stack;
		ImagePlus imp;
		if(!isVirtual) {
			stack = new ImageStack(tiff.width, tiff.height, cm);
			for(Object p : pixels) stack.addSlice(null, p);
			imp = new ImagePlus(f.getName(), stack);
		}
		else {
			// Planes are read in blocks as they are shown, each with its own progress
			ProgressEstimator.clearStarting();
			ParallelVirtualStack pVStack = new ParallelVirtualStack(tiff);
			if(cm != null) pVStack.setColorModel(cm);
			stack = pVStack;
			imp = new ParallelImagePlus(f.getName(), pVStack);
		}

		if(fi != null) {
			if (fi.info!=null)
				imp.setProperty("Info", fi.info);
			if (fi.properties!=null)
				imp.setProperties(fi.properties);
			imp.setFileInfo(fi);
			if (fi.sliceLabels!=null && fi.sliceLabels.length<=stack.size()) {
				for (int i=0; i<fi.sliceLabels.length; i++)
					stack.setSliceLabel(fi.sliceLabels[i], i+1);
			}
			try {
				// Calibration and hyperstack dimensions; signed 16-bit data,
				// shifted natively, is mapped back here
				setCalibration(imp, fi, fo);
			}
			catch(Exception e){
				System.out.println(e);
			}
		}
		else {
			FileInfo fileInfo = new FileInfo();
			fileInfo.directory = f.getParent();
			fileInfo.fileName = f.getName();
			imp.setFileInfo(fileInfo);
			if(tiff.isSigned() && tiff.bits == 16) imp.getLocalCalibration().setSigned16BitCalibration();
		}
		if(tiff.isSigned() && tiff.bits == 8) {
			// Map the shifted signed bytes back to their real values
			imp.getLocalCalibration().setFunction(Calibration.STRAIGHT_LINE, new double[]{-128.0,1.0}, "gray value");
		}

		// Several samples per pixel (16-bit or float color, say): one channel
		// each, which ImageJ shows as a composite like the color they are
		boolean samplesAsChannels = tiff.channels() > 1 && imp.getNChannels() == 1;
		if(samplesAsChannels) {
			imp.setDimensions(tiff.channels(), (int)tiff.slices, 1);
			imp.setOpenAsHyperStack(true);
		}
		// Composites draw every channel at once; virtual stacks stay hyperstacks
		// that show one channel at a time, like zarrs too large for memory
		if(!isVirtual) {
			if(samplesAsChannels && tiff.channels() <= CompositeImage.MAX_CHANNELS) imp = new CompositeImage(imp, IJ.COMPOSITE);
			else if(fi != null) imp = makeComposite(imp, fi);
		}
		
		this.imp = imp;
		if(showImage) imp.show();
	}
	
	public PRT(String fileName){
		this(fileName,true);
	}

	/** Whether a file starts with the BigTIFF header ("II" 43 0 or "MM" 0 43). */
	public static boolean isBigTiff(String path) {
		try (RandomAccessFile f = new RandomAccessFile(path, "r")) {
			byte[] h = new byte[4];
			f.readFully(h);
			return (h[0] == 'I' && h[1] == 'I' && h[2] == 43 && h[3] == 0) || (h[0] == 'M' && h[1] == 'M' && h[2] == 0 && h[3] == 43);
		}
		catch(IOException e) {
			return false;
		}
	}

	/** The first directory's ImageJ description from a scan, or null. */
	private static FileInfo firstInfo(FutureTask<FileInfo[]> scan) {
		try {
			FileInfo[] info = scan.get();
			return info != null && info.length > 0 ? info[0] : null;
		}
		catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		catch(ExecutionException e) {
			return null;
		}
	}
	
	public ImagePlus getImp() {
		return this.imp;
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
		// One question at a time when a folder's files are opened at once
		synchronized(PRT.class) {
			checkForCalibrationConflictNow(imp, cal);
		}
	}

	private void checkForCalibrationConflictNow(ImagePlus imp, Calibration cal) {
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
