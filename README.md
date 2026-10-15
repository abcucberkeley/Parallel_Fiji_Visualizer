[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.7613251.svg)](https://doi.org/10.5281/zenodo.7613251)
[![Tests](https://github.com/abcucberkeley/Parallel_Fiji_Visualizer/actions/workflows/tests.yml/badge.svg)](https://github.com/abcucberkeley/Parallel_Fiji_Visualizer/actions/workflows/tests.yml)
# Parallel_Fiji_Visualizer
An efficient TIFF/Zarr reader/writer for ImageJ/Fiji that can utilize all the cores of your CPU!

The heavy lifting is done by the native
[cpp-tiff](https://github.com/abcucberkeley/cpp-tiff) and
[cpp-zarr](https://github.com/abcucberkeley/cpp-zarr) libraries, which are
bundled inside the plugin jar and loaded automatically.

## Limitations
1. RGB images are saved with ImageJ's own tiff writer rather than the parallel one, and cannot be saved as zarr
2. Complex-valued zarr arrays cannot be opened. Data types ImageJ lacks are converted: float16, bfloat16, 32- and 64-bit integers and doubles open as 32-bit float images, and bool arrays as images of 0 and 1

## Quick Start Guide

### Prerequisites
1. An up-to-date installation of Fiji which can be downloaded from here: https://imagej.net/software/fiji/downloads
2. OS versions supported: Windows 10 or later, Ubuntu 22.04 (Most modern Linux distributions should also work), and macOS 13 or later (Apple Silicon/M-series only. Intel Macs are not supported as of v1.3.0)
3. **On Macs, use the Apple silicon Fiji download.** The classic/"stable" Fiji ships an Intel Java and runs through Rosetta even on M-series Macs, so it cannot load the plugin's ARM natives (the plugin will show an error explaining this)

### Install
Install the plugin through Fiji's updater. Fiji then keeps it up to date along with everything else.

1. In Fiji, open **Help › Update…** and click **Manage Update Sites**.
2. Click **Add Unlisted Site** and fill in the new row:
   - **Name:** `Parallel-Fiji-Visualizer`
   - **URL:** `https://sites.imagej.net/Parallel-Fiji-Visualizer/`
3. Click **Apply and Close**, then **Apply Changes**, and restart Fiji.

The plugin is then in the **Plugins** menu as **Parallel Fiji Visualizer**.

**Copied the plugin jar into Fiji by hand before?** Delete `Parallel_Fiji_Visualizer-<version>.jar` from Fiji's `plugins` folder first. Otherwise Fiji may keep your copy and never update it.

**No internet on the Fiji machine?** Copy `Parallel_Fiji_Visualizer-<version>.jar` from the [latest release](https://github.com/abcucberkeley/Parallel_Fiji_Visualizer/releases) into Fiji's `plugins` folder instead. It will not update itself.

**Upgrading from 1.2.x or older?** Delete the old
`Parallel_Fiji_Visualizer_lib` folder from `Fiji.app/lib/<os>` and the old
`Parallel_Fiji_Visualizer_plugin` jar from `Fiji.app/plugins` first; they are
no longer used.

### Opening Zarr
Drag a Zarr folder onto the plugin window. A folder is a Zarr if it holds Zarr
metadata (`.zarray`, `zarr.json` or `.zgroup`). The
plugin opens:
- Zarr v2 and v3 arrays with 2 to 5 axes, as hyperstacks with channel and time sliders
- OME-Zarr images (their full-resolution level, with its pixel size)
- groups that hold a single image

Axis names come from the metadata: Zarr v3 `dimension_names`, xarray's
`_ARRAY_DIMENSIONS`, or the OME-Zarr axes. Arrays without names are read as:
- **Zarr v2:** MATLAB's order (y, x, z, c, t), as PetaKit5D and this plugin
  write them. Chunks of 1 on the first axes mean NumPy's order (t, c, z, y, x)
  instead, and so does C storage order for 4D and 5D arrays.
- **Zarr v3:** (t, z, y, x, c), or (t, c, z, y, x) when the last axis is longer than 8.

Arrays with fewer axes leave out the ones they lack: a 4D Zarr v2 array is
(y, x, z, t), and a 3D Zarr v3 volume (z, y, x).

**Axes mixed up?** Set **Zarr axis order** at the bottom of the plugin window
to the array's axes in order, using x, y, z, c (channel) and t (time), e.g.
`tzyxc`, and open it again. An order applies to unnamed arrays with that many
axes, so give one per number of axes, separated by commas: `yxz, tzyxc`.
**Auto** goes back to the rules above. Macros can set it with
`call("ij.Prefs.set", "pfv.zarrAxisOrder", "tzyxc");`.

### Large images and color
TIFF and Zarr images bigger than Fiji's memory (**Edit › Options › Memory &
Threads**) open as virtual stacks, read a block of planes at a time as you
scroll. 8-bit color TIFFs open as RGB images, and 16-bit or float color as one
channel per color, as ImageJ shows them.

### Saving
**Save as Tiff** and **Save as Zarr** save in the background, so Fiji stays
usable while they run. The image is locked until it has been saved, as ImageJ
locks the images its commands are working on: its sliders are greyed out and
other commands leave it alone. Saving a virtual stack reads all of its planes,
so it has to fit in Fiji's memory.

### Saving as Zarr
**Save as Zarr** writes a Zarr v2 array in MATLAB's axis order, as PetaKit5D
does: (y, x, z), or (y, x, z, c, t) for a hyperstack, with the axis names
recorded so the plugin (and xarray) read the hyperstack back as it was. Chunk
files go in nested folders (`0/0/0`), which writes much faster to network file
systems than one folder of them all.

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

`src/test/java` holds regression tests for the JNI layer and the Java glue:
native round trips for every data type, end-to-end reads through the plugin's
readers, Zarr v2, v3 and OME-Zarr layouts and their axes, color and ImageJ
TIFFs, saving stacks and hyperstacks as Zarr, folder and hyperstack opening,
the virtual stacks, and checks that the plugin still loads in a stock Fiji. `mvn package -Pnative` builds the natives
and runs every test; a test that needs the natives fails if they were built but
cannot be loaded. Without `-Pnative` those tests are skipped and the Java-only
tests still run.

[GitHub Actions](.github/workflows/tests.yml) runs the same build and tests on
Linux x64, Windows x64 and macOS arm64 for every push that changes more than
documentation.

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
