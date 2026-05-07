package io.github.somehussar.crystalgraphics.harness.scene.test;

import io.github.somehussar.crystalgraphics.api.CgBindingPoints;
import io.github.somehussar.crystalgraphics.api.material.CgMaterial;
import io.github.somehussar.crystalgraphics.api.material.CgMaterialKey;
import io.github.somehussar.crystalgraphics.api.material.CgMaterialPipeline;
import io.github.somehussar.crystalgraphics.api.shader.CgActiveUniform;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMesh;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMeshBuilder;
import io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.capture.ArtifactService;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.scheduler.TaskScheduler;
import io.github.somehussar.crystalgraphics.harness.tool.GlErrorChecker;
import io.github.somehussar.crystalgraphics.harness.validation.ValidationCaptureStep;
import io.github.somehussar.crystalgraphics.harness.validation.ValidationChoreographer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL31;

import java.util.List;

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

    private static final Logger LOGGER = LogManager.getLogger("CgMaterialDualPathScene");

    private static final String DIR_TEST = "assets/harness/shader/test/";

    private boolean running = true;
    private boolean diagLogged = false;

    // ── Resources ─────────────────────────────────────────────────────────────

    private CgMaterial material;
    private CgMesh mesh;
    private CgMaterialPipeline pipeline;

    // Diagnostic shaders — isolate each data path so failures pinpoint the broken pipeline.
    private CgShader uboTestShader = CgShaderFactory.load(DIR_TEST + "test_ubo_only.vert",
            DIR_TEST + "test_ubo_only.frag");
    private CgShader ssboTestShader = CgShaderFactory.load(DIR_TEST + "test_ssbo_only.vert",
            DIR_TEST + "test_ssbo_only.frag");
    private CgShader uboSsboTestShader = CgShaderFactory.load(DIR_TEST + "test_ubo_ssbo.vert",
            DIR_TEST + "test_ubo_ssbo.frag");

    //private CgShader testShader = CgShaderFactory.load(DIR_TEST + "test-env.vert", DIR_TEST + "test-env.frag");

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void init(HarnessContext ctx) {
        pipeline = CgMaterialPipeline.getInstance();
        material = CgMaterial.load("demo:shaders/dual_path_test.shader");
       // material.applyBindings(b -> b.vec4("_Color", 1f, 1f, 1f, 1f));

        mesh = CgMeshBuilder.unitCube(CgVertexFormat.SPATIAL).upload();
        

        uboTestShader.bindings().ubo(pipeline.frameBuffer());
        uboSsboTestShader.bindings().ubo(pipeline.frameBuffer());

        scheduleValidationCapture(ctx);
    }

    private void scheduleValidationCapture(HarnessContext ctx) {
        final Camera3D camera = ctx.getCamera3D();
        final TaskScheduler scheduler = ctx.getTaskScheduler();
        final ArtifactService artifacts = ctx.getArtifactService();

        ValidationChoreographer choreographer = new ValidationChoreographer(
                camera, scheduler, artifacts, ctx.getRuntimeServices());

        choreographer.addStep(ValidationCaptureStep.builder("dual-path-overview", 0.5)
                                                   .cameraPosition(5.0f, 4.0f, 10.0f)
                                                   .cameraOrientation(0.0f, -15.0f)
                                                   .build());

        choreographer.onShutdown(new Runnable() {
            @Override
            public void run() {
                //running = false;
            }
        });
        choreographer.scheduleAll();
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        Matrix4f view = ctx.getCamera3D().getViewMatrix();
        Matrix4f projection = ctx.getProjection();

        pipeline.beginFrame(view, projection, (float) frame.getElapsedTime(),
                ctx.getScreenWidth(), ctx.getScreenHeight());

        // ── Material: non-instanced draw (1 cube at origin) ──────────────────
        CgShaderBuffer objectBuffer = material.objectBuffer();
        CgBufferWriter ssboW = objectBuffer.beginWrite(1);
        ssboW.beginRecord();
        ssboW.mat4(new Matrix4f().translation(-0f, 0f, 0f));
        ssboW.mat4(new Matrix4f().identity());
        ssboW.vec4(new Vector4f(1f, 1f, 1f, 1f));
        ssboW.vec4(new Vector4f(0f));
        ssboW.vec4(new Vector4f(0f));
        ssboW.vec4(new Vector4f(0f));
        ssboW.endRecord();
        objectBuffer.advanceRecord();
        objectBuffer.endWrite();

        material.bind();
        mesh.drawDirect();
        material.unbind();
        GlErrorChecker.assertNoGlError("CgMaterialDualPathScene.drawDirect");

        // ── Material: instanced draw (8 cubes across X at z = -5) ────────────
        int N = 11118;
        objectBuffer.beginWrite(N);
        for (int i = 0; i < N; i++) {
            Vector4f tint = SCRATCH_4F.set(
                    (i & 1) == 0 ? 1f : 0.25f,
                    (i & 2) == 0 ? 1f : 0.25f,
                    (i & 4) == 0 ? 1f : 0.25f,
                    1f);
            ssboW.beginRecord();
            ssboW.mat4(SCRATCH_4.identity().translation(i * 1.5f, 0f, -5f));
            ssboW.mat4(SCRATCH_4.identity());
            ssboW.vec4(tint);
            ssboW.vec4(SCRATCH_4F.set(0));
            ssboW.vec4(SCRATCH_4F.set(0));
            ssboW.vec4(SCRATCH_4F.set(0));
            ssboW.endRecord();
            objectBuffer.advanceRecord();
        }
        objectBuffer.endWrite();

        material.bind();
        mesh.drawInstanced(N);
        material.unbind();
        GlErrorChecker.assertNoGlError("CgMaterialDualPathScene.drawInstanced");

        if (!diagLogged) {
            diagLogged = true;
            logFirstFrameDiagnostics(view, projection);
        }
    }

    private static final Vector4f SCRATCH_4F = new Vector4f();
    private static final Matrix4f SCRATCH_4 = new Matrix4f();
    // ── Diagnostics ───────────────────────────────────────────────────────────

    private void logFirstFrameDiagnostics(Matrix4f view, Matrix4f proj) {
        LOGGER.info("=== CgMaterialDualPathScene first-frame diagnostics ===");

        logShaderStatus("ubo-only   (red) ", uboTestShader, "CgFrameBlock");
        logShaderStatus("ssbo-only  (grn) ", ssboTestShader, null);
        logShaderStatus("ubo+ssbo   (blu) ", uboSsboTestShader, "CgFrameBlock");

        CgShader matShader = material.getShader();
        int matId = matShader.getProgram() != null ? matShader.getProgram().getId() : -1;
        logShaderStatus("material   ", matShader, "CgFrameBlock");
        LOGGER.info("material: compiled={}, programId={}", matShader.isCompiled(), matId);
        if (matId > 0) {
            LOGGER.info("  _Color loc={}", GL20.glGetUniformLocation(matId, "_Color"));
            LOGGER.info("  CgFrameBlock idx={}", GL31.glGetUniformBlockIndex(matId, CgUniformBuffer.BLOCK_NAME));
            List<CgActiveUniform> active = matShader.getActiveUniforms();
            LOGGER.info("  active uniforms count={}", active.size());
            for (CgActiveUniform u : active) {
                LOGGER.info("    {} type=0x{} loc={}", u.name(), Integer.toHexString(u.glType()), u.location());
            }
            String infoLog = GL20.glGetProgramInfoLog(matId, 4096);
            LOGGER.info("  programInfoLog: [{}]", infoLog == null ? "" : infoLog.trim());
            LOGGER.info("  GL_LINK_STATUS={}", GL20.glGetProgrami(matId, GL20.GL_LINK_STATUS));
            LOGGER.info("  GL_VALIDATE_STATUS={}", GL20.glGetProgrami(matId, GL20.GL_VALIDATE_STATUS));
            int activeBlocks = GL20.glGetProgrami(matId, GL31.GL_ACTIVE_UNIFORM_BLOCKS);
            LOGGER.info("  active UBO blocks={}", activeBlocks);
        }

        LOGGER.info("view[0..3]  = {}, {}, {}, {}", view.m00(), view.m01(), view.m02(), view.m03());
        LOGGER.info("proj[0..3]  = {}, {}, {}, {}", proj.m00(), proj.m01(), proj.m02(), proj.m03());
  
        LOGGER.info("frameData bindingPoint={}", CgBindingPoints.FRAME_DATA);
        LOGGER.info("glGetError() after render = 0x{}", Integer.toHexString(GL11.glGetError()));
        LOGGER.info("=== end diagnostics ===");
    }

    private void logShaderStatus(String label, CgShader shader, String uboBlock) {
        int id = shader.getProgram() != null ? shader.getProgram().getId() : -1;
        LOGGER.info("{}: compiled={}, programId={}", label, shader.isCompiled(), id);
        if (id > 0 && uboBlock != null) {
            LOGGER.info("  {} blockIdx={}", uboBlock, GL31.glGetUniformBlockIndex(id, uboBlock));
            int activeBlocks = GL20.glGetProgrami(id, GL31.GL_ACTIVE_UNIFORM_BLOCKS);
            LOGGER.info("    active UBO blocks={}", activeBlocks);
        }
    }

  
    // ── Dispose ───────────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        if (uboTestShader != null) uboTestShader.delete();
        if (ssboTestShader != null) ssboTestShader.delete();
        if (uboSsboTestShader != null) uboSsboTestShader.delete();
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
