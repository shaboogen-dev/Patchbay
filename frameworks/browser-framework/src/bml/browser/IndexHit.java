package bml.browser;

import java.io.File;

/**
 * One result of {@link Panel#searchIndex}, {@link Panel#searchLibrary} or {@link Panel#listLibrary}:
 * an item of Bitwig's library.
 *
 * <ul>
 * <li>{@code file}: the item's file, or null for an item without one (from {@code searchLibrary} and
 *     {@code listLibrary} only; {@code searchIndex} gives files only). A plug-in's file is its binary.
 * <li>{@code name}: the name Bitwig's library gives it.
 * <li>{@code kind}: its kind as Bitwig names it in lower case ({@code "samples"}, {@code "presets"},
 *     {@code "plugins"}, {@code "devices"}, ...).
 * <li>{@code key}: Bitwig's own path for the item (a file's path; a plug-in's binary and its ID in
 *     the binary): what {@link Panel#insert(IndexHit)} and Bitwig's browser insert.
 * <li>{@code vendor}: a plug-in's vendor, else empty.
 * <li>{@code category}: a device's or plug-in's category as Bitwig's index has it, else empty.
 * <li>{@code format}: a plug-in's format, {@code "vst2"}, {@code "vst3"} or {@code "clap"}, else empty.
 * <li>{@code deviceType}: a device's or plug-in's type, {@code "audio"}, {@code "note"},
 *     {@code "instrument"} or {@code "note_detector"}, else empty.
 * </ul>
 */
public record IndexHit(File file, String name, String kind, String key, String vendor, String category,
                       String format, String deviceType) {

    /** A hit for a file: its path is the key, the other fields empty. */
    public IndexHit(File file, String name, String kind) {
        this(file, name, kind, file == null ? "" : file.getPath(), "", "", "", "");
    }
}
