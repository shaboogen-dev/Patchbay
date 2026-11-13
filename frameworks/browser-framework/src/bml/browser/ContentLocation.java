package bml.browser;

import java.io.File;

/** One of Bitwig's content locations (Settings > Locations): its folder and the name Bitwig shows. */
public record ContentLocation(File folder, String name) {
}
