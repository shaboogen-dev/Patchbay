package dev.bitwigre.bml;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Tools: small helpers for a setting of one mod that must be right before Bitwig starts (so they
 * live in the window, not in Bitwig's Settings). A tool is reached from its mod's selection bar on
 * the Mods page; what it finds wrong is listed on Errors and blocks Load while that mod is ticked.
 * Adding a tool means adding it to {@link #ALL}.
 */
final class BmlTools {
    private BmlTools(){}

    static final List<Tool> ALL=List.of(new DeviceFoldersTool());

    /** Something a tool found wrong with a mod's setup. While that mod is ticked, its issue is an error
     *  on Errors and Load refuses. {@code subject} is what's wrong, for the tool: a folder, say. */
    record Problem(String toolName,String modId,PackageInventory.Issue issue,String subject) {}

    interface Tool {
        String name();
        /** Why the tool can't be used now; null if it can. Runs off the event thread. */
        String unavailable(Path modsRoot);
        /** What is wrong now, ticked or not. Runs off the event thread, and must come back within seconds
         *  whatever the disks are doing. */
        default List<Problem> problems(Path modsRoot){return List.of();}
        /** The label of the one-click fix offered with a problem; null for none. */
        default String resolveLabel(){return null;}
        /** Carries out that fix. */
        default void resolve(Path modsRoot,Problem problem) throws IOException {}
    }
}
