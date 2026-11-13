package bml.browser.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.bitwig.base.io.z2y; // a library path: a file, or a file and an item inside it

import bitwig.obf.Am; // a CLAP plug-in
import bitwig.obf.BP; // a device or plug-in (type and category)
import bitwig.obf.CH; // a library result
import bitwig.obf.EE; // the library
import bitwig.obf.EX; // a place the library indexes, and its sources
import bitwig.obf.FB; // the library's kinds and queries
import bitwig.obf.Fz; // a set of library sources, to fill
import bitwig.obf.Jp; // a browser session
import bitwig.obf.LG; // a VST2 plug-in
import bitwig.obf.LT; // a VST3 plug-in
import bitwig.obf.Ngc; // a library source
import bitwig.obf.Pi; // a kind of library source
import bitwig.obf.Wix; // a set of library sources
import bitwig.obf.eoh; // a drag event
import bitwig.obf.qD; // a library kind
import bitwig.obf.qF; // a device type
import bitwig.obf.vE; // a plug-in (its vendor)
import bml.browser.IndexHit;

/**
 * The library side of the panel helpers: which sources a query runs over, what a result becomes as an
 * {@link IndexHit}, and the results kept so a hit can be dragged as Bitwig drags a result.
 */
final class Library {
    private Library() {
    }

    /** Results by key, kept for drags (the most recent ones; Bitwig's drag carries the result itself). */
    private static final int KEPT = 20_000;
    private static final Map<String, CH> RESULTS = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CH> eldest) {
            return size() > KEPT;
        }
    };

    /** Bitwig's kinds by their lower-case names; an unknown name throws (logged by the caller). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static EnumSet kinds(Set<String> names) {
        EnumSet wanted = EnumSet.noneOf((Class) qD.class); // Bitwig's own queries take an EnumSet
        for (String kind : names) wanted.add(qD.valueOf(kind.toUpperCase(Locale.ROOT)));
        return wanted;
    }

    /**
     * The sources a query runs over. Always the library's locations ({@code rGR}: the content
     * locations, as Bitwig's own Everything query takes them) and Bitwig's devices ({@code MJ}). With
     * {@code everyPlace}, also every source of the wanted kinds in every place the library indexes
     * ({@code E1b}: plug-in folders, packages, ...), as Bitwig's sources for plug-ins take them. Runs
     * on the library's thread.
     */
    @SuppressWarnings({"rawtypes"})
    static Wix sources(EE library, Set<?> wanted, boolean everyPlace) {
        Wix sources = new Fz();
        sources.cfM(library.rGR()); // every content location the library indexes
        sources.cfM(library.MJ());  // Bitwig's own devices
        if (!everyPlace) return sources;
        Set<Pi> types = new HashSet<>();
        for (Object kind : wanted) FB.cfM(type -> types.add((Pi) type), (qD) kind); // the kind's source types
        for (Object place : library.E1b()) {
            if (!(place instanceof EX ex)) continue;
            for (Object source : ex.NC()) {
                if (!(source instanceof Ngc s)) continue;
                if (types.contains(s.MRY()) && !sources.MRY(s)) sources.cfM(s); // once each
            }
        }
        return sources;
    }

    /** What a library result becomes for a view, or null for a result without a key. */
    static IndexHit hit(CH result) {
        File file = result.Grk();
        qD kind = result.hP_();
        String name = text(result.wGc()), kindName = kind == null ? "" : kind.name().toLowerCase(Locale.ROOT);
        String key = file == null ? null : file.getPath();
        String vendor = "", category = "", format = "", deviceType = "";
        try {
            z2y path = result.tp2(); // Bitwig's path for the result (what its browser inserts)
            if (path != null) key = path.toString();
            if (result instanceof vE plugin) vendor = text(plugin.qoU()); // the plug-in's vendor
            if (result instanceof BP device) {
                category = text(device.PD3()); // the category from the index
                qF type = device.Lcd();
                if (type != null) deviceType = type.name().toLowerCase(Locale.ROOT);
            }
            if (result instanceof LG) format = "vst2";
            else if (result instanceof LT) format = "vst3";
            else if (result instanceof Am) format = "clap";
        } catch (Throwable t) {
            State.LOG.log("library: reading the details of " + name + " failed", t); // the basics stay
        }
        if (key == null) return null;
        return new IndexHit(file, name, kindName, key, vendor, category, format, deviceType);
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    /** Keeps the results the view was given, for {@link #drag}. Called on the UI thread. */
    static void keep(Map<String, CH> results) {
        synchronized (RESULTS) {
            RESULTS.putAll(results);
        }
    }

    /**
     * Bitwig's drag of results, as its browser's results list starts one ({@code cFm.cfM(List, eoh)}):
     * the browser session's state, then each result and its path (a plain file as a file). A hit the
     * framework doesn't hold a result for goes as its file. Returns how many hits went in.
     */
    static int drag(Jp session, Collection<IndexHit> hits, eoh event) {
        List<Object> content = new ArrayList<>();
        for (IndexHit hit : hits) {
            if (hit == null) continue;
            CH result;
            synchronized (RESULTS) {
                result = hit.key() == null ? null : RESULTS.get(hit.key());
            }
            if (result != null) {
                content.add(result);
                z2y path = result.tp2();
                if (path != null) content.add(path.TMF() ? path : path.cfM()); // an item inside a file as the path
            } else if (hit.file() != null) {
                content.add(hit.file());
            }
        }
        if (content.isEmpty()) return 0;
        if (session != null) event.cfM(session.cfM(false)); // the session's state, as Bitwig adds it
        for (Object o : content) event.cfM(o);
        return (int) content.stream().filter(o -> o instanceof CH || o instanceof File).count();
    }
}
