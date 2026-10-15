package edu.abc.berkeley;

import ij.IJ;
import ij.ImagePlus;

/**
 * Runs the plugin window's reads and saves on threads of their own. On
 * Swing's event thread, which runs the window's buttons and drops, they would
 * freeze Fiji until done, their progress bars included (Swing redraws them on
 * that thread).
 */
final class Background {

	private Background() {
	}

	/**
	 * Runs task on a new thread and shows anything it throws in a dialog:
	 * these threads have no other exception handler, so without this a
	 * failure (e.g. natives that cannot load) dies silently in the console.
	 */
	static Thread run(String name, Runnable task) {
		Thread t = new Thread(() -> {
			try {
				task.run();
			}
			catch (Throwable e) {
				ProgressEstimator.clearStarting();
				e.printStackTrace();
				String msg = e.getMessage() != null ? e.getMessage() : e.toString();
				IJ.error("Parallel Fiji Visualizer", msg);
			}
		}, name);
		t.start();
		return t;
	}

	/**
	 * Saves imp with write, on a new thread (see run). The image is locked
	 * meanwhile, as ImageJ locks the images its commands work on, so that it
	 * is neither changed nor closed (which empties its stack) while it is
	 * written. An image another command or save has locked is not saved.
	 */
	static Thread save(ImagePlus imp, String fileName, Runnable write) {
		return run("PFV save " + fileName, () -> {
			if (!imp.lockSilently()) {
				IJ.error("Parallel Fiji Visualizer", "\"" + imp.getTitle() + "\" was not saved as " + fileName
					+ ": another command or save is using it. Save it again once that has finished.");
				return;
			}
			try {
				write.run();
			}
			finally {
				imp.unlock();
			}
		});
	}
}
