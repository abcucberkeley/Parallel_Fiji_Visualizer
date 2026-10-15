package edu.abc.berkeley;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What a folder is, by its Zarr metadata: a folder is a Zarr node because of
 * the metadata file in it, whatever its name. A ".zarray" file makes it a Zarr
 * v2 array, a "zarr.json" file a Zarr v3 array or group (by its node_type) and
 * a ".zgroup" file a Zarr v2 group. When a folder has more than one, ".zarray"
 * wins, then "zarr.json", the order cpp-zarr reads them in.
 */
public final class ZarrMetadata {

	public static final String V2_ARRAY = ".zarray", V2_GROUP = ".zgroup", V2_ATTRIBUTES = ".zattrs", V3 = "zarr.json";

	/** The folder. */
	public final File dir;
	/** The Zarr format: 2 or 3. */
	public final int format;
	/** An array, or else a group. */
	public final boolean array;
	/** The node's attributes (v2: .zattrs; v3: zarr.json's "attributes"); empty if it has none. */
	public final Map<String, Object> attributes;
	/** The array's .zarray or zarr.json; for a v2 group its .zgroup. */
	private final Map<String, Object> meta;

	private ZarrMetadata(File dir, int format, boolean array, Map<String, Object> meta, Map<String, Object> attributes) {
		this.dir = dir;
		this.format = format;
		this.array = array;
		this.meta = meta;
		this.attributes = attributes != null ? attributes : Collections.<String, Object>emptyMap();
	}

	/**
	 * Whether dir is a Zarr array or group of either format. Only looks for the
	 * metadata files, so it is cheap enough for every entry of a folder.
	 */
	public static boolean isZarr(File dir) {
		return new File(dir, V2_ARRAY).isFile() || new File(dir, V3).isFile() || new File(dir, V2_GROUP).isFile();
	}

	/**
	 * Whether dir is an image the plugin can open: a Zarr array, or a group with
	 * OME-Zarr metadata (an image's resolution levels, or a bioformats2raw
	 * container of images). Plain groups are not; they hold arrays to open.
	 */
	public static boolean isImage(File dir) {
		if (!isZarr(dir)) return false;
		// A v2 array needs no parsing (a folder may hold thousands of them)
		if (new File(dir, V2_ARRAY).isFile()) return true;
		try {
			ZarrMetadata m = read(dir);
			return m.array || m.multiscales() != null || m.isBioformats2raw();
		}
		catch (IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * The metadata of dir, or null when dir is not a Zarr node.
	 *
	 * @throws IllegalArgumentException when its metadata cannot be read
	 */
	public static ZarrMetadata read(File dir) {
		File zarray = new File(dir, V2_ARRAY), zarrJson = new File(dir, V3), zgroup = new File(dir, V2_GROUP);
		if (zarray.isFile() || (!zarrJson.isFile() && zgroup.isFile())) {
			boolean array = zarray.isFile();
			Map<String, Object> meta = readObject(array ? zarray : zgroup);
			File zattrs = new File(dir, V2_ATTRIBUTES);
			Map<String, Object> attributes = zattrs.isFile() ? readObject(zattrs) : null;
			return new ZarrMetadata(dir, 2, array, meta, attributes);
		}
		if (zarrJson.isFile()) {
			Map<String, Object> meta = readObject(zarrJson);
			String node = Json.string(meta.get("node_type"));
			if (!"array".equals(node) && !"group".equals(node))
				throw new IllegalArgumentException(dir.getName() + "'s zarr.json is not a Zarr v3 array or group (its node_type is " + meta.get("node_type") + ")");
			return new ZarrMetadata(dir, 3, "array".equals(node), meta, Json.object(meta.get("attributes")));
		}
		return null;
	}

	private static Map<String, Object> readObject(File f) {
		String text;
		try {
			text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Cannot read " + f.getPath() + ": " + e.getMessage(), e);
		}
		Map<String, Object> m;
		try {
			m = Json.object(Json.parse(text));
		}
		catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(f.getPath() + " is not valid Zarr metadata: " + e.getMessage(), e);
		}
		if (m == null) throw new IllegalArgumentException(f.getPath() + " is not valid Zarr metadata: it is not a JSON object");
		return m;
	}

	// --- Arrays ---

	/** The array's shape; null for a group or when its metadata has none. */
	public long[] shape() {
		return array ? Json.longs(meta.get("shape")) : null;
	}

	/**
	 * The array's chunk shape (v3: the chunk grid's, which is the shard shape of
	 * a sharded array); null when unknown.
	 */
	public long[] chunks() {
		if (!array) return null;
		if (format == 2) return Json.longs(meta.get("chunks"));
		Map<String, Object> grid = Json.object(meta.get("chunk_grid"));
		Map<String, Object> config = grid == null ? null : Json.object(grid.get("configuration"));
		return config == null ? null : Json.longs(config.get("chunk_shape"));
	}

	/**
	 * The order chunks are stored in: 'C' (last axis fastest) or 'F' (first
	 * axis fastest). A v3 array is 'F' when its transpose codec reverses the
	 * axes and 'C' otherwise, as cpp-zarr reads it.
	 */
	public char order() {
		if (format == 2) return "F".equals(Json.string(meta.get("order"))) ? 'F' : 'C';
		long[] shape = shape();
		List<Object> codecs = Json.list(meta.get("codecs"));
		// A sharded array's chunks are encoded by the codecs inside its sharding codec
		if (codecs != null && !codecs.isEmpty() && "sharding_indexed".equals(codecName(codecs.get(0)))) {
			Map<String, Object> config = codecConfiguration(codecs.get(0));
			codecs = config == null ? null : Json.list(config.get("codecs"));
		}
		if (codecs == null || codecs.isEmpty() || shape == null || shape.length < 2
			|| !"transpose".equals(codecName(codecs.get(0)))) return 'C';
		Map<String, Object> config = codecConfiguration(codecs.get(0));
		Object order = config == null ? null : config.get("order");
		if ("F".equals(order)) return 'F';
		long[] axes = Json.longs(order);
		if (axes == null || axes.length != shape.length) return 'C';
		for (int d = 0; d < axes.length; d++)
			if (axes[d] != axes.length - 1 - d) return 'C';
		return 'F';
	}

	/** A v3 codec's name: an object's "name", or the codec itself when it is just a name. */
	private static String codecName(Object codec) {
		Map<String, Object> m = Json.object(codec);
		return m != null ? Json.string(m.get("name")) : Json.string(codec);
	}

	private static Map<String, Object> codecConfiguration(Object codec) {
		Map<String, Object> m = Json.object(codec);
		return m == null ? null : Json.object(m.get("configuration"));
	}

	/**
	 * The array's axis names: v3's dimension_names, or the
	 * _ARRAY_DIMENSIONS attribute xarray gives v2 arrays. null when it has
	 * none; a name may be null.
	 */
	public String[] dimensionNames() {
		if (!array) return null;
		List<Object> names = Json.list(format == 3 ? meta.get("dimension_names") : attributes.get("_ARRAY_DIMENSIONS"));
		if (names == null) return null;
		String[] v = new String[names.size()];
		boolean any = false;
		for (int d = 0; d < v.length; d++) {
			v[d] = Json.string(names.get(d));
			any |= v[d] != null && !v[d].isEmpty();
		}
		return any ? v : null;
	}

	// --- Groups (OME-Zarr) ---

	/**
	 * The OME-Zarr attributes: those under "ome" (OME-Zarr 0.5, on Zarr v3) or
	 * else the attributes themselves (0.4 and earlier).
	 */
	Map<String, Object> ome() {
		Map<String, Object> ome = Json.object(attributes.get("ome"));
		return ome != null ? ome : attributes;
	}

	/** The group's first OME-Zarr multiscale image (its resolution levels), or null. */
	public Map<String, Object> multiscales() {
		if (array) return null;
		List<Object> list = Json.list(ome().get("multiscales"));
		return list == null || list.isEmpty() ? null : Json.object(list.get(0));
	}

	/** Whether the group is a bioformats2raw container, its images in subgroups "0", "1", ... */
	public boolean isBioformats2raw() {
		return !array && ome().get("bioformats2raw.layout") != null;
	}
}
