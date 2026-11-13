package bml.browser;

/**
 * A mod's browser entries. The mod names its provider class in the descriptor of its registration
 * with this framework; the framework creates one instance (public no-argument constructor) and
 * calls {@link #declare} once, before the first browser is built.
 */
public interface BrowserProvider {
    void declare(Browser browser);
}
