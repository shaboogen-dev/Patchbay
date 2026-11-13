package dev.bitwigre.bml;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public final class BmlControlApp {
    public static void main(String[] args) throws Exception {
        // Before any AWT class loads: Windows must not fill uncovered window area with the
        // default (white) background while a resize waits for Swing to repaint.
        System.setProperty("sun.awt.noerasebackground", "true");
        // Windows: Java's Direct3D pipeline shows nothing of a live resize until the drag stops
        // (the new area stays black, measured on Bitwig's Java 25); the GDI pipeline paints as the
        // window grows. A -Dsun.java2d.d3d given on the command line still wins.
        if (BmlPaths.WINDOWS && System.getProperty("sun.java2d.d3d") == null) System.setProperty("sun.java2d.d3d", "false");
        BmlPaths paths = BmlPaths.fromRuntime();
        if (args.length > 0 && "--status".equals(args[0])) {
            printStatus(new BmlService(paths));
            return;
        }
        if (args.length > 0 && "--self-test".equals(args[0])) {
            SelfTest.run();
            return;
        }
        // macOS: the menu bar names the application (the Dock name comes from -Xdock:name in bml-ui.sh).
        System.setProperty("apple.awt.application.name", AppText.NAME);
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
        SwingUtilities.invokeLater(() -> {
            try {
                PatchbayStyle.install();
                new PatchbayWindow(new BmlService(paths)).show();
            } catch (Exception ex) {
                ex.printStackTrace();
                String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                try {
                    PromptDialog.inform(null, AppText.NAME + " couldn't start", message);
                } catch (RuntimeException | Error dialogFailed) {
                    // Swing itself may be what failed: fall back to the platform's message box.
                    JOptionPane.showMessageDialog(null, message, AppText.NAME + " couldn't start", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
    }

    private static void printStatus(BmlService service) throws Exception {
        List<ModInfo> mods = service.discoverMods();
        Map<String,Boolean> enabled = service.loadEnabled(mods);
        System.out.println(AppText.NAME + " " + BmlService.VERSION);
        System.out.println("root=" + service.paths().root);
        System.out.println("platform=" + service.platform().name());
        System.out.println("launch_supported=" + service.platform().launchSupported());
        System.out.println("bitwig=" + service.config().bitwigExecutable());
        System.out.println("mods=" + mods.size());
        PackageInventory.Snapshot snapshot=PackageInventory.inspect(service.paths());
        System.out.println("installed_packages=" + snapshot.entries().size() + " blocking_issues=" + snapshot.errors());
        for(PackageInventory.Issue issue:snapshot.issues()) System.out.println("issue="+issue.severity()+" "+issue.packagePath()+" "+issue.code()+" "+issue.message());
        for (ModInfo m : mods) System.out.println("mod=" + m.id() + " enabled=" + enabled.getOrDefault(m.id(), true) + " version=" + m.version());
        Optional<SessionInfo> session = service.reconcileCurrentSession();
        System.out.println("session=" + (session.isPresent()
                ? session.get().id + " status=" + session.get().status + " pid=" + session.get().pid + " alive=" + session.get().processAlive() + " debug=" + session.get().traceEnabled
                : "none"));
    }

    private static final class SelfTest {
        private static boolean same(Path found,String expected){return found!=null&&found.toString().replace('\\','/').equals(expected);}
        static void run() throws Exception {
            Path root = Files.createTempDirectory("bml-control-selftest-");
            try {
                BmlPaths p = new BmlPaths(root);
                Files.createDirectories(p.mods.resolve("alpha"));
                Files.createDirectories(p.mods.resolve("beta"));
                Files.writeString(p.mods.resolve("alpha/mod.json"), manifest("alpha","Alpha","1.0"));
                Files.writeString(p.mods.resolve("beta/mod.json"), manifest("beta","Beta","2.0"));
                List<ModInfo> mods = new ModRegistry(p).discover();
                require(mods.size() == 2, "mod discovery");
                require("Self Test".equals(mods.get(0).author()) && "Self-test manifest".equals(mods.get(0).description()), "manifest metadata discovery");
                require(TextOrder.compareHumanText("Alpha", "beta") < 0, "name/author sort comparator");
                require(TextOrder.compareNatural("1.2.9", "1.10.0") < 0, "natural version sort comparator");

                EnabledModStore store = new EnabledModStore(p);
                Map<String,Boolean> state = new LinkedHashMap<>();
                state.put("alpha", false); state.put("beta", true);
                store.save(state);
                Map<String,Boolean> loaded = store.load(mods);
                require(!loaded.get("alpha") && loaded.get("beta"), "enabled state persistence");

                SessionInfo session = new SessionInfo();
                session.id="test-session"; session.mode="modded"; session.pid=123; session.status="running"; session.modIds="beta"; session.traceEnabled=true;
                session.save(p.currentSessionFile);
                SessionInfo roundTrip = SessionInfo.load(p.currentSessionFile);
                require("test-session".equals(roundTrip.id) && roundTrip.pid == 123 && "beta".equals(roundTrip.modIds) && roundTrip.traceEnabled, "session persistence");

                BmlService service = new BmlService(p);
                SessionInfo live = new SessionInfo();
                live.id="live-selftest"; live.mode="plain"; live.pid=ProcessHandle.current().pid(); live.status="running"; live.traceEnabled=false;
                live.captureProcessIdentity(); live.save(p.currentSessionFile);
                SessionInfo liveRoundTrip = service.reconcileCurrentSession().orElseThrow();
                require(liveRoundTrip.processAlive() && "running".equals(liveRoundTrip.status), "live session reconciliation");

                SessionInfo dead = new SessionInfo();
                dead.id="dead-selftest"; dead.mode="modded"; dead.pid=Long.MAX_VALUE; dead.status="running"; dead.traceEnabled=false;
                dead.save(p.currentSessionFile);
                SessionInfo deadRoundTrip = service.reconcileCurrentSession().orElseThrow();
                require(!deadRoundTrip.processAlive() && "exited-unverified".equals(deadRoundTrip.status), "dead session reconciliation");

                areaAndHomepage(root.resolve("area"));

                // A tool's folder list: found through the manifest, the rest of the file left as it is.
                require(FolderListConfig.find(p.mods,"folders-test")==null,"no folder list declared yet");
                Files.createDirectories(p.mods.resolve("gamma/resources"));
                Files.writeString(p.mods.resolve("gamma/mod.json"),"{\"manifest_version\":2,\"mod\":{\"id\":\"gamma\",\"version\":\"1.0\",\"name\":\"Gamma\",\"author\":\"a\",\"description\":\"d\"},"
                    +"\"bml_tools\":{\"folders-test\":{\"resource\":\"config\",\"key\":\"folders\"}},\"resources\":{\"config\":{\"kind\":\"file\",\"path\":\"resources/config.json\",\"persistence\":\"preserve\"}}}");
                Path listFile=p.modData("gamma").resolve("config.json");
                FolderListConfig folderList=FolderListConfig.find(p.mods,"folders-test");
                require(folderList!=null&&"Gamma".equals(folderList.modName)&&folderList.read().isEmpty(),"a missing list file lists none");
                folderList.write(List.of("C:\\My \"Devices\"","/home/ü/devices"));
                require(folderList.read().equals(List.of("C:\\My \"Devices\"","/home/ü/devices")),"folder list round trip");
                Files.writeString(listFile,"{\"schema_version\":1,\"keep\":{\"a\":[1,2.5,true,null],\"b\":{}},\"folders\":[]}");
                folderList.write(List.of("x"));
                Map<String,Object> kept=SimpleJson.object(SimpleJson.parse(Files.readString(listFile)));
                require(Files.readString(listFile).contains("\"schema_version\": 1,")&&kept.get("keep").equals(Map.of("a",java.util.Arrays.asList(1.0,2.5,true,null),"b",Map.of())),"folder list keeps the rest of the file");
                require(!Files.exists(listFile.resolveSibling("config.json.tmp")),"folder list leaves no temp file");
                String broken="{not json";Files.writeString(listFile,broken);
                boolean refused=false;
                try{folderList.write(List.of("y"));}catch(IOException expected){refused=true;}
                require(refused&&broken.equals(Files.readString(listFile)),"a list file that can't be read is left alone");

                // Device Folders: available only when a mod declares it; a gone folder is a problem, whatever the disks do.
                DeviceFoldersTool devices=new DeviceFoldersTool();
                require(devices.unavailable(p.mods)!=null&&devices.problems(p.mods).isEmpty(),"no mod declares device-folders");
                Files.createDirectories(p.mods.resolve("delta/resources"));
                Files.writeString(p.mods.resolve("delta/mod.json"),"{\"manifest_version\":2,\"mod\":{\"id\":\"delta\",\"version\":\"1.0\",\"name\":\"Delta\",\"author\":\"a\",\"description\":\"d\"},"
                    +"\"bml_tools\":{\"device-folders\":{\"resource\":\"config\",\"key\":\"folders\"}},\"resources\":{\"config\":{\"kind\":\"file\",\"path\":\"resources/config.json\",\"persistence\":\"preserve\"}}}");
                require(devices.unavailable(p.mods)==null,"a mod declares device-folders");
                FolderListConfig deviceList=FolderListConfig.find(p.mods,"device-folders");
                String there=root.toString(),gone=root.resolve("gone").toString(),aFile=p.mods.resolve("delta/mod.json").toString();
                deviceList.write(List.of(there,gone,aFile,gone));
                List<BmlTools.Problem> found=devices.problems(p.mods);
                require(found.size()==2&&found.get(0).subject().equals(gone)&&found.get(1).subject().equals(aFile)
                    &&found.get(0).modId().equals("delta")&&found.get(0).issue().blocking()&&found.get(0).issue().message().contains(gone)
                    &&found.get(0).issue().code().equals("DEVICE_FOLDER_MISSING")&&found.get(0).issue().packagePath().endsWith("delta"),"gone folders are problems, once each");
                require(devices.problems(p.mods).equals(found),"the same problems compare equal, so nothing refreshes needlessly");
                List<BmlTools.Problem> unreadable=devices.problems(deviceList,Map.of(there,FolderListConfig.FolderState.UNREADABLE));
                require(unreadable.get(0).issue().code().equals("DEVICE_FOLDER_UNREADABLE")&&unreadable.get(0).issue().blocking(),"an unreadable folder is a problem");
                java.util.concurrent.CountDownLatch answer=new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.atomic.AtomicInteger probes=new java.util.concurrent.atomic.AtomicInteger();
                java.util.function.Function<String,FolderListConfig.FolderState> hanging=f->{
                    probes.incrementAndGet();
                    try{answer.await();}catch(InterruptedException ex){}
                    return FolderListConfig.FolderState.OK;
                };
                long started=System.nanoTime();
                Map<String,FolderListConfig.FolderState> timedOut=FolderListConfig.check(List.of("dead-a","dead-b"),200,hanging);
                FolderListConfig.check(List.of("dead-a"),50,hanging);
                long took=(System.nanoTime()-started)/1_000_000;
                require(timedOut.get("dead-a")==FolderListConfig.FolderState.MISSING&&timedOut.get("dead-b")==FolderListConfig.FolderState.MISSING&&took<2000,"a folder that doesn't answer counts as missing, within the limit");
                require(probes.get()==2,"a folder still not answering gets no second check");
                answer.countDown();
                for(int wait=0;wait<100&&FolderListConfig.check(List.of("dead-a"),2000,hanging).get("dead-a")!=FolderListConfig.FolderState.OK;wait++)Thread.sleep(10);
                require(FolderListConfig.check(List.of("dead-a"),2000,hanging).get("dead-a")==FolderListConfig.FolderState.OK,"it counts again once it answers");
                List<String> kept2=new ArrayList<>(deviceList.read());kept2.removeIf(gone::equals);deviceList.write(kept2);
                require(devices.problems(p.mods).size()==1,"removing a folder from the list clears its problem");

                // A "preserve" resource lives in the data folder: seeded, a user's copy kept, the mod's folder untouched.
                Path eps=p.mods.resolve("epsilon");
                Files.createDirectories(eps.resolve("resources/layouts/sub"));
                Files.writeString(eps.resolve("resources/app.properties"),"a=shipped\n");
                Files.writeString(eps.resolve("resources/layouts/one.json"),"one shipped");
                Files.writeString(eps.resolve("resources/layouts/sub/two.json"),"two shipped");
                Files.writeString(eps.resolve("mod.json"),"{\"manifest_version\":2,\"mod\":{\"id\":\"epsilon\",\"version\":\"1.0\",\"name\":\"Epsilon\",\"author\":\"a\",\"description\":\"d\"},\"resources\":{"
                    +"\"app\":{\"kind\":\"file\",\"path\":\"resources/app.properties\",\"persistence\":\"preserve\"},"
                    +"\"layouts\":{\"kind\":\"directory\",\"path\":\"resources/layouts\",\"persistence\":\"preserve\"},"
                    +"\"icon\":{\"kind\":\"file\",\"path\":\"resources/app.properties\"}}}");
                ModData.seedAll(p,p.mods);
                Path data=p.modData("epsilon");
                require(Files.readString(data.resolve("app.properties")).equals("a=shipped\n")&&Files.readString(data.resolve("layouts/sub/two.json")).equals("two shipped"),"shipped files seed the data folder");
                require(!Files.exists(p.modData("alpha")),"a mod with nothing preserved gets no data folder");
                Files.writeString(data.resolve("app.properties"),"a=user\n");
                Files.writeString(data.resolve("layouts/one.json"),"one user");
                Files.writeString(data.resolve("layouts/mine.json"),"mine");
                Files.writeString(eps.resolve("resources/layouts/one.json"),"one newer shipped");
                Files.writeString(eps.resolve("resources/layouts/new.json"),"new shipped");
                ModData.seedAll(p,p.mods);
                require(Files.readString(data.resolve("app.properties")).equals("a=user\n")&&Files.readString(data.resolve("layouts/one.json")).equals("one user")&&Files.readString(data.resolve("layouts/mine.json")).equals("mine"),"seeding never overwrites a user's file");
                require(Files.readString(data.resolve("layouts/new.json")).equals("new shipped"),"a newly shipped file arrives");
                require(Files.readString(eps.resolve("resources/layouts/one.json")).equals("one newer shipped"),"the mod's folder is left as it is");
                try(java.util.stream.Stream<Path> left=Files.walk(data)){require(left.noneMatch(f->f.getFileName().toString().endsWith(".seeding")),"seeding leaves no temp file");}
                boolean clash=false;
                try{ModData.checkName("epsilon","r","x/settings.properties",new HashSet<>());}catch(IOException expected){clash=true;}
                require(clash,"a preserve resource can't take the settings file's place");
                clash=false;
                try{Set<String> taken=new HashSet<>();ModData.checkName("epsilon","r","a/x.json",taken);ModData.checkName("epsilon","s","b/X.json",taken);}catch(IOException expected){clash=true;}
                require(clash,"two preserve resources can't share a place");

                // A list file a user kept in the mod's folder before the tool first runs comes across.
                Files.createDirectories(p.mods.resolve("zeta/resources"));
                Files.writeString(p.mods.resolve("zeta/resources/config.json"),"{\"folders\":[\"old-place\"]}");
                Files.writeString(p.mods.resolve("zeta/mod.json"),"{\"manifest_version\":2,\"mod\":{\"id\":\"zeta\",\"version\":\"1.0\",\"name\":\"Zeta\",\"author\":\"a\",\"description\":\"d\"},"
                    +"\"bml_tools\":{\"zeta-tool\":{\"resource\":\"config\",\"key\":\"folders\"}},\"resources\":{\"config\":{\"kind\":\"file\",\"path\":\"resources/config.json\",\"persistence\":\"preserve\"}}}");
                FolderListConfig zeta=FolderListConfig.find(p.mods,"zeta-tool");
                require(zeta.read().equals(List.of("old-place")),"a tool finds the list kept in the mod's folder");
                zeta.write(List.of("new-place"));
                require(Files.readString(p.modData("zeta").resolve("config.json")).contains("new-place")&&Files.readString(p.mods.resolve("zeta/resources/config.json")).contains("old-place"),"a tool writes the data folder, never the mod's folder");

                // A "created" resource: the mod's own file in its data folder, nothing shipped; a tool writes it there.
                Files.createDirectories(p.mods.resolve("eta"));
                Files.writeString(p.mods.resolve("eta/mod.json"),manifest("eta","Eta","1.0").replace("\"targets\":{}",
                    "\"targets\":{},\"bml_tools\":{\"eta-tool\":{\"resource\":\"config\",\"key\":\"folders\"}},\"resources\":{\"config\":{\"kind\":\"file\",\"path\":\"config.json\",\"persistence\":\"created\"}}"));
                PackageInventory.Entry eta=PackageInventory.inspect(p.mods).find("eta");
                require(eta.loadable()&&eta.issues.isEmpty(),"a created resource needs nothing shipped");
                FolderListConfig etaList=FolderListConfig.find(p.mods,"eta-tool");
                require(etaList.read().isEmpty()&&!Files.exists(p.modData("eta").resolve("config.json")),"a created file that isn't there yet lists none, and nothing is seeded");
                etaList.write(List.of("here"));
                require(Files.readString(p.modData("eta").resolve("config.json")).contains("here")&&!Files.exists(p.mods.resolve("eta/config.json")),"a tool writes a created file in the data folder");
                Files.writeString(p.mods.resolve("eta/mod.json"),Files.readString(p.mods.resolve("eta/mod.json")).replace("\"path\":\"config.json\"","\"path\":\"resources/config.json\""));
                PackageInventory.Entry etaBad=PackageInventory.inspect(p.mods).find("eta");
                require(etaBad==null||!etaBad.loadable(),"a created resource's path is a plain name");
                deleteTree(p.mods.resolve("eta"));
                for(String bad:List.of("a/b.json","..","",".\\x"," x.json","c:x")){
                    boolean refusedName=false;
                    try{ModData.checkCreatedName("eta","r",bad);}catch(IOException expected){refusedName=true;}
                    require(refusedName,"a created resource refuses the path '"+bad+"'");
                }
                ModData.checkCreatedName("eta","r","config.json");

                // Seeding record: a deleted file of a folder stays deleted; a new one still arrives; a file resource comes back.
                Files.delete(data.resolve("layouts/sub/two.json"));
                Files.delete(data.resolve("app.properties"));
                Files.writeString(eps.resolve("resources/layouts/newer.json"),"newer shipped");
                ModData.seedAll(p,p.mods);
                require(!Files.exists(data.resolve("layouts/sub/two.json")),"a deleted shipped file stays deleted");
                require(Files.exists(data.resolve("layouts/newer.json")),"a file new to a version still arrives");
                require(Files.readString(data.resolve("app.properties")).equals("a=shipped\n"),"a deleted preserve file resource starts again from its default");
                require(Files.readString(data.resolve("seeded-files.txt")).contains("layouts/sub/two.json"),"the record lists what was seeded");
                clash=false;
                try{ModData.checkName("epsilon","r","x/seeded-files.txt",new HashSet<>());}catch(IOException expected){clash=true;}
                require(clash,"a preserve resource can't take the record's place");

                // The per-user folder on each platform, from the inputs given.
                java.util.function.Function<String,String> noEnv=k->null;
                require(same(BmlPaths.perUserRoot("Windows 11",k->k.equals("LOCALAPPDATA")?"C:\\Users\\u\\AppData\\Local":null,"C:\\Users\\u"),"C:/Users/u/AppData/Local/BML"),"Windows: %LOCALAPPDATA%");
                require(same(BmlPaths.perUserRoot("Windows 11",noEnv,"C:\\Users\\u"),"C:/Users/u/AppData/Local/BML"),"Windows without the variable");
                require(same(BmlPaths.perUserRoot("Windows 11",k->"relative",  "C:\\Users\\u"),"C:/Users/u/AppData/Local/BML"),"Windows ignores a relative variable");
                require(same(BmlPaths.perUserRoot("Mac OS X",k->"/xdg","/Users/u"),"/Users/u/Library/Application Support/BML"),"macOS");
                require(same(BmlPaths.perUserRoot("Linux",k->k.equals("XDG_CONFIG_HOME")?"/xdg":null,"/home/u"),"/xdg/bml"),"Linux: $XDG_CONFIG_HOME");
                require(same(BmlPaths.perUserRoot("Linux",noEnv,"/home/u"),"/home/u/.config/bml"),"Linux without it");
                require(same(BmlPaths.perUserRoot("Linux",k->"",  "/home/u"),"/home/u/.config/bml")&&same(BmlPaths.perUserRoot("Linux",k->"rel/ative","/home/u"),"/home/u/.config/bml"),"Linux ignores an empty or relative one");
                require(BmlPaths.perUserRoot("Linux",noEnv,"")==null&&BmlPaths.perUserRoot("Mac OS X",noEnv,"?")==null,"no home: the install keeps its settings");

                // The marker. Only worked out here, never created: this test touches no real per-user folder.
                Path install=root.resolve("install");
                Files.createDirectories(install.resolve("loader/app"));
                require(!new BmlPaths(install).perUser,"a folder that isn't an install never uses the per-user folder");
                Files.writeString(install.resolve("loader/app/bml-control.jar"),"");
                require(new BmlPaths(install).perUser,"an install without the marker uses it");
                Files.createDirectories(install.resolve("config"));
                Files.writeString(install.resolve("config").resolve(BmlPaths.TEST_INSTALL_MARKER),"");
                BmlPaths marked=new BmlPaths(install);
                require(!marked.perUser&&marked.configDir.equals(install.toAbsolutePath().normalize().resolve("config"))&&marked.enabledModsFile.startsWith(install.toAbsolutePath().normalize().resolve("state")),"the marker keeps everything in the install");
                SettingsFolder.ready(marked);
                require(!Files.exists(marked.stateDir.resolve("settings-imported")),"a marked install has nothing to set up");
                Files.delete(install.resolve("config").resolve(BmlPaths.TEST_INSTALL_MARKER));

                // First run, into a scratch per-user folder: this install's own settings come across, once, and stay where they were.
                Files.createDirectories(install.resolve("config/mods/m/layouts"));
                Files.writeString(install.resolve("config/mods/m/settings.properties"),"a=1\n");
                Files.writeString(install.resolve("config/mods/m/layouts/l.json"),"own");
                Files.writeString(install.resolve("config/bml.properties"),"bitwig.executable=x\n");
                Files.createDirectories(install.resolve("state"));
                Files.writeString(install.resolve("state/enabled-mods.properties"),"mod.m=false\n");
                Files.writeString(install.resolve("state/selected-components.properties"),"component.a.group.b.ids=c\n");
                Path scratch=root.resolve("per-user/BML");
                BmlPaths user=new BmlPaths(install,scratch);
                require(user.modData("m").equals(scratch.toAbsolutePath().normalize().resolve("mods/m"))&&user.configFile.startsWith(scratch.toAbsolutePath().normalize())&&user.enabledModsFile.startsWith(scratch.toAbsolutePath().normalize())&&user.selectedComponentsFile.startsWith(scratch.toAbsolutePath().normalize()),"settings live in the per-user folder");
                require(user.sessionsDir.startsWith(install.toAbsolutePath().normalize())&&user.runtimeDir.startsWith(install.toAbsolutePath().normalize())&&user.stateDir.startsWith(install.toAbsolutePath().normalize()),"sessions, runtime and state stay in the install");
                SettingsFolder.ready(user);
                require(Files.readString(user.modData("m").resolve("settings.properties")).equals("a=1\n")&&Files.readString(user.modData("m").resolve("layouts/l.json")).equals("own"),"first run brings the mod's data across");
                require(Files.readString(user.configFile).contains("bitwig.executable=x")&&Files.readString(user.enabledModsFile).contains("mod.m=false")&&Files.exists(user.selectedComponentsFile),"first run brings BML's own choices across");
                require(Files.exists(install.resolve("config/mods/m/settings.properties"))&&Files.exists(install.resolve("state/enabled-mods.properties")),"what was copied from stays");
                Files.writeString(user.modData("m").resolve("settings.properties"),"a=2\n");
                Files.writeString(install.resolve("config/mods/m/settings.properties"),"a=3\n");
                SettingsFolder.ready(user);
                require(Files.readString(user.modData("m").resolve("settings.properties")).equals("a=2\n"),"an existing per-user folder wins");
                try(java.util.stream.Stream<Path> left=Files.list(scratch.getParent())){require(left.count()==1,"setting up leaves nothing beside the folder");}

                // A tick of a mod not here isn't lost, and a new mod still starts ticked.
                EnabledModStore ticks=new EnabledModStore(user);
                ticks.save(Map.of("alpha",false));
                require(Files.readString(user.enabledModsFile).contains("mod.m=false")&&Files.readString(user.enabledModsFile).contains("mod.alpha=false"),"saving keeps the ticks of absent mods");
                Map<String,Boolean> ticked=ticks.load(mods);
                require(!ticked.get("alpha")&&ticked.get("beta")&&ticked.size()==2,"only present mods are listed; a newly found one starts ticked");

                // Deleting: refused while Bitwig runs and for a test install; only that folder goes.
                String deleteRefused=null;
                try{SettingsFolder.delete(user,true);}catch(IOException expected){deleteRefused=expected.getMessage();}
                require(deleteRefused!=null&&Files.isDirectory(scratch),"delete refused while Bitwig is running");
                deleteRefused=null;
                Files.writeString(install.resolve("config").resolve(BmlPaths.TEST_INSTALL_MARKER),"");
                try{SettingsFolder.delete(new BmlPaths(install),false);}catch(IOException expected){deleteRefused=expected.getMessage();}
                require(deleteRefused!=null&&Files.exists(install.resolve("config").resolve(BmlPaths.TEST_INSTALL_MARKER)),"a test install's folder and marker are never deleted");
                Files.delete(install.resolve("config").resolve(BmlPaths.TEST_INSTALL_MARKER));
                deleteRefused=null;
                try{SettingsFolder.delete(new BmlPaths(install,root),false);}catch(IOException expected){deleteRefused=expected.getMessage();}
                require(deleteRefused!=null,"a folder BML itself is inside is never deleted");
                Path beside=scratch.resolveSibling("beside.txt");Files.writeString(beside,"keep");
                SettingsFolder.delete(user,false);
                require(!Files.exists(scratch)&&Files.exists(beside)&&Files.exists(install.resolve("config/bml.properties")),"delete removes only that folder");
                Files.delete(beside);
                SettingsFolder.ready(user);
                require(Files.isDirectory(scratch)&&!Files.exists(user.modData("m").resolve("settings.properties")),"after a delete, this install's old settings don't come back");

                detectedComponents(root.resolve("detect"));
                emptyGroupsAndJsonNames(root.resolve("panels"));
                disabledMods(root.resolve("disabled"));

                System.out.println("SELF_TEST_OK mods=2 selection=ok state=ok session=ok reconcile=ok area_homepage=ok folder_list=ok device_folders=ok mod_data=ok created_resources=ok settings_folder=ok detected_components=ok empty_groups=ok json_names=ok disabled_mods=ok");
            } finally {
                deleteTree(root);
            }
        }

        /** mod.area and mod.homepage: optional; the area falls back to the name's prefix, then "Other". */
        private static void areaAndHomepage(Path root) throws Exception {
            Path mods=root.resolve("mods");
            String base=manifest("a1","Arranger: Add Points","1.0");
            Files.createDirectories(mods.resolve("a1"));Files.writeString(mods.resolve("a1/mod.json"),base);
            Files.createDirectories(mods.resolve("a2"));Files.writeString(mods.resolve("a2/mod.json"),manifest("a2","Plain Name","1.0")
                .replace("\"description\":\"Self-test manifest\"","\"description\":\"Self-test manifest\",\"area\":\"arranger\",\"homepage\":\"https://example.com/a2\""));
            Files.createDirectories(mods.resolve("a3"));Files.writeString(mods.resolve("a3/mod.json"),manifest("a3","Loose","1.0")
                .replace("\"description\":\"Self-test manifest\"","\"description\":\"Self-test manifest\",\"homepage\":\"file:///etc/passwd\""));
            PackageInventory.Snapshot s=PackageInventory.inspect(mods);
            PackageInventory.Entry a1=s.find("a1"),a2=s.find("a2"),a3=s.find("a3");
            require(a1.area().equals("Arranger")&&a1.shortName().equals("Add Points")&&a1.homepage==null&&a1.issues.isEmpty(),"area from the name's prefix");
            require(a2.area().equals("arranger")&&a2.shortName().equals("Plain Name")&&a2.homepage!=null&&a2.homepage.getHost().equals("example.com"),"declared area and homepage");
            require(a3.area().equals("Other")&&a3.homepage==null&&a3.issues.stream().anyMatch(i->i.code().equals("INVALID_HOMEPAGE")&&!i.blocking())&&a3.loadable(),"a homepage that isn't http(s) is a warning and ignored");
        }

        private static String detectParent(String groups) {
            return manifest("framework","Framework","1.0").replace("\"manifest_version\":2","\"manifest_version\":3,\"package_type\":\"mod\",\"component_groups\":"+groups);
        }
        private static final String THEMES="{\"themes\":{\"name\":\"Themes\",\"selection\":\"single\",\"interface_version\":\"1\",\"required_resources\":{\"theme\":{\"kind\":\"file\"}},\"detect\":{\"theme\":\"*.bte\",\"overlays\":\"overlays/\"}}}";
        private static ComponentCatalog.Part part(PackageInventory.Snapshot snapshot,String id) {
            return snapshot.components().parts().stream().filter(p->id.equals(p.id)).findFirst().orElse(null);
        }
        private static boolean hasIssue(PackageInventory.Snapshot snapshot,String code,PackageInventory.Severity severity) {
            return snapshot.issues().stream().anyMatch(i->i.code().equals(code)&&i.severity()==severity);
        }
        private static boolean groupRefused(String groups) {
            try{ComponentCatalog.parseGroups(SimpleJson.object(SimpleJson.parse("{\"component_groups\":"+groups+"}")));return false;}
            catch(IllegalArgumentException expected){return true;}
        }
        private static final String PANELS="{\"panels\":{\"name\":\"Panels\",\"selection\":\"multiple\",\"minimum\":0,\"interface_version\":\"1\",\"required_resources\":{\"panel\":{\"kind\":\"file\"}},\"detect\":{\"panel\":\"*.json\"}}}";
        /** A "multiple" group with "minimum": 0, and .json parts named by their top-level "name". */
        private static void emptyGroupsAndJsonNames(Path root) throws Exception {
            Path mods=root.resolve("mods"),components=root.resolve("components");
            Files.createDirectories(mods.resolve("framework"));
            Files.createDirectories(components);
            Files.writeString(mods.resolve("framework/mod.json"),detectParent(PANELS));
            String selKey=ComponentSelectionStore.key("framework","panels");

            // The key: whole numbers 0 or more, and only on "multiple".
            require(ComponentCatalog.parseGroups(SimpleJson.object(SimpleJson.parse("{\"component_groups\":"+PANELS+"}"))).get("panels").minimum()==0,"minimum is read");
            require(ComponentCatalog.parseGroups(SimpleJson.object(SimpleJson.parse("{\"component_groups\":"+THEMES+"}"))).get("themes").minimum()==1,"minimum is 1 by default");
            require(groupRefused("{\"g\":{\"name\":\"G\",\"selection\":\"single\",\"minimum\":0}}"),"a single choice can't be empty");
            require(!groupRefused("{\"g\":{\"name\":\"G\",\"selection\":\"single\",\"minimum\":1}}"),"a single choice may say 1");
            require(groupRefused("{\"g\":{\"name\":\"G\",\"selection\":\"multiple\",\"minimum\":-1}}")&&groupRefused("{\"g\":{\"name\":\"G\",\"selection\":\"multiple\",\"minimum\":0.5}}")
                &&groupRefused("{\"g\":{\"name\":\"G\",\"selection\":\"multiple\",\"minimum\":\"0\"}}"),"minimum is a whole number, 0 or more");

            // Nothing installed and nothing chosen: loadable, ready, no choice asked for.
            PackageInventory.Snapshot none=PackageInventory.inspect(mods,components,Map.of());
            ComponentCatalog.Group panels=none.find("framework").groups.get("panels");
            require(none.find("framework").loadable()&&none.issues().isEmpty(),"an empty group with nothing installed is no error");
            var empty=none.components().evaluate("framework",panels,List.of());
            require(empty.ready()&&!empty.configurationRequired()&&empty.selected().isEmpty()&&empty.problems().isEmpty(),"nothing chosen is ready");

            // Names from the JSON: text, a whole number's digits; otherwise the file name. IDs stay the file's.
            Files.writeString(components.resolve("tal-chorus-lx.json"),"{\"name\":\"  TAL-Chorus-LX\\n panel \",\"layouts\":[]}");
            Files.writeString(components.resolve("numbered.json"),"{\"name\":808}");
            Files.writeString(components.resolve("blank name.json"),"{\"name\":\"   \"}");
            Files.writeString(components.resolve("no-name.json"),"{\"layouts\":[]}");
            Files.writeString(components.resolve("not-json.json"),"{oops");
            Files.writeString(components.resolve("an-array.json"),"[{\"name\":\"X\"}]");
            Files.writeString(components.resolve("decimal.json"),"{\"name\":1.5}");
            Files.createDirectories(components.resolve("In A Folder"));
            Files.writeString(components.resolve("In A Folder/panel.json"),"{\"name\":\"Folder Panel\"}");
            PackageInventory.Snapshot named=PackageInventory.inspect(mods,components,Map.of());
            require(part(named,"tal-chorus-lx").name.equals("TAL-Chorus-LX panel"),"a JSON name is shown, its spacing tidied");
            require(part(named,"numbered").name.equals("808"),"a whole number reads as its digits");
            require(part(named,"blank-name").name.equals("blank name")&&part(named,"no-name").name.equals("no-name")&&part(named,"not-json").name.equals("not-json")
                &&part(named,"an-array").name.equals("an-array")&&part(named,"decimal").name.equals("decimal"),"otherwise the file name");
            require(part(named,"in-a-folder").name.equals("Folder Panel"),"a folder's JSON names it too");
            require(part(named,"tal-chorus-lx").usable()&&part(named,"not-json").usable(),"the name changes nothing else");
            Path themesMods=root.resolve("theme-mods");
            Files.createDirectories(themesMods.resolve("framework"));
            Files.writeString(themesMods.resolve("framework/mod.json"),detectParent(THEMES));
            Files.writeString(components.resolve("Named.bte"),"{\"name\":\"Not a JSON file\"}");
            require(part(PackageInventory.inspect(themesMods,components,Map.of()),"named").name.equals("Named"),"only .json files are read for a name");
            Files.delete(components.resolve("Named.bte"));

            // Choosing some, then none: both ready; a removed choice is still an error.
            var some=named.components().evaluate("framework",panels,List.of("tal-chorus-lx","numbered"));
            require(some.ready()&&some.selected().size()==2,"several can be chosen");
            require(PackageInventory.inspect(mods,components,Map.of(selKey,List.of())).find("framework").loadable(),"a saved empty choice loads");
            require(!PackageInventory.inspect(mods,components,Map.of(selKey,List.of("gone"))).find("framework").loadable(),"a chosen panel that is gone is still an error");

            // minimum 2 on a multiple group: nothing chosen asks for a choice, one is too few.
            Files.writeString(mods.resolve("framework/mod.json"),detectParent(PANELS.replace("\"minimum\":0","\"minimum\":2")));
            PackageInventory.Snapshot two=PackageInventory.inspect(mods,components,Map.of());
            ComponentCatalog.Group needTwo=two.find("framework").groups.get("panels");
            require(two.components().evaluate("framework",needTwo,List.of()).configurationRequired(),"below the minimum with nothing chosen asks for a choice");
            var oneOfTwo=two.components().evaluate("framework",needTwo,List.of("numbered"));
            require(!oneOfTwo.ready()&&oneOfTwo.problems().get(0).contains("at least 2"),"one of two is too few");
            // The default, 1: nothing installed is still the error it was.
            Files.writeString(mods.resolve("framework/mod.json"),detectParent(PANELS.replace("\"minimum\":0,","")));
            PackageInventory.Snapshot plain=PackageInventory.inspect(mods,root.resolve("no-components"),Map.of());
            require(!plain.find("framework").loadable()&&hasIssue(plain,"NO_USABLE_COMPONENTS",PackageInventory.Severity.BLOCKING),"without minimum 0, nothing installed is still an error");
            require(plain.components().evaluate("framework",plain.find("framework").groups.get("panels"),List.of()).configurationRequired(),"and nothing chosen still asks for a choice");
        }
        /** Packages with no mod.json, in a scratch folder. */
        private static void detectedComponents(Path root) throws Exception {
            Path mods=root.resolve("mods"),components=root.resolve("components");
            Files.createDirectories(mods.resolve("framework"));
            Files.createDirectories(components);
            Files.writeString(mods.resolve("framework/mod.json"),detectParent(THEMES));
            String selKey=ComponentSelectionStore.key("framework","themes");
            Map<String,List<String>> nothing=Map.of();

            // The manifest key.
            Map<String,Object> declared=SimpleJson.object(SimpleJson.parse("{\"component_groups\":"+THEMES+"}"));
            require(ComponentCatalog.parseGroups(declared).get("themes").detect().equals(Map.of("theme","*.bte","overlays","overlays/")),"detect is read");
            require(ComponentCatalog.parseGroups(SimpleJson.object(SimpleJson.parse("{\"component_groups\":{\"g\":{\"name\":\"G\",\"selection\":\"single\"}}}"))).get("g").detect().isEmpty(),"detect is optional");
            String base="{\"t\":{\"name\":\"T\",\"selection\":\"single\",\"required_resources\":{\"theme\":{\"kind\":\"file\"}},\"detect\":";
            require(groupRefused(base+"{\"theme\":\"../*.bte\"}}}"),"detect refuses a path");
            require(groupRefused(base+"{\"theme\":\"*\"}}}"),"detect refuses a pattern with nothing literal");
            require(groupRefused(base+"{\"theme\":\"sub/\"}}}"),"detect refuses a folder pattern for a file slot");
            require(groupRefused(base+"{\"other\":\"*.x\"}}}"),"detect must cover a required slot");
            require(groupRefused(base+"\"*.bte\"}}"),"detect must be an object");

            // IDs.
            require(ComponentCatalog.derivedId("Dark Neon").equals("dark-neon"),"ID from a name");
            require(ComponentCatalog.derivedId("  Été  (v1.2)!").equals("ete-v1.2"),"accents and punctuation");
            require(ComponentCatalog.derivedId("808 Dark").equals("c-808-dark"),"an ID starts with a letter");
            String hashed=ComponentCatalog.derivedId("\u0422\u0451\u043c\u043d\u0430\u044f");
            require(hashed.matches(ComponentCatalog.ID_PATTERN)&&hashed.equals(ComponentCatalog.derivedId("\u0422\u0451\u043c\u043d\u0430\u044f"))&&!hashed.equals(ComponentCatalog.derivedId("\u0421\u0432\u0435\u0442")),"a name with no usable character gets a stable ID");

            // A folder with one .bte and overlays; a loose file; a folder with a mod.json keeps its own name.
            Path neon=components.resolve("Dark Neon");
            Files.createDirectories(neon.resolve("overlays/icons"));
            Files.writeString(neon.resolve("whatever.bte"),"Window background: #123456\n");
            Files.writeString(neon.resolve("._whatever.bte"),"mac sidecar");
            Files.writeString(neon.resolve("notes.txt"),"not used");
            Path loose=components.resolve("Loose One.bte");
            Files.writeString(loose,"Selection: #abc\n");
            Files.writeString(components.resolve("readme.txt"),"left alone");
            Files.writeString(components.resolve("some-theme.zip"),"left alone");
            Path named=components.resolve("folder-name");
            Files.createDirectories(named);
            Files.writeString(named.resolve("x.bte"),"A: #000\n");
            Files.writeString(named.resolve("mod.json"),"{\"manifest_version\":3,\"package_type\":\"component\",\"component\":{\"id\":\"own-id\",\"name\":\"Own Name\",\"version\":\"1\",\"author\":\"a\",\"description\":\"d\",\"parent\":\"framework\",\"group\":\"themes\"},\"resources\":{\"theme\":{\"kind\":\"file\",\"path\":\"x.bte\"}}}");
            PackageInventory.Snapshot s=PackageInventory.inspect(mods,components,nothing);
            require(s.components().parts().size()==3,"a folder, a loose file and a manifest folder are found; other loose files aren't");
            ComponentCatalog.Part folder=part(s,"dark-neon"),file=part(s,"loose-one"),own=part(s,"own-id");
            require(folder!=null&&folder.usable()&&folder.inferred&&folder.name.equals("Dark Neon")&&folder.parent.equals("framework")&&folder.group.equals("themes")&&folder.author.isEmpty()&&folder.description.isEmpty()&&folder.version.isEmpty(),"a folder is recognised, with no metadata");
            require(folder.resources.get("theme").path().endsWith("whatever.bte")&&"file".equals(folder.resources.get("theme").kind())&&folder.resources.get("overlays").path().endsWith("overlays")&&"directory".equals(folder.resources.get("overlays").kind()),"its slots are filled");
            require(file!=null&&file.usable()&&file.name.equals("Loose One")&&file.resources.get("theme").path().equals(loose.toAbsolutePath().normalize())&&!file.resources.containsKey("overlays"),"a loose file is recognised and binds to itself");
            require(own!=null&&own.usable()&&!own.inferred&&own.name.equals("Own Name")&&own.author.equals("a"),"a mod.json decides");
            require(s.issues().isEmpty(),"nothing here is a problem");
            require(s.find("framework").loadable()&&s.components().evaluate("framework",s.find("framework").groups.get("themes"),List.of("loose-one")).ready(),"an inferred part can be selected");
            require(s.components().evaluate("framework",s.find("framework").groups.get("themes"),List.of("dark-neon")).selected().get(0)==folder,"and evaluates to itself");

            // A selected theme removed: an error until chosen again. Renaming is a removal.
            Map<String,List<String>> chosen=Map.of(selKey,List.of("loose-one"));
            Path moved=components.resolve("Renamed.bte");
            Files.move(loose,moved);
            PackageInventory.Snapshot gone=PackageInventory.inspect(mods,components,chosen);
            require(!gone.find("framework").loadable()&&hasIssue(gone,"COMPONENT_SELECTION_BLOCKED",PackageInventory.Severity.BLOCKING)&&part(gone,"renamed").usable(),"renaming drops the selection: an error until chosen again");
            Files.move(moved,loose);
            require(PackageInventory.inspect(mods,components,chosen).find("framework").loadable(),"putting it back clears it");

            // Two .bte in one folder: named, blocks only if selected, cleared by removing one.
            Path second=neon.resolve("second.bte");
            Files.writeString(second,"B: #111\n");
            PackageInventory.Snapshot two=PackageInventory.inspect(mods,components,nothing);
            var tooMany=two.issues().stream().filter(i->i.code().equals("TOO_MANY_COMPONENT_FILES")).findFirst().orElse(null);
            require(tooMany!=null&&tooMany.blocking()&&tooMany.message().contains("whatever.bte")&&tooMany.message().contains("second.bte")&&!tooMany.message().contains("._whatever")&&tooMany.remedy().contains("Keep one"),"two files are named, and it says to keep one");
            require(!part(two,"dark-neon").usable()&&two.find("framework").loadable(),"unselected, it blocks nothing");
            PackageInventory.Snapshot twoSelected=PackageInventory.inspect(mods,components,Map.of(selKey,List.of("dark-neon")));
            require(!twoSelected.find("framework").loadable(),"selected, it blocks the framework");
            Files.delete(second);
            require(PackageInventory.inspect(mods,components,Map.of(selKey,List.of("dark-neon"))).find("framework").loadable(),"removing one clears it");

            // A folder with nothing recognisable: a warning only. Hidden ones aren't mentioned. A nested .bte doesn't count.
            Path other=components.resolve("My Backups");
            Files.createDirectories(other.resolve("old"));
            Files.writeString(other.resolve("old/nested.bte"),"A: #000\n");
            Files.createDirectories(components.resolve(".git"));
            Files.createDirectories(components.resolve("__MACOSX"));
            PackageInventory.Snapshot kept=PackageInventory.inspect(mods,components,nothing);
            require(hasIssue(kept,"UNRECOGNISED_COMPONENT_FOLDER",PackageInventory.Severity.WARNING)&&kept.errors()==0&&kept.issues().size()==1,"a folder BML doesn't recognise is one warning");
            require(kept.issues().get(0).remedy().contains("*.bte")&&kept.issues().get(0).remedy().contains("Framework / Themes"),"it says what is looked for, from the manifest");
            require(kept.components().parts().size()==3&&kept.find("framework").loadable(),"and it isn't listed and stops nothing");

            // Files that can't be used: an empty one, one that isn't text. Each isolated unless selected.
            Path empty=components.resolve("Empty.bte"),binary=components.resolve("Binary.bte");
            Files.writeString(empty,"  \n");
            Files.write(binary,new byte[]{(byte)0xFF,(byte)0xFE,'A',0,':',0});
            PackageInventory.Snapshot bad=PackageInventory.inspect(mods,components,nothing);
            require(!part(bad,"empty").usable()&&hasIssue(bad,"EMPTY_COMPONENT_FILE",PackageInventory.Severity.BLOCKING),"an empty file is an error");
            require(!part(bad,"binary").usable()&&hasIssue(bad,"UNREADABLE_COMPONENT_FILE",PackageInventory.Severity.BLOCKING),"a file that isn't text is an error");
            require(bad.find("framework").loadable(),"neither stops anything while not selected");
            require(!PackageInventory.inspect(mods,components,Map.of(selKey,List.of("empty"))).find("framework").loadable(),"selected, it does");
            Map<String,List<String>> seeded=new HashMap<>();
            ComponentSelectionStore.seedUnconfiguredSingles(bad,seeded);
            require(seeded.get(selKey).equals(List.of("dark-neon")),"the first usable one is the default, skipping unusable ones");
            Files.delete(empty);Files.delete(binary);
            // A file another process holds: only Windows refuses the read.
            if(BmlPaths.WINDOWS){
                Path locked=components.resolve("Locked.bte");
                Files.writeString(locked,"A: #000\n");
                try(var channel=java.nio.channels.FileChannel.open(locked,StandardOpenOption.WRITE);var lock=channel.lock()){
                    PackageInventory.Snapshot held=PackageInventory.inspect(mods,components,nothing);
                    require(!part(held,"locked").usable()&&hasIssue(held,"UNREADABLE_COMPONENT_FILE",PackageInventory.Severity.BLOCKING)&&held.find("framework").loadable(),"a file that can't be read is an error, and stops nothing while not selected");
                }
                Files.delete(locked);
            }

            // Two names, one ID: both errors. Against a mod.json: the mod.json keeps it.
            Path clash=components.resolve("Dark-Neon");
            Files.createDirectories(clash);
            Files.writeString(clash.resolve("c.bte"),"A: #000\n");
            PackageInventory.Snapshot clashing=PackageInventory.inspect(mods,components,nothing);
            require(clashing.components().parts().stream().filter(p->"dark-neon".equals(p.id)).count()==2&&clashing.components().parts().stream().filter(p->"dark-neon".equals(p.id)).noneMatch(ComponentCatalog.Part::usable),"two names with one ID are both unusable");
            require(clashing.issues().stream().anyMatch(i->i.code().equals("DUPLICATE_COMPONENT_ID")&&i.message().contains("'Dark Neon'")&&i.message().contains("'Dark-Neon'")),"and the error names both");
            Files.delete(clash.resolve("c.bte"));Files.delete(clash);
            Path taken=components.resolve("Own Id.bte");
            Files.writeString(taken,"A: #000\n");
            PackageInventory.Snapshot takenSnapshot=PackageInventory.inspect(mods,components,Map.of(selKey,List.of("own-id")));
            require(part(takenSnapshot,"own-id").usable()&&!part(takenSnapshot,"own-id").inferred&&takenSnapshot.find("framework").loadable()&&hasIssue(takenSnapshot,"COMPONENT_ID_TAKEN",PackageInventory.Severity.WARNING),"a mod.json keeps its ID; the other is a warning");
            Files.delete(taken);

            // A broken mod.json is still its own error: no falling back to the files.
            Files.createDirectories(components.resolve("Broken"));
            Files.writeString(components.resolve("Broken/mod.json"),"{not json");
            Files.writeString(components.resolve("Broken/b.bte"),"A: #000\n");
            PackageInventory.Snapshot broken=PackageInventory.inspect(mods,components,nothing);
            require(hasIssue(broken,"INVALID_COMPONENT",PackageInventory.Severity.BLOCKING)&&part(broken,"broken")==null&&broken.find("framework").loadable(),"a broken mod.json isn't overruled by the files");
            Files.delete(components.resolve("Broken/mod.json"));Files.delete(components.resolve("Broken/b.bte"));Files.delete(components.resolve("Broken"));

            // No framework recognising anything: a warning, nothing listed.
            Path modsWithout=root.resolve("mods-without");
            Files.createDirectories(modsWithout.resolve("plain"));
            Files.writeString(modsWithout.resolve("plain/mod.json"),manifest("plain","Plain","1.0"));
            PackageInventory.Snapshot none=PackageInventory.inspect(modsWithout,components,nothing);
            require(none.components().parts().stream().allMatch(p->p.id!=null&&!p.inferred)&&hasIssue(none,"UNRECOGNISED_COMPONENT_FOLDER",PackageInventory.Severity.WARNING),"nothing is inferred without a framework declaring it");

            // Two frameworks both claiming a file: not guessed.
            Files.createDirectories(mods.resolve("framework2"));
            Files.writeString(mods.resolve("framework2/mod.json"),detectParent(THEMES).replace("\"id\":\"framework\"","\"id\":\"framework2\""));
            PackageInventory.Snapshot both=PackageInventory.inspect(mods,components,nothing);
            require(hasIssue(both,"AMBIGUOUS_COMPONENT",PackageInventory.Severity.WARNING)&&part(both,"loose-one")==null&&part(both,"dark-neon")==null,"two frameworks claiming the same file: a warning, not a guess");
            Files.delete(mods.resolve("framework2/mod.json"));Files.delete(mods.resolve("framework2"));

            // Links are refused, where this system lets a test make one.
            Path outside=root.resolve("outside.bte");
            Files.writeString(outside,"A: #000\n");
            try{
                Path linkedFile=components.resolve("Linked.bte");
                Files.createSymbolicLink(linkedFile,outside);
                Path linkedInFolder=components.resolve("Holder");
                Files.createDirectories(linkedInFolder);
                Files.createSymbolicLink(linkedInFolder.resolve("t.bte"),outside);
                Path linkedDir=components.resolve("LinkedDir");
                Files.createSymbolicLink(linkedDir,neon);
                PackageInventory.Snapshot linked=PackageInventory.inspect(mods,components,nothing);
                require(!part(linked,"linked").usable()&&!part(linked,"holder").usable()&&linked.find("framework").loadable(),"a linked file is refused");
                require(linked.components().parts().stream().anyMatch(p->p.directory.endsWith("LinkedDir")&&p.id==null&&!p.usable()),"a linked folder is refused");
                Files.delete(linkedDir);Files.delete(linkedInFolder.resolve("t.bte"));Files.delete(linkedInFolder);Files.delete(linkedFile);
            }catch(UnsupportedOperationException|java.nio.file.FileSystemException notAllowed){
                System.out.println("detected components: links not tested here ("+notAllowed.getClass().getSimpleName()+")");
            }

            // A junction needs no privilege on Windows, and is refused like a link.
            if(BmlPaths.WINDOWS){
                Path junction=components.resolve("Junction");
                Process mklink=new ProcessBuilder("cmd","/c","mklink","/J",junction.toString(),neon.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if(mklink.waitFor()==0){
                    PackageInventory.Snapshot joined=PackageInventory.inspect(mods,components,nothing);
                    require(!part(joined,"junction").usable()&&joined.find("framework").loadable(),"a junction is refused");
                    Files.delete(junction);
                }
            }

            // What the window watches changes when what is found changes.
            String seen=ComponentCatalog.signature(components);
            require(seen.equals(ComponentCatalog.signature(components)),"the signature is stable");
            Path added=components.resolve("Added.bte");
            Files.writeString(added,"A: #000\n");
            String withAdded=ComponentCatalog.signature(components);
            require(!seen.equals(withAdded),"adding a file changes the signature");
            Files.delete(added);
            Files.writeString(neon.resolve("second.bte"),"B: #111\n");
            require(!seen.equals(ComponentCatalog.signature(components)),"adding a file to a folder changes the signature");
        }

        private static String manifest(String id,String name,String version) {
            return "{\"manifest_version\":2,\"mod\":{\"id\":\""+id+"\",\"version\":\""+version+"\",\"name\":\""+name+"\",\"author\":\"Self Test\",\"description\":\"Self-test manifest\"},\"built_against\":{\"bitwig_version\":\"6.1.3\",\"platform\":\"windows\",\"architecture\":\"x64\"},\"targets\":{},\"hooks\":[],\"trace_targets\":[{\"id\":\"self\",\"class\":\"example.Helper\",\"method\":\"run\",\"descriptor\":\"()V\"}]}";
        }
        /** Disable Mod: the stored list, what a ticked mod keeps from being disabled, and the issues hidden. */
        private static void disabledMods(Path root) throws IOException {
            require(DisabledMods.parse(" b, a,,b ").equals(new TreeSet<>(List.of("a","b")))&&DisabledMods.format(List.of("b","a","b")).equals("a,b"),"disabled list parse and format");
            Files.createDirectories(root.resolve("config"));
            Files.writeString(root.resolve("config").resolve(BmlPaths.TEST_INSTALL_MARKER),"");
            BmlPaths p=new BmlPaths(root);
            BmlConfig c=new BmlConfig(p);c.load();
            require(c.disabledMods().isEmpty()&&!c.showDisabledMods(),"nothing disabled by default");
            c.setDisabledMods(List.of("theme","not-installed"));c.setShowDisabledMods(true);c.save();
            BmlConfig again=new BmlConfig(p);again.load();
            require(again.disabledMods().equals(new TreeSet<>(List.of("not-installed","theme")))&&again.showDisabledMods(),"disabled list kept in the install's bml.properties");
            require(Files.readString(p.configFile).contains("mods.disabled=not-installed,theme"),"stored as mods.disabled");
            PackageInventory.Entry a=entry(root,"a"),b=entry(root,"b"),cc=entry(root,"c"),d=entry(root,"d");
            a.requires=Map.of("b","");b.requires=Map.of("c","");
            List<PackageInventory.Entry> all=List.of(a,b,cc,d);
            Map<String,List<String>> needed=DisabledMods.neededBy(all,e->e==a);
            require(needed.keySet().equals(Set.of("b","c"))&&needed.get("c").equals(List.of("A")),"a ticked mod's prerequisites, direct and through another, can't be disabled");
            require(DisabledMods.neededBy(all,e->false).isEmpty(),"with nothing ticked, anything can be disabled");
            d.issue("INVALID_MANIFEST",PackageInventory.Severity.BLOCKING,"broken","fix");
            a.issue("INVALID_AREA",PackageInventory.Severity.WARNING,"area","fix");
            ComponentCatalog.Part part=new ComponentCatalog.Part(root.resolve("components/x"));part.parent="d";part.issue("ORPHAN_COMPONENT","x","y");
            List<PackageInventory.Issue> issues=List.of(d.issues.get(0),a.issues.get(0),part.issues.get(0));
            List<PackageInventory.Issue> shown=DisabledMods.visible(issues,all,List.of(part),Set.of("d"));
            require(shown.equals(List.of(a.issues.get(0))),"a disabled mod's issues and its components' are hidden");
            require(DisabledMods.visible(issues,all,List.of(part),Set.of("gone")).size()==3,"an ID that isn't installed hides nothing");
        }
        private static PackageInventory.Entry entry(Path root,String id){
            PackageInventory.Entry e=new PackageInventory.Entry(root.resolve("mods").resolve(id));e.id=id;e.name=id.toUpperCase(Locale.ROOT);return e;
        }
        private static void require(boolean ok,String label) { if (!ok) throw new IllegalStateException("Self-test failed: " + label); }
        private static void deleteTree(Path root) throws IOException {
            if (!Files.exists(root)) return;
            try (var s = Files.walk(root)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }
}
