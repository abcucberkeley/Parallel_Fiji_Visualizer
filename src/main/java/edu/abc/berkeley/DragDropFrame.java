package edu.abc.berkeley;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.dnd.DropTarget;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import ij.io.OpenDialog;

public class DragDropFrame extends JFrame{
	
	 public DragDropFrame() {

	        // Set the frame title
	        super("Parallel Fiji Visualizer");

	        // Set the size
	        this.setSize(600, 400);
	        
	        this.setLayout(new GridBagLayout());
	        GridBagConstraints c = new GridBagConstraints();
	        //this.setVisible(true);

	        
	        
	        // The three buttons share one GridLayout row, which forces them
	        // all to the same size (as wide as the widest label needs)
	        JPanel buttonPanel = new JPanel(new GridLayout(1, 3));

	        // Save Button for Tiff files
	        JButton saveAsTiffButton = new JButton("Save as Tiff");
	        saveAsTiffButton.addActionListener(new ActionListener() {

	            @Override
	            public void actionPerformed(ActionEvent e) {
	            	new PWT();
	            }
	        });
	        buttonPanel.add(saveAsTiffButton);
	        
	        // Save Button for Zarr files
	        JButton saveAsZarrButton = new JButton("Save as Zarr");
	        saveAsZarrButton.addActionListener(new ActionListener() {

	            @Override
	            public void actionPerformed(ActionEvent e) {
	            	new PWZ();
	            }
	        });
	        buttonPanel.add(saveAsZarrButton);
	        
	        // Import from Text List
	        JButton importFromTextListButton = new JButton("Import from Text List");
	        importFromTextListButton.addActionListener(new ActionListener() {

	            @Override
	            public void actionPerformed(ActionEvent e) {
	            	OpenDialog od = new OpenDialog("Select a file", null);
	                String directory = od.getDirectory();
	                String fileName = od.getFileName();
	            	if(fileName != null) {
	            		String fullPath = directory + fileName;
	            		new IFTL(fullPath);
	            	}
	            }
	        });
	        buttonPanel.add(importFromTextListButton);

	        c.fill = GridBagConstraints.HORIZONTAL;
	        c.anchor = GridBagConstraints.NORTH;
	        c.weighty = 1.0;
	        c.weightx = 1.0;
	        c.gridx = 0;
	        c.gridy = 0;
	        c.gridwidth = 3;
	        this.add(buttonPanel,c);
	        
	        // Create the label
	        c.fill = GridBagConstraints.HORIZONTAL;
	        c.weightx = 1.0;
	        c.gridx = 0;
	        c.gridy = 1;
	        c.gridwidth = 3;
	        //JLabel myLabel = new JLabel("Drag something here!", SwingConstants.CENTER);
	        JLabel myLabel = new JLabel("Drag something here!", SwingConstants.HORIZONTAL);
	        this.add(myLabel,c);
	        

	        // Create the drag and drop listener
	        DragDropListener myDragDropListener = new DragDropListener();

	        // Connect the label with a drag and drop listener
	        new DropTarget(this, myDragDropListener);

	        // Add the label to the content
	        //this.getContentPane().add(BorderLayout.CENTER, myLabel);

	        // Show the frame
	        this.setVisible(true);
	        

	    }
}
