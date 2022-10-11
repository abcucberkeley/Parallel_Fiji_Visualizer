/*
 * To the extent possible under law, the ImageJ developers have waived
 * all copyright and related or neighboring rights to this tutorial code.
 *
 * See the CC0 1.0 Universal license for details:
 *     http://creativecommons.org/publicdomain/zero/1.0/
 */

package edu.abc.berkeley;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileInfo;
import ij.io.FileOpener;
import ij.io.FileSaver;
import ij.io.LogStream;
import ij.io.Opener;
import ij.plugin.FolderOpener;
import ij.plugin.ParallelFolderOpener;
//import jdk.incubator.foreign.*;
//import jdk.incubator.foreign.MemorySession;
import net.imagej.ImageJ;

//import jdk.incubator.foreign.ResourceScope;


import net.imglib2.type.numeric.RealType;

import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import javax.swing.JFileChooser;



/*
 * This example illustrates how to create an ImageJ {@link Command} plugin.
 * <p>
 * The code here is a simple Gaussian blur using ImageJ Ops.
 * </p>
 * <p>
 * You should replace the parameter fields with your own inputs and outputs,
 * and replace the {@link run} method implementation with your own logic.
 * </p>
 */
@Plugin(type = Command.class, menuPath = "Plugins>Parallel Fiji Visualizer")
public class ParallelFijiVisualizer<T extends RealType<T>> implements Command {
    //
	// Feel free to add more parameters here...
	//

	//@Parameter
    //private Dataset currentData;

    //@Parameter
    //private UIService uiService;

    //@Parameter
    //private OpService opService;

    @SuppressWarnings("deprecation")
	@Override
    public void run() {
    	
    	//LogStream.redirectSystem();
    	new DragDropFrame();
    	/*
    	JFileChooser chooser = new JFileChooser();
    	File f = null;
    	int returnValue = chooser.showOpenDialog( null ) ;
    	 if( returnValue == JFileChooser.APPROVE_OPTION ) {
    	        f = chooser.getSelectedFile() ;
    	 }
    	 if(f != null)
    	 {
    	      
    	      
    	 }
    	 */
    	
    	/*

    	 */
    	
    	
    	/*
        final Img<T> image = (Img<T>)currentData.getImgPlus();

        //
        // Enter image processing code here ...
        // The following is just a Gauss filtering example
        //
        final double[] sigmas = {1.0, 3.0, 5.0};

        List<RandomAccessibleInterval<T>> results = new ArrayList<>();

        for (double sigma : sigmas) {
            results.add(opService.filter().gauss(image, sigma));
        }

        // display result
        for (RandomAccessibleInterval<T> elem : results) {
            uiService.show(elem);
        }
        */
    }

    /**
     * This main function serves for development purposes.
     * It allows you to run the plugin immediately out of
     * your integrated development environment (IDE).
     *
     * @param args whatever, it's ignored
     * @throws Exception
     */
    @SuppressWarnings({ "deprecation", "unchecked" })
	public static void main(final String... args) throws Exception {
    	//MemorySegment cString;
        // create the ImageJ application context with all available services
        
        //CLinker linker = CLinker.systemCLinker();
    	/*
        MemorySegment segment = MemorySegment.allocateNative(10 * 4, ResourceScope.newImplicitScope());
        for (int i = 0 ; i < 10 ; i++) {
            segment.setAtIndex(ValueLayout.JAVA_INT, i, i);
        }*/
        /*
        MethodHandle strlen = linker.downcallHandle(
                linker.lookup("strlen").get(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
        );
        try (ResourceScope scope = ResourceScope.newConfinedScope()) {
            MemorySegment cString = MemorySegment.allocateNative(5 + 1, scope);
            cString.setUtf8String(0,"Hello");
            long len = (long)strlen.invoke(cString); // 5
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }*/

    	
    	final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        //ImagePlus imp = (new Opener()).openImage("/home/matt/Desktop/testImages/MIPs_run7.tif");
        //imp.show();
        //imp.
        //FileSaver fs = new FileSaver(imp);
        //fs.save();
        
        //FolderOpener.open(null);
        //ParallelFolderOpener.open(null, null);
        /*
        ImagePlus imp = new ImagePlus();
        new Opener();
		FileInfo[] info = Opener.getTiffFileInfo("/clusterfs/nvme/Data/GaoGroupData/20220818_tpe_mouse_brain_tissue/Image 1_Merged-Top of the gel.tif");
        FileOpener fo = new FileOpener(info[0]);
        
        ImagePlus test = fo.open(false);
        */
        //imp.show();
        /*
        
        
        PRTC prtc = new PRTC();
        
        String fileName = "/clusterfs/nvme/Data/JaneliaData/UprightData/20220409_2PBessel_Mouse_Good_TPE_Blood1_Registered/named/binned/mean/zarr/Unmixed/tiffs/matlab_stitch_xcorr_feather_zarr_max_shift_100xy_5z/matlab_decon/test_z_correction/Scan_Iter_0000_CamA_ch0_CAM1_stack0000_920nm_0000000msec_0000352644msecAbs_decon_offset_0_bg_0.tif";
        long[] dims = prtc.getImageDims(fileName);
        int zSize = (int)dims[1]*(int)dims[0];
        float[][] im = new float[(int)dims[2]][zSize];
        //ByteBuffer bb = ByteBuffer.allocateDirect(zSize*dims[2]*4);
        ByteBuffer bb = ByteBuffer.allocateDirect(4);
        //prtc.parallelReadTiffC(fileName,bb);
        
        
        
        bb.order(java.nio.ByteOrder.nativeOrder());
        int nWorkers = Runtime.getRuntime().availableProcessors();
  	  	int batchSize = (im.length-1)/nWorkers+1;
  	  	setPositions[] sPositions = new setPositions[nWorkers];
  	  	
        for(int w = 0; w < nWorkers; w++) {
  		  sPositions[w] = new setPositions(w,batchSize,im,bb,zSize,dims);
  		  sPositions[w].start();
  	  	}
  	  
		  for(int w = 0; w < nWorkers; w++) {
			  try {
					sPositions[w].join();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
		  }
        
        
  	  	ImageStack stack = new ImageStack((int)dims[1],(int)dims[0]);
  	  	//stack.setBitDepth(16);
  	  	for(int i = 0; i < 1; i++){
  	  		stack.addSlice(null, im[i]);
  	  		//stack.addSlice(null,bb);
  	  	}
  	  	ImagePlus imp = new ImagePlus("/home/matt/Desktop/testImages/ds4x4.tif",stack);
  	  
  	  	imp.show();
  	  	

        // ask the user for a file to open
        //final File file = ij.ui().chooseFile(null, "open");
        
        //final Dataset dataset = ij.scifio().datasetIO().open("/home/matt/Desktop/tCJ.tif");
        //final RandomAccessibleInterval<Float> img;
        //Img<Float> ipImg;
        //final Img< FloatType > img = new ArrayImgFactory< FloatType >()
        //        .create( new long[] { 1800, 512,3400 }, new FloatType() );
        //img.getAt(0).set((float) 1.0);
		//final ImgFactory<FloatType> imgFactory = new CellImgFactory<>(new FloatType());
		 
		// create an 3d-Img with dimensions 20x30x40 (here cellsize is 5x5x5)Ø
		//final CellImg< FloatType, FloatArray > img = (CellImg<FloatType, FloatArray>)imgFactory.create( (long)1800, (long)512, (long)3400 );
        //ij.ui().show(img);
        //final RandomAccessibleInterval<int[]> img;
        //img = RandomAccessibleInterval(new int[]{ 0, 1 });
        //ij.ui().show(dataset);
        //SCIFIOImgPlus imP = new SCIFIOImgPlus(imP, imP);

        //if (file != null) {
            // load the dataset
           // final Dataset dataset = ij.scifio().datasetIO().open(file.getPath());

            // show the image
            //ij.ui().show(dataset);

            // invoke the plugin
        
           
            */
    	 ij.command().run(ParallelFijiVisualizer.class, true);
            
        //}
        
    }

}
