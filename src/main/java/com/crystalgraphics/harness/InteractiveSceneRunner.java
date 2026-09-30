package com.crystalgraphics.harness;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.gl.CgGlRecording;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.harness.trace.TraceDump;
import com.crystalgraphics.platform.input.CgSystemInput;
import java.io.File;

import com.crystalgraphics.harness.camera.Camera3D;
import com.crystalgraphics.harness.camera.FloorRenderer;
import com.crystalgraphics.harness.camera.HUDRenderer;
import com.crystalgraphics.harness.camera.PauseScreenRenderer;
import com.crystalgraphics.harness.capture.ArtifactService;
import com.crystalgraphics.harness.capture.CaptureCallback;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.config.OutputSettings;
import com.crystalgraphics.harness.config.RuntimeServices;
import com.crystalgraphics.harness.config.ViewportState;
import com.crystalgraphics.harness.config.WorldSettings;
import com.crystalgraphics.harness.debug.HarnessDebugTools;
import com.crystalgraphics.harness.runtime.FrameClock;
import com.crystalgraphics.harness.runtime.InputPauseHandler;
import com.crystalgraphics.harness.runtime.OverlayCaptureOrchestrator;
import com.crystalgraphics.harness.runtime.OverlayPipeline;
import com.crystalgraphics.harness.runtime.ResizeHandler;
import com.crystalgraphics.harness.runtime.WorldPassCoordinator;
import com.crystalgraphics.harness.scheduler.TaskScheduler;
import com.crystalgraphics.harness.util.HarnessProjectionUtil;
import com.crystalgraphics.harness.util.RenderPassState;
import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.harness.runtime.HarnessWindow;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Drives the render loop for interactive scene implementations.
 *
 * <p>Drives scenes through the unified {@link InteractiveSceneLifecycle}
 * contract.</p>
 *
 * <p>This runner composes focused runtime service collaborators, each owning
 * a single concern:</p>
 * <ul>
 *   <li>{@link FrameClock} — frame timing (delta, elapsed, frame number)</li>
 *   <li>{@link InputPauseHandler} — keyboard pause toggle and mouse grab</li>
 *   <li>{@link ResizeHandler} — display resize detection and propagation</li>
 *   <li>{@link OverlayCaptureOrchestrator} — overlay rendering and capture callbacks</li>
 * </ul>
 *
 * <p>The runner itself remains a slim sequencer that calls these services
 * in the correct order each frame. It wraps the scene's lifecycle methods
 * (init → render → dispose) and provides the debug tools for LLM-driven
 * validation.</p>
 *
 * <p><b>Frame ordering contract</b> (preserved exactly):</p>
 * <ol>
 *   <li>Frame clock tick (timing)</li>
 *   <li>Resize check and propagation</li>
 *   <li>Input: poll keyboard for pause toggle</li>
 *   <li>Camera update (skipped when paused)</li>
 *   <li>Task scheduler tick</li>
 *   <li>World pass GL state setup (depth ON, blend OFF)</li>
 *   <li>Clear framebuffer (sky color from resolved {@link WorldSettings})</li>
 *   <li>Floor rendering (if uses3DCamera)</li>
 *   <li>Scene pass GL state setup</li>
 *   <li>Scene content: {@code scene.render()}</li>
 *   <li>Post-scene GL state reset → pause overlay → HUD → capture callback</li>
 *   <li>Buffer swap + frame sync</li>
 * </ol>
 *
 * <p>Supports a pause mode toggled by ESCAPE or T keys. When paused:
 * the mouse cursor is unlocked, camera updates are disabled, and a
 * semi-transparent overlay is rendered at the bottom of the screen.
 * Pressing ESCAPE or T again resumes normal operation.</p>
 *
 * <p>For non-interactive scenes, this class is not used;
 * the existing single-shot execution path in FontDebugHarnessMain handles them.</p>
 */
public final class InteractiveSceneRunner implements CaptureCallback {

    private static final Logger LOGGER = Logger.getLogger(InteractiveSceneRunner.class.getName());

    /**
     * The frame limiter, and {@code -Dcrystalgraphics.harness.fps=0} takes it off.
     *
     * <p>{@link Display#sync} sleeps to hold a rate, and it holds it from BELOW — its sleep granularity
     * means a 120 target settles at about 117, which reads as a ceiling the engine imposed. It is not:
     * uncapped, a scene runs as fast as it can, which is what a headroom measurement wants. Capped is
     * the right default for a human at the keyboard, since a UI scene otherwise spins a core to draw a
     * picture nobody asked to be redrawn.</p>
     */
    private static final int TARGET_FPS = Integer.getInteger("crystalgraphics.harness.fps", 120);

    /** Frames discarded before {@code -Dcrystalgraphics.harness.profile} starts counting: the first
     * few carry every lazy allocation and every shader variant's first compile, which is a scene's
     * startup cost rather than its frame cost. {@code .profile.warmup=<frames>} for a scene that keeps
     * loading longer -- the desktop generates glyphs and starts the language stack for seconds. */
    private static final int PROFILE_WARMUP_FRAMES = Integer.getInteger("crystalgraphics.harness.profile.warmup", 30);

    /** The swap blocks when the GPU is behind, and the sync sleeps to hold the rate: waits, not work. */
    private static final int SWAP = CgTrace.waitName("frame.swap");
    private static final int SYNC = CgTrace.waitName("frame.sync");

    /** Zones a profiled frame may hold, per thread: the ring is sized so no profiled frame loses its own. */
    private static final int PROFILE_ZONES_PER_FRAME =
            Integer.getInteger("crystalgraphics.harness.profile.zonesPerFrame", 16_384);
    /** {@code -Dcrystalgraphics.harness.captureAt=<frame>}: photograph that frame and stop -- an unattended
     * picture of any interactive scene. Pair with {@code fixedDelta} for one that repeats run to run. */
    private static final int CAPTURE_AT = Integer.getInteger("crystalgraphics.harness.captureAt", 0);
    /** {@code -Dcrystalgraphics.harness.record=<frame>}: from that frame on, the scene pass is recorded and replayed
     * instead of drawn, so a capture shows the replay. Logs what a recorded frame allocates beside a direct one. */
    private static final int RECORD_FROM = Integer.getInteger("crystalgraphics.harness.record", 0);
    private static final int ALLOCATION_SAMPLE = 30;
    private final CgGlRecording recording = new CgGlRecording();
    private long directAllocated, recordedAllocated;
    private int directFrames, recordedFrames;
    /**
     * The scene driven by this runner, accessed through the unified lifecycle contract.
     */
    private final InteractiveSceneLifecycle scene;
    private final HarnessContext ctx;

    // ── Core subsystems (domain objects, not runtime lifecycle concerns) ──
    private Camera3D camera;
    private TaskScheduler scheduler;
    private HarnessDebugTools debugTools;
    private ArtifactService artifactService;

    // Immutable world settings resolved once from context at run start.
    // Used for sky clear color each frame — avoids calling mutable singleton mid-render.
    private WorldSettings worldSettings;

    // ── Runtime service collaborators ──
    // Each owns a single runtime concern, composed here for sequencing.
    // Extracted from the monolithic runner to isolate input handling,
    // frame timing, resize propagation, and overlay/capture orchestration.
    private FrameClock frameClock;
    private InputPauseHandler inputPauseHandler;
    private ResizeHandler resizeHandler;
    private OverlayCaptureOrchestrator overlayCaptureOrchestrator;
    private OverlayPipeline overlayPipeline;
    private WorldPassCoordinator worldPassCoordinator;

    private List<CgSystemInput.Mouse> mouseListeners = new ArrayList<>();
    private List<CgSystemInput.Keyboard> keyboardListeners = new ArrayList<>();

    public InteractiveSceneRunner(InteractiveSceneLifecycle scene, HarnessContext ctx) {
        this.scene = scene;
        this.ctx = ctx;
    }

    /**
     * Runs the full interactive scene lifecycle: init → render loop → cleanup.
     *
     * <p>Creates all runtime service collaborators, populates the context
     * with references, then enters the render loop. The loop runs until the
     * scene signals completion via {@link InteractiveSceneLifecycle#isRunning()}
     * returning false, or the window is closed.</p>
     */
    private void renderScene(HarnessContext ctx, FrameInfo info) {
        long frame = info.getFrameNumber();
        boolean sample = RECORD_FROM > 0 && frame >= RECORD_FROM - ALLOCATION_SAMPLE;
        long before = sample ? allocatedBytes() : 0;
        if (RECORD_FROM <= 0 || frame < RECORD_FROM) {
            scene.render(ctx, info);
            if (sample) { directAllocated += allocatedBytes() - before; directFrames++; }
            return;
        }
        recording.begin();
        try {
            scene.render(ctx, info);
        } finally {
            recording.end();
        }
        // The first recorded frame builds the recording's tape and scratch; a frame after it is what recording costs.
        if (frame > RECORD_FROM) {
            recordedAllocated += allocatedBytes() - before;
            recordedFrames++;
        }
        recording.replay();
    }

    private static long allocatedBytes() {
        return ((ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    public void run() {
        int currentWidth = ctx.getScreenWidth();
        int currentHeight = ctx.getScreenHeight();
        
        // ── Create core subsystems ──
        camera = new Camera3D();
        HUDRenderer hudRenderer = new HUDRenderer();
        PauseScreenRenderer pauseRenderer = new PauseScreenRenderer();
        overlayPipeline = new OverlayPipeline(hudRenderer, pauseRenderer);
        scheduler = new TaskScheduler();

        // Resolve immutable world settings from context (frozen at startup).
        // All rendering reads go through this field, not WorldConfig.get().
        worldSettings = ctx.getWorldSettings();
        if (worldSettings == null) {
            throw new IllegalStateException(
                    "WorldSettings must be set on HarnessContext before running an interactive scene");
        }
        worldPassCoordinator = new WorldPassCoordinator(new FloorRenderer(), worldSettings);

        // ── Create runtime service collaborators ──
        frameClock = new FrameClock();
        inputPauseHandler = new InputPauseHandler(scene.uses3DCamera());
        registerInputHandler(scene);
        registerInputHandler(inputPauseHandler);
        resizeHandler = new ResizeHandler(ctx, worldPassCoordinator, overlayPipeline);
        overlayCaptureOrchestrator = new OverlayCaptureOrchestrator(ctx, overlayPipeline);

        // Populate context with shared subsystem references so scenes
        // can access them via ctx.getCamera3D(), ctx.getRuntimeServices(), etc.\
        ctx.setProjection(HarnessProjectionUtil.perspective(currentWidth, currentHeight));
        ctx.setCamera3D(camera);
        ctx.setTaskScheduler(scheduler);
        ctx.setRuntimeServices(new RuntimeServices(this));

        // Create the framework-owned artifact service for centralized capture.
        // Uses the runner as the CaptureCallback so captures are scheduled
        // as post-render callbacks in the frame pipeline.
        OutputSettings outputSettings = ctx.getOutputSettings();
        ViewportState viewport = ctx.getViewport();
        artifactService = new ArtifactService(outputSettings, viewport, this);
        ctx.setArtifactService(artifactService);


        debugTools = new HarnessDebugTools(camera, artifactService);

        CgGL.glViewport(0, 0, currentWidth, currentHeight);
        CgGL.glEnable(CgGL.GL_DEPTH_TEST);
        CgGL.glDepthFunc(CgGL.GL_LEQUAL);

        init();

        LOGGER.info("[InteractiveSceneRunner] Entering render loop for: "
                + scene.getClass().getSimpleName());

        // ── Render loop ──
        // Frame ordering is explicitly documented and must be preserved exactly.
        // Each step delegates to the responsible service collaborator.
        // HarnessDeadline is tested alongside the scene's own exit conditions so a capped run shuts down
        // through the ordinary path, with GL teardown and any artifact writes intact. The watchdog inside
        // HarnessDeadline is the backstop for a scene that never gets back here at all.
        // -Dcrystalgraphics.harness.profile=<frames>: profile ANY interactive scene over that many
        // frames, dump, and stop -- the measurement a scene with its own profiling flag makes, without
        // every scene having to grow one. Unattended, so two builds can be compared back to back, which
        // is the only way these numbers mean anything: run to run on one machine they spread further
        // than most differences worth finding.
        // CrystalGraphics' own channels always; anything else -- a project on top of it, the GPU -- through
        // -Dcrystalgraphics.trace.channels, which the engine applied at launch.
        int profileFrames = Integer.getInteger("crystalgraphics.harness.profile", 0);
        if (profileFrames > 0) {
            // EVERY PROFILED FRAME KEEPS ITS ZONES. At the default ceiling a dense channel wraps a thread's
            // arena within a dozen frames, and the report's older frames came back with nothing in them.
            // After the scene's init, so a viewer's saved ring and stop-after-hitch cannot cut a profile short.
            CgTrace.configure(0, PROFILE_WARMUP_FRAMES + profileFrames + 8, PROFILE_ZONES_PER_FRAME);
            CgTrace.stopAfterHitch(0L, 0);
            CgTrace.enable("crystalgraphics");
        }
        long profileFrom = 0L;
        // THE HARNESS IS THE HOST, so it brackets each loop as a trace frame -- unless the scene frames
        // itself (a UIDocument does), which it notices the first time the index moves under a render.
        boolean sceneFrames = false;

        while (!HarnessWindow.shouldClose() && scene.isRunning() && !HarnessDeadline.expired()) {
            if (profileFrames > 0) {
                if (frameClock.getFrameNumber() == PROFILE_WARMUP_FRAMES) profileFrom = CgTrace.currentFrameIndex() + 1;
                if (frameClock.getFrameNumber() == PROFILE_WARMUP_FRAMES + profileFrames) {
                    File report = TraceDump.profile(new File(ctx.getOutputDir()),
                            "harness-" + profileFrames + "f", profileFrom);
                    LOGGER.info("[InteractiveSceneRunner] profile report=" + report);
                    break;
                }
            }

            // 1. Frame clock tick — compute delta, elapsed, frame number
            frameClock.tick();
            if (!sceneFrames) CgTrace.frameBegin();

            // 2. Handle window resize events — update context and notify renderers.
            //    Also notifies the scene via the unified lifecycle onResize hook.
            if (resizeHandler.checkAndPropagate()) {
                ViewportState vp = ctx.getViewport();
                CgGraphicsLifecycle.onResize(vp.getWidth(), vp.getHeight());
                scene.onResize(vp.getWidth(), vp.getHeight());
                ctx.setProjection(HarnessProjectionUtil.perspective(vp.getWidth(), vp.getHeight()));
            }

            // 3. Input, before the camera reads it. The OS message pump is here, and it can block.
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, "frame.input")) {
                pollInput();
            }

            // 4. Camera update (skipped when paused)
            if (scene.uses3DCamera() && !inputPauseHandler.isPaused()) {
                camera.update(frameClock.getDeltaTime());
            }

            // 5. Fire any scheduled tasks that are due (even when paused)
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, "frame.scheduler")) {
                scheduler.tick(frameClock.getElapsedTime());
            }

            // ── Frame render pipeline ──
            // Pass ordering: world → scene → post-scene reset → overlays → capture
            // Each pass uses RenderPassState to declare its GL state requirements.

            // Every step below carries a trace zone. Before they did, a profiling scene
            // could only see its own render() call -- typically a third of the frame -- and the
            // rest showed up as time belonging to no scope at all. On text-3d that unattributed
            // remainder was 60-110 ms on individual warmup frames, i.e. larger than everything
            // that WAS measured, which makes "the draw is fast" an unsupportable claim no matter
            // how good the draw numbers look. The scopes are no-ops unless a scene enabled the
            // profiler.
            long frameStartNanos = System.nanoTime();

            // 6. Pre-render: set world pass state (depth ON, blend OFF, depth writes ON)
            RenderPassState.beginWorldPass();

            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, "frame.worldPass")) {
                worldPassCoordinator.executeWorldPass(ctx, camera, scene.uses3DCamera());
            }

            // Text context
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, "frame.textContext")) {
                ctx.getTextContext().update(ctx);
            }

            // 9. Scene pass: set baseline state, then let the scene render freely
            RenderPassState.beginScenePass();
            long framedAs = CgTrace.currentFrameIndex();
            FrameInfo info = new FrameInfo(frameClock.getDeltaTime(),
                    frameClock.getElapsedTime(), frameClock.getFrameNumber());
            if (sceneFrames) {
                // A SCENE THAT FRAMES ITSELF begins its trace frame inside this call, so a zone around it
                // would straddle every boundary: force-closed each frame, with the scene's work orphaned.
                renderScene(ctx, info);
            } else {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, "frame.scene")) {
                    renderScene(ctx, info);
                }
            }
            if (CgTrace.currentFrameIndex() != framedAs) sceneFrames = true;
            if (CAPTURE_AT > 0 && frameClock.getFrameNumber() == CAPTURE_AT) {
                artifactService.requestCapture("frame" + CAPTURE_AT);
            }

            // 10-13. Post-scene sequence: GL reset → pause overlay → HUD → capture callback
            //        Delegated to OverlayCaptureOrchestrator which owns this entire sequence.
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, "frame.overlay")) {
                overlayCaptureOrchestrator.executePostSceneSequence(
                        inputPauseHandler.isPaused(), scene.uses3DCamera());
            }

            // 13b. Whole frame (world + scene + HUD overlay) is now fully rendered — the
            //      canonical per-frame tick point (ticks CgFontRegistry's frame clock via
            //      the platform lifecycle service, not called directly by feature code
            //      like HUDRenderer/CgUiPaintContext).
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, "frame.onFrameRendered")) {
                CgPlatform.lifecycle().onFrameRendered();
            }
            // The CPU mark: everything above was the frame's own work, and the swap below is waiting.
            if (!sceneFrames) CgTrace.frameEnd();

            // 14. Buffer swap + frame sync.
            //
            // Separately scoped, and the distinction matters: the swap is where a GPU that has fallen
            // behind the CPU shows up as a block; sync() is a deliberate sleep to hold TARGET_FPS, and
            // being large there means the frame finished EARLY. Lumping them together would make an
            // idle frame look like a stalled one.
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, SWAP)) {
                HarnessWindow.swapBuffers();
            }
            if (TARGET_FPS > 0) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, SYNC)) {
                    HarnessWindow.sync(TARGET_FPS);
                }
            }

            // 15. Frame is genuinely over -- hand the scene its true wall duration. See
            //     InteractiveSceneLifecycle#onFrameEnd for why profiling from inside render()
            //     misattributes both the duration and every post-render step.
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, "frame.onFrameEnd")) {
                scene.onFrameEnd(ctx, new FrameInfo(
                        (float) ((System.nanoTime() - frameStartNanos) / 1_000_000_000.0),
                        frameClock.getElapsedTime(), frameClock.getFrameNumber()));
            }
            if (CAPTURE_AT > 0 && frameClock.getFrameNumber() >= CAPTURE_AT) break;
        }

        LOGGER.info("[InteractiveSceneRunner] Render loop exited after "
                + frameClock.getFrameNumber() + " frames");
        if (RECORD_FROM > 0 && recordedFrames > 0) {
            LOGGER.info("[InteractiveSceneRunner] record: " + recording.size() + " bytes a frame; allocated "
                    + (directFrames == 0 ? "?" : String.valueOf(directAllocated / directFrames)) + " bytes a direct frame, "
                    + (recordedAllocated / recordedFrames) + " a recorded one (" + directFrames + " and " + recordedFrames + " frames)");
        }

        // Ensure cursor is released on exit
        inputPauseHandler.releaseCursor();

        scene.dispose();
        worldPassCoordinator.delete();
        overlayCaptureOrchestrator.deletePipeline();
        ctx.getTextContext().delete();

        LOGGER.info("[InteractiveSceneRunner] Cleanup complete. shouldShutdown="
                + scene.shouldShutdownOnComplete());
    }

    private void pollInput() {
        HarnessWindow.pollEvents();
        for (CgSystemInput.Mouse.Event event : HarnessWindow.drainMouse()) {
            for (CgSystemInput.Mouse listener : mouseListeners) {
                if (!listener.consumeMouseEvent(event)) break;
            }
        }

        // Every scene's keyboard events pass the global binding below, whether or not the scene listens:
        // it has to work in every scene or it is a binding nobody can rely on.
        for (CgSystemInput.Keyboard.Event event : HarnessWindow.drainKeyboard()) {
            // Ctrl+R: re-read every asset from disk -- textures, shaders, materials, and whatever the
            // extensions and reload listeners keep.
            //
            // Handled HERE rather than in a scene, and consumed, for two reasons. It works in every
            // scene rather than only the ones that remembered to implement it; and `r` is an ordinary
            // character that a focused TextEditor would otherwise type into the document.
            //
            // Ignores auto-repeat, or holding the key re-reads the files once a frame.
            if (event.pressed() && !event.repeat() && event.key() == CgKeyCodes.KEY_R && isCtrlDown()) {
                reloadAssets();
            }
            for (CgSystemInput.Keyboard listener : keyboardListeners) {
                if (!listener.consumeKeyboardEvent(event)) break;
            }
        }
    }

    private static boolean isCtrlDown() {
        return CgPlatform.input().isKeyDown(CgKeyCodes.KEY_LCONTROL)
                || CgPlatform.input().isKeyDown(CgKeyCodes.KEY_RCONTROL);
    }

    /**
     * What F3+T does in a game: each extension's {@link HarnessExtension#beforeReload} step, then
     * CrystalGraphics' asset reload, which re-reads textures, shaders and materials and then calls every
     * {@code CgReloadListener} -- CrystalGUI's stylesheets, icons and sprites among them.
     *
     * <p>Where the files are read FROM is {@code CgIO}'s business: {@code runHarness} points
     * {@code crystalgraphics.resourceOverrideDirs} at the source trees, so an edited file is what the reload
     * sees rather than the copy {@code processResources} made at build time.</p>
     */
    private void reloadAssets() {
        try {
            for (HarnessExtension extension : HarnessExtensions.all()) {
                extension.beforeReload();
            }
            CgAssetReloader.reload();
            LOGGER.info("[InteractiveSceneRunner] Ctrl+R: assets reloaded");
        } catch (Throwable t) {
            // Never let a bad file take the harness down -- a half-written file mid-save is the normal
            // case for this key, not an exceptional one.
            LOGGER.log(java.util.logging.Level.SEVERE, "[InteractiveSceneRunner] Ctrl+R: reload failed", t);
        }
    }

    private void registerInputHandler
(Object objectToProcess) {
        if (objectToProcess instanceof CgSystemInput.Mouse mouseHandler)
            this.mouseListeners.add(mouseHandler);

        if (objectToProcess instanceof CgSystemInput.Keyboard keyboardHandler)
            this.keyboardListeners.add(keyboardHandler);
    }

    public void init() {
        scene.init(ctx);
        overlayPipeline.init(ctx);
        worldPassCoordinator.init();
    }
    /**
     * Returns the debug tools for LLM-driven camera control and screenshot capture.
     */
    public HarnessDebugTools getDebugTools() {
        return debugTools;
    }

    /**
     * Returns the active camera.
     */
    public Camera3D getCamera() {
        return camera;
    }

    /**
     * Returns the current viewport width.
     */
    public int getCurrentWidth() {
        return ctx.getScreenWidth();
    }

    /**
     * Returns the current viewport height.
     */
    public int getCurrentHeight() {
        return ctx.getScreenHeight();
    }

    /**
     * Returns the task scheduler.
     */
    public TaskScheduler getScheduler() {
        return scheduler;
    }

    /**
     * Returns the output name prefix for filenames.
     */
    public String getOutputName() {
        return ctx.getOutputName();
    }

    /**
     * Returns whether the scene wants the program to shut down after completion.
     */
    public boolean shouldShutdown() {
        return scene.shouldShutdownOnComplete();
    }

    /**
     * Returns whether the runner is currently in paused state.
     * Delegates to the {@link InputPauseHandler} service.
     */
    public boolean isPaused() {
        return inputPauseHandler.isPaused();
    }

    /**
     * Programmatically sets the paused state. Used by test scenes to
     * trigger pause without keyboard input. Handles mouse grab/ungrab
     * and drains accumulated mouse delta to prevent camera jumps.
     * Delegates to the {@link InputPauseHandler} service.
     */
    public void setPaused(boolean paused) {
        inputPauseHandler.setPaused(paused);
    }

    /**
     * Schedules a one-shot callback to fire after the CURRENT frame finishes
     * rendering (floor, HUD, pause overlay all drawn) but BEFORE the buffer
     * swap. This is the correct point to capture screenshots that show the
     * current frame's content.
     * Delegates to the {@link OverlayCaptureOrchestrator} service.
     */
    public void setPostRenderCallback(Runnable callback) {
        overlayCaptureOrchestrator.setPostRenderCallback(callback);
    }

    // ── CaptureCallback implementation ──

    @Override
    public void schedulePostRenderCapture(Runnable capture) {
        setPostRenderCallback(capture);
    }

    /**
     * Returns the framework-owned artifact service for this run.
     */
    public ArtifactService getArtifactService() {
        return artifactService;
    }

    /**
     * Returns the active harness context.
     *
     * @return the context used by this runner
     */
    public HarnessContext getContext() {
        return ctx;
    }

    // ── Service accessors for RuntimeServices and other consumers ──

    /**
     * Returns the frame clock service.
     *
     * <p>Provides access to delta time, elapsed time, and frame number
     * without requiring the caller to know about the runner's internals.</p>
     *
     * @return the frame clock, never null after {@link #run()} begins
     */
    public FrameClock getFrameClock() {
        return frameClock;
    }

    /**
     * Returns the input/pause handler service.
     *
     * <p>Provides access to pause state and programmatic pause control.</p>
     *
     * @return the input handler, never null after {@link #run()} begins
     */
    public InputPauseHandler getInputPauseHandler() {
        return inputPauseHandler;
    }

    /**
     * Returns the resize handler service.
     *
     * <p>Provides access to the resize propagation mechanism.</p>
     *
     * @return the resize handler, never null after {@link #run()} begins
     */
    public ResizeHandler getResizeHandler() {
        return resizeHandler;
    }

    /**
     * Returns the overlay and capture orchestrator service.
     *
     * <p>Provides access to post-render callback scheduling and overlay
     * rendering sequence.</p>
     *
     * @return the orchestrator, never null after {@link #run()} begins
     */
    public OverlayCaptureOrchestrator getOverlayCaptureOrchestrator() {
        return overlayCaptureOrchestrator;
    }

}
