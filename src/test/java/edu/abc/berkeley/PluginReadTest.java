package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.Color;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.ParallelVirtualStack;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.LUT;
import ij.process.ShortProcessor;

/**
 * End-to-end regression tests for the Java glue: PRT/PRZ must turn files into
 * correctly typed, correctly calibrated ImagePlus objects with the exact
 * pixel values that were written.
 * <p>
 * The tests are skipped (not failed) when the natives are not staged; build
 * with {@code mvn test -Pnative} to run them.
 */
public class PluginReadTest {

	private static final int W = 64, H = 48, Z = 3, CHUNK = 32;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final ParallelWriteNative writer = new ParallelWriteNative();

	@BeforeClass
	public static void nativesRequired() {
		Natives.required();
	}

	private static short[][] shortSlices() {
		short[][] slices = new short[Z][W * H];
		for (int s = 0; s < Z; s++)
			for (int i = 0; i < W * H; i++)
				slices[s][i] = (short) (i * 31 + s * 7);
		return slices;
	}

	/** Overwrite the "ImageJ" marker in a tiff's description tag (same length). */
	private static void scrubImageJMarker(String path) throws RuntimeException {
		try {
			byte[] bytes = Files.readAllBytes(Paths.get(path));
			byte[] marker = "ImageJ".getBytes("US-ASCII");
			for (int i = 0; i <= bytes.length - marker.length; i++) {
				boolean match = true;
				for (int j = 0; j < marker.length; j++)
					if (bytes[i + j] != marker[j]) { match = false; break; }
				if (match) bytes[i] = 'X';
			}
			Files.write(Paths.get(path), bytes);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	public void tiffUint16ThroughPRT() {
		short[][] slices = shortSlices();
		String path = new File(tmp.getRoot(), "u16.tif").getPath();
		writer.parallelWriteTiff(path, slices, W, H, Z, 16);

		ImagePlus imp = new PRT(path, false).getImp();
		assertNotNull("PRT produced no image", imp);
		assertEquals(ImagePlus.GRAY16, imp.getType());
		assertEquals(W, imp.getWidth());
		assertEquals(H, imp.getHeight());
		assertEquals(Z, imp.getStackSize());
		for (int s = 0; s < Z; s++)
			assertArrayEquals("slice " + s, slices[s], (short[]) imp.getStack().getPixels(s + 1));
	}

	private static int[] rgbPixels() {
		int[] pixels = new int[W * H];
		for (int i = 0; i < pixels.length; i++)
			pixels[i] = 0xFF000000 | ((i * 3) % 256) << 16 | ((i * 7) % 256) << 8 | ((i * 13) % 256);
		return pixels;
	}

	private String writeRgbTiff(int[] pixels, String name) {
		ImagePlus rgb = new ImagePlus("rgb", new ColorProcessor(W, H, pixels.clone()));
		String path = new File(tmp.getRoot(), name).getPath();
		assertTrue(new FileSaver(rgb).saveAsTiff(path));
		return path;
	}

	private void assertRgbReads(String path, int[] pixels) {
		ParallelReadNative reader = new ParallelReadNative();
		assertEquals(3, reader.getTiffSamplesPerPixel(path));

		ImagePlus imp = new PRT(path, false).getImp();
		assertNotNull("PRT produced no image", imp);
		assertEquals(ImagePlus.COLOR_RGB, imp.getType());
		assertEquals(W, imp.getWidth());
		assertEquals(H, imp.getHeight());
		assertArrayEquals(pixels, (int[]) imp.getStack().getPixels(1));
	}

	/** ImageJ-written RGB: exercises cpp-tiff's ImageJ-metadata fast path. */
	@Test
	public void rgbImageJTiffThroughPRT() {
		int[] pixels = rgbPixels();
		String path = writeRgbTiff(pixels, "rgbImageJ.tif");
		assertRgbReads(path, pixels);
	}

	/** Non-ImageJ RGB (marker scrubbed): exercises the normal chunky-RGB path. */
	@Test
	public void rgbPlainTiffThroughPRT() {
		int[] pixels = rgbPixels();
		String path = writeRgbTiff(pixels, "rgbPlain.tif");
		scrubImageJMarker(path);
		assertRgbReads(path, pixels);
	}

	@Test
	public void zarrUint16ThroughPRZ() {
		short[][] slices = shortSlices();
		String path = new File(tmp.getRoot(), "u16.zarr").getPath();
		writer.parallelWriteZarr(path, slices, 0, 0, 0, H, W, Z, CHUNK, CHUNK, CHUNK, 1, "zstd", 1, 16);

		ImagePlus imp = new PRZ(path, false).getImp();
		assertNotNull("PRZ produced no image", imp);
		assertEquals(ImagePlus.GRAY16, imp.getType());
		assertEquals(W, imp.getWidth());
		assertEquals(H, imp.getHeight());
		assertEquals(Z, imp.getStackSize());
		for (int s = 0; s < Z; s++)
			assertArrayEquals("slice " + s, slices[s], (short[]) imp.getStack().getPixels(s + 1));
	}

	@Test
	public void signedInt16ZarrIsCalibratedThroughPRZ() throws Exception {
		short[][] slices = shortSlices();
		String path = new File(tmp.getRoot(), "i16.zarr").getPath();
		writer.parallelWriteZarr(path, slices, 0, 0, 0, H, W, Z, CHUNK, CHUNK, CHUNK, 1, "zstd", 1, 16);
		Path za = Paths.get(path, ".zarray");
		String json = new String(Files.readAllBytes(za), StandardCharsets.UTF_8);
		Files.write(za, json.replace("<u2", "<i2").getBytes(StandardCharsets.UTF_8));

		ImagePlus imp = new PRZ(path, false).getImp();
		assertNotNull("PRZ produced no image", imp);
		assertEquals(ImagePlus.GRAY16, imp.getType());

		// Raw pixels are shifted by 32768; the calibration maps them back so
		// measured values equal the true signed values.
		short[] raw = (short[]) imp.getStack().getPixels(1);
		assertEquals((short) (slices[0][0] ^ 0x8000), raw[0]);
		assertTrue("signed-16 calibration missing", imp.getCalibration().calibrated());
		for (int i = 0; i < 5; i++) {
			double measured = imp.getCalibration().getCValue(raw[i] & 0xFFFF);
			assertEquals("index " + i, slices[0][i], measured, 0.0);
		}
	}

	/**
	 * A z-window read through PRZ's windowed constructor: this is how the
	 * virtual stack loads one plane at a time, and it must return that plane
	 * for every z, not just the first.
	 */
	@Test
	public void zarrZWindowThroughPRZ() {
		short[][] slices = shortSlices();
		String path = new File(tmp.getRoot(), "zwin.zarr").getPath();
		writer.parallelWriteZarr(path, slices, 0, 0, 0, H, W, Z, CHUNK, CHUNK, CHUNK, 1, "zstd", 1, 16);
		for (int z = 0; z < Z; z++) {
			ImagePlus imp = new PRZ(path, 0, 0, z, H, W, z + 1, false).getImp();
			assertNotNull("window z=" + z + " produced no image", imp);
			assertEquals("window z=" + z, 1, imp.getStackSize());
			assertArrayEquals("window z=" + z, slices[z], (short[]) imp.getStack().getPixels(1));
		}
	}

	/** Exactly what Fiji does to show slice n of a zarr too large for the heap. */
	@Test
	public void zarrVirtualStackSlicesThroughParallelVirtualStack() {
		short[][] slices = shortSlices();
		String path = new File(tmp.getRoot(), "virt.zarr").getPath();
		writer.parallelWriteZarr(path, slices, 0, 0, 0, H, W, Z, CHUNK, CHUNK, CHUNK, 1, "zstd", 1, 16);
		ParallelVirtualStack stack = new ParallelVirtualStack(W, H, Z, null, path);
		stack.setBitDepth(16);
		assertEquals(Z, stack.getSize());
		for (int n = 1; n <= Z; n++) {
			ImageProcessor ip = stack.getProcessor(n);
			assertEquals("slice " + n, W, ip.getWidth());
			assertEquals("slice " + n, H, ip.getHeight());
			assertArrayEquals("slice " + n, slices[n - 1], (short[]) ip.getPixels());
		}
	}
	/**
	 * The virtual stack fetches planes in aligned blocks and keeps the current
	 * and previous block: scrolling forward, backward and jumping must all
	 * return the right planes, with only the expected block reads.
	 */
	@Test
	public void zarrVirtualStackBlockCache() {
		final int nz = 6;
		short[][] slices = new short[nz][W * H];
		for (int s = 0; s < nz; s++)
			for (int i = 0; i < W * H; i++)
				slices[s][i] = (short) (i * 13 + s * 101);
		String path = new File(tmp.getRoot(), "blocks.zarr").getPath();
		writer.parallelWriteZarr(path, slices, 0, 0, 0, H, W, nz, CHUNK, CHUNK, CHUNK, 1, "zstd", 1, 16);
		ParallelVirtualStack stack = new ParallelVirtualStack(W, H, nz, null, path);
		stack.setBitDepth(16);
		stack.setMaxBlockPlanes(2); // blocks {1,2} {3,4} {5,6}

		int[] order         = { 1, 2, 3, 4, 5, 6,   6, 5, 4, 3, 2, 1,   5 };
		int[] expectedReads = { 1, 1, 2, 2, 3, 3,   3, 3, 3, 3, 4, 4,   5 };
		for (int i = 0; i < order.length; i++) {
			int n = order[i];
			assertArrayEquals("slice " + n, slices[n - 1], (short[]) stack.getProcessor(n).getPixels());
			assertEquals("block reads after step " + i + " (slice " + n + ")", expectedReads[i], stack.getBlockReads());
		}
	}
	/** cpp-tiff 1.7 reads planar RGB (one plane per color), which used to be rejected. */
	@Test
	public void planarRgbTiffThroughPRT() throws Exception {
		int[] pixels = rgbPixels();
		String path = new File(tmp.getRoot(), "planar.tif").getPath();
		writePlanarRgbTiff(path, pixels);
		assertRgbReads(path, pixels);
	}

	/** A minimal uncompressed TIFF with R, G and B stored as three separate planes. */
	private static void writePlanarRgbTiff(String path, int[] argb) throws Exception {
		final int plane = W * H, entries = 10;
		final int bitsAt = 8 + 2 + entries * 12 + 4, offsetsAt = bitsAt + 6, countsAt = offsetsAt + 12, dataAt = countsAt + 12;
		ByteBuffer b = ByteBuffer.allocate(dataAt + 3 * plane).order(ByteOrder.LITTLE_ENDIAN);
		b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
		b.putShort((short) entries);
		tiffEntry(b, 256, 4, 1, W);          // width
		tiffEntry(b, 257, 4, 1, H);          // height
		tiffEntry(b, 258, 3, 3, bitsAt);     // 8 bits per sample
		tiffEntry(b, 259, 3, 1, 1);          // no compression
		tiffEntry(b, 262, 3, 1, 2);          // RGB
		tiffEntry(b, 273, 4, 3, offsetsAt);  // one strip per color plane
		tiffEntry(b, 277, 3, 1, 3);          // 3 samples per pixel
		tiffEntry(b, 278, 4, 1, H);          // rows per strip
		tiffEntry(b, 279, 4, 3, countsAt);
		tiffEntry(b, 284, 3, 1, 2);          // planar
		b.putInt(0);                         // no next directory
		b.putShort((short) 8).putShort((short) 8).putShort((short) 8);
		for (int c = 0; c < 3; c++) b.putInt(dataAt + c * plane);
		for (int c = 0; c < 3; c++) b.putInt(plane);
		for (int c = 0; c < 3; c++)
			for (int i = 0; i < plane; i++)
				b.put((byte) (argb[i] >> (16 - 8 * c)));
		Files.write(Paths.get(path), b.array());
	}

	private static void tiffEntry(ByteBuffer b, int tag, int type, int count, int value) {
		b.putShort((short) tag).putShort((short) type).putInt(count);
		if (type == 3 && count == 1) b.putShort((short) value).putShort((short) 0);
		else b.putInt(value);
	}

	private static void flipZarrDtype(String path, String from, String to) throws Exception {
		Path za = Paths.get(path, ".zarray");
		String json = new String(Files.readAllBytes(za), StandardCharsets.UTF_8);
		Files.write(za, json.replace(from, to).getBytes(StandardCharsets.UTF_8));
	}

	/** float16 zarrs open as 32-bit float images with their exact values. */
	@Test
	public void float16ZarrOpensAs32BitThroughPRZ() throws Exception {
		short[] half = { 0x3E00, (short) 0xB800, 0x6400 }; // 1.5, -0.5, 1024
		float[] value = { 1.5f, -0.5f, 1024f };
		short[][] slices = new short[Z][W * H];
		for (int s = 0; s < Z; s++)
			for (int i = 0; i < W * H; i++)
				slices[s][i] = half[(i + s) % half.length];
		String path = new File(tmp.getRoot(), "f16.zarr").getPath();
		writer.parallelWriteZarr(path, slices, 0, 0, 0, H, W, Z, CHUNK, CHUNK, CHUNK, 1, "zstd", 1, 16);
		flipZarrDtype(path, "<u2", "<f2");

		ImagePlus imp = new PRZ(path, false).getImp();
		assertNotNull("PRZ produced no image", imp);
		assertEquals(ImagePlus.GRAY32, imp.getType());
		for (int s = 0; s < Z; s++) {
			float[] px = (float[]) imp.getStack().getPixels(s + 1);
			for (int i = 0; i < 6; i++)
				assertEquals("slice " + s + " index " + i, value[(i + s) % value.length], px[i], 0f);
		}
	}

	/** ImageJ has no complex type: refuse with a message rather than show garbage. */
	@Test
	public void complexZarrIsRefusedWithAClearMessage() throws Exception {
		String path = new File(tmp.getRoot(), "c8.zarr").getPath();
		writer.parallelWriteZarr(path, new float[Z][W * H], 0, 0, 0, H, W, Z, CHUNK, CHUNK, CHUNK, 1, "zstd", 1, 32);
		flipZarrDtype(path, "<f4", "<c8");
		try {
			new PRZ(path, false);
			fail("a complex zarr was opened");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("complex"));
		}
	}

	/** bool zarrs open as 8-bit images of 0 and 1. */
	@Test
	public void boolZarrOpensAsZeroAndOneThroughPRZ() throws Exception {
		byte[][] slices = new byte[Z][W * H];
		for (int s = 0; s < Z; s++)
			for (int i = 0; i < W * H; i++)
				slices[s][i] = (byte) ((i + s) % 3 == 0 ? 1 : 0);
		String path = new File(tmp.getRoot(), "bool.zarr").getPath();
		writer.parallelWriteZarr(path, slices, 0, 0, 0, H, W, Z, CHUNK, CHUNK, CHUNK, 1, "zstd", 1, 8);
		flipZarrDtype(path, "|u1", "|b1");

		ImagePlus imp = new PRZ(path, false).getImp();
		assertNotNull("PRZ produced no image", imp);
		assertEquals(ImagePlus.GRAY8, imp.getType());
		for (int s = 0; s < Z; s++)
			assertArrayEquals("slice " + s, slices[s], (byte[]) imp.getStack().getPixels(s + 1));
	}

	/**
	 * ImageJ's multichannel hyperstack TIFFs open exactly as ImageJ opens them:
	 * channels, slices and frames, every pixel, the composite mode, each
	 * channel's LUT and display range, and the calibration. (Each bit depth is
	 * saved in another display mode.)
	 */
	@Test
	public void imageJHyperstackTiffOpensAsImageJOpensIt() throws Exception {
		int[] modes = { IJ.COMPOSITE, IJ.COLOR, IJ.GRAYSCALE };
		for (int bits : new int[] { 8, 16, 32 }) {
			final int c = 3, z = 2, t = 2;
			ImageStack stack = new ImageStack(W, H);
			for (int n = 0; n < c * z * t; n++) {
				ImageProcessor ip = bits == 32 ? new FloatProcessor(W, H) : bits == 16 ? new ShortProcessor(W, H)
					: new ij.process.ByteProcessor(W, H);
				for (int i = 0; i < W * H; i++) ip.setf(i, (i * 7 + n * 13) % 250);
				stack.addSlice("plane " + n, ip);
			}
			ImagePlus src = new ImagePlus("hyper", stack);
			src.setDimensions(c, z, t);
			CompositeImage comp = new CompositeImage(src, modes[bits / 16]);
			Color[] colors = { Color.magenta, Color.cyan, Color.yellow };
			for (int ch = 0; ch < c; ch++) {
				LUT lut = LUT.createLutFromColor(colors[ch]);
				lut.min = 10 * (ch + 1);
				lut.max = 100 + 50 * ch;
				comp.setChannelLut(lut, ch + 1);
			}
			Calibration cal = comp.getCalibration();
			cal.pixelWidth = 0.104;
			cal.pixelHeight = 0.104;
			cal.pixelDepth = 0.25;
			cal.frameInterval = 3.5;
			cal.setUnit("micron");
			String path = new File(tmp.getRoot(), "hyper" + bits + ".tif").getPath();
			assertTrue(new FileSaver(comp).saveAsTiff(path));

			ImagePlus ij = IJ.openImage(path), ours = new PRT(path, false).getImp();
			assertNotNull(bits + "-bit: PRT produced no image", ours);
			String what = bits + "-bit: ";
			assertEquals(what + "channels", c, ours.getNChannels());
			assertEquals(what + "slices", z, ours.getNSlices());
			assertEquals(what + "frames", t, ours.getNFrames());
			assertEquals(what + "bit depth", ij.getBitDepth(), ours.getBitDepth());
			assertTrue(what + "not a composite", ours.isComposite());
			assertEquals(what + "mode", ((CompositeImage) ij).getMode(), ((CompositeImage) ours).getMode());
			for (int ch = 1; ch <= c; ch++) {
				LUT a = ((CompositeImage) ij).getChannelLut(ch), b = ((CompositeImage) ours).getChannelLut(ch);
				assertEquals(what + "channel " + ch + " color", a.getRGB(255), b.getRGB(255));
				assertEquals(what + "channel " + ch + " display min", a.min, b.min, 0);
				assertEquals(what + "channel " + ch + " display max", a.max, b.max, 0);
			}
			// (TIFF stores the pixel size as a fraction: 0.104 reads back as 0.1040000067)
			Calibration theirs = ij.getCalibration(), mine = ours.getCalibration();
			assertEquals(what + "pixel width", 0.104, mine.pixelWidth, 1e-6);
			assertEquals(what + "pixel width", theirs.pixelWidth, mine.pixelWidth, 0);
			assertEquals(what + "pixel height", theirs.pixelHeight, mine.pixelHeight, 0);
			assertEquals(what + "pixel depth", 0.25, mine.pixelDepth, 0);
			assertEquals(what + "frame interval", 3.5, mine.frameInterval, 0);
			assertEquals(what + "unit", "micron", mine.getUnit());
			for (int n = 1; n <= c * z * t; n++) {
				assertEquals(what + "label " + n, ij.getStack().getSliceLabel(n), ours.getStack().getSliceLabel(n));
				Object a = ij.getStack().getPixels(n), b = ours.getStack().getPixels(n);
				if (bits == 8) assertArrayEquals(what + "plane " + n, (byte[]) a, (byte[]) b);
				else if (bits == 16) assertArrayEquals(what + "plane " + n, (short[]) a, (short[]) b);
				else assertArrayEquals(what + "plane " + n, (float[]) a, (float[]) b, 0f);
			}
		}
	}
}
