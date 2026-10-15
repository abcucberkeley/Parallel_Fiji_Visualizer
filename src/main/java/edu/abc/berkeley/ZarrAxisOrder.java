package edu.abc.berkeley;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import ij.Prefs;

/**
 * The axis orders to read Zarr arrays in when their metadata does not name
 * their axes. An order names each of an array's axes, in the array's order,
 * with the letters x, y, z, c and t: "tzyxc" is an array of shape (time, z,
 * y, x, channel). It applies to arrays with as many axes as it has letters, so
 * several can be given, separated by commas: "yxz, tzyxc". "Auto" (the
 * default) lets the plugin guess. Axes the metadata names are always read as
 * named. Kept in the ImageJ preferences, so macros can set it too:
 * {@code call("ij.Prefs.set", "pfv.zarrAxisOrder", "tzyxc");}
 */
public final class ZarrAxisOrder {

	public static final String KEY = "pfv.zarrAxisOrder";
	public static final String AUTO = "Auto";
	/** The choices the plugin window offers; any other order can be typed. */
	public static final String[] PRESETS = { AUTO, "tzyxc", "tczyx", "yxzct", "zyx", "yxz" };

	private ZarrAxisOrder() {
	}

	/** The current setting, normalized ("Auto" or orders such as "yxz, tzyxc"). */
	public static String get() {
		String s = Prefs.get(KEY, AUTO);
		try {
			return normalize(s);
		}
		catch (IllegalArgumentException e) {
			return AUTO;
		}
	}

	/**
	 * Changes the setting.
	 *
	 * @throws IllegalArgumentException for orders that cannot be used; the message says why
	 */
	public static void set(String orders) {
		Prefs.set(KEY, normalize(orders));
	}

	/** orders in a standard form: "Auto", or the orders by number of axes, separated by ", ". */
	public static String normalize(String orders) {
		Map<Integer, String> parsed = parse(orders);
		if (parsed.isEmpty()) return AUTO;
		StringBuilder b = new StringBuilder();
		for (String o : parsed.values()) b.append(b.length() == 0 ? "" : ", ").append(o);
		return b.toString();
	}

	/**
	 * The {x, y, z, c, t} axes (see {@link ZarrImage}) orders gives an array
	 * with n axes, or null when it has no order with n letters.
	 */
	static int[] roles(String orders, int n) {
		String order = parse(orders).get(n);
		return order == null ? null : roles(order);
	}

	/** The {x, y, z, c, t} axes of one valid order such as "tzyxc". */
	static int[] roles(String order) {
		int[] roles = { -1, -1, -1, -1, -1 };
		for (int d = 0; d < order.length(); d++)
			roles["xyzct".indexOf(order.charAt(d))] = d;
		return roles;
	}

	/** The orders by their number of axes; empty for Auto. */
	static Map<Integer, String> parse(String orders) {
		Map<Integer, String> parsed = new TreeMap<Integer, String>();
		String s = orders == null ? "" : orders.trim().toLowerCase(Locale.ROOT);
		if (s.isEmpty() || s.equals("auto")) return parsed;
		for (String order : s.split("[\\s,;]+")) {
			if (order.isEmpty()) continue;
			if (!order.matches("[xyzct]+"))
				throw new IllegalArgumentException("\"" + order + "\" is not an axis order: write the axes with the letters x, y, z, c and t, e.g. tzyxc");
			for (int i = 0; i < order.length(); i++)
				if (order.indexOf(order.charAt(i)) != i)
					throw new IllegalArgumentException("The axis order \"" + order + "\" has " + order.charAt(i) + " twice");
			if (order.indexOf('x') < 0 || order.indexOf('y') < 0)
				throw new IllegalArgumentException("The axis order \"" + order + "\" needs both an x and a y axis");
			if (parsed.put(order.length(), order) != null)
				throw new IllegalArgumentException("Give one axis order per number of axes: there are two for " + order.length() + " axes");
		}
		return parsed;
	}
}
