package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import edu.abc.berkeley.TiffFixture.Layout;
import ij.ImagePlus;
import ij.ParallelVirtualStack;

/**
 * An ImageJ stack whose last slices start beyond 4 GiB, where ImageJ's
 * classic TIFF holds only the first slice's offset and every other one is
 * computed: the natives' 64-bit positioning (pread; _fseeki64 on Windows)
 * reads the slice that straddles 4 GiB and the ones after it. The file is
 * sparse, so it takes a few MB of disk: only its directory and four slices
 * are written. Where the file system cannot make it sparse, the test is
 * skipped rather than writing 4 GB.
 */
public class ImageJStackOffsetTest {

	private static final String FORCE_VIRTUAL = "pfv.forceVirtual";
	private static final long FOUR_GIB = 1L << 32;

	// 2,048,000 bytes per slice (not a power of two, so a slice straddles
	// 4 GiB); slice 2097 does, and 2098 and 2099 start beyond it
	private static final int W = 1024, H = 1000, SLICES = 2100, STRADDLES = 2097;
	/** The slices written: the first, the one across 4 GiB and those after it. The others are holes, all zeros. */
	private static final int[] WRITTEN = { 0, STRADDLES, STRADDLES + 1, STRADDLES + 2 };

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private Path file;

	@BeforeClass
	public static void nativesRequired() {
		Natives.required();
	}

	@After
	public void deleteFile() throws IOException {
		System.clearProperty(FORCE_VIRTUAL);
		if (file != null) Files.deleteIfExists(file);
	}

	/** Slice z's pixel p: hashed, so a slice read from anywhere else in the file differs, and never 0, so a hole read in its place shows. */
	private static short value(int z, int p) {
		int h = z * 0x9E3779B1 + p * 0x85EBCA77;
		h ^= h >>> 15;
		h *= 0x2C1B3C6D;
		h ^= h >>> 13;
		return (short) (h | 1);
	}

	private static short[] expected(int z) {
		short[] p = new short[W * H];
		boolean written = false;
		for (int z0 : WRITTEN) written |= z0 == z;
		if (written) for (int i = 0; i < p.length; i++) p[i] = value(z, i);
		return p;
	}

	/**
	 * Whether files in dir can have holes: a hole of 256 MiB written past
	 * leaves the file system's unallocated space as it was (within 128 MiB,
	 * for other writers' sake), where without sparse files it is zero-filled
	 * at once.
	 */
	private static boolean sparseFiles(File dir) throws IOException {
		Path probe = new File(dir, "probe.bin").toPath();
		FileStore store = Files.getFileStore(dir.toPath());
		try {
			final long before = store.getUnallocatedSpace();
			try (FileChannel c = FileChannel.open(probe, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
				c.write(ByteBuffer.wrap(new byte[] { 1 }), 256L << 20);
				c.force(true);
			}
			return before - store.getUnallocatedSpace() < 128L << 20;
		}
		finally {
			Files.deleteIfExists(probe);
		}
	}

	/** Writes the stack sparsely, big-endian as ImageJ does: the header and directory, then only the WRITTEN slices at their offsets. */
	private Layout writeSparse() throws IOException {
		Layout l = new Layout(16, 1, 1).size(W, H, SLICES).order(ByteOrder.BIG_ENDIAN);
		byte[] directories = TiffFixture.directories(l);
		final long start = directories.length, sliceBytes = l.sliceBytes();
		assertTrue("the straddling slice starts below 4 GiB", start + STRADDLES * sliceBytes < FOUR_GIB);
		assertTrue("and ends above it", start + (STRADDLES + 1) * sliceBytes > FOUR_GIB);
		file = new File(tmp.getRoot(), "beyond4GiB.tif").toPath();
		try (FileChannel c = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
			c.write(ByteBuffer.wrap(directories), 0);
			ByteBuffer slice = ByteBuffer.allocate((int) sliceBytes).order(l.order);
			for (int z : WRITTEN) {
				slice.clear();
				for (int i = 0; i < W * H; i++) slice.putShort(value(z, i));
				slice.flip();
				for (long at = start + z * sliceBytes; slice.hasRemaining();) at += c.write(slice, at);
			}
		}
		assertEquals("the file's length", start + SLICES * sliceBytes, Files.size(file));
		return l;
	}

	@Test
	public void slicesBeyond4GiB() throws Exception {
		assumeTrue("no sparse files in " + tmp.getRoot(), sparseFiles(tmp.getRoot()));
		writeSparse();
		String path = file.toString();
		long[] info = new ParallelReadNative().getTiffInfo(path);
		assertEquals("an ImageJ stack", 1, info[ParallelReadNative.TIFF_IMAGEJ]);
		assertEquals("slices", SLICES, info[ParallelReadNative.TIFF_SLICES]);

		// The natives' read: the last slices in one read, then one at a time,
		// and holes on either side of 4 GiB
		TiffImage tiff = TiffImage.open(path);
		Object[] last = tiff.read(STRADDLES - 1, SLICES);
		assertEquals(SLICES - STRADDLES + 1, last.length);
		for (int n = 0; n < last.length; n++)
			assertArrayEquals("slice " + (STRADDLES - 1 + n) + " of the last ones", expected(STRADDLES - 1 + n), (short[]) last[n]);
		for (int z : new int[] { 0, 1, 1048, STRADDLES - 1, STRADDLES, STRADDLES + 1, STRADDLES + 2 })
			assertArrayEquals("slice " + z, expected(z), (short[]) tiff.read(z, z + 1)[0]);

		// The virtual stack's blocks. Reading ahead is off in both virtual
		// stacks here: its thread may still hold the file when the test
		// deletes it, which Windows refuses, and this test is about offsets
		ParallelVirtualStack stack = new ParallelVirtualStack(tiff);
		stack.setReadAhead(false);
		assertEquals(SLICES, stack.getSize());
		for (int z : new int[] { SLICES - 1, STRADDLES, STRADDLES + 1, STRADDLES - 1, 0, 1000 })
			assertArrayEquals("virtual slice " + z, expected(z), (short[]) stack.getProcessor(z + 1).getPixels());

		// The plugin's open, as a virtual stack (the whole stack would take 4 GB of heap)
		System.setProperty(FORCE_VIRTUAL, "true");
		ImagePlus imp = new PRT(path, false).getImp();
		assertNotNull("did not open", imp);
		assertTrue("not a virtual stack", imp.getStack().isVirtual());
		((ParallelVirtualStack) imp.getStack()).setReadAhead(false);
		assertEquals(SLICES, imp.getStackSize());
		assertEquals(16, imp.getBitDepth());
		for (int z : new int[] { STRADDLES, SLICES - 1, STRADDLES + 1, 0, 2 })
			assertArrayEquals("the plugin's slice " + z, expected(z), (short[]) imp.getStack().getPixels(z + 1));
	}
}
