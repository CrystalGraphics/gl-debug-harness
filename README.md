# GL debug harness

A standalone LWJGL 3 window for CrystalGraphics and CrystalGUI: scenes, gates and benchmarks with no Minecraft.

## What it needs

**CrystalGUI and CrystalGraphics, from source.** The harness ships neither and holds no submodules. It names them by
their coordinates (`com.crystalgui:core` and `com.crystalgui:language`, `com.crystalgraphics:*`), and the build it runs
in supplies them.

## Building it

**Inside CrystalGUI**, where it is the `gl-debug-harness` submodule, it is one of CrystalGUI's projects, and
CrystalGUI's own scenes (`harness-scenes`) come with it:

```bash
# from the CrystalGUI checkout
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-gallery"
```

**On its own**, from this directory, it includes CrystalGUI as a build, and CrystalGUI includes CrystalGraphics.
Where they are comes from `local.properties`, which is not committed:

```properties
# Default: .. -- this checkout inside CrystalGUI's
crystalGuiDir=X:/projects/CrystalGUI
# Default: CrystalGUI's own CrystalGraphics submodule
crystalGraphicsDir=X:/projects/CrystalGraphics
```

```bash
./gradlew runHarness --args="--list"
```

A relative path is from this directory. Built this way the harness has its own scenes only, without CrystalGUI's.

[`AGENTS.md`](AGENTS.md) has the scenes, the switches and how to write a scene.
