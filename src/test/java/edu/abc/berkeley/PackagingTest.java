package edu.abc.berkeley;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Test;

import ij.plugin.PlugIn;

/**
 * What stock Fiji needs from the plugin before any image is read. Both checks
 * guard against failures that are invisible in development builds: the plugin
 * simply does not load or does not appear in the menu. They run without the
 * natives.
 */
public class PackagingTest {

	/**
	 * Stock Fiji still runs on Java 8, which rejects newer class files with
	 * UnsupportedClassVersionError, so the plugin would not load at all. This
	 * happened once when the build targeted Java 16.
	 */
	@Test
	public void classesLoadOnJava8() throws Exception {
		Path classes = Paths.get(ParallelFijiVisualizer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		List<String> tooNew = new ArrayList<>();
		int checked = 0;
		try (Stream<Path> walk = Files.walk(classes)) {
			Iterator<Path> it = walk.filter(p -> p.toString().endsWith(".class")).iterator();
			while (it.hasNext()) {
				Path p = it.next();
				checked++;
				try (DataInputStream in = new DataInputStream(Files.newInputStream(p))) {
					in.readInt(); // magic
					in.readUnsignedShort(); // minor version
					int major = in.readUnsignedShort();
					if (major > 52) tooNew.add(classes.relativize(p) + " (class file version " + major + ")");
				}
			}
		}
		assertTrue("no class files under " + classes, checked > 0);
		assertTrue("these need a newer Java than Fiji's Java 8: " + tooNew, tooNew.isEmpty());
	}

	/**
	 * The Plugins menu command comes from plugins.config; without a valid
	 * entry Fiji loads the jar but shows nothing to click.
	 */
	@Test
	public void pluginsConfigRegistersTheMenuCommand() throws Exception {
		URL config = ParallelFijiVisualizer.class.getClassLoader().getResource("plugins.config");
		assertNotNull("plugins.config is missing", config);
		String entry = null;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(config.openStream(), StandardCharsets.UTF_8))) {
			for (String line; (line = r.readLine()) != null;) {
				if (line.trim().startsWith("#") || !line.contains("\"Parallel Fiji Visualizer\"")) continue;
				entry = line.trim();
			}
		}
		assertNotNull("plugins.config has no \"Parallel Fiji Visualizer\" command", entry);
		String[] parts = entry.split(",", 3);
		assertEquals("menu path, \"command\", class: " + entry, 3, parts.length);
		assertTrue("not in the Plugins menu: " + entry, parts[0].trim().startsWith("Plugins"));
		Class<?> plugin = Class.forName(parts[2].trim(), false, ParallelFijiVisualizer.class.getClassLoader());
		assertTrue(plugin.getName() + " is not an ImageJ PlugIn", PlugIn.class.isAssignableFrom(plugin));
	}
}
