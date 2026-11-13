package bml.pluginpanels.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import bitwig.obf.AQw; // an on/off value a widget can show and set
import bitwig.obf.VGL; // a value-change event: the value, its old and its new value
import bitwig.obf.foz; // a listener
import bitwig.obf.rMe; // a value a widget can show and set

/**
 * The page a layout shows (its index, as an {@code Integer}), for Bitwig's page switcher; plus the
 * values its chooser binds to: one on/off value per tab, or a choice of page names for a dropdown.
 * Kept for as long as the panel exists (a rebuilt panel starts on the first page). A change reaches
 * each listener as Bitwig's value-change event for the value it listens to (Bitwig's widget bindings
 * read the event as that), with that value's old and new state.
 */
final class PageValue implements rMe {
    /** A listener, the value it listens to, and that value's state for a page. */
    private record Watch(rMe value, IntFunction<Object> at, foz listener) {
    }

    private final List<String> names;
    private final List<Watch> listeners = new ArrayList<>();
    private int page;

    PageValue(List<String> names) {
        this.names = names;
    }

    int page() {
        return page;
    }

    void page(int p) {
        if (p == page || p < 0 || p >= names.size()) return;
        int old = page;
        page = p;
        List<Watch> now;
        synchronized (listeners) {
            now = List.copyOf(listeners);
        }
        for (Watch w : now) {
            try {
                w.listener().eventOccurred(new VGL(w.value(), w.at().apply(old), w.at().apply(p)));
            } catch (Throwable t) {
                Panels.report("page-listener", "switching the layout's page failed", t);
            }
        }
    }

    private void listen(rMe value, IntFunction<Object> at, foz listener) {
        synchronized (listeners) {
            listeners.add(new Watch(value, at, listener));
        }
    }

    private void unlisten(foz listener) {
        synchronized (listeners) {
            listeners.removeIf(w -> w.listener() == listener);
        }
    }

    @Override
    public Object getValue() {
        return page;
    }

    @Override
    public void setValue(Object value) {
        if (value instanceof Integer i) page(i);
    }

    @Override
    public boolean isSettable() {
        return true;
    }

    @Override
    public boolean isObservable() {
        return true;
    }

    @Override
    public void addListener(foz listener) {
        listen(this, i -> i, listener);
    }

    @Override
    public void removeListener(foz listener) {
        unlisten(listener);
    }

    /** On while page {@code index} is shown; turning it on shows that page. */
    AQw tab(int index) {
        PageValue pages = this;
        return new AQw() {
            private final AQw self = this;

            @Override
            public boolean getBoolValue() {
                return pages.page == index;
            }

            @Override
            public void setBoolValue(boolean on) {
                if (on) pages.page(index);
            }

            @Override
            public Object getValue() {
                return getBoolValue();
            }

            @Override
            public void setValue(Object value) {
                if (value instanceof Boolean on) setBoolValue(on);
            }

            @Override
            public boolean isSettable() {
                return true;
            }

            @Override
            public boolean isObservable() {
                return true;
            }

            @Override
            public void addListener(foz listener) {
                pages.listen(self, i -> i == index, listener);
            }

            @Override
            public void removeListener(foz listener) {
                pages.unlisten(listener);
            }
        };
    }

    /** One page name in the dropdown chooser. */
    static final class Name {
        final int index;
        final String name;

        Name(int index, String name) {
            this.index = index;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    Name[] namesForDropdown() {
        Name[] out = new Name[names.size()];
        for (int i = 0; i < out.length; i++) out[i] = new Name(i, names.get(i));
        return out;
    }

    /** The dropdown chooser's value: the shown page's name; picking one shows it. */
    rMe dropdown(Name[] items) {
        PageValue pages = this;
        return new rMe() {
            private final rMe self = this;

            @Override
            public Object getValue() {
                return items[pages.page];
            }

            @Override
            public void setValue(Object value) {
                if (value instanceof Name n) pages.page(n.index);
            }

            @Override
            public boolean isSettable() {
                return true;
            }

            @Override
            public boolean isObservable() {
                return true;
            }

            @Override
            public void addListener(foz listener) {
                pages.listen(self, i -> items[i], listener);
            }

            @Override
            public void removeListener(foz listener) {
                pages.unlisten(listener);
            }
        };
    }
}
