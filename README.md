[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.7613251.svg)](https://doi.org/10.5281/zenodo.7613251)
# Parallel_Fiji_Visualizer
An efficient TIFF/Zarr reader/writer for ImageJ/Fiji that can utilize all the cores of your CPU!

The heavy lifting is done by the native
[cpp-tiff](https://github.com/abcucberkeley/cpp-tiff) and
[cpp-zarr](https://github.com/abcucberkeley/cpp-zarr) libraries, which are
bundled inside the plugin jar and loaded automatically.

## Limitations
1. Chunky (interleaved) 8-bit RGB/RGBA tiffs are read in parallel; planar RGB tiffs and 16-bit RGB are not supported. RGB images are saved with ImageJ's own tiff writer rather than the parallel one, and cannot be saved as zarr
2. ImageJ tiff files (tiff files written by ImageJ) with multiple color channels are unofficially supported

## Quick Start Guide

### Prerequisites
1. An up-to-date installation of Fiji which can be downloaded from here: https://imagej.net/software/fiji/downloads
2. OS versions supported: Windows 10 or later, Ubuntu 22.04 (Most modern Linux distributions should also work), and macOS 13 or later (Apple Silicon/M-series only. Intel Macs are not supported as of v1.3.0)
3. **On Macs, use the Apple silicon Fiji download.** The classic/"stable" Fiji ships an Intel Java and runs through Rosetta even on M-series Macs, so it cannot load the plugin's ARM natives (the plugin will show an error explaining this)

### Download and Install
1. Download `Parallel_Fiji_Visualizer-<version>.jar` from the latest release: https://github.com/abcucberkeley/Parallel_Fiji_Visualizer/releases
2. Copy the jar into the `plugins` folder of your Fiji installation (e.g. `Fiji.app/plugins`)
3. Launch a new session of Fiji and the plugin appears under the Plugins tab! (You can also search for "Parallel Fiji Visualizer" in the search bar)

The same jar works on Windows, Linux, and macOS.

**Upgrading from 1.2.x or older:** delete the old
`Parallel_Fiji_Visualizer_lib` folder from `Fiji.app/lib/<os>` and the old
`Parallel_Fiji_Visualizer_plugin` jar from `Fiji.app/plugins` first; they are
no longer used.

## Building from Source

Requirements:
- JDK 16+ and Maven
- CMake ≥ 3.18 and a C++ toolchain with OpenMP (g++ on Linux/Windows-MinGW,
  Homebrew gcc on macOS)
- Network access on the first build (the native build downloads pinned
  releases of cpp-tiff, cpp-zarr and their dependencies)

```sh
# Java only (uses natives already staged under target/classes, if any):
mvn clean package

# Java + native libraries for the current platform:
mvn clean package -Pnative
```

The result is `target/Parallel_Fiji_Visualizer-<version>.jar` containing the
natives for the platform you built on. Release jars are produced by a Jenkins
pipeline that runs the `-Pnative` build on Linux, Windows and macOS and merges
the three native sets into one cross-platform jar.

### Tests

`src/test/java` contains round-trip regression tests for the JNI layer and
the Java glue (write an image natively, read it back, compare every pixel —
including the signed/integer dtype conversions and RGB). They run during
`mvn test -Pnative` / `mvn package -Pnative` and are skipped automatically
when the natives are not staged.

### Developing in an IDE (Eclipse)

Import the repository root via *File → Import → Maven → Existing Maven
Projects*, then run `ParallelFijiVisualizer` as a Java Application — its
`main()` starts ImageJ with the plugin loaded.

Build the natives once with `mvn process-classes -Pnative`. They are staged
both into `target/classes/natives/` (packaged into the jar) and into
`target/natives/` — IDE full builds wipe the former, and the plugin
automatically falls back to the latter, so Eclipse cleans don't break native
loading. After `mvn clean` (which removes both), rerun
`mvn process-classes -Pnative`. To point at natives somewhere else entirely,
set `-Dpfv.natives.dir=/path/to/libs` on the run configuration.

### Repository layout

```
pom.xml                  Maven build; javac -h generates the JNI headers
src/main/java/           the ImageJ plugin (edu.abc.berkeley) + ij.* extensions
src/main/native/         CMake superbuild: fetches pinned cpp-tiff/cpp-zarr
                         releases and compiles the two JNI libraries
                         (libParallelReadC, libParallelWriteC)
src/main/resources/      plugins.config (Fiji menu entry)
```

To bump the native library versions, edit `CPP_TIFF_VERSION` /
`CPP_ZARR_VERSION` (and their SHA256 pins) in `src/main/native/CMakeLists.txt`.

## Reference

Please cite our software if you find it useful in your work:

Xiongtao Ruan, Matthew Mueller, Gaoxiang Liu, Frederik Görlitz, Tian-Ming Fu, Daniel E. Milkie, Joshua L. Lillvis, Alexander Kuhn, Chu Yi Aaron Herr, Wilmene Hercule, Marc Nienhaus, Alison N. Killilea, Eric Betzig, Srigokul Upadhyayula. Image processing tools for petabyte-scale light sheet microscopy data. Nature Methods (2024). https://doi.org/10.1038/s41592-024-02475-4
