/*
 * To the extent possible under law, the ImageJ developers have waived
 * all copyright and related or neighboring rights to this tutorial code.
 *
 * See the CC0 1.0 Universal license for details:
 *     http://creativecommons.org/publicdomain/zero/1.0/
 */

package edu.abc.berkeley;

import ij.ImageJ;
import ij.plugin.PlugIn;



public class ParallelFijiVisualizer implements PlugIn {
    //
	// Feel free to add more parameters here...
	//

	//@Parameter
    //private Dataset currentData;

    //@Parameter
    //private UIService uiService;

    //@Parameter
    //private OpService opService;


	@Override
    public void run(final String args ) {
    	
    	// Create a drag and drop frame that will open dropped files
    	new DragDropFrame();
    }

    /**
     * This main function serves for development purposes.
     * It allows you to run the plugin immediately out of
     * your integrated development environment (IDE).
     *
     * @param args whatever, it's ignored
     * @throws Exception
     */

	public static void main(final String... args) throws Exception {
    	new ImageJ();
    	new ParallelFijiVisualizer().run("");
        //ij.ui().showUI();
        //PRZ test = new PRZ("/home/matt/Desktop/testImages/xrTest.zarr", 0, 0, 0, 1800, 512, 1, true);
        //if(true) return;
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


    	//ij.command().run(ParallelFijiVisualizer.class, true);

        
    }

}
