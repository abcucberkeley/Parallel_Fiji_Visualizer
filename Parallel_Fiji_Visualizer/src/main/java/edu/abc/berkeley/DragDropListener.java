package edu.abc.berkeley;


import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;

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

class DragDropListener implements DropTargetListener {

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
                    	
                    	if (file.isDirectory()) {
                    		if(fName.endsWith(".zarr")) {
                    			new Thread(() -> {
	                    			new PRZ(fName);
	                        	}).start();
                    		}
                    		else {
                    			new Thread(() -> {
                    				ImagePlus nStack = ParallelFolderOpener.open(null, fName);
                    				if(nStack != null) nStack.show();
	                        	}).start();
                    		}
                    		continue;
                    	}
                    	else if(!fName.endsWith(".tif")) {
                    		// Check if Windows destroyed the name because it can't handle paths greater than 256 chars
                    		// the contains function doesn't work so I have to do this. It's a Windows thing I suppose
                    		if(!SystemUtils.IS_OS_WINDOWS || fName.length() < 4 || StringUtils.difference(fName.substring(fName.length()-4),".TIF") != "") {
                    			IJ.log(fName + " does not contain the .tif or .zarr extension.");
                    			continue;
                    		}
                    	}
                    	if (file.isFile()) new Thread(() -> {
                    		new PRT(fName);
                    	}).start();
                    	
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
