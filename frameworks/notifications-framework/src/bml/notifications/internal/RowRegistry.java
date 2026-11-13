package bml.notifications.internal;

import bml.api.Bml;
import bml.notifications.Centre;
import bml.notifications.CentreProvider;
import bml.notifications.CentreWatcher;
import bml.notifications.MessageRowStyler;
import bml.notifications.RowProvider;
import bml.notifications.TaskRowStyler;
import bml.notifications.ToastHandler;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;

/**
 * What mods declare through a registration with this framework: row layouts, watchers and a toast
 * handler. Loaded once, on Bitwig's UI thread, the first time a row is built, the centre's list is made
 * or a toast is shown. The provider class implements {@link CentreProvider} (everything) or
 * {@link RowProvider} (row layouts only).
 */
final class RowRegistry {
    static final String FRAMEWORK = "notifications-framework";

    private static final Stylers<TaskRowStyler> TASKS = new Stylers<>("task rows");
    private static final Stylers<MessageRowStyler> MESSAGES = new Stylers<>("message rows");
    private static final Stylers<ToastHandler> TOASTS = new Stylers<>("toasts", "handler");
    private static final Watchers<CentreWatcher> WATCHERS = new Watchers<>();
    private static boolean started; // under the lock: a provider calling back in doesn't load twice
    private static volatile boolean loaded;

    private RowRegistry() { }

    static Stylers<TaskRowStyler> tasks() {
        load();
        return TASKS;
    }

    static Stylers<MessageRowStyler> messages() {
        load();
        return MESSAGES;
    }

    static Stylers<ToastHandler> toasts() {
        load();
        return TOASTS;
    }

    static Watchers<CentreWatcher> watchers() {
        load();
        return WATCHERS;
    }

    /** True once the declarations are loaded and no mod declared a toast handler. Any thread; never loads. */
    static boolean loadedWithoutToastHandler() {
        return loaded && TOASTS.current() == null;
    }

    private static synchronized void load() {
        if (started) return;
        started = true;
        for (Bml.Registration reg : Bml.registrations(FRAMEWORK)) {
            String modId = reg.modId();
            try {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(reg.descriptor())) {
                    p.load(in);
                }
                if (!"1".equals(p.getProperty("api.version", "").trim()))
                    throw new IllegalArgumentException("api.version must be 1 in " + reg.descriptor());
                String className = p.getProperty("provider.class", "").trim();
                Class<?> type = Class.forName(className, true, RowRegistry.class.getClassLoader());
                Object provider = type.getConstructor().newInstance();
                Centre centre = new Declarations(modId);
                if (provider instanceof CentreProvider c) {
                    c.declare(centre);
                } else if (provider instanceof RowProvider r) {
                    r.declare(centre);
                } else {
                    throw new IllegalArgumentException(className + " implements neither CentreProvider nor RowProvider");
                }
            } catch (Throwable t) {
                Center.MOD.log("mod " + modId + ": its notification-centre declarations are skipped", t);
            }
        }
        for (String line : TASKS.choose()) Center.MOD.log(line);
        for (String line : MESSAGES.choose()) Center.MOD.log(line);
        for (String line : TOASTS.choose()) Center.MOD.log(line);
        loaded = true;
        forgetOldOptions();
    }

    /**
     * Up to 0.2.0-dev.3 the framework kept two options of its own; they moved to Notifications: History
     * and Notifications: Pop-ups in the Centre, which have none. Removes the stale values, so nothing of
     * them stays behind (no write when they aren't there).
     */
    private static void forgetOldOptions() {
        try {
            bml.api.Settings s = Center.MOD.settings();
            s.remove("keep_history");
            s.remove("toasts_in_centre");
        } catch (Throwable t) {
            Center.MOD.log("could not remove the framework's old options", t);
        }
    }

    /** One mod's declarations. */
    private record Declarations(String modId) implements Centre {
        @Override
        public void taskRows(TaskRowStyler styler) {
            if (styler != null) TASKS.declare(modId, styler);
        }

        @Override
        public void messageRows(MessageRowStyler styler) {
            if (styler != null) MESSAGES.declare(modId, styler);
        }

        @Override
        public void watch(CentreWatcher watcher) {
            if (watcher != null) WATCHERS.declare(modId, watcher);
        }

        @Override
        public void toasts(ToastHandler handler) {
            if (handler != null) TOASTS.declare(modId, handler);
        }
    }
}
