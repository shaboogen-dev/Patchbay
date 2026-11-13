package bml.panels;

/**
 * A mod's views. The mod names its provider class in the descriptor of its registration with this
 * framework; the framework creates one instance (public no-argument constructor) and calls
 * {@link #declare} once, before the first display profile is built.
 */
public interface PanelProvider {
    void declare(Panels panels);
}
