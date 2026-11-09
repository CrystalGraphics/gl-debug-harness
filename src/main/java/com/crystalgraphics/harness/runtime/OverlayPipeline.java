package com.crystalgraphics.harness.runtime;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.harness.camera.HUDRenderer;
import com.crystalgraphics.harness.config.HarnessContext;
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
     * Fires the HUD stage over the frame's target when the scene has a 3D camera. Its pipelines declare their own state.
     * The harness's GUI is unscaled.
     */
    public void renderOverlays(HarnessContext ctx, boolean uses3DCamera) {
        if (!uses3DCamera) return;
        firing = ctx;
        CgRenderStage.HUD.fireOverlay(ctx.getScreenWidth(), ctx.getScreenHeight(), 1f, ctx.getTargetFramebuffer());
    }

    private void renderHud() {
        if (!hudHidden) hudRenderer.render(firing);
    }

    private boolean hudHidden;

    /** F1: the harness's HUD text hidden or shown again; the stage still fires, so the engine's subtitles stay. */
    public void toggleHud() {
        hudHidden = !hudHidden;
    }


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
        try (CgFrameBuffer.Drawing ignored = hudWarmTarget.drawInto()) {
            hudRenderer.render(ctx);
        }
        hudWarmQuiet = CgFontRegistry.get().getPendingAsyncGlyphCount() == 0 ? hudWarmQuiet + 1 : 0;
        return hudWarmQuiet >= 3;
    }

    /** Releases the HUD's stage, its warm target and its renderer: at the runner's shutdown. */
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
