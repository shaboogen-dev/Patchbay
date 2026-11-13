# Framework: Nitro Devices

Lets Bitwig Studio load native (Nitro) devices, modulators and Grid modules that don't ship with Bitwig: `.bwdevice`, `.bwmodulator` and `.bwmodule` files kept in folders of your own. They appear in Bitwig's browser and behave like Bitwig's own. Bitwig's own library folders stay as they are.

- Mod ID: `custom-device-registrar`
- Author: shaboogen
- Built against Bitwig 6.1.4.
- Needs Patchbay loader 0.36.0-dev.11 or later: an earlier loader can't read the way this framework declares its config file.

## Choosing your folders

In BML, open **Device Folders** under Tools, add the folders that hold your devices, and press Load. Remove a folder and press Load, and its devices are gone from Bitwig. Devices you move, add or delete inside a listed folder need nothing more than a restart of Bitwig.

A listed folder that is gone, isn't responding or can't be read is an error on BML's Errors page while the framework is ticked, and Load refuses until you fix it: reconnect or restore the folder, or remove it from Device Folders. Folders under `directories`, below, aren't checked.

The list is kept in the framework's config file, `mods/custom-device-registrar/config.json` in BML's settings folder (**Settings > Settings Folder** in BML's window shows where). An update of the framework leaves the file alone. You can also edit it by hand:

```json
{
  "schema_version": 1,
  "folders": ["D:\\Nitro", "E:\\Shared devices"]
}
```

## What the framework does at each start

It goes through each listed folder and its subfolders, skipping hidden folders and `auto-backups` as Bitwig's own library scan does, and reads the UUID of every `.bwdevice`, `.bwmodulator` and `.bwmodule` file with Bitwig's own reader. A folder may mix all three kinds. Then, for each file:

- It registers the file in Bitwig's native device registry under its UUID, kind and name. The name is the file name without its extension.
- When Bitwig reopens a project and looks up the registered UUID, it uses the exact file found by the scan, including its subfolder.
- It enables it, as Bitwig's own devices are.
- It gives it its name as a search keyword in the browser when Bitwig has none for it.

Each folder becomes an extra native library, next to Bitwig's own, for each kind it holds.

The rest is logged once in the session's mod log, and the framework itself never stops Bitwig from starting:

- A folder that doesn't exist at this point, or a file that can't be read, is skipped.
- A folder inside another listed folder is skipped, since the outer one already covers it.
- Of two files with the same UUID, or the same kind and name, the first wins: folders in the listed order, then by path within a folder.
- A file with a UUID Bitwig already has, such as a copy of one of its own devices, is not registered. Bitwig's own entry stays.
- Links are followed; a link that loops back is skipped. Subfolders are followed 32 levels deep.

The scan stops after 10 seconds and keeps what it has found. It takes well under a second on an ordinary device folder, so keep the listed folders to where your devices are rather than a whole drive.

A config that can't be read registers nothing; the reason is in the session's mod log.

## Entries written by hand (deprecated)

These keys still work, but are deprecated: list your folders in Device Folders instead. The config also accepts folders per kind and explicit entries, for a package with its own search keywords or one kept outside a scanned folder. BML's tool leaves these keys as they are, and doesn't list them.

```json
{
  "schema_version": 1,
  "folders": [],
  "directories": {
    "device": ["D:\\Nitro\\Devices"],
    "modulator": ["D:\\Nitro\\Modulators"],
    "module": ["D:\\Nitro\\Modules"]
  },
  "registrations": [
    { "uuid": "11111111-2222-3333-4444-555555555555", "display_name": "MY DEVICE", "type": "DEVICE", "keywords": ["my device", "distortion"] }
  ]
}
```

| Key | Required | What it is |
|---|---|---|
| `schema_version` | no | `1` (the default). |
| `folders` | no | Folders to scan, as above. |
| `directories.device`, `.modulator`, `.module` | no | Library folders of one kind each, not scanned. If `directories` is present, all three are required. A folder that doesn't exist, or is Bitwig's own, is skipped. |
| `registrations[]` | no | One entry per package file. These come before anything a scan finds, and their UUIDs and names must be unique. |
| `registrations[].uuid` | yes | The package's UUID. |
| `registrations[].display_name` | yes | The package's name: its file name without the extension (`MY DEVICE` for `MY DEVICE.bwdevice`). |
| `registrations[].type` | yes | `DEVICE`, `MODULATOR` or `MODULE`. |
| `registrations[].keywords` | no | Search keywords, used when Bitwig has none for the package. |

Nothing is shipped: until you add folders in Device Folders there is no config file, and the framework registers nothing.

When a hand-written entry matches a scanned file by UUID and kind, it keeps its configured name and keywords and uses the file found by the scan.
