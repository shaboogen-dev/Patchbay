# Patchbay Mod Loader

> **Pre-release document.** This guide has been written by Claude with an extremely light edit pass done by me (shaboogen) and only exists for early testers. Much fuller, human written / edited documents will be written before Patchbay Mod Loader is publicly released. Commands, paths and tool names may change before then.

**Disclaimer: Patchbay Mod Loader is not affiliated with Bitwig Studio or Bitwig GmbH. If anyone from Bitwig has any issues with this software, please contact me.**

Patchbay Mod Loader loads mods into Bitwig Studio; its window is called Patchbay for short, and its files and folders are named BML. When Bitwig starts, it changes Bitwig's code in memory so mods can add to it or change how it behaves. Bitwig's own files on disk are never modified: start Bitwig without Patchbay and it runs as it always does.

- **Supported:** Bitwig Studio 6.0, 6.1, 6.1.1, 6.1.2, 6.1.3 and 6.1.4 on Windows x64; the same versions on Linux x64 and on macOS 12 or newer (Apple silicon and Intel); on Linux and macOS, 6.0-6.1.3 are not yet tested in Bitwig. BML is unsigned on macOS: the first start of each download is one Terminal line (`INSTALL.md` in the macOS release).
- **Mods:** BML ships with mapping designed to facilitate best-effort matching of mods written against a version of Bitwig to all other supported versions of Bitwig. Currently, this is in place for mods written against 6.1.4. Mods written against other supported versions will work when loading that version.
- **Status:** pre-release, for testers.

## Use Patchbay

1. Download the ZIP for your system from the latest release on this repository's **Releases** page: `BML-<version>-windows.zip`, `BML-<version>-linux.zip` or `BML-<version>-macos.zip`.
2. Unpack it. It gives a folder named after the ZIP, e.g. `BML-0.34.0-dev.20-macos`.
3. Start Patchbay (with Bitwig closed); the Patchbay window opens:
   - **Windows:** double-click `bml-ui.cmd` in that folder.
   - **Linux:** in a terminal, run `sh <folder>/bml-ui.sh`, e.g. `sh ~/BML-0.34.0-dev.20-linux/bml-ui.sh`.
   - **macOS:** BML is unsigned, so the first start of each download is one line in Terminal (Applications → Utilities → Terminal), e.g. `sh ~/Downloads/BML-0.34.0-dev.20-macos/bml-ui.sh`. It removes macOS's download mark from BML's own folder only. After that, double-click `BML.command` in the folder. A newly downloaded version needs the Terminal line once again, with its own folder name.
4. In the Patchbay window, tick the mods you want and click **Load**: Patchbay prepares them and starts Bitwig. Start Bitwig from the Patchbay window whenever you want mods; started the usual way, it runs without them.

`INSTALL.md` inside the ZIP has the full steps, including what to check in **Settings** and **Maps** and what to send when something goes wrong.

The release contains the loader, the map packs, the eight frameworks and every accepted mod. The mods' source is in the [Mods](https://github.com/shaboogen-dev/Mods) repository.

## Where BML keeps things

Mods and map packs, sessions and logs are in BML's own folder. Your mods' settings and files, which mods are ticked and BML's own choices are in a folder for your user, so a new version of BML finds them:

- **Windows:** `%LOCALAPPDATA%\BML`
- **macOS:** `~/Library/Application Support/BML`
- **Linux:** `$XDG_CONFIG_HOME/bml`, or `~/.config/bml` if that isn't set

Deleting BML's folder doesn't remove this one. To remove it, use **Settings → Settings Folder → Delete Patchbay's Settings…** in the Patchbay window with Bitwig closed (Patchbay closes afterwards), or delete the folder by hand. **Open Folder** beside it shows the folder. Your devices stay where you keep them: only the list of their folders is here.

## Write mods

Start with the modder guide, [`docs/sdk/README.md`](docs/sdk/README.md). It covers what a mod is, writing one with the mod kit, the frameworks, building and checking a mod, and converting existing mods (including mods that ship a modified `bitwig.jar`).

[`docs/maps.md`](docs/maps.md) describes the map pack format and how BML uses map packs, for anyone writing their own mapping tool.

## What is in this repository

| Folder        | What                                                                                                                                                                |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `loader/`     | The loader: the Patchbay window, the preparer and the native agent. `python loader/build.py` builds it ([`loader/README.md`](loader/README.md)).                         |
| `modkit/`     | The mod kit: `@Hook` annotations, the runtime API `bml.api.Bml`, and the tool that writes a mod's hooks into its manifest ([`modkit/README.md`](modkit/README.md)). |
| `frameworks/` | The ten frameworks: Actions and Menus, Settings, Inspector, Themes, Nitro Devices, Panels, Browser, Notifications, Piano Roll, Plug-in Panels. |
| `docs/`       | The modder guide (`docs/sdk/`) and the map pack format (`docs/maps.md`).                                                                                            |
| `tools/`      | Build and check tools: building mods, test installs and the release, the source porter, the map validator ([`tools/README.md`](tools/README.md)).                   |

The build tools expect a workspace: one folder holding this repository as `BML/`, the [Mods](https://github.com/shaboogen-dev/Mods) repository as `Mods/` beside it, and Bitwig's jars in `bitwig/jars/<version>/` ([`tools/README.md`](tools/README.md)). Map packs go in `maps/<build>/` (`maps/6.1.4/` for Windows, `maps/linux-6.1.4/`, `maps/macos-6.1.4/`): unpack `BML-<version>-workspace-maps.zip` from the release into the workspace folder.

Building needs JDK 25.0.4.1 (`javac --release 21`), Python 3 and, for the native agents, MSVC from Visual Studio Build Tools 2026 (Windows), GCC 13.3.0 in WSL Ubuntu 24.04 (Linux) and, on a Mac, Xcode Command Line Tools 26.6 (macOS; `loader/build.py --mac-kit` packs what the Mac build needs). Never commit Bitwig's files or anything built: `.gitignore` keeps them out.

## Licence

MIT ([`LICENSE`](LICENSE)). Framework: Themes builds on Berikai's [Bitwig Theme Editor](https://github.com/Berikai/bitwig-theme-editor); its notice is in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
