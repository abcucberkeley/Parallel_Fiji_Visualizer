package edu.abc.berkeley;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
		dir.mkdirs();
		StringBuilder reversed = new StringBuilder("[");
		for (int d = shape.length - 1; d >= 0; d--) reversed.append(d).append(d > 0 ? ", " : "]");
		String codecs = (order == 'F' ? "{\"name\": \"transpose\", \"configuration\": {\"order\": " + reversed + "}}, " : "")
			+ "{\"name\": \"bytes\", \"configuration\": {\"endian\": \"little\"}}";
		StringBuilder json = new StringBuilder("{\"zarr_format\": 3, \"node_type\": \"array\", \"shape\": " + list(shape)
			+ ", \"data_type\": \"uint16\", \"chunk_grid\": {\"name\": \"regular\", \"configuration\": {\"chunk_shape\": " + list(chunks)
			+ "}}, \"chunk_key_encoding\": {\"name\": \"default\", \"configuration\": {\"separator\": \"/\"}}, \"fill_value\": 0, \"codecs\": ["
			+ codecs + "], \"attributes\": {}");
		if (names != null) {
			json.append(", \"dimension_names\": [");
			for (int d = 0; d < names.length; d++)
				json.append(d > 0 ? ", " : "").append(names[d] == null ? "null" : "\"" + names[d] + "\"");
			json.append("]");
		}
		write(new File(dir, "zarr.json"), json.append("}").toString());
		writeChunks(dir, "c/", shape, chunks, order);
		return dir;
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
		int n = shape.length;
		long[] grid = new long[n];
		long count = 1;
		for (int d = 0; d < n; d++) {
			grid[d] = (shape[d] + chunks[d] - 1) / chunks[d];
			count *= grid[d];
		}
		long[] g = new long[n];
		for (long k = 0; k < count; k++) {
			long rem = k;
			StringBuilder key = new StringBuilder(prefix);
			for (int d = n - 1; d >= 0; d--) {
				g[d] = rem % grid[d];
				rem /= grid[d];
			}
			for (int d = 0; d < n; d++) key.append(d > 0 ? "/" : "").append(g[d]);
			File f = new File(dir, key.toString());
			f.getParentFile().mkdirs();
			Files.write(f.toPath(), chunk(shape, chunks, g, order));
		}
	}

	/** One full-size chunk (edge chunks padded with 0), in C or F order. */
	private static byte[] chunk(long[] shape, long[] chunks, long[] g, char order) {
		int n = shape.length;
		long elements = 1;
		for (long c : chunks) elements *= c;
		ByteBuffer b = ByteBuffer.allocate((int) elements * 2).order(ByteOrder.LITTLE_ENDIAN);
		long[] local = new long[n], index = new long[n];
		for (long k = 0; k < elements; k++) {
			long rem = k;
			if (order == 'C') {
				for (int d = n - 1; d >= 0; d--) {
					local[d] = rem % chunks[d];
					rem /= chunks[d];
				}
			}
			else {
				for (int d = 0; d < n; d++) {
					local[d] = rem % chunks[d];
					rem /= chunks[d];
				}
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
