package edu.abc.berkeley;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Writes small uncompressed uint16 Zarr arrays, v2 and v3, in the layouts
 * other tools write, for the tests. Every element holds a different value,
 * 1 + its index in C order, so a test can tell exactly which element each
 * pixel came from.
 */
final class ZarrFixture {

	private ZarrFixture() {
	}

	/** The value the fixtures store at index. */
	static short value(long[] shape, long[] index) {
		long flat = 0;
		for (int d = 0; d < shape.length; d++) flat = flat * shape[d] + index[d];
		return (short) (flat + 1);
	}

	/**
	 * The ImageJ plane (c, z, t) of an array whose x, y, z, c and t axes are
	 * axes, as the plugin should show it: rows y of columns x.
	 */
	static short[] plane(long[] shape, int[] axes, int c, int z, int t) {
		int w = (int) shape[axes[0]], h = (int) shape[axes[1]];
		short[] p = new short[w * h];
		long[] index = new long[shape.length];
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++) {
				index[axes[0]] = x;
				index[axes[1]] = y;
				if (axes[2] >= 0) index[axes[2]] = z;
				if (axes[3] >= 0) index[axes[3]] = c;
				if (axes[4] >= 0) index[axes[4]] = t;
				p[y * w + x] = value(shape, index);
			}
		return p;
	}

	/**
	 * The planes of the window [start, end) of an array whose x, y, z, c and t
	 * axes are axes, as the plugin should show them: in ImageJ's czt order
	 * (plane c + C*(z + Z*t)), each rows y of columns x.
	 */
	static short[][] planes(long[] shape, int[] axes, long[] start, long[] end) {
		int[] n = new int[5];
		for (int i = 0; i < 5; i++) n[i] = axes[i] >= 0 ? (int) (end[axes[i]] - start[axes[i]]) : 1;
		short[][] planes = new short[n[2] * n[3] * n[4]][n[0] * n[1]];
		long[] index = new long[shape.length];
		for (int t = 0; t < n[4]; t++)
			for (int z = 0; z < n[2]; z++)
				for (int c = 0; c < n[3]; c++)
					for (int y = 0; y < n[1]; y++)
						for (int x = 0; x < n[0]; x++) {
							int[] at = { x, y, z, c, t };
							for (int d = 0; d < shape.length; d++) index[d] = start[d];
							for (int i = 0; i < 5; i++)
								if (axes[i] >= 0) index[axes[i]] += at[i];
							planes[c + n[3] * (z + n[2] * t)][y * n[0] + x] = value(shape, index);
						}
		return planes;
	}

	/** A Zarr v2 array (uncompressed, chunk files separated by "/"); attributes may be null. */
	static File writeV2(File dir, long[] shape, long[] chunks, char order, String attributes) throws IOException {
		dir.mkdirs();
		write(new File(dir, ".zarray"), "{\"zarr_format\": 2, \"shape\": " + list(shape) + ", \"chunks\": " + list(chunks)
			+ ", \"dtype\": \"<u2\", \"compressor\": null, \"fill_value\": 0, \"order\": \"" + order
			+ "\", \"filters\": null, \"dimension_separator\": \"/\"}");
		if (attributes != null) write(new File(dir, ".zattrs"), attributes);
		writeChunks(dir, "", shape, chunks, order);
		return dir;
	}

	/**
	 * A Zarr v3 array (bytes codec only; for F order a transpose codec that
	 * reverses the axes, as cpp-zarr and tensorstore write it); names may be null.
	 */
	static File writeV3(File dir, long[] shape, long[] chunks, char order, String[] names) throws IOException {
		return writeV3(dir, shape, chunks, stored(shape.length, order), names);
	}

	/**
	 * A Zarr v3 array whose chunks store the axes in the order stored (slowest
	 * first: a transpose codec unless it is C order); names may be null.
	 */
	static File writeV3(File dir, long[] shape, long[] chunks, int[] stored, String[] names) throws IOException {
		dir.mkdirs();
		write(new File(dir, "zarr.json"), v3Json(shape, chunks, "[" + chunkCodecs(stored) + "]", names));
		writeChunks(dir, "c/", shape, chunks, stored);
		return dir;
	}

	/**
	 * A sharded Zarr v3 array: shards of the shape shards, each holding inner
	 * chunks of the shape inner (C or F order inside), its index at the end
	 * without a checksum. The inner chunks at the grid indices omit are left
	 * out of their shards (they read as the fill value).
	 */
	static File writeV3Sharded(File dir, long[] shape, long[] shards, long[] inner, char order, String[] names,
	                           long[]... omit) throws IOException {
		return writeV3Sharded(dir, shape, shards, inner, stored(shape.length, order), false, names, omit);
	}

	/**
	 * The same with the inner chunks storing the axes in the order stored
	 * (slowest first: a transpose codec inside the shards unless it is C
	 * order), and the index followed by its CRC32C when checksum is set.
	 */
	static File writeV3Sharded(File dir, long[] shape, long[] shards, long[] inner, int[] stored, boolean checksum,
	                           String[] names, long[]... omit) throws IOException {
		dir.mkdirs();
		int n = shape.length;
		String codecs = "[{\"name\": \"sharding_indexed\", \"configuration\": {\"chunk_shape\": " + list(inner)
			+ ", \"codecs\": [" + chunkCodecs(stored) + "], \"index_codecs\": [{\"name\": \"bytes\", \"configuration\": "
			+ "{\"endian\": \"little\"}}" + (checksum ? ", {\"name\": \"crc32c\"}" : "") + "], \"index_location\": \"end\"}}]";
		write(new File(dir, "zarr.json"), v3Json(shape, shards, codecs, names));
		long[] grid = new long[n], perShard = new long[n];
		long count = 1, innerCount = 1;
		for (int d = 0; d < n; d++) {
			grid[d] = (shape[d] + shards[d] - 1) / shards[d];
			perShard[d] = shards[d] / inner[d];
			count *= grid[d];
			innerCount *= perShard[d];
		}
		long[] g = new long[n], local = new long[n], at = new long[n];
		for (long k = 0; k < count; k++) {
			unflatten(k, grid, g);
			ByteArrayOutputStream data = new ByteArrayOutputStream();
			ByteBuffer index = ByteBuffer.allocate((int) innerCount * 16).order(ByteOrder.LITTLE_ENDIAN);
			// The index lists the inner chunks last axis fastest
			for (long q = 0; q < innerCount; q++) {
				unflatten(q, perShard, local);
				boolean inside = true, omitted = false;
				for (int d = 0; d < n; d++) {
					at[d] = g[d] * perShard[d] + local[d];
					inside &= at[d] * inner[d] < shape[d];
				}
				for (long[] o : omit) omitted |= Arrays.equals(o, at);
				if (!inside || omitted) {
					index.putLong(-1L).putLong(-1L);
					continue;
				}
				byte[] chunk = chunk(shape, inner, at, stored);
				index.putLong(data.size()).putLong(chunk.length);
				data.write(chunk);
			}
			data.write(index.array());
			if (checksum) {
				int crc = crc32c(index.array());
				data.write(new byte[] { (byte) crc, (byte) (crc >>> 8), (byte) (crc >>> 16), (byte) (crc >>> 24) });
			}
			File f = new File(dir, "c/" + key(g));
			f.getParentFile().mkdirs();
			Files.write(f.toPath(), data.toByteArray());
		}
		return dir;
	}

	private static String v3Json(long[] shape, long[] chunks, String codecs, String[] names) {
		StringBuilder json = new StringBuilder("{\"zarr_format\": 3, \"node_type\": \"array\", \"shape\": " + list(shape)
			+ ", \"data_type\": \"uint16\", \"chunk_grid\": {\"name\": \"regular\", \"configuration\": {\"chunk_shape\": " + list(chunks)
			+ "}}, \"chunk_key_encoding\": {\"name\": \"default\", \"configuration\": {\"separator\": \"/\"}}, \"fill_value\": 0, \"codecs\": "
			+ codecs + ", \"attributes\": {}");
		if (names != null) {
			json.append(", \"dimension_names\": [");
			for (int d = 0; d < names.length; d++)
				json.append(d > 0 ? ", " : "").append(names[d] == null ? "null" : "\"" + names[d] + "\"");
			json.append("]");
		}
		return json.append("}").toString();
	}

	/** A chunk's codecs: a transpose to the stored order (none for C order), then the bytes. */
	private static String chunkCodecs(int[] stored) {
		boolean identity = true;
		StringBuilder order = new StringBuilder("[");
		for (int d = 0; d < stored.length; d++) {
			identity &= stored[d] == d;
			order.append(d > 0 ? ", " : "").append(stored[d]);
		}
		return (identity ? "" : "{\"name\": \"transpose\", \"configuration\": {\"order\": " + order + "]}}, ")
			+ "{\"name\": \"bytes\", \"configuration\": {\"endian\": \"little\"}}";
	}

	/** CRC-32C (Castagnoli), which Zarr v3's crc32c codec appends (Java 8 has no CRC32C class). */
	static int crc32c(byte[] b) {
		int crc = ~0;
		for (byte v : b) {
			crc ^= v & 0xff;
			for (int k = 0; k < 8; k++) crc = (crc >>> 1) ^ (0x82F63B78 & -(crc & 1));
		}
		return ~crc;
	}

	/** The axes of C order (0 to n - 1) or F order (n - 1 to 0), slowest first. */
	static int[] stored(int n, char order) {
		int[] axes = new int[n];
		for (int d = 0; d < n; d++) axes[d] = order == 'F' ? n - 1 - d : d;
		return axes;
	}

	/** A Zarr v2 group, with its attributes (null: none). */
	static File writeV2Group(File dir, String attributes) throws IOException {
		dir.mkdirs();
		write(new File(dir, ".zgroup"), "{\"zarr_format\": 2}");
		if (attributes != null) write(new File(dir, ".zattrs"), attributes);
		return dir;
	}

	/** A Zarr v3 group, with its attributes (a JSON object, or null). */
	static File writeV3Group(File dir, String attributes) throws IOException {
		dir.mkdirs();
		write(new File(dir, "zarr.json"), "{\"zarr_format\": 3, \"node_type\": \"group\", \"attributes\": "
			+ (attributes == null ? "{}" : attributes) + "}");
		return dir;
	}

	static void write(File f, String text) throws IOException {
		f.getParentFile().mkdirs();
		Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	static String list(long[] v) {
		StringBuilder b = new StringBuilder("[");
		for (int d = 0; d < v.length; d++) b.append(d > 0 ? ", " : "").append(v[d]);
		return b.append("]").toString();
	}

	private static void writeChunks(File dir, String prefix, long[] shape, long[] chunks, char order) throws IOException {
		writeChunks(dir, prefix, shape, chunks, stored(shape.length, order));
	}

	private static void writeChunks(File dir, String prefix, long[] shape, long[] chunks, int[] stored) throws IOException {
		int n = shape.length;
		long[] grid = new long[n];
		long count = 1;
		for (int d = 0; d < n; d++) {
			grid[d] = (shape[d] + chunks[d] - 1) / chunks[d];
			count *= grid[d];
		}
		long[] g = new long[n];
		for (long k = 0; k < count; k++) {
			unflatten(k, grid, g);
			File f = new File(dir, prefix + key(g));
			f.getParentFile().mkdirs();
			Files.write(f.toPath(), chunk(shape, chunks, g, stored));
		}
	}

	/** The index of position k of a grid, last axis fastest. */
	private static void unflatten(long k, long[] grid, long[] g) {
		for (int d = grid.length - 1; d >= 0; d--) {
			g[d] = k % grid[d];
			k /= grid[d];
		}
	}

	/** A chunk's key: its grid indices separated by "/". */
	private static String key(long[] g) {
		StringBuilder key = new StringBuilder();
		for (int d = 0; d < g.length; d++) key.append(d > 0 ? "/" : "").append(g[d]);
		return key.toString();
	}

	/**
	 * One full-size chunk (edge chunks padded with 0), its axes stored in the
	 * order stored (slowest first).
	 */
	private static byte[] chunk(long[] shape, long[] chunks, long[] g, int[] stored) {
		int n = shape.length;
		long elements = 1;
		for (long c : chunks) elements *= c;
		ByteBuffer b = ByteBuffer.allocate((int) elements * 2).order(ByteOrder.LITTLE_ENDIAN);
		long[] local = new long[n], index = new long[n];
		for (long k = 0; k < elements; k++) {
			long rem = k;
			for (int j = n - 1; j >= 0; j--) {
				local[stored[j]] = rem % chunks[stored[j]];
				rem /= chunks[stored[j]];
			}
			boolean inside = true;
			for (int d = 0; d < n; d++) {
				index[d] = g[d] * chunks[d] + local[d];
				inside &= index[d] < shape[d];
			}
			b.putShort(inside ? value(shape, index) : 0);
		}
		return b.array();
	}
}
