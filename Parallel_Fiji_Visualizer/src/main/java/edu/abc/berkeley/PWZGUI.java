package edu.abc.berkeley;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

import ij.ImageStack;

import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;

import java.io.File;

public class PWZGUI extends JFrame implements ActionListener{
    /*
     Panel contains three fields.
     GroupLayout - Layout that groups these specific widgets together
     searchField - JTextField
     browse - JButton
     filepathLabel - JLabel
    */
    JPanel textfieldPanel;
    GroupLayout textGroupLayout; // layout to group these widgets together
    JTextField searchField;
    JButton browse;
    JButton save;
    JLabel filepathLabel;

    // Setting up starting and ending coords JTable and JTablePanel.
    JPanel coordsPanel; // panel containing JTable
    JTable table;
    JScrollPane scrollPane;

    // Starting and ending XYZ
    public long startX, startY, startZ;
    public long endX, endY, endZ;

    // Chunk Table-related properties
    JPanel chunkPanel;
    JTable chunkTable;
    JScrollPane chunkScrollPane;

    public long chunkSizeX, chunkSizeY, chunkSizeZ;

    // Compression labels and textFields and save JButton
    JPanel compressorPanel;
    GroupLayout compressorLayout;
    JLabel compressorLabel;
    JComboBox compressorComboBox;

    String filepath;
    String compressor;

    ImageStack imageStack;
    Object[] cImageObj;
    long bits;


    public PWZGUI(){
        filepath = "";
        compressor = "lz4"; // Default expected to be lz4. (Can be modiefied)
        imageStack = null;
        startX = 0;
        startY = 0;
        startZ = 0;
        endX = 0;
        endY = 0;
        endZ = 0;
        chunkSizeX = 256;
        chunkSizeY = 256;
        chunkSizeZ = 256;

        initialize();
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

        initialize();
    }

    // This helps us create one instance to run the interface and call in each of the constructor
    // Rather then having the same runnable in both, we create a function to call once to initialize the itnerface
    public void initialize(){
        // Creates a thread. Lets be called and execute object in its own instance.
        // Basically like its own thread.
        Runnable r = new Runnable(){
            @Override
            public void run(){
                setup();
            }
        };

        SwingUtilities.invokeLater(r);
    }

    private void setup(){
        setTitle("Write Zarr");
        setSize(800, 600);

        // Grab the primary monitor screen dimension
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();

        // set the location of the frame to be in the center of the primary monitor
        setLocation(screenSize.width/2 - getWidth()/2, screenSize.height/2 - getHeight()/2);

        filepathProperties();
        startingEndingCoords();
        chunkProperties();

        pack();
    }

    private void filepathProperties(){
        textfieldPanel = new JPanel();
        textGroupLayout = new GroupLayout(textfieldPanel);
        textfieldPanel.setLayout(textGroupLayout);

        searchField = new JTextField(30);
        browse = new JButton("Browse");
        save = new JButton("Save");
        filepathLabel = new JLabel("Filepath");

        browse.addActionListener(this);
        save.addActionListener(this);

        textGroupLayout.setAutoCreateGaps(true);
        textGroupLayout.setAutoCreateContainerGaps(true);
        
        // Handles horizontally aligning are widgets and set the JPanel using GroupLayout to BorderLayout.NORTH
        // sets the components grouped up together in the order we want them to be layed out.
        textGroupLayout.setHorizontalGroup(textGroupLayout.createSequentialGroup()
            .addComponent(filepathLabel)
            .addComponent(searchField)
            .addComponent(browse)
            .addComponent(save)
        );
        
        // Handles vertically aligning are widgets and set the JPanel using GroupLayout to BorderLayout.NORTH
        textGroupLayout.setVerticalGroup(textGroupLayout.createParallelGroup()
            .addComponent(filepathLabel)
            .addComponent(searchField)
            .addComponent(browse)
            .addComponent(save)
        );

        getContentPane().add(textfieldPanel, BorderLayout.NORTH);
    }

    private void startingEndingCoords(){
        coordsPanel = new JPanel(new GridBagLayout());
        scrollPane = new JScrollPane();
        table = new JTable(10, 10);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.weightx = 1.0;
        gbc.gridheight = 50;
        gbc.gridwidth = 100;
        gbc.fill = GridBagConstraints.HORIZONTAL; // This fills the interface with horizontally. Uncomment to see changes.

        update(); // update table

        coordsPanel.add(scrollPane, gbc);
        
        add(coordsPanel, BorderLayout.CENTER);
    }

    private void chunkProperties(){
        chunkPanel = new JPanel();
        chunkPanel.setLayout(new BorderLayout());

        compressorPanel = new JPanel();
        compressorLayout = new GroupLayout(compressorPanel);
        compressorPanel.setLayout(compressorLayout);

        compressorLabel = new JLabel("Compressor");

        String[] options = {"lz4", "zstd", "blosclz", "lz4hc", "zlib", "gzip"}; // dropdown menu options important to less important
        compressorComboBox = new JComboBox<String>(options);

        chunkData();
        compressorLayoutProperties();

        chunkPanel.add(compressorPanel, BorderLayout.SOUTH);
        add(chunkPanel, BorderLayout.SOUTH);
    }


    // Update table
    public void update(){
        DefaultTableModel tableModel = new DefaultTableModel(){
            @Override
            public boolean isCellEditable(int row, int col) {
                if(row == 4 && col == 2) return false;
                return true;
            }
        };

        table = new JTable(tableModel);
        table.setBounds(235, 55, 115, 25);

        Object[][] data1 = {{startX, startY, startZ, endX, endY, endZ}};

        // Adding columns to the table
        tableModel.addColumn("Start X");
        tableModel.addColumn("Start Y");
        tableModel.addColumn("Start Z");
        tableModel.addColumn("End X");
        tableModel.addColumn("End Y");
        tableModel.addColumn("End Z");


        // Adding rows to the table.
        for(int i = 0; i < data1.length; i++) tableModel.addRow(data1[i]);
        

        scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(250, 100)); // Actually setting how large the scroll pane is.
        scrollPane.setBounds(195, 55, 230, 53);
        scrollPane.setVisible(true);
    }

    private void chunkData(){
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
        chunkScrollPane.setPreferredSize(new Dimension(20, 90));
        chunkScrollPane.setLocation(200, 250);
    }

    private void compressorLayoutProperties(){
        compressorLayout.setAutoCreateGaps(true);
        compressorLayout.setAutoCreateContainerGaps(true);


        // Handling vertical and horizontally alignments in the windodw.
        //horizontal alignment
        compressorLayout.setHorizontalGroup(
            compressorLayout.createSequentialGroup()
                        .addComponent(chunkScrollPane)
                        .addComponent(compressorLabel)
                        .addComponent(compressorComboBox)
        );

        //vertical alignment
        compressorLayout.setVerticalGroup(
            compressorLayout.createParallelGroup(GroupLayout.Alignment.BASELINE)
                        .addComponent(chunkScrollPane)
                        .addGap(20)
                        .addComponent(compressorLabel)
                        .addComponent(compressorComboBox) // dropdown menu with options
        );
    }

    // Display error message window, if the file is not a zarr file.
    private void errorMessage(){
        String message = "File must be a zarr file";
        JOptionPane.showMessageDialog(this, message);
        return;
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
        searchField.setText(this.filepath); // updates text area.
    }

    // helper function to check if file extension has .zarr
    private boolean checkExtension(String path){
        String extension = "";
        int i = path.lastIndexOf('.');

        if(i != -1) extension = path.substring(i+1);
        
        if(extension.equals("zarr")) return true;

        return false;
    }

    // "Submit" changes when "Save" clicked then updates interface.
    private void submit(){
        if(!checkExtension(this.filepath) && !checkExtension(searchField.getText())) {
            errorMessage(); // Checks if the file is a zarr file before we save and update
            return; // Do not want to continue so leave function. (OutofBounds error will occur, if this does not leave function call)
        }

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
    
    @Override
    public void actionPerformed(ActionEvent e){
        if(e.getSource() == browse) loadfile(); // We want to check if the checkbox is clicked before we compress that file.        
        if(e.getSource() == save) submit(); // Updates the changes to interface.
    }
}
