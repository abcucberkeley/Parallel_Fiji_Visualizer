package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ij.ImagePlus;
import ij.ImageStack;
import ij.VirtualStack;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

/**
 * The plugin window's saves run on threads of their own, so Fiji stays
 * responsive, with the image locked meanwhile so it is neither changed nor
 * emptied (by closing it) while it is written. They write what they always
 * wrote, virtual stacks included, to the file asked for.
 */
public class SaveTest {

	private static final int W = 64, H = 48;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static ImagePlus stack(int planes) {
		ImageStack stack = new ImageStack(W, H);
		for (int z = 0; z < planes; z++) {
			short[] p = new short[W * H];
			for (int i = 0; i < p.length; i++) p[i] = (short) (z * 1009 + i);
			stack.addSlice(new ShortProcessor(W, H, p, null));
		}
		return new ImagePlus("saved", stack);
	}

	private static void assertSamePlanes(ImagePlus expected, ImagePlus read) {
		assertNotNull("nothing read back", read);
		assertEquals("planes", expected.getStackSize(), read.getStackSize());
		for (int n = 1; n <= expected.getStackSize(); n++)
			assertArrayEquals("plane " + n, (short[]) expected.getStack().getPixels(n), (short[]) read.getStack().getPixels(n));
	}

	private static void finish(Thread save) throws InterruptedException {
		save.join(60000);
		assertFalse("still saving after 60 s", save.isAlive());
	}

	@Test
	public void tiffIsSavedOnAThreadOfItsOwn() throws Exception {
		Natives.required();
		ImagePlus imp = stack(3);
		File file = new File(tmp.getRoot(), "saved.tif");
		Thread save = PWT.save(imp, file);
		assertTrue(save != Thread.currentThread());
		finish(save);
		assertFalse("the image was left locked", imp.isLocked());
		assertSamePlanes(imp, new PRT(file.getPath(), false).getImp());
	}

	@Test
	public void zarrIsSavedOnAThreadOfItsOwn() throws Exception {
		Natives.required();
		ImagePlus imp = stack(3);
		File dir = new File(tmp.getRoot(), "saved.zarr");
		finish(PWZ.save(imp, dir));
		assertFalse("the image was left locked", imp.isLocked());
		assertSamePlanes(imp, new PRZ(dir.getPath(), false).getImp());
	}

	/** Closing an image empties its stack (ImageJ's flush), unless it is locked, as it is while saved. */
	@Test
	public void theImageIsLockedWhileItIsSaved() throws Exception {
		ImagePlus imp = stack(2);
		boolean[] locked = new boolean[1];
		finish(Background.save(imp, "test", () -> {
			locked[0] = imp.isLocked() && !imp.isLockedByAnotherThread();
			imp.flush();
		}));
		assertTrue("the save did not lock the image", locked[0]);
		assertFalse("the image was left locked", imp.isLocked());
		assertNotNull("closing the image emptied it while it was saved", imp.getStack().getPixels(1));
	}

	/** An image that another command or save has locked is not saved (the dialog says so). */
	@Test
	public void anImageInUseIsNotSaved() throws Exception {
		ImagePlus imp = stack(2);
		assertTrue(imp.lock());
		try {
			boolean[] wrote = new boolean[1];
			finish(Background.save(imp, "test", () -> wrote[0] = true));
			assertFalse("saved an image in use", wrote[0]);
			assertTrue("the other command's lock was taken", imp.isLocked());
		}
		finally {
			imp.unlock();
		}
	}

	/** A save that fails (its error shown in a dialog) leaves the image unlocked. */
	@Test
	public void aFailedSaveUnlocksTheImage() throws Exception {
		ImagePlus imp = stack(2);
		finish(Background.save(imp, "test", () -> {
			throw new RuntimeException("this save fails on purpose");
		}));
		assertFalse("the image was left locked", imp.isLocked());
	}

	/** A virtual stack has no array of its planes (it crashed the JVM): they are read and saved. */
	@Test
	public void virtualStacksAreSaved() throws Exception {
		Natives.required();
		final ImagePlus inMemory = stack(5);
		ImageStack virtual = new VirtualStack(W, H, null, null) {
			@Override
			public int getSize() {
				return 5;
			}

			@Override
			public int getBitDepth() {
				return 16;
			}

			@Override
			public ImageProcessor getProcessor(int n) {
				return inMemory.getStack().getProcessor(n).duplicate();
			}
		};
		File file = new File(tmp.getRoot(), "virtual.tif");
		PWT.write(new ImagePlus("virtual", virtual), file.getPath());
		assertSamePlanes(inMemory, new PRT(file.getPath(), false).getImp());
	}

	/** Only the name gets .tif: dots in the folders' names stay (they cut the path at the first). */
	@Test
	public void tiffsAreSavedInTheFolderChosen() {
		File dir = new File(new File(tmp.getRoot(), "first.last"), "v1.2");
		assertEquals(new File(dir, "out.tif"), PWT.tiffFile(new File(dir, "out")));
		assertEquals(new File(dir, "out.tif"), PWT.tiffFile(new File(dir, "out.png")));
		assertEquals(new File(dir, "out.tif"), PWT.tiffFile(new File(dir, "out.tif")));
		assertEquals(new File(dir, "OUT.TIFF"), PWT.tiffFile(new File(dir, "OUT.TIFF")));
		// As ImageJ names files: a dot then a digit, or a long tail, is part of the name
		assertEquals(new File(dir, "cell.01.tif"), PWT.tiffFile(new File(dir, "cell.01")));
		assertEquals(new File(dir, "2026.10.15_scan.tif"), PWT.tiffFile(new File(dir, "2026.10.15_scan")));
	}

	/** The error is about the planes (not, say, native memory running out). */
	private static void assertSaysWhichPlanes(RuntimeException e) {
		assertTrue(String.valueOf(e), e.getMessage() != null && e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("plane"));
	}

	/** Planes that are missing or of another size or type are refused, not read past (or crashed on). */
	@Test
	public void theWritersRefuseBadPlanes() {
		Natives.required();
		ParallelWriteNative writer = new ParallelWriteNative();
		short[] ok = new short[W * H];
		Object[][] bad = { null, { ok, ok }, { ok, null, ok }, { ok, new short[10], ok }, { ok, new byte[W * H], ok },
			{ ok, "not a plane", ok } };
		for (int k = 0; k < bad.length; k++) {
			try {
				writer.parallelWriteTiff(new File(tmp.getRoot(), "bad" + k + ".tif").getPath(), bad[k], W, H, 3, 16);
				fail("TIFF written from bad planes " + k);
			}
			catch (RuntimeException e) {
				assertSaysWhichPlanes(e);
			}
			if (bad[k] == null || bad[k].length < 3) continue;
			try {
				writer.parallelWriteZarrArray(new File(tmp.getRoot(), "bad" + k + ".zarr").getPath(), bad[k],
					new long[] { H, W, 3 }, new long[] { H, W, 1 }, new long[] { W, 1, (long) W * H }, "zstd", 16);
				fail("Zarr written from bad planes " + k);
			}
			catch (RuntimeException e) {
				assertSaysWhichPlanes(e);
			}
		}
	}
}
