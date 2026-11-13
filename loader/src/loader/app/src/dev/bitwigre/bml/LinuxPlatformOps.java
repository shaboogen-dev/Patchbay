package dev.bitwigre.bml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Linux: Bitwig's launcher (/opt/bitwig-studio/bitwig-studio) starts its JVM as
 * bin/BitwigStudio (a link to the bundled lib/jre/bin/java) with the main class
 * com.bitwig.flt.app.BitwigStudioMain on its command line. That JVM is the process BML tracks.
 * Closing it asks its X11 window to close (WM_DELETE_WINDOW), as clicking the window's close
 * button does, so Bitwig can ask to save first.
 */
final class LinuxPlatformOps implements PlatformOps {
    static final String BITWIG_MAIN_CLASS = "com.bitwig.flt.app.BitwigStudioMain";

    @Override public String name() { return "Linux"; }
    @Override public boolean launchSupported() { return true; }

    @Override public Set<Long> listBitwigPids() {
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        ProcessHandle.allProcesses().forEach(ph -> {
            if (isBitwigJvm(ph)) out.add(ph.pid());
        });
        return out;
    }

    static boolean isBitwigJvm(ProcessHandle ph) {
        // Other users' processes give no command line, so only this user's Bitwig is found.
        return ph.info().arguments().map(args -> Arrays.asList(args).contains(BITWIG_MAIN_CLASS)).orElse(false);
    }

    @Override public void requestGracefulClose(long pid) throws IOException {
        if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false) return;
        int asked = X11WindowClose.requestClose(pid);
        if (asked == 0) throw new IOException("Could not find Bitwig's window (PID " + pid + ") on display "
                + System.getenv("DISPLAY") + " to ask it to close. Close Bitwig from its own window.");
    }

    @Override public void openFolder(Path folder) throws IOException {
        Files.createDirectories(folder);
        // xdg-open is the desktop's own "open this folder"; AWT's Desktop needs GNOME libraries.
        try {
            Process p = new ProcessBuilder("xdg-open", folder.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() == 0) return; // still running: it is opening the folder
        } catch (IOException notInstalled) {
            // fall back to AWT below
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        }
        PlatformOps.super.openFolder(folder);
    }
}
