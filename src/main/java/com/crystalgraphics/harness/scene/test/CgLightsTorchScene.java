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
 * Minecraft's block light shadowed by our objects' gate (plan render-shadows §11, M3): at night, a torch on the host's
 * ground and a box of ours beside it. The ground behind the box must lose the torch's light; ground as far from the
 * torch on its other side must not change. Each against the same frames with no box.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-torch"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-torch --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [lights-torch] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgLightsTorchScene implements InteractiveSceneLifecycle {

    private static final int PHASE = 240, W = 320, H = 240;
    private static final int CX = -1000, CZ = 2000, GROUND = 64, TORCH = 14;
    private static final double EYE_X = CX + 3, EYE_Y = GROUND + 8, EYE_Z = CZ + 10;
    /** Ground behind the box from the torch, and ground as far from it on its other side. */
    private static final double[] BEHIND = {CX + 6, GROUND, CZ + 0.5}, OPEN = {CX - 5.5, GROUND, CZ + 0.5};
    private static final String[] NAMES = {"box", "no-box"};

    private final World world = new World();
    private final Matrix4f look = new Matrix4f().lookAt(0f, 0f, 0f, (float) (CX + 3 - EYE_X), (float) (GROUND - EYE_Y),
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
        // Minecraft's lightmap, shaped: brighter with block light and with sky light.
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
        // Ours: a box two blocks across, three blocks from the torch.
        if (phase == 0) renderer.draw(cube, grey).at(CX + 3.5, GROUND + 1, CZ + 0.5).transform(new Matrix4f().scale(2f)).submit();
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, W, H, target.getFboId()).view().set(EYE_X, EYE_Y, EYE_Z, look, projection);
            stage.host().environment().sun(Float.NaN, 0, 0f, 0f);
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
        float[][] at = new float[2][2];
        try {
            for (int k = 0; k < 2; k++) {
                BufferedImage image = ImageIO.read(new File(ctx.getOutputDir(), NAMES[k] + ".png"));
                at[k][0] = patch(image, BEHIND);
                at[k][1] = patch(image, OPEN);
            }
        } catch (IOException e) {
            failure = "the captures did not read back: " + e.getMessage();
        }
        String readings = String.format("behind %.1f vs %.1f, open %.1f vs %.1f", at[0][0], at[1][0], at[0][1], at[1][1]);
        if (failure == null && at[0][0] > 0.8f * at[1][0]) failure = "our box does not shadow the torch's light: " + readings;
        else if (failure == null && Math.abs(at[0][1] - at[1][1]) > 2f) failure = "the open ground changed: " + readings;
        boolean glErrors = GlErrorChecker.checkAndLog("lights-torch");
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) failure = "GL errors, logged above";
        else if (validation > 0) failure = validation + " Vulkan validation errors, logged above";
        if ("recording".equals(on) && !glErrors && validation == 0) {
            System.out.println("[lights-torch] PASS on recording: every command validated; a recording device draws nothing to compare");
        } else if (failure != null) {
            System.out.println("[lights-torch] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[lights-torch] PASS on " + on + ": " + readings);
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

    /** Solid below {@link #GROUND}; a torch on it at (CX, GROUND, CZ), its light flooded as Minecraft's; no sky light. */
    private static final class World implements CgWorldQuery {

        private static boolean solid(int x, int y, int z) {
            return y < GROUND;
        }

        private static boolean torch(int x, int y, int z) {
            return x == CX && y == GROUND && z == CZ;
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
        @Override public int light(int x, int y, int z) {
            return solid(x, y, z) ? 0 : Math.max(0, TORCH - Math.abs(x - CX) - Math.abs(y - GROUND) - Math.abs(z - CZ));
        }
        @Override public int surface(int x, int y, int z) { return solid(x, y, z) ? SURFACE_STONE : SURFACE_NONE; }
        @Override public float hardness(int x, int y, int z) { return solid(x, y, z) ? 1.5f : Float.NaN; }
        @Override public int lightEmission(int x, int y, int z) { return torch(x, y, z) ? TORCH : 0; }
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
