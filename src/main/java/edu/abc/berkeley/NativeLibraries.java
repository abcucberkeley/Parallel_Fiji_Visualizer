package edu.abc.berkeley;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

import org.scijava.nativelib.DefaultJniExtractor;
import org.scijava.nativelib.NativeLibraryUtil;

/**
 * Loads the native libraries in dependency order, from the first of:
 * <ol>
 * <li>the directory named by the system property {@code pfv.natives.dir};</li>
 * <li>the natives bundled under natives/&lt;platform&gt;/ inside this jar --
 * the normal case; they are extracted to a temporary directory first;</li>
 * <li>the development staging tree target/natives/&lt;platform&gt; written by
 * {@code mvn process-classes -Pnative} -- IDE full builds wipe the natives
 * out of target/classes, but this tree survives them.</li>
 * </ol>
 * All libraries must sit in one directory: libParallelReadC/libParallelWriteC
 * locate libcppTiff/libcppZarr through their $ORIGIN (Linux) or @loader_path
 * (macOS) run path, and those locate the bundled OpenMP runtime the same way.
 * Windows has no run path; there the dependencies are loaded explicitly
 * first, so the loader resolves them as already-loaded modules.
 */
final class NativeLibraries {

	/** MinGW runtime, loaded before everything else on Windows. */
	private static final String[] MINGW_RUNTIME = { "winpthread-1", "gcc_s_seh-1", "gomp-1" };

	/** The plugin's libraries, in dependency order. */
	private static final String[] LIBRARIES = { "cppTiff", "cppZarr", "ParallelReadC", "ParallelWriteC" };

	private static boolean loaded = false;

	private NativeLibraries() {}

	static synchronized void load() {
		if (loaded) return;
		final String platform = NativeLibraryUtil.getArchitecture().name().toLowerCase(Locale.ENGLISH);
		final boolean windows = platform.startsWith("windows");

		// 1) explicit override
		final String override = System.getProperty("pfv.natives.dir");
		if (override != null) {
			loadFromDirectory(new File(override), windows);
			loaded = true;
			return;
		}

		// 2) bundled in the jar (the normal case)
		final String resourceDir = "natives/" + platform + "/";
		if (NativeLibraries.class.getClassLoader().getResource(resourceDir + fileName(windows, "cppTiff")) != null) {
			loadFromResources(resourceDir, windows, platform);
			loaded = true;
			return;
		}

		// 3) development staging tree
		final File devDir = new File("target/natives/" + platform);
		if (devDir.isDirectory()) {
			loadFromDirectory(devDir, windows);
			loaded = true;
			return;
		}

		if (platform.equals("osx_64")) {
			throw new UnsatisfiedLinkError("The mac natives are Apple-silicon (arm64) only, but this " +
				"Fiji is running an Intel (x86_64) Java -- the classic Fiji build does this even on " +
				"M-series Macs, through Rosetta. Please use the Apple-silicon Fiji download " +
				"(or run Fiji with an ARM Java).");
		}
		throw new UnsatisfiedLinkError("No native libraries found for platform " + platform +
			": none bundled in the jar, no target/natives/" + platform +
			" staging tree (run: mvn process-classes -Pnative), and -Dpfv.natives.dir is not set.");
	}

	/** Platform file name of a library, with the MinGW "lib" prefix on Windows. */
	private static String fileName(final boolean windows, final String name) {
		return System.mapLibraryName(windows ? "lib" + name : name);
	}

	private static void loadFromResources(final String resourceDir, final boolean windows,
		final String platform)
	{
		try {
			// Pass our own class so resources are resolved against the plugin
			// jar's class loader (NativeLoader's default extractor may sit in a
			// parent class loader that cannot see this jar).
			final DefaultJniExtractor extractor = new DefaultJniExtractor(NativeLibraries.class);
			if (windows) {
				for (final String runtime : MINGW_RUNTIME) {
					final File lib = extractor.extractJni(resourceDir, "lib" + runtime);
					if (lib != null) System.load(lib.getAbsolutePath());
				}
			}
			else {
				// Extract only -- found next to libcppTiff/libcppZarr via their
				// run path when the system has no libgomp of its own.
				final boolean mac = platform.startsWith("osx");
				extractOptional(extractor, resourceDir + (mac ? "libgomp.1.dylib" : "libgomp.so.1"));
			}
			for (final String name : LIBRARIES) {
				final File lib = extractor.extractJni(resourceDir, windows ? "lib" + name : name);
				if (lib == null) {
					throw new UnsatisfiedLinkError("Native library " + name + " is missing from " +
						resourceDir + " in the plugin jar (jar built without natives for this platform?)");
				}
				System.load(lib.getAbsolutePath());
			}
		}
		catch (final IOException e) {
			final UnsatisfiedLinkError error = new UnsatisfiedLinkError(
				"Failed to extract the Parallel Fiji Visualizer native libraries: " + e.getMessage());
			error.initCause(e);
			throw error;
		}
	}

	/** Load directly from a directory on disk (override and development tiers). */
	private static void loadFromDirectory(final File dir, final boolean windows) {
		if (windows) {
			for (final String runtime : MINGW_RUNTIME) {
				final File lib = new File(dir, fileName(windows, runtime));
				if (lib.isFile()) System.load(lib.getAbsolutePath());
			}
		}
		for (final String name : LIBRARIES) {
			final File lib = new File(dir, fileName(windows, name));
			if (!lib.isFile()) {
				throw new UnsatisfiedLinkError("Native library " + lib.getAbsolutePath() + " does not exist");
			}
			System.load(lib.getAbsolutePath());
		}
	}

	/** Extract a resource verbatim (no loading, no library-name mapping). */
	private static void extractOptional(final DefaultJniExtractor extractor, final String resourcePath)
		throws IOException
	{
		final InputStream in = NativeLibraries.class.getClassLoader().getResourceAsStream(resourcePath);
		if (in == null) return;
		final File out = new File(extractor.getJniDir(),
			resourcePath.substring(resourcePath.lastIndexOf('/') + 1));
		try (InputStream is = in; OutputStream os = new FileOutputStream(out)) {
			final byte[] buf = new byte[8192];
			for (int n; (n = is.read(buf)) > 0;) {
				os.write(buf, 0, n);
			}
		}
		out.deleteOnExit();
	}
}
