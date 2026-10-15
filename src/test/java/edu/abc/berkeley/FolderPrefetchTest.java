package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.ParallelFolderOpener;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

/**
 * Opening a folder's files several at a time must give exactly what opening
 * them one at a time gives: every pixel, the dimensions, slice labels,
 * calibration, display range and composite mode, the files that are skipped,
 * and the error when one cannot be opened.
 */
public class FolderPrefetchTest {

	private static final int W = 40, H = 30;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@BeforeClass
	public static void nativesRequired() {
		Natives.required();
	}

	@After
	public void chosenBySize() {
		System.clearProperty(FolderPrefetch.READERS_PROPERTY);
	}

	private static ImagePlus open(File dir, int readers) {
		System.setProperty(FolderPrefetch.READERS_PROPERTY, Integer.toString(readers));
		try {
			return ParallelFolderOpener.open(dir.getPath(), "", null);
		}
		finally {
			System.clearProperty(FolderPrefetch.READERS_PROPERTY);
		}
	}

	/** The folder opened one file at a time, then with 2, 3 and 8 files at once: all the same. */
	private static ImagePlus assertSameEveryWay(File dir) {
		ImagePlus one = open(dir, 1);
		assertNotNull("the folder did not open", one);
		for (int readers : new int[] { 2, 3, 8 })
			assertSameImage(readers + " readers", one, open(dir, readers));
		return one;
	}

	static void assertSameImage(String what, ImagePlus a, ImagePlus b) {
		assertNotNull(what + ": no image", b);
		assertEquals(what + ": title", a.getTitle(), b.getTitle());
		assertEquals(what + ": width", a.getWidth(), b.getWidth());
		assertEquals(what + ": height", a.getHeight(), b.getHeight());
		assertEquals(what + ": channels", a.getNChannels(), b.getNChannels());
		assertEquals(what + ": slices", a.getNSlices(), b.getNSlices());
		assertEquals(what + ": frames", a.getNFrames(), b.getNFrames());
		assertEquals(what + ": type", a.getType(), b.getType());
		assertEquals(what + ": composite", a.isComposite(), b.isComposite());
		if (a.isComposite()) assertEquals(what + ": mode", ((CompositeImage) a).getMode(), ((CompositeImage) b).getMode());
		assertEquals(what + ": display min", a.getDisplayRangeMin(), b.getDisplayRangeMin(), 0);
		assertEquals(what + ": display max", a.getDisplayRangeMax(), b.getDisplayRangeMax(), 0);
		Calibration ca = a.getCalibration(), cb = b.getCalibration();
		assertEquals(what + ": pixel width", ca.pixelWidth, cb.pixelWidth, 0);
		assertEquals(what + ": pixel height", ca.pixelHeight, cb.pixelHeight, 0);
		assertEquals(what + ": pixel depth", ca.pixelDepth, cb.pixelDepth, 0);
		assertEquals(what + ": unit", ca.getUnit(), cb.getUnit());
		assertEquals(what + ": calibration function", ca.getFunction(), cb.getFunction());
		ImageStack sa = a.getStack(), sb = b.getStack();
		assertEquals(what + ": planes", sa.getSize(), sb.getSize());
		for (int n = 1; n <= sa.getSize(); n++) {
			assertEquals(what + ": label " + n, sa.getSliceLabel(n), sb.getSliceLabel(n));
			Object pa = sa.getPixels(n), pb = sb.getPixels(n);
			if (pa instanceof byte[]) assertArrayEquals(what + ": plane " + n, (byte[]) pa, (byte[]) pb);
			else if (pa instanceof short[]) assertArrayEquals(what + ": plane " + n, (short[]) pa, (short[]) pb);
			else if (pa instanceof int[]) assertArrayEquals(what + ": plane " + n, (int[]) pa, (int[]) pb);
			else assertArrayEquals(what + ": plane " + n, (float[]) pa, (float[]) pb, 0f);
		}
	}

	/** Every pixel of every file different, so a file read into the wrong place shows. */
	private static void fill(ImageProcessor ip, int file, int slice) {
		for (int i = 0; i < W * H; i++) ip.setf(i, (file * 97 + slice * 31 + i) % (ip instanceof ByteProcessor ? 256 : 30000));
	}

	private static short[][] shortSlices(int file, int z) {
		short[][] s = new short[z][W * H];
		for (int k = 0; k < z; k++)
			for (int i = 0; i < W * H; i++) s[k][i] = (short) ((file * 1009 + k * 131 + i) % 30000);
		return s;
	}

	@Test
	public void channelsAndTimePointsOpenTheSame() throws Exception {
		File dir = tmp.newFolder("series");
		String[][] cams = { { "CamA", "488nm" }, { "CamB", "560nm" } };
		ParallelWriteNative writer = new ParallelWriteNative();
		int f = 0;
		for (int t = 0; t < 4; t++)
			for (int c = 0; c < 2; c++)
				writer.parallelWriteTiff(new File(dir, String.format(
					"Scan_Iter_%04d_%s_ch0_CAM1_stack0000_%s_0000000msec_0000000000msecAbs_000x_000y_000z_0000t.tif",
					t, cams[c][0], cams[c][1])).getPath(), shortSlices(f++, 3), W, H, 3, 16);
		ImagePlus imp = assertSameEveryWay(dir);
		assertEquals(2, imp.getNChannels());
		assertEquals(4, imp.getNFrames());
	}

	@Test
	public void manySmallStacksOpenTheSame() throws Exception {
		File dir = tmp.newFolder("many");
		ParallelWriteNative writer = new ParallelWriteNative();
		for (int f = 0; f < 60; f++)
			writer.parallelWriteTiff(new File(dir, String.format("img_%03d.tif", f)).getPath(), shortSlices(f, 1 + f % 3), W, H, 1 + f % 3, 16);
		assertEquals(20 * 1 + 20 * 2 + 20 * 3, assertSameEveryWay(dir).getStackSize());
	}

	/** ImageJ's own TIFFs (classic, with ImageJ's description): calibration and display range. */
	@Test
	public void imageJTiffsOpenTheSame() throws Exception {
		for (String kind : new String[] { "8-bit", "32-bit", "RGB" }) {
			File dir = tmp.newFolder(kind);
			for (int f = 0; f < 12; f++) {
				ImageProcessor ip = kind.equals("8-bit") ? new ByteProcessor(W, H) : kind.equals("32-bit") ? new FloatProcessor(W, H) : null;
				if (ip != null) fill(ip, f, 0);
				else {
					int[] px = new int[W * H];
					for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | ((f * 7 + i) % 256) << 16 | (i % 256) << 8 | (f * 13) % 256;
					ip = new ColorProcessor(W, H, px);
				}
				ImagePlus imp = new ImagePlus("f" + f, ip);
				Calibration cal = imp.getCalibration();
				cal.pixelWidth = 0.104;
				cal.pixelHeight = 0.104;
				cal.setUnit("micron");
				assertTrue(new FileSaver(imp).saveAsTiff(new File(dir, String.format("%s_%02d.tif", kind, f)).getPath()));
			}
			ImagePlus imp = assertSameEveryWay(dir);
			assertEquals(kind, 12, imp.getStackSize());
			assertEquals(kind, "micron", imp.getCalibration().getUnit());
		}
	}

	/** 16-bit color: one composite per file, its channels stacked. */
	@Test
	public void sixteenBitColorOpensTheSame() throws Exception {
		File dir = tmp.newFolder("color48");
		for (int f = 0; f < 8; f++) TiffFixture.write(new File(dir, String.format("rgb48_%d.tif", f)), 16, 12, 2, 3, false);
		assertEquals(8 * 6, assertSameEveryWay(dir).getStackSize());
	}

	@Test
	public void zarrArraysOpenTheSame() throws Exception {
		File dir = tmp.newFolder("zarrs");
		long[] shape = { H, W, 4 };
		for (int f = 0; f < 10; f++) {
			if (f % 2 == 0) ZarrFixture.writeV2(new File(dir, "a" + f), shape, new long[] { 16, 16, 2 }, 'F', null);
			else ZarrFixture.writeV3(new File(dir, "a" + f), shape, new long[] { 16, 16, 2 }, 'F', new String[] { "y", "x", "z" });
		}
		assertEquals(40, assertSameEveryWay(dir).getStackSize());
	}

	/** Files the plugin does not read itself (here PNGs) stay in their place among the others. */
	@Test
	public void otherFormatsKeepTheirPlace() throws Exception {
		File dir = tmp.newFolder("mixed");
		ParallelWriteNative writer = new ParallelWriteNative();
		for (int f = 0; f < 12; f++) {
			String name = String.format("img_%02d", f);
			if (f % 4 == 1) {
				ShortProcessor ip = new ShortProcessor(W, H);
				fill(ip, f, 0);
				assertTrue(new FileSaver(new ImagePlus(name, ip)).saveAsPng(new File(dir, name + ".png").getPath()));
			}
			else writer.parallelWriteTiff(new File(dir, name + ".tif").getPath(), shortSlices(f, 1), W, H, 1, 16);
		}
		Files.write(new File(dir, "notes.txt").toPath(), "not an image".getBytes(StandardCharsets.UTF_8));
		assertEquals(12, assertSameEveryWay(dir).getStackSize());
	}

	/** A file far bigger than the others is read by the opener itself, in its place. */
	@Test
	public void aMuchBiggerFileIsReadInItsPlace() throws Exception {
		File dir = tmp.newFolder("oneBig");
		ParallelWriteNative writer = new ParallelWriteNative();
		String[] names = new String[10];
		for (int f = 0; f < 10; f++) {
			names[f] = String.format("img_%02d.tif", f);
			writer.parallelWriteTiff(new File(dir, names[f]).getPath(), shortSlices(f, 2), W, H, 2, 16);
		}
		// The same image in a 100 MB file (the rest unused): far bigger than the others
		try (RandomAccessFile big = new RandomAccessFile(new File(dir, names[4]), "rw")) {
			big.setLength(100L << 20);
		}
		System.setProperty(FolderPrefetch.READERS_PROPERTY, "4");
		FolderPrefetch prefetch = FolderPrefetch.start(dir.getPath(), names);
		System.clearProperty(FolderPrefetch.READERS_PROPERTY);
		assertNotNull(prefetch);
		try {
			assertFalse("the big file is read ahead", prefetch.handles(4));
			assertTrue(prefetch.handles(3));
		}
		finally {
			prefetch.close();
		}
		assertEquals(20, assertSameEveryWay(dir).getStackSize());
	}

	/** A file of another size is skipped, with or without reading ahead. */
	@Test
	public void wrongSizedFilesAreSkippedTheSame() throws Exception {
		File dir = tmp.newFolder("sizes");
		ParallelWriteNative writer = new ParallelWriteNative();
		for (int f = 0; f < 10; f++) {
			int w = f == 6 ? W + 2 : W;
			short[][] s = new short[1][w * H];
			for (int i = 0; i < w * H; i++) s[0][i] = (short) (f * 100 + i);
			writer.parallelWriteTiff(new File(dir, String.format("img_%02d.tif", f)).getPath(), s, w, H, 1, 16);
		}
		assertEquals(9, assertSameEveryWay(dir).getStackSize());
	}

	/** A file that cannot be opened throws the same error, and no reader is left running. */
	@Test
	public void aBrokenFileFailsTheSame() throws Exception {
		File dir = tmp.newFolder("broken");
		ParallelWriteNative writer = new ParallelWriteNative();
		for (int f = 0; f < 12; f++) {
			File file = new File(dir, String.format("img_%02d.tif", f));
			if (f == 7) Files.write(file.toPath(), "this is not a TIFF".getBytes(StandardCharsets.UTF_8));
			else writer.parallelWriteTiff(file.getPath(), shortSlices(f, 2), W, H, 2, 16);
		}
		String[] messages = new String[2];
		int k = 0;
		for (int readers : new int[] { 1, 6 }) {
			try {
				open(dir, readers);
				fail("opened a folder with a broken file, " + readers + " readers");
			}
			catch (RuntimeException e) {
				messages[k++] = e.getClass().getName() + ": " + e.getMessage();
			}
		}
		assertEquals(messages[0], messages[1]);
		long deadline = System.currentTimeMillis() + 5000;
		while (readerThreads() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(50);
		assertEquals("reader threads still running", 0, readerThreads());
	}

	private static int readerThreads() {
		int n = 0;
		for (Thread t : Thread.getAllStackTraces().keySet())
			if (t.getName().startsWith("PFV folder reader") && t.isAlive()) n++;
		return n;
	}

	/** The readers' thread limits are their own: the opener's thread keeps every core. */
	@Test
	public void theCallersThreadLimitIsUntouched() throws Exception {
		File dir = tmp.newFolder("limit");
		ParallelWriteNative writer = new ParallelWriteNative();
		for (int f = 0; f < 8; f++)
			writer.parallelWriteTiff(new File(dir, String.format("img_%02d.tif", f)).getPath(), shortSlices(f, 1), W, H, 1, 16);
		int before = ParallelReadNative.threadLimit();
		assertNotNull(open(dir, 4));
		assertEquals(before, ParallelReadNative.threadLimit());
	}

	@Test
	public void readersAreChosenFromTheFileSize() {
		long heap = 64L << 30, mb = 1L << 20;
		assertEquals(1, FolderPrefetch.readers(0, 48, heap));
		for (long size : new long[] { 2 * mb, 8 * mb, 32 * mb, 128 * mb, 256 * mb })
			assertEquals(size / mb + " MB", 8, FolderPrefetch.readers(size, 48, heap));
		assertEquals(4, FolderPrefetch.readers(512 * mb, 48, heap));
		assertEquals(2, FolderPrefetch.readers(1024 * mb, 48, heap));
		assertEquals(1, FolderPrefetch.readers(2048 * mb, 48, heap));
		// Half the cores: a laptop's 8 read 4 at once, 2 cores none
		assertEquals(4, FolderPrefetch.readers(8 * mb, 8, heap));
		assertEquals(1, FolderPrefetch.readers(8 * mb, 2, heap));
		// The files read ahead stay within an eighth of the heap
		assertEquals(4, FolderPrefetch.readers(128 * mb, 48, 8L << 30));
		assertEquals(1, FolderPrefetch.readers(512 * mb, 48, 8L << 30));
	}
}
