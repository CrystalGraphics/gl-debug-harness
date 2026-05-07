package io.github.somehussar.crystalgraphics.harness.scene.test;

import io.github.somehussar.crystalgraphics.api.material.CgFrameUniforms;
import io.github.somehussar.crystalgraphics.api.material.CgMaterial;
import io.github.somehussar.crystalgraphics.api.material.CgMaterialPipeline;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMesh;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMeshBuilder;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.tool.GlErrorChecker;
import org.joml.Matrix4f;

/**
 * CrystalShader MVP demo scene: one {@code .shader} file drawn via both
 * {@code drawDirect()} (non-instanced) and {@code drawInstanced(N)} (instanced)
 * without modification, no {@code #ifdef}, same VAO.
 *
 * <p>Also contains three isolated diagnostic shaders to verify the UBO and SSBO
 * data pipelines independently before testing the full material system:</p>
 * <ul>
 *   <li>RED   — UBO-only: reads {@code CgFrameBlock} view+proj, world position hardcoded in GLSL</li>
 *   <li>GREEN — SSBO-only: reads model matrix from SSBO binding 0, outputs directly to clip space</li>
 *   <li>BLUE  — UBO+SSBO: full MVP pipeline combining both buffers</li>
 * </ul>
 */
public class CgMaterialDualPathScene implements InteractiveSceneLifecycle {
    
    private boolean running = true;

    // ── Resources ─────────────────────────────────────────────────────────────

    private CgMaterial material;
    private CgMesh mesh;
    private CgMaterialPipeline pipeline;

    private static final Matrix4f SCRATCH_4 = new Matrix4f();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void init(HarnessContext ctx) {
        pipeline = CgMaterialPipeline.getInstance();
        material = CgMaterial.load("demo:shaders/dual_path_test.shader");
        mesh = CgMeshBuilder.unitCube(CgVertexFormat.SPATIAL).upload();
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        Matrix4f view = ctx.getCamera3D().getViewMatrix();
        Matrix4f projection = ctx.getProjection();

        CgFrameUniforms fu = pipeline.getFrameUniforms();
        fu.view(view).proj(projection).timeSecs((float) frame.getElapsedTime())
          .viewportW(ctx.getScreenWidth()).viewportH(ctx.getScreenHeight());
        pipeline.beginFrame();

        // ── Material: non-instanced draw (1 cube at origin) ──────────────────
        CgShaderBuffer objectBuffer = pipeline.objectBuffer();
        CgBufferWriter ssboW = objectBuffer.beginWrite(1);
        ssboW.beginRecord()
             .mat4("modelMatrix", SCRATCH_4.identity().translation(0f, 0f, 0f))
             .mat4("normalMatrix", SCRATCH_4.identity())
             .vec4("custom0", 1f, 1f, 1f, 1f);
        // custom1–custom3 auto-zeroed
        objectBuffer.endRecord();
        objectBuffer.endWrite();

        material.bind();
        mesh.drawDirect();
        material.unbind();
        GlErrorChecker.assertNoGlError("CgMaterialDualPathScene.drawDirect");

        // ── Material: instanced draw (8 cubes across X at z = -5) ────────────
        int N = 11118;
        ssboW = objectBuffer.beginWrite(N);
        for (int i = 0; i < N; i++) {
            float r = (i & 1) == 0 ? 1f : 0.25f;
            float g = (i & 2) == 0 ? 1f : 0.25f;
            float b = (i & 4) == 0 ? 1f : 0.25f;
            ssboW.beginRecord()
                 .mat4("modelMatrix", SCRATCH_4.identity().translation(i * 1.5f, 0f, -5f))
                 .mat4("normalMatrix", SCRATCH_4.identity())
                 .vec4("custom0", r, g, b, 1f);
            // custom1–custom3 auto-zeroed
            objectBuffer.endRecord();
        }
        objectBuffer.endWrite();

        material.bind();
        mesh.drawInstanced(N);
        material.unbind();
        GlErrorChecker.assertNoGlError("CgMaterialDualPathScene.drawInstanced");
    }


  
    // ── Dispose ───────────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        if (mesh != null) mesh.delete();
        if (material != null) material.delete();
    }

    @Override
    public boolean isRunning() {return running;}

    @Override
    public boolean uses3DCamera() {return true;}

    @Override
    public boolean shouldShutdownOnComplete() {return true;}
}
