package bml.pluginpanels.internal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import bitwig.obf.dpN; // Bitwig's UI thread
import bitwig.obf.dve; // a widget
import bitwig.obf.uUk; // a plug-in parameter
import bml.actions.ActionContext;
import bml.actions.ActionProvider;
import bml.actions.Actions;
import bml.actions.Menu;
import com.bitwig.flt.app.CHR; // the application window controller (shows notifications)
import com.bitwig.flt.document.core.master.device.dKO; // a plug-in parameter, as stored (asks the plug-in for value texts)

/**
 * "Generate Parameter Sheet" in a plug-in device's right-click menu: a JSON parameter sheet for a layout
 * builder (its format is in the framework's README): the plug-in's identity, and for every parameter
 * its position, ID, name, current value and text, steps and step names, and the plug-in's text at 0
 * and at 1. Copied to the clipboard and written to {@code sheets/<plug-in> <format>.parameters.json}
 * in the framework's settings folder. Registered with Framework: Action and Menu Registration, with
 * Generate Theme Sheet ({@link CopyTheme}) and Save Panel from Clipboard ({@link SavePanel}). The
 * action IDs keep their first names ("copy"). All three are menu rows only (Action and Menu's
 * {@code menuOnly}): each acts on the right-clicked device, which a shortcut doesn't have.
 */
public final class CopyParameters implements ActionProvider {
    static final String SHEET = "plugin-panels-parameter-sheet";
    static final int SHEET_VERSION = 1;
    private static final long WAIT_S = 3; // for the plug-in's texts at 0 and 1

    @Override
    public void declare(Actions actions) {
        actions.action("plugin_panels_framework_copy_parameters", "Generate Parameter Sheet")
               .in(Menu.DEVICE)
               .menuOnly() // it acts on the right-clicked device: a shortcut has none
               .visibleWhen(context -> Plugin.ofDevice(context.device()) != null)
               .onRun(CopyParameters::run);
        actions.action("plugin_panels_framework_copy_theme", "Generate Theme Sheet")
               .in(Menu.DEVICE)
               .menuOnly()
               .visibleWhen(context -> CopyTheme.debugMode() && Plugin.ofDevice(context.device()) != null) // a developer tool: Debug Mode only
               .onRun(CopyTheme::run);
        actions.action("plugin_panels_framework_save_panel", "Save Panel from Clipboard")
               .in(Menu.DEVICE)
               .menuOnly()
               .visibleWhen(context -> Plugin.ofDevice(context.device()) != null)
               .onRun(SavePanel::run);
    }

    private static void run(ActionContext context) {
        try {
            Plugin plugin = Plugin.ofDevice(context.device());
            if (plugin == null) return;
            List<uUk> params = plugin.parameters();
            List<CompletableFuture<Object>> ends = new ArrayList<>(); // per parameter: its text at 0, at 1
            for (uUk p : params) {
                ends.add(textAt(p, 0));
                ends.add(textAt(p, 1));
            }
            CompletableFuture.allOf(ends.toArray(new CompletableFuture[0])).orTimeout(WAIT_S, TimeUnit.SECONDS)
                    .whenComplete((ignored, failure) -> dpN.TVZ().asyncExec(() -> {
                        try {
                            String text = sheet(plugin, params, ends);
                            boolean copied = copy(context.widget(), text);
                            Path file = write(sheetName(plugin), text);
                            String where = file == null ? "" : " and saved to " + file;
                            Panels.MOD.log("parameter sheet of " + plugin.name() + (copied ? " copied" : " not copied (no window)") + where);
                            CHR.Grk().hUR(params.size() + " parameters of " + plugin.name() + (copied ? " copied" : " saved")); // notification
                        } catch (Throwable t) {
                            Panels.report("copy", "Generate Parameter Sheet failed", t);
                        }
                    }));
        } catch (Throwable t) {
            Panels.report("copy", "Generate Parameter Sheet failed", t);
        }
    }

    /** The plug-in's text for a normalised value, or a future of null. */
    @SuppressWarnings("unchecked")
    private static CompletableFuture<Object> textAt(uUk p, double value) {
        try {
            if (p instanceof dKO stored) return ((CompletionStage<Object>) stored.nVd(value)).toCompletableFuture(); // the plug-in's text for that value
        } catch (Throwable ignored) {
            // no text
        }
        return CompletableFuture.completedFuture(null);
    }

    private static Object answer(CompletableFuture<Object> f) {
        return f.isDone() && !f.isCompletedExceptionally() ? f.getNow(null) : null;
    }

    static String sheet(Plugin plugin, List<uUk> params, List<CompletableFuture<Object>> ends) {
        String nl = System.lineSeparator();
        StringBuilder out = new StringBuilder("{").append(nl);
        out.append("  \"sheet\": ").append(str(SHEET)).append(", \"version\": ").append(SHEET_VERSION).append(',').append(nl);
        out.append("  \"plugin\": { \"name\": ").append(str(plugin.name())).append(", \"vendor\": ").append(str(plugin.vendor()))
           .append(", \"format\": ").append(str(plugin.format())).append(", \"id\": ").append(str(plugin.pluginId()))
           .append(", \"category\": ").append(str(plugin.category())).append(" },").append(nl);
        out.append("  \"parameters\": [").append(nl);
        for (int i = 0; i < params.size(); i++) {
            uUk p = params.get(i);
            Object min = answer(ends.get(2 * i)), max = answer(ends.get(2 * i + 1));
            out.append("    { \"index\": ").append(i + 1)
               .append(", \"id\": ").append(str(Plugin.paramId(p)))
               .append(", \"name\": ").append(str(Plugin.paramName(p)))
               .append(", \"value\": ").append(num(Plugin.normalized(p)))
               .append(", \"text\": ").append(str(Plugin.display(p)))
               .append(", \"steps\": ").append(Plugin.steps(p))
               .append(", \"step_names\": [");
            String[] names = Plugin.stepNames(p);
            for (int k = 0; k < names.length; k++) out.append(k == 0 ? "" : ", ").append(str(names[k]));
            out.append("], \"min_text\": ").append(min == null ? "null" : str(min.toString()))
               .append(", \"max_text\": ").append(max == null ? "null" : str(max.toString()))
               .append(" }").append(i < params.size() - 1 ? "," : "").append(nl);
        }
        out.append("  ]").append(nl).append('}').append(nl);
        return out.toString();
    }

    private static String num(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e9 ? Long.toString((long) d) : String.format(Locale.ROOT, "%.6f", d);
    }

    /** A JSON string. */
    static String str(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : (s == null ? "" : s).toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    /** Bitwig's own clipboard, as its plug-in error list copies text. */
    private static boolean copy(Object widget, String text) {
        if (!(widget instanceof dve w)) return false;
        w.kI_().MIO().cfM(text); // the window's clipboard: set its text
        return true;
    }

    /** {@code <name> <format>.parameters.json}, from the plug-in's name with only safe characters. */
    private static String sheetName(Plugin plugin) {
        String safe = plugin.name().replaceAll("[^A-Za-z0-9 ._-]", "_").trim();
        if (safe.isEmpty()) safe = "plugin";
        return safe + " " + plugin.format() + ".parameters.json";
    }

    /** {@code sheets/<file>} in the framework's settings folder; null when it can't be written. */
    static Path write(String fileName, String text) {
        try {
            Path folder = Components.dataFolder().resolve("sheets");
            Files.createDirectories(folder);
            Path file = folder.resolve(fileName);
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return file;
        } catch (Throwable t) {
            Panels.report("copy-file", "writing " + fileName + " failed", t);
            return null;
        }
    }
}
