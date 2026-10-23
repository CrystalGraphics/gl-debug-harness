package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.demo.CgLightsDemo;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldHaze;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.variable.CgVars;
import com.crystalgraphics.vfx.CgVfx;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * How the lit air looks, as numbers: the lights demo's searchlight ({@code CgLightsDemo}) in its haze, a measurement
 * rather than a gate. Near its source, how much each pixel changes frame to frame with the air's history on and off;
 * side-on to the beam, how much it adds to the haze seen from 10 blocks and from 40, which the physics says should
 * barely differ; looking down the beam from just behind its source, as a player standing by it does; then the whole
 * demo playing, six blocks from one of its searchlights' base, history on and off, where what the show's own motion
 * changes counts in both.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-air-quality" -Dcrystalgraphics.harness.fixedDelta=0.0166667
 * }</pre>
 *
 * <p>Prints {@code [lights-air-quality]} lines: each jitter phase's mean and largest per-pixel deviation, and each
 * distance's beam over the haze.</p>
 */
public class CgLightsAirQualityScene implements InteractiveSceneLifecycle {

    private static final int W = 640, H = 360, SETTLE = 60, SERIES = 30, PHASE = SETTLE + SERIES;
    private static final int GROUND = 64;
    /** The searchlight: CgLightsDemo's, three blocks up, pointing along x and down a little. */
    private static final Vector3d SOURCE = new Vector3d(0, GROUND + 3, 0);
    private static final Vector3d DIR = new Vector3d(1, -0.3, 0).normalize();
    /** Where the jitter is read (a block along the beam) and the beam read side-on (eight along). */
    private static final Vector3d BASE = new Vector3d(DIR).mul(1).add(SOURCE), SIDE = new Vector3d(DIR).mul(8).add(SOURCE);
    private static final String HISTORY = "crystalgraphics.world.lightsAirHistory", AIR = "crystalgraphics.world.lightsAir";
    private static final String[] NAMES = {"jitter-history", "jitter-middles", "near-air", "near-none", "far-air", "far-none",
            "along-history", "along-middles", "demo-history", "demo-middles"};
    /** The phases photographed as a series, and the ones lighting froxel middles alone. */
    private static final int ALONG = 6, DEMO = 8;
    /** Looking down the beam: from a block and a half behind the source and a block and a half up, at six along. */
    private static final Vector3d BEHIND = new Vector3d(DIR).mul(-1.5).add(SOURCE).add(0, 1.5, 0),
            DOWN_BEAM = new Vector3d(DIR).mul(6).add(SOURCE);
    /** The demo's left searchlight (CgLightsDemo.searchlights: 15 west, 12 south, 3 up of where it plays), and a block
     *  along the way it points at the start. */
    private static final Vector3d DEMO_BASE = new Vector3d(-15, GROUND + 3, 12).add(new Vector3d(0.5, -0.3, -0.8).normalize());
    private static final double SHOW_AT = 30.0;
    /** The searchlight's VolumetricScatteringIntensity (-Dcrystalgraphics.harness.airQuality.scattering): the demo's 8. */
    private static final float SCATTERING = Float.parseFloat(System.getProperty("crystalgraphics.harness.airQuality.scattering", "8"));

    private final CgWorldHaze haze = new CgWorldHaze().box(-30, GROUND - 1, -30, 30, GROUND + 18, 30).density(0.003f, 4f);
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), (float) W / H, 0.05f, 300f);
    private final Matrix4f look = new Matrix4f();
    private final Vector3d eye = new Vector3d();
    private final CgLightsDemo demo = new CgLightsDemo(CgVfx.owner("harness"));
    private int demoFrom = -1;
    private HarnessFboHelper target;
    private CgMaterial grey;
    private int frame;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        CgPlatform.provide(CgLevels.SERVICE, CgLevels.single(new Flat(), CgEntityQuery.NONE));
        CgWorldRenderer.get().install();
        grey = CgMaterial.load("assets/harness/shader/bloom_wall.shader");
        CgWorldRenderer.get().media().add(haze);
        target = HarnessFboHelper.create(W, H, true);
        demo.prepare();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        int phase = frame / PHASE, at = frame % PHASE;
        // The demo's phases wait for its programs: the frame before them is drawn again, uncounted, until they are ready.
        boolean waiting = phase == DEMO && demoFrom < 0 && !demo.warmed();
        if (waiting) {
            phase = DEMO - 1;
            at = 0;
        } else if (phase == DEMO && demoFrom < 0) {
            demoFrom = frame;
            CgWorldRenderer.get().media().remove(haze);
        }
        if (!waiting) frame++;
        if (phase >= NAMES.length) {
            report(ctx);
            ((CgVar.Bool) CgVars.get(HISTORY)).set(true);
            ((CgVar.Bool) CgVars.get(AIR)).set(true);
            running = false;
            return;
        }
        ((CgVar.Bool) CgVars.get(HISTORY)).set(phase != 1 && phase != ALONG + 1 && phase != DEMO + 1);
        ((CgVar.Bool) CgVars.get(AIR)).set(phase != 3 && phase != 5);
        Vector3d on = camera(phase);
        CgWorldRenderer renderer = CgWorldRenderer.get();
        target.bind();
        target.clear(0.01f, 0.01f, 0.02f, 1f);
        CgMesh cube = CgMeshShapes.cube();
        renderer.draw(cube, grey).at(0, GROUND - 0.5, 0).transform(new Matrix4f().scale(120f, 1f, 120f)).submit();
        if (waiting) {
            demo.warmed();
        } else if (phase >= DEMO) {
            demo.submit(renderer, 0, GROUND, 0, SHOW_AT + (frame - demoFrom) / 60.0);
        } else {
            renderer.lights().add(SOURCE.x, SOURCE.y, SOURCE.z, 45f, 0xFFFFF4E0, 4f * (float) Math.PI * 144f, 0f)
                    .spot((float) DIR.x, (float) DIR.y, (float) DIR.z, 5f, 10f).scattering(SCATTERING);
        }
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, W, H, target.getFboId()).view().set(eye.x, eye.y, eye.z, look, projection);
            stage.fire();
        }
        CgFrameRing.endFrame();
        if (series(phase) && at >= SETTLE) target.captureToFile(ctx.getOutputDir(), NAMES[phase] + "-" + (at - SETTLE) + ".png");
        else if (!series(phase) && at == PHASE - 1) target.captureToFile(ctx.getOutputDir(), NAMES[phase] + ".png");
        target.unbind();
    }

    private static boolean series(int phase) {
        return phase < 2 || phase >= ALONG;
    }

    /**
     * Places {@link #eye} and {@link #look} for {@code phase} and answers what it looks at: six blocks off the base,
     * side-on; the beam side-on from 10 and 40 blocks; down the beam from behind its source; six off the demo's.
     */
    private Vector3d camera(int phase) {
        Vector3d on;
        if (phase == ALONG || phase == ALONG + 1) {
            on = DOWN_BEAM;
            eye.set(BEHIND);
        } else if (phase >= DEMO) {
            on = DEMO_BASE;
            eye.set(on.x + 6, on.y + 1, on.z);
        } else {
            on = phase < 2 ? BASE : SIDE;
            eye.set(on.x, on.y + 1, on.z + (phase < 2 ? 6 : phase < 4 ? 10 : 40));
        }
        look.setLookAt(0f, 0f, 0f, (float) (on.x - eye.x), (float) (on.y - eye.y), (float) (on.z - eye.z), 0f, 1f, 0f);
        return on;
    }

    private void report(HarnessContext ctx) {
        try {
            for (int p = 0; p < 2; p++) jitter(ctx, p);
            for (int p = ALONG; p < NAMES.length; p++) jitter(ctx, p);
            float nearBeam = beam(ctx, 2), farBeam = beam(ctx, 4);
            System.out.printf("[lights-air-quality] the beam over the haze side-on: %.1f from 10 blocks, %.1f from 40 (%.0f%%)%n",
                    nearBeam, farBeam, 100f * farBeam / Math.max(nearBeam, 1e-3f));
        } catch (IOException e) {
            System.out.println("[lights-air-quality] the captures did not read back: " + e.getMessage());
        }
    }

    /** Each pixel round what the phase looks at: its deviation over the series. */
    private void jitter(HarnessContext ctx, int phase) throws IOException {
        int[] c = project(camera(phase));
        int r = phase == ALONG || phase == ALONG + 1 ? 150 : 60, side = 2 * r + 1;
        double[] sum = new double[side * side], sq = new double[side * side];
        for (int k = 0; k < SERIES; k++) {
            BufferedImage image = ImageIO.read(new File(ctx.getOutputDir(), NAMES[phase] + "-" + k + ".png"));
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    double l = luminance(image, c[0] + dx, c[1] + dy);
                    int i = (dy + r) * side + dx + r;
                    sum[i] += l;
                    sq[i] += l * l;
                }
            }
        }
        double meanDev = 0, mean = 0;
        double[] devs = new double[sum.length];
        for (int i = 0; i < sum.length; i++) {
            double m = sum[i] / SERIES;
            devs[i] = Math.sqrt(Math.max(sq[i] / SERIES - m * m, 0));
            meanDev += devs[i];
            mean += m;
        }
        Arrays.sort(devs);
        System.out.printf("[lights-air-quality] %-15s brightness %.1f, deviation frame to frame %.2f mean, %.2f at the 99th"
                + " percentile, %.2f largest%n", NAMES[phase], mean / sum.length, meanDev / sum.length,
                devs[(int) (devs.length * 0.99)], devs[devs.length - 1]);
    }

    /** The beam's brightness over the haze's alone, side-on at {@code away} blocks: the middle 5x5 of the beam. */
    private float beam(HarnessContext ctx, int phase) throws IOException {
        int[] c = project(camera(phase));
        BufferedImage lit = ImageIO.read(new File(ctx.getOutputDir(), NAMES[phase] + ".png"));
        BufferedImage none = ImageIO.read(new File(ctx.getOutputDir(), NAMES[phase + 1] + ".png"));
        float sum = 0f;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) sum += luminance(lit, c[0] + dx, c[1] + dy) - luminance(none, c[0] + dx, c[1] + dy);
        }
        return sum / 25f;
    }

    private int[] project(Vector3d p) {
        Vector4f clip = new Vector4f((float) (p.x - eye.x), (float) (p.y - eye.y), (float) (p.z - eye.z), 1f);
        look.transform(clip);
        projection.transform(clip);
        return new int[]{(int) ((clip.x / clip.w * 0.5f + 0.5f) * W), H - 1 - (int) ((clip.y / clip.w * 0.5f + 0.5f) * H)};
    }

    private static double luminance(BufferedImage image, int x, int y) {
        int rgb = image.getRGB(Math.max(0, Math.min(W - 1, x)), Math.max(0, Math.min(H - 1, y)));
        return ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3.0;
    }

    @Override
    public void dispose() {
        demo.delete();
        if (target != null) target.delete();
        CgWorldRenderer.get().media().remove(haze);
        CgPlatform.provide(CgLevels.SERVICE, null);
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }

    /** Solid below {@link #GROUND}, open night sky above it. */
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
        @Override public int light(int x, int y, int z) { return 0; }
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
