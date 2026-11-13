package dev.bitwigre.bml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * macOS: Bitwig's executable (Bitwig Studio.app/Contents/MacOS/BitwigStudio) starts itself
 * again as a child, "BitwigStudio --launch", which runs the JVM inside its own process
 * (JNI_CreateJavaVM); the first process waits for it. That child is the process BML tracks.
 * Closing asks Bitwig to quit, as Cmd+Q does (the standard quit Apple event, sent by osascript),
 * so Bitwig can ask to save first. The first time, macOS asks whether the Terminal that BML was
 * started from may control Bitwig Studio (System Settings > Privacy & Security > Automation).
 */
final class MacPlatformOps implements PlatformOps {
    static final String EXECUTABLE_SUFFIX = "/Contents/MacOS/BitwigStudio";
    static final String BUNDLE_ID = "com.bitwig.studio";

    @Override public String name() { return "macOS"; }
    @Override public boolean launchSupported() { return true; }

    @Override public Set<Long> listBitwigPids() {
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        ProcessHandle.allProcesses().forEach(ph -> {
            if (isBitwigJvm(ph)) out.add(ph.pid());
        });
        return out;
    }

    static boolean isBitwigExecutable(ProcessHandle ph) {
        return ph.info().command().map(c -> c.endsWith(EXECUTABLE_SUFFIX)).orElse(false);
    }

    /** The "--launch" child: Bitwig's executable, started by Bitwig's executable. */
    static boolean isBitwigJvm(ProcessHandle ph) {
        if (!isBitwigExecutable(ph)) return false;
        if (ph.info().arguments().map(args -> Arrays.asList(args).contains("--launch")).orElse(false)) return true;
        return ph.parent().map(MacPlatformOps::isBitwigExecutable).orElse(false);
    }

    @Override public void requestGracefulClose(long pid) throws IOException, InterruptedException {
        if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false) return;
        // Sent without waiting for Bitwig's answer: it may first ask whether to save.
        Process p = new ProcessBuilder("osascript",
                "-e", "if application id \"" + BUNDLE_ID + "\" is running then",
                "-e", "tell application id \"" + BUNDLE_ID + "\"",
                "-e", "ignoring application responses",
                "-e", "quit",
                "-e", "end ignoring",
                "-e", "end tell",
                "-e", "return \"asked\"",
                "-e", "end if",
                "-e", "return \"not running\"")
                .redirectErrorStream(true).start();
        // The first time, osascript waits here until the Automation question is answered.
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroy();
            throw new IOException("macOS did not pass Bitwig the request to quit within 120 seconds. "
                    + "If it asks whether Terminal may control Bitwig Studio, click OK, then press Stop / Quit again.");
        }
        String output;
        try (InputStream in = p.getInputStream()) { output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim(); }
        if (p.exitValue() != 0) {
            if (output.contains("-1743")) throw new IOException("macOS did not allow BML to ask Bitwig to quit. In System Settings > "
                    + "Privacy & Security > Automation, allow Terminal to control Bitwig Studio, then press Stop / Quit again.");
            throw new IOException("Could not ask Bitwig to quit (osascript exit " + p.exitValue() + "). " + output);
        }
        if (output.equals("not running")) throw new IOException("macOS does not list Bitwig Studio (PID " + pid
                + ") as a running application, so BML can't ask it to quit. Quit Bitwig from its own window (Cmd+Q).");
    }

    @Override public void openFolder(Path folder) throws IOException {
        Files.createDirectories(folder);
        try {
            Process p = new ProcessBuilder("open", folder.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) return;
        } catch (IOException notFound) {
            // fall back to AWT below
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        }
        PlatformOps.super.openFolder(folder);
    }
}
