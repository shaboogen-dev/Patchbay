package dev.bitwigre.bml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class KeyValueFile {
    static Map<String,String> read(Path file) throws IOException {
        LinkedHashMap<String,String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int at = line.indexOf('=');
            if (at <= 0) continue;
            out.put(line.substring(0,at).trim(), line.substring(at+1).trim());
        }
        return out;
    }
}
