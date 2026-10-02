package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;

/**
 * {@code cg_SceneColor} on a real device: a row of coloured cubes drawn opaque, and in front of them a transparent lens
 * whose material shows the scene behind it rippled and inverted. Right when the lens reads as the cubes, inverted, with
 * wavy edges, and the cubes outside it are untouched. Meshes are data ({@code CgMeshShapes}), drawn from the mesh store.
 */
public class CgSceneColorScene implements InteractiveSceneLifecycle {

    private CgMesh cube, lens;
    private CgMaterial solid, lensMaterial;
    private final Matrix4f lensTransform = new Matrix4f().scale(4f, 2f, 1f);

    @Override
    public void init(HarnessContext ctx) {
        cube = CgMeshShapes.cube();
        lens = CgMeshShapes.quad();
        solid = CgMaterial.load("assets/harness/shader/dual_path_test.shader");
        lensMaterial = CgMaterial.load("assets/harness/shader/scene_color_test.shader");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();
        for (int i = 0; i < 8; i++) {
            float hue = i / 8f;
            world.draw(cube, solid).at(i - 3.5f, 1f, -1f)
                    .custom(0, 0.5f + 0.5f * (float) Math.cos(hue * 6.283f), 0.5f + 0.5f * (float) Math.cos((hue - 0.33f) * 6.283f),
                            0.5f + 0.5f * (float) Math.cos((hue - 0.67f) * 6.283f), 1f)
                    .submit();
        }
        world.draw(lens, lensMaterial).at(-1.5f, 1.5f, 1f).transform(lensTransform).submit();
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning()                { return true; }
    @Override public boolean uses3DCamera()             { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
