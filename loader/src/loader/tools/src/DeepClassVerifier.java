package dev.bitwigre.loader;

/**
 * Forces HotSpot to resolve declared members of prepared replacement classes.
 * Class.forName(..., false, ...) alone can leave method bytecode verification
 * lazy, so this deliberately asks for methods and constructors as well.
 */
public final class DeepClassVerifier {
    private DeepClassVerifier() {}
    public static void main(String[] args) {
        boolean ok = true;
        ClassLoader cl = DeepClassVerifier.class.getClassLoader();
        for (String name : args) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                c.getDeclaredMethods();
                c.getDeclaredConstructors();
                c.getDeclaredFields();
                System.out.println("DEEP_VERIFY_OK " + name);
            } catch (Throwable t) {
                ok = false;
                System.err.println("DEEP_VERIFY_FAIL " + name + " " + t);
                t.printStackTrace(System.err);
            }
        }
        if (!ok) System.exit(1);
    }
}
