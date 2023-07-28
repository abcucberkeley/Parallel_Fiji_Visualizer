package edu.abc.berkeley;

import org.apache.commons.lang.SystemUtils;

import ij.IJ;

public class helperFunctions {
    public static void loadLib(String libName) {
		String jLP = System.getProperty("java.library.path");
		String delim = ";";
		if(SystemUtils.IS_OS_MAC) {
			delim = ":";
		}
		String[] paths = jLP.split(delim);
		String pathToLib = "";
		for(String path : paths) {
			if((path.contains("/lib/")) && !path.contains("Parallel_Fiji_Visualizer_lib")) {
				pathToLib = path;
				break;
			}
		}
		String ext = "";
		if(SystemUtils.IS_OS_LINUX) {
			ext = ".so";
		}
		else if(SystemUtils.IS_OS_WINDOWS) {
			ext = ".dll";
		}
		else if (SystemUtils.IS_OS_MAC) {
			ext = ".dylib";
		}
		else {
			IJ.log("Could not determine OS when loading lib: "+libName+"\n");
		}
		System.load(pathToLib+"/Parallel_Fiji_Visualizer_lib/"+libName+ext);
	}

}
