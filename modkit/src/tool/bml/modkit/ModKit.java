package bml.modkit;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * BML mod kit.
 *
 * <pre>
 * modkit compile-jar &lt;bitwig.jar&gt; &lt;out.jar&gt;
 *     Write the jar mods compile against: signatures only, every class and member public, and
 *     Bitwig's classes in the unnamed package moved to bitwig.obf so named packages can import them.
 *
 * modkit finish &lt;classes dir&gt; &lt;hooks.json&gt; &lt;bitwig.jar&gt; [&lt;more jars&gt;...]
 *     After javac: check every @Hook and @AddField against the Bitwig jar, write them (and the non-public
 *     Bitwig members the code uses) to hooks.json, then rewrite the classes in place: bitwig.obf
 *     names back to Bitwig's own, and the mod kit's annotations removed.
 *
 * modkit jar-diff &lt;official bitwig.jar&gt; &lt;modified bitwig.jar&gt;
 *     List the changes in a modified Bitwig jar, each sorted into what it becomes in a BML mod.
 * </pre>
 */
public final class ModKit {
    public static final String VERSION = "0.1.0-dev.1";

    private ModKit() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("compile-jar")) {
            CompileJar.write(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        if (args.length >= 4 && args[0].equals("finish")) {
            Path[] jars = Arrays.stream(args, 3, args.length).map(Path::of).toArray(Path[]::new);
            System.exit(Finish.run(Path.of(args[1]), Path.of(args[2]), jars) ? 0 : 1);
        }
        if (args.length == 3 && args[0].equals("jar-diff")) {
            JarDiff.run(Path.of(args[1]), Path.of(args[2]), System.out);
            return;
        }
        if (args.length == 1 && args[0].equals("--version")) {
            System.out.println("bml-modkit " + VERSION);
            return;
        }
        System.err.println("usage: modkit compile-jar <bitwig.jar> <out.jar>");
        System.err.println("       modkit finish <classes dir> <hooks.json> <bitwig.jar> [<more jars>...]");
        System.err.println("       modkit jar-diff <official bitwig.jar> <modified bitwig.jar>");
        System.exit(2);
    }
}
