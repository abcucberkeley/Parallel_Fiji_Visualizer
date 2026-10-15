package edu.abc.berkeley;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * The progress status text must stay narrow enough that the ImageJ progress
 * bar beside it remains visible: long microscope file names used to widen the
 * status line until the bar was pushed past the right edge of the window.
 * <p>
 * Pure string tests; these run without the natives.
 */
public class ProgressEstimatorTest {

	private static final String LONG_NAME = "Scan_Iter_0000_CamA_ch0_CAM1_stack0000_488nm_0000000msec" +
		"_0016966725msecAbs_000x_000y_000z_0000t.tif";

	/** The batch state is static; never let it leak into other tests. */
	@After
	public void endAnyBatch() {
		ProgressEstimator.endBatch();
	}

	@Test
	public void shortStatusIsUnchanged() {
		String status = "Reading u16.tif (1.2 GB)";
		assertEquals(status, ProgressEstimator.shorten(status));
	}

	@Test
	public void longStatusKeepsBothEndsAndTheSize() {
		String shown = ProgressEstimator.shorten("Reading " + LONG_NAME + " (12.3 GB)");
		assertTrue("too wide: " + shown.length(), shown.length() <= 40);
		assertTrue("start lost: " + shown, shown.startsWith("Reading Scan"));
		assertTrue("size lost: " + shown, shown.endsWith("(12.3 GB)"));
		assertTrue("no ellipsis: " + shown, shown.contains("..."));
	}

	/**
	 * Opening a folder shows which file of how many is being read; the count
	 * sits at the end of the status line so the truncation must keep it.
	 */
	@Test
	public void batchCountSurvivesTruncation() {
		final long size = 1288490188L; // 1.2 GB
		ProgressEstimator.beginBatch(120);
		ProgressEstimator.batchItem(3);
		String shown = ProgressEstimator.composeStatus("Reading " + LONG_NAME, size);
		assertTrue("too wide: " + shown, shown.length() <= 40);
		assertTrue("count lost: " + shown, shown.endsWith(" 3/120"));

		ProgressEstimator.endBatch();
		String after = ProgressEstimator.composeStatus("Reading u16.tif", size);
		assertEquals("Reading u16.tif (1.2 GB)", after);
		assertFalse("count left behind after the batch: " + after, after.contains("/120"));
	}
}
