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
import com.crystalgraphics.render.world.CgWorldLights;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * The world lights' shadows' gate (plan render-shadows): a floor and a wall of blocks behind {@code CgWorldQuery}, one
 * light west of the wall. Drawn shadowed, then with {@code shadowed(false)}, then with no light, each long enough for
 * the temporal denoiser to settle. The light added on the floor east of the wall, behind it, must be nearly gone; on
 * the floor as far from the light in the open, the same either way.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-shadow"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-shadow --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [lights-shadow] PASS} or {@code FAIL}; on Vulkan a validation error fails it. Its three captures land
 * in its output directory.</p>
 */
public class CgLightsShadowScene implements InteractiveSceneLifecycle {

    /** Frames each phase holds: the window fills during the first, the history settles in each. */
    private static final int PHASE = 240, W = 320, H = 240;
    private static final int CX = -1000, CZ = 2000, GROUND = 64;
    private static final double LIGHT_X = CX - 3.5, LIGHT_Y = GROUND + 2.5, LIGHT_Z = CZ + 0.5;
    /** On the floor 8 blocks from the light across, behind the wall; and 8 blocks along, in the open. */
    private static final double[] BEHIND = {CX + 4.5, GROUND, CZ + 0.5}, OPEN = {LIGHT_X, GROUND, CZ + 8.5};
    private static final double EYE_X = CX + 0.5, EYE_Y = GROUND + 16, EYE_Z = CZ + 14;
    /** How much of the unshadowed light may remain behind the wall, and how far the open floor may differ. */
    private static final float LEFT_BEHIND = 0.15f, OPEN_SPREAD = 0.2f;

    private final World world = new World();
    private final Matrix4f look = new Matrix4f().lookAt(0f, 0f, 0f, (float) (CX + 0.5 - EYE_X), (float) (GROUND - EYE_Y),
            (float) (CZ + 3 - EYE_Z), 0f, 1f, 0f);
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), (float) W / H, 0.05f, 200f);
    private HarnessFboHelper target;
    private CgMaterial grey;
    private int frame;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        CgPlatform.provide(CgLevels.SERVICE, CgLevels.single(world, CgEntityQuery.NONE));
        CgWorldRenderer.get().install();
        grey = CgMaterial.load("assets/harness/shader/bloom_wall.shader");
        target = HarnessFboHelper.create(W, H, true);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        int phase = (frame - 1) / PHASE;
        CgWorldRenderer renderer = CgWorldRenderer.get();
        target.bind();
        target.clear(0.05f, 0.05f, 0.07f, 1f);
        CgMesh cube = CgMeshShapes.cube();
        renderer.draw(cube, grey).at(CX + 0.5, GROUND - 0.5, CZ + 0.5).transform(new Matrix4f().scale(40f, 1f, 40f)).submit();
        renderer.draw(cube, grey).at(CX + 0.5, GROUND + 1.5, CZ + 0.5).transform(new Matrix4f().scale(1f, 3f, 9f)).submit();
        if (phase < 2) {
            CgWorldLights lights = renderer.lights().add(LIGHT_X, LIGHT_Y, LIGHT_Z, 16f, 0xFFFFE0C0, 600f, 0f);
            if (phase == 1) lights.shadowed(false);
        }
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, W, H, target.getFboId()).view().set(EYE_X, EYE_Y, EYE_Z, look, projection);
            stage.fire();
        }
        CgFrameRing.endFrame();
        if (frame % PHASE == 0) target.captureToFile(ctx.getOutputDir(), NAMES[phase] + ".png");
        target.unbind();
        if (frame == 3 * PHASE) {
            report(ctx);
            running = false;
        }
    }

    private static final String[] NAMES = {"shadowed", "unshadowed", "dark"};

    private void report(HarnessContext ctx) {
        String failure = null;
        float[][] at = new float[3][2];
        try {
            for (int k = 0; k < 3; k++) {
                BufferedImage image = ImageIO.read(new File(ctx.getOutputDir(), NAMES[k] + ".png"));
                at[k][0] = patch(image, BEHIND);
                at[k][1] = patch(image, OPEN);
            }
        } catch (IOException e) {
            failure = "the captures did not read back: " + e.getMessage();
        }
        float behindShadowed = at[0][0] - at[2][0], behindOpen = at[1][0] - at[2][0];
        float openShadowed = at[0][1] - at[2][1], openUnshadowed = at[1][1] - at[2][1];
        if (failure == null && (behindOpen < 10f || openUnshadowed < 10f)) {
            failure = "the light adds too little to judge: " + behindOpen + " behind the wall, " + openUnshadowed
                    + " in the open (shadowed, unshadowed, dark: behind " + at[0][0] + ", " + at[1][0] + ", " + at[2][0]
                    + "; open " + at[0][1] + ", " + at[1][1] + ", " + at[2][1] + ")";
        } else if (failure == null && behindShadowed > LEFT_BEHIND * behindOpen) {
            failure = "behind the wall the shadowed light adds " + behindShadowed + " of the unshadowed " + behindOpen;
        } else if (failure == null && Math.abs(openShadowed - openUnshadowed) > OPEN_SPREAD * openUnshadowed + 3f) {
            failure = "in the open the shadowed light adds " + openShadowed + ", the unshadowed " + openUnshadowed;
        }
        boolean glErrors = GlErrorChecker.checkAndLog("lights-shadow");
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) failure = "GL errors, logged above";
        else if (validation > 0) failure = validation + " Vulkan validation errors, logged above";
        else if ("recording".equals(on)) failure = null;
        if (failure != null) {
            System.out.println("[lights-shadow] FAIL on " + on + ": " + failure);
        } else if ("recording".equals(on)) {
            System.out.println("[lights-shadow] PASS on recording: every command validated; a recording device draws nothing to compare");
        } else {
            System.out.printf("[lights-shadow] PASS on %s: behind the wall %.1f of %.1f remains; in the open %.1f against %.1f%n",
                    on, behindShadowed, behindOpen, openShadowed, openUnshadowed);
        }
    }

    /** The mean of a 7x7 patch's channels round where {@code p} lands in a top-down capture. */
    private float patch(BufferedImage image, double[] p) {
        Vector4f clip = new Vector4f((float) (p[0] - EYE_X), (float) (p[1] - EYE_Y), (float) (p[2] - EYE_Z), 1f);
        look.transform(clip);
        projection.transform(clip);
        int x = (int) ((clip.x / clip.w * 0.5f + 0.5f) * W), y = H - 1 - (int) ((clip.y / clip.w * 0.5f + 0.5f) * H);
        float sum = 0f;
        int n = 0;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                int px = Math.max(0, Math.min(W - 1, x + dx)), py = Math.max(0, Math.min(H - 1, y + dy));
                int rgb = image.getRGB(px, py);
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

    /** Solid below {@link #GROUND}; a wall a block thick at {@link #CX}, three high, nine long. Dark: no sky, no block light. */
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
