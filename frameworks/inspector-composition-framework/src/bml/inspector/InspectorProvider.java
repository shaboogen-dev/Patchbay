package bml.inspector;

/**
 * A mod's Inspector blocks. The mod names its provider class in the descriptor of its
 * registration with this framework; the framework creates one instance (public no-argument
 * constructor) and calls {@link #declare} once, before the first Inspector panel is built.
 */
public interface InspectorProvider {
    void declare(Blocks blocks);
}
