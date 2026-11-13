package dev.bitwigre.bml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Preparation stages, their times, and live progress for the control window. While it works,
 * the preparer writes state/prepare-progress.properties (the window polls it). After a full
 * preparation it writes state/prepare-timings.properties: each stage's time and step count,
 * which the next preparation's estimate is based on. With no timings file there is no estimate.
 */
final class PrepareProgress {
    static final String PROGRESS_FILE="prepare-progress.properties",TIMINGS_FILE="prepare-timings.properties";
    /** Stage ID -> what the window shows, in the order a full preparation runs them. */
    static final Map<String,String> STAGES=new LinkedHashMap<>();
    static {
        STAGES.put("check","Checking for changes");
        STAGES.put("maps","Loading the compatibility map");
        STAGES.put("mods","Reading mods");
        STAGES.put("hooks","Planning hooks");
        STAGES.put("helpers","Merging mod code");
        STAGES.put("transforms","Patching Bitwig classes");
        STAGES.put("verify","Verifying patched classes");
        STAGES.put("linkage","Checking mod code links");
        STAGES.put("reports","Writing reports");
    }
    /** Bar share of each stage when there are no timings yet: patching is nearly all of it. */
    private static final Map<String,Double> DEFAULT_WEIGHT=Map.of("check",.01,"maps",.01,"mods",.01,"hooks",.01,
        "helpers",.01,"transforms",.88,"verify",.06,"linkage",.005,"reports",.005);
    /** Shown instead of a stage when only components changed (the cached path). */
    static final String BINDINGS_LABEL="Updating component bindings";

    private final Path progressFile,timingsFile;
    private final Map<String,String> last;
    private final long started=System.nanoTime();
    private final Map<String,Long> stageNanos=new LinkedHashMap<>();
    private final Map<String,Integer> planned=new HashMap<>();
    private final Map<String,Long> modNanos=new TreeMap<>();
    private final Map<String,Integer> hooksLeft=new HashMap<>();
    private String stage="",label="";
    private long stageStart,lastWrite;
    private int stepsDone,stepsTotal,modsDone,modsTotal;

    PrepareProgress(Path stateDir) {
        progressFile=stateDir.resolve(PROGRESS_FILE);
        timingsFile=stateDir.resolve(TIMINGS_FILE);
        Map<String,String> read;
        try { read=KeyValueFile.read(timingsFile); } catch(IOException ex) { read=Map.of(); }
        // Timings of another loader version describe other code; they don't predict this one.
        last=JavaPreparer.LOADER_VERSION.equals(read.get("loader_version"))?read:Map.of();
    }

    synchronized void stage(String id) {
        closeStage();
        stage=id;label=id.equals("bindings")?BINDINGS_LABEL:STAGES.get(id);
        stageStart=System.nanoTime();stepsDone=0;stepsTotal=planned.getOrDefault(id,0);
        write(true);
    }
    synchronized void planned(String id,int steps) {planned.put(id,steps);}
    synchronized void mods(Map<String,Integer> hooksPerMod) {
        hooksLeft.putAll(hooksPerMod);modsTotal=hooksPerMod.size();modsDone=0;
    }
    /** Mods without hooks are prepared once their code is merged. */
    synchronized void helpersMerged() {
        for(var e:hooksLeft.entrySet())if(e.getValue()==0)modsDone++;
        write(true);
    }
    /** One step of the current stage done; its tool time counts toward the mod, if any. */
    synchronized void step(String modId,long nanos) {
        stepsDone++;
        if(modId!=null)modNanos.merge(modId,nanos,Long::sum);
        write(false);
    }
    synchronized void hookDone(String modId,long nanos) {
        Integer left=hooksLeft.get(modId);
        if(left!=null) {
            hooksLeft.put(modId,left-1);
            if(left==1)modsDone++;
        }
        step(modId,nanos);
    }
    /** The prepared runtime was reused (perhaps with its component bindings written again). */
    synchronized void cached() {
        closeStage();
        stage="";label="Reusing prepared mods";
        writeState("cached");
    }
    /** A full preparation finished: record its timings for the next estimate. */
    synchronized void finish() {
        closeStage();
        List<String> lines=new ArrayList<>();
        lines.add("loader_version="+JavaPreparer.LOADER_VERSION);
        lines.add("total_ms="+(System.nanoTime()-started)/1_000_000);
        StringBuilder summary=new StringBuilder();
        for(var e:stageNanos.entrySet()) {
            long ms=e.getValue()/1_000_000;
            lines.add("stage."+e.getKey()+".ms="+ms);
            if(planned.containsKey(e.getKey()))lines.add("stage."+e.getKey()+".steps="+planned.get(e.getKey()));
            summary.append(summary.length()==0?"":", ").append(e.getKey()).append(' ').append(seconds(ms));
        }
        for(var e:modNanos.entrySet())lines.add("mod."+e.getKey()+".ms="+e.getValue()/1_000_000);
        try {
            Files.createDirectories(timingsFile.getParent());
            Files.write(timingsFile,lines,StandardCharsets.UTF_8);
        } catch(IOException ex) {
            System.err.println("WARNING: cannot record preparation timings: "+ex.getMessage());
        }
        System.out.println("Preparation took "+seconds((System.nanoTime()-started)/1_000_000)+" s ("+summary+" s).");
        stage="";label="Prepared";
        writeState("done");
    }
    private static String seconds(long ms) {return String.format(Locale.ROOT,"%.1f",ms/1000.0);}
    private void closeStage() {
        if(!stage.isEmpty())stageNanos.merge(stage,System.nanoTime()-stageStart,Long::sum);
    }

    /** Remaining milliseconds from the last full preparation's stage times, or -1 with none. */
    private long remainingMs() {
        List<String> order=new ArrayList<>(STAGES.keySet());
        int at=order.indexOf(stage);
        // While checking, it isn't known yet whether anything will be prepared.
        if(last.isEmpty() || at<1) return -1;
        double remaining=0;
        for(int i=at;i<order.size();i++) {
            String id=order.get(i);
            String ms=last.get("stage."+id+".ms");
            if(ms==null) return -1;
            double expected=Long.parseLong(ms);
            int lastSteps=Integer.parseInt(last.getOrDefault("stage."+id+".steps","0"));
            int steps=planned.getOrDefault(id,0);
            if(lastSteps>0 && steps>0) expected=expected*steps/lastSteps;
            if(i==at) expected=Math.max(0,expected-(System.nanoTime()-stageStart)/1_000_000.0);
            remaining+=expected;
        }
        return Math.round(remaining);
    }
    private double fraction(long remaining) {
        if(remaining>=0) {
            double elapsed=(System.nanoTime()-started)/1_000_000.0;
            return elapsed/Math.max(1,elapsed+remaining);
        }
        double done=0;
        for(var e:STAGES.keySet()) {
            double w=DEFAULT_WEIGHT.get(e);
            if(e.equals(stage)) return done+w*(stepsTotal>0?Math.min(1,stepsDone/(double)stepsTotal):0);
            done+=w;
        }
        return done;
    }
    private void write(boolean always) {
        long now=System.nanoTime();
        if(!always && now-lastWrite<100_000_000L && stepsDone<stepsTotal) return;
        writeState("running");
    }
    private void writeState(String state) {
        lastWrite=System.nanoTime();
        long remaining=state.equals("running")?remainingMs():-1;
        List<String> lines=new ArrayList<>(List.of(
            "state="+state,
            "stage="+stage,
            "label="+label,
            "steps_done="+stepsDone,
            "steps_total="+stepsTotal,
            "mods_done="+modsDone,
            "mods_total="+modsTotal,
            "elapsed_ms="+(lastWrite-started)/1_000_000,
            "fraction="+String.format(Locale.ROOT,"%.4f",state.equals("running")?fraction(remaining):1.0)));
        if(remaining>=0) lines.add("remaining_ms="+remaining);
        // Best effort: the window reads this while it is written, so it is replaced in one move.
        try {
            Files.createDirectories(progressFile.getParent());
            Path tmp=progressFile.resolveSibling(PROGRESS_FILE+".tmp");
            Files.write(tmp,lines,StandardCharsets.UTF_8);
            Files.move(tmp,progressFile,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        } catch(IOException ex) {
            // A missed update only delays the window's next one.
        }
    }

    /** What the window shows, read from the progress file; null when there is none. */
    record Snapshot(String state,String label,int stepsDone,int stepsTotal,int modsDone,int modsTotal,
                    long elapsedMs,double fraction,long remainingMs) {}
    static Snapshot read(Path stateDir) {
        try {
            Map<String,String> m=KeyValueFile.read(stateDir.resolve(PROGRESS_FILE));
            if(m.isEmpty()) return null;
            return new Snapshot(m.getOrDefault("state",""),m.getOrDefault("label",""),
                Integer.parseInt(m.getOrDefault("steps_done","0")),Integer.parseInt(m.getOrDefault("steps_total","0")),
                Integer.parseInt(m.getOrDefault("mods_done","0")),Integer.parseInt(m.getOrDefault("mods_total","0")),
                Long.parseLong(m.getOrDefault("elapsed_ms","0")),Double.parseDouble(m.getOrDefault("fraction","0")),
                Long.parseLong(m.getOrDefault("remaining_ms","-1")));
        } catch(IOException|RuntimeException ex) {
            return null;
        }
    }
}
