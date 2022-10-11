package edu.abc.berkeley;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.dnd.DropTarget;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;

public class DragDropFrame extends JFrame{
	
	 public DragDropFrame() {

	        // Set the frame title
	        super("Parallel Tiff Reader");

	        // Set the size
	        this.setSize(400, 250);
	        
	        this.setLayout(new GridBagLayout());
	        GridBagConstraints c = new GridBagConstraints();
	        //this.setVisible(true);

	        
	        
	        // Save Button for Tiff files
	        JButton saveAsTiffButton = new JButton("Save as Tiff");
	        saveAsTiffButton.addActionListener(new ActionListener() {

	            @Override
	            public void actionPerformed(ActionEvent e) {
	            	new PWT();
	            }
	        });
	        c.fill = GridBagConstraints.HORIZONTAL;
	        c.anchor = GridBagConstraints.NORTH;
	        c.weighty = 1.0;
	        
	        c.weightx = 0.5;
	        c.gridx = 0;
	        c.gridy = 0;
	        this.add(saveAsTiffButton,c);
	        saveAsTiffButton.setVisible(true);
	        
	        // Save Button for Zarr files
	        JButton saveAsZarrButton = new JButton("Save as Zarr");
	        saveAsZarrButton.addActionListener(new ActionListener() {

	            @Override
	            public void actionPerformed(ActionEvent e) {
	            	new PWZ();
	            }
	        });
	        c.fill = GridBagConstraints.HORIZONTAL;
	        c.anchor = GridBagConstraints.NORTH;
	        c.weightx = 0.5;
	        c.gridx = 1;
	        c.gridy = 0;
	        this.add(saveAsZarrButton,c);
	        saveAsZarrButton.setVisible(true);
	        
	        // Create the label
	        c.fill = GridBagConstraints.HORIZONTAL;
	        c.weightx = 1.0;
	        c.gridx = 0;
	        c.gridy = 1;
	        c.gridwidth = 2;
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
