# BML Java Control Layer — Manifest v2 Preview 2

This directory contains BML's platform-neutral Java control layer.

## Design boundary

As of the **experimental Java Core Phase A preview**, the Java application JAR
also includes `JavaPreparer`, a headless Java migration of Manifest v2
preparation. It handles build/target resolution, resource bindings, helper
composition, transform planning/orchestration and deep-verifier invocation.
Existing bytecode tools remain separate Java JARs, and JVMTI replacement remains
native. The GUI still uses the existing mod inventory and Windows launcher;
these will be unified in the next Studio Companion work phase. This preview has
not been accepted in a real Bitwig runtime.

## Mod list and master selection

The mod list presents Manifest v2 metadata as explicit **Name**, **Version**, **Author**, and **Description** columns. The Name, Version, and Author headers are clickable: the first click sorts ascending and the next click sorts descending. Version sorting is natural/numeric-aware so values such as `1.10.0` sort after `1.2.9`. The active sort header shows an up/down arrow.

The first header cell contains the unlabeled tri-state master checkbox: empty means no discovered mods are selected, a tick means every discovered mod is selected, and a minus means the list is partially selected. It uses the same native checkbox styling/alignment as the individual mod rows. Clicking the master checkbox alternates between all and none; changing individual mod checkboxes recalculates the master state. This control affects only the next launch's mod selection and is independent of Debug Mode.

## Debug progress logging

When **Debug Mode** is enabled, BML streams launch preparation output live into the GUI and enables additional phase-level diagnostics in the preparation/launcher scripts. Normal non-debug launches keep the concise Preview 5 behavior. `state/launch-console.log` is still retained for launch diagnostics.

## Debug / tracing contract

Tracing is **opt-in**. The GUI's **Debug Mode** checkbox is off by default and is not persisted across application restarts.

A normal modded launch still starts the JVMTI agent because class-file replacement and helper loading require startup-time JVMTI hooks, but the agent runs in loader-only mode: it does not create a trace report, parse/install trace targets, or request/enable breakpoint and method-exit tracing capabilities.

When Debug Mode is enabled for a launch, the agent creates that session's trace report and BML records it in `state/last-trace-session.properties`.

**Stop / Quit** and **Zip Last Trace** are deliberately separate. Stopping a session never creates a ZIP. Closing Bitwig outside BML never creates a ZIP. Starting another session never creates a ZIP. Packaging happens only when the user presses **Zip Last Trace**.

## Session contract

`state/current-session.properties` tracks the most recently launched BML-controlled Bitwig process. BML records its PID plus process-start identity and reconciles that state both at GUI startup and through Java `ProcessHandle.onExit()` while the GUI is open.

`state/last-trace-session.properties` separately tracks the most recent debug session that produced a report. This keeps manual trace packaging independent of later non-debug launches.

## Platform boundary

`PlatformOps` contains host-specific behavior: Bitwig process discovery and a normal main-window close request. `WindowsPlatformOps` closes the main window through PowerShell; `LinuxPlatformOps` finds the JVM running `com.bitwig.flt.app.BitwigStudioMain` and sends its X11 windows WM_DELETE_WINDOW (`X11WindowClose`, which speaks the X11 protocol itself; `java -cp bml-control.jar dev.bitwigre.bml.X11WindowClose --list` shows the windows it sees). `MacPlatformOps` tracks the child that Bitwig's executable starts as `BitwigStudio --launch` (the JVM runs inside it) and asks Bitwig to quit as Cmd+Q does, with the standard quit Apple event (`osascript`; the first time, macOS asks whether Terminal may control Bitwig Studio). The remainder of the core uses standard Java APIs.

## Build

Java 21+:

- Windows: `build-app.cmd`
- macOS/Linux: `./build-app.sh`

The resulting artifact is `bml-control.jar` and has no third-party runtime dependencies.

## Preview 2 GUI refinements

- **Manifest metadata table:** explicit Name, Version, Author, and Description columns.
- **Header master checkbox:** Select All is now the unlabeled tri-state checkbox in the table header instead of occupying a separate labeled row.
- **Column sorting:** Name, Version, and Author toggle ascending/descending order when clicked; version ordering is natural/numeric-aware.
- **Compact default width:** the old oversized horizontal minimum was removed; the initial width is sized from the complete action row with balanced margins around Debug Mode and Clean Past Traces.

## Inherited Preview 9 GUI refinements

- **Select All partial state:** keeps native checkbox metrics and uses platform selection colours for a closer native mixed-state appearance.
- **Stable action layout:** always-visible controls stay in a fixed order; Debug-only Zip/Open/Clean controls are appended at the far right and hide/show without shifting the normal controls.
- **Zip Last Trace:** after a successful ZIP is created, BML opens the trace-output folder automatically.
- **Output Session Log:** after a successful snapshot is created, BML opens `state/session-logs/` automatically.
- Folder-open failures are warnings only; successfully-created ZIP/log outputs remain successful.

## Inherited Preview 8 GUI refinements

- **Debug-only trace controls:** Zip Last Trace, Open Trace Folder, and Clean Past Traces are hidden unless Debug Mode is enabled.
- **Clear Session Log:** clears only the visible operational log; persisted log files are not deleted.
- **Output Session Log:** writes a timestamped snapshot below `state/session-logs/`.
- `state/session-logs/current-session.log` is persisted continuously; the previous GUI run is retained as `last-session.log` when BML opens again. These are operational BML logs, not JVMTI traces.
