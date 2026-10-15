package edu.abc.berkeley;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON parser for Zarr metadata (.zarray, .zattrs, .zgroup and
 * zarr.json). Objects become Maps, arrays Lists, numbers Doubles, strings
 * Strings, true and false Booleans and null null. NaN, Infinity and
 * -Infinity, which some Zarr writers put in fill values, are read as numbers.
 */
final class Json {

	private final String s;
	private int i;

	private Json(String s) {
		this.s = s;
	}

	/** @throws IllegalArgumentException when text is not valid JSON */
	static Object parse(String text) {
		Json p = new Json(text);
		p.space();
		Object v = p.value();
		p.space();
		if (p.i != p.s.length()) throw p.error("unexpected text after the value");
		return v;
	}

	// --- Typed access: null when the value is missing or of another type ---

	@SuppressWarnings("unchecked")
	static Map<String, Object> object(Object o) {
		return o instanceof Map ? (Map<String, Object>) o : null;
	}

	@SuppressWarnings("unchecked")
	static List<Object> list(Object o) {
		return o instanceof List ? (List<Object>) o : null;
	}

	static String string(Object o) {
		return o instanceof String ? (String) o : null;
	}

	/** A list of whole numbers as longs. */
	static long[] longs(Object o) {
		List<Object> l = list(o);
		if (l == null) return null;
		long[] v = new long[l.size()];
		for (int k = 0; k < v.length; k++) {
			if (!(l.get(k) instanceof Double)) return null;
			double d = (Double) l.get(k);
			if (d != Math.rint(d) || Double.isInfinite(d)) return null;
			v[k] = (long) d;
		}
		return v;
	}

	/** A list of numbers as doubles. */
	static double[] doubles(Object o) {
		List<Object> l = list(o);
		if (l == null) return null;
		double[] v = new double[l.size()];
		for (int k = 0; k < v.length; k++) {
			if (!(l.get(k) instanceof Double)) return null;
			v[k] = (Double) l.get(k);
		}
		return v;
	}

	// --- Parsing ---

	private IllegalArgumentException error(String what) {
		return new IllegalArgumentException("Invalid JSON at character " + i + ": " + what);
	}

	private void space() {
		while (i < s.length()) {
			char c = s.charAt(i);
			if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
			i++;
		}
	}

	private Object value() {
		if (i >= s.length()) throw error("unexpected end");
		switch (s.charAt(i)) {
			case '{': return object();
			case '[': return array();
			case '"': return string();
			case 't': return word("true", Boolean.TRUE);
			case 'f': return word("false", Boolean.FALSE);
			case 'n': return word("null", null);
			case 'N': return word("NaN", Double.NaN);
			case 'I': return word("Infinity", Double.POSITIVE_INFINITY);
			default: return number();
		}
	}

	private Object word(String w, Object v) {
		if (!s.startsWith(w, i)) throw error("unexpected character");
		i += w.length();
		return v;
	}

	private Map<String, Object> object() {
		Map<String, Object> m = new LinkedHashMap<String, Object>();
		i++; // {
		space();
		if (i < s.length() && s.charAt(i) == '}') {
			i++;
			return m;
		}
		for (;;) {
			space();
			if (i >= s.length() || s.charAt(i) != '"') throw error("expected a name");
			String key = string();
			space();
			if (i >= s.length() || s.charAt(i) != ':') throw error("expected ':'");
			i++;
			space();
			m.put(key, value());
			space();
			if (i >= s.length()) throw error("unexpected end");
			char c = s.charAt(i++);
			if (c == '}') return m;
			if (c != ',') throw error("expected ',' or '}'");
		}
	}

	private List<Object> array() {
		List<Object> l = new ArrayList<Object>();
		i++; // [
		space();
		if (i < s.length() && s.charAt(i) == ']') {
			i++;
			return l;
		}
		for (;;) {
			space();
			l.add(value());
			space();
			if (i >= s.length()) throw error("unexpected end");
			char c = s.charAt(i++);
			if (c == ']') return l;
			if (c != ',') throw error("expected ',' or ']'");
		}
	}

	private String string() {
		StringBuilder b = new StringBuilder();
		i++; // "
		for (;;) {
			if (i >= s.length()) throw error("unterminated string");
			char c = s.charAt(i++);
			if (c == '"') return b.toString();
			if (c != '\\') {
				b.append(c);
				continue;
			}
			if (i >= s.length()) throw error("unterminated string");
			char e = s.charAt(i++);
			switch (e) {
				case '"': case '\\': case '/': b.append(e); break;
				case 'b': b.append('\b'); break;
				case 'f': b.append('\f'); break;
				case 'n': b.append('\n'); break;
				case 'r': b.append('\r'); break;
				case 't': b.append('\t'); break;
				case 'u':
					if (i + 4 > s.length()) throw error("bad \\u escape");
					try {
						b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
					}
					catch (NumberFormatException x) {
						throw error("bad \\u escape");
					}
					i += 4;
					break;
				default: throw error("bad escape");
			}
		}
	}

	private Double number() {
		int start = i;
		if (i < s.length() && s.charAt(i) == '-') {
			i++;
			if (s.startsWith("Infinity", i)) {
				i += 8;
				return Double.NEGATIVE_INFINITY;
			}
		}
		while (i < s.length()) {
			char c = s.charAt(i);
			if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
			else break;
		}
		if (i == start) throw error("unexpected character");
		try {
			return Double.valueOf(s.substring(start, i));
		}
		catch (NumberFormatException x) {
			i = start;
			throw error("bad number");
		}
	}
}
