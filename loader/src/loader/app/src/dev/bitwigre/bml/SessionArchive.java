package dev.bitwigre.bml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Experimental Phase 2A archive: one immutable launch-attempt identity and one
 * BML-owned folder per attempt. Legacy logs/traces are never imported or erased.
 * The Sessions GUI/retention/export migration belongs to the later phase.
 */
final class SessionArchive {
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss")
            .withZone(ZoneId.systemDefault());
    private final Path folder;
    final String id;
    final boolean debug;
    final Instant created;
    final String modIds;
    private String state = "Preparing";
    private Instant launched, ended;
    private long pid = -1, processStartEpochMs = -1;
    private long controllerPid = -1, controllerStartEpochMs = -1;
    private String note = "";
    private String buildVersion = BmlService.VERSION;
    /** How long Bitwig ran, from its process start to its exit as seen by the window; -1 when not seen. */
    private long ranMs = -1;

    private SessionArchive(Path folder,String id,boolean debug,Instant created,String modIds){
        this.folder=folder;this.id=id;this.debug=debug;this.created=created;this.modIds=modIds;
    }
    static SessionArchive begin(BmlPaths paths,boolean debug,String modIds) throws IOException {
        Path base=paths.sessionsDir.toAbsolutePath().normalize();
        Files.createDirectories(base);
        Instant now=Instant.now();
        for(int attempt=0;attempt<5;attempt++){
            String id=NAME.format(now)+"_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
            Path dir=base.resolve(id);
            try{Files.createDirectory(dir);}catch(FileAlreadyExistsException collision){continue;}
            SessionArchive archive=new SessionArchive(dir,id,debug,now,modIds);
            archive.controllerPid=ProcessHandle.current().pid();
            archive.controllerStartEpochMs=ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
            archive.write();
            archive.log("Launch attempt created; mode="+(debug?"Debug":"Normal")+" mods="+modIds);
            return archive;
        }
        throw new IOException("Could not allocate a unique BML session folder");
    }
    static SessionArchive restore(BmlPaths paths,String id) throws IOException {
        Path folder=locate(paths,id);
        if(!Files.isRegularFile(folder.resolve("session.json"),LinkOption.NOFOLLOW_LINKS))return null;
        Map<String,Object> data=SimpleJson.object(SimpleJson.parse(
                Files.readString(folder.resolve("session.json"),StandardCharsets.UTF_8)));
        if(!id.equals(data.get("session_id")))throw new IOException("Session identity mismatch: "+id);
        Instant when=Instant.parse(String.valueOf(data.get("created_utc")));
        boolean debug="debug".equals(data.get("launch_mode"));
        SessionArchive archive=new SessionArchive(folder,id,debug,when,String.valueOf(data.getOrDefault("mod_ids","")));
        archive.state=String.valueOf(data.getOrDefault("status","Interrupted"));
        archive.note=String.valueOf(data.getOrDefault("note",""));
        archive.buildVersion=String.valueOf(data.getOrDefault("bml_build","Unknown"));
        archive.launched=parseOptional(data.get("launched_utc"));
        archive.ended=parseOptional(data.get("ended_utc"));
        if(data.get("controller_pid") instanceof Number n)archive.controllerPid=n.longValue();
        if(data.get("controller_start_epoch_ms") instanceof Number n)archive.controllerStartEpochMs=n.longValue();
        if(data.get("pid") instanceof Number n)archive.pid=n.longValue();
        if(data.get("process_start_epoch_ms") instanceof Number n)archive.processStartEpochMs=n.longValue();
        if(data.get("ran_ms") instanceof Number n)archive.ranMs=n.longValue();
        return archive;
    }
    private static Instant parseOptional(Object value){
        if(value==null||String.valueOf(value).isBlank())return null;
        return Instant.parse(String.valueOf(value));
    }
    Path folder(){return folder;}
    synchronized long pid(){return pid;}
    synchronized long processStartEpochMs(){return processStartEpochMs;}
    synchronized Instant ended(){return ended;}
    synchronized String note(){return note;}
    synchronized String buildVersion(){return buildVersion;}
    /** How long Bitwig ran, or null when its exit wasn't seen (closed while the window was not open). */
    synchronized java.time.Duration ran(){return ranMs<0?null:java.time.Duration.ofMillis(ranMs);}
    /** Bitwig has just been seen to exit: records how long it ran, from its process start (else from
     *  when it was seen running). Written with the session's next state change. */
    synchronized void recordExit(){
        long start=processStartEpochMs>0?processStartEpochMs:launched==null?-1:launched.toEpochMilli();
        if(start>0&&ranMs<0)ranMs=Math.max(0,System.currentTimeMillis()-start);
    }
    /** A PID is not considered our process unless its start identity can be verified. */
    private static boolean confirmedAlive(long processPid,long expectedStart){
        if(processPid<=0 || expectedStart<=0)return false;
        return ProcessHandle.of(processPid).filter(ProcessHandle::isAlive)
                .flatMap(p->p.info().startInstant())
                .map(t->t.toEpochMilli()==expectedStart).orElse(false);
    }
    synchronized boolean processConfirmedAlive(){return confirmedAlive(pid,processStartEpochMs);}
    synchronized boolean controllerConfirmedAlive(){return confirmedAlive(controllerPid,controllerStartEpochMs);}
    synchronized boolean terminal(){return state.equals("Complete")||state.equals("Failed")||state.equals("Interrupted");}
    /** Runs during startup and when a managed process is observed to exit. */
    synchronized void reconcile() throws IOException {
        if(terminal())return;
        if(state.equals("Preparing")){
            if(controllerConfirmedAlive())return;
            // If start identity is unavailable but PID exists, we cannot rule out
            // a live preparer; fail safely instead of manufacturing a termination.
            if(controllerPid>0 && controllerStartEpochMs<=0 && ProcessHandle.of(controllerPid).map(ProcessHandle::isAlive).orElse(false))return;
            interrupt("Preparation owner no longer active; preparation outcome unverified");
            return;
        }
        if(state.equals("Finalizing") && controllerConfirmedAlive())return;
        if(state.equals("Running") && processConfirmedAlive())return;
        if(state.equals("Running") && pid>0 && processStartEpochMs<=0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))return;
        interrupt("Tracked Bitwig process no longer has its recorded identity; exit cause unverified");
    }
    private void interrupt(String reason) throws IOException {
        state="Interrupted"; ended=Instant.now();note=reason;write();log("Session Interrupted: "+reason);
    }
    synchronized void beginFinalization(String reason) throws IOException {
        if(terminal())return;
        state="Finalizing";note=reason==null?"":reason;
        write();log("Finalizing session: "+note);
    }

    Path outputRoot(String modId) throws IOException {
        if(!debug)throw new IOException("Debug output root is not available in Normal mode");
        if(!modId.matches("[A-Za-z][A-Za-z0-9_.-]*"))throw new IOException("Invalid mod ID for Debug root");
        Path group=folder.resolve("mod-logs");
        Files.createDirectories(group);
        Path root=group.resolve("mod-"+modId).normalize();
        if(!root.startsWith(group)||Files.isSymbolicLink(root))throw new IOException("Unsafe mod Debug output root");
        Files.createDirectories(root);
        return root;
    }
    synchronized void log(String line) throws IOException {
        Files.writeString(folder.resolve("bml.log"), Instant.now()+" "+(line==null?"":line)+System.lineSeparator(),
                StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    synchronized void launched(SessionInfo info) throws IOException {
        state="Running";launched=Instant.now();pid=info.pid;processStartEpochMs=info.processStartEpochMs;
        write();log("Bitwig process running PID="+pid);
    }
    synchronized void close(boolean confirmed,String explanation) throws IOException {
        if(terminal())return;
        state=confirmed?"Complete":"Interrupted";ended=Instant.now();note=explanation==null?"":explanation;
        write();log("Session "+state+": "+note);
    }
    synchronized void failed(String explanation) throws IOException {
        if(state.equals("Running")){
            note=explanation==null?"":explanation;write();log("Launch reporting failed after process start: "+note);return;
        }
        state="Failed";ended=Instant.now();note=explanation==null?"":explanation;write();log("Launch attempt failed: "+note);
    }
    synchronized String state(){return state;}
    static Path locate(BmlPaths paths,String id) throws IOException {
        if(id==null||!id.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}_[a-f0-9]{12}"))
            throw new IOException("Invalid BML launch session ID");
        Path base=paths.sessionsDir.toAbsolutePath().normalize(),folder=base.resolve(id).normalize();
        if(!folder.startsWith(base)||Files.isSymbolicLink(folder))throw new IOException("Unsafe session directory");
        return folder;
    }
    private synchronized void write() throws IOException {
        Path target=folder.resolve("session.json");
        Path temporary=folder.resolve(".session.json.tmp");
        String json="{\n"+
            "  \"schema_version\": 1,\n"+
            "  \"session_id\": "+q(id)+",\n"+
            "  \"created_utc\": "+q(created.toString())+",\n"+
            "  \"launch_mode\": "+q(debug?"debug":"normal")+",\n"+
            "  \"status\": "+q(state)+",\n"+
            "  \"bml_build\": "+q(buildVersion)+",\n"+
            "  \"mod_ids\": "+q(modIds)+",\n"+
            "  \"launched_utc\": "+q(launched==null?"":launched.toString())+",\n"+
            "  \"ended_utc\": "+q(ended==null?"":ended.toString())+",\n"+
            "  \"controller_pid\": "+controllerPid+",\n"+
            "  \"controller_start_epoch_ms\": "+controllerStartEpochMs+",\n"+
            "  \"pid\": "+pid+",\n"+
            "  \"process_start_epoch_ms\": "+processStartEpochMs+",\n"+
            "  \"ran_ms\": "+ranMs+",\n"+
            "  \"note\": "+q(note)+"\n}";
        Files.writeString(temporary,json,StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException x){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
    }
    private static String q(String text){
        StringBuilder b=new StringBuilder("\"");
        for(char c:text.toCharArray()){
            switch(c){case '\\'->b.append("\\\\");case '\"'->b.append("\\\"");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}
        }
        return b.append('\"').toString();
    }
}
