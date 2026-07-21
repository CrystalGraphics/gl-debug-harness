package io.github.somehussar.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.render.CgFrameData;
import com.crystalgraphics.api.render.CgRenderCommand;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.gl.mesh.CgMeshBuilder;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.tool.GlErrorChecker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

/**
 * Harness port of {@code CgRenderDemo}: a 4×4 rainbow-tinted cube grid rendered through
 * the full {@link CgRenderPipeline} (depth prepass → opaque forward pass).
 *
 * <p>Uses the harness 3D camera (WASD + mouse look) so the scene can be freely inspected.
 * Per-cube HSV colour is passed in {@code CG_OBJECT_CUSTOM0} and read by
 * {@code crystalgraphics:shaders/demo_render.shader}.</p>
 *
 * <p>Register in {@link io.github.somehussar.crystalgraphics.harness.SceneRegistry}
 * under scene id {@code "forward-renderer"}.</p>
 */
public class CgForwardRendererScene implements InteractiveSceneLifecycle {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.ForwardRendererScene");

    private static final int   GRID      = 4;
    private static final float GRID_STEP = 1.5f;

    private CgMesh     cubeMesh;
    private CgMaterial cubeMaterial;
    private CgRenderPipeline pipeline;

    private boolean running          = true;
    private boolean loggedFirstFrame = false;

    private final Matrix4f scratchView = new Matrix4f();
    private final Matrix4f scratchProj = new Matrix4f();

    @Override
    public void init(HarnessContext ctx) {
        cubeMesh     = CgMeshBuilder.unitCube(CgVertexFormat.SPATIAL).upload();
        cubeMaterial = CgMaterial.load("crystalgraphics:shaders/demo_render.shader");
        pipeline     = CgRenderPipeline.getInstance();

        ctx.getCamera3D().moveCamera(0f, 4f, 12f);
        ctx.getCamera3D().setPitch(-15f);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        scratchView.set(ctx.getCamera3D().getViewMatrix());
        scratchProj.set(ctx.getProjection());

        CgFrameData fd = pipeline.getFrameData();
        fd.viewMatrix.set(scratchView);
        fd.projMatrix.set(scratchProj);
        fd.timeSecs  = (float) frame.getElapsedTime();
        fd.viewportW = ctx.getScreenWidth();
        fd.viewportH = ctx.getScreenHeight();
        fd.deriveFromViewMatrix();

        float half = (GRID - 1) * GRID_STEP * 0.5f;
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                float x = i * GRID_STEP - half;
                float z = j * GRID_STEP - half;

                CgRenderCommand cmd = pipeline.acquireCommand();
                cmd.mesh      = cubeMesh;
                cmd.material  = cubeMaterial;
                cmd.queueSlot = CgRenderQueue.GEOMETRY;
                cmd.modelMatrix.identity().translation(x, 0f, z);

                float hue   = (i * GRID + j) / (float)(GRID * GRID);
                float[] rgb = hsvToRgb(hue, 0.85f, 1.0f);
                cmd.custom0.set(rgb[0], rgb[1], rgb[2], 1f);

                cmd.worldAabb[0] = x - 0.5f;  cmd.worldAabb[3] = x + 0.5f;
                cmd.worldAabb[1] =    -0.5f;   cmd.worldAabb[4] =    0.5f;
                cmd.worldAabb[2] = z - 0.5f;   cmd.worldAabb[5] = z + 0.5f;

                pipeline.submit(cmd);
            }
        }
        
//        GlErrorChecker.assertNoGlError("before pipeline");
        pipeline.execute(0.0f);
        GlErrorChecker.assertNoGlError("after pipeline");
//        
        if (!loggedFirstFrame) {
            loggedFirstFrame = true;
            LOG.info("[ForwardRendererScene] first frame: submitted {}x{} = {} cubes via demo_render.shader",
                    GRID, GRID, GRID * GRID);
        }
    }

    @Override
    public void dispose() {
        if (cubeMesh != null) { cubeMesh.delete(); cubeMesh = null; }
        cubeMaterial = null;
    }

    @Override public boolean isRunning()              { return running; }
    @Override public boolean uses3DCamera()           { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }

    private static float[] hsvToRgb(float h, float s, float v) {
        int   hi = (int)(h * 6f) % 6;
        float f  = h * 6f - (int)(h * 6f);
        float p  = v * (1f - s);
        float q  = v * (1f - f * s);
        float t  = v * (1f - (1f - f) * s);
        switch (hi) {
            case 0:  return new float[]{ v, t, p };
            case 1:  return new float[]{ q, v, p };
            case 2:  return new float[]{ p, v, t };
            case 3:  return new float[]{ p, q, v };
            case 4:  return new float[]{ t, p, v };
            default: return new float[]{ v, p, q };
        }
    }
}
