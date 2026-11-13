package bml.notifications;

import bml.notifications.internal.TaskDriver;

/**
 * A running task in Bitwig's notification centre, one row: a title, a live status line, a progress bar
 * and an optional Cancel button. Every method is safe from any thread and returns at once.
 */
public final class Task {
    private final TaskDriver driver;

    Task(String title, Runnable onCancel) {
        this.driver = new TaskDriver(title == null ? "" : title, onCancel);
        driver.handle(this);
    }

    /** Sets the status line under the title. */
    public Task status(String text) {
        driver.status(text == null ? "" : text);
        return this;
    }

    /** Sets the progress bar to {@code fraction} (0 to 1; values outside are clamped). */
    public Task progress(double fraction) {
        driver.progress(fraction);
        return this;
    }

    /**
     * The work goes on, but how far it is isn't known: the bar shows Bitwig's busy animation instead of a
     * value, as it does until the first {@link #progress} above 0.
     */
    public Task progressUnknown() {
        driver.progressUnknown();
        return this;
    }

    /** True once the user has pressed Cancel. */
    public boolean cancelled() {
        return driver.cancelled();
    }

    /**
     * Ends the task. Bitwig keeps an entry on screen for at least two seconds, and doesn't show one
     * that ends within its first 300 ms at all. Later calls do nothing.
     */
    public void finish() {
        driver.finish();
    }
}
