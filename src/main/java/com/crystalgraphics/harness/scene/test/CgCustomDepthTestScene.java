package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.shader.CgBonePalettes;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * The gate for CustomDepth and CustomStencil ({@code Draw.customDepth}, aaa-milestones OL2): the stencil byte read back
 * from {@link CgFrameKeys#CUSTOM_DEPTH} at five pixels. A cube hidden behind an unmarked wall must still be written (3:
 * tested against CustomDepth alone); a near cube (0x0F, mask 0xFF) in front of a larger far one (0x30, mask 0xF0) must
 * read 0x3F where they overlap (the far one fails depth and replaces its bits, Unreal's partial-mask rule) and 0x30
 * where the far one shows alone; a skinned quad reads 0x80; the empty background 0.
 *
 * <pre>
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=custom-depth"
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=custom-depth --device=vulkan"
 * </pre>
 */
public class CgCustomDepthTestScene implements InteractiveSceneLifecycle {

    private static final int W = 320, H = 240, ARM_AT = 3, GIVE_UP = 60;
    private static final Matrix4f PROJECTION = new Matrix4f().perspective((float) Math.toRadians(60), (float) W / H, 0.05f, 100f);
    private static final String[] NAMES = {"hidden cube", "near over far", "far alone", "skinned quad", "background"};
    /** Where each is sampled, in the world: projected to its pixel. */
    private static final Vector3f[] AT = {
            new Vector3f(-2.5f, 0f, -7.5f), new Vector3f(2.5f, -1.2f, -4.5f), new Vector3f(2.5f, -0.35f, -6f),
            new Vector3f(2.5f, 1.8f, -6f), new Vector3f(-3.5f, 2.5f, -5f)};
    private static final int[] EXPECTED = {3, 0x3F, 0x30, 0x80, 0};

    private CgMaterial wall, skin;
    private CgMesh quad;
    private HarnessFboHelper target;
    /** The near cube's front face, where {@code WORLD_NORMALS_READ} must have the opaque pass write +Z at eye depth 4.5. */
    private static final int NX = 275, NY = 64;

    private CgRenderStage.Registration asking, reader;
    private volatile byte[] stencil;
    private volatile float[] normal;
    private boolean armed, running = true;
    private int frame;

    @Override
    public void init(HarnessContext ctx) {
        wall = CgMaterial.load("assets/harness/shader/bloom_wall.shader");
        skin = CgMaterial.load("assets/harness/shader/skinned_mesh_test.shader");
        quad = CgMesh.build(CgVertexFormat.SKINNED, m -> {
            for (int k = 0; k < 4; k++) {
                float x = k == 1 || k == 2 ? 0.5f : -0.5f, y = k >= 2 ? 0.5f : -0.5f;
                m.vertex().position(x, y, 0f).normal(0f, 0f, 1f).uv(x + 0.5f, y + 0.5f).bone(0, 1f).end();
            }
            m.quad(0, 1, 2, 3);
        });
        target = HarnessFboHelper.create(W, H, true);
        CgWorldRenderer.get().install();
        asking = CgRenderStage.WORLD_OPAQUE.register(0, stage -> stage.resources().put(CgFrameKeys.WORLD_NORMALS_READ, Boolean.TRUE));
        reader = CgRenderStage.WORLD_TRANSPARENT.register(CgWorldRenderer.ORDER + 1, stage -> {
            CgGraphTexture mask = stage.resources().get(CgFrameKeys.CUSTOM_DEPTH);
            CgGraphTexture normals = stage.resources().get(CgFrameKeys.WORLD_NORMALS);
            if (!armed || mask == null || normals == null) return;
            armed = false;
            stage.recording().readback(normals, 0, NX, NY, 1, 1, this::readNormal);
            stage.recording().readback(mask, 0, 0, 0, W, H, this::read);
        });
    }

    private void readNormal(ByteBuffer data) {
        float[] out = new float[4];
        for (int c = 0; c < 4; c++) out[c] = Float.float16ToFloat(data.getShort(c * 2));
        normal = out;
    }

    private void read(ByteBuffer data) {
        byte[] out = new byte[W * H];
        data.get(0, out);
        stencil = out;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        if (stencil != null && normal != null || frame > GIVE_UP) {
            report();
            running = false;
            return;
        }
        armed = frame == ARM_AT;
        CgWorldRenderer world = CgWorldRenderer.get();
        CgMesh cube = CgMeshShapes.cube();
        world.draw(cube, wall).at(-3, 0, -5).transform(new Matrix4f().scale(6f, 6f, 0.2f)).submit();
        world.draw(cube, wall).at(-2.5, 0, -8).customDepth(3, 0xFF).submit();
        world.draw(cube, wall).at(2.5, -1.2, -5).customDepth(0x0F, 0xFF).submit();
        world.draw(cube, wall).at(2.5, -1.2, -7).transform(new Matrix4f().scale(2f)).customDepth(0x30, 0xF0).submit();
        int base = CgBonePalettes.write(new Matrix4f[]{new Matrix4f()}, 1);
        world.draw(quad, skin).bones(base).at(2.5, 1.8, -6).customDepth(0x80, 0xFF).submit();

        target.bind();
        target.clear(0.05f, 0.05f, 0.07f, 1f);
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, W, H, target.getFboId()).view().set(0, 0, 0, new Matrix4f(), PROJECTION);
            stage.fire();
        }
        target.unbind();
        frame++;
    }

    private void report() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        String fail = null;
        if (GlErrorChecker.checkAndLog("custom-depth")) fail = "GL errors, logged above";
        else if (PlatformServiceHarness.validationErrors() > 0) fail = "Vulkan validation errors, logged above";
        else if ("recording".equals(on)) fail = null;
        else if (stencil == null) fail = "CustomDepth never came back";
        else {
            for (int k = 0; k < AT.length; k++) {
                Vector3f ndc = PROJECTION.transformProject(new Vector3f(AT[k]));
                int x = (int) ((ndc.x * 0.5f + 0.5f) * W), y = (int) ((ndc.y * 0.5f + 0.5f) * H);   // rows bottom first
                int got = stencil[y * W + x] & 0xFF;
                System.out.printf("[custom-depth]   %s at (%d, %d): 0x%02X, expected 0x%02X%n", NAMES[k], x, y, got, EXPECTED[k]);
                if (got != EXPECTED[k] && fail == null) fail = NAMES[k] + " read 0x" + Integer.toHexString(got);
            }
            if (normal == null) {
                if (fail == null) fail = "WORLD_NORMALS never came back";
            } else {
                System.out.printf("[custom-depth]   world normal at (%d, %d): (%.3f, %.3f, %.3f) eye %.2f, expected (0.5, 0.5, 1) eye 4.5%n",
                        NX, NY, normal[0], normal[1], normal[2], normal[3]);
                boolean plusZ = Math.abs(normal[0] - 0.5f) < 0.01f && Math.abs(normal[1] - 0.5f) < 0.01f && normal[2] > 0.99f;
                if (fail == null && (!plusZ || Math.abs(normal[3] - 4.5f) > 0.05f)) fail = "the near cube's world normal";
            }
        }
        System.out.println(fail == null ? "[custom-depth] PASS on " + on : "[custom-depth] FAIL on " + on + ": " + fail);
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return true; }

    @Override
    public void dispose() {
        asking.close();
        reader.close();
        quad.release();
        target.delete();
    }
}
