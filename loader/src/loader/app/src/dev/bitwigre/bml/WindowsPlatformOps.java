package dev.bitwigre.bml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class WindowsPlatformOps implements PlatformOps {
    @Override public String name() { return "Windows"; }
    @Override public boolean launchSupported() { return true; }

    @Override public Set<Long> listBitwigPids() {
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        ProcessHandle.allProcesses().forEach(ph -> {
            String cmd = ph.info().command().orElse("").replace('\\','/').toLowerCase(Locale.ROOT);
            if (cmd.endsWith("/bitwigstudioapp.exe") || cmd.endsWith("/bitwig studio.exe")) out.add(ph.pid());
        });
        // Bitwig Studio.exe is the launcher wrapper; prefer actual app pids when present.
        LinkedHashSet<Long> app = new LinkedHashSet<>();
        ProcessHandle.allProcesses().forEach(ph -> {
            String cmd = ph.info().command().orElse("").replace('\\','/').toLowerCase(Locale.ROOT);
            if (cmd.endsWith("/bitwigstudioapp.exe")) app.add(ph.pid());
        });
        return app.isEmpty() ? out : app;
    }

    @Override public void requestGracefulClose(long pid) throws IOException, InterruptedException {
        String script = "$p=Get-Process -Id " + pid + " -ErrorAction SilentlyContinue; " +
                "if($null -eq $p){exit 0}; " +
                "$ok=$p.CloseMainWindow(); if(-not $ok){exit 3}; exit 0";
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true).start();
        String output;
        try (InputStream in = p.getInputStream()) { output = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        int rc = p.waitFor();
        if (rc != 0) throw new IOException("Could not request Bitwig to close its main window (exit " + rc + "). " + output.trim());
    }
}
