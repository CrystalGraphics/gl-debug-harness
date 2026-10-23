package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.demo.CgLightsDemo;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.variable.CgVars;
import com.crystalgraphics.vfx.CgVfx;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * The lights demo ({@code CgLightsDemo}) at midnight on a flat world under a night's lightmap, seen from in front of its
 * arena, captured with each of the world lights' later passes left out in turn: everything, the lit air off, every
 * shadow off, the transparent volume off, all three off. Prints each capture's mean brightness, its share of saturated
 * pixels and the mean round the mirror ball, to tell which pass brightens the show.
 *
 * <p>Steady ({@code lights-demo}), it draws every pass on and the show held at one moment until closed: a run to
 * profile, the particles stepped by {@code fixedDelta}, more lights added by the demo's sweep.</p>
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-demo-bisect --width=960 --height=540"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-demo --width=2560 --height=1440" \
 *     -Dcrystalgraphics.harness.fixedDelta=0.0166667 -Dcrystalgraphics.harness.profile=600 \
 *     -Dcrystalgraphics.harness.profile.warmup=900 -Dcrystalgraphics.trace.channels=crystalgraphics,gpu \
 *     -Dcrystalgraphics.demo.lightsSweep=100 -Dcrystalgraphics.demo.lightsSweepKind=scatter
 * }</pre>
 */
public class CgLightsDemoBisectScene implements InteractiveSceneLifecycle {

    private static final int PHASE = 180, GROUND = 64;
    private static final double SHOW_AT = 4.0;
    private static final double X = 0, Z = 0, EYE_X = 0, EYE_Y = GROUND + 4, EYE_Z = 24;
    /** The mirror ball: CgLightsDemo's, ten blocks over the arena's middle and two behind it. */
    private static final double[] BALL = {X, GROUND + 10, Z - 2};
    private static final String[] VARS = {"crystalgraphics.world.lightsAir", "crystalgraphics.world.lightsShadows",
            "crystalgraphics.world.lightsVolume"};
    /** Per phase, which of VARS are on. */
    private static final boolean[][] PHASES = {{true, true, true}, {false, true, true}, {true, false, true},
            {true, true, false}, {false, false, false}};
    private static final String[] NAMES = {"all", "no-air", "no-shadows", "no-volume", "none"};

    private final CgLightsDemo demo = new CgLightsDemo(CgVfx.owner("harness"));
    private final boolean steady;
    private final Matrix4f view = new Matrix4f().lookAt(0f, 0f, 0f, (float) (X - EYE_X), (float) (GROUND + 4 - EYE_Y),
            (float) (Z - 2 - EYE_Z), 0f, 1f, 0f);
    private final Matrix4f projection = new Matrix4f(), ground = new Matrix4f();
    private HarnessFboHelper target;
    private CgMaterial floor;
    private CgTexture2D lightmap;
    private int width, height, frame, warmAt = -1;
    private boolean running = true;

    public CgLightsDemoBisectScene() {
        this(false);
    }

    public CgLightsDemoBisectScene(boolean steady) {
        this.steady = steady;
    }

    @Override
    public void init(HarnessContext ctx) {
        CgPlatform.provide(CgLevels.SERVICE, CgLevels.single(new Flat(), CgEntityQuery.NONE));
        CgWorldRenderer.get().install();
        floor = CgMaterial.load("assets/harness/shader/bloom_wall.shader");
        demo.prepare();
        width = ctx.getScreenWidth();
        height = ctx.getScreenHeight();
        target = HarnessFboHelper.create(width, height, true);
        // A night's lightmap: block light along u as Minecraft's, the sky's moonlight a faint blue.
        ByteBuffer texels = ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.nativeOrder());
        for (int sky = 0; sky < 16; sky++) {
            for (int block = 0; block < 16; block++) {
                float b = 0.03f + 0.97f * block / 15f, s = 0.05f * sky / 15f;
                texels.put((byte) Math.round(255 * Math.min(1f, b + s))).put((byte) Math.round(255 * Math.min(1f, b + s)))
                        .put((byte) Math.round(255 * Math.min(1f, b + s * 1.6f))).put((byte) 255);
            }
        }
        texels.flip();
        lightmap = CgTexture2D.createEmpty(16, 16, CgTextureSpec.RGBA8_LINEAR);
        lightmap.uploadRegion(0, 0, 0, 16, 16, texels, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (warmAt < 0 && demo.warmed()) warmAt = frame;
        int phase = warmAt < 0 || steady ? -1 : (frame - warmAt) / PHASE;
        if (phase >= 0 && phase < PHASES.length) {
            for (int v = 0; v < VARS.length; v++) ((CgVar.Bool) CgVars.get(VARS[v])).set(PHASES[phase][v]);
        }
        projection.setPerspective((float) Math.toRadians(70), (float) width / height, 0.05f, 400f);
        target.bind();
        target.clear(0.01f, 0.01f, 0.03f, 1f);
        CgPassConstants host = new CgPassConstants();
        host.view.set(view);
        host.projection.set(projection);
        host.resolution(width, height).time(CgFrameClock.seconds()).depth(CgGL.isDepthReversed(), CgGL.isDepthZeroToOne())
                .cameraFromView();
        ground.translation((float) (X - EYE_X), (float) (GROUND - 0.5 - EYE_Y), (float) (Z - EYE_Z)).scale(400f, 1f, 400f);
        CgMesh cube = CgMeshShapes.cube();
        ObjectDraws.draw(host, floor, cube, 1, (i, data, at) -> ObjectDraws.object(data, at, ground, 0.35f, 0.4f, 0.3f, 1f));
        // One moment of the show for every capture, between its lightning's flashes: only the passes differ.
        if (warmAt >= 0) demo.submit(CgWorldRenderer.get(), X, GROUND, Z, SHOW_AT);
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, width, height, target.getFboId()).view().set(EYE_X, EYE_Y, EYE_Z, view, projection);
            stage.host().environment().sun(0.5f, 0, 0f, 1f);
            stage.host().textures().importedLightmap(lightmap.getId(), 16, 16);
            stage.fire();
        }
        CgFrameRing.endFrame();
        if (phase >= 0 && phase < PHASES.length && (frame - warmAt) % PHASE == PHASE - 1) {
            target.captureToFile(ctx.getOutputDir(), NAMES[phase] + ".png");
        }
        target.unbind();
        if (phase == PHASES.length) {
            for (String v : VARS) ((CgVar.Bool) CgVars.get(v)).set(true);
            report(ctx);
            running = false;
        }
    }

    private void report(HarnessContext ctx) {
        for (String name : NAMES) {
            try {
                BufferedImage image = ImageIO.read(new File(ctx.getOutputDir(), name + ".png"));
                double sum = 0, ball = 0;
                int saturated = 0, ballN = 0;
                int[] at = project(BALL);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int rgb = image.getRGB(x, y);
                        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
                        float l = (r + g + b) / 3f;
                        sum += l;
                        if (Math.max(r, Math.max(g, b)) >= 250) saturated++;
                        if (Math.abs(x - at[0]) <= 60 && Math.abs(y - at[1]) <= 60) {
                            ball += l;
                            ballN++;
                        }
                    }
                }
                System.out.printf("[lights-demo-bisect] %-10s mean %.1f, saturated %.1f%%, round the ball %.1f%n", name,
                        sum / (width * height), 100.0 * saturated / (width * height), ballN > 0 ? ball / ballN : -1.0);
            } catch (IOException e) {
                System.out.println("[lights-demo-bisect] " + name + " did not read back: " + e.getMessage());
            }
        }
    }

    private int[] project(double[] p) {
        Vector4f clip = new Vector4f((float) (p[0] - EYE_X), (float) (p[1] - EYE_Y), (float) (p[2] - EYE_Z), 1f);
        view.transform(clip);
        projection.transform(clip);
        return new int[]{(int) ((clip.x / clip.w * 0.5f + 0.5f) * width), height - 1 - (int) ((clip.y / clip.w * 0.5f + 0.5f) * height)};
    }

    @Override
    public void dispose() {
        demo.delete();
        if (target != null) target.delete();
        CgPlatform.provide(CgLevels.SERVICE, null);
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }

    /** Solid below {@link #GROUND}, open sky above it. */
    private static final class Flat implements CgWorldQuery {

        private static boolean solid(int y) {
            return y < GROUND;
        }

        @Override
        public int collisionBoxes(int x, int y, int z, float[] out) {
            if (!solid(y) || out.length < 6) return solid(y) ? 1 : 0;
            out[0] = out[1] = out[2] = 0f;
            out[3] = out[4] = out[5] = 1f;
            return 1;
        }

        @Override public float collisionTop(int x, int y, int z) { return solid(y) ? 1f : Float.NaN; }
        @Override public float collisionBottom(int x, int y, int z) { return solid(y) ? 0f : Float.NaN; }
        @Override public int light(int x, int y, int z) { return solid(y) ? 0 : 15 << 4; }
        @Override public int surface(int x, int y, int z) { return solid(y) ? SURFACE_STONE : SURFACE_NONE; }
        @Override public float hardness(int x, int y, int z) { return solid(y) ? 1.5f : Float.NaN; }
        @Override public int lightEmission(int x, int y, int z) { return 0; }
        @Override public int tint(int x, int y, int z) { return 0; }
        @Override public int biomeColor(int x, int y, int z, int kind) { return 0xFF808080; }
        @Override public int precipitation(int x, int y, int z) { return PRECIPITATION_NONE; }
        @Override public int surfaceY(int x, int z, int kind) { return GROUND; }
        @Override public boolean spriteRect(int x, int y, int z, float[] out) { return false; }
        @Override public float fluidHeight(int x, int y, int z) { return Float.NaN; }
        @Override public int fluidKind(int x, int y, int z) { return FLUID_NONE; }
        @Override public int mapColor(int x, int y, int z) { return solid(y) ? 0xFF707070 : 0; }
        @Override public boolean loaded(int x, int z) { return true; }
        @Override public int minY() { return 0; }
        @Override public int maxY() { return 256; }
        @Override public int seaLevel() { return 63; }
        @Override public int levelEpoch() { return 1; }
    }
}
