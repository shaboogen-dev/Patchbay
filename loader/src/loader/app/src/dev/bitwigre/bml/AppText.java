package dev.bitwigre.bml;

/**
 * The window's name and the longer words it shows (About, Acknowledgements, the Maps note), in one
 * place so they can be edited without touching layout code.
 */
final class AppText {
    private AppText(){}

    /** The name the window shows. Folders, jars, scripts and IDs keep the name "bml" for now. */
    static final String NAME = "Patchbay";

    /** Opened by About's "Open on GitHub". */
    static final String GITHUB = "https://github.com/shaboogen-dev/BML";

    static final String NOT_AFFILIATED = NAME + " is not affiliated with Bitwig Studio or Bitwig GmbH";

    static final String ABOUT =
        NAME + " loads mods into Bitwig Studio. It changes Bitwig in memory as it "
        + "starts and never changes Bitwig's own files. Mods are written for one Bitwig version; "
        + "the map for the version you have installed translates them to it.";

    static final String DISCLAIMER =
        NAME + " is an independent project. It is not affiliated with, endorsed by or supported by Bitwig GmbH.";

    /** The Maps page's explanation, shown there only. */
    static final String MAPS_NOTE =
        NAME + " uses the map made for the Bitwig you have installed. To update a map, import its newer ZIP; mods don't change.";

    /** Shown as written; a blank line starts a new paragraph. */
    static final String ACKNOWLEDGEMENTS = """
        Placeholder: thanks to the people and projects that made Patchbay possible will go here. \
        Name what each contributed, in a sentence or two.

        A second paragraph can follow after a blank line.""";
}
