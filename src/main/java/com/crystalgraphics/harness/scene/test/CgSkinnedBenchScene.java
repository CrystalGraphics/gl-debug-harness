package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkinning;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.shader.CgBonePalettes;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;

/**
 * What skinning costs a pass: {@code crystalgraphics.harness.skinned.count} (200) bars of 8,481 vertices, three bones
 * each, bending every frame from palettes written that frame. With {@code .skinned.static=true} the same bars are drawn
 * as their CPU-skinned twins, static meshes of the same vertices: the difference in a pass's GPU time is the skinned
 * factory's. Profile both:
 *
 * <pre>
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=skinned-bench" -Dcrystalgraphics.harness.profile=300 \
 *     -Dcrystalgraphics.trace.channels=crystalgraphics,gpu -Dcrystalgraphics.harness.fps=0
 * </pre>
 */
public class CgSkinnedBenchScene implements InteractiveSceneLifecycle {

    private static final int SEGMENTS = 256, ROWS = 32, BONES = 3;
    private static final float LENGTH = 6f, HEIGHT = 0.6f;
    private static final float[] JOINTS = {-3f, -1f, 1f};

    private final int count = Integer.getInteger("crystalgraphics.harness.skinned.count", 200);
    private final boolean still = Boolean.getBoolean("crystalgraphics.harness.skinned.static");
    private CgMaterial material;
    private CgMesh bar, twin;
    private final Matrix4f[] skinning = new Matrix4f[BONES];

    @Override
    public void init(HarnessContext ctx) {
        material = CgMaterial.load("assets/harness/shader/dual_path_test.shader");
        bar = CgMesh.build(CgVertexFormat.SKINNED, m -> {
            for (int s = 0; s <= SEGMENTS; s++) {
                float x = -LENGTH / 2f + LENGTH * s / SEGMENTS;
                int bone = x < JOINTS[1] ? 0 : x < JOINTS[2] ? 1 : 2;
                for (int r = 0; r <= ROWS; r++) {
                    float y = -HEIGHT / 2f + HEIGHT * r / ROWS;
                    m.vertex().position(x, y, 0f).normal(0f, 0f, 1f).uv(s / (float) SEGMENTS, r / (float) ROWS);
                    if (bone > 0 && x - JOINTS[bone] < 0.2f) m.bone(bone - 1, 0.5f).bone(bone, 0.5f);
                    else m.bone(bone, 1f);
                    m.end();
                }
            }
            for (int s = 0; s < SEGMENTS; s++) {
                for (int r = 0; r < ROWS; r++) {
                    int a = s * (ROWS + 1) + r;
                    m.quad(a, a + ROWS + 1, a + ROWS + 2, a + 1);
                }
            }
        });
        for (int b = 0; b < BONES; b++) skinning[b] = new Matrix4f();
        pose(0.5f);
        twin = CgSkinning.skin(bar, skinning);
        ctx.getCamera3D().moveCamera(0f, 4f, 14f);
        ctx.getCamera3D().setPitch(-15f);
    }

    /** Each bone bent {@code bend} radians more than its parent, about its joint. */
    private void pose(float bend) {
        Matrix4f parent = new Matrix4f();
        for (int b = 0; b < BONES; b++) {
            skinning[b].set(parent).translate(JOINTS[b], 0f, 0f).rotateZ(b == 0 ? 0f : bend).translate(-JOINTS[b], 0f, 0f);
            parent = skinning[b];
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();
        float t = (float) frame.getElapsedTime();
        int columns = (int) Math.ceil(Math.sqrt(count));
        for (int i = 0; i < count; i++) {
            double x = (i % columns - columns / 2.0) * 7.0, z = -(i / columns) * 2.0;
            if (still) {
                world.draw(twin, material).at(x, 1.0, z).custom(0, 0.8f, 0.7f, 0.6f, 1f).submit();
            } else {
                pose(0.5f * (float) Math.sin(t * 2f + i * 0.37f));
                int base = CgBonePalettes.write(skinning, BONES);
                world.draw(bar, material).bones(base).pad(LENGTH).at(x, 1.0, z).custom(0, 0.8f, 0.7f, 0.6f, 1f).submit();
            }
        }
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    @Override
    public String hudLine() {
        return count + " bars, " + (still ? "static twins" : "skinned") + " (crystalgraphics.harness.skinned.static)";
    }

    @Override
    public void dispose() {
        if (bar != null) bar.release();
        if (twin != null) twin.release();
    }

    @Override public boolean isRunning()                { return true; }
    @Override public boolean uses3DCamera()             { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
