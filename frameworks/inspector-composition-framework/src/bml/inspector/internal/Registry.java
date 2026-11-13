package bml.inspector.internal;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import bml.api.Bml;
import bml.inspector.Block;
import bml.inspector.BlockContext;
import bml.inspector.Blocks;
import bml.inspector.BuiltBlock;
import bml.inspector.Context;
import bml.inspector.DeviceRows;
import bml.inspector.InspectorProvider;

/** Every mod's declared blocks and device rows, in the order they appear. */
final class Registry {
    static final String FRAMEWORK = "inspector-composition-framework";
    static final Bml.Mod LOG = Bml.mod(FRAMEWORK);
    private static final String ID = "[A-Za-z][A-Za-z0-9_.-]*";

    /** A declared block. */
    static final class Declared implements Block {
        final String modId, id, title;
        final Set<Context> contexts = EnumSet.noneOf(Context.class);
        boolean top;
        Function<BlockContext, Object> content;

        Declared(String modId, String id, String title) {
            this.modId = modId;
            this.id = id;
            this.title = title;
        }

        String who() {
            return modId + "/" + id;
        }

        @Override
        public Block in(Context... contexts) {
            this.contexts.addAll(Arrays.asList(contexts));
            return this;
        }

        @Override
        public Block top() {
            top = true;
            return this;
        }

        @Override
        public Block bottom() {
            top = false;
            return this;
        }

        @Override
        public Block content(Function<BlockContext, Object> content) {
            this.content = content;
            return this;
        }
    }

    /** Declared device rows. */
    static final class Rows implements DeviceRows {
        final String modId, id;
        Function<BlockContext, Object> content;

        Rows(String modId, String id) {
            this.modId = modId;
            this.id = id;
        }

        String who() {
            return modId + "/" + id;
        }

        @Override
        public DeviceRows content(Function<BlockContext, Object> content) {
            this.content = content;
            return this;
        }
    }

    /** A mod's listener for built blocks. */
    record Listener(String modId, Consumer<BuiltBlock> listener) {
    }

    /** One provider's declarations, before they are checked. */
    private static final class Declaration implements Blocks {
        final String modId;
        final List<Declared> blocks = new ArrayList<>();
        final List<Rows> rows = new ArrayList<>();
        final List<Listener> listeners = new ArrayList<>();

        Declaration(String modId) {
            this.modId = modId;
        }

        @Override
        public Block block(String blockId, String title) {
            Declared d = new Declared(modId, blockId, title);
            blocks.add(d);
            return d;
        }

        @Override
        public DeviceRows deviceRows(String rowsId) {
            Rows r = new Rows(modId, rowsId);
            rows.add(r);
            return r;
        }

        @Override
        public void onBlockBuilt(Consumer<BuiltBlock> listener) {
            if (listener == null) LOG.log("mod " + modId + ": onBlockBuilt(null) ignored");
            else listeners.add(new Listener(modId, listener));
        }
    }

    private static List<Declared> blocks = List.of();
    private static List<Rows> rows = List.of();
    private static List<Listener> listeners = List.of();
    private static boolean trackBlocks;
    private static boolean loaded;

    private Registry() {
    }

    /** Blocks in their order: top blocks first, then by title (case-insensitive), mod ID and block ID. */
    static synchronized List<Declared> blocks() {
        load();
        return blocks;
    }

    /** Device rows in their order: by mod ID, then in the order the mod declared them. */
    static synchronized List<Rows> rows() {
        load();
        return rows;
    }

    /** Listeners for built blocks, in the order of their mods' IDs. */
    static synchronized List<Listener> listeners() {
        load();
        return listeners;
    }

    /** Whether any mod adds a block to the Track Inspector. */
    static synchronized boolean trackBlocks() {
        load();
        return trackBlocks;
    }

    /** Asks every registered provider for its blocks. Once. */
    private static void load() {
        if (loaded) return;
        loaded = true;
        List<Declared> allBlocks = new ArrayList<>();
        List<Rows> allRows = new ArrayList<>();
        List<Listener> allListeners = new ArrayList<>();
        for (Bml.Registration reg : Bml.registrations(FRAMEWORK)) {
            Declaration d = new Declaration(reg.modId());
            try {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(reg.descriptor())) {
                    p.load(in);
                }
                if (!"1".equals(p.getProperty("api.version", "").trim()))
                    throw new IllegalArgumentException("api.version must be 1 in " + reg.descriptor());
                String className = p.getProperty("provider.class", "").trim();
                Class<?> type = Class.forName(className, true, Registry.class.getClassLoader());
                ((InspectorProvider) type.getConstructor().newInstance()).declare(d);
            } catch (Throwable t) {
                LOG.log("mod " + reg.modId() + ": blocks skipped", t);
                continue;
            }
            Set<String> ids = new HashSet<>();
            for (Declared b : d.blocks) {
                if (b.id == null || !b.id.matches(ID)) LOG.log("block " + b.who() + ": invalid ID; skipped");
                else if (!ids.add("block:" + b.id)) LOG.log("block " + b.who() + ": the mod already declared that ID; skipped");
                else if (b.title == null || b.title.isBlank()) LOG.log("block " + b.who() + " has no title; skipped");
                else if (b.contexts.isEmpty()) LOG.log("block " + b.who() + " has no context (in); skipped");
                else if (b.content == null) LOG.log("block " + b.who() + " has no content; skipped");
                else allBlocks.add(b);
            }
            for (Rows r : d.rows) {
                if (r.id == null || !r.id.matches(ID)) LOG.log("device rows " + r.who() + ": invalid ID; skipped");
                else if (!ids.add("rows:" + r.id)) LOG.log("device rows " + r.who() + ": the mod already declared that ID; skipped");
                else if (r.content == null) LOG.log("device rows " + r.who() + " have no content; skipped");
                else allRows.add(r);
            }
            allListeners.addAll(d.listeners);
        }
        allBlocks.sort(Comparator.comparing((Declared b) -> !b.top)
                .thenComparing(b -> b.title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(b -> b.modId)
                .thenComparing(b -> b.id));
        blocks = List.copyOf(allBlocks);
        rows = List.copyOf(allRows); // registrations come ordered by mod ID, then registration ID
        listeners = List.copyOf(allListeners); // the same order
        trackBlocks = blocks.stream().anyMatch(b -> b.contexts.contains(Context.TRACK));
        LOG.log("blocks: " + blocks.size() + ", device rows: " + rows.size() + ", block listeners: " + listeners.size());
    }
}
