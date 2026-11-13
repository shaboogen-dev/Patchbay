package dev.bitwigre.bml;

import java.io.IOException;
import java.util.*;

/**
 * Platform-neutral shell for the UI/core on platforms without a launch/process adapter.
 * Windows, Linux and macOS have their own.
 */
final class PortablePlatformOps implements PlatformOps {
    @Override public String name() { return System.getProperty("os.name", "Unknown"); }
    @Override public boolean launchSupported() { return false; }
    @Override public Set<Long> listBitwigPids() { return Set.of(); }
    @Override public void requestGracefulClose(long pid) throws IOException {
        throw new IOException("Bitwig process control is not implemented for " + name() + " yet.");
    }
}
