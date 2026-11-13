package com.bitwig.mods.customdeviceregistrar;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RegistrarConfigTest {
    public static void main(String[] args) {
        UUID scanned = UUID.randomUUID();
        UUID manual = UUID.randomUUID();
        UUID unmatched = UUID.randomUUID();
        Path nested = Path.of("first", "releases", "Chord Arp.bwdevice");
        Path renamed = Path.of("second", "build", "Built Name.bwdevice");
        RegistrarConfig config = RegistrarConfig.withScan(Map.of(), List.of(
            new RegistrarConfig.Entry(manual, "DEVICE", "Configured Name", List.of("arp"), null),
            new RegistrarConfig.Entry(unmatched, "MODULE", "Unmatched", List.of(), null)
        ), List.of(
            new DeviceScan.Found(scanned, "DEVICE", "Chord Arp", nested, "first"),
            new DeviceScan.Found(scanned, "DEVICE", "Duplicate", Path.of("second", "Duplicate.bwdevice"), "second"),
            new DeviceScan.Found(UUID.randomUUID(), "DEVICE", "Chord Arp", Path.of("second", "Chord Arp.bwdevice"), "second"),
            new DeviceScan.Found(manual, "MODULATOR", "Wrong Kind", Path.of("first", "Wrong Kind.bwmodulator"), "first"),
            new DeviceScan.Found(manual, "DEVICE", "Built Name", renamed, "second"),
            new DeviceScan.Found(manual, "DEVICE", "Later Build", Path.of("second", "Later Build.bwdevice"), "second")
        ));

        List<RegistrarConfig.Entry> expected = List.of(
            new RegistrarConfig.Entry(manual, "DEVICE", "Configured Name", List.of("arp"), renamed.toAbsolutePath()),
            new RegistrarConfig.Entry(unmatched, "MODULE", "Unmatched", List.of(), null),
            new RegistrarConfig.Entry(scanned, "DEVICE", "Chord Arp", List.of("chord arp"), nested.toAbsolutePath())
        );
        if (!config.entries().equals(expected)) throw new AssertionError(config.entries());
        if (!config.directories("DEVICE").equals(List.of("first", "second"))) throw new AssertionError(config.directories("DEVICE"));
    }
}
