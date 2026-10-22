package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
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
 * The sun's shadows' gate (plan render-shadows §11, M2): Minecraft's ground drawn by the host, before our stages, and
 * two boxes of ours, at noon. One floats over the ground: its shadow must darken the ground under it, our object casting
 * on Minecraft's world. One sits under a roof of blocks above the view, off screen, which only the voxel world holds
 * (a block on screen is in the host's depth): its top must darken, blocks casting on our object. Ground in the open and the floating box's own top must not change. Each against
 * the same frames with no sun.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-sun"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-sun --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [lights-sun] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgLightsSunScene implements InteractiveSceneLifecycle {

    private static final int PHASE = 240, W = 320, H = 240;
    private static final int CX = -1000, CZ = 2000, GROUND = 64, ROOF = GROUND + 30;
    private static final double EYE_X = CX + 4, EYE_Y = GROUND + 10, EYE_Z = CZ + 12;
    /** Ground under the floating box, just inside its footprint's near edge; ground in the open; the two box tops. */
    private static final double[] UNDER = {CX + 0.5, GROUND, CZ + 1.1}, OPEN = {CX - 4, GROUND, CZ + 5},
            ROOFED_TOP = {CX + 10.5, GROUND + 1.5, CZ + 0.5}, FLOATING_TOP = {CX + 0.5, GROUND + 3.5, CZ + 0.5};
    private static final String[] NAMES = {"sun", "no-sun"};

    private final World world = new World();
    private final Matrix4f look = new Matrix4f().lookAt(0f, 0f, 0f, (float) (CX + 5 - EYE_X), (float) (GROUND - EYE_Y),
            (float) (CZ - EYE_Z), 0f, 1f, 0f);
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), (float) W / H, 0.05f, 200f);
    private HarnessFboHelper target;
    private CgMaterial grey;
    private CgTexture2D lightmap;
    private int frame;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        CgPlatform.provide(CgLevels.SERVICE, CgLevels.single(world, CgEntityQuery.NONE));
        CgWorldRenderer.get().install();
        grey = CgMaterial.load("assets/harness/shader/bloom_wall.shader");
        target = HarnessFboHelper.create(W, H, true);
        // Minecraft's lightmap, shaped: brighter with block light and with sky light, a sky light of 6 darker than 15.
        ByteBuffer texels = ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.nativeOrder());
        for (int sky = 0; sky < 16; sky++) {
            for (int block = 0; block < 16; block++) {
                int v = Math.round(255f * (0.05f + 0.95f * Math.max(block, sky) / 15f));
                texels.put((byte) v).put((byte) v).put((byte) v).put((byte) 255);
            }
        }
        texels.flip();
        lightmap = CgTexture2D.createEmpty(16, 16, CgTextureSpec.RGBA8_LINEAR);
        lightmap.uploadRegion(0, 0, 0, 16, 16, texels, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        int phase = (frame - 1) / PHASE;
        CgWorldRenderer renderer = CgWorldRenderer.get();
        target.bind();
        target.clear(0.05f, 0.05f, 0.07f, 1f);
        CgMesh cube = CgMeshShapes.cube();
        // The host's ground, drawn before our stages as Minecraft's world is: it is in the host's depth.
        CgPassConstants host = new CgPassConstants();
        host.view.set(look);
        host.projection.set(projection);
        host.resolution(W, H).time(CgFrameClock.seconds()).depth(CgGL.isDepthReversed(), CgGL.isDepthZeroToOne())
                .cameraFromView();
        Matrix4f ground = new Matrix4f().translation((float) (CX + 0.5 - EYE_X), (float) (GROUND - 0.5 - EYE_Y),
                (float) (CZ + 0.5 - EYE_Z)).scale(40f, 1f, 40f);
        ObjectDraws.draw(host, grey, cube, 1, (i, data, at) -> ObjectDraws.object(data, at, ground, 1f, 1f, 1f, 1f));
        // Ours: one floating over the ground, one under the roof of blocks.
        renderer.draw(cube, grey).at(CX + 0.5, GROUND + 2.5, CZ + 0.5).transform(new Matrix4f().scale(2f)).submit();
        renderer.draw(cube, grey).at(CX + 10.5, GROUND + 1, CZ + 0.5).submit();
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, W, H, target.getFboId()).view().set(EYE_X, EYE_Y, EYE_Z, look, projection);
            stage.host().environment().sun(phase == 0 ? 0f : Float.NaN, 0, 0f, 1f);
            stage.host().textures().importedLightmap(lightmap.getId(), 16, 16);
            stage.fire();
        }
        CgFrameRing.endFrame();
        if (frame % PHASE == 0) target.captureToFile(ctx.getOutputDir(), NAMES[phase] + ".png");
        target.unbind();
        if (frame == 2 * PHASE) {
            report(ctx);
            running = false;
        }
    }

    private void report(HarnessContext ctx) {
        String failure = null;
        float[][] at = new float[2][4];
        try {
            for (int k = 0; k < 2; k++) {
                BufferedImage image = ImageIO.read(new File(ctx.getOutputDir(), NAMES[k] + ".png"));
                at[k][0] = patch(image, UNDER);
                at[k][1] = patch(image, OPEN);
                at[k][2] = patch(image, ROOFED_TOP);
                at[k][3] = patch(image, FLOATING_TOP);
            }
        } catch (IOException e) {
            failure = "the captures did not read back: " + e.getMessage();
        }
        String readings = String.format("under %.1f vs %.1f, open %.1f vs %.1f, roofed top %.1f vs %.1f, floating top %.1f vs %.1f",
                at[0][0], at[1][0], at[0][1], at[1][1], at[0][2], at[1][2], at[0][3], at[1][3]);
        if (failure == null && at[0][0] > 0.8f * at[1][0]) failure = "our box's shadow does not darken the ground: " + readings;
        else if (failure == null && at[0][2] > 0.8f * at[1][2]) failure = "the roof of blocks does not darken our box: " + readings;
        else if (failure == null && Math.abs(at[0][1] - at[1][1]) > 2f) failure = "the open ground changed: " + readings;
        else if (failure == null && Math.abs(at[0][3] - at[1][3]) > 3f) failure = "the sunlit box top changed: " + readings;
        boolean glErrors = GlErrorChecker.checkAndLog("lights-sun");
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) failure = "GL errors, logged above";
        else if (validation > 0) failure = validation + " Vulkan validation errors, logged above";
        if ("recording".equals(on) && !glErrors && validation == 0) {
            System.out.println("[lights-sun] PASS on recording: every command validated; a recording device draws nothing to compare");
        } else if (failure != null) {
            System.out.println("[lights-sun] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[lights-sun] PASS on " + on + ": " + readings);
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
        if (lightmap != null) lightmap.delete();
        CgPlatform.provide(CgLevels.SERVICE, null);
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }

    /** Solid below {@link #GROUND} and a roof at {@link #ROOF} over the second box; full sky light above the ground. */
    private static final class World implements CgWorldQuery {

        private static boolean solid(int x, int y, int z) {
            return y < GROUND || y == ROOF && x >= CX + 8 && x <= CX + 12 && z >= CZ - 2 && z <= CZ + 2;
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
        @Override public int light(int x, int y, int z) { return solid(x, y, z) ? 0 : 15 << 4; }
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
