package edu.abc.berkeley;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The progress status text must stay narrow enough that the ImageJ progress
 * bar beside it remains visible: long microscope file names used to widen the
 * status line until the bar was pushed past the right edge of the window.
 * <p>
 * Pure string tests; these run without the natives.
 */
public class ProgressEstimatorTest {

	@Test
	public void shortStatusIsUnchanged() {
		String status = "Reading u16.tif (1.2 GB)";
		assertEquals(status, ProgressEstimator.shorten(status));
	}

	@Test
	public void longStatusKeepsBothEndsAndTheSize() {
		String name = "Scan_Iter_0000_CamA_ch0_CAM1_stack0000_488nm_0000000msec" +
			"_0016966725msecAbs_000x_000y_000z_0000t.tif";
		String shown = ProgressEstimator.shorten("Reading " + name + " (12.3 GB)");
		assertTrue("too wide: " + shown.length(), shown.length() <= 40);
		assertTrue("start lost: " + shown, shown.startsWith("Reading Scan"));
		assertTrue("size lost: " + shown, shown.endsWith("(12.3 GB)"));
		assertTrue("no ellipsis: " + shown, shown.contains("..."));
	}
}
