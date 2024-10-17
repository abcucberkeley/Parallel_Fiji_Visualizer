[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.7613251.svg)](https://doi.org/10.5281/zenodo.7613251)
# Parallel_Fiji_Visualizer
An efficient TIFF/Zarr reader/writer that can utilize all the cores of your CPU!

## Limitations
1. Currently RGB tiffs are not supported but support may be added in the future
2. ImageJ tiff files (tiff files written by ImageJ) with multiple color channels are unofficially supported

## Quick Start Guide

### Prerequisites
1. An up-to-date installation of Fiji which can be downloaded from here: https://imagej.net/software/fiji/downloads

2. OS versions supported: Windows 10 or later, Ubuntu 22.04 (other versions of Linux may work), and macOS 12.1 or later

### Download and Install
1. Download the latest release for your OS from here (windows/linux/mac.zip): https://github.com/abcucberkeley/Parallel_Fiji_Visualizer/releases
2. Unzip the folder
3. You should now see two folders within the folder you just unzipped
4. Copy the _lib folder to the lib/"os" folder in the root of your Fiji installation. Example on Windows: Fiji.app\lib\win64
5. Copy the _plugin folder to the plugins folder in the root of your Fiji installation. Example on Windows: Fiji.app\plugins
6. Now launch a new session of Fiji and you should be able to see the plugin under the Plugins tab! (You can also search for "Parallel Fiji Visualizer" in the search bar)

## Reference

Please cite our software if you find it useful in your work:

Xiongtao Ruan, Matthew Mueller, Gaoxiang Liu, Frederik Görlitz, Tian-Ming Fu, Daniel E. Milkie, Joshua L. Lillvis, Alexander Kuhn, Chu Yi Aaron Herr, Wilmene Hercule, Marc Nienhaus, Alison N. Killilea, Eric Betzig, Srigokul Upadhyayula. Image processing tools for petabyte-scale light sheet microscopy data. Nature Methods (2024). https://doi.org/10.1038/s41592-024-02475-4
