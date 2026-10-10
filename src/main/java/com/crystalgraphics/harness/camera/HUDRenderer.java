package com.crystalgraphics.harness.camera;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessFontUtil;
import com.crystalgraphics.api.text.CgTextLayout;

import com.crystalgraphics.harness.runtime.HarnessWindow;
import com.crystalgraphics.vfx.camera.CgCameraShake;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.bloom.CgBloom;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.render.world.CgWorldRenderer;

import java.util.Arrays;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Renders a minimal debug HUD in the top-left corner showing camera position
 * and rotation. Uses CgTextRenderer for text rendering via the same MSDF/bitmap
 * glyph atlas pipeline as all other CrystalGraphics text.
 *
 * <p>Text is rendered directly without a background box, using an orthographic
 * projection overlay after the 3D scene content. The CgTextRenderer handles all
 * glyph rasterization, atlas management, and shader setup internally.</p>
 *
 * <p>Text scales with the window's shorter side: 14 px at 600 pixels, in proportion above.</p>
 *
 * <p>HUD display format, the scene's own lines ({@code InteractiveSceneLifecycle.hudLine}) under it:</p>
 * <pre>
 * FPS: n
 * Pos: x.xx y.yy z.zz \ yaw° pitch°
 * Particles: n  (the median drawn a frame over the last 0.5 s)
 *
 * VFX sim [V]: cpu | gpu - Shake [C]: on, trauma t | off
 * HDR [G]: on | off | Glow [ ]: g
 * Bloom [L]: off | linear | blend | Intensity [- =]: b
 * </pre>
 */
public final class HUDRenderer {

    private static final Logger LOGGER = Logger.getLogger(HUDRenderer.class.getName());

    // Base reference values at 600px height (classic 800x600 resolution).
    // Font size and offset scale proportionally from these base values.
    private static final int BASE_FONT_SIZE_PX = 14;
    private static final float BASE_QUAD_OFFSET = 8.0f;
    private static final float BASE_RESOLUTION_HEIGHT = 600.0f;

    // White text with full opacity (packed RGBA: 0xRRGGBBAA)
    private static final int TEXT_COLOR = 0XFFFF0000;
    // Black outline, so the text reads over a bright effect.
    private static final float OUTLINE_EM = 0.12f;
    private static final int OUTLINE_COLOR = 0xFF000000;

    // Current scaled state (recomputed when screen resolution changes)
    private int lastScreenWidth = -1;
    private int lastScreenHeight = -1;
    private int currentFontSizePx;
    private float currentQuadOffset;

    // FPS counter: sampled over a rolling window rather than shown per-frame,
    // since a single frame's instantaneous 1/dt is too jittery to read.
    private static final long FPS_SAMPLE_WINDOW_NANOS = 500_000_000L; // 0.5s
    private long fpsWindowStartNanos = -1;
    private int fpsWindowFrameCount = 0;
    private double displayedFps = 0.0;

    // Particles drawn: each frame's count, the median shown once a window elapses.
    private int[] particleSamples = new int[256];
    private int particleSampleCount;
    private long particleWindowStartNanos = -1;
    private int displayedParticles;

    // CgTextRenderer resources (created in init, destroyed in delete)
    private CgCapabilities caps;
    private CgFont font;
    private CgFont jpFnt;
    private CgFont arabicFont;
    private CgFont demoFont;
    private CgTextRenderer renderer;
    private PoseStack poseStack;

    private boolean initialized = false;

    private static final float DEMO_TEXT_X = 20.0f;
    private static final float DEMO_TEXT_START_Y = 40.0f + 24.0f;
    private static final float DEMO_TEXT_ROW_GAP = 12.0f;
    private static final int DEMO_FONT_SIZE_PX = 24;

    /** The scene's own lines, under the HUD's ({@code InteractiveSceneLifecycle.hudLine}). */
    private Supplier<String> sceneLines = () -> null;

    public HUDRenderer() {
        currentFontSizePx = BASE_FONT_SIZE_PX;
        currentQuadOffset = BASE_QUAD_OFFSET;
    }

    public void sceneLines(Supplier<String> lines) {
        sceneLines = lines;
    }

    /**
     * Recomputes HUD font size and offset when the screen resolution changes.
     * Scale factor is derived from the screen height relative to the
     * 600px base height, ensuring proportional growth on high-res displays.
     *
     * <p>When the scale changes, the font is reloaded at the new pixel size
     * so glyph rasterization matches the display resolution.</p>
     */
    private void updateScaleIfNeeded(int screenWidth, int screenHeight) {
        if (screenWidth == lastScreenWidth && screenHeight == lastScreenHeight) {
            return;
        }
        lastScreenWidth = screenWidth;
        lastScreenHeight = screenHeight;

        // The shorter side: a tall, narrow window would otherwise get text sized for its height.
        float scale = Math.min(screenWidth, screenHeight) / BASE_RESOLUTION_HEIGHT;
        if (scale < 1.0f) {
            scale = 1.0f;
        }

        int newFontSizePx = Math.round(BASE_FONT_SIZE_PX * scale);
        currentQuadOffset = BASE_QUAD_OFFSET * scale;

        // Note: the renderer's context is created via CgTextRenderer.createScreenSized(),
        // so its orthographic projection auto-tracks the display window resolution via
        // CgTextRendererRegistry — no manual updateOrtho() call needed here.

        // Reload font at new size if the computed pixel size changed
        if (newFontSizePx != currentFontSizePx && font != null) {
            currentFontSizePx = newFontSizePx;
            reloadFont();
        }
    }

    /**
     * Reloads the font at the current pixel size. Disposes the old font and
     * creates a new one. The CgFontRegistry handles atlas cleanup via the
     * font's dispose listener.
     */
    /**
     * Updates the rolling FPS sample. Counts frames within a
     * {@link #FPS_SAMPLE_WINDOW_NANOS} window and recomputes {@link #displayedFps}
     * once the window elapses, rather than showing a raw per-frame 1/dt value
     * (too jittery to read frame to frame).
     */
    private void updateFpsSample() {
        long now = System.nanoTime();
        if (fpsWindowStartNanos < 0) {
            fpsWindowStartNanos = now;
        }
        fpsWindowFrameCount++;

        long elapsed = now - fpsWindowStartNanos;
        if (elapsed >= FPS_SAMPLE_WINDOW_NANOS) {
            displayedFps = fpsWindowFrameCount / (elapsed / 1_000_000_000.0);
            fpsWindowFrameCount = 0;
            fpsWindowStartNanos = now;
        }
    }

    /**
     * Samples {@link CgVfxSystem#particlesDrawn()} each frame and shows the median of each
     * {@link #FPS_SAMPLE_WINDOW_NANOS} window: a blast's spike or a gap between plays moves it less than a mean.
     */
    private void updateParticleSample() {
        long now = System.nanoTime();
        if (particleWindowStartNanos < 0) {
            particleWindowStartNanos = now;
        }
        if (particleSampleCount == particleSamples.length) {
            particleSamples = Arrays.copyOf(particleSamples, particleSamples.length * 2);
        }
        particleSamples[particleSampleCount++] = CgVfxSystem.particlesDrawn();
        if (now - particleWindowStartNanos >= FPS_SAMPLE_WINDOW_NANOS) {
            Arrays.sort(particleSamples, 0, particleSampleCount);
            displayedParticles = particleSamples[particleSampleCount / 2];
            particleSampleCount = 0;
            particleWindowStartNanos = now;
        }
    }

    private void reloadFont() {
        String fontPath = HarnessFontUtil.resolveFontPath(null);

        if (font != null) font.dispose();
        if (jpFnt != null) jpFnt.dispose();
        if (arabicFont != null) arabicFont.dispose();
        
        font = CgFont.load(fontPath, CgFontStyle.REGULAR, currentFontSizePx);
        jpFnt = CgFont.load(HarnessFontUtil.JAPANESE_FONT, CgFontStyle.REGULAR, currentFontSizePx);
        arabicFont = CgFont.load(HarnessFontUtil.ARABIC_FONT, CgFontStyle.REGULAR, currentFontSizePx);
        LOGGER.fine("[HUDRenderer] Font reloaded at " + currentFontSizePx + "px");
    }

    private void ensureDemoFont() {
        if (demoFont != null && !demoFont.isDisposed()) {
            return;
        }
        String fontPath = HarnessFontUtil.resolveFontPath(null);
        demoFont = CgFont.load(fontPath, CgFontStyle.REGULAR, DEMO_FONT_SIZE_PX);
    }

    /**
     * Initializes CgTextRenderer and all GL resources. Must be called once
     * with a valid GL context.
     *
     * <p>Creates the full text rendering pipeline: capabilities detection,
     * font loading, glyph registry, text renderer, orthographic projection
     * context, and layout builder.</p>
     */
    public void init(HarnessContext ctx) {
        if (initialized) {
            return;
        }

        caps = CgCapabilities.detect();

        // Load font from system/test font path at base size
        String fontPath = HarnessFontUtil.resolveFontPath(null);
        font = CgFont.load(fontPath, CgFontStyle.REGULAR, currentFontSizePx);
        demoFont = CgFont.load(fontPath, CgFontStyle.REGULAR, DEMO_FONT_SIZE_PX);

        // Screen-sized: the owned context's orthographic projection auto-tracks the
        // display window resolution via CgTextRendererRegistry (see CgGraphicsLifecycle.onResize).
        renderer = CgTextRenderer.create();
        poseStack = new PoseStack();

        initialized = true;
        LOGGER.info("[HUDRenderer] Initialized with CgTextRenderer, font=" + fontPath
                + ", size=" + currentFontSizePx + "px");
    }

    private float poseScale = 1.0f;
    /**
     * Renders the HUD overlay with current camera position and rotation.
     *
     * <p>Formats the camera state into two lines of text, builds a text layout,
     * and renders it at the top-left corner using CgTextRenderer's orthographic
     * 2D path. The text is rendered directly without any background box.</p>
     *
     * <p>Saves and restores critical GL state (depth test, blend, cull face,
     * active program, VAO, textures) to avoid corrupting subsequent render
     * passes.</p>
     *
     * @param ctx the harness context (provides camera, screen dimensions)
     */
    public void render(HarnessContext ctx) {
        Camera3D camera = ctx.getCamera3D();
        int screenWidth = ctx.getScreenWidth();
        int screenHeight = ctx.getScreenHeight();
        if (!initialized) {
            throw new IllegalStateException("HUDRenderer.init() must be called before render()");
        }

        updateScaleIfNeeded(screenWidth, screenHeight);
        updateFpsSample();
        updateParticleSample();

        String fpsLine = String.format("FPS: %.1f", displayedFps);
        String posLine = String.format("Pos: %.2f %.2f %.2f \\ %.2f\u00B0 %.2f\u00B0",
                camera.getPosX(), camera.getPosY(), camera.getPosZ(), camera.getYaw(), camera.getPitch());
        String particleLine = String.format("Particles: %,d", displayedParticles);
        String simLine = "VFX sim [V]: " + (CgVfxSystem.simulation() == CgVfxSystem.Simulation.CPU ? "cpu" : "gpu")
                + (HarnessCameraShake.INSTANCE.on()
                ? String.format(" - Shake [C]: on, trauma %.2f", CgCameraShake.trauma()) : " - Shake [C]: off");
        CgBloom bloom = CgPostStack.get().bloom();
        String hdrLine = "HDR [G]: " + (CgWorldRenderer.get().hdrScene() ? "on" : "off")
                + String.format(" | Glow [ ]: %.2f", CgWorldRenderer.get().sceneEmission());
        String bloomLine = "Bloom [L]: " + (bloom.intensity() == 0f ? "off" : bloom.linear() ? "linear" : "blend")
                + String.format(" | Intensity [- =]: %.2f", bloom.intensity());
        String hudText = fpsLine + "\n" + posLine + "\n" + particleLine + "\n\n" + simLine + "\n" + hdrLine + "\n" + bloomLine;
        String own = sceneLines.get();
        if (own != null) hudText += "\n" + own;

        // Build text layout for the current frame's text.
        // maxWidth=0 means unbounded (no line wrapping beyond our explicit newline).
        CgTextLayout layout = CgTextLayout.of(hudText, font).build();
        
        // Render text at top-left corner with the configured offset.
        // CgTextRenderer.draw() handles its own GL state save/restore internally
        // via CgStateBoundary, but in the standalone harness the GLStateMirror
        // may be in UNKNOWN state, so we also do explicit cleanup after draw.
        renderer.beginBatch();
        renderer.draw().layout(layout).font(font).at(4, 4).color(TEXT_COLOR).stroke(OUTLINE_EM, OUTLINE_COLOR).submit();

        double wheel = HarnessWindow.takeWheel();
        if (wheel > 0) {
            poseScale = Math.min(4.0f, poseScale + 0.1f);
        } else if (wheel < 0) {
            poseScale = Math.max(0.5f, poseScale - 0.1f);
        }

        String DEMO_TEXT_2D_LABEL = "2D UI text: logical size stable, raster scales with pose";
        String DEMO_TEXT = "CrystalGraphics font demo - mouse wheel zoom بيانات الاستفسار";
        ensureDemoFont();


        float[] demoScales = {0.5f, 1.0f, 1.5f, 2.0f,4.0f};
        float lineY = DEMO_TEXT_START_Y;
        for (float demoScale : demoScales) {
            if(true)
                continue;
            PoseStack ps = anchoredScalePose(DEMO_TEXT_X, lineY, demoScale);
            float logicalWidth = ctx.getScreenWidth() / demoScale;
            CgTextLayout demoLayout = CgTextLayout.of(
                            DEMO_TEXT + " [base " + 24 + "px, pose " + String.format("%.1f", demoScale) + "x]",
                            demoFont)
                    .maxWidth(logicalWidth)
                    .build();

            renderer.context().clearHistory();
            renderer.draw().layout(demoLayout).font(demoFont).at(DEMO_TEXT_X, lineY)
                    .color(0xFFFFFFFF).pose(ps).submit();

            lineY += demoLayout.totalHeight() * demoScale + DEMO_TEXT_ROW_GAP;
        }

        renderer.endBatch();

        renderer.context().clearHistory();
//
//                PoseStack identityPose = new PoseStack();
//                renderer.draw(
//                        DEMO_TEXT_2D_LABEL,
//                        font,
//                        64,
//                        20.0f,
//                        20.0f,
//                        0xAAFFAAFF,
//                        identityPose);
        }

    private PoseStack anchoredScalePose(float anchorX, float anchorY, float scale) {
        PoseStack ps = new PoseStack();
        ps.translate(anchorX, anchorY, 0.0f);
        ps.scale(scale, scale, 1.0f);
        ps.translate(-anchorX, -anchorY, 0.0f);
        return ps;
    }

    /**
     * Handles display resize events. Forces recalculation of scaled font
     * size and orthographic projection on the next render call.
     *
     * @param newWidth  new viewport width in pixels
     * @param newHeight new viewport height in pixels
     */
    public void onDisplayResize(int newWidth, int newHeight) {
        // Force updateScaleIfNeeded to recalculate by invalidating cached dimensions
        lastScreenWidth = -1;
        lastScreenHeight = -1;
    }

    /**
     * Releases all resources held by this renderer: CgTextRenderer, font registry,
     * font, and all associated GL objects (shaders, VBOs, atlas textures).
     */
    public void delete() {
        if (!initialized) {
            return;
        }
        if (renderer != null) {
            renderer.delete();
            renderer = null;
        }

        if (font != null) {
            font.dispose();
            font = null;
        }
        if (demoFont != null) {
            demoFont.dispose();
            demoFont = null;
        }
        initialized = false;
        LOGGER.info("[HUDRenderer] Deleted.");
    }
}
