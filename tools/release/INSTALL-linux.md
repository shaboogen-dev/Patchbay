# Installing Patchbay Mod Loader (tester release)

> **Pre-release document.** This guide has been written by Claude with an extremely light edit pass done by me (shaboogen) and only exists for early testers. Much fuller, human written / edited documents will be written before Patchbay Mod Loader is publicly released. Commands, paths and tool names may change before then.

**Disclaimer: Patchbay Mod Loader is not affiliated with Bitwig Studio or Bitwig GmbH. If anyone from Bitwig has any issues with this software, please contact me.**

Patchbay Mod Loader changes Bitwig Studio's code when Bitwig starts, so mods can add to it or change how it behaves. Bitwig's own files on disk are never modified. This folder is a complete install for Linux: the loader, the map for every supported Bitwig version, the frameworks and every accepted mod, plus the mod kit and the modder guide. Its files and folders are named BML; the app is called Patchbay for short.

## What you need

| What | Check |
|---|---|
| Linux, x64 | `uname -m` prints `x86_64` |
| Bitwig Studio @BITWIG_VERSIONS@, installed from Bitwig's `.deb` package (it installs to `/opt/bitwig-studio`) | Help → About in Bitwig shows the version |
| Java: nothing to install. Patchbay runs on Bitwig Studio's own bundled Java. Only if that isn't found does it use `JAVA_HOME` or `java` on `PATH`, which must then be Java 21 or newer (`BML_JAVA` picks another Java) | |
| `unzip` | `unzip -v` |

## Install

1. Close Bitwig Studio.
2. Unpack the release ZIP into your home folder:
   ```sh
   unzip ~/Downloads/@FOLDER@.zip -d ~
   ```
   This gives `~/@FOLDER@/bml-ui.sh` and so on. Any folder you can write to works (then use its path below): Patchbay writes its prepared files, logs and settings inside its own folder.
3. Start Patchbay in a terminal:
   ```sh
   sh ~/@FOLDER@/bml-ui.sh
   ```
   The Patchbay window opens. Start Patchbay this way each time.
4. Go to **Settings**. Under **Bitwig Studio** it shows the Bitwig executable it found (`/opt/bitwig-studio/bitwig-studio`). If it says not found, click **Browse…**, pick `bitwig-studio` in the folder Bitwig is installed in. Choosing it saves it.
5. Go to **Maps**. It must say **Bitwig Studio <your version> is supported**. If it says there is no map for the installed Bitwig, your Bitwig version isn't one of those above: stop here and report it.

## Choose mods and start Bitwig

1. Go to **Mods**. Every mod is listed; ticked mods are loaded at the next start.
   - A greyed-out mod whose tick box can't be ticked isn't available on your Bitwig version: its description starts with `Not available on …`, and the **Errors** page says why. Ticking a mod also ticks the mods it needs. Every ticked mod must be able to load: if one can't (a mod it needs is turned off, or it isn't available on your Bitwig version), its row turns red, the **Errors** page lists it with how to fix it, and **Load** refuses until it is fixed.
   - **Framework: Themes** stays unticked and unavailable until a theme is installed, and the **Errors** page lists it for that reason. This release ships no theme. To add one, put its `.bte` file, or a folder holding it, in the `components/` folder beside `mods/`, tick the framework, select its row and choose the theme in the bar at the bottom of the window. Unpacking a new version of BML starts a new `components/` folder: copy your themes across.
2. Tick or untick mods. Click a mod's row to select it: the bar at the bottom of the window shows its description and its buttons, including **README** for its documentation. Click the row again, or press Esc, to deselect it. To set a mod aside without uninstalling it, right-click it and choose **Disable Mod**: it leaves the list and is never loaded. **Show Disabled** lists it again, with **Enable Mod**.
3. Click **Load**. Patchbay prepares the mods (a progress bar shows the stage) and starts Bitwig. On Bitwig 6.1.4, preparing takes about 15 seconds. On other versions it can take a minute or more, because Patchbay first translates each mod to that version. Starting again with the same mods reuses the result and is quick; changing which mods are ticked, updating a mod or updating Bitwig prepares again.
4. Always start Bitwig from the Patchbay window when you want mods. Started from its own menu entry or the `bitwig-studio` command, Bitwig runs without mods.

To run Bitwig without mods again, start it as usual. Nothing has to be uninstalled; to remove Patchbay, delete its folder.

## Where your settings are

Patchbay keeps your mods' settings and files, which mods are ticked and its own choices in `$XDG_CONFIG_HOME/bml`, or `~/.config/bml` if that isn't set, for your user alone. A new version of Patchbay, in a new folder, finds them there. Everything else, including sessions and logs, stays in Patchbay's own folder.

Deleting Patchbay's folder doesn't remove the settings. To remove them, use **Settings → Settings Folder → Delete Patchbay's Settings…** in the Patchbay window with Bitwig closed, before you delete Patchbay's folder (Patchbay closes afterwards), or delete that folder by hand. **Open Folder** beside it shows the folder.

## When something goes wrong

1. In the Patchbay window, go to **Sessions**, select the latest session and click **View Log**: the log has the preparation output. A line starting with `PREPARE_FAILED` names the problem.
2. In **Settings**, tick **Show Debug Mode**. Then tick **Debug Mode** in the window's footer, next to **Load**, and click **Load** again: the session then also keeps traces.
3. Report: your Linux distribution, your Bitwig version, the mods ticked, the session log (or the ZIP that **ZIP Session** makes), `~/@FOLDER@/state/bitwig-console.log` (what Bitwig printed when Patchbay started it), and what you did when it failed.

## Writing mods

| What | Where in this folder |
|---|---|
| The guide | `docs/sdk/README.md` (start there) |
| Mod kit: `bml-api.jar`, `bml-modkit.jar` | `modkit/` (reference: `modkit/README.md`) |
| Framework jars and READMEs | `mods/<framework-id>/helper.jar` and `README.md` |
| Map packs | `maps/` |
| Offline check | `loader/prepare-mod.sh` |

The guide's build steps are written for Windows (PowerShell). The offline check works here too: put the mods to check, and the frameworks they need (copy their folders from `~/@FOLDER@/mods/`), into one folder, e.g. `~/check-mods`, and run:

```sh
sh ~/@FOLDER@/loader/prepare-mod.sh /opt/bitwig-studio/bin ~/check-mods
```

It ends with a line starting with `PREPARE_OK` when every mod prepares for the installed Bitwig.

If you clone the BML repository to use its build tools (`tools/build_mod.py`, `tools/bml_build.py`, `tools/mod_uses.py`), the guide calls the folder holding `BML/` your workspace. Unpack `BML-@VERSION@-workspace-maps.zip` (from the same release) into that folder, so the map packs are at `<workspace>/maps/<build>/` (`6.1.4/` for Windows 6.1.4, `linux-6.1.4/`, `macos-6.1.4/`).
