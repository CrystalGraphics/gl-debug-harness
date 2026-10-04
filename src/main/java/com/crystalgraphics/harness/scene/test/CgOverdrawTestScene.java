package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
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

import java.nio.ByteBuffer;
import java.util.List;

/**
 * The gate for the overdraw view (render-distortion T3): an opaque wall over the left half, three transparent planes in
 * front of it, two behind, and one in front discarding its bottom half, counted by {@code CgWorldRenderer.overdraw} and
 * read back. Each quadrant's centre must hold exactly 3, 4, 5 or 6.
 *
 * <pre>
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=overdraw-count"
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=overdraw-count --device=vulkan"
 * </pre>
 */
public class CgOverdrawTestScene implements InteractiveSceneLifecycle {

    private static final int W = 320, H = 240, ARM_AT = 3, GIVE_UP = 60;
    /** Bottom-left, bottom-right, top-left, top-right: GL's rows, bottom first. */
    private static final int[] EXPECTED = {3, 5, 4, 6};

    private CgMaterial wall, plane, cut;
    private HarnessFboHelper target;
    private CgRenderStage.Registration reader;
    private volatile float[] counts;
    private boolean armed, wasOverdraw, running = true;
    private int frame;

    @Override
    public void init(HarnessContext ctx) {
        wall = CgMaterial.load("assets/harness/shader/bloom_wall.shader");
        plane = CgMaterial.newInstance("assets/harness/shader/overdraw_plane.shader");
        cut = CgMaterial.newInstance("assets/harness/shader/overdraw_plane.shader");
        cut.enableKeyword("CUT");
        target = HarnessFboHelper.create(W, H, true);
        CgWorldRenderer world = CgWorldRenderer.get();
        world.install();
        wasOverdraw = world.overdraw();
        world.overdraw(true);
        reader = CgRenderStage.WORLD_TRANSPARENT.register(CgWorldRenderer.ORDER + 1, stage -> {
            CgGraphTexture overdraw = stage.resources().get(CgFrameKeys.OVERDRAW);
            if (!armed || overdraw == null) return;
            armed = false;
            stage.recording().readback(overdraw, 0, 0, 0, W, H, CgOverdrawTestScene.this::read);
        });
    }

    private void read(ByteBuffer data) {
        float[] out = new float[W * H];
        for (int i = 0; i < out.length; i++) out[i] = Float.float16ToFloat(data.getShort(i * 2));
        counts = out;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        if (counts != null || frame > GIVE_UP) {
            report();
            running = false;
            return;
        }
        armed = frame == ARM_AT;
        CgWorldRenderer world = CgWorldRenderer.get();
        CgMesh slab = CgMeshShapes.cube();
        Matrix4f thin = new Matrix4f().scale(40f, 40f, 0.01f);
        world.draw(slab, wall).at(-5, 0, -5).transform(new Matrix4f().scale(10f, 10f, 0.2f)).submit();
        for (int k = 0; k < 3; k++) world.draw(slab, plane).at(0, 0, -2 - k * 0.3).transform(thin).submit();
        for (int k = 0; k < 2; k++) world.draw(slab, plane).at(0, 0, -8 - k * 0.3).transform(thin).submit();
        world.draw(slab, cut).at(0, 0, -1.5).transform(thin).submit();

        target.bind();
        target.clear(0.05f, 0.05f, 0.07f, 1f);
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), (float) W / H, 0.05f, 100f);
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, W, H, target.getFboId()).view().set(0, 0, 0, new Matrix4f(), projection);
            stage.fire();
        }
        target.unbind();
        frame++;
    }

    private void report() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        String fail = null;
        if (GlErrorChecker.checkAndLog("overdraw-count")) fail = "GL errors, logged above";
        else if (PlatformServiceHarness.validationErrors() > 0) fail = "Vulkan validation errors, logged above";
        else if ("recording".equals(on)) fail = null;
        else if (counts == null) fail = "the overdraw target never came back";
        else {
            int[][] at = {{W / 4, H / 4}, {3 * W / 4, H / 4}, {W / 4, 3 * H / 4}, {3 * W / 4, 3 * H / 4}};
            StringBuilder got = new StringBuilder();
            for (int q = 0; q < 4; q++) {
                float count = counts[at[q][1] * W + at[q][0]];
                got.append(q == 0 ? "" : ", ").append(count);
                if (count != EXPECTED[q]) fail = "counted " + got + "...; expected 3, 5, 4, 6 (bottom-left, bottom-right, top-left, top-right)";
            }
            if (fail == null) System.out.println("[overdraw-count]   counted " + got + " (bottom-left, bottom-right, top-left, top-right)");
        }
        System.out.println(fail == null ? "[overdraw-count] PASS on " + on : "[overdraw-count] FAIL on " + on + ": " + fail);
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return true; }

    @Override
    public void dispose() {
        reader.close();
        CgWorldRenderer.get().overdraw(wasOverdraw);
        target.delete();
    }
}
