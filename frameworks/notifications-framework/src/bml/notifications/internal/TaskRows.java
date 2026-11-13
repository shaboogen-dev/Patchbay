package bml.notifications.internal;

import bitwig.obf.YFZ; // a background task
import bitwig.obf.eaA; // a task's row in the notification centre

import bml.notifications.TaskRowStyler;

/**
 * What happens to every task row Bitwig builds ({@link RowHooks#taskRow}). With no mod's layout, the row
 * stays exactly as Bitwig built it; the framework's own tasks only get the bar's busy mode while their
 * progress is unknown. UI thread.
 */
final class TaskRows {
    /** Set while the framework asks Bitwig for a plain row after a layout failed. */
    private static final ThreadLocal<Boolean> STOCK = new ThreadLocal<>();
    private static volatile boolean shapeLogged;

    private TaskRows() { }

    /** The row to show instead of {@code row}: the same row, laid out by a mod's styler, or a fresh Bitwig row. */
    static eaA finish(eaA row) {
        YFZ task = row.iAj(); // the task the row shows
        if (task == null) return row;
        TaskRowView view = TaskRowView.read(row, task);
        if (view != null && task instanceof BarTask) {
            try {
                TaskRowView.ownBar(view); // busy while the framework's task doesn't know its progress
            } catch (Throwable t) {
                Center.MOD.log("could not set a task's bar to busy", t);
            }
        }
        if (Boolean.TRUE.equals(STOCK.get())) return row;
        Stylers<TaskRowStyler> stylers = RowRegistry.tasks();
        if (stylers.current() == null) return row;
        if (view == null) {
            if (!shapeLogged) {
                shapeLogged = true;
                Center.MOD.log("a task row isn't laid out as expected; Bitwig's own rows stay as they are");
            }
            return row;
        }
        if (stylers.run(view, TaskRowStyler::style, Center.MOD::log)) return row;
        return stock(task, row);
    }

    /** A row exactly as Bitwig builds it, for a task whose layout failed halfway. */
    private static eaA stock(YFZ task, eaA failed) {
        STOCK.set(Boolean.TRUE);
        try {
            return eaA.cfM(task); // Bitwig's own row builder (comes back through the hook, which leaves it alone)
        } catch (Throwable t) {
            Center.MOD.log("could not rebuild a task row", t);
            return failed;
        } finally {
            STOCK.remove();
        }
    }
}
