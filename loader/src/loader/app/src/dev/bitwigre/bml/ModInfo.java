package dev.bitwigre.bml;

import java.nio.file.Path;

record ModInfo(String id, String name, String version, String author, String description, Path directory, Path manifest) {
}
