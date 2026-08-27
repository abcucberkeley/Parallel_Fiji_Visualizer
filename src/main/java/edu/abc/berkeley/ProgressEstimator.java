package edu.abc.berkeley;

import java.util.function.DoubleSupplier;

import javax.swing.Timer;

import ij.IJ;
import ij.Prefs;

/**
 * Drives the ImageJ progress bar for an operation whose true progress is not
 * observable. The duration is estimated from the operation size, the machine's
 * throughput learned from previous runs (persisted in the ImageJ preferences),
 * and how much of the machine the operation can actually use: an image with
 * fewer slices/chunks than cores runs at a proportionally lower rate.
 * Observed rates are normalized back to full parallelism before learning, so
 * small and large images calibrate the same number. The estimate can be raised
 * by a real observation when one is available (e.g. counting the chunk files a
 * zarr write has produced so far).
 * <p>
 * The estimate is displayed on an easing curve that approaches 95% and only
 * reaches 100% when {@link #finish()} is called, so the bar may pace wrongly
 * but it never claims completion early. The worker thread is never observed or
 * blocked: a Swing timer reads the clock ten times a second, so the estimator
 * cannot affect read/write speed.
 */
final class ProgressEstimator {

	private static final int TICK_MS = 100;
	private static final double CAP = 0.95;
	/** Chosen so the bar reaches ~85% when exactly the estimated time has elapsed. */
	private static final double EASE = 2.25;
	/** Fixed per-operation cost (dialogs, JNI, file open, thread spin-up). */
	private static final double OVERHEAD_SECONDS = 0.2;
	private static final int CORES = Runtime.getRuntime().availableProcessors();
	/** First-run default scales with the machine; learned rates replace it quickly. */
	private static final double DEFAULT_MBPS = Math.min(2000.0, Math.max(100.0, 20.0 * CORES));
	private static final double EWMA_WEIGHT = 0.3;

	private final String prefsKey;
	private final long totalBytes;
	private final double parallelFrac;
	private final double estimatedSeconds;
	private final DoubleSupplier observed;
	private final long startNanos;
	private final Timer timer;
	private boolean done = false;
	private double lastShown = 0.0;

	/**
	 * Purely time-based estimate. {@code parallelUnits} is how many independent
	 * pieces the native code can work on in parallel (slices for tiff, chunks
	 * for zarr); fewer units than cores means a proportionally lower rate.
	 */
	static ProgressEstimator begin(final String opKey, final long totalBytes,
		final long parallelUnits, final String status)
	{
		return new ProgressEstimator(opKey, totalBytes, parallelUnits, status, null);
	}

	/**
	 * Time-based estimate raised by a real observation; {@code observed}
	 * returns the completed fraction (0-1) and is polled on the Swing timer.
	 */
	static ProgressEstimator begin(final String opKey, final long totalBytes,
		final long parallelUnits, final String status, final DoubleSupplier observed)
	{
		return new ProgressEstimator(opKey, totalBytes, parallelUnits, status, observed);
	}

	private ProgressEstimator(final String opKey, final long totalBytes,
		final long parallelUnits, final String status, final DoubleSupplier observed)
	{
		this.prefsKey = "pfv.rate2." + opKey;
		this.totalBytes = totalBytes;
		this.parallelFrac = Math.min(1.0, Math.max(1L, parallelUnits) / (double) CORES);
		this.observed = observed;
		final double mbps = Math.max(1.0, Prefs.get(prefsKey, DEFAULT_MBPS));
		this.estimatedSeconds = OVERHEAD_SECONDS + totalBytes / (mbps * parallelFrac * 1e6);
		this.startNanos = System.nanoTime();
		IJ.showStatus(status + " (" + formatBytes(totalBytes) + ")");
		IJ.showProgress(0.0);
		this.timer = new Timer(TICK_MS, e -> tick());
		timer.start();
	}

	private synchronized void tick() {
		if (done) return;
		final double t = (System.nanoTime() - startNanos) / 1e9;
		double p = CAP * (1.0 - Math.exp(-EASE * t / estimatedSeconds));
		if (observed != null) {
			p = Math.max(p, Math.min(CAP, observed.getAsDouble()));
		}
		if (p > lastShown) {
			lastShown = p;
			IJ.showProgress(p);
		}
	}

	/** The operation completed: snap to 100% and learn the observed throughput. */
	synchronized void finish() {
		if (done) return;
		done = true;
		timer.stop();
		IJ.showProgress(1.0);
		final double seconds = (System.nanoTime() - startNanos) / 1e9;
		// Tiny operations are too noisy to learn from.
		if (seconds > OVERHEAD_SECONDS + 0.05 && totalBytes > 1_000_000L) {
			// Normalize to the machine's full-parallel rate before learning.
			final double work = seconds - OVERHEAD_SECONDS;
			final double mbpsFull = totalBytes / 1e6 / work / parallelFrac;
			final double learned = (1.0 - EWMA_WEIGHT) * Prefs.get(prefsKey, DEFAULT_MBPS)
				+ EWMA_WEIGHT * mbpsFull;
			Prefs.set(prefsKey, learned);
		}
	}

	/**
	 * The operation did not complete: hide the bar without learning a rate.
	 * No-op after {@link #finish()}, so it is safe in a finally block.
	 */
	synchronized void abort() {
		if (done) return;
		done = true;
		timer.stop();
		IJ.showProgress(1.0); // >= 1 hides the ImageJ progress bar
	}

	private static String formatBytes(final long bytes) {
		if (bytes >= 1L << 30) return String.format("%.1f GB", bytes / (double) (1L << 30));
		if (bytes >= 1L << 20) return String.format("%.1f MB", bytes / (double) (1L << 20));
		return String.format("%d kB", Math.max(1, bytes >> 10));
	}
}
