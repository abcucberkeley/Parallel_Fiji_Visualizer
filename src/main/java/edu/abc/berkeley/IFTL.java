package edu.abc.berkeley;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Locale;

import ij.IJ;

/** Opens the TIFF files and Zarr folders listed in a text file, one per line. */
public class IFTL {
	IFTL(String fileName){
		try (BufferedReader br = new BufferedReader(new FileReader(fileName))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim(); // remove whitespace
                if (line.isEmpty()) continue;
                if (PRT.isTiff(line)) {
                    new PRT(line);
                }
                else if (ZarrMetadata.isZarr(new File(line)) || line.toLowerCase(Locale.ROOT).endsWith(".zarr")) {
                	new PRZ(line);
                }
                else {
                	IJ.log(line + " is not a TIFF file (.tif or .tiff) or a Zarr folder; skipped.");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
	}
}
