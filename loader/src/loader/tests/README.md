# The window

`PatchbayScreenshot.java` renders each page of the Patchbay window to PNG (Mods, Maps, Errors,
Sessions, Settings, a selection bar, the README reader) without starting Bitwig. Compile it against
a built `bml-control.jar` and run it with the install to show:

```
javac -cp bml-control.jar -d tests-classes loader/tests/PatchbayScreenshot.java
java -cp "bml-control.jar;tests-classes" dev.bitwigre.bml.PatchbayScreenshot <BML root> <out folder> [width height]
```

A folder without `loader/app/bml-control.jar` keeps its settings in its own `config/` and `state/`,
so pointing it at a scratch copy of `mods/`, `maps/` and `components/` touches nothing of the
user's. `java -jar bml-control.jar --self-test` checks the window's data side (ticks, sessions,
manifests, tools, settings folder, components).

`PatchbayDisabledSmoke.java` drives Disable Mod, the Show dropdown's Disabled choice and Enable Mod through the window's
own code over a test install (one with `config/portable`), and checks that a ticked mod's
prerequisite can't be disabled and that ticking a mod enables its disabled prerequisite again. It
adds a mod with an error for the run and removes it after; prints `DISABLE_SMOKE_OK`:

```
javac -cp bml-control.jar -d tests-classes loader/tests/PatchbayDisabledSmoke.java
java -cp "bml-control.jar;tests-classes" dev.bitwigre.bml.PatchbayDisabledSmoke <test install> [png folder]
```

---

# Java Preparer synthetic smoke tests

Both scripts are safe to run without an installed Bitwig Studio copy. They
construct synthetic vendor classfiles in temporary directories and use temporary
copies of the distributed compatibility metadata. No proprietary vendor content
is created or distributed, and no user installation is modified.

Run with JDK 21+ (javac, jar, java) and Python 3:

```
python3 loader/tests/smoke_java_preparer.py
python3 loader/tests/smoke_transform_families.py
```

The test runner has been executed on Linux. Windows runtime behaviour, native
agent integration and cross-version live Bitwig compatibility are **not** proven
by these tests. Do not classify synthetic patched-class execution as a Bitwig
runtime acceptance.
