package com.bitwig.mods.customdeviceregistrar;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import bitwig.obf.AU; // the native device library (.bwdevice files)
import bitwig.obf.Bb; // the native modulator library (.bwmodulator files)
import bitwig.obf.Bh; // the native module library (.bwmodule files)
import bitwig.obf.EE; // the native libraries' owner: creates and binds them
import bitwig.obf.EX; // a native library (base of the three)
import bitwig.obf.Oo; // native device search
import bitwig.obf.PRZ; // a library folder's file access
import bitwig.obf.Xa; // the application context
import bitwig.obf.YBi; // a virtual file
import bitwig.obf.hLr; // an entitlement row for one UUID
import bitwig.obf.iJW; // the application context facade used by the device document cache
import bitwig.obf.pYi; // the entitlement state
import bml.api.At;
import bml.api.Hook;
import bml.api.This;
import com.bitwig.flt.packaging.core.Wkj; // the native device registry
import com.bitwig.flt.packaging.core.WzZ; // a native package kind: DEVICE, MODULATOR, MODULE

/**
 * Registers the Nitro devices, modulators and modules found through the mod's config with Bitwig:
 * adds them to the native device registry, grants their entitlements, supplies their search
 * keywords, and adds their folders as extra native library roots. A UUID Bitwig already has stays
 * Bitwig's: a copy of one of its own devices changes nothing.
 */
public final class NitroRegistration {
    /** The extra roots, kept reachable for the session. */
    private static final List<EX> EXTRA_ROOTS = Collections.synchronizedList(new ArrayList<>());
    /** The entries the registry took; set while the registry is set up. */
    private static volatile List<RegistrarConfig.Entry> registered = List.of();
    private static volatile Map<UUID, File> scannedFiles = Map.of();

    private NitroRegistration() {
    }

    /** The registry is set up: register each entry like Bitwig's own, unless Bitwig already has its UUID. */
    @Hook(target = Wkj.class, method = "<clinit>", at = At.BEFORE_RETURN, id = "orng-install-native-entries")
    public static void install() {
        List<RegistrarConfig.Entry> taken = new ArrayList<>();
        Map<UUID, File> files = new HashMap<>();
        try {
            List<RegistrarConfig.Entry> entries = RegistrarConfig.get().entries();
            if (entries.isEmpty()) {
                RegistrarConfig.MOD.log("no configured registrations");
                return;
            }
            for (RegistrarConfig.Entry entry : entries) {
                if (Wkj.cfM(entry.uuid())) {
                    RegistrarConfig.MOD.log("skipping " + entry.name() + ", Bitwig already has uuid=" + entry.uuid());
                    continue;
                }
                String path = registryPath(entry.kind(), entry.name());
                Wkj.cfM(entry.uuid(), entry.name(), WzZ.valueOf(entry.kind()), path, false); // register a native package
                taken.add(entry);
                if (entry.file() != null) files.put(entry.uuid(), entry.file().toFile());
                RegistrarConfig.MOD.log("registered " + entry.kind() + " " + entry.name() + " uuid=" + entry.uuid()
                    + " registryPath=" + path + (entry.file() == null ? "" : " source=" + entry.file()));
            }
            RegistrarConfig.MOD.log("registry complete: " + taken.size() + " " + (taken.size() == 1 ? "entry" : "entries"));
        } catch (Throwable t) {
            RegistrarConfig.report("install", t);
        } finally {
            registered = List.copyOf(taken);
            scannedFiles = Map.copyOf(files);
        }
    }

    /** Project recall looks up a package by UUID before opening its document. */
    @Hook(target = iJW.class, method = "cfM(Ljava/util/UUID;)Ljava/io/File;", at = At.REPLACE_CALL,
          id = "resolve-scanned-native-package-file", callOwner = Xa.class,
          call = "MRY(Ljava/util/UUID;)Ljava/io/File;", occurrence = 1)
    public static File packageFile(Xa context, UUID uuid) {
        File scanned = scannedFiles.get(uuid);
        return scanned != null ? scanned : context.MRY(uuid);
    }

    /** A new entitlement state: add a row for every registered UUID to each of its three UUID catalogues. */
    @Hook(target = pYi.class, method = "<init>()V", at = At.BEFORE_RETURN, id = "orng-grant-native-entitlements")
    @SuppressWarnings("unchecked")
    public static void grant(@This pYi state) {
        try {
            if (RegistrarConfig.get().entries().isEmpty()) return;
            Wkj.cfM(new UUID(0, 0)); // sets the registry up if it isn't yet, so what it took is known
            List<RegistrarConfig.Entry> entries = registered;
            List<HashMap<UUID, Object>> catalogues = List.of(state.Z1C(), state.dnz(), state.OzW());
            int inserts = 0;
            for (RegistrarConfig.Entry entry : entries) {
                for (HashMap<UUID, Object> catalogue : catalogues) {
                    if (!catalogue.containsKey(entry.uuid())) {
                        catalogue.put(entry.uuid(), new hLr(entry.uuid()));
                        inserts++;
                    }
                }
            }
            RegistrarConfig.MOD.log("entitlements complete: entries=" + entries.size() + " map inserts=" + inserts);
        } catch (Throwable t) {
            RegistrarConfig.report("grant", t);
        }
    }

    /** Native search asks for a package's keywords: when Bitwig has none for a configured entry, use the configured ones. */
    @Hook(target = Oo.class, method = "cfM(Ljava/lang/String;Lcom/bitwig/flt/packaging/core/WzZ;Ljava/util/function/Function;)Ljava/util/Set;",
          at = At.REPLACE_CALL, id = "orng-runtime-description-keywords",
          callOwner = Function.class, call = "apply", occurrence = 1)
    public static Object keywords(Function<Object, Object> stockKeywords, Object name) {
        return RegistrarConfig.keywords(stockKeywords, name);
    }

    /** Bitwig creates its device library: create it, then add the configured device folders. */
    @Hook(target = EE.class, method = "MIO", at = At.REPLACE_CALL, id = "add-and-bind-native-device-root",
          callOwner = AU.class, call = "cfM(Ljava/io/File;LPRZ;LEE;)LAU;", occurrence = 1)
    public static AU deviceLibrary(File stockRoot, PRZ files, EE owner) {
        AU root = AU.cfM(stockRoot, files, owner);
        addRoots("DEVICE", stockRoot, owner, AU::cfM);
        return root;
    }

    /** Bitwig creates its modulator library: create it, then add the configured modulator folders. */
    @Hook(target = EE.class, method = "ODA", at = At.REPLACE_CALL, id = "add-and-bind-native-modulator-root",
          callOwner = Bb.class, call = "cfM(Ljava/io/File;LPRZ;LEE;)LBb;", occurrence = 1)
    public static Bb modulatorLibrary(File stockRoot, PRZ files, EE owner) {
        Bb root = Bb.cfM(stockRoot, files, owner);
        addRoots("MODULATOR", stockRoot, owner, Bb::cfM);
        return root;
    }

    /** Bitwig creates its module library: create it, then add the configured module folders. */
    @Hook(target = EE.class, method = "vpw", at = At.REPLACE_CALL, id = "add-and-bind-native-module-root",
          callOwner = Bh.class, call = "cfM(Ljava/io/File;LPRZ;LEE;)LBh;", occurrence = 1)
    public static Bh moduleLibrary(File stockRoot, PRZ files, EE owner) {
        Bh root = Bh.cfM(stockRoot, files, owner);
        addRoots("MODULE", stockRoot, owner, Bh::cfM);
        return root;
    }

    /** How a library of one kind is created for a folder. */
    private interface LibraryFactory {
        EX create(File folder, PRZ files, EE owner);
    }

    /** Creates a library of the kind for each configured folder and binds it to the owner, as Bitwig binds its own. */
    private static void addRoots(String kind, File stockRoot, EE owner, LibraryFactory factory) {
        for (String directory : RegistrarConfig.get().directories(kind)) {
            File folder = new File(directory).getAbsoluteFile();
            try {
                if (folder.toPath().normalize().equals(stockRoot.getAbsoluteFile().toPath().normalize())) {
                    RegistrarConfig.MOD.log(kind + ": skipping " + folder + ", it is Bitwig's own library folder");
                    continue;
                }
                if (!folder.isDirectory()) {
                    RegistrarConfig.MOD.log(kind + ": skipping " + folder + ", not a folder");
                    continue;
                }
                PRZ files = YBi.MRY(folder).Ucl(); // the folder's file access
                EX root = factory.create(folder, files, owner);
                if (root == null) {
                    RegistrarConfig.MOD.log(kind + ": Bitwig created no library for " + folder);
                    continue;
                }
                root.cfM(owner.fFs().iHb(), owner); // bind to the owner's lifecycle
                EXTRA_ROOTS.add(root);
                RegistrarConfig.MOD.log(kind + ": added library folder " + folder);
            } catch (Throwable t) {
                RegistrarConfig.report(kind + " folder " + folder, t);
            }
        }
    }

    /** The registry's package-relative path, as Bitwig's own entries have: DEVICE + "Foo" → devices/Foo.bwdevice. */
    private static String registryPath(String kind, String name) {
        String lower = kind.toLowerCase(Locale.ROOT);
        return lower + "s/" + name + ".bw" + lower;
    }
}
