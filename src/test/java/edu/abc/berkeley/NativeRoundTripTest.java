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

/**
 * Round-trip regression tests for the JNI layer (ParallelWriteNative +
 * ParallelReadNative): what goes in must come back out, for every data type
 * the plugin claims to support.
 * <p>
 * Data types the writer cannot produce directly (signed and 32-bit integer
 * zarrs) are created by writing the same bytes under an unsigned/float dtype
 * and then rewriting the dtype in the .zarray metadata -- the on-disk bytes
 * are identical, so the expected values are exactly computable.
 * <p>
 * The tests are skipped (not failed) when the natives are not staged; build
 * with {@code mvn test -Pnative} to run them.
 */
public class NativeRoundTripTest {

	private static final int W = 64, H = 48, Z = 3, CHUNK = 32;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final ParallelWriteNative writer = new ParallelWriteNative();
	private final ParallelReadNative reader = new ParallelReadNative();

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
				slices[s][i] = (short) (i * 31 + s * 7); // covers "negative" bit patterns
		return slices;
	}

	private static byte[][] byteSlices() {
		byte[][] slices = new byte[Z][W * H];
		for (int s = 0; s < Z; s++)
			for (int i = 0; i < W * H; i++)
				slices[s][i] = (byte) (i * 13 + s * 5);
		return slices;
	}

	private static float[][] floatSlices() {
		float[][] slices = new float[Z][W * H];
		for (int s = 0; s < Z; s++)
			for (int i = 0; i < W * H; i++)
				slices[s][i] = i * 0.25f + s + 1.0f; // positive, exactly representable
		return slices;
	}

	/** Rewrite the dtype in a zarr's .zarray metadata (the bytes stay put). */
	private static void flipDtype(String zarrPath, String from, String to) throws Exception {
		Path za = Path.of(zarrPath, ".zarray");
		Files.writeString(za, Files.readString(za).replace(from, to));
	}

	private String writeZarr(Object[] slices, int bits, String name) {
		String path = new File(tmp.getRoot(), name).getPath();
		// PWZ's conventions: end coords are {height, width, z}
		writer.parallelWriteZarr(path, slices, 0, 0, 0, H, W, Z, CHUNK, CHUNK, CHUNK, 1, "zstd", 1, bits);
		return path;
	}

	// --- TIFF ---

	@Test
	public void tiffUint16RoundTrip() {
		short[][] slices = shortSlices();
		String path = new File(tmp.getRoot(), "u16.tif").getPath();
		writer.parallelWriteTiff(path, slices, W, H, Z, 16);

		assertEquals(16, reader.getTiffDataType(path));
		assertEquals(1, reader.getTiffSampleFormat(path));
		assertEquals(1, reader.getTiffSamplesPerPixel(path));
		assertArrayEquals(new long[] { H, W, Z }, reader.getTiffDims(path));

		short[][] back = reader.parallelReadTiffUINT16(path);
		assertEquals(Z, back.length);
		for (int s = 0; s < Z; s++)
			assertArrayEquals("slice " + s, slices[s], back[s]);
	}

	@Test
	public void tiffUint8RoundTrip() {
		byte[][] slices = byteSlices();
		String path = new File(tmp.getRoot(), "u8.tif").getPath();
		writer.parallelWriteTiff(path, slices, W, H, Z, 8);
		byte[][] back = reader.parallelReadTiffUINT8(path);
		for (int s = 0; s < Z; s++)
			assertArrayEquals("slice " + s, slices[s], back[s]);
	}

	@Test
	public void tiffFloatRoundTrip() {
		float[][] slices = floatSlices();
		String path = new File(tmp.getRoot(), "f32.tif").getPath();
		writer.parallelWriteTiff(path, slices, W, H, Z, 32);
		assertEquals(3, reader.getTiffSampleFormat(path)); // IEEE float
		float[][] back = reader.parallelReadTiffFLOAT(path);
		for (int s = 0; s < Z; s++)
			assertArrayEquals("slice " + s, slices[s], back[s], 0.0f);
	}

	// --- Zarr ---

	@Test
	public void zarrUint16RoundTrip() {
		short[][] slices = shortSlices();
		String path = writeZarr(slices, 16, "u16.zarr");
		assertEquals("<u2", reader.getZarrDtype(path));
		assertEquals(16, reader.getZarrDataType(path));
		assertArrayEquals(new long[] { H, W, Z }, reader.getZarrDims(path));
		short[][] back = reader.parallelReadZarrUINT16(path, 0, 0, 0, H, W, Z);
		for (int s = 0; s < Z; s++)
			assertArrayEquals("slice " + s, slices[s], back[s]);
	}

	@Test
	public void zarrFloatRoundTrip() {
		float[][] slices = floatSlices();
		String path = writeZarr(slices, 32, "f32.zarr");
		assertEquals("<f4", reader.getZarrDtype(path));
		float[][] back = reader.parallelReadZarrFLOAT(path, 0, 0, 0, H, W, Z);
		for (int s = 0; s < Z; s++)
			assertArrayEquals("slice " + s, slices[s], back[s], 0.0f);
	}

	@Test
	public void zarrInt16ShiftsSignBit() throws Exception {
		short[][] slices = shortSlices();
		String path = writeZarr(slices, 16, "i16.zarr");
		flipDtype(path, "<u2", "<i2");
		assertEquals("<i2", reader.getZarrDtype(path));

		// Same bytes, signed dtype: the reader must return them shifted by 32768
		short[][] back = reader.parallelReadZarrINT16(path, 0, 0, 0, H, W, Z);
		for (int s = 0; s < Z; s++)
			for (int i = 0; i < W * H; i++)
				assertEquals("slice " + s + " index " + i,
					(short) (slices[s][i] ^ 0x8000), back[s][i]);
	}

	@Test
	public void zarrInt8ShiftsSignBit() throws Exception {
		byte[][] slices = byteSlices();
		String path = writeZarr(slices, 8, "i8.zarr");
		flipDtype(path, "<u1", "<i1");
		byte[][] back = reader.parallelReadZarrINT8(path, 0, 0, 0, H, W, Z);
		for (int s = 0; s < Z; s++)
			for (int i = 0; i < W * H; i++)
				assertEquals("slice " + s + " index " + i,
					(byte) (slices[s][i] ^ 0x80), back[s][i]);
	}

	@Test
	public void zarrInt32ConvertsToFloat() throws Exception {
		float[][] slices = floatSlices();
		String path = writeZarr(slices, 32, "i32.zarr");
		flipDtype(path, "<f4", "<i4");

		// Same bytes, integer dtype: each value is the float's bit pattern as int
		float[][] back = reader.parallelReadZarrINT32(path, 0, 0, 0, H, W, Z);
		for (int s = 0; s < Z; s++)
			for (int i = 0; i < W * H; i++)
				assertEquals("slice " + s + " index " + i,
					(float) Float.floatToRawIntBits(slices[s][i]), back[s][i], 0.0f);
	}

	@Test
	public void zarrUint32ConvertsToFloat() throws Exception {
		float[][] slices = floatSlices();
		String path = writeZarr(slices, 32, "u32.zarr");
		flipDtype(path, "<f4", "<u4");
		float[][] back = reader.parallelReadZarrUINT32(path, 0, 0, 0, H, W, Z);
		for (int s = 0; s < Z; s++)
			for (int i = 0; i < W * H; i++)
				assertEquals("slice " + s + " index " + i,
					(float) (Float.floatToRawIntBits(slices[s][i]) & 0xFFFFFFFFL), back[s][i], 0.0f);
	}

	@Test
	public void zarrWriteProducesChunkFiles() {
		short[][] slices = shortSlices();
		String path = writeZarr(slices, 16, "chunks.zarr");
		File dir = new File(path);
		assertTrue(dir.isDirectory());
		String[] names = dir.list();
		assertNotNull(names);
		int chunkFiles = 0;
		for (String n : names)
			if (!n.startsWith(".")) chunkFiles++;
		long expected = ((H + CHUNK - 1) / CHUNK) * ((W + CHUNK - 1) / CHUNK) * ((Z + CHUNK - 1) / CHUNK);
		assertEquals(expected, chunkFiles);
	}
}
