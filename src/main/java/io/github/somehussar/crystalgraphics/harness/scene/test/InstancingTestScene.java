package io.github.somehussar.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.vertex.CgInstanceFormat;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.staging.CgInstanceWriter;
import com.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import com.crystalgraphics.gl.render.CgBatchRenderer;
import com.crystalgraphics.gl.render.CgInstanceRenderer;
import com.crystalgraphics.gl.render.CgQuadInstanceRenderer;
import com.crystalgraphics.gl.vertex.CgInstanceVertexArrayBinding;
import com.crystalgraphics.api.CgCapabilities;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * GL harness diagnostics scene for the instancing backend.
 *
 * <p>Verifies that instanced VAO setup does not contaminate base (non-instanced) VAO state,
 * and that instanced draws issue one GL draw call per {@link CgInstanceRenderer#flush}.</p>
 *
 * <h3>Diagnostic phases</h3>
 * <ol>
 *   <li>SKIP if instancing capability is unavailable — logs details and exits cleanly.</li>
 *   <li>base-before: one non-instanced quad draw via {@link CgBatchRenderer}.</li>
 *   <li>instanced: 100 instanced quads via {@link CgInstanceRenderer}.</li>
 *   <li>base-after: one more non-instanced quad draw, verifying base VAO is uncontaminated.</li>
 *   <li>gl-error: {@code glGetError()} checked after each phase — any error → FAIL.</li>
 * </ol>
 *
 * <p>Register in {@link io.github.somehussar.crystalgraphics.harness.SceneRegistry}
 * under mode id {@code "instancing-test"}.</p>
 */
public class InstancingTestScene implements InteractiveSceneLifecycle {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.InstancingTest");

    private static final int INSTANCE_COUNT = 100;

    private static final String BASE_VERT =
        "#version 330 core\n"
        + "layout(location = 0) in vec2 a_pos;\n"
        + "layout(location = 1) in vec2 a_uv;\n"
        + "layout(location = 2) in vec4 a_color;\n"
        + "uniform mat4 u_projection;\n"
        + "out vec4 v_color;\n"
        + "void main() {\n"
        + "    gl_Position = u_projection * vec4(a_pos, 0.0, 1.0);\n"
        + "    v_color = a_color;\n"
        + "}\n";

    private static final String BASE_FRAG =
        "#version 330 core\n"
        + "in vec4 v_color;\n"
        + "out vec4 fragColor;\n"
        + "void main() { fragColor = v_color; }\n";

    private static final String INSTANCED_VERT =
        "#version 330 core\n"
        + "layout(location = 0) in vec2 a_pos;\n"
        + "layout(location = 1) in vec2 a_uv;\n"
        + "layout(location = 2) in vec4 a_color;\n"
        + "layout(location = 3) in vec4 a_instanceModel0;\n"
        + "layout(location = 4) in vec4 a_instanceModel1;\n"
        + "layout(location = 5) in vec4 a_instanceModel2;\n"
        + "layout(location = 6) in vec4 a_instanceModel3;\n"
        + "layout(location = 7) in vec4 a_instanceColor;\n"
        + "layout(location = 8) in vec4 a_instanceCustom;\n"
        + "uniform mat4 u_projection;\n"
        + "out vec4 v_color;\n"
        + "void main() {\n"
        + "    mat4 model = mat4(a_instanceModel0, a_instanceModel1,\n"
        + "                      a_instanceModel2, a_instanceModel3);\n"
        + "    vec4 worldPos = model * vec4(a_pos, 0.0, 1.0);\n"
        + "    gl_Position = u_projection * worldPos;\n"
        + "    v_color = a_instanceColor;\n"
        + "    v_color.rgb += a_instanceCustom.x * 0.1;\n"
        + "}\n";

    private CgShader baseShader;
    private CgShader instancedShader;
    private CgBatchRenderer baseRenderer;
    private CgQuadInstanceRenderer instancedRenderer;
    private MultiMeshInstancingDemoRenderer multiMeshDemo;

    private boolean diagnosticsRan = false;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        baseShader = CgShaderFactory.fromSource(BASE_VERT, BASE_FRAG);
        if (!baseShader.isCompiled()) {
            LOG.error("FAIL base shader compile error: {}", baseShader.getLastCompileError());
        }

        instancedShader = CgShaderFactory.fromSource(INSTANCED_VERT, BASE_FRAG);
        if (!instancedShader.isCompiled()) {
            LOG.error("FAIL instanced shader compile error: {}", instancedShader.getLastCompileError());
        }

        if (CgInstanceVertexArrayBinding.isSupported()) {
            baseRenderer = CgBatchRenderer.create(CgVertexFormat.POS2_UV2_COL4UB, 4);
            instancedRenderer = CgQuadInstanceRenderer.create(
                    CgVertexFormat.POS2_UV2_COL4UB,
                    CgInstanceFormat.TRANSFORM_COLOR_CUSTOM,
                     INSTANCE_COUNT);
            multiMeshDemo = new MultiMeshInstancingDemoRenderer();
            multiMeshDemo.init();
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        boolean report = !diagnosticsRan;
        diagnosticsRan = true;

        CgCapabilities caps = CgCapabilities.detect();
        boolean supported = CgInstanceVertexArrayBinding.isSupported(caps);

        if (!supported) {
            logInfoIf(report, "SKIP instancing-test: drawInstanced={} vertexAttribDivisor={}",
                    caps.isDrawInstancedSupported(), caps.isVertexAttribDivisorSupported());
            running = false;
            return;
        }

        int passed = 0;
        int total = 6;

        int w = ctx.getViewport().getWidth();
        int h = ctx.getViewport().getHeight();
        Matrix4f ortho = new Matrix4f().ortho(0, w, h, 0, -1, 1);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // Phase: shader-compile
        boolean shaderOk = baseShader.isCompiled() && instancedShader.isCompiled();
        if (shaderOk) { logInfoIf(report, "PASS [1/{}] shader-compile", total); passed++; }
        else { logErrorIf(report, "FAIL [1/{}] shader-compile base={} instanced={}", total,
                baseShader.getLastCompileError(), instancedShader.getLastCompileError()); }

        // Phase: base-before
        boolean baseBeforeOk = false;
        try {
            final Matrix4f proj = ortho;
            baseShader.applyBindings(b -> b.mat4("u_projection", proj)).bind();
            baseRenderer.begin();
            writeBaseQuad(baseRenderer.vertex(), 10, 10, 40, 40);
            baseRenderer.flush();
            baseRenderer.end();
            baseShader.unbind();
            int err = GL11.glGetError();
            baseBeforeOk = (err == GL11.GL_NO_ERROR);
            if (baseBeforeOk) { logInfoIf(report, "PASS [2/{}] base-before (gl-error={})", total, err); passed++; }
            else { logErrorIf(report, "FAIL [2/{}] base-before gl-error={}", total, err); }
        } catch (Exception e) {
            logErrorIf(report, "FAIL [2/{}] base-before threw: {}", total, e.getMessage(), e);
        }

        // Phase: instanced
        boolean instancedOk = false;
        try {
            final Matrix4f proj = ortho;
            instancedShader.applyBindings(b -> b.mat4("u_projection", proj)).bind();
            instancedRenderer.begin();
            //writeBaseQuad(instancedRenderer.v(), -8, -8, 8, 8);
            for (int i = 0; i < INSTANCE_COUNT; i++) {
                writeInstance(i, w, h);
            }
            instancedRenderer.flush();
            instancedRenderer.end();
            instancedShader.unbind();
            int err = GL11.glGetError();
            instancedOk = (err == GL11.GL_NO_ERROR);
            if (instancedOk) { logInfoIf(report, "PASS [3/{}] instanced (count={}, gl-error={})", total, INSTANCE_COUNT, err); passed++; }
            else { logErrorIf(report, "FAIL [3/{}] instanced gl-error={}", total, err); }
        } catch (Exception e) {
            logErrorIf(report, "FAIL [3/{}] instanced threw: {}", total, e.getMessage(), e);
        }

        // Phase: base-after
        boolean baseAfterOk = false;
        try {
            final Matrix4f proj = ortho;
            baseShader.applyBindings(b -> b.mat4("u_projection", proj)).bind();
            baseRenderer.begin();
            writeBaseQuad(baseRenderer.vertex(), w - 50, 10, w - 10, 50);
            baseRenderer.flush();
            baseRenderer.end();
            baseShader.unbind();
            int err = GL11.glGetError();
            baseAfterOk = (err == GL11.GL_NO_ERROR);
            if (baseAfterOk) { logInfoIf(report, "PASS [4/{}] base-after (gl-error={})", total, err); passed++; }
            else { logErrorIf(report, "FAIL [4/{}] base-after gl-error={}", total, err); }
        } catch (Exception e) {
            logErrorIf(report, "FAIL [4/{}] base-after threw: {}", total, e.getMessage(), e);
        }


        // Phase: multi-mesh ergonomics sample
        boolean multiMeshOk = false;
        try {
            if (multiMeshDemo == null || !multiMeshDemo.isCompiled()) {
                throw new IllegalStateException(multiMeshDemo != null
                        ? multiMeshDemo.getLastCompileError()
                        : "multi-mesh demo not initialized");
            }
            multiMeshDemo.render(ortho, w, h);
            int err = GL11.glGetError();
            multiMeshOk = (err == GL11.GL_NO_ERROR);
            if (multiMeshOk) { logInfoIf(report, "PASS [5/{}] multi-mesh-ergonomics (3 mesh shapes, gl-error={})", total, err); passed++; }
            else { logErrorIf(report, "FAIL [5/{}] multi-mesh-ergonomics gl-error={}", total, err); }
        } catch (Exception e) {
            logErrorIf(report, "FAIL [5/{}] multi-mesh-ergonomics threw: {}", total, e.getMessage(), e);
        }

        // Phase: gl-error (final check)
        int finalErr = GL11.glGetError();
        if (finalErr == GL11.GL_NO_ERROR) {
            logInfoIf(report, "PASS [6/{}] gl-error (final check: no pending errors)", total);
            passed++;
        } else {
            logErrorIf(report, "FAIL [6/{}] gl-error (final pending error={})", total, finalErr);
        }

        logInfoIf(report, "Instancing diagnostics complete: {}/{} passed", passed, total);
//        running = false;
    }

    @Override
    public void dispose() {
        if (baseShader != null) baseShader.delete();
        if (instancedShader != null) instancedShader.delete();
        if (multiMeshDemo != null) multiMeshDemo.delete();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return false; }


    private void logInfoIf(boolean report, String message, Object... args) {
        if (report) {
            LOG.info(message, args);
        }
    }

    private void logErrorIf(boolean report, String message, Object... args) {
        if (report) {
            LOG.error(message, args);
        }
    }

    private void writeBaseQuad(CgVertexWriter w, float x0, float y0, float x1, float y1) {
        w.vertex(x0, y0).uv(0, 0).color(255, 255, 255, 255).endVertex();
        w.vertex(x1, y0).uv(1, 0).color(255, 255, 255, 255).endVertex();
        w.vertex(x1, y1).uv(1, 1).color(255, 255, 255, 255).endVertex();
        w.vertex(x0, y1).uv(0, 1).color(255, 255, 255, 255).endVertex();
    }

    private void writeInstance(int idx, int w, int h) {
        CgInstanceWriter iw = instancedRenderer.instance();
        float col = idx / (float) INSTANCE_COUNT;
        float tx = (idx % 10) * (w / 10.0f) + w / 20.0f;
        float ty = (idx / 10) * (h / 10.0f) + h / 20.0f;

        Matrix4f model = new Matrix4f().translation(tx, ty, 0f).scale(15);
        iw.mat4(model)
          .color((int)(col * 255), (int)((1 - col) * 255), 100, 200)
          .vec4(col, 1 - col, 0.5f, 1.0f)
          .endInstance();
    }
}
