package edu.abc.berkeley;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Channel and time point detection from file names (no natives needed). */
public class SeriesLayoutTest {

	private static String scan(int iter, String cam, String nm, String tile) {
		return "Scan_Iter_" + String.format("%04d", iter) + "_" + cam + "_ch0_CAM1_stack0000_" + nm
			+ "_0000000msec_0106060251msecAbs_" + tile + "_0000t.tif";
	}

	@Test
	public void microscopeNamesFormChannelsByTimePoints() {
		String[] names = {
			scan(1, "CamB", "560nm", "000x_003y_000z"), scan(0, "CamA", "488nm", "000x_003y_000z"),
			scan(2, "CamA", "488nm", "000x_003y_000z"), scan(1, "CamA", "488nm", "000x_003y_000z"),
			scan(0, "CamB", "560nm", "000x_003y_000z"), scan(2, "CamB", "560nm", "000x_003y_000z") };
		SeriesLayout l = SeriesLayout.detect(names);
		assertNotNull(l);
		assertEquals(2, l.channels);
		assertEquals(3, l.frames);
		assertArrayEquals(new String[] { "CamA_ch0_488nm", "CamB_ch0_560nm" }, l.channelNames);
		assertArrayEquals(new String[] { names[1], names[4], names[3], names[0], names[2], names[5] }, l.order);
	}

	@Test
	public void genericTokensWork() {
		SeriesLayout l = SeriesLayout.detect(new String[] { "img_c2_t1.tif", "img_c1_t2.tif", "img_c1_t1.tif", "img_c2_t2.tif" });
		assertNotNull(l);
		assertEquals(2, l.channels);
		assertEquals(2, l.frames);
		assertArrayEquals(new String[] { "img_c1_t1.tif", "img_c2_t1.tif", "img_c1_t2.tif", "img_c2_t2.tif" }, l.order);
	}

	@Test
	public void timePointsOnly() {
		SeriesLayout l = SeriesLayout.detect(new String[] { scan(2, "CamA", "488nm", "000x_000y_000z"),
			scan(0, "CamA", "488nm", "000x_000y_000z"), scan(1, "CamA", "488nm", "000x_000y_000z") });
		assertNotNull(l);
		assertEquals(1, l.channels);
		assertEquals(3, l.frames);
		assertEquals(scan(0, "CamA", "488nm", "000x_000y_000z"), l.order[0]);
	}

	@Test
	public void incompleteGridIsNotAHyperstack() {
		assertNull(SeriesLayout.detect(new String[] { scan(0, "CamA", "488nm", "000x_000y_000z"),
			scan(0, "CamB", "560nm", "000x_000y_000z"), scan(1, "CamA", "488nm", "000x_000y_000z") }));
	}

	@Test
	public void tilesAloneAreNotAHyperstack() {
		assertNull(SeriesLayout.detect(new String[] { scan(0, "CamA", "488nm", "000x_000y_000z"),
			scan(0, "CamA", "488nm", "001x_000y_000z") }));
	}

	/** The PetaKit5D DSR naming: time point in the second Iter number and the t suffix, four tiles per camera. */
	private static String dsr(int t, String cam, int tileY, long msecAbs) {
		return String.format("Scan_Iter_0000_%04d_%s_ch0_CAM1_stack0000_488nm_0000000msec_%010dmsecAbs_000x_%03dy_000z_%04dt.tif",
			t, cam, msecAbs, tileY, t);
	}

	@Test
	public void tilesAreStackedAlongZ() {
		String[] names = {
			dsr(0, "CamA", 3, 106060251L), dsr(0, "CamA", 2, 106082240L), dsr(0, "CamA", 1, 106104333L), dsr(0, "CamA", 0, 106126432L),
			dsr(0, "CamB", 3, 106060251L), dsr(0, "CamB", 2, 106082240L), dsr(0, "CamB", 1, 106104333L), dsr(0, "CamB", 0, 106126432L),
			dsr(1, "CamA", 3, 106148492L), dsr(1, "CamA", 2, 106170570L), dsr(1, "CamA", 1, 106192817L), dsr(1, "CamA", 0, 106215043L),
			dsr(1, "CamB", 3, 106148492L), dsr(1, "CamB", 2, 106170570L), dsr(1, "CamB", 1, 106192817L), dsr(1, "CamB", 0, 106215043L) };
		SeriesLayout l = SeriesLayout.detect(names);
		assertNotNull(l);
		assertEquals(2, l.channels);
		assertEquals(2, l.frames);
		assertEquals(4, l.filesPerCell);
		assertArrayEquals(new String[] { "CamA_ch0_488nm", "CamB_ch0_488nm" }, l.channelNames);
		// time point 0: CamA tiles y0..y3, then CamB tiles y0..y3; then time point 1
		assertEquals(dsr(0, "CamA", 0, 106126432L), l.order[0]);
		assertEquals(dsr(0, "CamA", 3, 106060251L), l.order[3]);
		assertEquals(dsr(0, "CamB", 0, 106126432L), l.order[4]);
		assertEquals(dsr(1, "CamA", 0, 106215043L), l.order[8]);
		assertEquals(dsr(1, "CamB", 3, 106148492L), l.order[15]);
	}

	@Test
	public void plainNamesAreLeftAlone() {
		assertNull(SeriesLayout.detect(new String[] { "a.tif", "b.tif", "c.tif" }));
		assertNull(SeriesLayout.detect(new String[] { "only.tif" }));
	}
	@Test
	public void distinguishingNamesDropTheSharedTokens() {
		String[] labels = SeriesLayout.distinguishingNames(new String[] {
			dsr(0, "CamA", 3, 106060251L), dsr(0, "CamB", 0, 106126432L), dsr(1, "CamA", 3, 106148492L) });
		assertArrayEquals(new String[] { "0000_CamA_0106060251msecAbs_003y_0000t.tif",
			"0000_CamB_0106126432msecAbs_000y_0000t.tif", "0001_CamA_0106148492msecAbs_003y_0001t.tif" }, labels);
		assertArrayEquals(new String[] { "c1", "c2" }, // t1.tif is shared too
			SeriesLayout.distinguishingNames(new String[] { "img_c1_t1.tif", "img_c2_t1.tif" }));
		// nothing to drop, or different shapes: names stay as they are
		assertArrayEquals(new String[] { "a.tif", "b.tif" }, SeriesLayout.distinguishingNames(new String[] { "a.tif", "b.tif" }));
		assertArrayEquals(new String[] { "a_b.tif", "a.tif" }, SeriesLayout.distinguishingNames(new String[] { "a_b.tif", "a.tif" }));
		assertArrayEquals(new String[] { "same.tif" }, SeriesLayout.distinguishingNames(new String[] { "same.tif" }));
	}
}
