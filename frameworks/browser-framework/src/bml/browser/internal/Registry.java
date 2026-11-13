package bml.browser.internal;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;

import bml.api.Bml;
import bml.browser.Browser;
import bml.browser.BrowserProvider;
import bml.browser.Feature;
import bml.browser.Features;
import bml.browser.Scope;
import bml.browser.View;

/** Calls every registered provider's {@code declare}, once, in the order the loader lists them (by mod ID). */
final class Registry {
    private static boolean loaded;

    private Registry() {
    }

    static synchronized void load() {
        if (loaded) return;
        loaded = true;
        int count = 0;
        for (Bml.Registration reg : Bml.registrations(State.FRAMEWORK)) {
            String modId = reg.modId();
            Browser browser = new Browser() {
                @Override
                public Features features() {
                    return State.INSTANCE;
                }

                @Override
                public View view(String viewId, String name) {
                    return Views.declare(modId, viewId, name);
                }

                @Override
                public void kind(String fileKind, String name, String tileResource, String iconName) {
                    Kinds.declare(modId, fileKind, name, tileResource, iconName);
                }

                @Override
                public void hide(Feature feature, Scope scope, boolean hidden) {
                    State.INSTANCE.hide(modId, feature, scope, hidden);
                }

                @Override
                public boolean hides(Feature feature, Scope scope) {
                    return State.INSTANCE.hides(modId, feature, scope);
                }

                @Override
                public void rebuild(String why) {
                    Rebuild.soon("mod " + modId + ": " + (why == null ? "" : why));
                }
            };
            try {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(reg.descriptor())) {
                    p.load(in);
                }
                if (!"1".equals(p.getProperty("api.version", "").trim()))
                    throw new IllegalArgumentException("api.version must be 1 in " + reg.descriptor());
                String className = p.getProperty("provider.class", "").trim();
                Class<?> type = Class.forName(className, true, Registry.class.getClassLoader());
                ((BrowserProvider) type.getConstructor().newInstance()).declare(browser);
                count++;
            } catch (Throwable t) {
                State.LOG.log("mod " + modId + ": browser registration skipped", t);
            }
        }
        State.LOG.log("providers: " + count);
        Views.loaded();
    }
}
