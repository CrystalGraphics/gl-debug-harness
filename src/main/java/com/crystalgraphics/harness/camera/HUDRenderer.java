package com.crystalgraphics.harness.camera;

import com.crystalgraphics.render.scene.renderer.CgSceneRenderer;
import com.crystalgraphics.render.post.aa.CgTemporalAA;
import com.crystalgraphics.render.post.upscale.CgAntiAliasing;
import com.crystalgraphics.render.scene.renderer.feature.ao.CgAmbientOcclusion;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.api.font.CgDefaultFont;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.text.style.CgTextLayer;
import com.crystalgraphics.text.style.CgTextStyle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.post.CgPost;
import com.crystalgraphics.render.post.debug.CgPostDebug;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.camera.CgCameraShake;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * The harness's HUD, top left in the engine's default font, cherry red outlined in black, 14 px where the window's shorter
 * side is 600 and in proportion above. The scene's own lines ({@code InteractiveSceneLifecycle.hudLine}) go under it:
 * <pre>
 * FPS: n | 1% low n | worst x.xx ms   (each over the last 0.5 s)
 * Pos: x.xx y.yy z.zz \ yaw° pitch°
 * Particles: n  (the median drawn a frame over the last 0.5 s)
 *
 * Shake [C]: on, trauma t | off
 * Bloom [L]: on, b [- =] | off | Emission [ ]: g | Temporal AA [F7]: on, TAA | TSR [F11] | off, RCAS [F8]: on | off
 * AO [F12]: off | SSAO | GTAO | CACAO, its quality [Alt+F12], CACAO downsampled [Ctrl+Alt+F12], show [Ctrl+F12]
 * </pre>
 */
public final class HUDRenderer {

    private static final int BASE_FONT_PX = 14;
    private static final float BASE_SIDE = 600f;
    /** Red, outlined 0.12 em in black: at 75 pt, 100 px, a width of 12. */
    private static final CgTextStyle LOOK = CgTextStyle.builder("harness:hud")
            .size(75f)
            .layer(CgTextLayer.outline("outline").width(12f).paint(0xFF000000))
            .layer(CgTextLayer.fill("fill").paint(0xFFFF4F5E))
            .build();
    private static final long WINDOW_NANOS = 500_000_000L;

    // The average alone hides a hitch (one 30 ms frame in half a second at 1,000 fps moves it 3%)
    private long fpsWindowStart = -1;
    private long lastFrame = -1;
    private long[] frameNanos = new long[1024];
    private int frameCount;
    private double fps, lowFps, worstMs;

    private int[] particleSamples = new int[256];
    private int particleSampleCount;
    private long particleWindowStart = -1;
    private int particles;

    private CgFont font;
    private int fontPx;
    private static final long REFRESH_NANOS = 50_000_000L;
    private CgTextLayout layout;
    private int layoutPx;
    private long layoutAt;

    private Supplier<String> sceneLines = () -> null;
    private boolean shakeAndGlow = true;

    public void sceneLines(Supplier<String> lines) {
        sceneLines = lines;
    }

    /** Whether the shake and glow controls show ({@code InteractiveSceneLifecycle.hudShowsShakeAndGlow}). */
    public void shakeAndGlow(boolean shown) {
        shakeAndGlow = shown;
    }

    /** Draws the HUD on {@code canvas}, in the window's pixels. */
    public void render(CgCanvas canvas, HarnessContext ctx) {
        int px = Math.round(BASE_FONT_PX * Math.max(1f, Math.min(ctx.getScreenWidth(), ctx.getScreenHeight()) / BASE_SIDE));
        if (px != fontPx) {
            font = CgDefaultFont.regular(px);
            fontPx = px;
        }
        sampleFps();
        sampleParticles();

        // At most 20 times a second: in between the same layout is drawn, whose shaping and glyphs are kept
        long now = System.nanoTime();
        if (layout == null || layoutPx != px || now - layoutAt >= REFRESH_NANOS) {
            layout = CgTextLayout.of(text(ctx), font).build();
            layoutPx = px;
            layoutAt = now;
        }
        canvas.text().style(LOOK).layout(layout).font(font).at(4, 4).submit();
    }

    private String text(HarnessContext ctx) {
        Camera3D camera = ctx.getCamera3D();
        CgPostSettings bloom = CgPost.client().settings();
        StringBuilder text = new StringBuilder()
                .append(String.format("FPS: %.0f | 1%% low %.0f | worst %.2f ms", fps, lowFps, worstMs))
                .append(String.format("\nPos: %.2f %.2f %.2f \\ %.2f° %.2f°",
                        camera.getPosX(), camera.getPosY(), camera.getPosZ(), camera.getYaw(), camera.getPitch()))
                .append(String.format("\nParticles: %,d\n", particles));
        if (shakeAndGlow) {
            text.append(HarnessCameraShake.INSTANCE.on()
                    ? String.format("\nShake [C]: on, trauma %.2f", CgCameraShake.trauma()) : "\nShake [C]: off");
        }
        text.append(bloom.bloomIntensity() == 0f ? "\nBloom [L]: off" : String.format("\nBloom [L]: on, %.2f [- =]", bloom.bloomIntensity()));
        if (shakeAndGlow) text.append(String.format(" | Emission [ ]: %.2f", CgView.main().flag(CgSceneRenderer.SCENE_EMISSION)));
        text.append(CgView.main().flag(CgSceneRenderer.GRADE_DRAWS) ? " | Kit looks [F6]: on" : " | Kit looks [F6]: off")
                .append(!CgTemporalAA.enabled(CgView.main()) ? " | Temporal AA [F7]: off"
                        : CgView.main().antiAliasing() == CgAntiAliasing.TSR ? " | Temporal AA [F7]: on, TSR [F11]" : " | Temporal AA [F7]: on, TAA [F11]")
                .append(CgTemporalAA.sharpening() ? ", RCAS [F8]: on" : ", RCAS [F8]: off")
                .append("\nAO [F12]: ").append(!CgGraphicsSettings.AMBIENT_OCCLUSION.get() ? "off"
                        : CgAmbientOcclusion.methodName() + ", " + CgAmbientOcclusion.LEVEL_NAMES[
                                CgAmbientOcclusion.shaderLevel(CgView.main().post().settings())] + " [Alt+F12]"
                                + (CgGraphicsSettings.AMBIENT_OCCLUSION_DOWNSAMPLE.get() ? ", downsampled" : ", native") + " [Ctrl+Alt+F12]")
                .append(CgPostDebug.VIEW.get().equals("ao") ? ", shown [Ctrl+F12]" : ", show [Ctrl+F12]");
        String own = sceneLines.get();
        if (own != null) text.append('\n').append(own);
        return text.toString();
    }

    public void delete() {
        // The default font is shared: dropped, never disposed
        font = null;
        fontPx = 0;
        layout = null;
    }

    /** A window's average FPS, its 1% low (the slowest 1% of its frames, averaged: CapFrameX's) and its worst frame. */
    private void sampleFps() {
        long now = System.nanoTime();
        if (lastFrame >= 0) {
            if (frameCount == frameNanos.length) frameNanos = Arrays.copyOf(frameNanos, frameCount * 2);
            frameNanos[frameCount++] = now - lastFrame;
        }
        lastFrame = now;
        if (fpsWindowStart < 0) fpsWindowStart = now;
        long elapsed = now - fpsWindowStart;
        if (elapsed < WINDOW_NANOS || frameCount == 0) return;
        fps = frameCount / (elapsed / 1e9);
        Arrays.sort(frameNanos, 0, frameCount);
        int slowest = Math.max(1, frameCount / 100);
        long sum = 0;
        for (int i = frameCount - slowest; i < frameCount; i++) sum += frameNanos[i];
        lowFps = 1e9 * slowest / sum;
        worstMs = frameNanos[frameCount - 1] / 1e6;
        frameCount = 0;
        fpsWindowStart = now;
    }

    /** The median of {@link CgVfxSystem#particlesDrawn()} over a window: a blast's spike moves it less than a mean. */
    private void sampleParticles() {
        long now = System.nanoTime();
        if (particleWindowStart < 0) particleWindowStart = now;
        if (particleSampleCount == particleSamples.length) {
            particleSamples = Arrays.copyOf(particleSamples, particleSampleCount * 2);
        }
        particleSamples[particleSampleCount++] = CgVfxSystem.particlesDrawn();
        if (now - particleWindowStart >= WINDOW_NANOS) {
            Arrays.sort(particleSamples, 0, particleSampleCount);
            particles = particleSamples[particleSampleCount / 2];
            particleSampleCount = 0;
            particleWindowStart = now;
        }
    }
}
