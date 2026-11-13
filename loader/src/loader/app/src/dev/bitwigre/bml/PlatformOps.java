package dev.bitwigre.bml;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

interface PlatformOps {
    String name();
    boolean launchSupported();
    Set<Long> listBitwigPids() throws IOException;
    void requestGracefulClose(long pid) throws IOException, InterruptedException;

    default boolean waitForExit(long pid, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false) return true;
            Thread.sleep(250);
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false;
    }

    default void openFolder(Path folder) throws IOException {
        Files.createDirectories(folder);
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException("Desktop folder opening is not supported on this platform/runtime.");
        }
        Desktop.getDesktop().open(folder.toFile());
    }

    static PlatformOps detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return new WindowsPlatformOps();
        if (os.contains("linux")) return new LinuxPlatformOps();
        if (os.startsWith("mac")) return new MacPlatformOps();
        return new PortablePlatformOps();
    }
}
