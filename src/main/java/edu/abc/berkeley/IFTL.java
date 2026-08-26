package edu.abc.berkeley;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class IFTL {
	IFTL(String fileName){
		try (BufferedReader br = new BufferedReader(new FileReader(fileName))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim(); // remove whitespace
                if (!line.isEmpty() && line.toLowerCase().endsWith(".tif")) {
                    new PRT(line);
                }
                else {
                	new PRZ(line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
	}
}
