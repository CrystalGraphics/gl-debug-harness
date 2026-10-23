package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.demo.CgShadowsDemo;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.IntConsumer;

/**
 * The world shadows' cost where it is worst: {@link CgShadowsDemo} on the host's ground under a morning sun, the camera
 * four blocks over the floating cube's shadow so the ground fills the window, as a player standing over it sees it.
 * Profile it for the shadow passes' GPU time ({@code world.lightsSample}, {@code world.lightsShade},
 * {@code world.lightsTemporal}, {@code world.lightsDenoise}, {@code world.lightsApply}). Scene id
 * {@code lights-shadows-bench}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-shadows-bench --width=1920 --height=1080" \
 *     -Dcrystalgraphics.harness.profile=300 -Dcrystalgraphics.trace.channels=crystalgraphics,gpu
 * ... -Dcrystalgraphics.harness.shadowsBench.entities=64      // animals on the ground round the camera, 0 by default
 * ... -Dcrystalgraphics.harness.shadowsBench.eye=1.62          // the eye's height over the ground: a player's
 * ... -Dcrystalgraphics.harness.shadowsBench.sun=0.21          // the sun's celestial angle: /time set 1000's low morning sun
 * }</pre>
 */
public final class CgLightsShadowsBenchScene implements InteractiveSceneLifecycle {

    private static final int GROUND = 64;
    /** Animals standing round the camera, as a superflat world spawns them: what the shadow rays test as capsules. */
    private static final int ENTITIES = Integer.getInteger("crystalgraphics.harness.shadowsBench.entities", 0);
    /** The sun's celestial angle: morning, its shadows beside what casts them. */
    private static final float SUN_ANGLE = Float.parseFloat(System.getProperty("crystalgraphics.harness.shadowsBench.sun", "0.07"));
    /** The demo's middle, and the eye: over the floating cube's shadow, west of the plaza (CgShadowsDemo's STATION). */
    private static final double X = 0, Z = 0, EYE_X = X - 14, EYE_Z = Z + 3;
    private static final double EYE_Y = GROUND + Double.parseDouble(System.getProperty("crystalgraphics.harness.shadowsBench.eye", "4"));

    private final CgShadowsDemo demo = new CgShadowsDemo();
    private final Matrix4f view = new Matrix4f().lookAt(0f, 0f, 0f, 0f, (float) (GROUND - EYE_Y), (float) (Z - EYE_Z), 0f, 1f, 0f);
    private final Matrix4f projection = new Matrix4f(), ground = new Matrix4f();
    private CgMaterial floor;
    private CgTexture2D lightmap;

    @Override
    public void init(HarnessContext ctx) {
        CgPlatform.provide(CgLevels.SERVICE, CgLevels.single(new Flat(), ENTITIES > 0 ? new Herd() : CgEntityQuery.NONE));
        CgWorldRenderer.get().install();
        floor = CgMaterial.load("assets/harness/shader/bloom_wall.shader");
        demo.prepare();
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
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        projection.setPerspective((float) Math.toRadians(70), (float) w / h, 0.05f, 400f);
        // The host's ground, drawn by the host as Minecraft draws its terrain.
        CgPassConstants host = new CgPassConstants();
        host.view.set(view);
        host.projection.set(projection);
        host.resolution(w, h).time(CgFrameClock.seconds()).depth(CgGL.isDepthReversed(), CgGL.isDepthZeroToOne()).cameraFromView();
        ground.translation((float) (X - EYE_X), (float) (GROUND - 0.5 - EYE_Y), (float) (Z - EYE_Z)).scale(400f, 1f, 400f);
        CgMesh cube = CgMeshShapes.cube();
        ObjectDraws.draw(host, floor, cube, 1, (i, data, at) -> ObjectDraws.object(data, at, ground, 1f, 1f, 1f, 1f));
        if (demo.warmed()) demo.submit(CgWorldRenderer.get(), X, GROUND, Z, frame.getElapsedTime());
        for (CgRenderStage stage : new CgRenderStage[]{CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT}) {
            stage.host().set(0f, w, h, ctx.getTargetFramebuffer()).view().set(EYE_X, EYE_Y, EYE_Z, view, projection);
            stage.host().environment().sun(SUN_ANGLE, 0, 0f, 1f);
            stage.host().textures().importedLightmap(lightmap.getId(), 16, 16);
            stage.fire();
        }
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning() { return true; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return false; }

    /** {@link #ENTITIES} cows, 0.9 by 1.4, on a golden-angle disc 30 blocks round the eye. */
    private static final class Herd implements CgEntityQuery {

        private static double x(int id) {
            return EYE_X + Math.sqrt((id + 0.5) / ENTITIES) * 30 * Math.cos(id * 2.39996);
        }

        private static double z(int id) {
            return EYE_Z + Math.sqrt((id + 0.5) / ENTITIES) * 30 * Math.sin(id * 2.39996);
        }

        @Override public int localPlayer() { return -1; }
        @Override public int cameraEntity() { return -1; }

        @Override
        public boolean pose(int id, float partialTick, double[] out) {
            if (id < 0 || id >= ENTITIES) return false;
            out[X] = x(id);
            out[Y] = GROUND;
            out[Z] = z(id);
            out[WIDTH] = 0.9;
            out[HEIGHT] = 1.4;
            out[EYE_HEIGHT] = 1.3;
            return true;
        }

        @Override public int flags(int id) { return id >= 0 && id < ENTITIES ? ALIVE | LIVING | ON_GROUND : 0; }
        @Override public int kind(int id) { return KIND_ANIMAL; }

        @Override
        public boolean box(int id, float partialTick, CgBoxSink out) {
            return false;
        }

        @Override public int parent(int id) { return id; }
        @Override public int rootVehicle(int id) { return id; }

        @Override
        public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, IntConsumer visitor) {
            for (int id = 0; id < ENTITIES; id++) {
                if (x(id) >= minX && x(id) <= maxX && z(id) >= minZ && z(id) <= maxZ && GROUND <= maxY && GROUND + 1.4 >= minY) {
                    visitor.accept(id);
                }
            }
        }
    }

    /** Solid below {@link #GROUND}, full sky light above it. */
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
