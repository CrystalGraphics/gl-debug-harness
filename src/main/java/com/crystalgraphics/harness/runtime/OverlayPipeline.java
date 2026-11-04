package com.crystalgraphics.harness.runtime;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.harness.camera.HUDRenderer;
import com.crystalgraphics.harness.camera.PauseScreenRenderer;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.RenderPassState;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgPipelinePrecache;
import com.crystalgraphics.text.cache.CgFontRegistry;

import java.util.logging.Logger;

/**
 * Overlay pipeline coordinator that groups HUD and pause overlays under
 * a single owner with explicit render order, resize notification, and
 * lifecycle management.
 *
 * <p>This coordinator replaces the pattern where individual overlay renderers
 * (HUD, pause) were created and managed as loose fields scattered across
 * the runner and orchestrator. Instead, the overlay pipeline owns all
 * overlay renderers and presents a unified interface for:</p>
 * <ul>
 *   <li><b>Initialization</b> — {@link #init()} creates all overlay GL resources</li>
 *   <li><b>Rendering</b> — {@link #renderOverlays(HarnessContext, boolean, boolean)}
 *       renders pause and HUD in the correct order</li>
 *   <li><b>Resize</b> — {@link #onResize(int, int)} propagates resize events to
 *       all overlay renderers through one coordinator path</li>
 *   <li><b>Cleanup</b> — {@link #delete()} releases all overlay GL resources</li>
 * </ul>
 *
 * <h3>Render order contract</h3>
 * <p>Overlays are rendered in a fixed order after the scene pass and
 * post-scene GL state reset:</p>
 * <ol>
 *   <li>Pause overlay (if paused) — semi-transparent bar at bottom of screen</li>
 *   <li>{@link CgRenderStage#HUD} (if 3D camera active and not hidden with F1), fired as a game's host fires it: the
 *       HUD text at {@link #HUD_ORDER}, then anything else drawing over the HUD, the engine's subtitles among them</li>
 * </ol>
 * <p>This order ensures the HUD text renders on top of the pause overlay
 * when both are active simultaneously.</p>
 *
 * <h3>Resize-aware ownership</h3>
 * <p>Each overlay renderer has different resize behavior:</p>
 * <ul>
 *   <li>{@link HUDRenderer} — none: it sizes its text from the window each frame</li>
 *   <li>{@link PauseScreenRenderer} — no-op (quad vertices are recomputed
 *       from screen dimensions each frame)</li>
 * </ul>
 * <p>The pipeline routes resize notifications to all renderers uniformly,
 * so each renderer's resize behavior is encapsulated within its own class.
 * External callers (e.g. {@link ResizeHandler}) only need to notify the
 * pipeline, not each renderer individually.</p>
 *
 * <p><b>Thread safety</b>: Only used on the LWJGL render thread.</p>
 *
 * @see OverlayCaptureOrchestrator
 * @see RenderPassState
 */
public final class OverlayPipeline {

    private static final Logger LOGGER = Logger.getLogger(OverlayPipeline.class.getName());

    /** Where the HUD text draws on {@link CgRenderStage#HUD}: under the engine's subtitles. */
    public static final int HUD_ORDER = 0;

    private final HUDRenderer hudRenderer;
    private final PauseScreenRenderer pauseRenderer;
    private CgRenderStage.Registration hudStage;
    /** The context of the firing under way, for the HUD text's callback. */
    private HarnessContext firing;
    private final Runnable drawHud = this::renderHud;

    /**
     * Creates a new overlay pipeline owning the given HUD and pause renderers.
     *
     * <p>The pipeline takes ownership of both renderer instances — callers
     * should not init, render, resize, or delete them directly after passing
     * them to this constructor.</p>
     *
     * @param hudRenderer   the HUD overlay renderer (must not be null)
     * @param pauseRenderer the pause screen overlay renderer (must not be null)
     */
    public OverlayPipeline(HUDRenderer hudRenderer, PauseScreenRenderer pauseRenderer) {
        if (hudRenderer == null) {
            throw new IllegalArgumentException("hudRenderer must not be null");
        }
        if (pauseRenderer == null) {
            throw new IllegalArgumentException("pauseRenderer must not be null");
        }
        this.hudRenderer = hudRenderer;
        this.pauseRenderer = pauseRenderer;
    }

    /**
     * Initializes all overlay renderers' GL resources.
     *
     * <p>Must be called once with a valid GL context before any rendering.
     * Delegates to each renderer's init method in dependency order.</p>
     */
    public void init(HarnessContext ctx) {
        initHud(ctx);
        initPause();
    }

    /** The HUD text's half of {@link #init}: its fonts and renderer, and its place on the HUD stage. */
    public void initHud(HarnessContext ctx) {
        hudRenderer.init(ctx);
        hudStage = CgRenderStage.HUD.register(HUD_ORDER, frame -> frame.callback("harness.hud", drawHud));
    }

    /** The pause overlay's half of {@link #init}: its program, compiled now. */
    public void initPause() {
        pauseRenderer.init();
        LOGGER.info("[OverlayPipeline] All overlay renderers initialized.");
    }

    /**
     * Renders active overlays in the correct order.
     *
     * <p>This method should be called during the overlay pass, after
     * {@link RenderPassState#beginOverlayPass()}
     * has set the required GL state (depth OFF, blend ON with alpha).</p>
     *
     * <p><b>Render order</b>: pause overlay first, then HUD. This ensures
     * the HUD text is drawn on top of the pause overlay when both are
     * visible.</p>
     *
     * @param ctx          the harness context (provides screen dimensions, camera)
     * @param paused       whether the runner is currently paused
     * @param uses3DCamera whether the active scene uses the 3D camera system
     */
    public void renderOverlays(HarnessContext ctx, boolean paused, boolean uses3DCamera) {
        // Pause overlay renders first (below HUD text)
        if (paused) {
            pauseRenderer.render(ctx);
        }

        // The HUD stage second (on top of pause overlay if both active), into the window: the scene's target has been
        // blitted to it by now. The harness's GUI is unscaled.
        if (uses3DCamera) {
            firing = ctx;
            CgRenderStage.HUD.fireOverlay(ctx.getScreenWidth(), ctx.getScreenHeight(), 1f, 0);
        }
    }

    private void renderHud() {
        if (!hudHidden) hudRenderer.render(firing);
    }

    private boolean hudHidden;

    /** F1: the harness's HUD text hidden or shown again; the stage still fires, so the engine's subtitles stay. */
    public void toggleHud() {
        hudHidden = !hudHidden;
    }

    /**
     * Propagates a display resize event to all overlay renderers.
     *
     * <p>This is the single resize notification path for overlays.
     * External callers (e.g. {@link ResizeHandler}) should call this
     * method rather than notifying individual renderers directly.</p>
     *
     * <p>Each renderer handles resize according to its own needs:</p>
     * <ul>
     *   <li>HUD — nothing: it sizes its text from the window each frame</li>
     *   <li>Pause — no-op (recomputes quad geometry each frame anyway)</li>
     * </ul>
     *
     * @param newWidth  new viewport width in pixels
     * @param newHeight new viewport height in pixels
     */
    public void onResize(int newWidth, int newHeight) {
        pauseRenderer.onDisplayResize(newWidth, newHeight);
    }

    /**
     * Releases all GL resources held by overlay renderers.
     *
     * <p>Called during runner shutdown. After this call, no overlay
     * methods should be invoked.</p>
     */
    /** {@link #warmHud}'s target: drawn into, never read. */
    private CgFrameBuffer hudWarmTarget;
    private static final int HUD_WARM_FRAMES = 240;
    private int hudWarmQuiet, hudWarmFrames;
    private long hudWarmFrame = -1;

    /**
     * Draws the HUD text into a scratch target behind the loading screen, which draws no overlay: its font at the
     * window's size and its glyphs are made before it shows, once the engine's precache has built: drawn sooner, its
     * text pipelines build at the draw. Once a frame, since glyphs land at a frame's tick; true
     * once three frames' draws in a row left no glyph pending, or after {@link #HUD_WARM_FRAMES}, should glyph work for
     * others never go quiet.
     */
    public boolean warmHud(HarnessContext ctx) {
        long frame = CgFrameClock.frameIndex();
        if (frame == hudWarmFrame) return false;
        hudWarmFrame = frame;
        if (CgPipelinePrecache.remaining() > 0) return false;
        if (++hudWarmFrames > HUD_WARM_FRAMES) return true;
        if (hudWarmTarget == null) {
            hudWarmTarget = CgFrameBuffer.create("harness_hud_warm", 16, 16,
                    CgFrameBufferFormat.builder("harness_hud_warm").color(0, CgTextureType.RGBA8).build());
        }
        try (CgGlScope ignored = CgGlState.saveAll()) {
            CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, hudWarmTarget.getId());
            CgGL.glViewport(0, 0, 16, 16);
            hudRenderer.render(ctx);
        }
        hudWarmQuiet = CgFontRegistry.get().getPendingAsyncGlyphCount() == 0 ? hudWarmQuiet + 1 : 0;
        return hudWarmQuiet >= 3;
    }

    public void delete() {
        if (hudStage != null) hudStage.close();
        if (hudWarmTarget != null) {
            hudWarmTarget.delete();
            hudWarmTarget = null;
        }
        hudRenderer.delete();
        pauseRenderer.delete();
        LOGGER.info("[OverlayPipeline] All overlay renderers deleted.");
    }

    /**
     * Returns whether any overlay would render given the current state.
     *
     * <p>Used by the orchestrator to decide whether to set up and tear
     * down overlay GL state (depth OFF, blend ON). If no overlays are
     * active, the state boundary can be skipped entirely.</p>
     *
     * @param paused       whether the runner is currently paused
     * @param uses3DCamera whether the active scene uses the 3D camera system
     * @return true if at least one overlay would render
     */
    public boolean hasActiveOverlays(boolean paused, boolean uses3DCamera) {
        return paused || uses3DCamera;
    }

    /**
     * Returns the HUD renderer owned by this pipeline.
     *
     * <p><b>Internal use only</b> — exposed for the orchestrator and
     * resize handler during the migration period. External code should
     * interact with the pipeline, not individual renderers.</p>
     *
     * @return the HUD renderer
     */
    HUDRenderer getHudRenderer() {
        return hudRenderer;
    }

    /**
     * Returns the pause renderer owned by this pipeline.
     *
     * <p><b>Internal use only</b> — exposed for the orchestrator and
     * resize handler during the migration period. External code should
     * interact with the pipeline, not individual renderers.</p>
     *
     * @return the pause screen renderer
     */
    PauseScreenRenderer getPauseRenderer() {
        return pauseRenderer;
    }

    @Override
    public String toString() {
        return "OverlayPipeline[hud=" + hudRenderer + ", pause=" + pauseRenderer + "]";
    }
}
