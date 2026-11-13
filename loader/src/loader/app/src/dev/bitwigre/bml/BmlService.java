package dev.bitwigre.bml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

final class BmlService {
    static final String VERSION = JavaPreparer.LOADER_VERSION;

    private final BmlPaths paths;
    private final ModRegistry registry;
    private final EnabledModStore enabledStore;
    private final BmlConfig config;
    private final PlatformOps platform;
    private SessionArchive activeArchive;
    /** The session whose Bitwig this service last saw running: its exit, when seen, records how long Bitwig ran. */
    private String seenRunning;
    private final SessionStore sessionStore;
    private final List<String> brokenSessionFolders = new ArrayList<>();

    BmlService(BmlPaths paths) throws IOException {
        this.paths = paths;
        this.registry = new ModRegistry(paths);
        this.enabledStore = new EnabledModStore(paths);
        this.config = new BmlConfig(paths);
        this.platform = PlatformOps.detect();
        SettingsFolder.ready(paths);
        Files.createDirectories(paths.configDir);
        Files.createDirectories(paths.stateDir);
        Files.createDirectories(paths.reportsDir);
        Files.createDirectories(paths.sessionsDir);
        config.load();
        sessionStore = new SessionStore(paths);
        // A session folder that can't be read never stops the window opening; Errors lists it.
        brokenSessionFolders.addAll(sessionStore.reconcileAll());
    }
    /** Session folders found broken at start-up, as "<folder>: <why>". */
    List<String> brokenSessionFolders(){return List.copyOf(brokenSessionFolders);}
    /** True if a Bitwig process is running, from this BML or not, or that can't be told. */
    boolean bitwigRunning(){
        try{return !platform.listBitwigPids().isEmpty();}catch(Exception unknown){return true;}
    }
    List<SessionArchive> archivedSessions() throws IOException{return sessionStore.list();}
    Path zipArchivedSession(String id) throws IOException{return sessionStore.zipSession(id);}
    void deleteArchivedSession(String id) throws IOException{sessionStore.deleteSession(id);}
    int archivedExportCount() throws IOException{return sessionStore.countExports();}
    SessionStore.CleanupResult clearAllArchivedSessionsAndExports() throws IOException{return sessionStore.clearAllClosedSessionsAndExports();}


    BmlPaths paths() { return paths; }
    BmlConfig config() { return config; }
    PlatformOps platform() { return platform; }
    List<ModInfo> discoverMods() throws IOException {
        return PackageInventory.inspect(paths).entries().stream()
                .filter(PackageInventory.Entry::loadable).map(PackageInventory.Entry::modInfo).toList();
    }
    Map<String,Boolean> loadEnabled(List<ModInfo> mods) throws IOException { return enabledStore.load(mods); }
    Map<String,Boolean> loadEnabled(List<ModInfo> mods,Set<String> unavailable) throws IOException { return enabledStore.load(mods,unavailable); }
    void saveEnabled(Map<String,Boolean> state) throws IOException { enabledStore.save(state); }

    Optional<SessionInfo> currentSession() throws IOException {
        return Files.isRegularFile(paths.currentSessionFile) ? Optional.of(SessionInfo.load(paths.currentSessionFile)) : Optional.empty();
    }

    /**
     * Reconcile persisted session state with the exact OS process identity.
     * A closed session never requires packaging in order to become complete.
     * Trace packaging is a separate, explicit action.
     */
    Optional<SessionInfo> reconcileCurrentSession() throws IOException {
        Optional<SessionInfo> opt = currentSession();
        if (opt.isEmpty()) return opt;

        SessionInfo s = opt.get();
        if(activeArchive==null || !activeArchive.id.equals(s.id)){
            try{activeArchive=SessionArchive.restore(paths,s.id);}
            catch(Exception invalidArchive){
                // Keep the original persisted process identity usable. Corrupt/new
                // archive metadata must not prevent controlling a running Bitwig.
                activeArchive=null;
            }
        }
        boolean alive = s.processAlive();
        if (alive) {
            seenRunning = s.id;
            if (s.status == null || s.status.isBlank() || s.complete() || s.status.startsWith("exited")) {
                s.status = "running";
                saveSession(s);
            }
            return Optional.of(s);
        }

        if (!s.complete() && !"exited-unverified".equals(s.status)) {
            s.status = "exited-unverified";
            saveSession(s);
            // No verified graceful-exit signal: classify unobserved termination as incomplete.
            if (activeArchive != null && activeArchive.id.equals(s.id)) {
                // Seen running and now gone: the exit was just noticed, so the run time is known.
                if (s.id.equals(seenRunning)) activeArchive.recordExit();
                activeArchive.close(false,"Bitwig process no longer running; exit cause unverified");
            }
        }
        return Optional.of(s);
    }

    SessionInfo launch(Map<String,Boolean> enabled, boolean debugMode, Consumer<String> log) throws Exception {
        if (!platform.launchSupported()) throw new IOException("Bitwig launch is not implemented for " + platform.name() + " in this preview.");
        Path bitwig = config.bitwigExecutable();
        if (bitwig == null || !Files.isRegularFile(bitwig)) throw new IOException("Bitwig Studio executable not found. Choose the Bitwig Studio executable first.");
        if (!Files.isRegularFile(paths.launchScript)) throw new IOException("BML launch script missing: " + paths.launchScript);

        Optional<SessionInfo> existing = reconcileCurrentSession();
        if (existing.isPresent() && existing.get().processAlive()) {
            throw new IOException("A BML-managed Bitwig session is already running (PID " + existing.get().pid + ").");
        }

        Set<Long> before = platform.listBitwigPids();
        if (!before.isEmpty()) throw new IOException("Bitwig is already running (PID " + before.iterator().next() + "). Close it before launching a BML-managed session.");

        config.save();
        writeLaunchSelection(enabled);
        Files.deleteIfExists(paths.agentActiveSessionFile);

        List<String> ids = enabled.entrySet().stream().filter(e -> Boolean.TRUE.equals(e.getValue())).map(Map.Entry::getKey).sorted().toList();
        String idsCsv = String.join(",", ids);
        SessionArchive attempt = SessionArchive.begin(paths,debugMode,idsCsv);
        activeArchive=attempt;
        Consumer<String> archivedLog = line -> {
            try { attempt.log(line); } catch(IOException ex) { throw new java.io.UncheckedIOException(ex); }
            log.accept(line);
        };
        try {
        archivedLog.accept("Preparing and launching Bitwig with " + ids.size() + " enabled mod(s)" + (debugMode ? " in Debug Mode..." : " (Debug Mode off)..."));

        Path launchLog = debugMode ? attempt.folder().resolve("launch").resolve("console.log")
                : paths.stateDir.resolve("launch-console.log");
        Files.createDirectories(launchLog.getParent());
        Files.deleteIfExists(launchLog);
        ProcessBuilder pb = new ProcessBuilder(cmdScript(paths.launchScript, bitwig.toString()));
        pb.directory(paths.root.toFile());
        pb.redirectErrorStream(true);
        // Always detach Java from the launcher's stdout/stderr pipe. On Windows, Bitwig can
        // inherit console/pipe handles from the short-lived launcher; waiting on that pipe
        // would leave the GUI in its busy state until Bitwig exits. Redirect to the launch
        // log for every mode and, in Debug Mode, tail that file for live progress instead.
        pb.redirectOutput(ProcessBuilder.Redirect.to(launchLog.toFile()));
        pb.environment().put("BML_SESSION_ID", attempt.id);
        pb.environment().put("BML_SESSION_ROOT", attempt.folder().toString());
        if(debugMode) pb.environment().put("BML_SESSION_TRACE_ROOT",attempt.folder().resolve("traces").toString());
        pb.environment().put("BML_MOD_SELECTION_ACTIVE", "1");
        pb.environment().put("BML_ENABLED_MODS_FILE", paths.stateDir.resolve("launch-enabled-mods.txt").toString());
        pb.environment().put("BML_ENABLED_MOD_IDS", idsCsv);
        pb.environment().put("BML_TRACE_ENABLED", debugMode ? "1" : "0");
        pb.environment().put("BML_DEBUG_VERBOSE", debugMode ? "1" : "0");
        // The preparer runs on the Java running this window (on Windows its java.exe, beside the window's javaw.exe).
        pb.environment().put("BML_JAVA", Path.of(System.getProperty("java.home"), "bin", BmlPaths.WINDOWS ? "java.exe" : "java").toString());
        if (debugMode) archivedLog.accept("Debug Mode progress logging enabled; preparation output will stream live below.");
        Process proc = pb.start();
        int rc = debugMode ? waitForLaunchWithLiveLogFile(proc, launchLog, archivedLog) : proc.waitFor();
        if (!debugMode) replayLaunchLog(launchLog, archivedLog);
        if (rc != 0) throw new IOException("BML launch failed with exit code " + rc + ". See the log above for the preparation/launch error.");

        Map<String,String> build = KeyValueFile.read(paths.buildInfoFile);
        int transformCount = parseInt(build.get("transform_count"), -1);
        int traceTargetCount = parseInt(build.get("trace_target_count"), -1);
        int replacementClassCount = parseInt(build.get("replacement_class_count"), -1);
        if (transformCount < 0 || traceTargetCount < 0 || replacementClassCount < 0) {
            throw new IOException("Launch completed but build-info.txt is missing transform/trace/replacement counts.");
        }

        boolean agentExpected = replacementClassCount > 0 || (debugMode && traceTargetCount > 0);
        SessionInfo session;
        if (agentExpected) {
            Map<String,String> agent = waitForAgentSession(Duration.ofSeconds(5));
            session = fromAgentSession(agent);
            session.id=attempt.id;
        } else {
            long pid = waitForNewBitwigPid(before, Duration.ofSeconds(30));
            session = new SessionInfo();
            session.id = attempt.id;
            session.mode = "plain";
            session.pid = pid;
            session.launched = session.id;
            session.traceEnabled = false;
            if (debugMode) archivedLog.accept("Debug Mode requested, but no trace targets were prepared; Bitwig launched without tracing.");
        }
        session.modIds = build.getOrDefault("mod_ids", idsCsv);
        session.status = "running";
        session.captureProcessIdentity();
        saveSession(session);
        attempt.launched(session);
        archivedLog.accept("BML session " + session.id + " is running (PID " + session.pid + ", debug=" + (session.traceEnabled ? "on" : "off") + ").");
        return session;
        } catch(Exception failure) {
            attempt.failed(failure.getMessage());
            throw failure;
        }
    }

    void stopAndQuit(Consumer<String> log) throws Exception {
        SessionInfo s = reconcileCurrentSession().orElseThrow(() -> new IOException("No BML-managed session has been recorded."));
        if (!s.processAlive()) {
            log.accept("Tracked Bitwig session is already closed; exit cause is not verified.");
            s.status = "exited-unverified";
            saveSession(s);
            if(activeArchive!=null && activeArchive.id.equals(s.id))
                activeArchive.close(false,"Process was already closed before Stop / Quit");
            return;
        }

        s.status = "stopping";
        saveSession(s);
        stopTraceIfNeeded(s, log);

        log.accept("Requesting a normal Bitwig window close. If Bitwig asks to save, answer that prompt in Bitwig.");
        platform.requestGracefulClose(s.pid);
        if (!platform.waitForExit(s.pid, Duration.ofSeconds(120))) {
            s.status = "waiting-for-bitwig-close";
            saveSession(s);
            throw new IOException("Bitwig is still running after 120 seconds, possibly waiting for a save dialog. Resolve it and press Stop / Quit again; BML will not force-kill Bitwig.");
        }
        s.status = "complete";
        saveSession(s);
        if(activeArchive!=null && activeArchive.id.equals(s.id)){
            activeArchive.recordExit();
            activeArchive.beginFinalization("Graceful Stop / Quit completed; finalizing metadata");
            activeArchive.close(true,"Graceful Stop / Quit completed");
        }
        Files.deleteIfExists(paths.agentActiveSessionFile);
        if (s.traceEnabled && s.report != null) log.accept("Debug traces are in the session folder: " + s.report + ".");
    }

    private void stopTraceIfNeeded(SessionInfo s, Consumer<String> log) throws Exception {
        if (!s.traceEnabled || !"modded".equalsIgnoreCase(s.mode)) return;
        if (s.control == null || !Files.exists(s.control)) {
            log.accept("Trace stop skipped because no active control file exists for this debug session.");
            return;
        }
        String control = Files.readString(s.control, StandardCharsets.UTF_8).trim();
        if ("stopped".equalsIgnoreCase(control)) {
            log.accept("Trace is already stopped.");
            return;
        }
        log.accept("Stopping trace for session " + s.id + "...");
        runCommand(cmdScript(paths.loader.resolve("jvmti").resolve("stop-tracer" + BmlPaths.SCRIPT_EXTENSION)), paths.root, log);
        waitForControlStopped(s.control, Duration.ofSeconds(5));
    }

    private void saveSession(SessionInfo s) throws IOException {
        s.save(paths.currentSessionFile);
    }

    private void writeLaunchSelection(Map<String,Boolean> enabled) throws IOException {
        enabledStore.save(enabled);
        List<String> ids = enabled.entrySet().stream().filter(e -> Boolean.TRUE.equals(e.getValue())).map(Map.Entry::getKey).sorted().toList();
        Files.createDirectories(paths.stateDir);
        Files.write(paths.stateDir.resolve("launch-enabled-mods.txt"), ids, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private Map<String,String> waitForAgentSession(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(paths.agentActiveSessionFile)) {
                Map<String,String> m = KeyValueFile.read(paths.agentActiveSessionFile);
                if (m.containsKey("pid")) return m;
            }
            Thread.sleep(100);
        }
        throw new IOException("Modded launch succeeded but JVMTI active-session metadata was not produced.");
    }

    private SessionInfo fromAgentSession(Map<String,String> m) throws IOException {
        SessionInfo s = new SessionInfo();
        s.id = m.getOrDefault("session_id", m.getOrDefault("launched", "unknown"));
        s.mode = m.getOrDefault("mode", "modded");
        s.launched = m.getOrDefault("launched", s.id);
        try { s.pid = Long.parseLong(m.getOrDefault("pid", "-1")); }
        catch (NumberFormatException ex) { throw new IOException("Agent session contains invalid pid: " + m.get("pid")); }
        if (s.pid <= 0) throw new IOException("Agent session did not contain a valid Bitwig PID.");
        String traceFlag = m.getOrDefault("trace_enabled", "0");
        s.traceEnabled = "1".equals(traceFlag) || "true".equalsIgnoreCase(traceFlag);
        s.report = toPath(m.get("report"));
        s.trace = toPath(m.get("trace"));
        s.control = toPath(m.get("control"));
        return s;
    }

    private long waitForNewBitwigPid(Set<Long> before, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (long pid : platform.listBitwigPids()) if (!before.contains(pid)) return pid;
            Thread.sleep(250);
        }
        throw new IOException("BitwigStudioApp did not appear within 30 seconds after launch.");
    }

    private void waitForControlStopped(Path control, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(control) && "stopped".equalsIgnoreCase(Files.readString(control, StandardCharsets.UTF_8).trim())) return;
            Thread.sleep(100);
        }
        throw new IOException("Tracer did not confirm 'stopped' within 5 seconds.");
    }

    private static int waitForLaunchWithLiveLogFile(Process proc, Path launchLog, Consumer<String> log) throws Exception {
        Files.createDirectories(launchLog.toAbsolutePath().normalize().getParent());
        int emitted = 0;

        // Poll the redirected launch log instead of consuming Process.getInputStream().
        // This is intentionally simple: launch output is small, and re-reading the file
        // avoids keeping any Java pipe connected to handles that the Bitwig child process
        // may inherit on Windows. Temporary sharing/lock failures are retried.
        while (proc.isAlive()) {
            emitted = emitNewLaunchLogLines(launchLog, emitted, log);
            Thread.sleep(100);
        }

        int rc = proc.waitFor();

        // Drain the launcher's final lines after it exits. Two stable reads are enough to
        // cover ordinary stdio/file buffering while keeping the GUI transition immediate.
        int stableReads = 0;
        for (int i = 0; i < 12 && stableReads < 2; i++) {
            int before = emitted;
            emitted = emitNewLaunchLogLines(launchLog, emitted, log);
            stableReads = emitted == before ? stableReads + 1 : 0;
            if (stableReads < 2) Thread.sleep(100);
        }
        return rc;
    }

    private static int emitNewLaunchLogLines(Path launchLog, int emitted, Consumer<String> log) {
        if (!Files.isRegularFile(launchLog)) return emitted;
        try {
            List<String> lines = Files.readAllLines(launchLog, StandardCharsets.UTF_8);
            if (emitted > lines.size()) emitted = 0; // defensive if the file was replaced/truncated
            for (int i = emitted; i < lines.size(); i++) log.accept(lines.get(i));
            return lines.size();
        } catch (IOException ex) {
            // Windows may briefly deny a concurrent read while cmd.exe is opening/flushing
            // the redirected file. The next poll will retry; do not turn that into a launch
            // failure or noisy warning.
            return emitted;
        }
    }

    private static void replayLaunchLog(Path launchLog, Consumer<String> log) {
        if (!Files.isRegularFile(launchLog)) return;
        try (BufferedReader r = Files.newBufferedReader(launchLog, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) log.accept(line);
        } catch (IOException ex) {
            log.accept("WARNING: Could not replay launcher output from " + launchLog + ": " + ex.getMessage());
        }
    }

    private static int runCommand(List<String> command, Path directory, Consumer<String> log) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = p.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) log.accept(line);
        }
        int rc = p.waitFor();
        if (rc != 0) throw new IOException("Command failed with exit code " + rc + ": " + String.join(" ", command));
        return rc;
    }

    private static List<String> cmdScript(Path script, String... args) {
        if (!BmlPaths.WINDOWS) {
            // POSIX sh, so the script needn't carry an executable bit (ZIP tools may drop it).
            List<String> command = new ArrayList<>();
            command.add("/bin/sh");
            command.add(script.toAbsolutePath().normalize().toString());
            command.addAll(Arrays.asList(args));
            return command;
        }
        StringBuilder command = new StringBuilder("call \"").append(script.toAbsolutePath().normalize()).append("\"");
        for (String arg : args) command.append(" \"").append(arg.replace("\"", "\"\"")).append("\"");
        return List.of("cmd.exe", "/d", "/s", "/c", command.toString());
    }

    private static int parseInt(String s, int fallback) { try { return Integer.parseInt(s); } catch (Exception ex) { return fallback; } }
    private static Path toPath(String s) { return s == null || s.isBlank() ? null : Path.of(s).toAbsolutePath().normalize(); }
}
