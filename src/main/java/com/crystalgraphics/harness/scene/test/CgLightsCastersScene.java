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
 * Our meshes as shadow casters through their distance fields (plan render-shadows §12, D2), seen from above as the
 * screen trace failed them: a floating box over the host's ground and a ball over a slab of ours, under a low sun. The
 * middle of each shadow must be dark, not only its edge; ground beside the box on the sun's and the camera's side must
 * not change, where a screen-space thickness smeared the shadow toward the camera. Each against the same frames with
 * no sun. Then under a low sun from the side, its edge across the ball's face, the camera turns half a degree: the ball's
 * shaded share must hold, where normals rebuilt from depth and held to an axis flipped whole patches of it.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-casters"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-casters --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [lights-casters] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgLightsCastersScene implements InteractiveSceneLifecycle {

    private static final int PHASE = 480, W = 320, H = 240;
    private static final int CX = -1000, CZ = 2000, GROUND = 64;
    /** The sun's celestial angle: a morning sun, its shadows long enough to fall beside what casts them. */
    /** A low morning sun, from the side of the ball away from the camera: its edge crosses the face the camera sees. */
    private static final float SUN_ANGLE = 0.07f, SIDE_SUN = 0.8f;
    private static final double HEIGHT = 3, SLAB_TOP = 0.25;
    private static final double[] BOX = {CX + 0.5, GROUND + HEIGHT, CZ + 0.5}, BALL = {CX + 10.5, GROUND + HEIGHT, CZ + 0.5};
    private static final double EYE_X = CX + 5, EYE_Y = GROUND + 14, EYE_Z = CZ + 9;
    private static final String[] NAMES = {"sun", "no-sun", "side", "side-turned"};

    private final World world = new World();
    private final Matrix4f look = new Matrix4f().lookAt(0f, 0f, 0f, (float) (CX + 5 - EYE_X), (float) (GROUND - EYE_Y),
            (float) (CZ - EYE_Z), 0f, 1f, 0f);
    private final Matrix4f turned = new Matrix4f().rotateY((float) Math.toRadians(0.5)).mul(look);
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), (float) W / H, 0.05f, 200f);
    private final float[] sun = new float[3];
    private double[] boxShadow, ballShadow, beside;
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
        CgMesh cube = CgMeshShapes.cube(), ball = CgMeshShapes.sphere(24, 32);
        CgPassConstants host = new CgPassConstants();
        host.view.set(look);
        host.projection.set(projection);
        host.resolution(W, H).time(CgFrameClock.seconds()).depth(CgGL.isDepthReversed(), CgGL.isDepthZeroToOne())
                .cameraFromView();
        Matrix4f ground = new Matrix4f().translation((float) (CX + 5 - EYE_X), (float) (GROUND - 0.5 - EYE_Y),
                (float) (CZ - EYE_Z)).scale(60f, 1f, 60f);
        ObjectDraws.draw(host, grey, cube, 1, (i, data, at) -> ObjectDraws.object(data, at, ground, 1f, 1f, 1f, 1f));
        renderer.draw(cube, grey).at(BOX[0], BOX[1], BOX[2]).transform(new Matrix4f().scale(2f)).submit();
        renderer.draw(cube, grey).at(BALL[0], GROUND + SLAB_TOP / 2, BALL[2]).transform(new Matrix4f().scale(10f, (float) SLAB_TOP, 10f))
                .submit();
        renderer.draw(ball, grey).at(BALL[0], BALL[1], BALL[2]).submit();
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, W, H, target.getFboId()).view().set(EYE_X, EYE_Y, EYE_Z, phase == 3 ? turned : look, projection);
            stage.host().environment().sun(phase == 1 ? Float.NaN : phase >= 2 ? SIDE_SUN : SUN_ANGLE, 0, 0f, 1f);
            stage.host().textures().importedLightmap(lightmap.getId(), 16, 16);
            if (boxShadow == null && phase == 0) place(stage);
            stage.fire();
        }
        CgFrameRing.endFrame();
        if (frame % PHASE == 0) target.captureToFile(ctx.getOutputDir(), NAMES[phase] + ".png");
        target.unbind();
        if (frame == 4 * PHASE) {
            report(ctx);
            running = false;
        }
    }

    /** Where the shadows fall, from the sun the stage will draw by: each caster's centre along it to the surface below. */
    private void place(CgRenderStage stage) {
        stage.host().environment().sunDirection(sun);
        double down = HEIGHT / sun[1], downOnSlab = (HEIGHT - SLAB_TOP) / sun[1];
        boxShadow = new double[]{BOX[0] - sun[0] * down, GROUND, BOX[2] - sun[2] * down};
        ballShadow = new double[]{BALL[0] - sun[0] * downOnSlab, GROUND + SLAB_TOP, BALL[2] - sun[2] * downOnSlab};
        // Under the box, toward the sun and the camera: lit, and where a screen-space thickness smeared the shadow.
        double sx = sun[0], sz = sun[2], len = Math.max(1e-6, Math.hypot(sx, sz));
        beside = new double[]{BOX[0] + sx / len * 2.5, GROUND, BOX[2] + sz / len * 2.5 + 1.5};
    }

    private void report(HarnessContext ctx) {
        String failure = null;
        float[][] at = new float[2][3];
        float shadedNow = 0f, shadedTurned = 0f;
        try {
            for (int k = 0; k < 2; k++) {
                BufferedImage image = ImageIO.read(new File(ctx.getOutputDir(), NAMES[k] + ".png"));
                at[k][0] = patch(image, boxShadow);
                at[k][1] = patch(image, ballShadow);
                at[k][2] = patch(image, beside);
            }
            shadedNow = shaded(ImageIO.read(new File(ctx.getOutputDir(), NAMES[2] + ".png")), look);
            shadedTurned = shaded(ImageIO.read(new File(ctx.getOutputDir(), NAMES[3] + ".png")), turned);
        } catch (IOException e) {
            failure = "the captures did not read back: " + e.getMessage();
        }
        String readings = String.format("box's shadow %.1f vs %.1f, ball's on our slab %.1f vs %.1f, beside the box %.1f vs %.1f,"
                + " the ball shaded %.1f%% then %.1f%% turned", at[0][0], at[1][0], at[0][1], at[1][1], at[0][2], at[1][2],
                shadedNow * 100f, shadedTurned * 100f);
        if (failure == null && at[0][0] > 0.8f * at[1][0]) failure = "the box's shadow is not dark through its middle: " + readings;
        else if (failure == null && at[0][1] > 0.8f * at[1][1]) failure = "the ball's shadow on our slab is not dark: " + readings;
        else if (failure == null && Math.abs(at[0][2] - at[1][2]) > 2f) failure = "the ground beside the box changed: " + readings;
        else if (failure == null && (shadedNow < 0.1f || shadedNow > 0.9f)) failure = "the side sun's edge is not across the ball: " + readings;
        else if (failure == null && Math.abs(shadedNow - shadedTurned) > 0.04f) failure = "the ball's shading jumped as the camera turned: " + readings;
        boolean glErrors = GlErrorChecker.checkAndLog("lights-casters");
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) failure = "GL errors, logged above";
        else if (validation > 0) failure = validation + " Vulkan validation errors, logged above";
        if ("recording".equals(on) && !glErrors && validation == 0) {
            System.out.println("[lights-casters] PASS on recording: every command validated; a recording device draws nothing to compare");
        } else if (failure != null) {
            System.out.println("[lights-casters] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[lights-casters] PASS on " + on + ": " + readings);
        }
    }

    /**
     * The share of the ball's pixels, its disc on screen inset a little, below 80% of its brightest: a sun shadow, 43% of
     * the light in linear terms, is about 65% of it in the capture's sRGB.
     */
    private float shaded(BufferedImage image, Matrix4f view) {
        Vector4f centre = new Vector4f((float) (BALL[0] - EYE_X), (float) (BALL[1] - EYE_Y), (float) (BALL[2] - EYE_Z), 1f);
        view.transform(centre);
        float depth = -centre.z;
        projection.transform(centre);
        float cx = (centre.x / centre.w * 0.5f + 0.5f) * W, cy = H - 1 - (centre.y / centre.w * 0.5f + 0.5f) * H;
        float r = 0.8f * H / (2f * depth * (float) Math.tan(Math.toRadians(35)));
        float brightest = 0f;
        int n = 0, dark = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int y = (int) (cy - r); y <= cy + r; y++) {
                for (int x = (int) (cx - r); x <= cx + r; x++) {
                    if (x < 0 || y < 0 || x >= W || y >= H || (x - cx) * (x - cx) + (y - cy) * (y - cy) > r * r) continue;
                    int rgb = image.getRGB(x, y);
                    float v = ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3f;
                    if (pass == 0) {
                        brightest = Math.max(brightest, v);
                    } else {
                        n++;
                        if (v < 0.8f * brightest) dark++;
                    }
                }
            }
        }
        return n == 0 ? 0f : dark / (float) n;
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

    /** Solid below {@link #GROUND}; full sky light above it. */
    private static final class World implements CgWorldQuery {

        private static boolean solid(int x, int y, int z) {
            return y < GROUND;
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
