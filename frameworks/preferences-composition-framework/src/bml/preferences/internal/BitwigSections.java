package bml.preferences.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import bml.preferences.Page;
import bml.preferences.Section;

/**
 * Bitwig's own Settings pages and sections, by their public IDs. Bitwig builds each section with
 * a heading key ({@code device}) or, on Controllers, a fixed English title; the framework finds the
 * section by it.
 */
final class BitwigSections {
    static final List<Page> PAGES = List.of(Page.USER_INTERFACE, Page.BEHAVIOUR, Page.AUDIO, Page.RECORDING,
            Page.CONTROLLERS, Page.SYNCHRONIZATION, Page.LOCATIONS, Page.PLUGINS, Page.SHORTCUTS);

    /** Bitwig's heading key or title -> the section's public ID. */
    private static final Map<String, Section> BY_KEY = new LinkedHashMap<>();
    /** Plug-in Interface Scaling has a translated title; the framework finds it by its builder instead. */
    static final Section INTERFACE_SCALING = Section.of("plugins/interface-scaling");

    static {
        add("user-interface", "language", "language");
        add("user-interface", "display-profile", "display_profile");
        add("user-interface", "contrast", "contrast");
        add("user-interface", "view", "view");
        add("user-interface", "mouse", "mouse");
        add("user-interface", "transport", "transport");
        add("behaviour", "update-notifications", "notifications");
        add("behaviour", "open-on-start", "open_on_start");
        add("behaviour", "template", "template");
        add("behaviour", "audio-import", "audio_import_and_preview");
        add("behaviour", "defaults", "defaults");
        add("behaviour", "fades", "auto_fade.auto_fade_header");
        add("behaviour", "sequencer", "sequencer_header");
        add("behaviour", "device", "device");
        add("behaviour", "solo", "solo");
        add("behaviour", "select-tracks", "select_tracks_header");
        add("behaviour", "play-start-marker", "update_play_start_header");
        add("behaviour", "resume-playback", "resume_playback");
        add("behaviour", "usage-data", "telemetry");
        add("recording", "recording", "recording");
        add("recording", "arranger-parameter-recording", "arranger_parameter_recording");
        add("recording", "auto-arm", "auto_arm");
        add("recording", "pre-roll", "pre_roll");
        add("recording", "record-quantization", "record_quantization");
        add("recording", "created-audio", "recorded_media");
        add("controllers", "takeover-mode", "Takeover mode");
        add("controllers", "controllers", "Controllers");
        add("synchronization", "transport-sync", "synchronization.transport");
        add("synchronization", "midi-sync", "synchronization.midi_outputs");
        add("locations", "downloaded-packages", "packages_location");
        add("locations", "analysis-cache", "cache.decoding");
        add("shortcuts", "edit-shortcuts-for", "edit_shortcuts_for");
    }

    private BitwigSections() {
    }

    private static void add(String page, String section, String key) {
        BY_KEY.put(key, Section.of(page + "/" + section));
    }

    /** The section Bitwig builds with this heading key or title, or null. */
    static Section byKey(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    static boolean isBitwigs(Section s) {
        return INTERFACE_SCALING.equals(s) || BY_KEY.containsValue(s);
    }

    static boolean isBitwigs(Page p) {
        return PAGES.contains(p);
    }

    /** Every Bitwig section ID, for the log and tests. */
    static List<Section> all() {
        List<Section> out = new java.util.ArrayList<>(BY_KEY.values());
        out.add(INTERFACE_SCALING);
        return out;
    }
}
