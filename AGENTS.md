# GL Debug Harness — Agent Knowledge Base

**Module**: `gl-debug-harness/`  
**Purpose**: Standalone LWJGL 3 debug harness (GLFW, the newest OpenGL core context the driver grants, 4.6 down to 3.3) for CrystalGraphics, running outside Minecraft to test font rendering, FBO pipelines, atlas generation, and shader behavior in isolation.

---

## ⏱ `--seconds=N` — ALWAYS use this for an unattended run

**An interactive scene runs until its window is closed. If nothing is going to close it, it never returns.**
Every scene accepts `--seconds=N`, and any agent or script launching one should pass it.

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=text-3d --seconds=5"
```

| | |
|---|---|
| Applies to | **every scene**, interactive and managed — it is enforced by the runner, not by scene code, so a scene cannot omit it and a new scene gets it for free |
| Timed from | scene start, so GL context creation and shader compilation are not counted against it |
| Values | any positive number; fractional is fine (`--seconds=0.5` is enough to prove a scene draws) |
| Exit code | **0**. Reaching the cap is the requested outcome, not a failure — a non-zero code would make every timed run a red build |
| Default | absent = run until closed, which is the right default for a human at the keyboard |

## `--device=gl|tracked|vulkan` — what `CgGL` runs on

`gl` (the default) is the driver. The other two run CrystalGraphics' tracked backend (`plan/device-seam.md`
§5), and the tracker's counts print at the end:

- **`tracked`** — over a **recording device**: every command a Vulkan device would be sent, validated, and
  **nothing presented** — the window stays black. Captures and readbacks come back black.
- **`vulkan`** — over **`CgVulkanDevice`** on its own device (`OwnedVulkanHost`), presenting to the window,
  which then has no GL context. The Khronos validation layer is on whenever it is installed (the LunarG SDK);
  `-Dcrystalgraphics.harness.vulkanValidation=false` turns it off for a timing run, since it checks every
  command. Its messages print as `[vulkan] ERROR ...`, and their count ends the `tracked:` line.
  `-Dcrystalgraphics.vulkan.syncValidation=true` adds its synchronization checks, with the shader-access analysis
  they need to see what a shader reads and writes through pushed descriptors: a missing barrier between passes
  is then an error. `--mode=compute-seam` is the scene that proves it fires
  (`-Dcrystalgraphics.harness.computeSeam.skipBarriers=true` must fail). It also dispatches
  `harness:shaders/compute_seam.compute` through `CgKernelProgram`, subgroups native and emulated, against the same
  CPU reference: the gate for `.compute` on each device. `--mode=compute-graph` is the frame graph's: kernels, a
  history and an indirect dispatch recorded and built on a worker; with `-Dcrystalgraphics.graph.barriers=false` it
  must fail under synchronization validation. `--mode=indirect-draw` is the indirect draws': counts a kernel wrote,
  drawn in every mode and matched against direct draws, in a graph and through the world renderer; it must fail the
  same way with the barriers off. `--mode=material-buffer` is a material's: a kernel's records compacted on the GPU,
  each live one placed and coloured by a material's `Buffers { }` through one indirect draw, in a graph and through the
  world renderer; it must fail the same way too. `--mode=raster-levels` is raster passes into mip levels: a chain drawn
  level by level in one texture, each level reading the one above through a level view, every texel checked against
  Java. `--mode=readback` is `CgRecording.readback`'s: kernel-written words, a float target, an unaligned byte target
  and a mip level read back every frame, each delivery checked against the frame that recorded it.
  `--mode=async-compute` is `CgComputePass.async()`'s: blurs beside fill-bound drawing, timed alone, in order and
  async, the outputs of both orders compared byte for byte; on `vulkan` the async frame must save a quarter of the
  drawing, elsewhere it runs in order. `--mode=multi-draw` is joined draws': 68 instances of 66 meshes drawn joined
  into multi-draws and then a call a draw, the pictures compared byte for byte and the calls counted.
  `-Dcrystalgraphics.graph.asyncAll=true` sends every compute pass async in any
  scene, which the scenes above must pass under synchronization validation. `--mode=compute-tiers` is the gate for the forms a kernel takes: one kernel per
  shape, a general one with a lowerable fallback, one with only a Java body, one with neither, every result worked out
  in Java (`CgComputeSelfTest`, the engine's, which a client runs too). All five scenes must pass forced to each tier
  (`-Dcrystalgraphics.compute.tier=G43|G40|G33|CPU`, and G33 with `-Dcrystalgraphics.shaderBuffer.tier=TBO` for a 3.3
  context's GLSL), unforced on both downlevel contexts (below), and on `vulkan` at its own tier and at CPU.

## `-Pharness.downlevel=mac41|gl33` — a context that genuinely lacks a feature

A forced tier on NVIDIA still has every extension and NVIDIA's lenient GLSL compiler. A downlevel run loads Mesa's
llvmpipe (mesa-dist-win, fetched once by `fetchMesa` into the Gradle cache) in place of the driver, shaped as macOS's
GL 4.1 core or a bare GL 3.3: `MESA_GL_VERSION_OVERRIDE`, and every extension a real context of that version lacks
removed. Windows only; `--device=gl` only.

```bash
./gradlew :gl-debug-harness:runHarness -Pharness.downlevel=mac41 --args="--mode=compute-tiers --seconds=5"
# [harness] downlevel mac41: 4.1 (Core Profile) Mesa 26.2.3 on llvmpipe (LLVM ...), 98 extensions listed
```

- **`downlevel/<name>.txt` lists what a context keeps**; everything else in `downlevel/mesa.txt` is removed, and the
  window refuses to open when Mesa lists one neither names, so a Mesa update cannot silently widen a context.
- **Some Mesa extensions cannot be removed**: about 45 are always on, and a few share an internal flag with one a
  context keeps (removing `GL_ATI_texture_float` kills `RGBA32F`, removing `GL_ARB_multi_draw_indirect` kills
  draw indirect). `downlevel/mesa-shared.txt` keeps those listed by Mesa and the build hands every one to
  `-Dcrystalgraphics.gl.disableExtensions`, so the engine treats them as absent all the same.
- **`MESA_DEBUG=1`** prints each GL error with its function and enum; Mesa's context has no debug output for
  `-Dcrystalgraphics.gl.debugStacks`.
- llvmpipe renders on the CPU: a scene that times anything means nothing here.

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-desktop --device=tracked --seconds=10"
# INFO: [Harness] tracked: frames=314 deviceDraws=88495 ... passes=4778 breaks=709 ... misses=17
./gradlew :gl-debug-harness:runHarness --args="--mode=shader-compile-audit --device=vulkan"
# every shipped shader, kernel and keyword combination compiled, and its pipeline built: the driver's own compile
```

A scene fails on either by throwing: a call the device cannot express, or a draw it would refuse (a binding
with no buffer, a draw with no program) -- a non-zero exit, with the `tracked:` line missing. What cannot
apply: `host-section` and `gl-state-dump` call raw GL, and `gpu-trace-probe` times a GPU a recording device
does not have.

## One picture per frame number — `captureAt` and `fixedDelta`

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-desktop --device=vulkan --seconds=150" \
    -Dcrystalgraphics.harness.fixedDelta=0.0166667 -Dcrystalgraphics.harness.captureAt=180
# <scene>-frame180.png: the same picture on any device and machine, however fast each ran
```

`fixedDelta` advances the frame clock by exactly that much per frame; `captureAt` photographs that frame and
stops, **ignoring live input meanwhile** — a pointer crossing the window would otherwise be grabbed and turn
the camera. This is how GL and Vulkan are compared (`plan/device-seam/device-milestones.md` D3.10).

**A scene animates from `FrameInfo`, never from `System.nanoTime()` or `currentTimeMillis()`**, or its frame N
is a different picture every run. What may read the wall clock is what shows measured time — the HUD's FPS,
a frame-time readout — and a comparison masks those.

**`-Dcrystalgraphics.harness.gl=<major.minor>`** asks for that core context alone instead of the newest the driver
gives: `3.2` is Minecraft 1.17 to 1.21.4's. It is no stand-in for macOS's 4.1, since a desktop driver lists every
extension in a 4.1 context too; `-Dcrystalgraphics.compute.tier` is how a lower tier is tested.
`--mode=capability-report` writes `CgGpuReport`'s table for the context or device it ran on.

Three more unattended switches, for what a single picture cannot show:

| Flag | Does |
|---|---|
| `-Dcrystalgraphics.harness.frameTimes=<n>` | after 60 frames, times `n` frame to frame, logs `[frame-times]` median, mean and p95, and stops. Pair with `.fps=0`, or the pacing is what gets timed; live input is ignored |
| `-Dcrystalgraphics.harness.reloadAt=<frame>` | Ctrl+R's reload at that frame; with `.reloadStage=<dir>`, that directory's files are first copied over the first `crystalgraphics.resourceOverrideDirs` root — an edit saved mid-run |
| `-Dcrystalgraphics.harness.resizeAt=<frame>:<w>x<h>` | resizes the window at that frame |

On `--device=vulkan`, `[Harness] vulkan after teardown: validationErrors=N` is printed after the device has
closed, so it counts what teardown raised. `-Dcrystalgraphics.vulkan.presentMode=immediate|mailbox|fifo` forces
a present mode.

## A GPU capture — `--renderdoc`

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-desktop --renderdoc"                  # RenderDoc's install path
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-desktop --renderdoc=D:/rd/renderdoc.dll"
# [RenderDoc] attached, API 1.x.y -- then F12, or RenderDoc.java from code
```

Loads RenderDoc before the window exists (it hooks context creation) and binds its in-app API through
`java.lang.foreign`. Captures go to `harness-output/<scene>/renderdoc/<scene>_frameN.rdc`; open one in the RenderDoc
UI, which also lists them live under File > Attach to Running Instance. The replay gives each draw's GPU duration and
every resource's contents — what the frame's own `gpu:` zones cannot, since a `GL_TIME_ELAPSED` zone includes
whatever queue sat in front of it.

From a scene or probe, `runtime.RenderDoc`: `captureNextFrames(n)`, `startFrameCapture()` then
`endFrameCapture()` or `discardFrameCapture()`, and `captures()` for the files written. Every call is a no-op
without `--renderdoc`, so none needs a guard. OpenGL only — a Vulkan capture needs RenderDoc's layer, which this does
not load.

**Attached, every frame is 2-4x slower, captured or not** (the desktop with the shader graph: 8.3 ms to 33 ms wall,
GPU 4 ms to 32 ms). So a run with `--renderdoc` measures nothing live, and a hitch it catches is RenderDoc's: capture
for what the replay shows, never for when. `ShaderGraphCostProbe`'s `-Dcrystalgui.harness.desktop.graphCost.renderDoc`
captures one settled frame.

Reading a capture without the UI: `qrenderdoc.exe --python script.py` runs a script in RenderDoc's embedded Python
(`import renderdoc as rd`; `rd.OpenCaptureFile()`, `OpenCapture`, `FetchCounters([rd.GPUCounter.EventGPUDuration])`
per event, `GetPipelineState()` for what an event drew into). End it with `os._exit(0)` or the UI stays open.


---

## Quick Start

```bash
# List all modes
./gradlew :gl-debug-harness:runHarness --args="--list"

# Run a specific scene
./gradlew :gl-debug-harness:runHarness --args="--mode=triangle-2d"

# Run atlas dump
./gradlew :gl-debug-harness:runHarness --args="--mode=atlas-dump"

# Run with options
./gradlew :gl-debug-harness:runHarness --args="--mode=atlas-dump --atlas-type=mtsdf --font-size-px=128"

# Run a 3D scene
./gradlew :gl-debug-harness:runHarness --args="--mode=text-3d"

# Run a 3D scene
./gradlew :gl-debug-harness:runHarness --args="--mode=image"

# Shader #include preprocessor test
./gradlew :gl-debug-harness:runHarness --args="--mode=shader-lib-test"

# Material
./gradlew :gl-debug-harness:runHarness --args="--mode=material-dual-path"

# CgWorldRenderer, fired as the world stages are
./gradlew :gl-debug-harness:runHarness --args="--mode=forward-renderer"

# Attached buffer stress test
./gradlew :gl-debug-harness:runHarness --args="--mode=attached-buffer-stress"

# Mesh loader test
./gradlew :gl-debug-harness:runHarness --args="--mode=mesh-test"

# The VFX showcase: sixteen effect spheres (CgVfxShowcase)
./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-spheres"

# Frame graph: three paths, identical PNGs
./gradlew :gl-debug-harness:runHarness --args="--mode=graph-executor-test"

# The world's bloom: a glowing ball behind a wall changes no pixel, one in front does; PASS/FAIL, run on gl and vulkan
./gradlew :gl-debug-harness:runHarness --args="--mode=bloom-occlusion --seconds=20"

# Text recorded on a worker beside render-thread text; prints PASS/FAIL lines
./gradlew :gl-debug-harness:runHarness --args="--mode=text-threaded"

# Run a 3D scene with custom output name
./gradlew :gl-debug-harness:runHarness --args="--mode=text-3d --output-name=my-test"

# Run tests
./gradlew :gl-debug-harness:test

# Compile only
./gradlew :gl-debug-harness:compileJava
---

```

# ⚠️ AGENT EXECUTION RULES — READ BEFORE ANYTHING ELSE

**These rules apply to ALL agents operating in this repository, including subagents.**

- **When creating new scenes, NEVER implement raw low level GL calls. Always rely on the existing infrastructure in CrystalGraphics.**
  Instead of `int vao = GL30.glGenVertexArrays` or `int vbo = GL11.glGenBuffers`, use
  CgMesh for geometry and the world renderer or CgImmediate to draw it. Same for Shaders -> CgShaderBuffer,
  Textures -> CgTexture, Shader buffers -> CgShaderBuffer,
  FBO -> CgFrameBuffer, etc...
- **What is left at the GL level goes through `CgGL`, never `org.lwjgl.opengl`.** CrystalGraphics' state
  shadow sees only `CgGL`, so a raw write makes it skip a later call it thinks is redundant. Two exceptions:
  the host layer (`HarnessWindow`, `HarnessContext`) is the harness's LWJGL host, and a scene playing a
  foreign host (`CgHostSectionScene`) draws raw inside `CgGlState.hostForeign` on purpose. For an upload
  from a `FloatBuffer`/`IntBuffer`, `HarnessBuffers.bytes(buffer)` is the byte view `CgGL` takes.

---

## Architecture Overview

The harness is split into two execution strategies that share a common scene lifecycle:

- **Managed runtime**: single-shot scenes that call `init -> render (once) -> dispose`. Used for FBO captures, atlas dumps, and diagnostic tools.
- **Interactive runtime**: continuous render-loop scenes with camera, overlays, and timed capture. Calls `init -> render (loop) -> dispose`.

Both runtimes use the same `HarnessSceneLifecycle` interface. Interactive scenes extend it via `InteractiveSceneLifecycle` to add loop-control hooks (`isRunning()`, `uses3DCamera()`, `shouldShutdownOnComplete()`).

Scenes should implement `HarnessSceneLifecycle` (managed) or `InteractiveSceneLifecycle` (interactive) directly.

### Scene Registry

The harness's own scenes and diagnostic tools are registered explicitly in `SceneRegistry.createDefault()`.
No reflection or annotation scanning.

Each entry has:
- **SceneDescriptor**: mode id, description, lifecycle mode, FBO/depth requirements, default dimensions, clear color
- **HarnessSceneFactory**: deferred scene instantiation

### Scenes from another project — `HarnessExtension`

**The harness is CrystalGraphics-only and names no project built on it.** A project's scenes come in as a
`HarnessExtension`, found through `ServiceLoader`: CrystalGUI's live in its own `harness-scenes` module
(`com.crystalgui.harness.CrystalGuiHarness`). One interface, three hooks — `registerScenes`, `beforeReload`
(Ctrl+R, ahead of CrystalGraphics' `CgAssetReloader.reload()`), and `shaderNamespaces` (what
`shader-compile-audit` checks: each namespace's `.shader` and `.compute` under `shaders/`). Its javadoc has the example.

The harness cannot depend on the module holding them, so that module wires itself into the run:

```kotlin
evaluationDependsOn(":gl-debug-harness")
val harness = project(":gl-debug-harness")
(harness.extra["hostAssetRoots"] as ConfigurableFileCollection).from(file("src/main/resources"))
harness.tasks.named<JavaExec>("runHarness") { classpath += sourceSets.main.get().runtimeClasspath }
```

`hostAssetRoots` is what makes Ctrl+R see an edit to that module's assets. List the extension in
`META-INF/services/com.crystalgraphics.harness.HarnessExtension`; a scene id already taken throws at startup.

Lifecycle modes:
- `MANAGED`: Standard scenes that use shared helpers (FBO, projection, etc.)
- `FULL_OVERRIDE`: Scenes that own their entire GL lifecycle
- `DIAGNOSTIC`: Non-rendering tools that produce reports/dumps
- `INTERACTIVE`: 3D scenes with camera controls, render loop, and task scheduler

### Scene Lifecycle Contract

The unified lifecycle (`HarnessSceneLifecycle`) defines four hooks:

```java
public interface HarnessSceneLifecycle {
    void init(HarnessContext ctx);                // One-time GL resource setup
    void render(HarnessContext ctx, FrameInfo frame); // Render one frame (once for managed, per-frame for interactive)
    default void onResize(int width, int height) {} // Optional display resize notification
    void dispose();                                // Release GL resources
}
```

Interactive scenes extend this with loop-control hooks:

```java
public interface InteractiveSceneLifecycle extends HarnessSceneLifecycle {
    boolean isRunning();              // Return false to exit the render loop
    boolean uses3DCamera();           // True enables camera, floor, HUD
    boolean shouldShutdownOnComplete(); // True exits the program when done
}
```

`FrameInfo` is an immutable snapshot of per-frame timing: delta time, elapsed time, and frame number. For managed scenes, the runtime passes `FrameInfo.SINGLE_FRAME` (zero timing, frame 1).

### Config Resolution

Typed scene configuration is resolved once before scene execution. Scenes read their config from `ctx.getSceneConfig()` rather than parsing raw CLI args.

Resolution order (later wins):
1. Hardcoded defaults in config class constructors
2. System properties (`-Dharness.font.path=...`)
3. CLI args (`--font-path=...`)

Config classes:
- `HarnessConfig` — base (outputDir, width, height, fontPath, outputName)
- `AtlasDumpConfig` — adds atlasType, atlasSize, fontSizePx, text, parityPrewarm, prewarmBitmap, dumpAllPages, atlasPageSize
- `TextSceneConfig` — adds text, atlasSize, fontSizePx, dumpBitmapAtlas

### Typed Context Model

`HarnessContext` is the composition root. It provides typed sub-objects instead of loose mutable fields:

| Accessor | Type | Available For | Purpose |
|---|---|---|---|
| `ctx.getViewport()` | `ViewportState` | All scenes | Live viewport dimensions, updated on resize |
| `ctx.getOutputSettings()` | `OutputSettings` | All scenes | Immutable output directory + name prefix |
| `ctx.getSceneConfig()` | `HarnessConfig` (or subclass) | All scenes | Typed scene config resolved from CLI/sysprops |
| `ctx.getWorldSettings()` | `WorldSettings` | Interactive scenes | Immutable sky/floor colors resolved once per run |
| `ctx.getRuntimeServices()` | `RuntimeServices` | Interactive scenes | Typed access to runner, pause, post-render callbacks |
| `ctx.getArtifactService()` | `ArtifactService` | Interactive scenes | Framework-owned capture with semantic suffix API |
| `ctx.getCamera3D()` | `Camera3D` | Interactive scenes | Shared 3D camera |
| `ctx.getTaskScheduler()` | `TaskScheduler` | Interactive scenes | Time-based callback system |

Legacy convenience accessors like `ctx.getScreenWidth()` and `ctx.getOutputDir()` still work, but new code should use the typed accessors directly.

### World Settings

`WorldSettings` is an immutable per-run snapshot of world rendering parameters (sky color, floor color, floor half-size). It replaces direct calls to the mutable `WorldConfig` singleton during rendering.

World settings are resolved once at startup via `WorldSettings.resolveFromDefaults()` and frozen in the context. No rendering code should call `WorldConfig.get()` after this point.

```java
// How it works internally (handled by FontDebugHarnessMain):
ctx.setWorldSettings(WorldSettings.resolveFromDefaults());

// Scenes and renderers read from the frozen snapshot:
WorldSettings ws = ctx.getWorldSettings();
float skyR = ws.getSkyR();
```

### Artifact Capture Service

The `ArtifactService` centralizes all interactive screenshot capture. Scenes request captures by semantic suffix, and the service handles filename composition, viewport dimension lookup, and post-render callback scheduling.

```java
// In scene code:
ArtifactService artifacts = ctx.getArtifactService();

// Request a post-render capture (fires after full frame: scene + floor + HUD + pause):
artifacts.requestCapture("front-view");
// Writes: {outputDir}/{outputName}-front-view.png

// Immediate FBO capture (not post-render):
artifacts.captureFbo(fboId, texId, width, height, "fbo-dump");

// Immediate backbuffer capture (use only when you know the content is ready):
artifacts.captureNow("snapshot");
```

The service reads viewport dimensions live from `ViewportState` at capture time, so it never uses stale cached values.

---

## Shared Helpers

| Helper | Location | Purpose |
|---|---|---|
| `HarnessFontUtil` | `util/` | Font path resolution (config -> test font -> system font) |
| `HarnessBitmapUtil` | `util/` | FreeType bitmap normalization (pitch/row-order) |
| `HarnessProjectionUtil` | `util/` | Orthographic + perspective projection. Owns shared constants: `FOV_DEGREES`, `NEAR_PLANE`, `FAR_PLANE` |
| `HarnessFboHelper` | `util/` | FBO creation, bind/unbind, clear, capture, teardown |
| `HarnessShaderUtil` | `util/` | Shader compilation and resource loading |
| `ScreenshotUtil` | `util/` | PNG capture from backbuffer, FBO, or texture |
| `HarnessOutputDir` | `util/` | Output directory creation |
| `GlStateResetHelper` | `util/` | Canonical post-scene GL state reset (unbind shaders, VAOs, textures, restore depth/blend) |
| `RenderPassState` | `util/` | Per-pass GL state boundary helpers (world, scene, overlay, capture point) |
| `ValidationCubeHelper` | `util/` | Shared colored-cube geometry + shader for validation scenes |
| `ValidationChoreographer` | `validation/` | Scripted camera/pause/capture sequence coordinator |

### GL State Management

Scenes don't manage GL state cleanup themselves. The harness provides two mechanisms:

1. **`GlStateResetHelper.resetAfterScene()`**: Called by the runtime after every scene `render()` call. Unbinds shaders, VAOs, VBOs, textures; restores depth test; disables blend and cull face; unbinds FBOs.

2. **`RenderPassState`**: Explicit state boundary helpers for each pipeline pass. Each pass declares the state it needs via `beginXxxPass()` rather than relying on folklore cleanup.

```
Pipeline order:
  beginWorldPass()    -> floor rendering (depth ON, blend OFF)
  beginScenePass()    -> scene content (safe defaults; scene may modify)
  resetAfterScene()   -> clean up scene leftovers
  beginOverlayPass()  -> pause + HUD (depth OFF, blend ON with alpha)
  endOverlayPass()    -> restore prior state
  beginCapturePoint() -> post-render callback fires for screenshots
```

---

## Maintained Scenes

| Mode ID                | Output Directory                                                                 | Lifecycle | Description |
|------------------------|----------------------------------------------------------------------------------|---|---|
| `triangle-2d`          | `triangle-2d/triangle.png`                                                       | MANAGED | Basic colored triangle on backbuffer |
| `text-2d`              | `text-2d/text-scene.png` + `atlas/atlas-dump-<size>px.png`                       | MANAGED | Full text rendered via CgTextRenderer + FBO |
| `text-3d`              | `text-3d/{name}-normal.png`, `{name}-paused.png`, `{name}-topdown.png`           | INTERACTIVE | Interactive 3D world-space text with camera controls. Implemented by `TextScene3D`. |
| `camera-3d` | `camera-3d/{name}-front-view.png`, etc. (4 angles)                     | INTERACTIVE | 3D camera validation with cube + floor |
| `atlas-dump`           | `atlas-dump/atlas/atlas-dump-24px.png` + `atlas/atlas-dump-32px.png`             | MANAGED | Glyph atlas dump via CgTextRenderer production pipeline |


All outputs go into scene-specific subdirectories under `harness-output/`. The `{name}` placeholder defaults to the scene mode ID, overridable with `--output-name=PREFIX`.

## Diagnostic Tools

| Mode ID | Output | Description |
|---|---|---|
| `gl-state-dump` | `gl-state-dump.txt` | Current GL state: bindings, flags, limits, errors |
| `capability-report` | `capability-report.txt` | CgCapabilities + environment summary |

---

## 3D Interactive Runtime System

The interactive runtime decomposes into focused services instead of one monolithic runner:

| Component | Location | Purpose |
|---|---|---|
| `InteractiveSceneRunner` | `InteractiveSceneRunner.java` | Slim loop coordinator: sequences services, drives render loop |
| `HarnessWindow` | `runtime/HarnessWindow.java` | The GLFW window and its core context (newest granted, 4.6 down to 3.3): swap, poll, pacing, resize, cursor grab, and the key/char/pointer events the runner hands to scenes. Key STATE is `CgPlatform.input()`'s, not this class's |
| `FrameClock` | `runtime/FrameClock.java` | High-resolution frame timing (delta, elapsed, frame number) |
| `InputPauseHandler` | `runtime/InputPauseHandler.java` | Keyboard pause toggle (ESC/T) + mouse grab management |
| `ResizeHandler` | `runtime/ResizeHandler.java` | Framebuffer resize detection (`HarnessWindow.takeResized`) + propagation to viewport, GL, and renderers |
| `OverlayPipeline` | `runtime/OverlayPipeline.java` | Coordinates HUD and pause overlays with resize awareness |
| `WorldPassCoordinator` | `runtime/WorldPassCoordinator.java` | Coordinates world-base contributors like `FloorRenderer` |
| `OverlayCaptureOrchestrator` | `runtime/OverlayCaptureOrchestrator.java` | Post-scene overlay rendering order + post-render capture callback |
| `ArtifactService` | `capture/ArtifactService.java` | Semantic capture API (suffix-based filenames, live viewport) |
| `CaptureCallback` | `capture/CaptureCallback.java` | Interface for scheduling post-render captures (implemented by runner) |
| `Camera3D` | `camera/Camera3D.java` | First-person camera with WASD/SPACE/SHIFT movement and mouse look |
| `FloorRenderer` | `camera/FloorRenderer.java` | Infinite ground plane at Y=0 with grid pattern |
| `HUDRenderer` | `camera/HUDRenderer.java` | On-screen position/rotation display (top-left corner) |
| `PauseScreenRenderer` | `camera/PauseScreenRenderer.java` | Semi-transparent overlay shown when paused |
| `TaskScheduler` | `scheduler/TaskScheduler.java` | Time-based callback system for automated events |
| `HarnessDebugTools` | `debug/HarnessDebugTools.java` | Programmatic camera control + screenshot capture via `ArtifactService` |

### Rendering Pipeline (per frame)

The interactive runner sequences these steps every frame:

1. **Frame clock tick**: `FrameClock.tick()` computes delta/elapsed time, increments frame number
2. **Resize check**: `ResizeHandler.checkAndPropagate()` detects display resize, updates viewport + renderers
3. **Input polling**: `InputPauseHandler.pollPauseToggle()` checks ESC/T for pause toggle
4. **Camera update**: Process WASD/SPACE/SHIFT movement + mouse look (skipped when paused)
5. **Task scheduler tick**: Fire any callbacks whose time has elapsed
6. **World pass**: `RenderPassState.beginWorldPass()` then `WorldPassCoordinator` renders world base (e.g. floor)
7. **Scene pass**: `RenderPassState.beginScenePass()` then `scene.render(ctx, frameInfo)`
8. **Post-scene sequence**: `OverlayCaptureOrchestrator.executePostSceneSequence()`:
   - GL state reset via `GlStateResetHelper.resetAfterScene()`
   - Overlay pass (handled by `OverlayPipeline`: pause overlay if paused, HUD if 3D camera active)
   - Post-render capture callback (one-shot, after all overlays, before swap)
9. **Swap buffers**: `HarnessWindow.swapBuffers()` + `HarnessWindow.sync(fps)`

### Camera Controls

| Key | Action |
|---|---|
| W/S | Move forward/backward |
| A/D | Strafe left/right |
| SPACE | Move up |
| SHIFT | Move down |
| Mouse | Look around (yaw/pitch) |
| ESCAPE or T | Toggle pause (releases mouse cursor) |

### HUD Display

Top-left corner shows camera state in real-time:
```
Pos: (X, Y, Z)
Rot: yaw=Y pitch=P
```

---

## Agent Debug Toolkit (7 Tools)

1. **ScreenshotUtil** — PNG capture API for backbuffer/FBO/texture
2. **TextureInspector** — Capture arbitrary texture ID to PNG
3. **FboInspector** — Dump FBO color attachments to PNG
4. **GlStateDumper** — Structured GL state report to file
5. **CapabilityReport** — CgCapabilities + harness environment report
6. **GlErrorChecker** — Drain and name all pending GL errors
7. **AtlasDumper** — Dump CgGlyphAtlasPage texture(s) + manifest file

### Usage from Scene Code

```java
// Screenshot capture (direct, for managed scenes)
ScreenshotUtil.captureBackbuffer(width, height, outputDir, "screenshot.png");
ScreenshotUtil.captureFboColorTexture(fboId, texId, w, h, outputDir, "fbo.png");
ScreenshotUtil.captureTexture(texId, w, h, CgGL.GL_R8, outputDir, "tex.png");

// Interactive scene capture (preferred for interactive scenes)
ArtifactService artifacts = ctx.getArtifactService();
artifacts.requestCapture("my-view"); // -> {outputName}-my-view.png

// Texture inspector
TextureInspector.capture(texId, 512, 512, 0x8229, outputDir, "inspect.png");

// FBO inspector
FboInspector.dumpColorAttachment(fboId, 800, 600, outputDir, "fbo-color.png");

// GL error check
boolean hadErrors = GlErrorChecker.checkAndLog("after render pass");
List<String> errors = GlErrorChecker.drainErrors();

// Atlas dumper (paged atlas pages — each page is a layer of the atlas
// family's shared GL_TEXTURE_2D_ARRAY as of the atlas texture-array migration;
// dumpAllPagedPages reads pages back via ScreenshotUtil.captureArrayTextureLayer)
AtlasDumper.dumpAllPagedPages(pages, "bitmap-atlas-dump", "128px", outputDir);
// Produces: bitmap-atlas-dump-128px-page-N.png + bitmap-atlas-dump-128px-manifest.txt
```

---

## Scene Authoring Guide

### Adding a New Managed Scene

Managed scenes render a single frame and exit. They're used for FBO captures, atlas dumps, and diagnostic output.

**Step 1**: Create a class implementing `HarnessSceneLifecycle`:

```java
public class MyManagedScene implements HarnessSceneLifecycle {

    @Override
    public void init(HarnessContext ctx) {
        // Set up GL resources (shaders, FBOs, textures)
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // Render once. The runtime calls this exactly once.
        OutputSettings out = ctx.getOutputSettings();
        // ... render to FBO or backbuffer ...
        ScreenshotUtil.captureBackbuffer(
            ctx.getViewport().getWidth(),
            ctx.getViewport().getHeight(),
            out.getOutputDir(),
            out.getOutputName() + ".png"
        );
    }

    @Override
    public void dispose() {
        // Delete GL resources
    }
}
```

**Step 2**: Register in `SceneRegistry.createDefault()`:

```java
reg.register(
    SceneDescriptor.builder("my-scene")
        .description("My custom scene description")
        .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
        .category(SceneDescriptor.Category.SCENE)
        .needsFbo(true)
        .needsDepthBuffer(true)
        .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
        .build(),
    new HarnessSceneFactory() {
        public HarnessSceneLifecycle create() { return new MyManagedScene(); }
    }
);
```

Note: The factory returns `HarnessScene`. Since `MyManagedScene` implements `HarnessSceneLifecycle`, the main entry point detects this and calls the new lifecycle directly (no adapter needed).

**Step 3**: If the scene needs typed config, extend `HarnessConfig` and add the factory case in `FontDebugHarnessMain.createConfig()`.

### Adding a New Interactive Scene

Interactive scenes run a continuous render loop with camera controls, scheduled events, and automatic screenshot capture.

**Step 1**: Create a class implementing `InteractiveSceneLifecycle`:

```java
public class MyInteractiveScene implements InteractiveSceneLifecycle {

    private boolean running = true;
    private Camera3D camera;
    private TaskScheduler scheduler;
    private ArtifactService artifacts;

    @Override
    public void init(HarnessContext ctx) {
        // Grab shared subsystems from context
        this.camera = ctx.getCamera3D();
        this.scheduler = ctx.getTaskScheduler();
        this.artifacts = ctx.getArtifactService();

        // Initialize scene GL resources
        // ...

        // Position camera
        camera.moveCamera(0f, 3f, 8f);
        camera.setPitch(-15f);

        // Schedule timed events
        scheduler.schedule(0.5, "capture-normal", new Runnable() {
            @Override
            public void run() {
                // Request a post-render capture by semantic suffix.
                // The ArtifactService composes the filename and schedules
                // the capture after the full frame renders.
                artifacts.requestCapture("normal");
            }
        });

        scheduler.schedule(1.0, "shutdown", new Runnable() {
            @Override
            public void run() {
                running = false;
            }
        });
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // Render scene content each frame.
        // Floor, HUD, and pause overlay are handled by the runtime.
        // GL state reset after this call is also handled by the runtime.
    }

    @Override
    public void dispose() {
        // Delete GL resources
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public boolean uses3DCamera() { return true; }

    @Override
    public boolean shouldShutdownOnComplete() { return true; }
}
```

**Step 2**: Register in `SceneRegistry.createDefault()`:

```java
reg.register(
    SceneDescriptor.builder("my-interactive")
        .description("My interactive 3D scene")
        .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
        .category(SceneDescriptor.Category.SCENE)
        .needsFbo(false)
        .needsDepthBuffer(true)
        .build(),
    new HarnessSceneFactory() {
        public HarnessSceneLifecycle create() { return new MyInteractiveScene(); }
    }
);
```

**Step 3**: Access shared systems through the context:

- **Camera**: `ctx.getCamera3D()` — `camera.moveCamera()`, `camera.setYaw()`, `camera.setPitch()`
- **Scheduler**: `ctx.getTaskScheduler()` — `scheduler.schedule(time, id, callback)`
- **Captures**: `ctx.getArtifactService()` — `artifacts.requestCapture("suffix")`
- **World settings**: `ctx.getWorldSettings()` — sky color, floor color, floor half-size (read-only)
- **Viewport**: `ctx.getViewport()` — live width/height, aspect ratio
- **Debug tools**: `new HarnessDebugTools(ctx.getCamera3D(), ctx.getArtifactService())`
- **Pause control**: `ctx.getRuntimeServices().setPaused(true)`
- **Validation**: `new ValidationChoreographer(...)` + `ValidationCaptureStep.builder(...)` for scripted capture sequences

### Output Conventions

- Each scene outputs to its own subdirectory: `harness-output/{sceneName}/`
- The base output directory is `config.getOutputDir()` (default: `gl-debug-harness/harness-output/`)
- Interactive scenes use `ArtifactService.requestCapture("suffix")` which produces `{outputName}-{suffix}.png`
- The output name defaults to the scene's mode ID, overridable via `--output-name=PREFIX`
- PNG filenames must be deterministic and stable
- Non-PNG artifacts (manifests, reports) use `.txt` extension

### GL State Rules for Scenes

- Scenes don't need to clean up GL state after rendering. The runtime calls `GlStateResetHelper.resetAfterScene()` automatically.
- Scenes start with safe defaults: depth test ON, blend OFF, depth writes ON (set by `RenderPassState.beginScenePass()`).
- Scenes may modify any GL state they need during `render()`. The post-scene reset handles cleanup.
- Scenes should NOT directly manage overlay or capture timing. Use `ArtifactService` for captures.

### Testing

- Non-GL code: JUnit 4 tests in `gl-debug-harness/src/test/java/`
- GL-touching code: Verify via `runHarness` command + artifact existence check
- Never mock GL calls in unit tests

---

## Console Arguments for Harness

### Frame rate, and the ceiling that is not the engine's

Every interactive scene runs behind `HarnessWindow.sync(120)`, which sleeps to hold the rate — so a readout
tops out at the cap, and that number is the limiter's, not the engine's.

```bash
# Uncapped, for a headroom measurement: how fast CAN this scene run?
./gradlew :gl-debug-harness:runHarness --args="--mode=forward-renderer" -Dcrystalgraphics.harness.fps=0

# Or pin it somewhere else
-Dcrystalgraphics.harness.fps=240
```

Capped stays the default: a UI scene otherwise spins a core redrawing a picture nobody asked for. Pair
an uncapped run with a frame readout — from CrystalGUI, **F7** in its `cgui-desktop` and
`-Dcrystalgui.frameprofile=true` for the per-phase breakdown.

### Timing a scene — `FrameBench`

`-Dcrystalgraphics.harness.bench=<frames>` times that many frames of any interactive scene, after
`bench.warmup` (default 300) discarded ones, then prints wall-time percentiles, allocation per frame and
mean CPU time, and stops. It also prints the class-file version the engine was loaded from.

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-desktop" \
    -Dcrystalgraphics.harness.bench=1500 -Dcrystalgraphics.harness.fps=0 -Dcrystalgraphics.harness.fixedDelta=0.0166667

# the same, against the Java 8 bytecode the shipped jars carry
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-desktop" -Pharness.bytecode=8 \
    -Dcrystalgraphics.harness.bench=1500 -Dcrystalgraphics.harness.fps=0 -Dcrystalgraphics.harness.fixedDelta=0.0166667
```

- Run uncapped (`fps=0`), or the cap's sleep is what gets measured.
- CPU time is a mean only: a thread's CPU clock ticks in 15.6 ms steps on Windows.
- One run is one sample. Alternate the builds being compared, several times each: run to run on one
  machine, wall time spreads by more than most differences worth finding.
- A scene that ends itself (`text-stress`, `cgui-text-stress` stop at 543 frames) needs a warm-up and
  window that fit, or it prints nothing.

### Output Organization

All scene outputs are organized into scene-specific subdirectories:

```
harness-output/
├── triangle/
│   └── triangle.png
├── atlas-dump/
│   └── atlas/
│       ├── bitmap-atlas-dump-24px.png
│       └── msdf-atlas-dump-32px.png
├── text-3d/
│   ├── text-3d-normal.png
│   ├── text-3d-paused.png
│   └── text-3d-topdown.png
├── camera-3d/
│   ├── camera-3d-front-view.png
│   ├── camera-3d-side-view.png
│   ├── camera-3d-top-down-view.png
│   └── camera-3d-diagonal-view.png
└── render-validation/
    ├── render-validation-normal.png
    ├── render-validation-paused.png
    └── render-validation-top-down.png
```

### Custom Output Name Prefix

Use `--output-name=PREFIX` to customize output filenames:

```bash
# Default: scene name used as prefix
./gradlew :gl-debug-harness:runHarness --args="--mode=text-3d"
# -> harness-output/text-3d/text-3d-normal.png
# -> harness-output/text-3d/text-3d-paused.png
# -> harness-output/text-3d/text-3d-topdown.png

# Custom prefix:
./gradlew :gl-debug-harness:runHarness --args="--mode=text-3d --output-name=test1"
# -> harness-output/text-3d/test1-normal.png
# -> harness-output/text-3d/test1-paused.png
# -> harness-output/text-3d/test1-topdown.png

# Camera validation with custom prefix:
./gradlew :gl-debug-harness:runHarness --args="--mode=camera-3d --output-name=gpu-test"
# -> harness-output/camera-3d/gpu-test-front-view.png
# -> harness-output/camera-3d/gpu-test-side-view.png
# -> harness-output/camera-3d/gpu-test-top-down-view.png
# -> harness-output/camera-3d/gpu-test-diagonal-view.png
```

### All Available Scenes

| Mode ID             | Type | Description |
|---------------------|---|---|
| `triangle-2d`       | MANAGED | Basic colored triangle |
| `atlas-dump`        | MANAGED | Glyph atlas texture dump |
| `text-2d`           | MANAGED | Full text rendering via CgTextRenderer |
| `text-3d`           | INTERACTIVE | Interactive 3D world-space text with camera |
| `camera-3d`         | INTERACTIVE | Camera validation: cube from 4 angles |
| `gl-state-dump`     | DIAGNOSTIC | GL state dump to text file |
| `capability-report` | DIAGNOSTIC | CgCapabilities + environment report |

### Common CLI Arguments

| Argument | Default | Description |
|---|---|---|
| `--mode=<id>` | (required) | Scene to run |
| `--output-dir=<dir>` | `gl-debug-harness/harness-output` | Base output directory |
| `--output-name=<prefix>` | scene mode ID | Custom filename prefix for outputs |
| `--font-path=<path>` | system font | Font file path |
| `--width=<n>` | 800 | Window/FBO width |
| `--height=<n>` | 600 | Window/FBO height |
| `--list` | — | List all available modes |
| `--help` | — | Show help with all options |

## System Properties

| Property | Default | Description |
|---|---|---|
| `harness.output.dir` | `gl-debug-harness/harness-output` | Output directory |
| `harness.font.path` | (system font) | Font file path |
| `harness.width` | `800` | Window/FBO width |
| `harness.height` | `600` | Window/FBO height |
| `harness.atlas.type` | `both` | Atlas type for atlas-dump |
| `harness.atlas.size` | `512` | Atlas texture size for atlas-dump |
| `harness.bitmap.px.size` | `24` | Bitmap atlas font size in px |
| `harness.msdf.px.size` | `32` | MSDF atlas font size in px (min: 32) |
| `harness.font.size.px` | varies | Shared font size (overrides both bitmap/msdf) |
| `harness.parity.prewarm` | `false` | Deterministic MSDF prewarm for parity testing |
| `harness.prewarm.bitmap` | `false` | Deterministic bitmap prewarm |
| `harness.dump.all.pages` | `false` | Dump all atlas pages (not just first) |
| `harness.atlas.page.size` | auto | Override per-page atlas texture dimension |

---

## Build Configuration

The harness is a standalone Gradle subproject (`gl-debug-harness/build.gradle.kts`) that depends on CrystalGraphics alone — `platform`, `core`, the JNI bindings and the `lwjgl3` tier-1 runtime, by `com.crystalgraphics:*` coordinates a host resolves through its composite build — and names no project on top of it. It resolves LWJGL 3 through its BOM, with the natives for the running OS and architecture chosen at configuration time (no extraction step), plus the JNI bindings for freetype-harfbuzz and msdfgen.

Key tasks:
- `compileJava` — Compile all harness code
- `test` — Run JUnit 4 tests (non-GL)
- `runHarness` — Launch the standalone harness

---

## Package Structure

```
com.crystalgraphics.harness/
├── FontDebugHarnessMain.java        # Entry point: CLI parsing, config resolution, runtime dispatch
├── HarnessSceneFactory.java         # Factory for deferred scene creation
├── HarnessSceneLifecycle.java       # Unified scene lifecycle v2 (init/render/onResize/dispose)
├── InteractiveSceneLifecycle.java   # Interactive extension (isRunning/uses3DCamera/shouldShutdown)
├── InteractiveSceneRunner.java      # Slim loop coordinator for interactive scenes
├── FrameInfo.java                   # Immutable per-frame timing snapshot
├── SceneRegistry.java               # Explicit scene/mode registration
├── HarnessExtension.java            # What another project adds: scenes, a reload step, shader namespaces
├── HarnessExtensions.java           # The extensions on the classpath (ServiceLoader, loaded once)
├── camera/
│   ├── Camera3D.java                # First-person 3D camera
│   ├── FloorRenderer.java          # Ground plane at Y=0
│   ├── HUDRenderer.java            # On-screen camera state text
│   └── PauseScreenRenderer.java    # Semi-transparent pause overlay
├── capture/
│   ├── ArtifactService.java         # Framework-owned capture API (suffix-based naming)
│   └── CaptureCallback.java         # Post-render capture scheduling interface
├── config/
│   ├── HarnessConfig.java           # Base config (outputDir, width, height, fontPath, outputName)
│   ├── AtlasDumpConfig.java         # Atlas-specific config
│   ├── TextSceneConfig.java         # Text-scene-specific config
│   ├── HarnessContext.java          # Composition root with typed sub-objects
│   ├── ViewportState.java           # Mutable viewport tracking (updated on resize)
│   ├── OutputSettings.java          # Immutable output dir + name prefix
│   ├── RuntimeServices.java         # Typed interactive runtime access
│   ├── SceneDescriptor.java         # Scene metadata (id, lifecycle mode, FBO/depth needs)
│   ├── WorldConfig.java             # Mutable singleton (legacy, read at startup only)
│   └── WorldSettings.java           # Immutable per-run world rendering config
├── debug/
│   └── HarnessDebugTools.java       # Programmatic camera + capture via ArtifactService
├── runtime/
│   ├── HarnessWindow.java           # GLFW window, context, input queues, pacing
│   ├── FrameClock.java              # Frame timing service
│   ├── InputPauseHandler.java       # Pause toggle + mouse grab management
│   ├── ResizeHandler.java           # Framebuffer resize detection + propagation
│   ├── OverlayPipeline.java         # Coordinates HUD and pause overlays
│   ├── WorldPassCoordinator.java    # Coordinates world contributors (floor, etc.)
│   └── OverlayCaptureOrchestrator.java  # Post-scene overlay order + capture callback
├── scene/
│   ├── TriangleScene2D.java           # Basic triangle (managed)
│   ├── AtlasDumpScene.java          # Atlas dump (managed)
│   ├── TextScene2D.java               # Full text rendering (managed)
│   ├── TextScene3D.java # Interactive world-text with camera (interactive)
│   └── CameraScene3D.java           # Camera validation: 4-angle cube captures
├── scheduler/
│   └── TaskScheduler.java           # Time-based callback scheduling
├── tool/
│   ├── GlStateDumper.java           # GL state dump diagnostic
│   ├── CapabilityReport.java        # Capabilities diagnostic
│   ├── FboInspector.java            # FBO color attachment inspector
│   ├── TextureInspector.java        # Texture capture inspector
│   ├── GlErrorChecker.java          # GL error drain utility
│   └── AtlasDumper.java             # Atlas texture + manifest dumper
├── validation/
│   ├── ValidationChoreographer.java # Scripted capture sequence coordinator
│   └── ValidationCaptureStep.java    # Value object for a single capture step
└── util/
    ├── ScreenshotUtil.java          # Low-level PNG capture primitives
    ├── GlStateResetHelper.java      # Canonical post-scene GL state reset
    ├── RenderPassState.java         # Per-pass GL state boundary helpers
    ├── ValidationCubeHelper.java    # Shared cube geometry for validation scenes
    ├── HarnessProjectionUtil.java   # Projection matrices + shared FOV/near/far constants
    ├── HarnessFontUtil.java         # Font path resolution
    ├── HarnessBitmapUtil.java       # FreeType bitmap normalization
    ├── HarnessShaderUtil.java       # Shader compilation
    ├── HarnessOutputDir.java        # Output directory creation
    └── HarnessDiagnostics.java      # Startup diagnostic logging
```
