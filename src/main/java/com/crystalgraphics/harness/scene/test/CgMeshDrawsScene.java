package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.world.CgWorldRenderer;

/**
 * Mesh rewrite M4's gate: draws of part of a mesh, and geometry with no vertex data.
 *
 * <ul>
 *   <li>Left: {@code CgMesh.quads(1024)} placed by its shader in a 32-wide grid, drawn twice -- quads 0 to 299, then
 *       600 to 699. Right when the grid stops after 300 quads, and the second block sits rows above with its own
 *       colours: a range never renumbers {@code CG_VERTEX_ID}.</li>
 *   <li>Middle: {@code CgMesh.vertices(64, TRIANGLE_STRIP)}, a wavy orange band.</li>
 *   <li>Right: one model of two submeshes, a cube and a sphere, drawn as submesh 1 above and submesh 0 below.</li>
 *   <li>Nowhere: a third quads draw whose stated bounds are far off screen, so it is culled even though its shader
 *       would draw over the grid.</li>
 * </ul>
 */
public class CgMeshDrawsScene implements InteractiveSceneLifecycle {

    private CgMesh model;
    private CgMaterial quadsMaterial, stripMaterial, solid;

    @Override
    public void init(HarnessContext ctx) {
        model = CgMesh.build(CgVertexFormat.SPATIAL, m -> {
            CgMeshShapes.cube(m);
            m.submesh();
            CgMeshShapes.sphere(m, 16, 32, 0.6f);
        });
        quadsMaterial = CgMaterial.load("assets/harness/shader/mesh_quads_test.shader");
        stripMaterial = CgMaterial.load("assets/harness/shader/mesh_strip_test.shader");
        solid = CgMaterial.load("assets/harness/shader/dual_path_test.shader");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();
        CgMesh quads = CgMesh.quads(1024);
        world.draw(quads, quadsMaterial).at(-3.5f, 0f, -1f).indices(0, 300 * 6).bounds(0f, 0f, 0f, 3.2f, 3.2f, 0f).submit();
        world.draw(quads, quadsMaterial).at(-3.5f, 0f, -1f).indices(600 * 6, 100 * 6).bounds(0f, 0f, 0f, 3.2f, 3.2f, 0f)
                .submit();
        world.draw(quads, quadsMaterial).at(-3.5f, 0f, -1f).bounds(100f, 100f, 100f, 101f, 101f, 101f).submit();

        world.draw(CgMesh.vertices(64, CgMeshTopology.TRIANGLE_STRIP), stripMaterial).at(-0.2f, 1f, -1f)
                .bounds(0f, -0.2f, 0f, 3.2f, 0.5f, 0f).submit();

        world.draw(model, solid).at(3.2f, 2.6f, -1f).submesh(1).custom(0, 0.4f, 0.8f, 1f, 1f).submit();
        world.draw(model, solid).at(3.2f, 0.8f, -1f).submesh(0).custom(0, 1f, 0.5f, 0.5f, 1f).submit();
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    @Override
    public void dispose() {
        if (model != null) model.release();
    }

    @Override public boolean isRunning()                { return true; }
    @Override public boolean uses3DCamera()             { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
