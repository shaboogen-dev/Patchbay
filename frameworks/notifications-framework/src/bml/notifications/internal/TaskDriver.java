package bml.notifications.internal;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives one task entry: a single Bitwig progress-bar task ({@link BarTask}), whose bar is part of its
 * own row. The Bitwig task only waits in its {@code run()}, on the framework's own threads (not
 * Bitwig's four-thread pool), and applies the mod's updates there: Bitwig wants a task's status set
 * from the task's own thread.
 *
 * <p>Bitwig's progress-bar task takes values from 0 to 1 only, so while the progress is unknown its value
 * is 0; the framework's hook on task rows ({@link TaskRows}) puts the bar of its own tasks in Bitwig's
 * busy mode while the value is 0.
 */
public final class TaskDriver {
    /** Bars closer than this to the last one shown aren't sent to Bitwig (at most ~10 updates a second anyway). */
    private static final double STEP = 0.002;

    private static final ExecutorService TASK_POOL = pool("BML Notifications task");

    private final String title;
    private final Runnable onCancel;
    private volatile bml.notifications.Task handle;
    /**
     * What this task's entry runs on. Everything Bitwig runs there (its runner, which also renames the
     * thread, then our run()) is guarded: a throw fails this notification, never Bitwig.
     */
    private final Executor tasks = r -> TASK_POOL.execute(() -> guarded(r));
    private final BlockingQueue<Runnable> ops = new LinkedBlockingQueue<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean finishing;
    private volatile boolean dead;
    private volatile double progress; // 0 while unknown

    // On the task's own thread only:
    private BarTask owner;
    private double shown = Double.NaN;

    public TaskDriver(String title, Runnable onCancel) {
        this.title = Text.title(title, null); // Bitwig names the task's thread after it: never empty
        this.onCancel = onCancel;
        Center.later(this::start);
    }

    private void start() { // UI thread
        if (finishing) {
            dead = true; // finished before it started: nothing to show
            return;
        }
        try {
            BarTask task = new BarTask(title, tasks, this);
            task.fraction(0);
            task.barShown(true); // the bar is part of the row from the start, busy until progress arrives
            task.wGc(); // start it: Bitwig lists it in the centre and runs it on our executor
        } catch (Throwable t) {
            dead = true;
            Center.MOD.log("could not start the task \"" + title + "\"", t);
        }
    }

    /** The mod's handle, set once by {@link bml.notifications.Task}. */
    public void handle(bml.notifications.Task task) {
        handle = task;
    }

    bml.notifications.Task handle() {
        return handle;
    }

    // ---------- from the mod, any thread ----------

    public void status(String text) {
        if (finishing || dead) return;
        String t = Text.text(text);
        ops.add(() -> owner.cfM(t)); // the status line (from the task's own thread, as Bitwig asks)
    }

    public void progress(double fraction) {
        if (finishing || dead) return;
        progress = Double.isNaN(fraction) ? 0 : Math.max(0, Math.min(1, fraction));
    }

    public void progressUnknown() {
        if (finishing || dead) return;
        progress = 0;
    }

    public void finish() {
        finishing = true;
        ops.add(() -> { });
    }

    public boolean cancelled() {
        return cancelled.get();
    }

    // ---------- from Bitwig's task ----------

    boolean cancellable() {
        return onCancel != null;
    }

    /** Cancel pressed in the entry (UI thread). */
    void cancelPressed() {
        if (onCancel == null || !cancelled.compareAndSet(false, true)) return;
        try {
            onCancel.run();
        } catch (Throwable t) {
            Center.MOD.log("the Cancel of \"" + title + "\" failed", t);
        }
    }

    /** The Bitwig task's run(): apply the updates until the mod finishes. Never throws. */
    void runOn(BarTask task) {
        owner = task;
        try {
            while (true) {
                Runnable op = ops.poll(100, TimeUnit.MILLISECONDS);
                while (op != null) {
                    apply(op);
                    op = ops.poll();
                }
                apply(this::applyProgress);
                if (finishing && ops.isEmpty()) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Center.MOD.log("the task \"" + title + "\" stopped updating", t);
        } finally {
            dead = true;
        }
    }

    private void apply(Runnable op) {
        try {
            op.run();
        } catch (Throwable t) {
            Center.MOD.log("a task update of \"" + title + "\" failed", t);
        }
    }

    private void applyProgress() { // task thread
        double p = progress;
        if (!Double.isNaN(shown) && Math.abs(p - shown) < STEP && !(p == 1 && shown != 1) && !(p == 0 && shown != 0)) return;
        shown = p;
        owner.fraction(p);
    }

    /** Runs what Bitwig hands our executor; whatever it throws is logged and ends this task's updates. */
    private void guarded(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            dead = true;
            finishing = true;
            Center.MOD.log("the entry of \"" + title + "\" failed; Bitwig is unaffected", t);
        }
    }

    private static ExecutorService pool(String name) {
        AtomicInteger n = new AtomicInteger();
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, name + " " + n.incrementAndGet());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((th, e) -> Center.MOD.log("uncaught in " + th.getName(), e)); // never Bitwig's handler
            return t;
        });
    }
}
