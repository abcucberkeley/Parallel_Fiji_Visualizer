package edu.abc.berkeley;

import org.junit.Assume;

/** Shared guard for the test classes that need the native libraries. */
final class Natives {

	private Natives() {}

	/**
	 * Skip the calling test class when the natives are not staged, so the
	 * Java-only tests still run in a plain {@code mvn test}. With
	 * {@code -Dpfv.requireNatives=true}, which the native profile sets, a load
	 * failure fails the class instead: natives that build but do not load
	 * (a missing runtime library, a wrong install name) must not look like a
	 * passing build.
	 */
	static void required() {
		try {
			new ParallelReadNative();
		}
		catch (LinkageError e) {
			if (Boolean.getBoolean("pfv.requireNatives")) {
				throw new AssertionError("The natives were built but cannot be loaded", e);
			}
			Assume.assumeNoException("Natives are not staged; run mvn test -Pnative", e);
		}
	}
}
