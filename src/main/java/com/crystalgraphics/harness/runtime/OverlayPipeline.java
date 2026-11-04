package com.crystalgraphics.harness.runtime;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.harness.camera.HUDRenderer;
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
 * The harness's HUD: its text on {@link CgRenderStage#HUD}, fired as a game's host fires it, after the scene pass
 * and the post-scene GL reset, when the scene has a 3D camera. The text draws at {@link #HUD_ORDER} unless F1 hid it,
 * then anything else drawing over the HUD: the engine's subtitles, CrystalGUI's pinned windows. It sizes itself
 * from the window each frame.
 */
public final class OverlayPipeline {

    private static final Logger LOGGER = Logger.getLogger(OverlayPipeline.class.getName());

    /** Where the HUD text draws on {@link CgRenderStage#HUD}: under the engine's subtitles. */
    public static final int HUD_ORDER = 0;

    private final HUDRenderer hudRenderer;
    private CgRenderStage.Registration hudStage;
    /** The context of the firing under way, for the HUD text's callback. */
    private HarnessContext firing;
    private final Runnable drawHud = this::renderHud;

    /**
     * Creates the overlay pipeline owning the given HUD renderer.
     *
     * <p>The pipeline takes ownership of both renderer instances — callers
     * should not init, render, resize, or delete them directly after passing
     * them to this constructor.</p>
     *
     * @param hudRenderer   the HUD overlay renderer (must not be null)
     */
    public OverlayPipeline(HUDRenderer hudRenderer) {
        if (hudRenderer == null) {
            throw new IllegalArgumentException("hudRenderer must not be null");
        }
        this.hudRenderer = hudRenderer;
    }

    /**
     * Initializes all overlay renderers' GL resources.
     *
     * <p>Must be called once with a valid GL context before any rendering.
     * Delegates to each renderer's init method in dependency order.</p>
     */
    public void init(HarnessContext ctx) {
        initHud(ctx);
    }

    /** The HUD text's half of {@link #init}: its fonts and renderer, and its place on the HUD stage. */
    public void initHud(HarnessContext ctx) {
        hudRenderer.init(ctx);
        hudStage = CgRenderStage.HUD.register(HUD_ORDER, frame -> frame.callback("harness.hud", drawHud));
    }

    /**
     * Renders active overlays in the correct order.
     *
     * <p>This method should be called during the overlay pass, after
     * {@link RenderPassState#beginOverlayPass()}
     * has set the required GL state (depth OFF, blend ON with alpha).</p>
     *
     * @param ctx          the harness context (provides screen dimensions, camera)
     * @param uses3DCamera whether the active scene uses the 3D camera system
     */
    public void renderOverlays(HarnessContext ctx, boolean uses3DCamera) {
        // Into the window: the scene's target has been
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
        LOGGER.info("[OverlayPipeline] All overlay renderers deleted.");
    }

    /**
     * Returns whether any overlay would render given the current state.
     *
     * <p>Used by the orchestrator to decide whether to set up and tear
     * down overlay GL state (depth OFF, blend ON). If no overlays are
     * active, the state boundary can be skipped entirely.</p>
     *
     * @param uses3DCamera whether the active scene uses the 3D camera system
     * @return true if at least one overlay would render
     */
    public boolean hasActiveOverlays(boolean uses3DCamera) {
        return uses3DCamera;
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


    @Override
    public String toString() {
        return "OverlayPipeline[hud=" + hudRenderer + "]";
    }
}
