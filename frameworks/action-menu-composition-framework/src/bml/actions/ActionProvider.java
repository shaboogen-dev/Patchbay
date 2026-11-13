package bml.actions;

/**
 * A mod's actions. The mod names its provider class in the descriptor of its registration with
 * this framework; the framework creates one instance (public no-argument constructor) and calls
 * {@link #declare} once, after Bitwig has registered its own actions.
 */
public interface ActionProvider {
    void declare(Actions actions);
}
