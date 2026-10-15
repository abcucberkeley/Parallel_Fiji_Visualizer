package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.ParallelVirtualStack;
import ij.io.FileInfo;
import ij.io.FileSaver;
import ij.process.ColorProcessor;
import ij.process.ShortProcessor;

/**
 * TIFF reading from one description of the file (getTiffInfo): reads of a
 * range of slices, images with several 16-bit samples per pixel, ImageJ
 * stacks stored as one block, and the virtual stack for TIFFs too large for
 * memory.
 */
public class TiffReadTest {

	private static final int W = 16, H = 12;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final ParallelReadNative reader = new ParallelReadNative();

	@BeforeClass
	public static void nativesRequired() {
		Natives.required();
	}

	private static short[][] slices(int z) {
		short[][] s = new short[z][W * H];
		for (int i = 0; i < z; i++)
			s[i] = TiffFixture.plane(W, H, i, 0);
		return s;
	}

	private String writeStack(String name, int z) {
		String path = new File(tmp.getRoot(), name).getPath();
		new ParallelWriteNative().parallelWriteTiff(path, slices(z), W, H, z, 16);
		return path;
	}

	@Test
	public void tiffInfoDescribesTheFile() {
		long[] info = reader.getTiffInfo(writeStack("info.tif", 5));
		assertEquals(W, info[ParallelReadNative.TIFF_WIDTH]);
		assertEquals(H, info[ParallelReadNative.TIFF_HEIGHT]);
		assertEquals(5, info[ParallelReadNative.TIFF_SLICES]);
		assertEquals(5, info[ParallelReadNative.TIFF_DIRECTORIES]);
		assertEquals(16, info[ParallelReadNative.TIFF_BITS]);
		assertEquals(1, info[ParallelReadNative.TIFF_SAMPLES]);
		assertEquals(1, info[ParallelReadNative.TIFF_SAMPLE_FORMAT]);
	}

	@Test
	public void notATiffIsRefused() throws Exception {
		File f = tmp.newFile("text.tif");
		java.nio.file.Files.write(f.toPath(), "not a tiff".getBytes("UTF-8"));
		try {
			reader.getTiffInfo(f.getPath());
			fail("described a file that is not a TIFF");
		}
		catch (RuntimeException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("TIFF"));
		}
	}

	@Test
	public void sliceRangesReadJustThoseSlices() {
		String path = writeStack("range.tif", 6);
		long[] info = reader.getTiffInfo(path);
		short[][] back = reader.parallelReadTiffUINT16(path, info, 2, 5, ParallelReadNative.newShortPlanes(3, W * H));
		for (int i = 0; i < 3; i++)
			assertArrayEquals("slice " + (i + 2), TiffFixture.plane(W, H, i + 2, 0), back[i]);
		long[][] bad = { { 0, 0 }, { 5, 7 }, { -1, 2 }, { 3, 2 } };
		for (long[] r : bad) {
			try {
				reader.parallelReadTiffUINT16(path, info, r[0], r[1], ParallelReadNative.newShortPlanes(1, W * H));
				fail("read slices " + r[0] + "-" + r[1]);
			}
			catch (RuntimeException e) {
				assertTrue(e.getMessage(), e.getMessage().contains("outside"));
			}
		}
		try {
			reader.parallelReadTiffUINT8(path, info, 0, 1, ParallelReadNative.newBytePlanes(1, W * H));
			fail("read 16-bit slices as 8-bit");
		}
		catch (RuntimeException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("data type"));
		}
	}

	/** How ImageJ saves stacks over 4 GB: one directory, then every plane in one block. */
	@Test
	public void imageJStackStoredAsOneBlock() throws Exception {
		String path = TiffFixture.write(new File(tmp.getRoot(), "block.tif"), W, H, 4, 1, true).getPath();
		long[] info = reader.getTiffInfo(path);
		assertEquals(1, info[ParallelReadNative.TIFF_DIRECTORIES]);
		assertEquals(4, info[ParallelReadNative.TIFF_SLICES]);
		assertEquals(1, info[ParallelReadNative.TIFF_IMAGEJ]);
		short[][] back = reader.parallelReadTiffUINT16(path, info, 1, 3, ParallelReadNative.newShortPlanes(2, W * H));
		assertArrayEquals(TiffFixture.plane(W, H, 1, 0), back[0]);
		assertArrayEquals(TiffFixture.plane(W, H, 2, 0), back[1]);

		ImagePlus imp = new PRT(path, false).getImp();
		assertEquals(4, imp.getStackSize());
		for (int z = 0; z < 4; z++)
			assertArrayEquals("slice " + z, TiffFixture.plane(W, H, z, 0), (short[]) imp.getStack().getPixels(z + 1));
	}

	/**
	 * 16-bit color opens as one channel per sample: for a single image just as
	 * ImageJ opens it, and for a stack as a hyperstack of those channels (ImageJ
	 * opens it as a plain stack of the same planes, the samples one after another).
	 */
	@Test
	public void sixteenBitColorOpensAsChannels() throws Exception {
		for (int slices : new int[] { 1, 2 }) {
			for (int samples : new int[] { 3, 4 }) {
				String what = slices + " slice(s) of " + samples + " samples: ";
				String path = TiffFixture.write(new File(tmp.getRoot(), "color" + slices + "_" + samples + ".tif"), W, H, slices, samples, false).getPath();
				ImagePlus ours = new PRT(path, false).getImp();
				assertNotNull(what + "no image", ours);
				assertEquals(what + "channels", samples, ours.getNChannels());
				assertEquals(what + "slices", slices, ours.getNSlices());
				assertTrue(what + "not shown as a composite", ours.isComposite());
				assertEquals(what + "mode", IJ.COMPOSITE, ((CompositeImage) ours).getMode());
				for (int z = 0; z < slices; z++)
					for (int s = 0; s < samples; s++)
						assertArrayEquals(what + "slice " + z + " sample " + s, TiffFixture.plane(W, H, z, s),
							(short[]) ours.getStack().getPixels(ours.getStackIndex(s + 1, z + 1, 1)));
				if (samples == 3) {
					ImagePlus ij = IJ.openImage(path);
					assertEquals(what + "planes", ij.getStackSize(), ours.getStackSize());
					if (slices == 1) assertEquals(what + "channels", ij.getNChannels(), ours.getNChannels());
					for (int n = 1; n <= ij.getStackSize(); n++)
						assertArrayEquals(what + "plane " + n, (short[]) ij.getStack().getPixels(n), (short[]) ours.getStack().getPixels(n));
				}
			}
		}
	}

	// --- The virtual stack ---

	@Test
	public void virtualTiffStackReadsBlocksOfSlices() {
		String path = writeStack("virtual.tif", 6);
		ParallelVirtualStack stack = new ParallelVirtualStack(TiffImage.open(path));
		stack.setMaxBlockPlanes(2); // blocks {1,2} {3,4} {5,6}
		assertEquals(6, stack.getSize());
		assertEquals(16, stack.getBitDepth());
		int[] order         = { 1, 2, 3, 4, 5, 6,   6, 5, 4, 3, 2, 1,   5 };
		int[] expectedReads = { 1, 1, 2, 2, 3, 3,   3, 3, 3, 3, 4, 4,   5 };
		for (int i = 0; i < order.length; i++) {
			int n = order[i];
			assertArrayEquals("slice " + n, TiffFixture.plane(W, H, n - 1, 0), (short[]) stack.getProcessor(n).getPixels());
			assertEquals("block reads after step " + i + " (slice " + n + ")", expectedReads[i], stack.getBlockReads());
		}
	}

	/** Blocks of planes that start or end inside a slice's samples. */
	@Test
	public void virtualTiffStackSplitsSamples() throws Exception {
		String path = TiffFixture.write(new File(tmp.getRoot(), "virtual48.tif"), W, H, 3, 3, false).getPath();
		ParallelVirtualStack stack = new ParallelVirtualStack(TiffImage.open(path));
		stack.setMaxBlockPlanes(2);
		assertEquals(9, stack.getSize());
		for (int n = 9; n >= 1; n--)
			assertArrayEquals("plane " + n, TiffFixture.plane(W, H, (n - 1) / 3, (n - 1) % 3), (short[]) stack.getProcessor(n).getPixels());
	}

	@Test
	public void virtualTiffStackOfColorSlices() {
		ImageStack rgb = new ImageStack(W, H);
		int[][] pixels = new int[3][W * H];
		for (int z = 0; z < 3; z++) {
			for (int i = 0; i < W * H; i++) pixels[z][i] = 0xFF000000 | (i * 3 + z) % 256 << 16 | (i * 5) % 256 << 8 | (i * 7 + z) % 256;
			rgb.addSlice(null, new ColorProcessor(W, H, pixels[z].clone()));
		}
		String path = new File(tmp.getRoot(), "rgb.tif").getPath();
		assertTrue(new FileSaver(new ImagePlus("rgb", rgb)).saveAsTiffStack(path));
		ParallelVirtualStack stack = new ParallelVirtualStack(TiffImage.open(path));
		assertEquals(24, stack.getBitDepth());
		for (int z = 0; z < 3; z++) {
			int[] px = (int[]) stack.getProcessor(z + 1).getPixels();
			for (int i = 0; i < W * H; i++)
				assertEquals("slice " + z + " pixel " + i, pixels[z][i] & 0xFFFFFF, px[i] & 0xFFFFFF);
		}
	}

	/**
	 * cpp-tiff writes BigTIFFs, which ImageJ's own reader cannot parse (1.54f
	 * turns them into nonsense, for some files taking half a minute): their
	 * description must not come from ImageJ. ImageJ's own files are classic.
	 */
	@Test
	public void bigTiffsAreNotScannedByImageJ() throws Exception {
		String big = writeStack("big.tif", 3);
		assertTrue(PRT.isBigTiff(big));
		ImagePlus imp = new PRT(big, false).getImp();
		FileInfo fi = imp.getOriginalFileInfo();
		assertEquals("big.tif", fi.fileName);
		assertEquals(tmp.getRoot().getPath(), fi.directory);
		assertEquals(3, imp.getStackSize());
		assertArrayEquals(TiffFixture.plane(W, H, 2, 0), (short[]) imp.getStack().getPixels(3));

		String classic = new File(tmp.getRoot(), "classic.tif").getPath();
		assertTrue(new FileSaver(new ImagePlus("classic", new ShortProcessor(W, H))).saveAsTiff(classic));
		assertFalse(PRT.isBigTiff(classic));
		assertFalse(PRT.isBigTiff(new File(tmp.getRoot(), "missing.tif").getPath()));
	}
}
