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
import com.crystalgraphics.render.world.CgWorldHaze;
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
 * The world lights in the air's gate (plan clustered-lights-decals L6): a floor, a wall of blocks 14 high and a light
 * west of it, all in a haze ({@code CgWorldHaze}). Drawn with the air lit, then not ({@code crystalgraphics.world.lightsAir}),
 * then with no light. Looking down past the light's side of the wall the haze must glow; past the other side, where the
 * wall shadows the air, it must not.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-air"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-air --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [lights-air] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgLightsAirScene implements InteractiveSceneLifecycle {

    private static final int PHASE = 120, W = 320, H = 240;
    private static final int CX = -1000, CZ = 2000, GROUND = 64;
    private static final double LIGHT_X = CX - 3.5, LIGHT_Y = GROUND + 2.5, LIGHT_Z = CZ + 0.5;
    /** Where the eye looks down past the light's side of the wall, and past the other side. */
    private static final double[] OPEN = {CX - 3.5, GROUND, CZ + 4}, BEHIND = {CX + 4.5, GROUND, CZ + 4};
    private static final int WALL = 14;
    private static final double EYE_X = CX + 0.5, EYE_Y = GROUND + 16, EYE_Z = CZ + 14;
    private static final String AIR = "crystalgraphics.world.lightsAir";
    private static final String[] NAMES = {"air", "no-air", "dark"};

    private final World world = new World();
    private final Matrix4f look = new Matrix4f().lookAt(0f, 0f, 0f, (float) (CX + 0.5 - EYE_X), (float) (GROUND - EYE_Y),
            (float) (CZ + 3 - EYE_Z), 0f, 1f, 0f);
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), (float) W / H, 0.05f, 200f);
    private HarnessFboHelper target;
    private CgMaterial grey;
    private final CgWorldHaze haze = new CgWorldHaze().box(CX - 20, GROUND, CZ - 20, CX + 20, GROUND + 20, CZ + 20)
            .density(0.05f, 2f);
    private int frame;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        CgPlatform.provide(CgLevels.SERVICE, CgLevels.single(world, CgEntityQuery.NONE));
        CgWorldRenderer.get().install();
        grey = CgMaterial.load("assets/harness/shader/bloom_wall.shader");
        CgWorldRenderer.get().media().add(haze);
        target = HarnessFboHelper.create(W, H, true);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        int phase = (frame - 1) / PHASE;
        ((CgVar.Bool) CgVars.get(AIR)).set(phase != 1);
        CgWorldRenderer renderer = CgWorldRenderer.get();
        target.bind();
        target.clear(0.05f, 0.05f, 0.07f, 1f);
        CgMesh cube = CgMeshShapes.cube();
        renderer.draw(cube, grey).at(CX + 0.5, GROUND - 0.5, CZ + 0.5).transform(new Matrix4f().scale(40f, 1f, 40f)).submit();
        renderer.draw(cube, grey).at(CX + 0.5, GROUND + WALL / 2.0, CZ + 0.5).transform(new Matrix4f().scale(1f, WALL, 13f))
                .submit();
        if (phase < 2) renderer.lights().add(LIGHT_X, LIGHT_Y, LIGHT_Z, 16f, 0xFFFFE0C0, 600f, 0f);
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, W, H, target.getFboId()).view().set(EYE_X, EYE_Y, EYE_Z, look, projection);
            stage.fire();
        }
        CgFrameRing.endFrame();
        if (frame % PHASE == 0) target.captureToFile(ctx.getOutputDir(), NAMES[phase] + ".png");
        target.unbind();
        if (frame == 3 * PHASE) {
            ((CgVar.Bool) CgVars.get(AIR)).set(true);
            report(ctx);
            running = false;
        }
    }

    private void report(HarnessContext ctx) {
        String failure = null;
        float[][] at = new float[3][2];
        try {
            for (int k = 0; k < 3; k++) {
                BufferedImage image = ImageIO.read(new File(ctx.getOutputDir(), NAMES[k] + ".png"));
                at[k][0] = patch(image, OPEN);
                at[k][1] = patch(image, BEHIND);
            }
        } catch (IOException e) {
            failure = "the captures did not read back: " + e.getMessage();
        }
        float open = at[0][0] - at[1][0], behind = at[0][1] - at[1][1];
        String readings = String.format("past the light's side %.1f with the air lit, %.1f without, %.1f dark; past the"
                + " wall's shadow %.1f, %.1f, %.1f", at[0][0], at[1][0], at[2][0], at[0][1], at[1][1], at[2][1]);
        if (failure == null && open < 8f) failure = "the air by the light glows too little: " + readings;
        else if (failure == null && behind > 0.3f * open + 2f) failure = "the air in the wall's shadow glows: " + readings;
        boolean glErrors = GlErrorChecker.checkAndLog("lights-air");
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) failure = "GL errors, logged above";
        else if (validation > 0) failure = validation + " Vulkan validation errors, logged above";
        else if ("recording".equals(on)) failure = null;
        if (failure != null) System.out.println("[lights-air] FAIL on " + on + ": " + failure);
        else System.out.println("[lights-air] PASS on " + on + ": " + readings);
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
        CgWorldRenderer.get().media().remove(haze);
        CgPlatform.provide(CgLevels.SERVICE, null);
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }

    /** Solid below {@link #GROUND}; a wall a block thick at {@link #CX}, {@link #WALL} high, thirteen long. */
    private static final class World implements CgWorldQuery {

        private static boolean solid(int x, int y, int z) {
            return y < GROUND || x == CX && y < GROUND + WALL && z >= CZ - 6 && z <= CZ + 6;
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
