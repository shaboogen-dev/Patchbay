package bml.pianoroll;

/** A listener a mod has started, from {@link PianoRoll#onSelectionChange} or {@link PianoRoll#onChange}. */
public final class Listening {
    private final Runnable stop;
    private boolean stopped;

    Listening(Runnable stop) {
        this.stop = stop;
    }

    /** The listener isn't called again. Stopping twice does nothing. */
    public void stop() {
        if (!stopped) {
            stopped = true;
            stop.run();
        }
    }
}
