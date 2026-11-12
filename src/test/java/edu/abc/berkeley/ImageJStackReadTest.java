package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import edu.abc.berkeley.TiffFixture.Layout;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.ParallelVirtualStack;
import ij.io.FileSaver;
import ij.process.ShortProcessor;

/**
 * ImageJ stacks stored as one block (one directory, then every slice: how
 * ImageJ saves stacks over 4 GB), which the natives read with every thread
 * themselves: straight into the planes when the planes take the samples as
 * stored, else through a reused buffer of whole slices and the conversions.
 * Every pixel type, sample count and byte order is compared plane by plane
 * with the values written, with cpp-tiff's read of the same pixels stored a
 * directory per slice, and, where ImageJ reads the file, with ImageJ.
 */
public class ImageJStackReadTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@BeforeClass
	public static void nativesRequired() {
		Natives.required();
	}

	@After
	public void defaultSlabs() {
		ParallelReadNative.setTiffSlabBytes(0);
	}

	/**
	 * Every type the plugin reads: bits, sample format, samples per pixel, and
	 * 1 if ImageJ reads it as the plugin shows it (1.54f reads 32-bit unsigned
	 * integers as signed ones and signed bytes as unsigned, and does not open
	 * 64-bit floats stored this way).
	 */
	private static final int[][] TYPES = {
		{ 8, 1, 1, 1 }, { 8, 2, 1, 0 }, { 16, 1, 1, 1 }, { 16, 2, 1, 1 }, { 32, 3, 1, 1 }, { 32, 2, 1, 1 }, { 32, 1, 1, 0 },
		{ 64, 3, 1, 0 }, { 64, 2, 1, 0 }, { 64, 1, 1, 0 }, { 8, 1, 3, 1 }, { 8, 1, 4, 0 }, { 16, 1, 2, 0 }, { 16, 1, 3, 0 },
		{ 32, 3, 3, 0 }
	};

	private static final ByteOrder[] ORDERS = { ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN };

	private int files;

	private String write(Layout l) throws IOException {
		return TiffFixture.write(new File(tmp.getRoot(), "stack" + files++ + ".tif"), l).getPath();
	}

	private static Object[] readAll(String path) {
		TiffImage t = TiffImage.open(path);
		return t.read(0, t.planes());
	}

	private static void assertPlanesEqual(String what, Object[] expected, Object[] actual) {
		assertEquals(what + ": planes", expected.length, actual.length);
		for (int n = 0; n < expected.length; n++) assertPlaneEquals(what + ", plane " + n, expected[n], actual[n]);
	}

	private static void assertPlaneEquals(String what, Object expected, Object actual) {
		assertNotNull(what + ": no plane", actual);
		assertEquals(what + ": type", expected.getClass(), actual.getClass());
		if (expected instanceof byte[]) assertArrayEquals(what, (byte[]) expected, (byte[]) actual);
		else if (expected instanceof short[]) assertArrayEquals(what, (short[]) expected, (short[]) actual);
		else if (expected instanceof int[]) assertArrayEquals(what, (int[]) expected, (int[]) actual);
		// Bit for bit: NaN and -0 as well
		else {
			float[] e = (float[]) expected, a = (float[]) actual;
			assertEquals(what + ": length", e.length, a.length);
			for (int i = 0; i < e.length; i++)
				if (Float.floatToRawIntBits(e[i]) != Float.floatToRawIntBits(a[i])) fail(what + ": pixel " + i + " is " + a[i] + ", not " + e[i]);
		}
	}

	private static Object[] expected(Layout l) {
		Object[] planes = new Object[l.planes()];
		for (int n = 0; n < planes.length; n++) planes[n] = TiffFixture.plane(l, n);
		return planes;
	}

	private static Layout layout(int[] type) {
		return new Layout(type[0], type[1], type[2]);
	}

	/** Every type in both byte orders, against the values written, cpp-tiff and ImageJ. */
	@Test
	public void everyTypeInBothByteOrders() throws Exception {
		for (int[] type : TYPES) {
			// The same pixels a directory per slice: what cpp-tiff makes of them
			Layout plain = layout(type).oneBlock(false);
			String cppTiff = write(plain);
			assertEquals(plain + ": read by cpp-tiff", 0, new ParallelReadNative().getTiffInfo(cppTiff)[ParallelReadNative.TIFF_IMAGEJ]);
			Object[] fromCppTiff = readAll(cppTiff);
			assertPlanesEqual(plain + ", read by cpp-tiff", expected(plain), fromCppTiff);
			for (ByteOrder order : ORDERS) {
				Layout l = layout(type).order(order);
				String path = write(l);
				long[] info = new ParallelReadNative().getTiffInfo(path);
				assertEquals(l + ": an ImageJ stack", 1, info[ParallelReadNative.TIFF_IMAGEJ]);
				assertEquals(l + ": slices", l.slices, info[ParallelReadNative.TIFF_SLICES]);
				Object[] planes = readAll(path);
				assertPlanesEqual(l.toString(), expected(l), planes);
				assertPlanesEqual(l + " against cpp-tiff", fromCppTiff, planes);
				if (type[3] == 1) {
					ImagePlus ij = IJ.openImage(path);
					assertNotNull(l + ": ImageJ cannot open it", ij);
					assertEquals(l + ": ImageJ's planes", planes.length, ij.getStackSize());
					for (int n = 0; n < planes.length; n++)
						assertPlaneEquals(l + " against ImageJ, plane " + n, ij.getStack().getPixels(n + 1), planes[n]);
				}
			}
		}
	}

	/** The conversions emptying a buffer of a few slices at a time, the last one shorter. */
	@Test
	public void slabsOfAnySize() throws Exception {
		for (int[] type : TYPES) {
			Layout l = layout(type).size(9, 5, 7).order(ByteOrder.BIG_ENDIAN);
			String path = write(l);
			Object[] all = expected(l);
			for (long slab : new long[] { 1, l.sliceBytes(), 2 * l.sliceBytes() + 1, 3 * l.sliceBytes(), 100 * l.sliceBytes() }) {
				ParallelReadNative.setTiffSlabBytes(slab);
				assertPlanesEqual(l + ", slabs of " + slab + " bytes", all, readAll(path));
			}
		}
	}

	/** Every range of planes, also ones that start or end inside a slice's samples. */
	@Test
	public void planeRanges() throws Exception {
		for (int[] type : new int[][] { { 16, 1, 1 }, { 16, 2, 1 }, { 16, 1, 3 }, { 8, 1, 3 }, { 32, 3, 1 } }) {
			for (ByteOrder order : ORDERS) {
				Layout l = new Layout(type[0], type[1], type[2]).size(11, 6, 5).order(order);
				TiffImage tiff = TiffImage.open(write(l));
				Object[] all = expected(l);
				ParallelReadNative.setTiffSlabBytes(2 * l.sliceBytes());
				for (int first = 0; first < all.length; first++)
					for (int last = first + 1; last <= all.length; last++)
						assertPlanesEqual(l + ", planes " + first + "-" + last, Arrays.copyOfRange(all, first, last), tiff.read(first, last));
				ParallelReadNative.setTiffSlabBytes(0);
				for (int block : new int[] { 1, 2, 3 }) {
					ParallelVirtualStack stack = new ParallelVirtualStack(tiff);
					stack.setMaxBlockPlanes(block);
					assertEquals(l + ": virtual planes", all.length, stack.getSize());
					for (int n = all.length; n >= 1; n--)
						assertPlaneEquals(l + ", virtual plane " + n + " in blocks of " + block, all[n - 1], stack.getProcessor(n).getPixels());
				}
			}
		}
	}

	/** The first directory as other programs may write it: BigTIFF, several strips, offsets of each type, inline or not. */
	@Test
	public void directoryLayouts() throws Exception {
		for (ByteOrder order : ORDERS) {
			for (boolean big : new boolean[] { false, true }) {
				for (int offsetType : big ? new int[] { 3, 4, 16 } : new int[] { 3, 4 }) {
					for (int rows : new int[] { 0, 1, 2, 3 }) {
						for (int[] type : new int[][] { { 16, 1, 1 }, { 16, 1, 3 } }) {
							Layout l = new Layout(type[0], type[1], type[2]).order(order).bigTiff(big, offsetType).strips(rows, offsetType);
							String path = write(l);
							assertEquals(l + ": an ImageJ stack", 1, new ParallelReadNative().getTiffInfo(path)[ParallelReadNative.TIFF_IMAGEJ]);
							assertPlanesEqual(l.toString(), expected(l), readAll(path));
						}
					}
				}
			}
		}
	}

	/** Planes of several pieces each, read by several threads, the last piece of each plane short. */
	@Test
	public void largePlanesReadBySeveralThreads() throws Exception {
		for (int[] type : new int[][] { { 16, 1, 1 }, { 32, 3, 1 }, { 16, 2, 1 }, { 64, 3, 1 } }) {
			for (ByteOrder order : ORDERS) {
				Layout l = new Layout(type[0], type[1], type[2]).size(1001, 700, 3).order(order);
				String path = write(l);
				assertPlanesEqual(l.toString(), expected(l), readAll(path));
				ParallelReadNative.setTiffSlabBytes(l.sliceBytes() / 3);
				assertPlanesEqual(l + ", a slice per slab", expected(l), readAll(path));
				ParallelReadNative.setTiffSlabBytes(0);
			}
		}
	}

	/**
	 * More planes than the natives pin at once (4096), in both kinds of reads;
	 * and so many small planes that several threads' stretches of the read each
	 * take several batches.
	 */
	@Test
	public void manyPlanes() throws Exception {
		for (int[] type : new int[][] { { 16, 1, 1 }, { 16, 2, 1 }, { 8, 1, 3 } }) {
			Layout l = new Layout(type[0], type[1], type[2]).size(4, 3, 4500).order(ByteOrder.BIG_ENDIAN);
			assertPlanesEqual(l.toString(), expected(l), readAll(write(l)));
		}
		Layout l = new Layout(16, 1, 1).size(4, 4, 60000).order(ByteOrder.BIG_ENDIAN);
		assertPlanesEqual(l.toString(), expected(l), readAll(write(l)));
	}

	/** Planes the caller does not give are allocated; planes that do not fit the image are refused. */
	@Test
	public void destinationPlanes() throws Exception {
		ParallelReadNative r = new ParallelReadNative();
		Layout gray = new Layout(16, 1, 1).order(ByteOrder.BIG_ENDIAN), signed = new Layout(16, 2, 1), rgb = new Layout(8, 1, 3);
		String grayPath = write(gray), signedPath = write(signed), rgbPath = write(rgb);
		long[] grayInfo = r.getTiffInfo(grayPath), signedInfo = r.getTiffInfo(signedPath), rgbInfo = r.getTiffInfo(rgbPath);
		assertPlanesEqual("gray into new planes", expected(gray), r.parallelReadTiffUINT16(grayPath, grayInfo, 0, gray.slices, null));
		assertPlanesEqual("signed into new planes", expected(signed), r.parallelReadTiffINT16(signedPath, signedInfo, 0, signed.slices, null));
		assertPlanesEqual("color into new planes", expected(rgb), r.parallelReadTiffRGB8(rgbPath, rgbInfo, 0, rgb.slices, null));
		final int px = gray.width * gray.height;
		short[][][] wrong = { new short[gray.slices - 1][px], new short[gray.slices][px - 1] };
		for (short[][] dest : wrong) {
			try {
				r.parallelReadTiffUINT16(grayPath, grayInfo, 0, gray.slices, dest);
				fail("read into planes that do not fit");
			}
			catch (RuntimeException e) {
				assertTrue(e.getMessage(), e.getMessage().contains("Destination"));
			}
			try {
				r.parallelReadTiffINT16(signedPath, signedInfo, 0, signed.slices, dest);
				fail("read into planes that do not fit");
			}
			catch (RuntimeException e) {
				assertTrue(e.getMessage(), e.getMessage().contains("Destination"));
			}
		}
	}

	/** A file cut short (an interrupted copy): the slices it still holds read, the others fail, and no image opens. */
	@Test
	public void fileCutShort() throws Exception {
		for (int[] type : new int[][] { { 16, 1, 1 }, { 16, 2, 1 }, { 8, 1, 3 } }) {
			for (ByteOrder order : ORDERS) {
				Layout l = new Layout(type[0], type[1], type[2]).size(13, 7, 5).order(order);
				File f = new File(write(l));
				try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
					raf.setLength(f.length() - 2 * l.sliceBytes() - 3);
				}
				TiffImage tiff = TiffImage.open(f.getPath());
				assertEquals(l + ": slices", 5, tiff.slices);
				final int perSlice = l.planes() / l.slices;
				Object[] all = expected(l);
				assertPlanesEqual(l + ", the slices left", Arrays.copyOfRange(all, 0, 2 * perSlice), tiff.read(0, 2 * perSlice));
				for (long[] range : new long[][] { { 0, l.planes() }, { 2 * perSlice, 3 * perSlice }, { 4 * perSlice, 5 * perSlice } }) {
					try {
						tiff.read(range[0], range[1]);
						fail(l + ": read planes " + range[0] + "-" + range[1] + " of a file cut short");
					}
					catch (RuntimeException e) {
						assertTrue(e.getMessage(), e.getMessage().contains("damaged"));
					}
				}
				try {
					new PRT(f.getPath(), false);
					fail(l + ": opened a file cut short");
				}
				catch (RuntimeException e) {
					assertTrue(e.getMessage(), e.getMessage().contains("damaged"));
				}
			}
		}
	}

	/**
	 * Paths with a space read; a path with a character outside ASCII as well
	 * where the JVM names files in UTF-8, and on Windows where the character
	 * is in the ANSI code page (which the natives pass paths in there), and
	 * anywhere just as cpp-tiff reads such a path. PathCharactersTest has
	 * the characters outside any code page.
	 */
	@Test
	public void pathsWithSpacesAndOtherCharacters() throws Exception {
		String encoding = System.getProperty("sun.jnu.encoding", System.getProperty("file.encoding"));
		for (String name : new String[] { "with space", "with space \u00e9\u00b5" }) {
			// A JVM whose file names are ASCII (the C locale) cannot even make this path
			if (!Charset.forName(encoding).newEncoder().canEncode(name)) continue;
			File dir = new File(tmp.getRoot(), name);
			assertTrue(dir.mkdir());
			Layout l = new Layout(16, 1, 1).order(ByteOrder.BIG_ENDIAN), plain = new Layout(16, 1, 1).oneBlock(false);
			String block = TiffFixture.write(new File(dir, "block " + name + ".tif"), l).getPath();
			String cppTiff = TiffFixture.write(new File(dir, "plain " + name + ".tif"), plain).getPath();
			Object[] fromCppTiff = readOrNull(cppTiff), planes = readOrNull(block);
			String what = "\"" + name + "\" (file names in " + encoding + ")";
			boolean ascii = name.chars().allMatch(c -> c < 128);
			if (ascii || namesReachNatives(encoding)) assertNotNull(what + ": cpp-tiff cannot read it", fromCppTiff);
			assertEquals(what + ": read as cpp-tiff reads it", fromCppTiff != null, planes != null);
			if (planes != null) {
				assertPlanesEqual(what, expected(l), planes);
				assertPlanesEqual(what + " against cpp-tiff", fromCppTiff, planes);
			}
		}
	}

	/**
	 * Whether the natives open every file Java names in encoding: the natives
	 * pass paths in UTF-8, and on Windows in the ANSI code page, which is the
	 * encoding Java names files in there (see nativePath in jni_marshal.h).
	 */
	private static boolean namesReachNatives(String encoding) {
		return System.getProperty("os.name").startsWith("Windows") || Charset.forName(encoding).equals(StandardCharsets.UTF_8);
	}

	private static Object[] readOrNull(String path) {
		try {
			return readAll(path);
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	/** An ImageJ stack of a directory per slice (how ImageJ saves stacks under 4 GB) is cpp-tiff's to read, as before. */
	@Test
	public void imageJStackOfManyDirectories() throws Exception {
		final int w = 10, h = 6, z = 4;
		ImageStack stack = new ImageStack(w, h);
		for (int i = 0; i < z; i++) {
			short[] p = new short[w * h];
			for (int k = 0; k < p.length; k++) p[k] = (short) (i * 4000 + k * 37);
			stack.addSlice(null, new ShortProcessor(w, h, p, null));
		}
		String path = new File(tmp.getRoot(), "ij_dirs.tif").getPath();
		assertTrue(new FileSaver(new ImagePlus("ij_dirs", stack)).saveAsTiffStack(path));
		long[] info = new ParallelReadNative().getTiffInfo(path);
		assertEquals(0, info[ParallelReadNative.TIFF_IMAGEJ]);
		assertEquals(z, info[ParallelReadNative.TIFF_DIRECTORIES]);
		Object[] planes = readAll(path);
		assertEquals(z, planes.length);
		for (int i = 0; i < z; i++) assertPlaneEquals("slice " + i, stack.getPixels(i + 1), planes[i]);
	}

	/** The plugin's own open of a big-endian stack: the image ImageJ shows. */
	@Test
	public void opensAsImageJDoes() throws Exception {
		Layout l = new Layout(16, 2, 1).size(17, 9, 6).order(ByteOrder.BIG_ENDIAN);
		String path = write(l);
		ImagePlus ours = new PRT(path, false).getImp(), ij = IJ.openImage(path);
		assertEquals(ij.getStackSize(), ours.getStackSize());
		assertEquals(ij.getBitDepth(), ours.getBitDepth());
		assertEquals(ij.getCalibration().getCValue(0), ours.getCalibration().getCValue(0), 0);
		for (int n = 1; n <= ij.getStackSize(); n++)
			assertPlaneEquals("plane " + n, ij.getStack().getPixels(n), ours.getStack().getPixels(n));
	}
}
