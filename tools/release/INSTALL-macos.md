# Installing Patchbay Mod Loader (tester release)

> **Pre-release document.** This guide has been written by Claude with an extremely light edit pass done by me (shaboogen) and only exists for early testers. Much fuller, human written / edited documents will be written before Patchbay Mod Loader is publicly released. Commands, paths and tool names may change before then.

**Disclaimer: Patchbay Mod Loader is not affiliated with Bitwig Studio or Bitwig GmbH. If anyone from Bitwig has any issues with this software, please contact me.**

Patchbay Mod Loader changes Bitwig Studio's code when Bitwig starts, so mods can add to it or change how it behaves. Bitwig's own files on disk are never modified. This folder is a complete install for macOS: the loader, the map for every supported Bitwig version, the frameworks and every accepted mod, plus the mod kit and the modder guide. Its files and folders are named BML; the app is called Patchbay for short.

**Patchbay is unsigned on macOS.** It is not signed or notarised by Apple. macOS marks a downloaded file as quarantined and then refuses to load BML's agent into Bitwig, and to open `BML.command` from Finder. The first start of each downloaded copy is therefore one Terminal line (step 3 below), which removes that mark from BML's own folder only.

## What you need

| What | Check |
|---|---|
| macOS 12 or newer, Apple silicon or Intel | Apple menu → About This Mac |
| Bitwig Studio @BITWIG_VERSIONS@, in `/Applications` | Bitwig Studio → About Bitwig Studio shows the version |
| Java: nothing to install. Patchbay runs on the Java inside Bitwig Studio.app. Only if that isn't found does it use `JAVA_HOME`, `java` on `PATH` or the system Java (`/usr/libexec/java_home`), which must then be Java 21 or newer (`BML_JAVA` picks another Java) | |

## Install

1. Quit Bitwig Studio.
2. Download `@FOLDER@.zip` to your Downloads folder and double-click it in Finder (Safari may already have unpacked it). It unpacks to the folder `@FOLDER@` in Downloads. You can move that folder to any folder you can write to, before step 3 (then use its path there): Patchbay writes its prepared files, logs and settings inside its own folder.
3. The first time: open Terminal (Applications → Utilities → Terminal) and run:
   ```sh
   sh ~/Downloads/@FOLDER@/bml-ui.sh
   ```
   It prints `macOS: removed the download quarantine from BML's own folder (...); nothing outside it is changed.` and that from now on you can double-click `BML.command`. The Patchbay window opens.
4. After that, start Patchbay by double-clicking `BML.command` in `@FOLDER@`. Terminal opens a window for it; leave that window open while you use Patchbay. Each newly downloaded version of Patchbay needs the Terminal line of step 3 once, with its own folder name.
5. Go to **Settings**. Under **Bitwig Studio** it shows the Bitwig executable it found (`/Applications/Bitwig Studio.app/Contents/MacOS/BitwigStudio`). If it says not found, click **Browse…**, pick `Bitwig Studio.app`. Choosing it saves it.
6. Go to **Maps**. It must say **Bitwig Studio <your version> is supported**. If it says there is no map for the installed Bitwig, your Bitwig version isn't one of those above: stop here and report it.

## Choose mods and start Bitwig

1. Go to **Mods**. Every mod is listed; ticked mods are loaded at the next start.
   - A greyed-out mod whose tick box can't be ticked isn't available on your Bitwig version: its description starts with `Not available on …`, and the **Errors** page says why. Ticking a mod also ticks the mods it needs. Every ticked mod must be able to load: if one can't (a mod it needs is turned off, or it isn't available on your Bitwig version), its row turns red, the **Errors** page lists it with how to fix it, and **Load** refuses until it is fixed.
   - **Framework: Themes** stays unticked and unavailable until a theme is installed, and the **Errors** page lists it for that reason. This release ships no theme. To add one, put its `.bte` file, or a folder holding it, in the `components/` folder beside `mods/`, tick the framework, select its row and choose the theme in the bar at the bottom of the window. Unpacking a new version of BML starts a new `components/` folder: copy your themes across.
2. Tick or untick mods. Click a mod's row to select it: the bar at the bottom of the window shows its description and its buttons, including **README** for its documentation. Click the row again, or press Esc, to deselect it. To set a mod aside without uninstalling it, right-click it and choose **Disable Mod**: it leaves the list and is never loaded. **Show Disabled** lists it again, with **Enable Mod**.
3. Click **Load**. Patchbay prepares the mods (a progress bar shows the stage) and starts Bitwig. On Bitwig 6.1.4, preparing takes about 15 seconds. On other versions it can take a minute or more, because Patchbay first translates each mod to that version. Starting again with the same mods reuses the result and is quick; changing which mods are ticked, updating a mod or updating Bitwig prepares again.
4. Always start Bitwig from the Patchbay window when you want mods. Started from the Dock, Launchpad or Finder, Bitwig runs without mods.
5. **Stop / Quit** in the Patchbay window asks Bitwig to quit, as Cmd+Q does (Bitwig may first ask to save). The first time, macOS asks whether Terminal may control Bitwig Studio: click **OK**. If you declined, allow it in System Settings → Privacy & Security → Automation (Terminal → Bitwig Studio) and press **Stop / Quit** again.

To run Bitwig without mods again, start it as usual. Nothing has to be uninstalled; to remove Patchbay, delete its folder.

## Where your settings are

Patchbay keeps your mods' settings and files, which mods are ticked and its own choices in `~/Library/Application Support/BML`, for your user alone. A new version of Patchbay, in a new folder, finds them there. Everything else, including sessions and logs, stays in Patchbay's own folder.

Deleting Patchbay's folder doesn't remove the settings. To remove them, use **Settings → Settings Folder → Delete Patchbay's Settings…** in the Patchbay window with Bitwig closed, before you delete Patchbay's folder (Patchbay closes afterwards), or delete that folder by hand. **Open Folder** beside it shows the folder.

## When something goes wrong

1. In the Patchbay window, go to **Sessions**, select the latest session and click **View Log**: the log has the preparation output. A line starting with `PREPARE_FAILED` names the problem.
2. In **Settings**, tick **Show Debug Mode**. Then tick **Debug Mode** in the window's footer, next to **Load**, and click **Load** again: the session then also keeps traces.
3. Report: your macOS version and Mac (Apple silicon or Intel), your Bitwig version, the mods ticked, the session log (or the ZIP that **ZIP Session** makes), Bitwig's own log `~/Library/Logs/Bitwig/BitwigStudio.log` (BML's agent writes its lines there), and what you did when it failed.

## Writing mods

| What | Where in this folder |
|---|---|
| The guide | `docs/sdk/README.md` (start there) |
| Mod kit: `bml-api.jar`, `bml-modkit.jar` | `modkit/` (reference: `modkit/README.md`) |
| Framework jars and READMEs | `mods/<framework-id>/helper.jar` and `README.md` |
| Map packs | `maps/` |
| Offline check | `loader/prepare-mod.sh` |

The guide's build steps are written for Windows (PowerShell). The offline check works here too: put the mods to check, and the frameworks they need (copy their folders from `@FOLDER@/mods/`), into one folder, e.g. `~/check-mods`, and run (after step 3 of Install):

```sh
sh ~/Downloads/@FOLDER@/loader/prepare-mod.sh "/Applications/Bitwig Studio.app/Contents/Java" ~/check-mods
```

It ends with a line starting with `PREPARE_OK` when every mod prepares for the installed Bitwig.

If you clone the BML repository to use its build tools (`tools/build_mod.py`, `tools/bml_build.py`, `tools/mod_uses.py`), the guide calls the folder holding `BML/` your workspace. Unpack `BML-@VERSION@-workspace-maps.zip` (from the same release) into that folder, so the map packs are at `<workspace>/maps/<build>/` (`6.1.4/` for Windows 6.1.4, `linux-6.1.4/`, `macos-6.1.4/`).
