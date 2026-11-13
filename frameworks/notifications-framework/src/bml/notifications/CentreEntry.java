package bml.notifications;

/**
 * One entry of Bitwig's notification centre, as a {@link CentreWatcher} sees it. The same object from
 * {@link CentreWatcher#shown} to {@link CentreWatcher#ended}, so it can be a map key (by identity).
 * Read it on Bitwig's UI thread.
 */
public interface CentreEntry {
    /** True for a task (a status line, maybe a progress bar and Cancel), false for a message. */
    boolean isTask();

    /** The title, as the row shows it. */
    String title();

    /** A message's text; a task's status line when it ended (or now, while it runs). May be empty. */
    String text();

    /** A message's severity; {@link Severity#INFORMATION} for a task. */
    Severity severity();

    /** A task the user cancelled. False for a message. */
    boolean cancelled();

    /** When the centre first showed it, in {@link System#currentTimeMillis()} time. */
    long shownAt();

    /** When it ended, or 0 while it is still in the centre. */
    long endedAt();

    /**
     * Ended because its mod replaced it ({@link Message#replace}): the new words follow as a new entry.
     */
    boolean replaced();

    /** This framework's handle when a mod posted it through {@link Notifications}, else null. */
    Message message();

    /** This framework's handle when a mod started it through {@link Notifications#task}, else null. */
    Task task();

    /** Bitwig's own entry object, for a mod that works with Bitwig's classes. */
    Object entry();
}
