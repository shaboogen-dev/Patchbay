package dev.bitwigre.bml;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class SessionLifecycleSmoke {
  private static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
  private static String state(SessionArchive a)throws Exception{
    var json=SimpleJson.object(SimpleJson.parse(Files.readString(a.folder().resolve("session.json"))));
    return String.valueOf(json.get("status"));
  }
  private static void expectBlocked(RunnableIO action,String reason)throws Exception{
    try{action.run();throw new AssertionError("Allowed "+reason);}catch(IOException expected){}
  }
  @FunctionalInterface interface RunnableIO{void run()throws IOException;}
  public static void main(String[] args)throws Exception {
    Path home=Files.createTempDirectory("bml-life-");BmlPaths paths=new BmlPaths(home);
    SessionStore store=new SessionStore(paths);
    Path oldLog=paths.stateDir.resolve("session-logs/last-session.log");
    Files.createDirectories(oldLog.getParent());Files.writeString(oldLog,"old log preserved");
    Path oldReport=paths.reportsDir.resolve("trace-old/trace.tsv");
    Files.createDirectories(oldReport.getParent());Files.writeString(oldReport,"legacy trace preserved");
    SessionArchive running=SessionArchive.begin(paths,true,"mod-a");
    SessionInfo live=new SessionInfo();live.pid=ProcessHandle.current().pid();live.captureProcessIdentity();
    check(live.processStartEpochMs>0,"start identity known");
    running.launched(live);
    check("Running".equals(state(running)),"running metadata");
    check(store.reconcileAll().isEmpty(),"active reconciliation warning-free");
    check("Running".equals(state(running)),"active process retained");
    expectBlocked(()->store.zipSession(running.id),"export active session");
    expectBlocked(()->store.deleteSession(running.id),"delete active session");
    SessionArchive reused=SessionArchive.begin(paths,false,"mod-b");
    SessionInfo impostor=new SessionInfo();impostor.pid=live.pid;impostor.processStartEpochMs=live.processStartEpochMs-9000;
    reused.launched(impostor);
    store.reconcileAll();
    check("Interrupted".equals(state(reused)),"PID reuse/start mismatch never trusted");
    check(SessionArchive.restore(paths,reused.id).ended()!=null,"interrupted end timestamp");
    SessionArchive failed=SessionArchive.begin(paths,false,"mod-c");failed.failed("preparer failed");
    check("Failed".equals(SessionArchive.restore(paths,failed.id).state()),"failed prep persists");
    SessionArchive debug=SessionArchive.begin(paths,true,"mod-d");
    Path logging=debug.outputRoot("mod-d").resolve("diagnostics");Files.createDirectories(logging);
    Files.writeString(logging.resolve("output.txt"),"optional mod output");
    Path trace=debug.folder().resolve("traces/trace.tsv");Files.createDirectories(trace.getParent());Files.writeString(trace,"trace owned by this session");
    debug.beginFinalization("user stopped");check("Finalizing".equals(state(debug)),"finalizing stage visible");
    expectBlocked(()->store.zipSession(debug.id),"export finalizing session");
    debug.close(true,"stop confirmed");check("Complete".equals(state(debug)),"completed metadata");
    Path outside=Files.createTempFile("bml-outside-",".txt");Files.writeString(outside,"keep outside");
    Path link=debug.folder().resolve("link-to-outside");
    boolean created=false;
    try{Files.createSymbolicLink(link,outside);created=true;}catch(IOException|UnsupportedOperationException ignored){}
    if(created){
      expectBlocked(()->store.zipSession(debug.id),"symlink included in session");
      expectBlocked(()->store.deleteSession(debug.id),"symlink unsafe delete");
      check(Files.exists(outside),"external target intact");Files.delete(link);
    }
    Path export=store.zipSession(debug.id);
    check(export.startsWith(paths.sessionExportsDir),"ZIP outside session tree");
    try(ZipFile z=new ZipFile(export.toFile())){
      check(z.getEntry(debug.id+"/session.json")!=null,"ZIP metadata");
      check(z.getEntry(debug.id+"/bml.log")!=null,"ZIP BML log");
      check(z.getEntry(debug.id+"/mod-logs/mod-mod-d/diagnostics/output.txt")!=null,"ZIP mod diagnostics");
      check(z.getEntry(debug.id+"/traces/trace.tsv")!=null,"ZIP JVMTI trace");
    }
    expectBlocked(()->store.zipSession(debug.id),"overwrite existing export");
    store.deleteSession(debug.id);
    check(!Files.exists(debug.folder()),"selected session deleted");
    check(Files.isRegularFile(export),"export preserved on delete");
    check(Files.readString(oldLog).equals("old log preserved"),"legacy log unchanged");
    check(Files.readString(oldReport).equals("legacy trace preserved"),"legacy JVMTI evidence unchanged");
    check(store.list().stream().noneMatch(a->a.id.equals(debug.id)),"deleted session not in inventory");
    check(store.list().get(0).created.compareTo(store.list().get(1).created)>=0,"newest first");
    Path corrupt=paths.sessionsDir.resolve("2026-10-11_01-01-01_abcabcabcabc");Files.createDirectories(corrupt);
    Files.writeString(corrupt.resolve("session.json"),"not-json");
    check(store.reconcileAll().stream().anyMatch(x->x.contains("abcabcabcabc")),"corrupt metadata reported");
    check(Files.exists(corrupt.resolve("session.json")),"corrupt evidence left intact");
    System.out.println("SESSION_LIFECYCLE_OK active_protection pid_identity restart_recovery failed_prep finalization ZIP_owned_contents symlink_guards deletion isolation legacy_preserved corruption_warning");
  }
}
