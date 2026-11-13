package bml.notifications.internal;

import bitwig.obf.Zb1; // a background task with a progress bar
import bitwig.obf.dpN; // Bitwig's application, which runs and lists background tasks
import bitwig.obf.iFt; // the visitor every task accepts (ignored)
import bitwig.obf.sO3; // literal (untranslated) text

import java.util.concurrent.Executor;

/**
 * A task entry: Bitwig's progress-bar task, so title, status line, bar and (when cancellable) Cancel
 * are one row. Its {@code run()} is the {@link TaskDriver}'s loop.
 */
final class BarTask extends Zb1 {
    private final TaskDriver driver;

    BarTask(String title, Executor executor, TaskDriver driver) {
        super(dpN.TVZ(), executor, new sO3(title)); // the application, our own executor, a literal title
        this.driver = driver;
    }

    /** The mod's handle for this task. */
    bml.notifications.Task handle() {
        return driver.handle();
    }

    /** Sets the bar, 0 to 1. Any thread (Bitwig hops to its UI thread). */
    void fraction(double value) {
        ((Zb1) this).cfM(value); // the progress value
    }

    /** Shows or hides the bar. Any thread. */
    void barShown(boolean shown) {
        ((Zb1) this).cfM(shown); // the bar's visibility
    }

    @Override
    public boolean lYc() { // listed in the notification centre
        return true;
    }

    @Override
    public boolean FzB() { // has a status line
        return true;
    }

    @Override
    public boolean Hnt() { // has a Cancel button
        return driver.cancellable();
    }

    @Override
    public void iHb() { // Cancel pressed (UI thread)
        try {
            super.iHb(); // marks it cancelled, greys the button out
        } catch (Throwable t) {
            Center.MOD.log("Bitwig's cancel of a task failed", t);
        }
        driver.cancelPressed(); // catches its own
    }

    @Override
    public void cfM(iFt visitor) { // ignored, as Bitwig's own tasks do
    }

    @Override
    public void run() { // on our executor; nothing may escape to Bitwig
        try {
            driver.runOn(this);
        } catch (Throwable t) {
            Center.MOD.log("a task stopped", t);
        }
    }
}
