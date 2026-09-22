package edu.abc.berkeley;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out the channels and time points of a folder of files from their
 * names, so the sequence can open as a hyperstack with channel and frame
 * sliders. A channel is identified by the tokens {@code Cam<X>}, {@code ch<n>}
 * and {@code <nnn>nm} of the lab's microscope names (any combination of them
 * that differs between files), or by a generic {@code c<n>} token; a time
 * point by the numbers after {@code Iter_} (e.g. {@code Iter_0000_0001}), the
 * {@code _0001t} suffix, or a generic {@code t<n>}. Files that share a channel
 * and time point but differ otherwise (tiles) are stacked along z, in tile
 * order. Detection succeeds only when every channel has the same time points
 * and every channel/time point cell holds the same number of files.
 */
public final class SeriesLayout {

	private static final Pattern CHANNEL = Pattern.compile(
		"Cam[A-Z](?![A-Za-z])|(?<![A-Za-z])ch\\d+|(?<![A-Za-z0-9])\\d+nm(?![A-Za-z])|(?<![A-Za-z0-9])[cC]\\d+(?![A-Za-z0-9])");
	private static final Pattern ITER = Pattern.compile("Iter_(\\d+(?:_\\d+)*)");
	private static final Pattern T_SUFFIX = Pattern.compile("_(\\d+)t(?=\\.|_|$)");
	private static final Pattern GENERIC_T = Pattern.compile("(?<![A-Za-z0-9])[tT](\\d+)(?![A-Za-z0-9])");
	private static final Pattern TILE = Pattern.compile("(\\d+)x_(\\d+)y_(\\d+)z");
	private static final Pattern DIGITS = Pattern.compile("\\d+");

	public final int channels;
	public final int frames;
	/** Files per channel/time point cell (tiles); they are stacked along z. */
	public final int filesPerCell;
	/** One label per channel, e.g. "CamA_ch0_488nm", in channel order. */
	public final String[] channelNames;
	/** The file names in opening order: time point outer, channel inner, then tile. */
	public final String[] order;

	private SeriesLayout(String[] channelNames, int frames, int filesPerCell, String[] order) {
		this.channels = channelNames.length;
		this.frames = frames;
		this.filesPerCell = filesPerCell;
		this.channelNames = channelNames;
		this.order = order;
	}

	/** The layout of these files, or null if they do not describe channels or time points. */
	public static SeriesLayout detect(String[] names) {
		if (names == null || names.length < 2) return null;
		String[] chan = new String[names.length];
		String[] time = new String[names.length];
		int withTime = 0;
		for (int i = 0; i < names.length; i++) {
			chan[i] = channelKey(names[i]);
			time[i] = timeKey(names[i]);
			if (time[i] != null) withTime++;
			else time[i] = "";
		}
		if (withTime != 0 && withTime != names.length) return null; // some files dated, some not

		List<String> channelList = new ArrayList<String>();
		List<String> timeList = new ArrayList<String>();
		Map<String, List<String>> cells = new LinkedHashMap<String, List<String>>();
		for (int i = 0; i < names.length; i++) {
			if (!channelList.contains(chan[i])) channelList.add(chan[i]);
			if (!timeList.contains(time[i])) timeList.add(time[i]);
			String cell = chan[i] + " " + time[i];
			List<String> files = cells.get(cell);
			if (files == null) cells.put(cell, files = new ArrayList<String>());
			files.add(names[i]);
		}
		final int nC = channelList.size(), nT = timeList.size();
		if (nC == 1 && nT == 1) return null;
		if (cells.size() != nC * nT) return null; // a channel is missing a time point
		final int perCell = names.length / cells.size();
		for (List<String> files : cells.values())
			if (files.size() != perCell) return null; // uneven cells

		Collections.sort(channelList, NATURAL);
		Collections.sort(timeList, NATURAL);
		String[] order = new String[names.length];
		int k = 0;
		for (String t : timeList)
			for (String c : channelList) {
				List<String> files = cells.get(c + " " + t);
				Collections.sort(files, TILE_ORDER);
				for (String f : files) order[k++] = f;
			}
		return new SeriesLayout(channelList.toArray(new String[0]), nT, perCell, order);
	}

	/**
	 * Short labels for a set of file names: the tokens (split at "_") that are
	 * identical in every name are dropped, leaving what tells the files apart,
	 * e.g. the camera, time point and tile of a microscope name. ImageJ shows
	 * at most 60 characters of a slice label, and the full names are longer
	 * than that with their distinguishing part at the end. Names that do not
	 * all have the same number of tokens are returned unchanged.
	 */
	public static String[] distinguishingNames(String[] names) {
		String[] out = names.clone();
		if (names.length < 2) return out;
		String[][] tokens = new String[names.length][];
		for (int i = 0; i < names.length; i++) {
			tokens[i] = names[i].split("_");
			if (tokens[i].length != tokens[0].length) return out;
		}
		boolean[] keep = new boolean[tokens[0].length];
		int kept = 0;
		for (int k = 0; k < keep.length; k++) {
			for (int i = 1; i < names.length && !keep[k]; i++)
				if (!tokens[i][k].equals(tokens[0][k])) keep[k] = true;
			if (keep[k]) kept++;
		}
		if (kept == 0) return out;
		for (int i = 0; i < names.length; i++) {
			StringBuilder b = new StringBuilder();
			for (int k = 0; k < keep.length; k++) {
				if (!keep[k]) continue;
				if (b.length() > 0) b.append('_');
				b.append(tokens[i][k]);
			}
			out[i] = b.toString();
		}
		return out;
	}

	/** The channel tokens of a name joined with "_", or "" when it has none. */
	static String channelKey(String name) {
		StringBuilder key = new StringBuilder();
		Matcher m = CHANNEL.matcher(name);
		while (m.find()) {
			if (key.length() > 0) key.append('_');
			key.append(m.group());
		}
		return key.toString();
	}

	/** The time point of a name as a sortable key, or null when it has none. */
	static String timeKey(String name) {
		Matcher m = ITER.matcher(name);
		if (m.find()) return naturalKey(m.group(1));
		m = T_SUFFIX.matcher(name);
		if (m.find()) return naturalKey(m.group(1));
		m = GENERIC_T.matcher(name);
		if (m.find()) return naturalKey(m.group(1));
		return null;
	}

	/** Natural order: digit runs compare numerically, so c2 sorts before c10. */
	private static final Comparator<String> NATURAL = new Comparator<String>() {
		public int compare(String a, String b) {
			return naturalKey(a).compareTo(naturalKey(b));
		}
	};

	/** Files of one cell: by tile position (x, then y, then z) when present, else natural order. */
	private static final Comparator<String> TILE_ORDER = new Comparator<String>() {
		public int compare(String a, String b) {
			Matcher ma = TILE.matcher(a), mb = TILE.matcher(b);
			if (ma.find() && mb.find()) {
				for (int g = 1; g <= 3; g++) {
					int d = Long.compare(Long.parseLong(ma.group(g)), Long.parseLong(mb.group(g)));
					if (d != 0) return d;
				}
			}
			return NATURAL.compare(a, b);
		}
	};

	private static String naturalKey(String s) {
		StringBuffer out = new StringBuffer();
		Matcher m = DIGITS.matcher(s);
		while (m.find()) {
			String digits = m.group();
			if (digits.length() > 12) digits = digits.substring(digits.length() - 12);
			m.appendReplacement(out, String.format("%012d", Long.parseLong(digits)));
		}
		m.appendTail(out);
		return out.toString();
	}
}
