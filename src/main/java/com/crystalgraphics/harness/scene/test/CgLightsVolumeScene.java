package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.variable.CgVars;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * The world lights on transparent surfaces' gate (plan clustered-lights-decals L5): {@code lights-shadow}'s floor, wall and
 * light, and two lit glass panes over the floor, each 8 blocks from the light: one in the open, one behind the wall.
 * Drawn with the translucency lighting volume, then without it ({@code crystalgraphics.world.lightsVolume}), then with
 * no light. The pane in the open must be brighter with the volume; the one behind the wall, which the light's ray
 * cannot reach, no brighter. Beside the open pane two {@code "Lighting" = "Surface"} panes, one's normal toward the
 * light and one's away: the volume's direction must light the first above the second. A pane carrying its own lighting
 * shows the light it is handed ({@code CG_DRAW_POINT_LIGHT_*}): found, at its distance, with its radius, while there is
 * a light, and none in the dark.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-volume"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-volume --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [lights-volume] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgLightsVolumeScene implements InteractiveSceneLifecycle {

    private static final int PHASE = 120, W = 320, H = 240;
    private static final int CX = -1000, CZ = 2000, GROUND = 64;
    private static final double LIGHT_X = CX - 3.5, LIGHT_Y = GROUND + 2.5, LIGHT_Z = CZ + 0.5;
    /** The panes, a block over the floor: 8 blocks from the light across the wall, and 8 along it in the open. */
    private static final double[] BEHIND = {CX + 4.5, GROUND + 1, CZ + 0.5}, OPEN = {LIGHT_X, GROUND + 1, CZ + 8.5};
    /** The Surface-lit panes either side of the open one, their normals toward the light (-z) and away. */
    private static final double[] FACING = {LIGHT_X - 2.5, GROUND + 1, CZ + 8.5}, AWAY = {LIGHT_X + 2.5, GROUND + 1, CZ + 8.5};
    /** The pane handed the light, 9.55 blocks from it. */
    private static final double[] HANDED = {CX + 1.5, GROUND + 1, CZ + 8.5};
    private static final float LIGHT_RADIUS = 16f;
    private static final double EYE_X = CX + 0.5, EYE_Y = GROUND + 16, EYE_Z = CZ + 14;
    private static final String VOLUME = "crystalgraphics.world.lightsVolume";
    private static final String[] NAMES = {"volume", "no-volume", "dark"};

    private final World world = new World();
    private final Matrix4f look = new Matrix4f().lookAt(0f, 0f, 0f, (float) (CX + 0.5 - EYE_X), (float) (GROUND - EYE_Y),
            (float) (CZ + 3 - EYE_Z), 0f, 1f, 0f);
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), (float) W / H, 0.05f, 200f);
    private HarnessFboHelper target;
    private CgMaterial grey, glass, surface, handed;
    private int frame;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        CgPlatform.provide(CgLevels.SERVICE, CgLevels.single(world, CgEntityQuery.NONE));
        CgWorldRenderer.get().install();
        grey = CgMaterial.load("assets/harness/shader/bloom_wall.shader");
        glass = CgMaterial.load("assets/harness/shader/forward_transparent_test.shader");
        surface = CgMaterial.load("assets/harness/shader/surface_lit_pane.shader");
        handed = CgMaterial.load("assets/harness/shader/handed_lights_pane.shader");
        target = HarnessFboHelper.create(W, H, true);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        int phase = (frame - 1) / PHASE;
        ((CgVar.Bool) CgVars.get(VOLUME)).set(phase != 1);
        CgWorldRenderer renderer = CgWorldRenderer.get();
        target.bind();
        target.clear(0.05f, 0.05f, 0.07f, 1f);
        CgMesh cube = CgMeshShapes.cube();
        renderer.draw(cube, grey).at(CX + 0.5, GROUND - 0.5, CZ + 0.5).transform(new Matrix4f().scale(40f, 1f, 40f)).submit();
        renderer.draw(cube, grey).at(CX + 0.5, GROUND + 1.5, CZ + 0.5).transform(new Matrix4f().scale(1f, 3f, 9f)).submit();
        for (double[] pane : new double[][]{BEHIND, OPEN}) {
            renderer.draw(cube, glass).at(pane[0], pane[1], pane[2]).transform(new Matrix4f().scale(2f, 0.1f, 2f))
                    .custom(0, 0.5f, 0.5f, 0.5f, 1.8f).submit();
        }
        for (double[] pane : new double[][]{FACING, AWAY}) {
            renderer.draw(cube, surface).at(pane[0], pane[1], pane[2]).transform(new Matrix4f().scale(2f, 0.1f, 2f))
                    .custom(0, 0.5f, 0.5f, 0.5f, 0.9f).custom(1, 0f, 0f, pane == FACING ? -1f : 1f, 0f).submit();
        }
        renderer.draw(cube, handed).at(HANDED[0], HANDED[1], HANDED[2]).transform(new Matrix4f().scale(2f, 0.1f, 2f)).submit();
        if (phase < 2) renderer.lights().add(LIGHT_X, LIGHT_Y, LIGHT_Z, LIGHT_RADIUS, 0xFFFFE0C0, 600f, 0f);
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, W, H, target.getFboId()).view().set(EYE_X, EYE_Y, EYE_Z, look, projection);
            stage.fire();
        }
        CgFrameRing.endFrame();
        if (frame % PHASE == 0) target.captureToFile(ctx.getOutputDir(), NAMES[phase] + ".png");
        target.unbind();
        if (frame == 3 * PHASE) {
            ((CgVar.Bool) CgVars.get(VOLUME)).set(true);
            report(ctx);
            running = false;
        }
    }

    private void report(HarnessContext ctx) {
        String failure = null;
        float[][] at = new float[3][4];
        int[][] shown = new int[3][];
        try {
            for (int k = 0; k < 3; k++) {
                BufferedImage image = ImageIO.read(new File(ctx.getOutputDir(), NAMES[k] + ".png"));
                at[k][0] = patch(image, OPEN);
                at[k][1] = patch(image, BEHIND);
                at[k][2] = patch(image, FACING);
                at[k][3] = patch(image, AWAY);
                shown[k] = texel(image, HANDED);
            }
        } catch (IOException e) {
            failure = "the captures did not read back: " + e.getMessage();
        }
        float open = at[0][0] - at[1][0], behind = at[0][1] - at[1][1];
        String readings = String.format("the pane in the open %.1f with the volume, %.1f without, %.1f dark; behind the wall"
                + " %.1f, %.1f, %.1f; the Surface panes facing the light %.1f and away %.1f with the volume, %.1f and %.1f"
                + " without", at[0][0], at[1][0], at[2][0], at[0][1], at[1][1], at[2][1], at[0][2], at[0][3], at[1][2], at[1][3]);
        if (failure == null && open < 8f) failure = "the volume lights the pane in the open too little: " + readings;
        else if (failure == null && behind > 0.15f * open + 2f) failure = "the volume lights the pane behind the wall: " + readings;
        else if (failure == null && at[0][2] - at[0][3] < 8f) failure = "the volume's direction lights the Surface pane facing"
                + " the light no more than the one facing away: " + readings;
        if (failure == null) {
            double dx = HANDED[0] - LIGHT_X, dy = HANDED[1] - LIGHT_Y, dz = HANDED[2] - LIGHT_Z;
            int[] want = {255, (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz) / 16.0 * 255.0),
                    Math.round(LIGHT_RADIUS / 32f * 255f)};
            readings += String.format("; the handed pane %s lit, %s dark, wanted %s", rgb(shown[0]), rgb(shown[2]), rgb(want));
            for (int c = 0; c < 3 && failure == null; c++) {
                if (Math.abs(shown[0][c] - want[c]) > 6 || Math.abs(shown[1][c] - want[c]) > 6 || shown[2][c] > 6) {
                    failure = "the pane handed the light does not show it: " + readings;
                }
            }
        }
        boolean glErrors = GlErrorChecker.checkAndLog("lights-volume");
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) failure = "GL errors, logged above";
        else if (validation > 0) failure = validation + " Vulkan validation errors, logged above";
        else if ("recording".equals(on)) failure = null;
        if (failure != null) System.out.println("[lights-volume] FAIL on " + on + ": " + failure);
        else System.out.println("[lights-volume] PASS on " + on + ": " + readings);
    }

    private static String rgb(int[] c) {
        return "(" + c[0] + ", " + c[1] + ", " + c[2] + ")";
    }

    /** The red, green and blue where {@code p} lands in the capture. */
    private int[] texel(BufferedImage image, double[] p) {
        int[] xy = screen(p);
        int rgb = image.getRGB(xy[0], xy[1]);
        return new int[]{rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF};
    }

    /** Where {@code p} lands in the capture. */
    private int[] screen(double[] p) {
        Vector4f clip = new Vector4f((float) (p[0] - EYE_X), (float) (p[1] - EYE_Y), (float) (p[2] - EYE_Z), 1f);
        look.transform(clip);
        projection.transform(clip);
        int x = (int) ((clip.x / clip.w * 0.5f + 0.5f) * W), y = H - 1 - (int) ((clip.y / clip.w * 0.5f + 0.5f) * H);
        return new int[]{Math.max(0, Math.min(W - 1, x)), Math.max(0, Math.min(H - 1, y))};
    }

    /** The mean of a 7x7 patch's channels round where {@code p} lands in the capture. */
    private float patch(BufferedImage image, double[] p) {
        Vector4f clip = new Vector4f((float) (p[0] - EYE_X), (float) (p[1] - EYE_Y), (float) (p[2] - EYE_Z), 1f);
        look.transform(clip);
        projection.transform(clip);
        int x = (int) ((clip.x / clip.w * 0.5f + 0.5f) * W), y = H - 1 - (int) ((clip.y / clip.w * 0.5f + 0.5f) * H);
        float sum = 0f;
        int n = 0;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                int rgb = image.getRGB(Math.max(0, Math.min(W - 1, x + dx)), Math.max(0, Math.min(H - 1, y + dy)));
                sum += ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3f;
                n++;
            }
        }
        return sum / n;
    }

    @Override
    public void dispose() {
        if (target != null) target.delete();
        CgPlatform.provide(CgLevels.SERVICE, null);
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }

    /** lights-shadow's: solid below {@link #GROUND}; a wall a block thick at {@link #CX}, three high, nine long. */
    private static final class World implements CgWorldQuery {

        private static boolean solid(int x, int y, int z) {
            return y < GROUND || x == CX && y < GROUND + 3 && z >= CZ - 4 && z <= CZ + 4;
        }

        @Override
        public int collisionBoxes(int x, int y, int z, float[] out) {
            if (!solid(x, y, z) || out.length < 6) return solid(x, y, z) ? 1 : 0;
            out[0] = out[1] = out[2] = 0f;
            out[3] = out[4] = out[5] = 1f;
            return 1;
        }

        @Override public float collisionTop(int x, int y, int z) { return solid(x, y, z) ? 1f : Float.NaN; }
        @Override public float collisionBottom(int x, int y, int z) { return solid(x, y, z) ? 0f : Float.NaN; }
        @Override public int light(int x, int y, int z) { return 0; }
        @Override public int surface(int x, int y, int z) { return solid(x, y, z) ? SURFACE_STONE : SURFACE_NONE; }
        @Override public float hardness(int x, int y, int z) { return solid(x, y, z) ? 1.5f : Float.NaN; }
        @Override public int lightEmission(int x, int y, int z) { return 0; }
        @Override public int tint(int x, int y, int z) { return 0; }
        @Override public int biomeColor(int x, int y, int z, int kind) { return 0xFF808080; }
        @Override public int precipitation(int x, int y, int z) { return PRECIPITATION_NONE; }
        @Override public int surfaceY(int x, int z, int kind) { return GROUND; }
        @Override public boolean spriteRect(int x, int y, int z, float[] out) { return false; }
        @Override public float fluidHeight(int x, int y, int z) { return Float.NaN; }
        @Override public int fluidKind(int x, int y, int z) { return FLUID_NONE; }
        @Override public int mapColor(int x, int y, int z) { return solid(x, y, z) ? 0xFF707070 : 0; }
        @Override public boolean loaded(int x, int z) { return true; }
        @Override public int minY() { return 0; }
        @Override public int maxY() { return 256; }
        @Override public int seaLevel() { return 63; }
        @Override public int levelEpoch() { return 1; }
    }
}
