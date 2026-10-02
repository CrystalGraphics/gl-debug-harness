package com.crystalgraphics.harness.scene;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.camera.Camera3D;
import com.crystalgraphics.harness.capture.ArtifactService;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.object.WorldAxisRenderer;
import com.crystalgraphics.harness.scene.test.HarnessWorld;
import com.crystalgraphics.harness.scheduler.TaskScheduler;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Meshes from shapes and files, drawn through the world renderer under an 8x8 UV checker.
 *
 * <ul>
 *   <li>Shapes: a cube at (-6, 0, 0), a quad at (-3, 0, 0), a 4x4-cell grid at (0, -2, 0), a sphere at (0, 2, 0) and
 *       a level-4 icosphere at (0, 0, -4).</li>
 *   <li>Files: {@code test_model.obj} at (-1.5, 0, 0), {@code test_model.glb} at (4.5, 2, 0).</li>
 *   <li>{@code two_parts.gltf} at (2.5, -0.5, 0): two primitives, each its own submesh, drawn with the material it
 *       names -- a checkered quad on the left, a flat orange strip on the right (mesh rewrite M5's gate).</li>
 * </ul>
 *
 * <p>Captures {@code overview} at 1 s and {@code closeup} at 1.5 s.</p>
 */
public class MeshTestScene implements InteractiveSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(MeshTestScene.class.getName());
    private static final CgVertexFormat FORMAT = CgVertexFormat.SPATIAL;

    private HarnessContext ctx;
    private CgMaterial checker, tint;
    private CgMeshLoader.Model obj, glb, parts;
    private final Matrix4f scale = new Matrix4f();
    private final WorldAxisRenderer axis = new WorldAxisRenderer();

    @Override
    public void init(HarnessContext ctx) {
        this.ctx = ctx;
        axis.init(ctx);
        checker = CgMaterial.load("assets/harness/shader/mesh_test.shader");
        tint = CgMaterial.load("assets/harness/shader/dual_path_test.shader");
        obj = load("harness:meshes/test_model.obj");
        glb = load("harness:meshes/test_model.glb");
        parts = load("harness:meshes/two_parts.gltf");
        scheduleCaptures();
    }

    @Nullable
    private static CgMeshLoader.Model load(String path) {
        try {
            CgMeshLoader.Model model = CgMeshLoader.model(path, FORMAT);
            LOGGER.info(String.format("[MeshTestScene] %s: %d vertices, %d indices, %d parts %s", path,
                    model.mesh().vertexCount(), model.mesh().indexCount(), model.mesh().submeshCount(), model.materials()));
            return model;
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "[MeshTestScene] " + path + " failed", e);
            return null;
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        axis.render(ctx);
        CgWorldRenderer world = CgWorldRenderer.get();
        world.draw(CgMeshShapes.cube(FORMAT), checker).at(-6f, 0f, 0f).submit();
        world.draw(CgMeshShapes.quad(FORMAT), checker).at(-3f, 0f, 0f).submit();
        world.draw(CgMeshShapes.grid(FORMAT, 4), checker).at(0f, -2f, 0f).transform(scale.scaling(2f)).submit();
        world.draw(CgMeshShapes.sphere(FORMAT, 16, 16), checker).at(0f, 2f, 0f).transform(scale.scaling(0.8f)).submit();
        world.draw(CgMeshShapes.icosphere(FORMAT, 4), checker).at(0f, 0f, -4f).submit();
        if (obj != null) world.draw(obj.mesh(), checker).at(-1.5f, 0f, 0f).submit();
        if (glb != null) world.draw(glb.mesh(), checker).at(4.5f, 2f, 0f).submit();
        if (parts != null) {
            for (int i = 0; i < parts.mesh().submeshCount(); i++) {
                CgMaterial material = "tint".equals(parts.material(i)) ? tint : checker;
                world.draw(parts.mesh(), material).at(2.5f, -0.5f, 0f).submesh(i).custom(0, 1.4f, 0.9f, 0.5f, 1.4f)
                        .submit();
            }
        }
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    private void scheduleCaptures() {
        TaskScheduler scheduler = ctx.getTaskScheduler();
        ArtifactService artifacts = ctx.getArtifactService();
        Camera3D camera = ctx.getCamera3D();
        scheduler.schedule(1.0, "capture-overview", () -> artifacts.requestCapture("overview"));
        scheduler.schedule(1.5, "capture-closeup", () -> {
            camera.moveCamera(0.0f, 1.5f, 5.0f);
            camera.setYaw(0.0f);
            camera.setPitch(-5.0f);
            artifacts.requestCapture("closeup");
        });
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning()                { return true; }
    @Override public boolean uses3DCamera()             { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
