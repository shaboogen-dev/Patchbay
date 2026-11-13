package dev.bitwigre.bml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

final class SessionInfo {
    String id = "";
    String mode = "";
    long pid = -1;
    long processStartEpochMs = -1;
    String processCommand = "";
    String launched = "";
    String modIds = "";
    Path report;
    Path trace;
    Path control;
    Path archive;
    boolean traceEnabled;
    String status = "";

    static SessionInfo load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); }
        SessionInfo s = new SessionInfo();
        s.id = p.getProperty("session_id", "");
        s.mode = p.getProperty("mode", "");
        s.launched = p.getProperty("launched", "");
        s.modIds = p.getProperty("mod_ids", "");
        s.status = p.getProperty("status", "");
        s.processCommand = p.getProperty("process_command", "");
        try { s.pid = Long.parseLong(p.getProperty("pid", "-1")); } catch (NumberFormatException ignored) {}
        try { s.processStartEpochMs = Long.parseLong(p.getProperty("process_start_epoch_ms", "-1")); } catch (NumberFormatException ignored) {}
        s.report = path(p.getProperty("report"));
        s.trace = path(p.getProperty("trace"));
        s.control = path(p.getProperty("control"));
        s.archive = path(p.getProperty("archive"));
        String traceFlag = p.getProperty("trace_enabled", "").trim();
        s.traceEnabled = traceFlag.isEmpty() ? (s.report != null || s.trace != null || s.control != null)
                : ("1".equals(traceFlag) || "true".equalsIgnoreCase(traceFlag));
        return s;
    }

    void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Properties p = new Properties();
        put(p,"session_id",id); put(p,"mode",mode); put(p,"launched",launched); put(p,"mod_ids",modIds); put(p,"status",status);
        put(p,"process_command",processCommand);
        if (pid > 0) p.setProperty("pid", Long.toString(pid));
        if (processStartEpochMs > 0) p.setProperty("process_start_epoch_ms", Long.toString(processStartEpochMs));
        p.setProperty("trace_enabled", traceEnabled ? "1" : "0");
        put(p,"report",str(report)); put(p,"trace",str(trace)); put(p,"control",str(control)); put(p,"archive",str(archive));
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            p.store(w, "BML tracked session");
        }
    }

    void captureProcessIdentity() {
        if (pid <= 0) return;
        ProcessHandle.of(pid).ifPresent(ph -> {
            ph.info().startInstant().ifPresent(i -> processStartEpochMs = i.toEpochMilli());
            ph.info().command().ifPresent(c -> processCommand = c);
        });
    }

    boolean processAlive() {
        if (pid <= 0) return false;
        Optional<ProcessHandle> opt = ProcessHandle.of(pid);
        if (opt.isEmpty() || !opt.get().isAlive()) return false;

        ProcessHandle ph = opt.get();
        if (processStartEpochMs <= 0 && (processCommand == null || processCommand.isBlank())) return false;
        if (processStartEpochMs > 0) {
            Optional<Instant> start = ph.info().startInstant();
            if (start.isPresent() && start.get().toEpochMilli() != processStartEpochMs) return false;
        } else if (processCommand != null && !processCommand.isBlank()) {
            Optional<String> command = ph.info().command();
            if (command.isPresent() && !sameCommand(command.get(), processCommand)) return false;
        }
        return true;
    }

    boolean complete() {
        return "complete".equalsIgnoreCase(status);
    }

    boolean hasTraceReport() {
        return traceEnabled && report != null && Files.isDirectory(report);
    }

    String identityKey() {
        return id + "|" + pid + "|" + processStartEpochMs;
    }

    private static boolean sameCommand(String a, String b) {
        try {
            return Path.of(a).toAbsolutePath().normalize().equals(Path.of(b).toAbsolutePath().normalize());
        } catch (Exception ignored) {
            return a.equalsIgnoreCase(b);
        }
    }

    private static Path path(String s) { return s == null || s.isBlank() ? null : Path.of(s).toAbsolutePath().normalize(); }
    private static String str(Path p) { return p == null ? "" : p.toAbsolutePath().normalize().toString(); }
    private static void put(Properties p,String k,String v) { if (v != null && !v.isBlank()) p.setProperty(k,v); }
}
