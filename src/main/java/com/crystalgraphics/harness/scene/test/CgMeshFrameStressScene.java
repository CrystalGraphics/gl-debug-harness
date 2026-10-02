package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.mesh.CgMeshWriter;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.world.CgWorldRenderer;

/**
 * Many {@code FRAME} meshes rewritten every frame: {@code -Dcrystalgraphics.harness.frameStress.meshes} strips (64) of
 * {@code .points} vertex pairs (189), each its own mesh, waving. Profile it on the ring and, with
 * {@code -Dcrystalgraphics.mesh.frameRing=false}, on slabs. {@code .usage=DYNAMIC} builds them with another usage,
 * which the store reports as edited every frame.
 */
public class CgMeshFrameStressScene implements InteractiveSceneLifecycle {

    private static final int MESHES = Integer.getInteger("crystalgraphics.harness.frameStress.meshes", 64);
    private static final int POINTS = Integer.getInteger("crystalgraphics.harness.frameStress.points", 189);
    private static final CgMesh.Usage USAGE =
            CgMesh.Usage.valueOf(System.getProperty("crystalgraphics.harness.frameStress.usage", "FRAME"));

    private final CgMesh[] strips = new CgMesh[MESHES];
    private CgMaterial solid;
    private long frameNumber;
    private int writing;

    @Override
    public void init(HarnessContext ctx) {
        solid = CgMaterial.load("assets/harness/shader/dual_path_test.shader");
        for (int i = 0; i < MESHES; i++) {
            strips[i] = CgMesh.build(CgVertexFormat.SPATIAL, USAGE, m -> {});
            strips[i].reserve(2 * POINTS, 0);
        }
    }

    private static void write(CgMeshWriter m, CgMeshFrameStressScene scene) {
        m.topology(CgMeshTopology.TRIANGLE_STRIP);
        double phase = scene.frameNumber * 0.05 + scene.writing * 0.7;
        for (int i = 0; i < POINTS; i++) {
            float x = i * (6f / POINTS), y = 0.2f * (float) Math.sin(phase + i * 0.15);
            m.vertex().position(x, y, 0f).uv(0f, 0f).normal(0f, 0f, 1f).end();
            m.vertex().position(x, y + 0.04f, 0f).uv(0f, 1f).normal(0f, 0f, 1f).end();
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        frameNumber = frame.getFrameNumber();
        CgWorldRenderer world = CgWorldRenderer.get();
        for (int i = 0; i < MESHES; i++) {
            writing = i;
            strips[i].edit(this, CgMeshFrameStressScene::write);
            float shade = (float) i / MESHES;
            world.draw(strips[i], solid).at(-3f, 3.2f - i * (4.4f / MESHES), -1f)
                    .custom(0, 0.5f + shade, 1.2f - shade * 0.5f, 1.3f, 1.4f).submit();
        }
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    @Override
    public void dispose() {
        for (CgMesh strip : strips) if (strip != null) strip.release();
    }

    @Override public boolean isRunning()                { return true; }
    @Override public boolean uses3DCamera()             { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
