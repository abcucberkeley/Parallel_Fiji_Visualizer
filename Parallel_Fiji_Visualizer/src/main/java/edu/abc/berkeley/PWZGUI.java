package edu.abc.berkeley;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;

import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;

import java.io.File;

/*
    FILE: PWZ
    JFrame will help in opening the frame/window for interface
    JPanel will have the panel, for widgets to adjust and adapt based on window size.

    JPanel will add:
    JLabels
    Buttons
    Checkboxes
*/

/*
 * 
 * Project Meeting Notes
 * MAKE CHANGES TO INTERFACE
 * Crop Button
 * Purpose: Cropping the file size of the zarr file we are trying to load by clicking the browse widget.
 * - Bring Down and adjust crop widget. Crop widget adjust to the XML formatting. (near/in front of starting and ending XYZ)
 * - Make Starting and ending XYZ, with chunk and default compressor seperate from each other
 * 1. table for starting & ending XYZ
 * 2. another for chunk and compressor.
 * NOTE: Dont actually write default, just put compressor.
 */

 /*
  ** Variables that are important reference to this one here!
  Implement (IMPORTANCE)
  PWZGUI(string, long, long, long, long, long, long, long, long, long, string) // filepath: string, Starting XYZ: long, ending XYZ: long, chunkSize: long, compressor: string

  Widget implementation
  Click Browse
  - Use given API JFileChooser
  */

  /*
   * NOTES (10/27/2022)
   1.) Implement Coords.java class (handling starting and ending XYZ, for better readability)
   * 
   * 
   * 
   */

public class PWZGUI implements ActionListener{
    JFrame window;
    JPanel panel;

    JButton browse;

    // Crop check box
    JLabel checkboxLabel;
    JCheckBox checkBox;

    // Text box
    JLabel textAreaLabel;
    JTextArea textArea;

    // Starting and XYZ
    JPanel tablePanel;
    JTable table;
    JScrollPane scrollPane;
    
    // Data retrievers.
    long startX=0, startY=0, startZ=0;
    long endX=0, endY=0, endZ=0;
    String filepath;
    String compressor;

    // Default values for the chunk sizes.
    // Also, not reusing other starting and ending, so doesnt alter those variables.
    long defaultX=256, defaultY=256, defaultZ=256;



    // Grab Coordinates (UPDATED FUNC VERS)
    Container pane;
    
    

    public PWZGUI() {
        // Creates a thread. Lets be called and execute object in its own instance.
        // Basically like its own thread.
        this.filepath = "";
        this.compressor = "";

        // Starting coords
        this.startX = 0;
        this.startY = 0;
        this.startZ = 0;
        
        // Ending coords
        this.endX = 0;
        this.endY = 0;
        this.endZ = 0;
        defaultX = 256;
        defaultY = 256;
        defaultZ = 256;

        Runnable r = new Runnable() {
            @Override
            public void run(){
                init();
            }
        };

        // Swing GUIs should be created and updated on the EDT
        // http://docs.oracle.com/javase/tutorial/uiswing/concurrency
        SwingUtilities.invokeLater(r);
    }

    /*public PWZGUI(String filepath, Coords starting, Coords ending, String compressor){
        this.filepath = filepath;
        this.compressor = compressor;

        // Starting coords
        this.startX = starting.x;
        this.startY = starting.y;
        this.startZ = starting.z;
        
        // Ending coords
        this.endX = ending.x;
        this.endY = ending.y;
        this.endZ = ending.z;
        defaultX = 256;
        defaultY = 256;
        defaultZ = 256;

        Runnable r = new Runnable(){
            @Override
            public void run(){
                init();
            }
        };

        SwingUtilities.invokeLater(r);
    }*/

    // public PWZGUI(String filepath, Coords starting, Coords ending, String compressor){
    public PWZGUI(String filepath, long start_x, long start_y, long start_z, long end_x, long end_y, long end_z, long chunkSize, String compressor){
        this.filepath = filepath;
        this.compressor = compressor;

        // Starting coords
        this.startX = start_x;
        this.startY = start_y;
        this.startZ = start_z;
        
        // Ending coords
        this.endX = end_x;
        this.endY = end_y;
        this.endZ = end_z;
        defaultX = 256;
        defaultY = 256;
        defaultZ = 256;

        pane = new Container();

        Runnable r = new Runnable(){
            @Override
            public void run(){
                init();
            }
        };

        SwingUtilities.invokeLater(r);
    }

    private void init(){

        window = new JFrame("Zarr FIle");
        panel = new JPanel(); // Main Panel
        tablePanel = new JPanel(); // JTable Panel


        setupTextProperties(); // Handling button widget
        setupCropProperties(); // User type text label, and text box field.
        grabCoordinates();
        show(); // Handling adding all the widgets into the panel, while panel is being referred by the JFrame.

        // Setting window/jframe properties
        window.setSize(600, 300);
        window.setVisible(true);
    }

    // setting up text input properties.
    private void setupTextProperties(){
        browse = new JButton("Browse");
        browse.setBounds(300, 5, 15, 15);
        browse.addActionListener(this);

        // Handling text box and label
        textAreaLabel = new JLabel("Filepath");
        textAreaLabel.setBounds(135, 5, 75, 15);

        textArea = new JTextArea(1, 6);
        textArea.setBounds(215, 5, 75, 25);
        textArea.setLayout(new FlowLayout());
    }

    // Setup cropping properties and JLabels corresponding to given check box widget.
    private void setupCropProperties(){
        // User type text label, and text box field.
        checkBox = new JCheckBox("Crop");
        checkBox.setBounds(215, 50, 75, 15);
        checkBox.addActionListener(this);
    }

    private void grabCoordinates(){
        Object[][] data = {
            {"Start", startX, startY, startZ},
            {"End", endX, endY, endZ},
            {"Chunk", defaultX, defaultY, defaultZ},
            {"Default", "Iz4"}
        };

        DefaultTableModel tableModel = new DefaultTableModel(){
            @Override
            public boolean isCellEditable(int row, int col) {
                if(row == 4 && col == 2) return false;
                return true;
            }
        };

        table = new JTable(tableModel);
        table.setBounds(30, 30, 950, 950);

        // Adding columns to the table
        tableModel.addColumn("");
        tableModel.addColumn("X");
        tableModel.addColumn("Y");
        tableModel.addColumn("Z");

        // Adding rows to the table.
        for(int i = 0; i < data.length; i++) tableModel.addRow(data[i]);

        scrollPane = new JScrollPane(table);
        // Original width = 210, height = 75
        scrollPane.setPreferredSize(new Dimension(250, 100)); // Actually setting how large the scroll pane is.
        scrollPane.setLocation(650, 130);
        scrollPane.setVisible(true);
    }

    // show function, has keeps track of all the added components into JFrame -> going to -> JPanel -> widgets/checkboxes/etc.
    private void show(){
        // Adding/packing all widgets into the panel, then referring that panel to the window.
        panel.add(textAreaLabel);
        panel.add(textArea);
        panel.add(browse);
        panel.add(checkBox);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        

        tablePanel.add(scrollPane);
        

        // Setting window/jframe properties
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        window.getContentPane().add(tablePanel, BorderLayout.SOUTH); // This is where we add the JTable to the interface.
        window.add(panel, BorderLayout.CENTER);
        window.pack();
        window.setLocation(new Point(900, 250)); // Hopefully puts the screen in the center of the monitor (vary depending on monitoring)
        window.setVisible(true);
    }

    // Handling events happening with the interface.
    @Override
    public void actionPerformed(ActionEvent e){
        String filepath = textArea.getText();
        boolean emptyString = filepath.equals(""); // We dont want to browse anything that isn't a string. (Don't assume, everyone will type the right input. Just precautionary)

        // Just add this just in case, we do not want empty characters
        if(emptyString) return;

        if(e.getSource() == browse){
            // We want to check if the checkbox is clicked before we compress that file.
            if(checkBox.isSelected()) checkboxClicked();
            if(!emptyString) loadfile(filepath);
        }
    }

    // Loading filepath given.
    private void loadfile(String filepath){
        // File Handling stuff. (Referenced PWZ.java)
        // PWZC pwzc = new PWZC();
        JFileChooser chooser = new JFileChooser();
        chooser.setApproveButtonText("Save");
		chooser.setDialogTitle("Save as Zarr");
        File file = null;

        int returnValue = chooser.showOpenDialog(null);

        if(returnValue == JFileChooser.APPROVE_OPTION) file = chooser.getSelectedFile();
        if(file == null) return;

        ImagePlus cImagePlus = IJ.getImage();

        if(cImagePlus == null) return;

        ImageStack cImageStack = cImagePlus.getImageStack();
        Object[] cImageObj = cImageStack.getImageArray();

        // May need to get bits another way
		int bits = cImageStack.getBitDepth();
		String fileName = file.getPath();
		int x = cImageStack.getWidth();
		int y = cImageStack.getHeight();
		int z = cImageStack.getSize();
		// pwzc.parallelWriteZarr(fileName, cImageObj, 0, 0, 0, y, x, z, 256, 256, 256, 1, "lz4", 1, bits);

        System.out.println("[DEBUGGING]: " + filepath + " has been typed!");
    }

    // Does smthing when this check box is clicked.
    private void checkboxClicked(){ System.out.println("[DEBUGGING]: Check Box Clicked!"); }

    // Testing and debugging interface with main method.
    // public static void main(String[] args) { new PWZGUI(); }
}