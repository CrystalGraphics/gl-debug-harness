package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.mesh.CgMeshStore;
import com.crystalgraphics.render.world.CgWorldRenderer;

import java.util.logging.Logger;

/**
 * Mesh rewrite M6's gate: a field of 720 spheres running into the distance, drawn for 120 frames at the finest level
 * of {@link CgMeshShapes#sphereLods()} and then through the levels, each picked by how tall its sphere stands on
 * screen. Logs the vertices each way draws a frame ({@code [mesh-lods]}) and photographs both: they should look
 * alike, the far spheres no less round.
 */
public class CgMeshLodsScene implements InteractiveSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(CgMeshLodsScene.class.getName());
    private static final int COLUMNS = 12, ROWS = 60, SWITCH = 120;

    private CgMaterial solid;
    private long fixed, levelled;

    @Override
    public void init(HarnessContext ctx) {
        solid = CgMaterial.load("assets/harness/shader/dual_path_test.shader");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        long n = frame.getFrameNumber();
        boolean useLods = n > SWITCH;
        CgMeshLods lods = CgMeshShapes.sphereLods();
        CgWorldRenderer world = CgWorldRenderer.get();
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                float x = (column - (COLUMNS - 1) * 0.5f) * 3f, z = -3f - row * 3f;
                float shade = 0.5f + 0.5f * ((row + column) & 1);
                CgWorldRenderer.Draw draw = useLods ? world.draw(lods, solid) : world.draw(lods.finest(), solid);
                draw.at(x, -1f, z).custom(0, 0.4f + 0.6f * shade, 0.4f + 0.5f * shade, 1.4f, 1.4f).submit();
            }
        }
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());

        long drawn = CgMeshStore.get().drawnVertices();   // the frame before this one
        if (n == SWITCH - 1) fixed = drawn;
        if (n == SWITCH + 60) {
            levelled = drawn;
            LOGGER.info(String.format("[mesh-lods] fixed: %d vertices a frame, levels: %d (%.1f%%)", fixed, levelled,
                    100.0 * levelled / Math.max(1, fixed)));
        }
        if (n == SWITCH - 10) ctx.getArtifactService().requestCapture("fixed");
        if (n == SWITCH + 50) ctx.getArtifactService().requestCapture("lods");
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning()                { return true; }
    @Override public boolean uses3DCamera()             { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
