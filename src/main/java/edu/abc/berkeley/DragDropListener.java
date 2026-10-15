package edu.abc.berkeley;


import java.util.List;
import java.util.Locale;

import ij.IJ;
import ij.ImagePlus;
import ij.plugin.ParallelFolderOpener;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.io.File;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

class DragDropListener implements DropTargetListener {
	
	private boolean isReadable(String filePath) {
        Path path = Paths.get(filePath);
        return Files.isReadable(path);
	}

	/** Run a reader on its own thread, anything it throws shown in a dialog. */
	private static void openInBackground(Runnable open) {
		Background.run("PFV open", open);
	}

    @Override
    public void drop(DropTargetDropEvent event) {

        // Accept copy drops
        event.acceptDrop(DnDConstants.ACTION_COPY);

        // Get the transfer which can provide the dropped item data
        Transferable transferable = event.getTransferable();

        // Get the data formats of the dropped item
        DataFlavor[] flavors = transferable.getTransferDataFlavors();

        // Loop through the flavors
        for (DataFlavor flavor : flavors) {

            try {

                // If the drop items are files
                if (flavor.isFlavorJavaFileListType()) {

                    // Get all of the dropped files
                    List<File> files = (List) transferable.getTransferData(flavor);

                    // Loop them through
                    for (File file : files) {
                    	String fName = file.getPath();
                    	if(!isReadable(fName)) {
                    		if(file.isDirectory()) IJ.log("Permission Denied for folder:\"" + fName + "\". Please check your permissions.");
                    		else IJ.log("Permission Denied for file:\"" + fName + "\". Please check your permissions.");
                			continue;
                    	}
                    	
                    	if (file.isDirectory()) {
                    		// A folder is a Zarr by its metadata, whatever its name; one
                    		// named .zarr without any gets PRZ's message saying so
                    		if(ZarrMetadata.isZarr(file) || fName.toLowerCase(Locale.ROOT).endsWith(".zarr")) {
                    			openInBackground(() -> new PRZ(fName));
                    		}
                    		else {
                    			openInBackground(() -> {
                    				ImagePlus nStack = ParallelFolderOpener.open(null, fName);
                    				if(nStack != null) nStack.show();
                    			});
                    		}
                    		continue;
                    	}
                    	// Check if the tiff extension is correct (in any case: Windows
                    	// gives paths longer than 256 characters short names like X~1.TIF)
                    	else if(!PRT.isTiff(fName)) {
                    		IJ.log(fName + " is not a TIFF file (.tif or .tiff) or a Zarr folder.");
                    		continue;
                    	}
                    	
                    	if (file.isFile()) openInBackground(() -> new PRT(fName));
                    	
                    	//else if (file.isFile()) new PRT(fName);
                        // Print out the file path
                    	else IJ.log(fName + " is not a file.");

                    }

                }

            } catch (Exception e) {

                // Print out the error stack
                e.printStackTrace();

            }
        }

        // Inform that the drop is complete
        event.dropComplete(true);

    }

    @Override
    public void dragEnter(DropTargetDragEvent event) {
    }

    @Override
    public void dragExit(DropTargetEvent event) {
    }

    @Override
    public void dragOver(DropTargetDragEvent event) {
    }

    @Override
    public void dropActionChanged(DropTargetDragEvent event) {
    }

}
