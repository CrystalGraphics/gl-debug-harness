package io.github.somehussar.crystalgraphics.harness;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.input.SystemInput;
import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.camera.FloorRenderer;
import io.github.somehussar.crystalgraphics.harness.camera.HUDRenderer;
import io.github.somehussar.crystalgraphics.harness.camera.PauseScreenRenderer;
import io.github.somehussar.crystalgraphics.harness.capture.ArtifactService;
import io.github.somehussar.crystalgraphics.harness.capture.CaptureCallback;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.config.OutputSettings;
import io.github.somehussar.crystalgraphics.harness.config.RuntimeServices;
import io.github.somehussar.crystalgraphics.harness.config.ViewportState;
import io.github.somehussar.crystalgraphics.harness.config.WorldSettings;
import io.github.somehussar.crystalgraphics.harness.debug.HarnessDebugTools;
import io.github.somehussar.crystalgraphics.harness.runtime.FrameClock;
import io.github.somehussar.crystalgraphics.harness.runtime.InputPauseHandler;
import io.github.somehussar.crystalgraphics.harness.runtime.OverlayCaptureOrchestrator;
import io.github.somehussar.crystalgraphics.harness.runtime.OverlayPipeline;
import io.github.somehussar.crystalgraphics.harness.runtime.ResizeHandler;
import io.github.somehussar.crystalgraphics.harness.runtime.WorldPassCoordinator;
import io.github.somehussar.crystalgraphics.harness.scheduler.TaskScheduler;
import io.github.somehussar.crystalgraphics.harness.util.HarnessProjectionUtil;
import io.github.somehussar.crystalgraphics.harness.util.RenderPassState;
import com.crystalgraphics.mc.CgAssetReloader;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

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

    private static final int TARGET_FPS = 60;
    /**
     * How many nanoseconds are represented by a millisecond.
     */
    private static final long NANOS_IN_MILLIS = 1_000_000L ;
    private static final int NORMALIZE_TOP_LEFT_ORIGIN = -1;
    private static final float MOUSE_SCROLL_NORMALIZE = 1/120f * NORMALIZE_TOP_LEFT_ORIGIN;

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

    private List<SystemInput.Mouse> mouseListeners = new ArrayList<>();
    private List<SystemInput.Keyboard> keyboardListeners = new ArrayList<>();

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
     * returning false, or the Display close is requested.</p>
     */
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
        registerInputHandler(inputPauseHandler);
        registerInputHandler(scene);
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

        GL11.glViewport(0, 0, currentWidth, currentHeight);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);

        init();

        LOGGER.info("[InteractiveSceneRunner] Entering render loop for: "
                + scene.getClass().getSimpleName());

        // ── Render loop ──
        // Frame ordering is explicitly documented and must be preserved exactly.
        // Each step delegates to the responsible service collaborator.
        while (!Display.isCloseRequested() && scene.isRunning()) {

            // 1. Frame clock tick — compute delta, elapsed, frame number
            frameClock.tick();

            // 2. Handle window resize events — update context and notify renderers.
            //    Also notifies the scene via the unified lifecycle onResize hook.
            if (resizeHandler.checkAndPropagate()) {
                ViewportState vp = ctx.getViewport();
                CgGraphicsLifecycle.onResize(vp.getWidth(), vp.getHeight());
                scene.onResize(vp.getWidth(), vp.getHeight());
                ctx.setProjection(HarnessProjectionUtil.perspective(vp.getWidth(), vp.getHeight()));
            }

            if (Keyboard.isKeyDown(Keyboard.KEY_R)) CgPlatform.reload().onReload();
            if (Keyboard.isKeyDown(Keyboard.KEY_I)) init();
            
            // 3. Check for pause toggle BEFORE camera input processing.
            //    Uses Keyboard event queue to detect key-down events (not held state),
            //    preventing rapid toggling from a single key press.
//            inputPauseHandler.pollPauseToggle();
            pollInput();

            // 4. Camera update (skipped when paused)
            if (scene.uses3DCamera() && !inputPauseHandler.isPaused()) {
                camera.update(frameClock.getDeltaTime());
            }

            // 5. Fire any scheduled tasks that are due (even when paused)
            scheduler.tick(frameClock.getElapsedTime());

            // ── Frame render pipeline ──
            // Pass ordering: world → scene → post-scene reset → overlays → capture
            // Each pass uses RenderPassState to declare its GL state requirements.

            // 6. Pre-render: set world pass state (depth ON, blend OFF, depth writes ON)
            RenderPassState.beginWorldPass();

            worldPassCoordinator.executeWorldPass(ctx, camera, scene.uses3DCamera());
            
            // Text context
            ctx.getTextContext().update(ctx);

            // 9. Scene pass: set baseline state, then let the scene render freely
            RenderPassState.beginScenePass();
            scene.render(ctx, new FrameInfo(frameClock.getDeltaTime(),
                    frameClock.getElapsedTime(), frameClock.getFrameNumber()));

            // 10-13. Post-scene sequence: GL reset → pause overlay → HUD → capture callback
            //        Delegated to OverlayCaptureOrchestrator which owns this entire sequence.
            overlayCaptureOrchestrator.executePostSceneSequence(
                    inputPauseHandler.isPaused(), scene.uses3DCamera());

            // 13b. Whole frame (world + scene + HUD overlay) is now fully rendered — the
            //      canonical per-frame tick point (ticks CgFontRegistry's frame clock via
            //      the platform lifecycle service, not called directly by feature code
            //      like HUDRenderer/CgUiPaintContext).
            CgPlatform.lifecycle().onFrameRendered();

            // 14. Buffer swap + frame sync
            Display.update();
            Display.sync(TARGET_FPS);
        }

        LOGGER.info("[InteractiveSceneRunner] Render loop exited after "
                + frameClock.getFrameNumber() + " frames");

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
        if (!mouseListeners.isEmpty()) {
            while (Mouse.next()) {
                int buttonId = Mouse.getEventButton();
                long millisTimestamp = buttonId == -1 ? -1 : Mouse.getEventNanoseconds() / NANOS_IN_MILLIS;
                SystemInput.Mouse.Event event = new SystemInput.Mouse.Event(
                        Mouse.getEventX(), ctx.getScreenHeight()-Mouse.getEventY(),
                        Mouse.getEventDX(), Mouse.getEventDY() * NORMALIZE_TOP_LEFT_ORIGIN,
                        buttonId, Mouse.getEventButtonState(),
                        Mouse.getEventDWheel() * MOUSE_SCROLL_NORMALIZE, millisTimestamp
                );
                for (SystemInput.Mouse listener : mouseListeners) {
                    if (!listener.consumeMouseEvent(event)) break;
                }
            }
        }

        if (!keyboardListeners.isEmpty()) {
            while (Keyboard.next()) {
                SystemInput.Keyboard.Event event = new SystemInput.Keyboard.Event(
                        Keyboard.getEventCharacter(), Keyboard.getEventKey(),
                        Keyboard.getEventKeyState(), Keyboard.isRepeatEvent(),
                        Keyboard.getEventNanoseconds() / NANOS_IN_MILLIS
                );
                for (SystemInput.Keyboard listener : keyboardListeners) {
                    if (!listener.consumeKeyboardEvent(event)) break;
                }
            }
        }
    }

    private void registerInputHandler(Object objectToProcess) {
        if (objectToProcess instanceof SystemInput.Mouse mouseHandler)
            this.mouseListeners.add(mouseHandler);

        if (objectToProcess instanceof SystemInput.Keyboard keyboardHandler)
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
