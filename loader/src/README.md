# BML 0.32.0 — selective v2 standalone modular core (map-free)

Accepted internally 25 October 2026. This is the EXACT user-tested core
binary, whose runtime still identifies itself as 0.32.0-independent-maps-preview1.

Extract the full BML-0.32.0-Selective-V2-Core directory. Launch bml-ui.cmd on
Windows (Java 21+). Separately import a matching full physical map ZIP for
the installed Bitwig Studio JAR and compatible mod ZIPs. Maps, user mods,
stock Bitwig files, private configurations and session traces are NOT bundled.

New v2 map packages preserve entire JAR-wide mapping in compressed remap
TSV and load only the native identities requested by enabled mod manifests.
SHA256 verification and fail-closed unmapped/helper-linkage checks remain.
Previous v1 map packages continue to work through the old JSON code path.
The standalone UI's map importer was tested with all four v2 packages in
a new clean temporary install root; no Bitwig process was launched.

Accepted functional regressions:
- 6.1: 5/5 user Pass, 18 mods (110 transforms, 51 replacements).
- 6.1.4: 7/7 user Pass, 19 mods (111 transforms, 52 replacements).
  Unrelated Alias Cell diagnostic entries remain separately recorded.
- 6.1.2: focused user Pass of repaired Custom Device Registrar v1.0.1-rc.1,
  including the Poly Grid/Spectral Multiply whole-device delete/Undo crash
  regression and project save/reopen.
- 6.1.1: 8/8 user Pass, 19 mods, 111 transforms, 52 replacements,
  3295/3295 helper links, 69/69 valid trace descriptors, graceful shutdown.

This release changes exactly three technical files compared with the previous
accepted map-free 0.32.0 core: bml-control.jar, JavaPreparer.java and
MapPackCatalog.java. All other prior GUI/JVMTI/bytecode-tool files are
identical. Prior accepted core and superseded v1 maps are archived for rollback.

This is NOT a claim of exhaustive mod compatibility, 6.0 support,
multi-platform function, 6.1.3 human runtime acceptance with this exact
binary, or standalone GUI human testing with no maps installed.
