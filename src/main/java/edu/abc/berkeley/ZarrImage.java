package edu.abc.berkeley;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ij.ImagePlus;
import ij.measure.Calibration;

/**
 * A Zarr array as an ImageJ image: the array to read (for an OME-Zarr image,
 * its full-resolution level), which of its axes are the image's x, y, z,
 * channels and time points, its data type, and the pixel size its metadata
 * gives.
 * <p>
 * Axes are named by the array's dimension_names (Zarr v3), its
 * _ARRAY_DIMENSIONS attribute (xarray) or the OME-Zarr image it belongs to.
 * Arrays without names are read in the order the user set for their number of
 * axes ({@link ZarrAxisOrder}), or else in a guessed one:
 * <ul>
 * <li>Zarr v2 arrays in MATLAB's order (y, x, z, c, t), the way PetaKit5D and
 * this plugin write them, or in NumPy's (t, c, z, y, x) when their chunks
 * point to it: cpp-zarr's writers give the axes beyond the spatial ones chunks
 * of 1, which MATLAB puts last and NumPy first. With no such sign, 4D and 5D
 * arrays stored in C order are read in NumPy's order.
 * <li>Zarr v3 arrays as (t, z, y, x, c), the channels last, when the last axis
 * is small enough to be channels (8 or fewer), and otherwise in OME-Zarr's
 * order (t, c, z, y, x).
 * </ul>
 * Either way an array leaves out the axes it does not have: a 4D array is
 * (y, x, z, t), (t, z, y, x) or (z, y, x, c).
 */
public final class ZarrImage {

	/** Image axes: the indices of {@link #axes()} and of the boxes {@link #read} takes. */
	public static final int X = 0, Y = 1, Z = 2, C = 3, T = 4;

	/** The array's folder. */
	public final String path;
	/** What was opened (the folder's name), for the window title. */
	public final String title;
	/** The numpy-style data type, e.g. "&lt;u2", and its bits per element. */
	public final String dtype;
	public final int bits;
	/** 'u', 'i', 'f', 'b' (bool) or 'h' (bfloat16). */
	private final char kind;
	private final long[] shape;
	private final long[] chunks;
	private final int[] axes;
	/** Pixel size per image axis (NaN when unknown) and units, from OME-Zarr. */
	private final double[] scale = { Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN };
	private String spaceUnit, timeUnit;

	/**
	 * The image in folder dir: a Zarr array, an OME-Zarr image (its
	 * full-resolution level is read), a bioformats2raw container (its first
	 * image), or a group holding just one of these.
	 *
	 * @throws IllegalArgumentException when dir holds nothing the plugin can show
	 */
	public static ZarrImage open(File dir) {
		ZarrMetadata m = ZarrMetadata.read(dir);
		if (m == null)
			throw new IllegalArgumentException(dir.getName() + " is not a Zarr array: it has no Zarr metadata (.zarray, zarr.json or .zgroup)");
		return resolve(dir.getName(), m, 0);
	}

	private static ZarrImage resolve(String title, ZarrMetadata m, int depth) {
		if (m.array) {
			// A level of an OME-Zarr image (e.g. its folder "0") has the image's axes
			Ome ome = null;
			File parent = m.dir.getAbsoluteFile().getParentFile();
			if (parent != null && ZarrMetadata.isZarr(parent)) {
				try {
					ZarrMetadata pm = ZarrMetadata.read(parent);
					if (pm.multiscales() != null) ome = Ome.of(pm.multiscales(), m.dir.getName());
				}
				catch (IllegalArgumentException e) {
					// unreadable parent metadata: the array is read without it
				}
			}
			return new ZarrImage(title, m, ome);
		}
		Map<String, Object> multiscales = m.multiscales();
		if (multiscales != null) {
			Ome ome = Ome.of(multiscales, null);
			if (ome == null) throw new IllegalArgumentException(title + " is an OME-Zarr image without resolution levels");
			ZarrMetadata level = ZarrMetadata.read(new File(m.dir, ome.path));
			if (level == null || !level.array)
				throw new IllegalArgumentException(title + " is an OME-Zarr image whose full-resolution level (" + ome.path + ") is not a Zarr array");
			return new ZarrImage(title, level, ome);
		}
		if (m.isBioformats2raw() && depth < 4) {
			ZarrMetadata first = ZarrMetadata.read(new File(m.dir, "0"));
			if (first != null) return resolve(title, first, depth + 1);
		}
		List<String> images = new ArrayList<String>();
		String[] names = m.dir.list();
		if (names != null) {
			Arrays.sort(names);
			for (String n : names)
				if (ZarrMetadata.isImage(new File(m.dir, n))) images.add(n);
		}
		if (images.size() == 1 && depth < 4) return resolve(title, ZarrMetadata.read(new File(m.dir, images.get(0))), depth + 1);
		if (images.isEmpty()) throw new IllegalArgumentException(title + " is a Zarr group with no images in it");
		StringBuilder list = new StringBuilder();
		for (int i = 0; i < images.size() && i < 10; i++) list.append(i == 0 ? "" : ", ").append(images.get(i));
		if (images.size() > 10) list.append(", ...");
		throw new IllegalArgumentException(title + " is a Zarr group, not an image. Open one of the images in it: " + list);
	}

	private ZarrImage(String title, ZarrMetadata m, Ome ome) {
		this.title = title;
		this.path = m.dir.getPath();
		// cpp-zarr's view of the array: it must be able to read it
		ParallelReadNative reader = new ParallelReadNative();
		this.shape = reader.getZarrDims(path);
		this.dtype = reader.getZarrDtype(path);
		this.bits = (int) reader.getZarrDataType(path);
		this.kind = dtypeKind(dtype);
		if (kind == 'c') throw new IllegalArgumentException(title + " holds complex numbers (" + dtype + "), which ImageJ cannot show");
		if (kind == '?') throw new IllegalArgumentException(title + " has a data type the plugin cannot show: " + dtype);
		long[] c = m.chunks();
		this.chunks = c != null && c.length == shape.length ? c : null;

		String[] names = m.dimensionNames(), types = null;
		if (ome != null && ome.names != null && ome.names.length == shape.length) {
			names = ome.names;
			types = ome.types;
		}
		if (names != null && names.length != shape.length) names = null;
		this.axes = axisRoles(shape, chunks, m.order(), m.format, names, types, ZarrAxisOrder.get(), title);

		for (long s : shape)
			if (s == 0) throw new IllegalArgumentException(title + " is empty: its shape is " + Arrays.toString(shape));
		if (width() * height() > Integer.MAX_VALUE - 8 || channels() * depth() * frames() > Integer.MAX_VALUE)
			throw new IllegalArgumentException(title + " is too large for ImageJ: its shape is " + Arrays.toString(shape));

		if (ome != null && ome.scale != null && ome.scale.length == shape.length)
			for (int role = 0; role < 5; role++)
				if (axes[role] >= 0) scale[role] = ome.scale[axes[role]];
		if (ome != null && ome.units != null && ome.units.length == shape.length) {
			for (int role = X; role <= Z && spaceUnit == null; role++)
				if (axes[role] >= 0) spaceUnit = ome.units[axes[role]];
			if (axes[T] >= 0) timeUnit = ome.units[axes[T]];
		}
	}

	// --- Size ---

	/** The array's shape. */
	public long[] shape() {
		return shape.clone();
	}

	/** The array's axes that are the image's x, y, z, c and t; -1 for z, c and t when it has none. */
	public int[] axes() {
		return axes.clone();
	}

	/** The image's size along x, y, z, c or t. */
	public long extent(int axis) {
		return axes[axis] >= 0 ? shape[axes[axis]] : 1;
	}

	public long width() { return extent(X); }
	public long height() { return extent(Y); }
	public long depth() { return extent(Z); }
	public long channels() { return extent(C); }
	public long frames() { return extent(T); }

	/** Bits per pixel ImageJ shows: 16-bit floats and wider types become 32-bit float. */
	public int displayBits() {
		return isFloat16() ? 32 : Math.min(bits, 32);
	}

	private boolean isFloat16() {
		return (kind == 'f' && bits == 16) || kind == 'h';
	}

	// --- Reading ---

	/**
	 * Reads the box [from, to) of the image, given along x, y, z, c and t (the
	 * axes the image does not have are [0, 1)), as ImageJ pixel arrays in its
	 * czt order: channel fastest, then z, then time.
	 */
	public Object[] read(long[] from, long[] to) {
		long[] start = new long[shape.length], end = new long[shape.length];
		Arrays.fill(end, 1);
		for (int role = 0; role < 5; role++) {
			if (axes[role] < 0) continue;
			start[axes[role]] = from[role];
			end[axes[role]] = to[role];
		}
		ParallelReadNative r = new ParallelReadNative();
		long planes = ParallelReadNative.planes(start, end, axes), n = ParallelReadNative.planeElements(start, end, axes);
		if (isFloat16()) return r.parallelReadZarrFLOAT16(path, start, end, axes, kind == 'h', ParallelReadNative.newFloatPlanes(planes, n));
		switch (bits) {
			case 8:
				// Signed bytes are shifted to 0-255 (see calibrate); bool reads as 0 and 1
				if (kind == 'i') return r.parallelReadZarrINT8(path, start, end, axes, ParallelReadNative.newBytePlanes(planes, n));
				return r.parallelReadZarrUINT8(path, start, end, axes, ParallelReadNative.newBytePlanes(planes, n));
			case 16:
				if (kind == 'i') return r.parallelReadZarrINT16(path, start, end, axes, ParallelReadNative.newShortPlanes(planes, n));
				return r.parallelReadZarrUINT16(path, start, end, axes, ParallelReadNative.newShortPlanes(planes, n));
			case 32:
				if (kind == 'i') return r.parallelReadZarrINT32(path, start, end, axes, ParallelReadNative.newFloatPlanes(planes, n));
				if (kind == 'u') return r.parallelReadZarrUINT32(path, start, end, axes, ParallelReadNative.newFloatPlanes(planes, n));
				return r.parallelReadZarrFLOAT(path, start, end, axes, ParallelReadNative.newFloatPlanes(planes, n));
			case 64:
				// ImageJ has no double or 64-bit integer type: all become float
				if (kind == 'i') return r.parallelReadZarrINT64(path, start, end, axes, ParallelReadNative.newFloatPlanes(planes, n));
				if (kind == 'u') return r.parallelReadZarrUINT64(path, start, end, axes, ParallelReadNative.newFloatPlanes(planes, n));
				return r.parallelReadZarrDOUBLE(path, start, end, axes, ParallelReadNative.newFloatPlanes(planes, n));
			default:
				throw new IllegalArgumentException(title + " has a data type the plugin cannot show: " + dtype);
		}
	}

	/** {@link #read(long[], long[])}, showing the progress bar labeled with what. */
	public Object[] read(long[] from, long[] to, String what) {
		long elements = 1, units = 1;
		for (int role = 0; role < 5; role++) {
			long n = to[role] - from[role];
			elements *= n;
			// Reads parallelize over chunks; without a chunk shape assume the 256
			// cubes the plugin writes
			long chunk = axes[role] < 0 ? 1 : chunks != null ? Math.max(1, chunks[axes[role]]) : role <= Z ? 256 : 1;
			units *= (n + chunk - 1) / chunk;
		}
		ProgressEstimator progress = ProgressEstimator.begin("readZarr", elements * Math.max(1, bits / 8), units, what);
		try {
			Object[] planes = read(from, to);
			progress.finish();
			return planes;
		}
		finally {
			progress.abort();
		}
	}

	/**
	 * Gives imp the image's pixel size (from OME-Zarr) and, for signed
	 * integers, the calibration that maps the shifted values back.
	 */
	public void calibrate(ImagePlus imp) {
		Calibration cal = imp.getLocalCalibration();
		if (scale[X] > 0) cal.pixelWidth = scale[X];
		if (scale[Y] > 0) cal.pixelHeight = scale[Y];
		if (scale[Z] > 0) cal.pixelDepth = scale[Z];
		if (scale[T] > 0) cal.frameInterval = scale[T];
		if (spaceUnit != null) cal.setUnit(spaceUnit(spaceUnit));
		if (timeUnit != null) cal.setTimeUnit(timeUnit(timeUnit));
		if (kind == 'i' && bits == 8) cal.setFunction(Calibration.STRAIGHT_LINE, new double[] { -128.0, 1.0 }, "gray value");
		if (kind == 'i' && bits == 16) cal.setSigned16BitCalibration();
	}

	private static String spaceUnit(String unit) {
		switch (unit.toLowerCase(Locale.ROOT)) {
			case "micrometer": case "micron": case "um": case "µm": return "micron";
			case "nanometer": return "nm";
			case "millimeter": return "mm";
			case "centimeter": return "cm";
			case "meter": return "m";
			case "angstrom": return "Å";
			default: return unit;
		}
	}

	private static String timeUnit(String unit) {
		switch (unit.toLowerCase(Locale.ROOT)) {
			case "second": return "sec";
			case "millisecond": return "ms";
			case "microsecond": return "µs";
			case "nanosecond": return "ns";
			case "minute": return "min";
			case "hour": return "hour";
			default: return unit;
		}
	}

	// --- Axes ---

	/**
	 * The {x, y, z, c, t} axes of an array (see the class comment). names (or
	 * else OME-Zarr axis types) name the axes; without both x and y among them
	 * they are no help. Named axes ImageJ has no place for go to its free
	 * hyperstack dimensions (z, then time, then channels) unless they have
	 * extent 1. userOrders is the user's {@link ZarrAxisOrder} setting.
	 *
	 * @throws IllegalArgumentException for arrays ImageJ cannot show
	 */
	static int[] axisRoles(long[] shape, long[] chunks, char order, int format, String[] names, String[] types,
	                       String userOrders, String title) {
		final int n = shape.length;
		if (n < 2) throw new IllegalArgumentException(title + " is a " + n + "D array; only images (2D to 5D arrays) can be opened");
		if (names != null) {
			int[] named = namedRoles(shape, names, types, title);
			if (named != null) return named;
		}
		int[] user = ZarrAxisOrder.roles(userOrders, n);
		if (user != null) return user;
		if (n > 5) {
			// Set the axes of extent 1 aside: what is left may be an image
			List<Integer> kept = new ArrayList<Integer>();
			for (int d = 0; d < n; d++)
				if (shape[d] > 1) kept.add(d);
			if (kept.size() > 5 || kept.size() < 2)
				throw new IllegalArgumentException(title + " is a " + n + "D array without axis names; only images (2D to 5D arrays) can be opened");
			long[] subShape = new long[kept.size()], subChunks = chunks == null ? null : new long[kept.size()];
			for (int k = 0; k < kept.size(); k++) {
				subShape[k] = shape[kept.get(k)];
				if (chunks != null) subChunks[k] = chunks[kept.get(k)];
			}
			int[] roles = axisRoles(subShape, subChunks, order, format, null, null, userOrders, title);
			for (int r = 0; r < 5; r++)
				if (roles[r] >= 0) roles[r] = kept.get(roles[r]);
			return roles;
		}
		return ZarrAxisOrder.roles(guessedOrder(shape, chunks, order, format));
	}

	// The guesses by number of axes (see the class comment)
	private static final String[] MATLAB = { null, null, "yx", "yxz", "yxzt", "yxzct" };
	private static final String[] NUMPY = { null, null, "yx", "zyx", "tzyx", "tczyx" };
	private static final String[] CHANNELS_LAST = { null, null, "yx", "yxc", "zyxc", "tzyxc" };

	/** The order an array of 2 to 5 axes without names is guessed to have, e.g. "yxz". */
	static String guessedOrder(long[] shape, long[] chunks, char order, int format) {
		final int n = shape.length;
		if (format == 3) return n >= 3 && shape[n - 1] <= 8 ? CHANNELS_LAST[n] : NUMPY[n];
		boolean leading = chunks != null && chunks[0] == 1, trailing = chunks != null && chunks[n - 1] == 1;
		boolean numpy = leading != trailing ? leading : n > 3 && order == 'C';
		return numpy ? NUMPY[n] : MATLAB[n];
	}

	private static int[] namedRoles(long[] shape, String[] names, String[] types, String title) {
		int[] roles = { -1, -1, -1, -1, -1 };
		List<Integer> others = new ArrayList<Integer>();
		for (int d = 0; d < shape.length; d++) {
			int r = role(names[d], types == null ? null : types[d]);
			if (r < 0) {
				if (shape[d] > 1) others.add(d);
				continue;
			}
			// The same axis twice: the names say nothing reliable
			if (roles[r] >= 0) return null;
			roles[r] = d;
		}
		if (roles[X] < 0 || roles[Y] < 0) return null;
		for (int d : others) {
			int r = roles[Z] < 0 ? Z : roles[T] < 0 ? T : roles[C] < 0 ? C : -1;
			if (r < 0)
				throw new IllegalArgumentException(title + " has more axes than ImageJ can show (x, y, z, channels and time): "
					+ Arrays.toString(names) + " of sizes " + Arrays.toString(shape));
			roles[r] = d;
		}
		return roles;
	}

	/** The image axis an axis name (or OME-Zarr axis type) stands for, or -1. */
	private static int role(String name, String type) {
		String s = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
		switch (s) {
			case "x": return X;
			case "y": return Y;
			case "z": return Z;
			case "c": case "ch": case "channel": case "channels": return C;
			case "t": case "time": case "frame": case "frames": return T;
			default:
				if ("channel".equalsIgnoreCase(type)) return C;
				if ("time".equalsIgnoreCase(type)) return T;
				return -1;
		}
	}

	private static final Pattern DTYPE = Pattern.compile("[<>|=]?([biufc])\\d+");

	/**
	 * 'u', 'i', 'f', 'b' (bool), 'c' (complex) or 'h' (bfloat16) for cpp-zarr's
	 * numpy-style dtype names such as "<u2", "|u1", ">f4" or "bfloat16"; Zarr
	 * v3 types get the same names. '?' for anything else.
	 */
	static char dtypeKind(String dtype) {
		if (dtype == null) return '?';
		if (dtype.endsWith("bfloat16")) return 'h';
		Matcher m = DTYPE.matcher(dtype);
		return m.matches() ? m.group(1).charAt(0) : '?';
	}

	// --- OME-Zarr ---

	/** An OME-Zarr image's axes and the pixel size of one of its resolution levels. */
	static final class Ome {
		/** The level's folder, relative to the image. */
		final String path;
		/** Per axis; null when the metadata does not say. */
		final String[] names, types, units;
		final double[] scale;

		private Ome(String path, String[] names, String[] types, String[] units, double[] scale) {
			this.path = path;
			this.names = names;
			this.types = types;
			this.units = units;
			this.scale = scale;
		}

		/** The level at path (null: the first, which has the full resolution), or null if there is none. */
		static Ome of(Map<String, Object> multiscale, String path) {
			List<Object> datasets = Json.list(multiscale.get("datasets"));
			if (datasets == null) return null;
			Map<String, Object> level = null;
			for (Object o : datasets) {
				Map<String, Object> d = Json.object(o);
				if (d != null && Json.string(d.get("path")) != null && (path == null || path.equals(Json.string(d.get("path"))))) {
					level = d;
					break;
				}
			}
			if (level == null) return null;

			String[] names = null, types = null, units = null;
			List<Object> axes = Json.list(multiscale.get("axes"));
			if (axes != null) {
				// OME-Zarr 0.4 and later: {name, type, unit} objects; 0.3: names
				names = new String[axes.size()];
				types = new String[axes.size()];
				units = new String[axes.size()];
				for (int d = 0; d < names.length; d++) {
					Map<String, Object> a = Json.object(axes.get(d));
					names[d] = a != null ? Json.string(a.get("name")) : Json.string(axes.get(d));
					if (a != null) {
						types[d] = Json.string(a.get("type"));
						units[d] = Json.string(a.get("unit"));
					}
				}
			}
			else {
				// OME-Zarr 0.1 and 0.2 images are always (t, c, z, y, x)
				names = new String[] { "t", "c", "z", "y", "x" };
			}

			// A level's scale, times the scale all levels share (if any)
			double[] scale = scaleOf(level.get("coordinateTransformations"));
			double[] shared = scaleOf(multiscale.get("coordinateTransformations"));
			if (scale == null) scale = shared;
			else if (shared != null && shared.length == scale.length)
				for (int d = 0; d < scale.length; d++) scale[d] *= shared[d];
			return new Ome(Json.string(level.get("path")), names, types, units, scale);
		}

		private static double[] scaleOf(Object transformations) {
			List<Object> list = Json.list(transformations);
			if (list == null) return null;
			for (Object o : list) {
				Map<String, Object> t = Json.object(o);
				if (t != null && "scale".equals(Json.string(t.get("type")))) return Json.doubles(t.get("scale"));
			}
			return null;
		}
	}
}
