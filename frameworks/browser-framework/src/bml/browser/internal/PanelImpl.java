package bml.browser.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.bitwig.flt.app.CHR; // the application
import com.bitwig.flt.document.core.iface.RYd; // a track
import com.bitwig.flt.document.core.iface.clipboard.clip.ArrangerClipInsertionPoint;
import com.bitwig.flt.document.core.iface.pHd; // a project
import com.bitwig.flt.document.core.master.AJH; // a document with a transport
import com.bitwig.flt.document.core.master.file_preview.hgq; // the cue player
import com.bitwig.flt.prefs.kHn; // a content location
import com.bitwig.flt.prefs.tad; // the preview stretch preference's values
import com.bitwig.flt.prefs.zbI; // the content locations preference
import com.bitwig.ramona.core.oDc; // a model object (the cue player is one)
import com.bitwig.ramona.core.uzc; // a property of a model object

import bitwig.obf.AWA; // a cue player
import bitwig.obf.CH; // a library result (a file)
import bitwig.obf.EE; // the library
import bitwig.obf.FB; // the library's kinds and queries
import bitwig.obf.Iog; // the cue player's stretch mode
import bitwig.obf.M22; // a library query
import bitwig.obf.SEJ; // a transport
import bitwig.obf.Wix; // a set of library sources
import bitwig.obf.aBW; // a project window
import bitwig.obf.aYz; // a window's selection
import bitwig.obf.cBP; // the browsers' file drag
import bitwig.obf.cFV; // the side browser
import bitwig.obf.dmQ; // a registered action
import bitwig.obf.dpN; // Bitwig's UI event loop
import bitwig.obf.dve; // a widget
import bitwig.obf.eoh; // a drag event
import bitwig.obf.iKU; // a track's arranger content
import bitwig.obf.kyI; // a query's parse problems
import bitwig.obf.pHE; // the import task's settings
import bitwig.obf.qD; // a library kind
import bitwig.obf.uoz; // an arranger lane
import bml.browser.ContentLocation;
import bml.browser.IndexHit;
import bml.browser.Panel;
import bml.browser.ProjectItem;

/**
 * One side browser panel showing a view's content, and the helpers a view calls. Bitwig failures are
 * logged and answered with false; a callback of the view's that throws makes the view fail
 * ({@link Views#fail}).
 */
final class PanelImpl implements Panel {
    final Views.Declared view;
    private final cFV browser;
    private final pHd project;
    private final List<Runnable> onDispose = new CopyOnWriteArrayList<>();
    private final List<Consumer<File>> onLoaded = new CopyOnWriteArrayList<>();
    private volatile boolean live = true;
    private dve content;
    /** The view's own search field, as it declared it, or null. */
    private dve searchField;

    /** The cue player held for previews: retained and activated by this panel, or null. */
    private AWA player;
    private boolean retained, active;
    /** The file this panel asked to preview, and when and from where playback started. */
    private String requested;
    private long startNanos;
    private double startBeat;
    private boolean playingOurs;

    PanelImpl(Views.Declared view, cFV browser) {
        this.view = view;
        this.browser = browser;
        pHd p = null;
        try {
            p = browser.gfw(); // the project
        } catch (Throwable t) {
            log("reading the panel's project failed", t);
        }
        this.project = p;
    }

    void built(dve widget) {
        content = widget;
    }

    Object browserObject() {
        return browser;
    }

    boolean owns(hgq loaded) {
        return live && player == (Object) loaded && requested != null;
    }

    /** Disposed with its side browser: the view's actions run, the cue player is let go. */
    void dispose() {
        if (!live) return;
        live = false;
        for (Runnable action : onDispose) callView("onDispose", action);
        try {
            if (player != null) {
                if (active) player.hUR(); // deactivate
                if (retained) player.TMF(); // release
            }
        } catch (Throwable t) {
            log("letting go of the cue player failed", t);
        }
        player = null;
        retained = active = false;
    }

    // ---------------------------------------------------------------- basics

    @Override
    public String viewId() {
        return view.id;
    }

    @Override
    public boolean live() {
        return live;
    }

    @Override
    public Object content() {
        return content;
    }

    @Override
    public Object browser() {
        return browser;
    }

    @Override
    public Map<String, Object> state() {
        return Contents.state(project, view.key());
    }

    @Override
    public void onDispose(Runnable action) {
        if (action != null) onDispose.add(action);
    }

    @Override
    public void searchField(Object widget) {
        if (widget != null && !(widget instanceof dve)) {
            State.LOG.log("view " + view.key() + ": searchField: " + widget.getClass().getName() + " isn't a widget; ignored");
            return;
        }
        searchField = (dve) widget;
    }

    @Override
    public boolean focusSearch() {
        try {
            dve field = searchField;
            if (!live || field == null) return false;
            field.ju_(); // Bitwig's focus request, as the stock search field's own (cFt.ji_ -> ecz.ju_)
            return true;
        } catch (Throwable t) {
            log("focusing the view's search field failed", t);
            return false;
        }
    }

    @Override
    public boolean focus() {
        try {
            if (!live || content == null) return false;
            content.ju_(); // Bitwig's focus request, as its own search field asks for the focus (cFt.ji_)
            return true;
        } catch (Throwable t) {
            log("focusing the view's content failed", t);
            return false;
        }
    }

    @Override
    public boolean showPanel() {
        try {
            if (!live) return false;
            if (browser.Pfd()) return true; // shown
            aBW window = (aBW) browser.TMF(aBW.class);
            dmQ action = window == null ? null : window.cfM(aBW.RDJ); // the Browser panel action
            if (action == null) return false;
            action.run();
            return true;
        } catch (Throwable t) {
            log("showing the Browser panel failed", t);
            return false;
        }
    }

    // ---------------------------------------------------------------- audition

    /** The panel's cue player, retained (once) for as long as the panel lives. */
    private AWA player() {
        if (!live) return null;
        if (player == null) {
            player = browser.iAj(); // the side browser's cue player
            if (player == null) return null;
        }
        if (!retained) {
            player.FzB(); // retain
            retained = true;
        }
        return player;
    }

    /** The cue player, activated: Bitwig's own preview widget activates it only while it is shown. */
    private AWA activePlayer() {
        AWA p = player();
        if (p != null && !active) {
            p.UNj(); // activate
            active = true;
            int mode = stretchMode();
            if (mode >= 0) p.cfM(Iog.cfM(mode)); // the preference, as Bitwig's preview widget sets it
        }
        return p;
    }

    @Override
    public boolean preview(File file) {
        try {
            AWA p = file == null ? null : activePlayer();
            if (p == null) return false;
            requested = file.getPath();
            playingOurs = false;
            p.cfM(requested); // load and play when loaded; the loaded file plays again
            if (requested.equals(p.MIO()) && p.tp2()) started(0);
            return true;
        } catch (Throwable t) {
            log("previewing " + file + " failed", t);
            return false;
        }
    }

    /** A file finished loading in this panel's cue player. */
    void loaded(hgq loaded) {
        String path;
        try {
            path = loaded.MIO();
        } catch (Throwable t) {
            log("reading the loaded preview failed", t);
            return;
        }
        if (path == null || !path.equals(requested)) return;
        started(0);
        File file = new File(path);
        later(() -> { // the load may finish off the UI thread
            for (Consumer<File> listener : onLoaded) if (live) callView("onPreviewLoaded", () -> listener.accept(file));
        });
    }

    private void started(double beat) {
        startNanos = System.nanoTime();
        startBeat = beat;
        playingOurs = true;
    }

    @Override
    public boolean stopPreview() {
        try {
            playingOurs = false;
            if (player == null) return true;
            player.BNq(); // stop
            return true;
        } catch (Throwable t) {
            log("stopping the preview failed", t);
            return false;
        }
    }

    @Override
    public boolean previewPlaying() {
        try {
            return player != null && player.tp2(); // is_playing
        } catch (Throwable t) {
            log("reading the preview state failed", t);
            return false;
        }
    }

    @Override
    public boolean playFrom(double beat) {
        try {
            AWA p = activePlayer();
            if (p == null || !Double.isFinite(beat)) return false;
            p.cfM(Math.max(0, beat)); // start_preview_from
            started(Math.max(0, beat));
            return true;
        } catch (Throwable t) {
            log("playing the preview from " + beat + " failed", t);
            return false;
        }
    }

    @Override
    public double previewDurationBeats() {
        try {
            if (player == null) return Double.NaN;
            double beats = playerValue(DURATION);
            return beats > 0 ? beats : Double.NaN;
        } catch (Throwable t) {
            log("reading the preview's length failed", t);
            return Double.NaN;
        }
    }

    @Override
    public double playheadBeats() {
        try {
            if (!playingOurs || player == null || !player.tp2()) return -1;
            double tempo = player.cfM(); // the project's tempo
            if (stretchMode() != STRETCH_SYNC_TO_PROJECT) {
                double fileTempo = playerValue(FILE_TEMPO);
                if (fileTempo > 0) tempo = fileTempo;
            }
            if (!(tempo > 0)) return -1;
            double beat = startBeat + (System.nanoTime() - startNanos) / 1e9 * tempo / 60;
            double length = previewDurationBeats();
            return Double.isNaN(length) || beat < length ? beat : -1;
        } catch (Throwable t) {
            log("working out the playhead failed", t);
            return -1;
        }
    }

    /**
     * The cue player's properties the panel reads, by their IDs in Bitwig's model. Read through the
     * player's property list ({@code oDc.iM()}, each a {@code uzc} named by {@code E3()}) rather than
     * the player's own getters ({@code TWy.rGR()}, {@code TWy.MJ()}), which the 6.0 map can't translate:
     * the IDs are the same in every Bitwig version the framework is mapped to.
     */
    private static final String DURATION = "loaded_duration_in_beats", FILE_TEMPO = "loaded_file_tempo";
    /** The player whose properties are held below, and the two properties (null until found). */
    private Object propertiesOf;
    private uzc durationProperty, tempoProperty;

    /** A double property of the cue player, by ID; NaN when it has none or no value. */
    private double playerValue(String id) {
        if (propertiesOf != player) {
            propertiesOf = player;
            durationProperty = tempoProperty = null;
            if (player instanceof oDc object) {
                for (Object field : object.iM()) { // its properties, each bound to the player
                    if (!(field instanceof uzc property)) continue;
                    if (DURATION.equals(property.E3())) durationProperty = property;
                    else if (FILE_TEMPO.equals(property.E3())) tempoProperty = property;
                }
            }
            if (player != null && (durationProperty == null || tempoProperty == null)) {
                State.LOG.log("view " + view.key() + ": the cue player has no " + DURATION + " or " + FILE_TEMPO);
            }
        }
        uzc property = DURATION.equals(id) ? durationProperty : tempoProperty;
        return property != null && property.getValue() instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    @Override
    public int stretchMode() {
        try {
            return CHR.Grk().hDc().MJs().xL().cfM(); // the preview stretch preference
        } catch (Throwable t) {
            log("reading the preview stretch mode failed", t);
            return -1;
        }
    }

    @Override
    public boolean setStretchMode(int mode) {
        if (mode < STRETCH_SYNC_TO_PROJECT || mode > STRETCH_RAW) return false;
        try {
            CHR.Grk().hDc().MJs().cfM(tad.cfM(mode)); // the preference
            if (player != null) player.cfM(Iog.cfM(mode)); // the cue player, as Bitwig's preview widget does
            return true;
        } catch (Throwable t) {
            log("setting the preview stretch mode failed", t);
            return false;
        }
    }

    @Override
    public void onPreviewLoaded(Consumer<File> listener) {
        if (listener != null) onLoaded.add(listener);
    }

    // ---------------------------------------------------------------- files and the project

    @Override
    public void insertAtPlayCursor(File file, Consumer<Throwable> done) {
        try {
            if (!live) throw new IllegalStateException("The browser panel is closed");
            if (file == null || !file.isFile() || !file.canRead())
                throw new IllegalArgumentException("The file is missing or can't be read: " + file);
            aBW window = (aBW) browser.TMF(aBW.class);
            if (window == null) throw new IllegalStateException("The project window is unavailable");
            aYz selection = window.LFv(); // the window's selection
            RYd track = selection == null ? null : selection.luv(); // the selected track
            iKU arranger = track == null ? null : track.tp2(); // its arranger content
            if (arranger == null || arranger.L_()) throw new IllegalStateException("Select a track first");
            pHd trackProject = arranger.apV();
            uoz lane = arranger.xcy(); // its arranger lane
            if (lane == null || lane.apV() != trackProject)
                throw new IllegalStateException("The selected track has no arranger lane in this project");
            if (!(trackProject instanceof AJH document)) throw new IllegalStateException("The project has no transport");
            SEJ transport = document.tHK();
            double cursor = transport.TD2(); // the play cursor, in beats
            if (!Double.isFinite(cursor) || cursor < 0) throw new IllegalStateException("The play cursor isn't valid");
            ArrangerClipInsertionPoint point = new ArrangerClipInsertionPoint(lane, cursor, null, null);
            if (!point.MRY()) throw new IllegalStateException("The project isn't available for inserting");
            Completion completion = new Completion(done);
            if (!point.cfM(file, pHE.MRY.IwV(), completion)) completion.cfM(new IllegalStateException("Bitwig refused the import"));
        } catch (Throwable t) {
            log("inserting " + file + " at the play cursor failed", t);
            report(done, t);
        }
    }

    /** Bitwig's import reports back here (on its own thread); the view hears on the UI thread. */
    private final class Completion implements com.bitwig.base.async.lBQ {
        private final Consumer<Throwable> done;
        private boolean reported;

        Completion(Consumer<Throwable> done) {
            this.done = done;
        }

        @Override
        public void taskCompleted() {
            finish(null);
        }

        @Override
        public void cfM(Exception failure) {
            log("Bitwig's import failed", failure);
            finish(failure);
        }

        private synchronized void finish(Throwable result) {
            if (reported) return;
            reported = true;
            later(() -> report(done, result));
        }
    }

    private void report(Consumer<Throwable> done, Throwable result) {
        if (done != null) callView("insertAtPlayCursor's done", () -> done.accept(result));
    }

    @Override
    public boolean insertWithBitwig(File file) {
        try {
            if (!live || file == null) return false;
            browser.cfM(file.getPath()); // the browsers' insert by path, as a chosen result
            return true;
        } catch (Throwable t) {
            log("inserting " + file + " failed", t);
            return false;
        }
    }

    @Override
    public boolean dragFiles(Collection<File> files, Object dragEvent) {
        try {
            if (!live || files == null || files.isEmpty() || !(dragEvent instanceof eoh event)) return false;
            cBP.cfM(new LinkedHashSet<>(files), event); // the files become the drag's content
            return true;
        } catch (Throwable t) {
            log("starting the file drag failed", t);
            return false;
        }
    }

    @Override
    public List<ContentLocation> contentLocations() {
        List<ContentLocation> locations = new ArrayList<>();
        try {
            for (Object entry : zbI.cfM.cfM(CHR.Grk().hDc().tSS())) { // the content locations preference
                if (!(entry instanceof kHn location)) continue;
                String path = location.ODA();
                if (path == null || path.isBlank()) continue;
                String name = location.Ucl();
                File folder = new File(path);
                locations.add(new ContentLocation(folder, name == null || name.isBlank() ? folder.getName() : name.strip()));
            }
        } catch (Throwable t) {
            log("reading the content locations failed", t);
        }
        return Collections.unmodifiableList(locations);
    }

    @Override
    public File projectFolder() {
        try {
            String path = project == null ? null : project.nfq();
            if (path == null || path.isBlank()) return null;
            File file = new File(path);
            return file.isFile() ? file.getParentFile() : file;
        } catch (Throwable t) {
            log("reading the project's folder failed", t);
            return null;
        }
    }

    @Override
    public boolean revealInFileManager(File file) {
        try {
            if (!live || file == null) return false;
            dpN.TVZ().MRY(content != null ? content : browser, file);
            return true;
        } catch (Throwable t) {
            log("showing " + file + " in the file manager failed", t);
            return false;
        }
    }

    @Override
    public void searchIndex(Set<String> kinds, String text, int limit, BooleanSupplier cancelled,
                            Consumer<List<IndexHit>> results) {
        query("searchIndex", kinds, text, limit, false, cancelled, results);
    }

    @Override
    public void searchLibrary(Set<String> kinds, String text, int limit, BooleanSupplier cancelled,
                              Consumer<List<IndexHit>> results) {
        query("searchLibrary", kinds, text, limit, true, cancelled, results);
    }

    @Override
    public void listLibrary(Set<String> kinds, int limit, BooleanSupplier cancelled, Consumer<List<IndexHit>> results) {
        query("listLibrary", kinds, "", limit, true, cancelled, results);
    }

    /**
     * Runs a library query on the library's thread and hands the hits to the view on the UI thread.
     * {@code everything}: every item and every place the library indexes ({@code searchLibrary});
     * otherwise files only, over the content locations and Bitwig's devices ({@code searchIndex}).
     */
    @SuppressWarnings("rawtypes")
    private void query(String what, Set<String> kinds, String text, int limit, boolean everything,
                       BooleanSupplier cancelled, Consumer<List<IndexHit>> results) {
        try {
            EnumSet wanted = kinds == null ? EnumSet.noneOf(qD.class) : Library.kinds(kinds);
            if (wanted.isEmpty() || limit <= 0) {
                later(() -> deliver(what, Collections.emptyList(), Collections.emptyMap(), cancelled, results));
                return;
            }
            String words = text == null ? "" : text;
            EE library = EE.UNj();
            library.cfM(() -> { // on the library's thread
                List<IndexHit> hits = new ArrayList<>();
                Map<String, CH> kept = new LinkedHashMap<>();
                long started = System.nanoTime();
                int sourceCount = -1;
                try {
                    kyI problems = new kyI();
                    M22 query = FB.cfM(words, wanted, problems); // Bitwig's query for the text and kinds
                    if (problems.cfM() != 0) throw new IllegalArgumentException("the search text: " + problems);
                    IndexCollector collector = new IndexCollector(limit);
                    // searchIndex: the library's locations (rGR: the content locations' files) and
                    // Bitwig's devices (MJ), as Bitwig's own Everything query takes them (Ij).
                    // searchLibrary: also the wanted kinds' sources everywhere the library indexes.
                    Wix sources = Library.sources(library, wanted, everything);
                    sourceCount = sources.cfM().size();
                    sources.cfM(query, collector);
                    for (CH result : collector.found()) {
                        if (!everything && result.Grk() == null) continue; // searchIndex: files only
                        IndexHit hit = Library.hit(result);
                        if (hit == null) continue;
                        hits.add(hit);
                        kept.put(hit.key(), result);
                    }
                } catch (Throwable t) {
                    log(what + ": the library query failed", t);
                    hits.clear();
                    kept.clear();
                }
                State.LOG.log("view " + view.key() + ": " + what + " \"" + words + "\" kinds " + kinds + ", "
                        + sourceCount + " sources, " + hits.size() + " hits (limit " + limit + "), "
                        + (System.nanoTime() - started) / 1_000_000 + " ms");
                later(() -> deliver(what, hits, kept, cancelled, results));
            });
        } catch (Throwable t) {
            log(what + ": starting the library query failed", t);
            later(() -> deliver(what, Collections.emptyList(), Collections.emptyMap(), cancelled, results));
        }
    }

    private void deliver(String what, List<IndexHit> hits, Map<String, CH> kept, BooleanSupplier cancelled,
                         Consumer<List<IndexHit>> results) {
        callView(what + "'s results", () -> {
            if (results == null || (cancelled != null && cancelled.getAsBoolean())) return;
            Library.keep(kept);
            results.accept(List.copyOf(hits));
        });
    }

    @Override
    public boolean insert(IndexHit hit) {
        try {
            if (!live || hit == null || hit.key() == null || hit.key().isBlank()) return false;
            browser.cfM(hit.key()); // as the side browser inserts a result (cFV.cfM(wt): its path)
            return true;
        } catch (Throwable t) {
            log("inserting " + (hit == null ? null : hit.key()) + " failed", t);
            return false;
        }
    }

    @Override
    public boolean dragLibraryItems(Collection<IndexHit> hits, Object dragEvent) {
        try {
            if (!live || hits == null || hits.isEmpty() || !(dragEvent instanceof eoh event)) return false;
            return Library.drag(browser.EUG(), hits, event) > 0; // the side browser's session
        } catch (Throwable t) {
            log("starting the library drag failed", t);
            return false;
        }
    }

    // ---------------------------------------------------------------- the open project's contents

    @Override
    public void projectItems(BooleanSupplier cancelled, Consumer<List<ProjectItem>> results) {
        long started = System.nanoTime();
        Consumer<List<ProjectItem>> done = items -> {
            State.LOG.log("view " + view.key() + ": projectItems, " + items.size() + " items, "
                    + (System.nanoTime() - started) / 1_000_000 + " ms");
            callView("projectItems' results", () -> {
                if (results != null && live && (cancelled == null || !cancelled.getAsBoolean())) results.accept(items);
            });
        };
        Consumer<Throwable> failed = t -> {
            log("listing the project failed", t);
            done.accept(List.of());
        };
        try {
            if (!live) throw new IllegalStateException("The browser panel is closed");
            ProjectWalk.start(project, () -> !live || (cancelled != null && cancelled.getAsBoolean()), done, failed);
        } catch (Throwable t) {
            later(() -> failed.accept(t));
        }
    }

    @Override
    public boolean dragProjectItems(Collection<ProjectItem> items, Object dragEvent) {
        try {
            if (!live || items == null || items.isEmpty() || !(dragEvent instanceof eoh event)) return false;
            int added = 0;
            for (ProjectItem item : items) {
                Object object = ProjectWalk.object(item);
                if (object == null) continue;
                event.cfM(object); // Bitwig's own object, as its drags carry it
                added++;
            }
            return added > 0;
        } catch (Throwable t) {
            log("starting the project drag failed", t);
            return false;
        }
    }

    // ---------------------------------------------------------------- guards

    /** Runs one of the view's callbacks; one that throws makes the view fail. */
    private void callView(String what, Runnable call) {
        try {
            call.run();
        } catch (Throwable t) {
            State.LOG.log("view " + view.key() + ": " + what + " failed", t);
            Views.fail(what + " failed");
        }
    }

    private static void later(Runnable action) {
        dpN.TVZ().asyncExec(action);
    }

    private void log(String what, Throwable t) {
        State.LOG.log("view " + view.key() + ": " + what, t);
    }
}
