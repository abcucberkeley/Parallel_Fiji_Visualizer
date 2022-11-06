package edu.abc.berkeley;
import javax.swing.*;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.DefaultTableModel;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;

import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;

import java.io.File;

/*
 * PREVIOUS MEETING DISCUSSION
 * - Implement constructor that takes in this many arguments. (As shown below)
 * ** PWZGUI(string, long, long, long, long, long, long, long, long, long, string) // filepath: string, Starting XYZ: long, ending XYZ: long, chunkSize: long, compressor: string
 * CHANGES NEEDED TO BE MADE TO THE INTERFACE
 * 1.) Crop Button. By brinding down the crop box, and positioning the widgets similarily to what is being shown in the XML diagrams that we discussed and promptly designed in diagrams.io
 * --> Purpose: Cropping the file size of the zarr file we are trying to load by clicking the browse widget.
 * 2.) Bring Down and adjust crop widget. Crop widget adjust to the XML formatting. (near/in front of starting and ending XYZ)
 * 3.) Make Starting and ending XYZ, with chunk and default compressor seperate from each other

 * SIDE NOTE: (Describing by thought process.)
 * 1. table for starting & ending XYZ
 * 2. another for chunk and compressor.
 * NOTE: Dont actually write default, just put compressor.
 */

/*
 * NOTES
 * 1.) Fix interface to look similarily to the wxWidget diagram from diagram.io (DONE)
 * 2.) [IMPORTANT] Integrate the JFileChooser to being used by this interface. (DONE)
 * 3.) Seperate Chunk Size and Compressor into their own rows and cols fields. (DONE)
 * SOMETHING TO NOTE: Dont actually write default, just put compressor.
 * 4.) Re-adjusting the crop file size widget to a similar position referenced to the diagrams.io design. (DONE)
 * 5.) Once implemented. Refactor and clean up code. Debugging and troubleshooting, so there is no unecessary code not being used. (DONE)
 * 
 * IN-MEETING DISCUSSION
 * - Proposing there be a coordinates class. So, instead of passing in 6 variables, we can pass two objects.  Also for better readability to the programmer.
 * - Easier to handly the X,Y, and Z coordinates.
 * - Or we can keep both implementation, in case we may need to edit the specific values directly, possibly.
 */

 /*
  * WRAP UP NOTES FOR THIS PROJECT
  * - Constructor chnage variable names (like start_x to startX)
  * - Change default_x to chunkX
  * - Submit button to make changes.
  * - Compressor default is lz4 not iz4.
*/

/*
    C++ MAIN PROJECT.
 * 1.) Release and get matlab working with C++ Qt (version, 6.2.4)
 */

public class PWZGUI implements ActionListener{
    JFrame window;
    JPanel textAreaPanel;

    JButton browse;

    // Crop check box
    JLabel checkboxLabel;
    JCheckBox checkBox;

    // Text box
    JLabel textAreaLabel;
    JTextArea textArea;

    // Starting and ending XYZ
    JTable table;
    JScrollPane scrollPane;
    
    // Data retrievers.
    JLabel startCoordsLabel;
    JLabel endCoordsLabel;

    public String filepath;
    public String compressor;
    public long startX=0, startY=0, startZ=0;
    public long endX=0, endY=0, endZ=0;

    // Default values for the chunk sizes.
    // Also, not reusing other starting and ending, so doesnt alter those variables.
    public long chunkSizeX=256, chunkSizeY=256, chunkSizeZ=256;

    JTable chunkTable; // Displaying the chunk sizes that are the default XYZ coords. (Though not expected to be changed.)
    JScrollPane chunkScrollPane; // Scroll pane, to help display the default values of the chunk size.
    
    // Default Compressor
    JLabel compressorLabel;

    JButton saveChangesButton; // To save changes when 

    public PWZGUI() {
        filepath = "";
        compressor = "lz4"; // Compressor is lz4. (NOTE, this value does not change by default whatsoever)

        // Starting coords
        // Starting and ending coordinates are the expected values to be changed by user using the intercface.
        startX = 0;
        startY = 0;
        startZ = 0;
        
        // Ending coords
        endX = 0;
        endY = 0;
        endZ = 0;

        // These default values are for the chunk sizes. That should remain as the default.
        chunkSizeX = 256;
        chunkSizeY = 256;
        chunkSizeZ = 256;

        // Creates a thread. Lets be called and execute object in its own instance.
        // Basically like its own thread.
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
    
    // This commented constructor was just an idea (will deleted, if this implementation may not be needed.)
    // public PWZGUI(String filepath, Coords starting, Coords ending, String compressor){
    public PWZGUI(String filepath, long startX, long startY, long startZ, long endX, long endY, long endZ, long chunkSizeX, long chunkSizeY, long chunkSizeZ, String compressor){
        this.filepath = filepath;
        this.compressor = compressor;

        // Starting coords
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        
        // Ending coords
        this.endX = endX;
        this.endY = endY;
        this.endZ = endZ;
        this.chunkSizeX = chunkSizeX;
        this.chunkSizeY = chunkSizeY;
        this.chunkSizeZ = chunkSizeZ;

        // Creates a thread. Lets be called and execute object in its own instance.
        // Basically like its own thread.
        Runnable r = new Runnable(){
            @Override
            public void run(){
                init();
            }
        };

        SwingUtilities.invokeLater(r);
    }

    private void init(){

        window = new JFrame("Write Zarr");
        textAreaPanel = new JPanel(); // Main Panel
        compressorLabel = new JLabel();



        setupTextProperties(); // Handling button widget
        setupCropProperties(); // User type text label, and text box field.
        grabCoordinates();
        chunkAndCompressorProperties();
        saveChangesButton(); // update the table changes.
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
        textAreaLabel.setBounds(125, 5, 75, 15);

        textArea = new JTextArea(1, 6);
        textArea.setBounds(215, 5, 75, 25);
        textArea.setLayout(new FlowLayout());

    }

    // Setup cropping properties and JLabels corresponding to given check box widget.
    private void setupCropProperties(){
        // User type text label, and text box field.
        checkBox = new JCheckBox("Crop");
        checkBox.setBounds(160, 110, 75, 15); // Higher x is more to the right we go, lower value y is the more we go upwards.
        checkBox.addActionListener(this);
    }
    
    private void grabCoordinates(){
        // Labels allowing the user using the interface to know
        startCoordsLabel = new JLabel("Start");
        endCoordsLabel = new JLabel("End");

        startCoordsLabel.setBounds(115, 90, 150, 150);
        endCoordsLabel.setBounds(115, 110, 150, 150);
        
        Object[][] data = {
            {startX, startY, startZ},
            {endX, endY, endZ},
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
        tableModel.addColumn("X");
        tableModel.addColumn("Y");
        tableModel.addColumn("Z");

        // Adding rows to the table.
        for(int i = 0; i < data.length; i++) tableModel.addRow(data[i]);

        scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(250, 100)); // Actually setting how large the scroll pane is.
        scrollPane.setBounds(172, 145, 250, 53); // Set where we want the JTable to be located in the JFrame. (Actually adjusts X and Y coords, and width and height)
        scrollPane.setVisible(true);
    }

    private void chunkAndCompressorProperties(){
        // Setting up JTable specifically for Chunk Size.
        Object[] data = {chunkSizeX, chunkSizeY, chunkSizeZ};
        DefaultTableModel model = new DefaultTableModel();

        chunkTable = new JTable(model);
        chunkTable.setBounds(30, 30, 850, 950);

        // Adding rows to the table.
        model.addColumn("Chunk X");
        model.addColumn("Chunk Y");
        model.addColumn("Chunk Z");
        model.addRow(data);

        chunkScrollPane = new JScrollPane(chunkTable);
        // Original width = 210, height = 75
        chunkScrollPane.setPreferredSize(new Dimension(200, 100)); // Actually setting how large the scroll pane is.
        chunkScrollPane.setBounds(170, 205, 255, 40); // Set where we want the JTable to be located in the JFrame. (This line is what updates the X, Y coordinates of widget)
        chunkScrollPane.setVisible(true);

        compressorLabel.setText("Compressor: " + compressor);
        compressorLabel.setBounds(180, 245, 130, 20); // NOTE: Higher the Y-axis lower widgets are positioned. Higher X axis, more to right the widgets positioned at.
    }

    // Function to help organize the widget that handles the saving changes button.
    private void saveChangesButton() {
        saveChangesButton = new JButton("Save Changes");
        // saveChangesButton.setBounds(230, 55, 115, 25);
        saveChangesButton.setBounds(235, 55, 115, 25);

        saveChangesButton.addActionListener(this);
    }

    // show function, has keeps track of all the added components into JFrame -> going to -> JPanel -> widgets/checkboxes/etc.
    private void show(){
        // NOTE: Add to panel
        // Adding/packing all widgets into the panel, then referring that panel to the window.
        // This portion adds in the user input interface interaction widgets into there own panel.
        textAreaPanel.add(textAreaLabel);
        textAreaPanel.add(textArea);
        textAreaPanel.add(browse);

        textAreaPanel.setSize(50, 50);

        // Adds the other widgets into the JFrame window themselves.
        window.add(checkBox); // UPDATE: Add this into the window frame. So we can move this widget however we see fit.
        window.add(startCoordsLabel);
        window.add(endCoordsLabel);
        window.add(scrollPane);
        window.add(chunkScrollPane);
        window.add(compressorLabel);

        
        window.add(saveChangesButton);

        
        

        // Setting window/jframe properties
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        window.add(textAreaPanel, BorderLayout.CENTER); // This is how the TextArea and Labels, are being centered to the TOP of the interface.
        window.pack();
        window.setLocation(new Point(900, 250)); // Hopefully puts the screen in the center of the monitor (vary depending on monitoring)
        window.setVisible(true);
        // window.setResizable(false);
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

        // Updates and clears the table.
        if(e.getSource() == saveChangesButton) updateTable();
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

        
		int bits = cImageStack.getBitDepth(); // May need to get bits another way
		String fileName = file.getPath();
		int x = cImageStack.getWidth();
		int y = cImageStack.getHeight();
		int z = cImageStack.getSize();

		// pwzc.parallelWriteZarr(fileName, cImageObj, 0, 0, 0, y, x, z, 256, 256, 256, 1, "lz4", 1, bits);
    }

    
    private void checkboxClicked(){ System.out.println("[DEBUGGING]: Check Box Clicked!"); } // Does smthing when this check box is clicked.

    // When "Save Changes" button is clicked, the given inputted information is updated
    // Little redundant, for now was trying to get interface working with updating the JTable.
    private void updateTable(){
        startX = 123;
        startY = 456;
        startZ = 890;

        endX = 1024;
        endY = 1036;
        endZ = 1048;

        chunkSizeX = 256;
        chunkSizeY = 256;
        chunkSizeZ = 256;

        table.getModel().setValueAt(startX, 0, 0);
        table.getModel().setValueAt(startY, 0, 1);
        table.getModel().setValueAt(startZ, 0, 2);
        table.getModel().setValueAt(endX, 1, 0);
        table.getModel().setValueAt(endY, 1, 1);
        table.getModel().setValueAt(endZ, 1, 2);

        chunkTable.getModel().setValueAt(chunkSizeX, 0, 0);
        chunkTable.getModel().setValueAt(chunkSizeY, 0, 1);
        chunkTable.getModel().setValueAt(chunkSizeZ, 0, 2);
    }

    // For testing and debugging the interface with a main method.
   //  public static void main(String[] args) { new PWZGUI(); }
}