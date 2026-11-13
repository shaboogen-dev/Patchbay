package bml.notifications;

/**
 * A task's row in Bitwig's notification centre, as Bitwig built it, handed to a {@link TaskRowStyler}
 * before Bitwig shows it. Every task's row: Bitwig's own and every mod's, in the centre, inside another
 * task's row (sub-tasks) and in the list of hidden tasks.
 *
 * <p>Bitwig's layout, top to bottom, in {@link #column()}: the title line (spinner and title), the bar
 * group, Cancel (aligned right), the status line, the sub-task list. The images, when a task has any, sit
 * left of the column, in {@link #frame()}. A part the task doesn't have is null.
 */
public interface TaskRow extends Row {
    // ---------- the task ----------

    /** The framework's handle when a mod started the task through this framework; otherwise null. */
    Task task();

    /** The task's title. */
    String titleText();

    /** The task's status line as it is now; empty when there is none. */
    String statusText();

    /** True when the task has a progress value (Bitwig's progress-bar tasks and this framework's tasks). */
    boolean hasProgress();

    /** The progress, 0 to 1; NaN when the task has no progress value. */
    double progress();

    /** True when the task has a Cancel button. */
    boolean cancellable();

    /** True once the task was cancelled. */
    boolean cancelled();

    // ---------- the parts ----------

    /** The column, a group: title line, bar group, Cancel, status, sub-tasks, in Bitwig's order. */
    Part column();

    /** The title line, a group: spinner and title. */
    Part titleLine();

    /** The title (a wrapping text, in a box that stretches it across the line). */
    Part title();

    /** The busy spinner by the title. Bitwig shows it for tasks without a progress value. */
    Part spinner();

    /** The group holding the bar. */
    Part barBox();

    /** The progress bar. */
    Part bar();

    /** Cancel, or null for a task that can't be cancelled. */
    Part cancel();

    /** The status line (one line, cut in the middle when too long), or null for a task without one. */
    Part status();

    /** The sub-task list, or null. */
    Part subtasks();

    /** The images left of the column, or null. */
    Part images();

    // ---------- helpers ----------

    /**
     * A new wrapping text that shows the task's status as it changes, in {@code look}, hidden while the
     * status is empty: Bitwig's wrapping text widget bound as Bitwig binds its one-line status.
     */
    Part wrappingStatus(TextLook look);

    /** Shows the bar for as long as the row is there, also for tasks Bitwig shows no bar for. */
    void barShownWhileRunning();

    /** Puts the bar in its busy mode (an animated bar instead of a value), or takes it out of it. */
    void barWaiting(boolean waiting);

    /**
     * Busy while the progress isn't known, the value as soon as it is: for a task with a progress value,
     * busy while it is 0 or the task hides its bar; for a task without one, always busy.
     */
    void barWaitingWhileUnknown();
}
