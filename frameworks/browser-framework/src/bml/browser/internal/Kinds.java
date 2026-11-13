package bml.browser.internal;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import bitwig.obf.Cal; // a text Bitwig displays
import bitwig.obf.Wo; // a source group (PACKAGES, COLLECTIONS, SOUNDS, LOCATIONS)
import bitwig.obf.Wz; // a source for one file kind (Music, Templates, ...)
import bitwig.obf.cBi; // the browser configurations
import bitwig.obf.qD; // a file kind
import bitwig.obf.qH; // a browser configuration
import bitwig.obf.sO3; // a text that is given, not looked up
import bml.api.Bml;

/**
 * File kinds a mod adds to the browser listed as kinds: a source of Bitwig's own one-kind sort
 * ({@code Wz}, as Templates and Music are) for each file kind the side browser's configuration
 * allows beyond Bitwig's own set (Projects, with Projects in Browser). Each kind is in the group
 * Bitwig's kinds are in (SOUNDS), so it is listed under BY KIND in the side pane and in the source
 * picker, wherever a configuration allows its file kind. Its results are Bitwig's query for the
 * file kind. Its name and icon are what a mod declares ({@code Browser.kind}); Bitwig derives
 * neither for a kind it doesn't have.
 *
 * <p>Added to Bitwig's source registry ({@code Vv}): its stream ({@code Vv.MRY()}) and its lookup
 * by name ({@code Vv.cfM(String)}, which restores a session's saved source). Without the mod a
 * session saved with such a kind opens on Everything (the session's fallback for a source it can't
 * find).
 */
final class Kinds {
    /** A kind for one added file kind. */
    static final class Kind extends Wz {
        private final qD kind;
        private URI icon;
        private boolean iconLooked;
        private Cal text;

        Kind(qD kind) {
            this.kind = kind;
        }

        @Override
        public qD eLY() {
            return kind;
        }

        @Override
        public Wo TtY() {
            return Wo.Hnt; // SOUNDS, as Bitwig's kinds
        }

        @Override
        public String in_() {
            Declared d = declared(kind.MRY());
            if (d != null && d.name != null && !d.name.isBlank()) return d.name;
            String id = kind.MRY(); // Bitwig's name would be search_source.<id>, which it doesn't have
            return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
        }

        /**
         * The text Bitwig reads a source's name from (the grid's caption, a dragged source's label):
         * given here, since its own looks up search_source.<id> and shows "localization missing".
         */
        @Override
        public Cal ar_() {
            if (text == null) text = new sO3(in_());
            return text;
        }

        @Override
        public URI hUR() {
            if (!iconLooked) {
                iconLooked = true;
                Declared d = declared(kind.MRY());
                icon = d == null ? null : d.icon();
                if (icon == null) State.LOG.log("kinds: " + kind.MRY() + " has no icon (declare one with Browser.kind)");
            }
            return icon;
        }
    }

    /** A kind a mod declares ({@link bml.browser.Browser#kind}): its name, its tile as a manifest resource of that mod, and its small icon by name. */
    private record Declared(String modId, String name, String iconResource, String iconName) {
        /** The icon's file: one of Bitwig's tiles ({@code kind: bitwig}) or the mod's own WebP, as a URI; null (logged) if missing. */
        URI icon() {
            if (iconResource == null) return null;
            try {
                Path file = Bml.mod(modId).resource(iconResource);
                if (Files.isRegularFile(file)) return file.toUri();
                State.LOG.log("kinds: mod " + modId + ": the icon " + iconResource + " (" + file + ") isn't there");
            } catch (Throwable t) {
                State.LOG.log("kinds: mod " + modId + ": the icon " + iconResource + " can't be used", t);
            }
            return null;
        }
    }

    /**
     * Declared kinds by file kind id; the first declaration of a file kind wins. A kind's tile is a
     * raster tile, as Bitwig loads its kinds' ({@code Vn.hUR()}: {@code images/library/<id>.webp});
     * an SVG shows as Bitwig's missing-image mark.
     */
    private static final Map<String, Declared> DECLARED = new LinkedHashMap<>();

    static synchronized void declare(String modId, String fileKind, String name, String iconResource, String iconName) {
        if (fileKind == null || fileKind.isBlank()) {
            State.LOG.log("kinds: mod " + modId + " declared a kind without a file kind; skipped");
            return;
        }
        if (DECLARED.containsKey(fileKind)) {
            State.LOG.log("kinds: mod " + modId + ": " + fileKind + " is already declared by " + DECLARED.get(fileKind).modId() + "; skipped");
            return;
        }
        if (qD.cfM(fileKind) == null) {
            State.LOG.log("kinds: mod " + modId + ": " + fileKind + " isn't one of Bitwig's file kinds; skipped");
            return;
        }
        DECLARED.put(fileKind, new Declared(modId, name, iconResource, iconName));
        State.LOG.log("kinds: mod " + modId + " declared " + fileKind + " (" + name + ")");
    }

    private static Declared declared(String fileKind) {
        State.get(); // the providers have declared
        synchronized (Kinds.class) {
            return DECLARED.get(fileKind);
        }
    }

    /** Set once a session first builds its filter list: from then on Everything allows the declared file kinds. */
    private static volatile boolean listing;
    /** Bitwig's allowed set for Everything, and the copy with the declared file kinds. */
    private static volatile Set<?> stockAllowed, withDeclared;

    static void filtersListed() {
        listing = true;
    }

    /**
     * The file kinds a configuration allows ({@code qH.MRY()}, its private set's only reader):
     * Everything's (the side browser's, {@code cBi.cfM}) with the declared file kinds, from the first
     * filter list on (as Projects in Browser did since 1.0.3, when it replaced the set). Cached per
     * stock set.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static Set allowed(Set stock, qH configuration) {
        if (!listing || stock == null || configuration != cBi.cfM) return stock;
        if (stockAllowed == stock && withDeclared != null) return withDeclared;
        State.get(); // the providers have declared
        LinkedHashSet copy = new LinkedHashSet(stock);
        synchronized (Kinds.class) {
            for (String id : DECLARED.keySet()) {
                qD kind = qD.cfM(id);
                if (kind != null) copy.add(kind);
            }
        }
        Set result = copy.size() == stock.size() ? stock : java.util.Collections.unmodifiableSet(copy);
        withDeclared = result;
        stockAllowed = stock;
        return result;
    }

    /** The small icon Bitwig draws a file kind with by name ({@code CB.Ucl()}): the declared one, if any. */
    static String iconName(String bitwigIcon, qD kind) {
        if (kind == null || DECLARED.isEmpty()) return bitwigIcon;
        Declared d = declared(kind.MRY());
        return d == null || d.iconName() == null ? bitwigIcon : d.iconName();
    }

    /** One kind per file kind, made once, so the registry always hands out the same source. */
    private static final Map<qD, Kind> MADE = new LinkedHashMap<>();

    private Kinds() {
    }

    /**
     * The kinds for the file kinds the side browser's configuration allows beyond Bitwig's own.
     * Read each time: a mod may add its file kind later (Projects in Browser adds Projects from the
     * first filter list on).
     */
    static synchronized List<Kind> all() {
        List<Kind> now = new ArrayList<>();
        try {
            qH everything = cBi.cfM;
            Set<?> allowed = everything.MRY(); // with the kinds mods add (Projects in Browser wraps this)
            Set<?> stock = everything.cfM; // Bitwig's own set
            for (Object k : new LinkedHashSet<>(allowed)) {
                if (!(k instanceof qD kind) || stock.contains(kind)) continue;
                Kind made = MADE.get(kind);
                if (made == null) {
                    made = new Kind(kind);
                    MADE.put(kind, made);
                    State.LOG.log("kinds: " + kind.MRY() + " added");
                }
                now.add(made);
            }
        } catch (Throwable t) {
            State.LOG.log("kinds: finding the added file kinds failed", t);
        }
        return now;
    }

    /** Bitwig's registry stream, then the added kinds. */
    static Stream<?> withKinds(Stream<?> registry) {
        List<Kind> added = all();
        return added.isEmpty() ? registry : Stream.concat(registry, added.stream());
    }

    /** Bitwig's lookup by name found nothing: an added kind of that name, or null. */
    static Kind named(String name) {
        if (name == null) return null;
        for (Kind k : all()) {
            if (name.equals(k.E3())) return k;
        }
        return null;
    }
}
