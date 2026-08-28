package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.ImagePlus;
import ij.io.FileSaver;
import ij.process.ColorProcessor;

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
		try {
			new ParallelReadNative();
		}
		catch (LinkageError e) {
			Assume.assumeNoException("Natives are not staged; run mvn test -Pnative", e);
		}
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
			byte[] bytes = Files.readAllBytes(Path.of(path));
			byte[] marker = "ImageJ".getBytes("US-ASCII");
			for (int i = 0; i <= bytes.length - marker.length; i++) {
				boolean match = true;
				for (int j = 0; j < marker.length; j++)
					if (bytes[i + j] != marker[j]) { match = false; break; }
				if (match) bytes[i] = 'X';
			}
			Files.write(Path.of(path), bytes);
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
		Path za = Path.of(path, ".zarray");
		Files.writeString(za, Files.readString(za).replace("<u2", "<i2"));

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
}
