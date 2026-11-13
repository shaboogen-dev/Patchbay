package bml.preferences;

/**
 * A mod's Settings controls. The mod names its provider class in the descriptor of its
 * registration with this framework; the framework creates one instance (public no-argument
 * constructor) and calls {@link #declare} once, before Bitwig's Settings are first built.
 */
public interface PreferenceProvider {
    void declare(Preferences preferences);
}
