package bml.pluginpanels.internal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import bitwig.obf.dve; // a widget
import bml.actions.ActionContext;
import com.bitwig.flt.app.CHR; // the application window controller (shows notifications)

/**
 * "Save Panel from Clipboard" in a plug-in device's right-click menu: takes a panel copied from a
 * layout builder, checks it as the framework reads panels, and saves it in Patchbay's
 * {@code components/} folder as {@code <panel name>.json} (the file's {@code "name"}, else the
 * plug-in name its first layout matches). A file of that name is replaced: saving an edited panel
 * again updates it. A new panel is used once it is chosen in Patchbay and Bitwig starts again; a
 * chosen one that is replaced applies the next time Bitwig builds the plug-in's page.
 */
final class SavePanel {
    private SavePanel() {
    }

    static void run(ActionContext context) {
        try {
            if (!(context.widget() instanceof dve widget)) return;
            var clipboard = widget.kI_().MIO().Hnt(); // the window's clipboard, as Bitwig's own Paste reads it
            String text = clipboard == null ? null : clipboard.lYc(); // its plain text
            if (text == null || text.isBlank()) {
                notify("No panel to save: the clipboard has no text. Copy the panel's JSON in the layout builder first.");
                return;
            }
            List<Layouts.Layout> layouts;
            try {
                layouts = Layouts.parse(text, "the clipboard");
            } catch (Throwable t) {
                notify("The clipboard doesn't hold a valid panel: " + t.getMessage());
                return;
            }
            if (layouts.isEmpty()) {
                notify("The clipboard's panel has no layouts.");
                return;
            }
            String name = name(text, layouts.get(0));
            Path folder = Components.folder();
            Files.createDirectories(folder);
            Path file = folder.resolve(fileName(name));
            boolean replaced = Files.exists(file);
            boolean chosen = Components.panels().contains(file.toAbsolutePath().normalize());
            Files.writeString(file, text, StandardCharsets.UTF_8);
            Panels.MOD.log("panel " + name + (replaced ? " replaced: " : " saved: ") + file);
            if (chosen) notify("Panel " + name + " updated. Reload the plug-in to see it.");
            else notify("Panel " + name + (replaced ? " replaced" : " saved") + " in components. Choose it in Patchbay, then start Bitwig again.");
        } catch (Throwable t) {
            Panels.report("save-panel", "Save Panel from Clipboard failed", t);
            notify("Saving the panel failed: " + t.getMessage());
        }
    }

    /** The panel's name: the file's "name", else the plug-in name its first layout matches. */
    private static String name(String text, Layouts.Layout first) {
        if (Json.parse(text) instanceof Map<?, ?> top) {
            Object n = top.get("name"); // text, or a whole number read as its digits (as every text in a layout)
            String s = n instanceof String t ? t : n instanceof Double d && d == Math.rint(d) ? Long.toString(d.longValue()) : "";
            if (!s.isBlank()) return s.trim();
        }
        return first.name().equals("*") ? "All plug-ins" : first.name();
    }

    /** {@code <name>.json}, with only safe characters. */
    static String fileName(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9 ._()-]", "_").trim();
        while (safe.startsWith(".")) safe = safe.substring(1);
        if (safe.isEmpty()) safe = "panel";
        return safe + ".json";
    }

    private static void notify(String message) {
        try {
            CHR.Grk().hUR(message); // Bitwig's notification
        } catch (Throwable t) {
            Panels.MOD.log(message);
        }
    }
}
