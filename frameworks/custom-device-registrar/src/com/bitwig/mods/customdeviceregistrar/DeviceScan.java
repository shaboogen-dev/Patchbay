package com.bitwig.mods.customdeviceregistrar;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.bitwig.base.serial.file.lBQ; // Bitwig's file format: reads a document's metadata
import com.bitwig.flt.file.format.FloatDocumentRelatedFileFormat; // the format devices are saved in
import com.bitwig.flt.file.format.hgq; // reads values out of a document's metadata

/**
 * Finds the device, modulator and module files under the configured folders and reads each one's
 * UUID. Bitwig's own reader does the reading, as for its own library: it knows every version of
 * the file format, encrypted ones included. Unreadable files and missing folders are logged and
 * skipped; links are followed with loop detection; the depth and the time are limited, so a huge
 * or dead folder can't hold up Bitwig's start for long.
 */
final class DeviceScan {
    /** One file found. {@code kind} is DEVICE, MODULATOR or MODULE; {@code folder} is the configured folder it is under. */
    record Found(UUID uuid, String kind, String name, Path file, String folder) {
    }

    private static final int MAX_DEPTH = 32;
    private static final long TIME_LIMIT_MILLIS = 10_000;

    private DeviceScan() {
    }

    /** The files under the folders, in folder order and by path within a folder. Never throws. */
    static List<Found> scan(List<String> folders, Consumer<String> log) {
        List<Found> found = Collections.synchronizedList(new ArrayList<>());
        long deadline = System.currentTimeMillis() + TIME_LIMIT_MILLIS;
        Thread worker = new Thread(() -> {
            for (String folder : folders) scanFolder(folder, found, deadline, log);
            if (System.currentTimeMillis() > deadline) log.accept("scan stopped at its time limit with " + found.size() + " files; a folder is too large");
        }, "custom-device-registrar scan");
        worker.setDaemon(true);
        worker.start();
        try {
            // Waited for here too: a dead network folder can block inside a single file call.
            worker.join(TIME_LIMIT_MILLIS + 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) log.accept("scan stopped with " + found.size() + " files; a folder is not responding");
        synchronized (found) {
            return List.copyOf(found);
        }
    }

    /** The kind a file name stands for, or null. */
    static String kindOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".bwdevice")) return "DEVICE";
        if (lower.endsWith(".bwmodulator")) return "MODULATOR";
        if (lower.endsWith(".bwmodule")) return "MODULE";
        return null;
    }

    /** The file's UUID, as Bitwig reads it. */
    @SuppressWarnings("unchecked")
    static UUID readUuid(File file) throws Exception {
        lBQ format = FloatDocumentRelatedFileFormat.iHb();
        return hgq.hUR((Map<Object, Object>) format.MRY(file));
    }

    private static void scanFolder(String folder, List<Found> found, long deadline, Consumer<String> log) {
        Path root = Path.of(folder);
        if (!Files.isDirectory(root)) {
            log.accept("skipping " + folder + ", not a folder");
            return;
        }
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (System.currentTimeMillis() > deadline) return FileVisitResult.TERMINATE;
                    // The folders Bitwig's own library scan leaves out.
                    String name = dir.equals(root) || dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return name.startsWith(".") || name.equals("auto-backups") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && kindOf(file.getFileName().toString()) != null) files.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    if (e instanceof FileSystemLoopException) log.accept("skipping link loop at " + file);
                    else if (!(e instanceof NoSuchFileException)) log.accept("can't read " + file + ": " + e.getClass().getSimpleName());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            log.accept("scan of " + folder + " failed: " + e);
        }
        Collections.sort(files);
        for (Path file : files) {
            if (System.currentTimeMillis() > deadline) return;
            String fileName = file.getFileName().toString();
            try {
                UUID uuid = readUuid(file.toFile());
                if (uuid == null) throw new IllegalStateException("it has no UUID");
                found.add(new Found(uuid, kindOf(fileName), fileName.substring(0, fileName.lastIndexOf('.')), file, folder));
            } catch (Throwable t) {
                log.accept("skipping " + file + ", can't read its UUID: " + t);
            }
        }
    }
}
