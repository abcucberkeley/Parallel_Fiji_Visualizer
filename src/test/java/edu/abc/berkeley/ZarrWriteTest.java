package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

/**
 * Saving as Zarr: stacks as (y, x, z) and hyperstacks as (y, x, z, c, t)
 * arrays in nested chunk folders, which open again exactly as they were.
 */
public class ZarrWriteTest {

	private static final int W = 40, H = 30;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@BeforeClass
	public static void nativesRequired() {
		Natives.required();
	}

	/** A stack of c * z * t planes in ImageJ's czt order, every pixel distinct. */
	private static ImagePlus image(int bits, int c, int z, int t) {
		ImageStack stack = new ImageStack(W, H);
		for (int n = 0; n < c * z * t; n++) {
			ImageProcessor ip = bits == 8 ? new ByteProcessor(W, H) : bits == 16 ? new ShortProcessor(W, H) : new FloatProcessor(W, H);
			for (int i = 0; i < W * H; i++) ip.setf(i, bits == 8 ? (i + n * 7) % 256 : bits == 16 ? n * 1500 + i : n * 1500 + i + 0.25f);
			stack.addSlice(ip);
		}
		ImagePlus imp = new ImagePlus("image", stack);
		imp.setDimensions(c, z, t);
		return imp;
	}

	private static void assertSamePixels(ImagePlus expected, ImagePlus actual) {
		assertEquals(expected.getWidth(), actual.getWidth());
		assertEquals(expected.getHeight(), actual.getHeight());
		assertEquals(expected.getNChannels(), actual.getNChannels());
		assertEquals(expected.getNSlices(), actual.getNSlices());
		assertEquals(expected.getNFrames(), actual.getNFrames());
		assertEquals(expected.getBitDepth(), actual.getBitDepth());
		for (int n = 1; n <= expected.getStackSize(); n++) {
			Object a = expected.getStack().getPixels(n), b = actual.getStack().getPixels(n);
			if (a instanceof byte[]) assertArrayEquals("plane " + n, (byte[]) a, (byte[]) b);
			else if (a instanceof short[]) assertArrayEquals("plane " + n, (short[]) a, (short[]) b);
			else assertArrayEquals("plane " + n, (float[]) a, (float[]) b, 0f);
		}
	}

	private static Map<String, Object> json(File f) throws Exception {
		return Json.object(Json.parse(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8")));
	}

	@Test
	public void hyperstackSavesAsFiveDAndOpensTheSame() throws Exception {
		ImagePlus imp = image(16, 2, 3, 2);
		File dir = new File(tmp.getRoot(), "hyper.zarr");
		PWZ.write(imp, dir);

		Map<String, Object> zarray = json(new File(dir, ".zarray"));
		assertArrayEquals(new long[] { H, W, 3, 2, 2 }, Json.longs(zarray.get("shape")));
		assertArrayEquals("chunks no bigger than the image", new long[] { H, W, 3, 1, 1 }, Json.longs(zarray.get("chunks")));
		assertEquals("/", zarray.get("dimension_separator"));
		assertEquals("F", zarray.get("order"));
		assertEquals(Json.list(Json.parse("[\"y\", \"x\", \"z\", \"c\", \"t\"]")),
			Json.list(json(new File(dir, ".zattrs")).get("_ARRAY_DIMENSIONS")));
		assertTrue("nested chunk folders", new File(dir, "0/0/0/1/1").isFile());

		assertSamePixels(imp, new PRZ(dir.getPath(), false).getImp());
	}

	@Test
	public void framesWithoutChannelsStayFrames() throws Exception {
		ImagePlus imp = image(32, 1, 2, 3);
		File dir = new File(tmp.getRoot(), "frames.zarr");
		PWZ.write(imp, dir);
		assertSamePixels(imp, new PRZ(dir.getPath(), false).getImp());
	}

	@Test
	public void stacksSaveAsThreeD() throws Exception {
		for (int bits : new int[] { 8, 16, 32 }) {
			ImagePlus imp = image(bits, 1, 4, 1);
			File dir = new File(tmp.getRoot(), "stack" + bits + ".zarr");
			PWZ.write(imp, dir);
			assertArrayEquals(new long[] { H, W, 4 }, Json.longs(json(new File(dir, ".zarray")).get("shape")));
			assertSamePixels(imp, new PRZ(dir.getPath(), false).getImp());
		}
	}
}
