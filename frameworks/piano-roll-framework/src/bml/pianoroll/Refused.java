package bml.pianoroll;

/** The notes can't be edited as asked. Thrown before anything changes; mods throw it too. */
public final class Refused extends RuntimeException {
    public Refused(String reason) {
        super(reason);
    }
}
