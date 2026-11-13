package bml.browser;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One side browser panel showing a view's content, and what the view can do there. There is one per
 * window's Browser panel; it lives as long as that side browser (the windows are rebuilt when the
 * user chooses another view, and when a project closes). Bitwig's own browser stays built underneath,
 * hidden, so its cue player and library keep working.
 *
 * <p>Call on Bitwig's UI thread. A helper that Bitwig refuses or that fails returns {@code false}
 * (or an empty value) and is logged; it never throws. A callback the view hands in that throws makes
 * the view's claims and content lapse (logged), as a failing {@code onActive} does.
 */
public interface Panel {
    /** Bitwig's preview stretch modes ({@link #stretchMode()}). */
    int STRETCH_SYNC_TO_PROJECT = 0, STRETCH_ORIGINAL_SPEED = 1, STRETCH_RAW = 2;

    /** The view's ID, as declared. */
    String viewId();

    /** True until the panel is disposed. */
    boolean live();

    /** The view's widget in this panel (what {@link ContentFactory#create} returned). */
    Object content();

    /** Bitwig's side browser this panel belongs to (a {@code cFV}, as the mod kit names it). */
    Object browser();

    /**
     * A map for the view's own state, the same object for every panel of the same project while the
     * project is open, so state survives the windows being rebuilt. Keep plain values in it, not
     * Bitwig's objects or widgets.
     */
    Map<String, Object> state();

    /** Runs {@code action} when the panel is disposed (its side browser is). */
    void onDispose(Runnable action);

    /** Gives the view's widget the keyboard focus. */
    boolean focus();

    /** Shows the window's Browser panel if it is hidden (Bitwig's own Browser panel action). */
    boolean showPanel();

    /**
     * Declares the view's own search field in this panel: one of Bitwig's widgets (a {@code dve}, as
     * the mod kit names it) inside the view's content, or null for none. While the view shows,
     * Bitwig's own search field is hidden under it; a mod that focuses "the side browser's search
     * field" asks {@link ViewSearch#focus} first, which focuses this one. Without a declaration it
     * gets false and does what it would do without the view.
     */
    void searchField(Object widget);

    /** Gives the declared search field the keyboard focus; false when there is none (or the panel is gone). */
    boolean focusSearch();

    // ---------------------------------------------------------------- audition (Bitwig's cue player)

    /** Loads {@code file} into the panel's cue player and plays it when loaded; the same file plays again. */
    boolean preview(File file);

    /** Stops the cue player. */
    boolean stopPreview();

    /** True while the cue player plays. */
    boolean previewPlaying();

    /** Plays the loaded file from {@code beat} (Bitwig's preview position, in beats). */
    boolean playFrom(double beat);

    /** The loaded file's length in beats, as the cue player reports it, or {@code NaN}. */
    double previewDurationBeats();

    /**
     * Where playback is, in beats from the file's start: worked out from when playback started and the
     * tempo it plays at (the cue player doesn't report its position). -1 when not playing.
     */
    double playheadBeats();

    /** Bitwig's stretch mode for previews ({@link #STRETCH_SYNC_TO_PROJECT}, ...), or -1. */
    int stretchMode();

    /** Sets Bitwig's stretch mode for previews (its preference, as the preview player's own button does). */
    boolean setStretchMode(int mode);

    /** Calls {@code listener} when a file this panel asked to preview has loaded (on Bitwig's UI thread). */
    void onPreviewLoaded(Consumer<File> listener);

    // ---------------------------------------------------------------- files and the project

    /**
     * Imports an audio file as a clip on the selected track's arranger lane at the play cursor, with
     * Bitwig's own clip import. {@code done} gets null when Bitwig reports the import complete, else
     * the reason (no track selected, refused, the import failed); {@code done} may be null.
     */
    void insertAtPlayCursor(File file, Consumer<Throwable> done);

    /** Inserts a file the way Bitwig's browser inserts a result (presets, clips, devices, ...). */
    boolean insertWithBitwig(File file);

    /**
     * Starts Bitwig's file drag with {@code files}: {@code dragEvent} is the drag event Bitwig gave the
     * view's widget when the drag began (an {@code eoh}, as the mod kit names it).
     */
    boolean dragFiles(Collection<File> files, Object dragEvent);

    /** Bitwig's content locations (Settings > Locations), in Bitwig's order. */
    List<ContentLocation> contentLocations();

    /** The open project's folder as Bitwig reports it, or null (a project never saved). */
    File projectFolder();

    /** Shows {@code file} in the system's file manager (Bitwig's own Show in Explorer / Finder). */
    boolean revealInFileManager(File file);

    /**
     * Searches Bitwig's library index: files of the given kinds ({@code "samples"}, {@code "presets"},
     * {@code "clips"}, {@code "multisamples"}, ... Bitwig's kind names) matching {@code text} with
     * Bitwig's own search syntax, at most {@code limit}. Runs on Bitwig's library thread;
     * {@code results} gets the hits once, on the UI thread, unless {@code cancelled} answers true by
     * then (an empty list when the search fails; logged).
     */
    void searchIndex(Set<String> kinds, String text, int limit, BooleanSupplier cancelled,
                     Consumer<List<IndexHit>> results);

    // ---------------------------------------------------------------- library items (plug-ins, devices, ...)

    /**
     * As {@link #searchIndex}, for every item of Bitwig's library, those without a file included:
     * {@code "plugins"} (VST2, VST3 and CLAP, as Bitwig's plug-in scan found them), {@code "devices"}
     * (Bitwig's own), and the file kinds. Hits carry the vendor, category, format and device type
     * where Bitwig's index has them.
     */
    void searchLibrary(Set<String> kinds, String text, int limit, BooleanSupplier cancelled,
                       Consumer<List<IndexHit>> results);

    /** Every item of the given kinds, at most {@code limit}: {@link #searchLibrary} without search text. */
    void listLibrary(Set<String> kinds, int limit, BooleanSupplier cancelled, Consumer<List<IndexHit>> results);

    /**
     * Inserts a library item the way Bitwig's side browser inserts a result on Enter or a double-click
     * (a plug-in or device on the selected track, a preset, a clip, a file).
     */
    boolean insert(IndexHit hit);

    /**
     * Starts Bitwig's drag of library items, as dragging results out of Bitwig's browser does:
     * {@code dragEvent} is the drag event Bitwig gave the view's widget (an {@code eoh}).
     */
    boolean dragLibraryItems(Collection<IndexHit> hits, Object dragEvent);

    // ---------------------------------------------------------------- the open project's contents

    /**
     * Lists the open project's tracks, and on each its devices and clips, in Bitwig's order (a track,
     * then what is on it), from Bitwig's own project model on the UI thread, in short slices so the UI
     * stays responsive. {@code results} gets the list once, on the UI thread, unless {@code cancelled}
     * answers true by then (an empty list when it fails; logged). Each call lists the project as it is
     * then; call again to refresh.
     */
    void projectItems(BooleanSupplier cancelled, Consumer<List<ProjectItem>> results);

    /**
     * Starts Bitwig's drag with project items (Bitwig's own objects for them, as its own drags of
     * tracks, devices and clips carry them). Items Bitwig no longer has are left out.
     */
    boolean dragProjectItems(Collection<ProjectItem> items, Object dragEvent);
}
