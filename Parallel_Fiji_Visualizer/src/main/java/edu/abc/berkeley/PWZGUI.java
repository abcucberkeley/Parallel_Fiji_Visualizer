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
import java.nio.file.Files;

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
 * HOW TO RESIZES WIDGETS (MEETING NOTES)
 * --> Using custom grid layouts. (For future ref, for resizeable widgets)
 * 
 * FLow should work (Refactor)
 
What should the interface do actually do?

 * - Remove crop button. (Should use coordinates) [DONE]
 * 1.) Should be able to browse even without typing the filepath [DONE]
 * 2.) Filepath taking in the value from file.getPath(). [???]
 * 3.) Update text area and this.filepath when browsing to a file. [DONE]
 * 4.) Keep in mind: lz4 should be an editable text box (like the filepath text area) [DONE]
 * 5.) Save changes to submit, and set location to be at the bottom of the window. 
 * 6.) Submit button clicked, then should quit out of the form. (Close this JFrame only, not the entire software)
 * 
 * VARIABLES
 * - Add in the constructor params ImageStack array, in the constructor.
 * - Take out default values variables in update table.
 * 
 * What to check for? (Error Checking)
 * - Check for only .zarr file. (If not, then display error message), these are the only error-checking required right now.
 * 
 */

/*
    C++ MAIN PROJECT.
 * 1.) Release and get matlab working with C++ Qt (version, 6.2.4)
 */

public class PWZGUI implements ActionListener{
    JFrame window;
    JPanel textAreaPanel;

    JButton browse; // Browse specific filepath.

    // Text box
    JLabel textAreaLabel;
    JTextArea textArea;

    // For compressor lz4
    JTextArea compressTextArea;// Compressor Text Area
    JLabel compressorLabel; // Default Compressor

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

    public ImageStack imageStack;

    public Object[] cImageObj;

    public int bits;

    JTable chunkTable; // Displaying the chunk sizes that are the default XYZ coords. (Though not expected to be changed.)
    JScrollPane chunkScrollPane; // Scroll pane, to help display the default values of the chunk size.
    
    

    JButton submitButton; // To submit changes, then close this specific interface. (Not the entire software.)

    public PWZGUI() {
        filepath = "";
        compressor = "lz4"; // Compressor is lz4. (NOTE, this value does not change by default whatsoever)
        imageStack = null;

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

        bits = 0;

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
    public PWZGUI(String filepath, ImageStack imageStack, long startX, long startY, long startZ, long endX, long endY, long endZ, long chunkSizeX, long chunkSizeY, long chunkSizeZ, String compressor, int bits){
        this.filepath = filepath;
        this.compressor = compressor;
        this.imageStack = imageStack;
        this.cImageObj = imageStack.getImageArray(); // Is this how we want to pass in an array

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

        this.bits = bits;

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
        compressTextArea = new JTextArea();



        setupTextProperties(); // Handling button widget
        grabCoordinates();
        chunkAndCompressorProperties();
        submitButton(); // Submit button: What this does is submit the given changes, then close this specific interface.
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
    
    private void grabCoordinates(){
        // Labels allowing the user using the interface to know
        startCoordsLabel = new JLabel("Start");
        endCoordsLabel = new JLabel("End");

        // startCoordsLabel.setBounds(115, 90, 150, 150);
        // endCoordsLabel.setBounds(115, 110, 150, 150);
        startCoordsLabel.setBounds(140, 5, 150, 150);
        endCoordsLabel.setBounds(145, 20, 150, 150);

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
        // table.setBounds(30, 30, 950, 950);
        table.setBounds(235, 55, 115, 25);

        // Adding columns to the table
        tableModel.addColumn("X");
        tableModel.addColumn("Y");
        tableModel.addColumn("Z");

        // Adding rows to the table.
        for(int i = 0; i < data.length; i++) tableModel.addRow(data[i]);

        scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(250, 100)); // Actually setting how large the scroll pane is.
        // scrollPane.setBounds(172, 145, 250, 53); // Set where we want the JTable to be located in the JFrame. (Actually adjusts X and Y coords, and width and height)
        scrollPane.setBounds(195, 55, 230, 53);
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
        // chunkScrollPane.setBounds(170, 205, 255, 40); // Set where we want the JTable to be located in the JFrame. (This line is what updates the X, Y coordinates of widget)
        chunkScrollPane.setBounds(195, 125, 230, 53); // Set where we want the JTable to be located in the JFrame. (This line is what updates the X, Y coordinates of widget)
        chunkScrollPane.setVisible(true);

        // Compressor = default is lz4 (though the user, should also be able to change the default as an option as well)
        compressorLabel.setText("Compressor: ");
        compressorLabel.setBounds(180, 195, 130, 20); // NOTE: Higher the Y-axis lower widgets are positioned. Higher X axis, more to right the widgets positioned at.
        
        compressTextArea.setText(compressor);
        // compressTextArea.setBounds(265,245, 65, 19);
        compressTextArea.setBounds(265,195, 65, 19);
    }

    // Function to help organize the widget that handles the saving changes button.
    private void submitButton() {
        submitButton = new JButton("Save Changes");
        // saveChangesButton.setBounds(230, 55, 115, 25);
        // submitButton.setBounds(212, 55, 115, 25);
        submitButton.setBounds(175, 225, 115, 25);
        submitButton.addActionListener(this);
    }

    // Display error message window, if the file is not a zarr file.
    private void errorMessage(){
        String message = "File must be a zarr file";
        // JOptionPane.showInputDialog(message);
        JOptionPane.showMessageDialog(window, message);
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
        window.add(startCoordsLabel);
        window.add(endCoordsLabel);
        window.add(scrollPane);
        window.add(chunkScrollPane);
        window.add(compressorLabel);
        window.add(compressTextArea);

        
        window.add(submitButton);

        
        

        // Setting window/jframe properties
        // window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        window.add(textAreaPanel, BorderLayout.CENTER); // This is how the TextArea and Labels, are being centered to the TOP of the interface.
        window.pack();
        window.setLocation(new Point(900, 250)); // Hopefully puts the screen in the center of the monitor (vary depending on monitoring)
        window.setVisible(true);
    }

    // Handling events happening with the interface.
    @Override
    public void actionPerformed(ActionEvent e){
        if(e.getSource() == browse) loadfile(); // We want to check if the checkbox is clicked before we compress that file.
        // Updates and clears the table.
        if(e.getSource() == submitButton) updateTable(); // Updates the table when we submit the changes, and then is the function that quits out the JFrame for this specific interface.
    }

    // Loading filepath given.
    private void loadfile(){
        // File Handling stuff. (Referenced PWZ.java)
        // PWZC pwzc = new PWZC(); // This code is only used to call the C-functionality...  (Wont work on mac, so comment out in the meantime.)
        JFileChooser chooser = new JFileChooser();
        chooser.setApproveButtonText("Save");
		chooser.setDialogTitle("Save as Zarr");
        File file = null;

        int returnValue = chooser.showOpenDialog(null);

        if(returnValue == JFileChooser.APPROVE_OPTION) file = chooser.getSelectedFile();
        if(file == null) return;

        ImagePlus cImagePlus = IJ.getImage();

        if(cImagePlus == null) return;

		// pwzc.parallelWriteZarr(this.filepath, this.cImageObj, this.startX, this.startY, this.startZ, this.endX, this.endY, this.endZ, this.chunkSizeX, this.chunkSizeY, this.chunkSizeZ, 1, this.compressor, 1, this.bits);
    }

    private boolean checkExtension(String path){
        String extension = "";
        int i = path.lastIndexOf('.');

        if(i != -1) extension = path.substring(i+1);

        // System.out.println("LOGGER: " + extension + ", i = " + i); // For debugging purposes.
        
        if(extension.equals("zarr")) return true;

        return false;
    }

    // When "Submit changes" button is clicked, the given inputted information is updated
    private void updateTable(){

        // Checks if the text area box is a zarr file, or the filepath we load in from the browser button are a zarr file. If not display error message.
        if(!checkExtension(this.filepath) && !checkExtension(textArea.getText())) errorMessage();
        textArea.setText(this.filepath);

        table.getModel().setValueAt(startX, 0, 0);
        table.getModel().setValueAt(startY, 0, 1);
        table.getModel().setValueAt(startZ, 0, 2);
        table.getModel().setValueAt(endX, 1, 0);
        table.getModel().setValueAt(endY, 1, 1);
        table.getModel().setValueAt(endZ, 1, 2);

        chunkTable.getModel().setValueAt(chunkSizeX, 0, 0);
        chunkTable.getModel().setValueAt(chunkSizeY, 0, 1);
        chunkTable.getModel().setValueAt(chunkSizeZ, 0, 2);

        window.dispose(); // Dispose() is how we will exit, once the submit changes have been made.
    }

    // For testing and debugging the interface with a main method.
   // public static void main(String[] args) { new PWZGUI(); }
}