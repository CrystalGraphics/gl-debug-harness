package com.crystalgraphics.harness.camera;

import com.crystalgraphics.api.font.CgDefaultFont;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.bloom.CgBloom;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.text.render.CgTextRenderer;
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
 * HDR [G]: on | off | Glow [ ]: g
 * Bloom [L]: off | linear | blend | Intensity [- =]: b
 * </pre>
 */
public final class HUDRenderer {

    private static final int BASE_FONT_PX = 14;
    private static final float BASE_SIDE = 600f;
    private static final int TEXT_COLOR = 0xFFFF5C7A;
    private static final float OUTLINE_EM = 0.12f;
    private static final int OUTLINE_COLOR = 0xFF000000;
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
    private CgTextRenderer renderer;

    private Supplier<String> sceneLines = () -> null;
    private boolean shakeAndGlow = true;

    public void sceneLines(Supplier<String> lines) {
        sceneLines = lines;
    }

    /** Whether the shake and glow controls show ({@code InteractiveSceneLifecycle.hudShowsShakeAndGlow}). */
    public void shakeAndGlow(boolean shown) {
        shakeAndGlow = shown;
    }

    /** Once, on the render thread. */
    public void init(HarnessContext ctx) {
        if (renderer == null) renderer = CgTextRenderer.create();
    }

    public void render(HarnessContext ctx) {
        if (renderer == null) throw new IllegalStateException("HUDRenderer.init() must be called before render()");
        int px = Math.round(BASE_FONT_PX * Math.max(1f, Math.min(ctx.getScreenWidth(), ctx.getScreenHeight()) / BASE_SIDE));
        if (px != fontPx) {
            font = CgDefaultFont.regular(px);
            fontPx = px;
        }
        sampleFps();
        sampleParticles();

        Camera3D camera = ctx.getCamera3D();
        CgBloom bloom = CgPostStack.get().bloom();
        StringBuilder text = new StringBuilder()
                .append(String.format("FPS: %.0f | 1%% low %.0f | worst %.2f ms", fps, lowFps, worstMs))
                .append(String.format("\nPos: %.2f %.2f %.2f \\ %.2f° %.2f°",
                        camera.getPosX(), camera.getPosY(), camera.getPosZ(), camera.getYaw(), camera.getPitch()))
                .append(String.format("\nParticles: %,d\n", particles));
        if (shakeAndGlow) {
            text.append(HarnessCameraShake.INSTANCE.on()
                    ? String.format("\nShake [C]: on, trauma %.2f", CgCameraShake.trauma()) : "\nShake [C]: off");
        }
        text.append("\nHDR [G]: ").append(CgWorldRenderer.get().hdrScene() ? "on" : "off");
        if (shakeAndGlow) text.append(String.format(" | Glow [ ]: %.2f", CgWorldRenderer.get().sceneEmission()));
        text.append("\nBloom [L]: ").append(bloom.intensity() == 0f ? "off" : bloom.linear() ? "linear" : "blend")
                .append(String.format(" | Intensity [- =]: %.2f", bloom.intensity()));
        String own = sceneLines.get();
        if (own != null) text.append('\n').append(own);

        CgTextLayout layout = CgTextLayout.of(text.toString(), font).build();
        renderer.beginBatch();
        renderer.draw().layout(layout).font(font).at(4, 4).color(TEXT_COLOR).stroke(OUTLINE_EM, OUTLINE_COLOR).submit();
        renderer.endBatch();
    }

    public void delete() {
        if (renderer != null) {
            renderer.delete();
            renderer = null;
        }
        // The default font is shared: dropped, never disposed
        font = null;
        fontPx = 0;
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
