package edu.abc.berkeley;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

import ij.ImageStack;

import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;

import java.io.File;

public class PWZGUI implements ActionListener{
    JFrame window;
    JPanel textAreaPanel;

    JButton browse; // Browse specific files/folders.

    // Text box
    JLabel textAreaLabel;
    JTextArea textArea;

    // For compressor lz4
    JTextArea compressTextArea;// Compressor Text Area
    JLabel compressorLabel; // Default Compressor

    
    JTable table;
    JScrollPane scrollPane;
    
    JLabel startCoordsLabel;
    JLabel endCoordsLabel;

    public String filepath;
    public String compressor;

    // Starting and ending XYZ
    public long startX=0, startY=0, startZ=0;
    public long endX=0, endY=0, endZ=0;

    // Default values for the chunk sizes.
    public long chunkSizeX=256, chunkSizeY=256, chunkSizeZ=256;

    public ImageStack imageStack;

    public Object[] cImageObj;

    public long bits;

    JTable chunkTable; // Table to show Chunk Size XYZ. (Changeable)
    JScrollPane chunkScrollPane;
    
    JButton save; // save changes

    public PWZGUI() {
        filepath = "";
        compressor = "lz4"; // Default expected to be lz4. (Is changeable)
        imageStack = null;
        startX = 0;
        startY = 0;
        startZ = 0;
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

    public PWZGUI(String filepath, ImageStack imageStack, long startX, long startY, long startZ, long endX, long endY, long endZ, long chunkSizeX, long chunkSizeY, long chunkSizeZ, String compressor, long bits){
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
        save(); // Save: What this does is submit the given changes, then close this specific interface.
        show(); // Just adds in buttons to frame.

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
        textAreaLabel.setBounds(125, 5, 90, 15);

        textArea = new JTextArea(1, 6);
        textArea.setBounds(215, 5, 75, 25);
        textArea.setLayout(new FlowLayout());

    }
    
    private void grabCoordinates(){
        // Labels allowing the user using the interface to know
        startCoordsLabel = new JLabel("Start");
        endCoordsLabel = new JLabel("End");

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
        table.setBounds(235, 55, 115, 25);

        // Adding columns to the table
        tableModel.addColumn("X");
        tableModel.addColumn("Y");
        tableModel.addColumn("Z");

        // Adding rows to the table.
        for(int i = 0; i < data.length; i++) tableModel.addRow(data[i]);

        scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(250, 100)); // Actually setting how large the scroll pane is.
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
        chunkScrollPane.setPreferredSize(new Dimension(200, 100)); // Actually setting how large the scroll pane is.
        chunkScrollPane.setBounds(195, 125, 230, 53); // Set where we want the JTable to be located in the JFrame. (This line is what updates the X, Y coordinates of widget)
        chunkScrollPane.setVisible(true);

        compressorLabel.setText("Compressor: ");
        compressorLabel.setBounds(180, 195, 130, 20); // NOTE FOR REF: Higher the Y-axis lower widgets are positioned. Higher X axis, more to right the widgets positioned at.
        
        compressTextArea.setText(compressor);
        compressTextArea.setBounds(265,195, 65, 19);
    }

    // Function to help organize the widget that handles the saving changes button.
    // When browsing this save button will update the text box.
    private void save() {
        save = new JButton("Save");
        save.setBounds(175, 225, 115, 25);
        save.addActionListener(this);
    }

    // Display error message window, if the file is not a zarr file.
    private void errorMessage(){
        String message = "File must be a zarr file";
        JOptionPane.showMessageDialog(window, message);
    }


    private void show(){
        // sets up text area box.
        textAreaPanel.add(textAreaLabel);
        textAreaPanel.add(textArea);
        textAreaPanel.add(browse);
        textAreaPanel.setSize(50, 50);

        // sets up widgets to interface.
        window.add(startCoordsLabel);
        window.add(endCoordsLabel);
        window.add(scrollPane);
        window.add(chunkScrollPane);
        window.add(compressorLabel);
        window.add(compressTextArea);
        window.add(save);
        window.add(textAreaPanel, BorderLayout.CENTER); // This is how the TextArea and Labels, are being centered to the TOP of the interface.

        window.pack();
        window.setLocation(new Point(900, 250)); // Hopefully puts the screen in the center of the monitor (vary depending on monitoring)
        window.setVisible(true);
    }

    @Override
    public void actionPerformed(ActionEvent e){

        if(e.getSource() == browse) loadfile(); // We want to check if the checkbox is clicked before we compress that file.        
        if(e.getSource() == save) submit(); // Updates the changes to interface.

    }

    // Load new/selected file.
    private void loadfile(){
        JFileChooser chooser = new JFileChooser();
        chooser.setApproveButtonText("Save");
		chooser.setDialogTitle("Save as Zarr");
        File file = null;

        int returnValue = chooser.showDialog(null, "Save");

        if(returnValue == JFileChooser.APPROVE_OPTION) file = chooser.getSelectedFile();
        
        if(file == null) return;

        this.filepath = file.getPath();
        textArea.setText(this.filepath); // updates text area.
    }

    // Function for handling file extensions.
    private boolean checkExtension(String path){
        String extension = "";
        int i = path.lastIndexOf('.');

        if(i != -1) extension = path.substring(i+1);
        
        if(extension.equals("zarr")) return true;

        return false;
    }

    // "Submit" changes when "Save" clicked then updates interface.
    private void submit(){
        if(!checkExtension(this.filepath) && !checkExtension(textArea.getText())) errorMessage(); // Checks if the file is a zarr file before we save and update

        // Manually adding inputs into the charts. Maybe a better way, but for now.
        startX = (long) table.getModel().getValueAt(0, 0);
        startY = (long) table.getModel().getValueAt(0, 1);
        startZ = (long) table.getModel().getValueAt(0, 2);
        endX = (long) table.getModel().getValueAt(1, 0);
        endY = (long) table.getModel().getValueAt(1, 1);
        endZ = (long) table.getModel().getValueAt(1, 2);

        chunkSizeX = (long) chunkTable.getModel().getValueAt(0, 0);
        chunkSizeY = (long) chunkTable.getModel().getValueAt(0, 1);
        chunkSizeZ = (long) chunkTable.getModel().getValueAt(0, 2);
        
        bits = imageStack.getBitDepth();
        
        PWZC pwzc = new PWZC();
        
        pwzc.parallelWriteZarr(filepath, cImageObj, startX, startY, startZ, endX, endY, endZ, chunkSizeX, chunkSizeY, chunkSizeZ, 1, compressor, 1, bits);
    }
}
