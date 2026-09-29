package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.util.HarnessBuffers;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.api.shader.CgActiveUniform;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.gl.shader.CgShaderFactory;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.object.VertexBinding;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;

import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;

/**
 * Interactive harness scene that exercises the GLSL shader library
 * ({@code crystalgraphics:shaders/lib/}) via a fullscreen quad.
 *
 * <p>The fragment shader uses {@code #include} to pull in {@code math.glsl},
 * {@code color.glsl}, {@code noise.glsl}, and {@code uv.glsl}. It cycles
 * through four visual modes every three seconds, demonstrating:</p>
 * <ol>
 *   <li>Mode 0 — raw {@code value_noise}</li>
 *   <li>Mode 1 — {@code fbm4} + {@code fbm_ridged}</li>
 *   <li>Mode 2 — {@code rotate_hue}, {@code luminance}, {@code desaturate}</li>
 *   <li>Mode 3 — {@code rotate_uv} + {@code cartesian_to_polar_uv}</li>
 * </ol>
 *
 * <p>On {@code init}, {@link #runDiagnostics()} is called once to verify the
 * three new shader features: inline source compilation, compile error retrieval,
 * and uniform introspection.  Results are logged with PASS/FAIL prefix to
 * {@code CrystalGraphics.ShaderTest}.</p>
 *
 * <p>Register in {@code SceneRegistry} under the mode id {@code "shader-lib-test"}.</p>
 */
public class ShaderLibTestScene implements InteractiveSceneLifecycle {

    private static final Logger TEST_LOG = LogManager.getLogger("CrystalGraphics.ShaderTest");

    private static final String SHADER_DIR = "assets/harness/shader/shader_lib_test/";
    private static final float  MODE_DURATION = 3.0f;

    // Trivial vert used by multiple diagnostics tests.
    private static final String TRIVIAL_VERT =
        "#version 330 core\nin vec2 a_pos;\nvoid main() { gl_Position = vec4(a_pos, 0.0, 1.0); }";

    private CgShader     testShader;
    private VertexBinding quad;
    private float         elapsedTime;

    @Override
    public void init(HarnessContext ctx) {
        testShader = CgShaderFactory.load(SHADER_DIR + "test.vert", SHADER_DIR + "test.frag");
        quad = buildQuad();
        runDiagnostics();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        elapsedTime += (float) frame.getDeltaTime();
        int mode = (int) (elapsedTime / MODE_DURATION) % 4;

        CgGL.glDisable(CgGL.GL_DEPTH_TEST);
        CgGL.glEnable(CgGL.GL_BLEND);
        CgGL.glBlendFunc(CgGL.GL_SRC_ALPHA, CgGL.GL_ONE_MINUS_SRC_ALPHA);

        final float time = elapsedTime;
        final int   m    = mode;
        testShader.applyBindings(b -> {
            b.set1f("u_time", time);
            b.set1i("u_mode", m);
        }).bind();

        CgGL.glBindVertexArray(quad.vaoId);
        CgGL.glDrawElements(CgGL.GL_TRIANGLES, quad.indexCount, CgGL.GL_UNSIGNED_INT, 0L);
    }

    @Override
    public void dispose() {
        if (quad != null)       quad.dispose();
        if (testShader != null) testShader.delete();
    }

    @Override public boolean isRunning()              { return true;  }
    @Override public boolean uses3DCamera()           { return false; }
    @Override public boolean shouldShutdownOnComplete() { return false; }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    /**
     * Runs four compile-time shader feature verification tests and logs results.
     * Does not render anything.  Failures are non-fatal; each test is isolated
     * in its own try-catch so one failure does not prevent subsequent tests from running.
     */
    private void runDiagnostics() {
        int passed = 0;
        int total  = 4;

        // ── Test 1: Inline source shader compiles ─────────────────────
        try {
            String frag1 = "#version 330 core\nout vec4 fragColor;\nvoid main() { fragColor = vec4(1.0); }";
            CgShader inlineShader = CgShaderFactory.fromSource(TRIVIAL_VERT, frag1);
            boolean ok = inlineShader.isCompiled() && inlineShader.getLastCompileError() == null;
            inlineShader.delete();
            if (ok) {
                TEST_LOG.info("PASS [1/4] Inline source shader compiles");
                passed++;
            } else {
                TEST_LOG.error("FAIL [1/4] Inline source shader did not compile (isCompiled={}, error={})",
                    inlineShader.isCompiled(), inlineShader.getLastCompileError());
            }
        } catch (Exception e) {
            TEST_LOG.error("FAIL [1/4] Inline source shader threw exception: {}", e.getMessage(), e);
        }

        // ── Test 2: Bad inline shader captures error ───────────────────
        try {
            String badVert = "#version 330 core\nvoid main() { THIS_IS_NOT_GLSL }";
            String frag2   = "#version 330 core\nout vec4 fragColor;\nvoid main() { fragColor = vec4(1.0); }";
            CgShader badShader = CgShaderFactory.fromSource(badVert, frag2);
            boolean compiled     = badShader.isCompiled();
            String  errorMessage = badShader.getLastCompileError();
            badShader.delete();
            boolean ok = !compiled && errorMessage != null && !errorMessage.isEmpty();
            if (ok) {
                TEST_LOG.info("PASS [2/4] Bad inline shader error captured: {}", errorMessage);
                passed++;
            } else {
                TEST_LOG.error("FAIL [2/4] Bad inline shader: isCompiled={}, error={}",
                    compiled, errorMessage);
            }
        } catch (Exception e) {
            TEST_LOG.error("FAIL [2/4] Bad inline shader threw unexpected exception: {}", e.getMessage(), e);
        }

        // ── Test 3: Uniform introspection on testShader ────────────────
        try {
            // Ensure the test shader is compiled by triggering bind/unbind.
            testShader.bind();
            testShader.unbind();

            List<CgActiveUniform> uniforms = testShader.getActiveUniforms();
            boolean foundTime = false;
            boolean foundMode = false;
            for (int i = 0; i < uniforms.size(); i++) {
                CgActiveUniform u = uniforms.get(i);
                if ("u_time".equals(u.name())) foundTime = true;
                if ("u_mode".equals(u.name())) foundMode = true;
            }
            boolean ok = foundTime && foundMode;
            if (ok) {
                TEST_LOG.info("PASS [3/4] Uniform introspection: found u_time and u_mode ({} total uniforms)",
                    uniforms.size());
                passed++;
            } else {
                TEST_LOG.error("FAIL [3/4] Uniform introspection: foundTime={}, foundMode={}, uniforms={}",
                    foundTime, foundMode, uniforms);
            }
        } catch (Exception e) {
            TEST_LOG.error("FAIL [3/4] Uniform introspection threw exception: {}", e.getMessage(), e);
        }

        // ── Test 4: Inline shader with preprocessor #include ──────────
        try {
            String fragWithInclude =
                "#version 330 core\n"
                + "#include \"crystalgraphics:shaders/lib/math.glsl\"\n"
                + "out vec4 fragColor;\n"
                + "void main() { fragColor = vec4(saturate(1.5), 0.0, 0.0, 1.0); }";
            CgShader includedShader = CgShaderFactory.fromSource(TRIVIAL_VERT, fragWithInclude);
            includedShader.preprocess(new CgShaderPreprocessor());
            includedShader.bind();
            boolean ok = includedShader.isCompiled();
            includedShader.unbind();
            includedShader.delete();
            if (ok) {
                TEST_LOG.info("PASS [4/4] Inline shader with #include compiled via preprocessor");
                passed++;
            } else {
                TEST_LOG.error("FAIL [4/4] Inline shader with #include failed to compile: {}",
                    includedShader.getLastCompileError());
            }
        } catch (Exception e) {
            TEST_LOG.error("FAIL [4/4] Inline shader with #include threw exception: {}", e.getMessage(), e);
        }

        TEST_LOG.info("Shader diagnostics complete: {}/{} passed", passed, total);
    }

    // ── Geometry ──────────────────────────────────────────────────────────────

    private static VertexBinding buildQuad() {
        // NDC fullscreen quad: POS3_UV2_COL4UB — stride = 3*4 + 2*4 + 4 = 24 bytes
        float[] verts = {
            -1f, -1f, 0f,  0f, 0f,  col(0xffffffff),
             1f, -1f, 0f,  1f, 0f,  col(0xffff8800),
             1f,  1f, 0f,  1f, 1f,  col(0xff00ff88),
            -1f,  1f, 0f,  0f, 1f,  col(0xff8800ff),
        };
        int[] indices = { 0, 1, 2, 2, 3, 0 };

        FloatBuffer vb = BufferUtils.createFloatBuffer(verts.length);
        vb.put(verts).flip();

        IntBuffer ib = BufferUtils.createIntBuffer(indices.length);
        ib.put(indices).flip();

        int vao = CgGL.glGenVertexArrays();
        CgGL.glBindVertexArray(vao);

        int vbo = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vbo);
        CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, HarnessBuffers.bytes(vb), CgGL.GL_STATIC_DRAW);

        int ebo = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, ebo);
        CgGL.glBufferData(CgGL.GL_ELEMENT_ARRAY_BUFFER, HarnessBuffers.bytes(ib), CgGL.GL_STATIC_DRAW);

        CgGL.glVertexAttribPointer(0, 3, CgGL.GL_FLOAT,         false, 24, 0);   // a_pos
        CgGL.glVertexAttribPointer(1, 2, CgGL.GL_FLOAT,         false, 24, 12);  // a_uv
        CgGL.glVertexAttribPointer(2, 4, CgGL.GL_UNSIGNED_BYTE, true,  24, 20);  // a_col

        CgGL.glEnableVertexAttribArray(0);
        CgGL.glEnableVertexAttribArray(1);
        CgGL.glEnableVertexAttribArray(2);

        CgGL.glBindVertexArray(0);

        return new VertexBinding(vao, vbo, ebo, indices.length);
    }

    private static float col(int argb) {
        int r = argb >> 16 & 0xff;
        int g = argb >>  8 & 0xff;
        int b = argb       & 0xff;
        int a = argb >> 24 & 0xff;

        int packed;
        if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            packed = a << 24 | b << 16 | g << 8 | r;
        } else {
            packed = r << 24 | g << 16 | b << 8 | a;
        }
        return Float.intBitsToFloat(packed);
    }
}
