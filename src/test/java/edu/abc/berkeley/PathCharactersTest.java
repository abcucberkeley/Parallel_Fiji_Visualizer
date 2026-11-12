package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import edu.abc.berkeley.TiffFixture.Layout;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;

/**
 * Files in folders whose names are not ASCII: accented Latin letters (in
 * Windows' ANSI code page in Western Europe and the Americas), CJK (in no
 * such code page there) and an emoji (outside the BMP: two UTF-16 units, and
 * in JNI's modified UTF-8 two 3-byte halves that name no file). Each folder
 * holds a TIFF stack cpp-tiff reads, an ImageJ stack of one block (read by
 * the natives' own reader through RawFile), and Zarr v2 and v3 arrays, all
 * read through PRT and PRZ and compared pixel by pixel; saves into the folder
 * are read back.
 *
 * On Linux and macOS the natives pass the paths in UTF-8 and everything
 * reads and saves. On Windows cpp-tiff and cpp-zarr 1.7.3 take paths in the
 * ANSI code page: a path that is all in it must work; one that is not reads
 * through its 8.3 short name (the test is skipped where the volume keeps
 * none), and a save to a file whose own name is not in it gives an error
 * that names the file and says what to do.
 */
@RunWith(Parameterized.class)
public class PathCharactersTest {

	@Parameters(name = "{0}")
	public static Collection<Object[]> names() {
		return Arrays.asList(new Object[][] { { "café µm" }, { "日本" }, { "🔬 scope" } });
	}

	/** The folder's name. */
	@Parameter
	public String name;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@BeforeClass
	public static void nativesRequired() {
		Natives.required();
	}

	private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

	/** The encoding Java names files in: on Windows the ANSI code page, which is what the natives' narrow calls read there. */
	private static final Charset NAMES = Charset.forName(System.getProperty("sun.jnu.encoding", System.getProperty("file.encoding")));

	private static final int W = 21, H = 11, PLANES = 5;

	/**
	 * The folder of this name. Elsewhere than on Windows Java names files in
	 * sun.jnu.encoding, and the natives' UTF-8 names the same file only when
	 * that is UTF-8 (in the C locale, Java cannot even make these names).
	 */
	private File folder() throws IOException {
		assumeTrue("Java names files in " + NAMES + ", not UTF-8", WINDOWS || NAMES.equals(StandardCharsets.UTF_8));
		File dir = new File(tmp.getRoot(), name);
		assertTrue("cannot make " + dir, dir.mkdir());
		return dir;
	}

	/** Whether Windows' narrow calls take path as it is (each of its characters in the ANSI code page). */
	private static boolean inAnsi(String path) {
		return NAMES.newEncoder().canEncode(path);
	}

	/**
	 * On Windows, skips the test unless the natives can pass f's path on: in
	 * the ANSI code page, or else as its 8.3 short form, if the volume keeps
	 * short names (some have them off).
	 */
	private static void assumeReachable(File f) {
		if (!WINDOWS || inAnsi(f.getPath())) return;
		String s = ParallelReadNative.shortPathName(f.getPath());
		assumeTrue("the volume keeps no 8.3 short name for " + f + " (" + s + ")", s != null && inAnsi(s));
	}

	private static void assertPlanes(String what, Object[] expected, ImagePlus imp) {
		assertNotNull(what + ": nothing read", imp);
		assertEquals(what + ": planes", expected.length, imp.getStackSize());
		for (int n = 0; n < expected.length; n++) {
			Object plane = imp.getStack().getPixels(n + 1);
			if (expected[n] instanceof short[]) assertArrayEquals(what + ", plane " + n, (short[]) expected[n], (short[]) plane);
			else fail(what + ": unexpected plane type " + expected[n].getClass());
		}
	}

	private static Object[] expected(Layout l) {
		Object[] planes = new Object[l.planes()];
		for (int n = 0; n < planes.length; n++) planes[n] = TiffFixture.plane(l, n);
		return planes;
	}

	/** A stack of distinct 16-bit planes. */
	private static ImagePlus stack() {
		ImageStack stack = new ImageStack(W, H);
		for (int z = 0; z < PLANES; z++) {
			short[] p = new short[W * H];
			for (int i = 0; i < p.length; i++) p[i] = (short) (z * 1009 + i * 3 + 1);
			stack.addSlice(new ShortProcessor(W, H, p, null));
		}
		return new ImagePlus("saved", stack);
	}

	private static Object[] planesOf(ImagePlus imp) {
		Object[] planes = new Object[imp.getStackSize()];
		for (int n = 0; n < planes.length; n++) planes[n] = imp.getStack().getPixels(n + 1);
		return planes;
	}

	/** A stack of a directory per slice, which cpp-tiff reads. */
	@Test
	public void cppTiffStackReads() throws Exception {
		Layout l = new Layout(16, 1, 1).size(W, H, PLANES).oneBlock(false);
		File f = TiffFixture.write(new File(folder(), name + " stack.tif"), l);
		assumeReachable(f);
		assertEquals("read by cpp-tiff", 0, new ParallelReadNative().getTiffInfo(f.getPath())[ParallelReadNative.TIFF_IMAGEJ]);
		assertPlanes(f.getPath(), expected(l), new PRT(f.getPath(), false).getImp());
	}

	/** An ImageJ stack of one block, which the natives read themselves (raw_file.h). */
	@Test
	public void imageJStackReads() throws Exception {
		Layout l = new Layout(16, 1, 1).size(W, H, PLANES).order(ByteOrder.BIG_ENDIAN);
		File f = TiffFixture.write(new File(folder(), name + " imagej.tif"), l);
		assumeReachable(f);
		assertEquals("read as an ImageJ stack", 1, new ParallelReadNative().getTiffInfo(f.getPath())[ParallelReadNative.TIFF_IMAGEJ]);
		assertPlanes(f.getPath(), expected(l), new PRT(f.getPath(), false).getImp());
	}

	/** MATLAB's (y, x, z) in F order, in chunks that do not divide it. */
	@Test
	public void zarrV2Reads() throws Exception {
		long[] shape = { H, W, PLANES };
		File dir = ZarrFixture.writeV2(new File(folder(), name + " v2.zarr"), shape, new long[] { 4, 8, 2 }, 'F',
			"{\"_ARRAY_DIMENSIONS\": [\"y\", \"x\", \"z\"]}");
		assumeReachable(dir);
		Object[] expected = ZarrFixture.planes(shape, ZarrAxisOrder.roles("yxz"), new long[3], shape);
		assertPlanes(dir.getPath(), expected, new PRZ(dir.getPath(), false).getImp());
	}

	/** NumPy's (z, y, x) in C order, Zarr v3. */
	@Test
	public void zarrV3Reads() throws Exception {
		long[] shape = { PLANES, H, W };
		File dir = ZarrFixture.writeV3(new File(folder(), name + " v3.zarr"), shape, new long[] { 2, 4, 8 }, 'C',
			new String[] { "z", "y", "x" });
		assumeReachable(dir);
		Object[] expected = ZarrFixture.planes(shape, ZarrAxisOrder.roles("zyx"), new long[3], shape);
		assertPlanes(dir.getPath(), expected, new PRZ(dir.getPath(), false).getImp());
	}

	/** Saves of files with ASCII names into the folder (on Windows through its short name, when not in the ANSI code page). */
	@Test
	public void savesIntoTheFolder() throws Exception {
		File dir = folder();
		assumeReachable(dir);
		ImagePlus imp = stack();
		File tif = new File(dir, "saved.tif"), zarr = new File(dir, "saved.zarr");
		PWT.write(imp, tif.getPath());
		assertPlanes(tif.getPath(), planesOf(imp), new PRT(tif.getPath(), false).getImp());
		PWZ.write(imp, zarr);
		assertPlanes(zarr.getPath(), planesOf(imp), new PRZ(zarr.getPath(), false).getImp());
		assertEquals("files left beside the saves", Arrays.asList("saved.tif", "saved.zarr"), sorted(dir.list()));
	}

	/**
	 * Saves of files named like the folder: they work wherever the name
	 * reaches the writers, and on Windows, where it is not in the ANSI code
	 * page (no file that does not exist yet has a short name), fail with an
	 * error that names the file the user chose and says what to do, leaving
	 * nothing behind.
	 */
	@Test
	public void savesNamedLikeTheFolder() throws Exception {
		File dir = folder();
		ImagePlus imp = stack();
		File tif = new File(dir, name + ".tif"), zarr = new File(dir, name + ".zarr");
		if (!WINDOWS || inAnsi(tif.getPath())) {
			assumeReachable(dir);
			PWT.write(imp, tif.getPath());
			assertPlanes(tif.getPath(), planesOf(imp), new PRT(tif.getPath(), false).getImp());
			PWZ.write(imp, zarr);
			assertPlanes(zarr.getPath(), planesOf(imp), new PRZ(zarr.getPath(), false).getImp());
			return;
		}
		try {
			PWT.write(imp, tif.getPath());
			fail("saved " + tif + ", whose name Windows cannot pass to cpp-tiff");
		}
		catch (RuntimeException e) {
			assertNamesAndAdvises(tif, e);
		}
		try {
			PWZ.write(imp, zarr);
			fail("saved " + zarr + ", whose name Windows cannot pass to cpp-zarr");
		}
		catch (RuntimeException e) {
			assertNamesAndAdvises(zarr, e);
		}
		assertEquals("files left behind", Arrays.asList(), sorted(dir.list()));
	}

	private static void assertNamesAndAdvises(File f, RuntimeException e) {
		String msg = String.valueOf(e.getMessage());
		assertTrue("the message names " + f + ": " + msg, msg.contains(f.getPath()));
		assertTrue("the message says what to do: " + msg, msg.contains("Rename the folder or file, or move the file"));
	}

	/**
	 * The natives' messages name the path as it was given: cpp-zarr's hold the
	 * path it was handed (on Windows in the ANSI code page or short), and the
	 * exception's text is built from UTF-8, not read as modified UTF-8.
	 */
	@Test
	public void messagesNameThePathAsGiven() throws Exception {
		File missing = new File(folder(), name + " missing.zarr");
		try {
			new ParallelReadNative().getZarrDims(missing.getPath());
			fail("read " + missing + ", which is not there");
		}
		catch (RuntimeException e) {
			assertTrue("the message names " + missing + ": " + e.getMessage(), String.valueOf(e.getMessage()).contains(missing.getPath()));
		}
	}

	/** A path holding a NUL character names no file (C would end it there, at another file). */
	@Test
	public void pathsWithNulAreRefused() throws Exception {
		File f = TiffFixture.write(new File(folder(), "nul.tif"), new Layout(16, 1, 1).oneBlock(false));
		try {
			new ParallelReadNative().getTiffInfo(f.getPath() + "\u0000.ignored");
			fail("read a path holding a NUL character");
		}
		catch (RuntimeException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("NUL character"));
		}
	}

	private static java.util.List<String> sorted(String[] names) {
		assertNotNull(names);
		String[] s = names.clone();
		Arrays.sort(s);
		return Arrays.asList(s);
	}

	/** The test hook gives short names on Windows only, and only for files that exist. */
	@Test
	public void shortPathNamesOnlyOnWindows() throws Exception {
		File dir = folder();
		if (!WINDOWS) assertEquals("a short name elsewhere than on Windows", null, ParallelReadNative.shortPathName(dir.getPath()));
		assertEquals("a short name for a file that is not there", null, ParallelReadNative.shortPathName(new File(dir, "none").getPath()));
	}
}
