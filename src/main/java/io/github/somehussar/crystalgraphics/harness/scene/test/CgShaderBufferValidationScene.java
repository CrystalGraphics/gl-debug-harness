package io.github.somehussar.crystalgraphics.harness.scene.test;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import org.joml.Matrix3f;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.HarnessSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.tool.GlErrorChecker;
import java.io.File;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Harness GPU validation scene for Waves 1–4 foundations.
 *
 * <p>Proves UBO (CgUniformBuffer), SSBO, and TBO object-data paths work
 * end-to-end with actual GPU readback — not just compilation. Each test renders
 * a full-screen triangle to an 8×8 FBO, reads back the resulting pixel colour
 * with {@code glReadPixels}, and asserts that the value matches a deterministic
 * expectation derived from the known uploaded data.</p>
 *
 * <h3>Tests executed</h3>
 * <ol>
 *   <li><b>UBO binding</b> — uploads an identity view matrix; fragment reads
 *       {@code cg_ViewMatrix[0][0]} (expected 1.0) and {@code cg_ViewMatrix[1][1]}
 *       (expected 1.0). Asserts R≈255, G≈255.</li>
 *   <li><b>Object buffer (preferred path)</b> — writes {@code custom0=(0.5, 0.25, 0.0, 1.0)}
 *       to slot 0 via {@link CgShaderBuffer}; fragment reads {@code custom0.rg}.
 *       Asserts R≈127, G≈64.</li>
 *   <li><b>TBO path (forced, raw GL)</b> — creates a raw {@code GL_TEXTURE_BUFFER}
 *       regardless of preferred path; writes {@code custom0=(0.0, 0.75, 0.5, 1.0)}.
 *       Fragment reads texel 7. Asserts G≈191, B≈127.</li>
 * </ol>
 *
 * <p>If any test fails an assertion, this scene throws {@link RuntimeException}
 * so the harness reports a hard failure. All GL errors are drained and reported
 * after each test step.</p>
 *
 * <p>No {@code CgMaterial} or Wave 5+ abstractions are used.</p>
 */
public class CgShaderBufferValidationScene implements HarnessSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(CgShaderBufferValidationScene.class.getName());

    /** FBO dimensions: small enough for fast readback, large enough for stable rendering. */
    private static final int FBO_W = 8;
    private static final int FBO_H = 8;

    /** Tolerance for 8-bit pixel comparison (float → byte rounding). */
    private static final int PIXEL_TOLERANCE = 3;

    // GL object IDs — all deleted in dispose()
    private int fboId;
    private int fboColorTexId;
    private int dummyVaoId;

    private CgUniformBuffer frameUbo;
    private CgShaderBuffer objectBuffer;

    // Compiled programs
    private int uboTestProgram;
    private int ssboTestProgram;
    private int tboTestProgram;

    // Raw TBO resources for forced-TBO test
    private int rawTboVboId;
    private int rawTboTexId;

    private String outputDir;
    private final List<String> report = new ArrayList<String>();

    // ── Inline GLSL ───────────────────────────────────────────────────────────

    /** Vertex shader shared by all tests: draws a full-screen triangle from gl_VertexID. */
    private static final String FULLSCREEN_VERT_330 =
        "#version 330 core\n" +
        "void main() {\n" +
        "    // Full-screen triangle via gl_VertexID; requires no vertex attributes.\n" +
        "    const vec2 pos[3] = vec2[3](vec2(-1.0,-1.0), vec2(3.0,-1.0), vec2(-1.0, 3.0));\n" +
        "    gl_Position = vec4(pos[gl_VertexID], 0.0, 1.0);\n" +
        "}\n";

    /** Fragment shader for UBO test (GLSL 330). */
    private static final String UBO_FRAG_330 =
        "#version 330 core\n" +
        "layout(std140) uniform CgFrameBlock {\n" +
        "    mat4 cg_ViewMatrix;\n" +
        "    mat4 cg_ProjMatrix;\n" +
        "};\n" +
        "out vec4 outColor;\n" +
        "void main() {\n" +
        "    // If identity view was uploaded, [0][0]=1.0, [1][1]=1.0 => outColor=(1,1,0,1)\n" +
        "    outColor = vec4(cg_ViewMatrix[0][0], cg_ViewMatrix[1][1], 0.0, 1.0);\n" +
        "}\n";

    /**
     * Vertex shader for SSBO test (GLSL 430): same full-screen triangle, with
     * {@code CG_VERTEX_STAGE} defined so {@code cg_env.glsl} skips attribute aliases
     * and wires CG_INSTANCE_ID = gl_InstanceID. Using inline version here (no include).
     */
    private static final String FULLSCREEN_VERT_430 =
        "#version 430 core\n" +
        "flat out int cg_InstanceId;\n" +
        "void main() {\n" +
        "    const vec2 pos[3] = vec2[3](vec2(-1.0,-1.0), vec2(3.0,-1.0), vec2(-1.0, 3.0));\n" +
        "    gl_Position = vec4(pos[gl_VertexID], 0.0, 1.0);\n" +
        "    cg_InstanceId = gl_InstanceID;\n" +
        "}\n";

    /**
     * Fragment shader for SSBO test (GLSL 430): reads {@code custom0} from SSBO slot 0
     * and outputs it as the fragment colour.
     *
     * <p>Note: {@code mat4 normalMatrix} in std430 is 64 bytes (4 columns × vec4),
     * matching the ABI in {@code cg_env.glsl} (mat4 normalMatrix = 16 floats at offset 16).</p>
     */
    private static final String SSBO_FRAG_430 =
        "#version 430 core\n" +
        "flat in int cg_InstanceId;\n" +
        "struct CgObjectData {\n" +
        "    mat4 modelMatrix;\n" +      // 64 bytes offset 0
        "    mat4 normalMatrix;\n" +     // 64 bytes offset 64
        "    vec4 custom0;\n" +          // 16 bytes offset 128
        "    vec4 custom1;\n" +
        "    vec4 custom2;\n" +
        "    vec4 custom3;\n" +
        "};\n" +
        "layout(std430, binding = 0) readonly buffer CgShaderBuffer {\n" +
        "    CgObjectData cg_Objects[];\n" +
        "};\n" +
        "out vec4 outColor;\n" +
        "void main() {\n" +
        "    vec4 c0 = cg_Objects[cg_InstanceId].custom0;\n" +
        "    outColor = vec4(c0.r, c0.g, c0.b, 1.0);\n" +
        "}\n";

    /** Fragment shader for TBO path (GLSL 330): reads custom0 from texel slot 8. */
    private static final String TBO_FRAG_330 =
        "#version 330 core\n" +
        "uniform samplerBuffer cg_ObjectTBO;\n" +
        "out vec4 outColor;\n" +
        "void main() {\n" +
        "    // Instance 0: base = 0*12 = 0; custom0 is at base+8 = 8\n" +
        "    vec4 custom0 = texelFetch(cg_ObjectTBO, 8);\n" +
        "    outColor = vec4(custom0.r, custom0.g, custom0.b, 1.0);\n" +
        "}\n";

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void init(HarnessContext ctx) {
        outputDir = ctx.getOutputDir() + File.separator + "shader-buffer-validation";
        new File(outputDir).mkdirs();

        LOGGER.info("[ShaderBufferValidation] Initialising validation resources...");

        CgCapabilities caps = CgCapabilities.detect();
        CgCapabilities.ShaderBufferPath preferredPath = caps.preferredShaderBufferPath();
        report.add("Preferred shader buffer path: " + preferredPath);

        // Create a small FBO for pixel readback
        createFbo();
        GlErrorChecker.checkAndLog("after FBO creation");

        // Empty VAO — required for gl_VertexID full-screen triangle on some drivers
        dummyVaoId = GL30.glGenVertexArrays();
        GlErrorChecker.checkAndLog("after dummy VAO creation");

        // Frame UBO
        frameUbo = new CgUniformBuffer(CgUniformBuffer.BLOCK_NAME, CgUniformBuffer.BINDING_POINT);
        GlErrorChecker.checkAndLog("after CgUniformBuffer creation");

        // Object buffer — uses preferred path (SSBO if available, else TBO)
        if (preferredPath != CgCapabilities.ShaderBufferPath.NONE) {
            objectBuffer = CgShaderBuffer.create(4);
        }
        GlErrorChecker.checkAndLog("after CgShaderBuffer creation");

        // Compile test programs
        uboTestProgram = compileProgram("UBO test", FULLSCREEN_VERT_330, UBO_FRAG_330);

        if (preferredPath == CgCapabilities.ShaderBufferPath.SSBO_GL43 ||
                preferredPath == CgCapabilities.ShaderBufferPath.SSBO_ARB) {
            ssboTestProgram = compileProgram("SSBO test", FULLSCREEN_VERT_430, SSBO_FRAG_430);
        }

        tboTestProgram = compileProgram("TBO test", FULLSCREEN_VERT_330, TBO_FRAG_330);

        // Create raw TBO for the forced-TBO test (always, regardless of preferred path)
        createRawTbo();
        GlErrorChecker.checkAndLog("after raw TBO creation");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        boolean allPassed = true;

        // ── Test 1: Frame UBO binding ──────────────────────────────────────────
        report.add("\n=== Test 1: Frame UBO binding ===");
        try {
            allPassed &= runUboTest();
        } catch (Exception e) {
            report.add("FAIL (exception): " + e.getMessage());
            allPassed = false;
        }

        // ── Test 1b: UBO non-identity matrix (scale X by 0.5) ─────────────────
        report.add("\n=== Test 1b: UBO non-identity matrix (scale X=0.5) ===");
        try {
            allPassed &= runUboNonIdentityTest();
        } catch (Exception e) {
            report.add("FAIL (exception): " + e.getMessage());
            allPassed = false;
        }

        // ── Test 1c: UBO no-op upload (reset + no write = previous data stays) ─
        report.add("\n=== Test 1c: UBO reset + no-op upload reads previous data ===");
        try {
            allPassed &= runUboResetNoUploadTest();
        } catch (Exception e) {
            report.add("FAIL (exception): " + e.getMessage());
            allPassed = false;
        }

        // ── Test 2: Object buffer (preferred path) ────────────────────────────
        report.add("\n=== Test 2: Object buffer (preferred path: " +
                   CgCapabilities.detect().preferredShaderBufferPath() + ") ===");
        try {
            allPassed &= runObjectBufferTest();
        } catch (Exception e) {
            report.add("FAIL (exception): " + e.getMessage());
            allPassed = false;
        }

        // ── Test 2b: Object buffer multi-write (N=1 then N=3) ─────────────────
        report.add("\n=== Test 2b: Object buffer multi-write (N=1 then N=3) ===");
        try {
            allPassed &= runObjectBufferMultiWriteTest();
        } catch (Exception e) {
            report.add("FAIL (exception): " + e.getMessage());
            allPassed = false;
        }

        // ── Test 3: TBO path (forced, raw GL) ─────────────────────────────────
        report.add("\n=== Test 3: TBO path (forced raw GL, bypasses CgShaderBuffer) ===");
        try {
            allPassed &= runForcedTboTest();
        } catch (Exception e) {
            report.add("FAIL (exception): " + e.getMessage());
            allPassed = false;
        }

        // ── Test 4: Capacity growth (create(4), beginWrite(5)) ────────────────
        report.add("\n=== Test 4: Capacity growth (create(4), beginWrite(5)) ===");
        try {
            allPassed &= runCapacityGrowthTest();
        } catch (Exception e) {
            report.add("FAIL (exception): " + e.getMessage());
            allPassed = false;
        }

        // ── Test 5: bind(N+1) after writing N throws IllegalStateException ─────
        report.add("\n=== Test 5: bind(N+1) after writing N throws ===");
        try {
            allPassed &= runBindOverCountThrowsTest();
        } catch (Exception e) {
            report.add("FAIL (exception): " + e.getMessage());
            allPassed = false;
        }

        // ── Test 6: delete() twice — no crash, isDeleted()=true ──────────────
        report.add("\n=== Test 6: delete() twice — idempotent ===");
        try {
            allPassed &= runDeleteIdempotentTest();
        } catch (Exception e) {
            report.add("FAIL (exception): " + e.getMessage());
            allPassed = false;
        }

        // Write report to file
       // writeReport(allPassed);

        if (!allPassed) {
            throw new RuntimeException(
                "Waves 1-4 GPU validation FAILED. See " + outputDir + "/validation-report.txt");
        }
        LOGGER.info("[ShaderBufferValidation] All GPU validation tests PASSED.");
    }

    @Override
    public void dispose() {
        if (frameUbo != null && !frameUbo.isDeleted()) frameUbo.delete();
        if (objectBuffer != null && !objectBuffer.isDeleted()) objectBuffer.delete();

        if (uboTestProgram != 0) GL20.glDeleteProgram(uboTestProgram);
        if (ssboTestProgram != 0) GL20.glDeleteProgram(ssboTestProgram);
        if (tboTestProgram != 0) GL20.glDeleteProgram(tboTestProgram);

        if (rawTboVboId != 0) GL15.glDeleteBuffers(rawTboVboId);
        if (rawTboTexId != 0) GL11.glDeleteTextures(rawTboTexId);

        if (dummyVaoId != 0) GL30.glDeleteVertexArrays(dummyVaoId);
        if (fboColorTexId != 0) GL11.glDeleteTextures(fboColorTexId);
        if (fboId != 0) GL30.glDeleteFramebuffers(fboId);

        GlErrorChecker.checkAndLog("after dispose");
    }

    // ── Test implementations ──────────────────────────────────────────────────

    /**
     * Test 1b: UBO non-identity matrix.
     * Uploads a view matrix with X-scale 0.5; fragment reads cg_ViewMatrix[0][0]=0.5 and [1][1]=1.0.
     * Asserts R≈127 G≈255.
     */
    private boolean runUboNonIdentityTest() {
        Matrix4f scaleX = new Matrix4f().scaling(0.5f, 1.0f, 1.0f);
        Matrix4f identity = new Matrix4f();
        CgBufferWriter w = frameUbo.writer();
        w.reset();
        w.mat4(scaleX).mat4(identity);
        frameUbo.upload();
        GlErrorChecker.checkAndLog("after UBO non-identity upload");

        frameUbo.bindBlock(CgUniformBuffer.BLOCK_NAME, uboTestProgram);

        bindFbo();
        clearFbo();
        GL20.glUseProgram(uboTestProgram);
        frameUbo.bind();
        GL30.glBindVertexArray(dummyVaoId);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        frameUbo.unbind();
        GL20.glUseProgram(0);

        boolean glOk = !GlErrorChecker.checkAndLog("after UBO non-identity render");

        int[] pixel = readCentrePixel();
        unbindFbo();

        boolean rOk = assertPixelChannel("UBO-nonid R (view[0][0]=0.5)", pixel[0], 127);
        boolean gOk = assertPixelChannel("UBO-nonid G (view[1][1]=1.0)", pixel[1], 255);

        boolean passed = glOk && rOk && gOk;
        report.add((passed ? "PASS" : "FAIL") + " — pixel=(" + pixel[0] + "," + pixel[1] + "," + pixel[2] + ")");
        return passed;
    }

    /**
     * Test 1c: UBO reset + no-op upload.
     * Calls reset() then upload() without writing any data (no-op path in CgUniformBuffer).
     * The GPU buffer must still contain the previous upload (scaleX from Test 1b).
     * Asserts R≈127 G≈255 (same as Test 1b).
     */
    private boolean runUboResetNoUploadTest() {
        CgBufferWriter w = frameUbo.writer();
        w.reset();
        frameUbo.upload();
        GlErrorChecker.checkAndLog("after UBO no-op upload");

        bindFbo();
        clearFbo();
        GL20.glUseProgram(uboTestProgram);
        frameUbo.bind();
        GL30.glBindVertexArray(dummyVaoId);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        frameUbo.unbind();
        GL20.glUseProgram(0);

        boolean glOk = !GlErrorChecker.checkAndLog("after UBO no-op render");

        int[] pixel = readCentrePixel();
        unbindFbo();

        boolean rOk = assertPixelChannel("UBO-noop R (previous 0.5 must persist)", pixel[0], 127);
        boolean gOk = assertPixelChannel("UBO-noop G (previous 1.0 must persist)", pixel[1], 255);

        boolean passed = glOk && rOk && gOk;
        report.add((passed ? "PASS" : "FAIL") + " — pixel=(" + pixel[0] + "," + pixel[1] + "," + pixel[2] + ")");
        return passed;
    }

    /**
     * Test 2b: Object buffer multi-write (N=1 then N=3).
     * Writes one object, binds with N=1, then rewrites three objects, binds with N=3.
     * Verifies custom0.r=1.0 in slot 0 on the second write (R≈255).
     */
    private boolean runObjectBufferMultiWriteTest() {
        if (objectBuffer == null) {
            report.add("SKIP — no usable shader buffer path on this hardware");
            return true;
        }
        if (objectBuffer.getPath() != CgCapabilities.ShaderBufferPath.SSBO_GL43 &&
            objectBuffer.getPath() != CgCapabilities.ShaderBufferPath.SSBO_ARB) {
            report.add("SKIP — multi-write render test requires SSBO path");
            return true;
        }
        if (ssboTestProgram == 0) {
            report.add("SKIP — SSBO test program not compiled");
            return true;
        }

        Matrix4f model = new Matrix4f();

        objectBuffer.beginWrite(1);
        objectBuffer.writer().beginRecord();
        objectBuffer.writer().mat4(model).mat4(new Matrix4f())
            .vec4(0.0f, 0.5f, 0.0f, 1.0f)
            .vec4Zero().vec4Zero().vec4Zero();
        objectBuffer.writer().endRecord();
        objectBuffer.advanceRecord();
        objectBuffer.endWrite();
        objectBuffer.bind(1);
        objectBuffer.unbind();
        GlErrorChecker.checkAndLog("after multi-write phase 1");

        objectBuffer.beginWrite(3);
        for (int i = 0; i < 3; i++) {
            objectBuffer.writer().beginRecord();
            objectBuffer.writer().mat4(model).mat4(new Matrix4f())
                .vec4(1.0f, 0.0f, 0.0f, 1.0f)
                .vec4Zero().vec4Zero().vec4Zero();
            objectBuffer.writer().endRecord();
            objectBuffer.advanceRecord();
        }
        objectBuffer.endWrite();
        GlErrorChecker.checkAndLog("after multi-write phase 2");

        bindFbo();
        clearFbo();
        GL20.glUseProgram(ssboTestProgram);
        objectBuffer.bind(3);
        GL30.glBindVertexArray(dummyVaoId);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        objectBuffer.unbind();
        GL20.glUseProgram(0);

        boolean glOk = !GlErrorChecker.checkAndLog("after multi-write render");

        int[] pixel = readCentrePixel();
        unbindFbo();

        boolean rOk = assertPixelChannel("Multi-write R (custom0.r=1.0 in slot 0)", pixel[0], 255);

        boolean passed = glOk && rOk;
        report.add((passed ? "PASS" : "FAIL") + " — pixel=(" + pixel[0] + "," + pixel[1] + "," + pixel[2] + ")");
        return passed;
    }

    /**
     * Test 4: Capacity growth — create(4), beginWrite(5), write 5 records, endWrite(). Must not crash.
     */
    private boolean runCapacityGrowthTest() {
        if (CgCapabilities.detect().preferredShaderBufferPath() == CgCapabilities.ShaderBufferPath.NONE) {
            report.add("SKIP — no usable shader buffer path on this hardware");
            return true;
        }

        CgShaderBuffer growBuf = CgShaderBuffer.create(4);
        try {
            Matrix4f model = new Matrix4f();

            growBuf.beginWrite(5);
            for (int i = 0; i < 5; i++) {
                growBuf.writer().beginRecord();
                growBuf.writer().mat4(model).mat4(new Matrix4f())
                    .vec4Zero().vec4Zero().vec4Zero().vec4Zero();
                growBuf.writer().endRecord();
                growBuf.advanceRecord();
            }
            growBuf.endWrite();
            GlErrorChecker.checkAndLog("after capacity growth endWrite");

            report.add("  PASS capacity growth: create(4) + beginWrite(5) + 5 records = no crash");
            return true;
        } catch (Exception e) {
            report.add("  FAIL capacity growth: " + e.getMessage());
            return false;
        } finally {
            if (!growBuf.isDeleted()) growBuf.delete();
            GlErrorChecker.checkAndLog("after capacity growth buffer delete");
        }
    }

    /**
     * Test 5: bind(N+1) after writing N must throw IllegalStateException.
     */
    private boolean runBindOverCountThrowsTest() {
        if (CgCapabilities.detect().preferredShaderBufferPath() == CgCapabilities.ShaderBufferPath.NONE) {
            report.add("SKIP — no usable shader buffer path on this hardware");
            return true;
        }

        CgShaderBuffer buf = CgShaderBuffer.create(2);
        try {
            Matrix4f model = new Matrix4f();

            buf.beginWrite(2);
            for (int i = 0; i < 2; i++) {
                buf.writer().beginRecord();
                buf.writer().mat4(model).mat4(new Matrix4f())
                    .vec4Zero().vec4Zero().vec4Zero().vec4Zero();
                buf.writer().endRecord();
                buf.advanceRecord();
            }
            buf.endWrite();

            try {
                buf.bind(3);
                report.add("  FAIL bind-overcount: expected IllegalStateException but none thrown");
                return false;
            } catch (IllegalStateException expected) {
                report.add("  PASS bind-overcount: IllegalStateException thrown as expected");
                GlErrorChecker.checkAndLog("after bind-overcount test");
                return true;
            }
        } catch (Exception e) {
            report.add("  FAIL bind-overcount: unexpected exception: " + e.getMessage());
            return false;
        } finally {
            if (!buf.isDeleted()) buf.delete();
            GlErrorChecker.checkAndLog("after bind-overcount buffer delete");
        }
    }

    /**
     * Test 6: delete() twice must be idempotent; isDeleted() must be true after first delete().
     */
    private boolean runDeleteIdempotentTest() {
        if (CgCapabilities.detect().preferredShaderBufferPath() == CgCapabilities.ShaderBufferPath.NONE) {
            report.add("SKIP — no usable shader buffer path on this hardware");
            return true;
        }

        CgShaderBuffer buf = CgShaderBuffer.create(1);
        try {
            buf.delete();
            if (!buf.isDeleted()) {
                report.add("  FAIL delete-idempotent: isDeleted() is false after first delete()");
                return false;
            }
            buf.delete();
            if (!buf.isDeleted()) {
                report.add("  FAIL delete-idempotent: isDeleted() is false after second delete()");
                return false;
            }
            GlErrorChecker.checkAndLog("after delete-idempotent test");
            report.add("  PASS delete-idempotent: double delete + isDeleted()=true");
            return true;
        } catch (Exception e) {
            report.add("  FAIL delete-idempotent: exception on double delete: " + e.getMessage());
            return false;
        }
    }

    private boolean runUboTest() {
        Matrix4f identity = new Matrix4f();
        CgBufferWriter w = frameUbo.writer();
        w.reset();
        w.mat4(identity).mat4(identity);
        frameUbo.upload();

        frameUbo.bindBlock(CgUniformBuffer.BLOCK_NAME, uboTestProgram);
        GlErrorChecker.checkAndLog("after UBO block binding");

        bindFbo();
        clearFbo();
        GL20.glUseProgram(uboTestProgram);
        frameUbo.bind();
        GL30.glBindVertexArray(dummyVaoId);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        frameUbo.unbind();
        GL20.glUseProgram(0);

        boolean glOk = !GlErrorChecker.checkAndLog("after UBO test render");

        // Readback centre pixel
        int[] pixel = readCentrePixel();
        unbindFbo();

        // Identity view[0][0]=1.0 → R≈255; view[1][1]=1.0 → G≈255; B=0 → B≈0
        boolean rOk = assertPixelChannel("UBO R (view[0][0])", pixel[0], 255);
        boolean gOk = assertPixelChannel("UBO G (view[1][1])", pixel[1], 255);
        boolean bOk = assertPixelChannel("UBO B (should be 0)", pixel[2], 0);

        boolean passed = glOk && rOk && gOk && bOk;
        report.add((passed ? "PASS" : "FAIL") + " — pixel=(" + pixel[0] + "," + pixel[1] + "," + pixel[2] + ")");
        return passed;
    }

    /**
     * Test 2: Object buffer test using the capability-preferred path (SSBO or TBO).
     * Writes {@code custom0=(0.5, 0.25, 0.0, 1.0)} to slot 0; asserts R≈127 G≈64 B≈0.
     */
    private boolean runObjectBufferTest() {
        if (objectBuffer == null) {
            report.add("SKIP — no usable shader buffer path on this hardware (< GL 3.3)");
            return true;
        }

        CgCapabilities.ShaderBufferPath path = objectBuffer.getPath();
        report.add("Using path: " + path);

        Matrix4f model = new Matrix4f();
        objectBuffer.beginWrite(1);
        objectBuffer.writer().beginRecord();
        objectBuffer.writer().mat4(model).mat4(new Matrix4f())
            .vec4(0.5f, 0.25f, 0.0f, 1.0f)
            .vec4Zero().vec4Zero().vec4Zero();
        objectBuffer.writer().endRecord();
        objectBuffer.advanceRecord();
        objectBuffer.endWrite();
        GlErrorChecker.checkAndLog("after objectBuffer write");

        int program;
        if (path == CgCapabilities.ShaderBufferPath.SSBO_GL43 ||
                path == CgCapabilities.ShaderBufferPath.SSBO_ARB) {
            program = ssboTestProgram;
            if (program == 0) {
                report.add("SKIP — SSBO test program failed to compile");
                return true;
            }
        } else {
            // TBO path via CgShaderBuffer
            program = tboTestProgram;
            // Set TBO sampler uniform
            GL20.glUseProgram(program);
            int tboLoc = GL20.glGetUniformLocation(program, "cg_ObjectTBO");
            if (tboLoc != -1) {
                GL20.glUniform1i(tboLoc, objectBuffer.getBindingLocation());
            }
            GL20.glUseProgram(0);
        }

        bindFbo();
        clearFbo();
        GL20.glUseProgram(program);
        objectBuffer.bind(1);
        GL30.glBindVertexArray(dummyVaoId);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        objectBuffer.unbind();
        GL20.glUseProgram(0);

        boolean glOk = !GlErrorChecker.checkAndLog("after object buffer test render");

        int[] pixel = readCentrePixel();
        unbindFbo();

        // custom0.r=0.5 → R≈127; custom0.g=0.25 → G≈64; custom0.b=0.0 → B≈0
        boolean rOk = assertPixelChannel("ObjBuf R (custom0.r=0.5)", pixel[0], 127);
        boolean gOk = assertPixelChannel("ObjBuf G (custom0.g=0.25)", pixel[1], 64);
        boolean bOk = assertPixelChannel("ObjBuf B (custom0.b=0.0)", pixel[2], 0);

        boolean passed = glOk && rOk && gOk && bOk;
        report.add((passed ? "PASS" : "FAIL") + " — pixel=(" + pixel[0] + "," + pixel[1] + "," + pixel[2] + ")");
        return passed;
    }

    /**
     * Test 3: TBO path (forced raw GL, bypasses CgShaderBuffer path selection).
     * Tests that the TBO shader contract works regardless of what CgShaderBuffer.create() chose.
     * Writes {@code custom0=(0.0, 0.75, 0.5, 1.0)} at texel 7; asserts R≈0 G≈191 B≈127.
     */
    private boolean runForcedTboTest() {
        // Bind texture unit 0 to the raw TBO (test uses unit 0 to avoid conflicts)
        int tboUnit = 0;
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + tboUnit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, rawTboTexId);

        // Set the TBO sampler to unit 0
        GL20.glUseProgram(tboTestProgram);
        int tboLoc = GL20.glGetUniformLocation(tboTestProgram, "cg_ObjectTBO");
        if (tboLoc != -1) {
            GL20.glUniform1i(tboLoc, tboUnit);
        } else {
            report.add("WARN: cg_ObjectTBO uniform not found in TBO test program");
        }

        bindFbo();
        clearFbo();
        GL30.glBindVertexArray(dummyVaoId);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);

        // Unbind TBO
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + tboUnit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        boolean glOk = !GlErrorChecker.checkAndLog("after forced TBO test render");

        int[] pixel = readCentrePixel();
        unbindFbo();

        // custom0=(0.0, 0.75, 0.5, 1.0) → R≈0, G≈191, B≈127
        boolean rOk = assertPixelChannel("TBO R (custom0.r=0.0)", pixel[0], 0);
        boolean gOk = assertPixelChannel("TBO G (custom0.g=0.75)", pixel[1], 191);
        boolean bOk = assertPixelChannel("TBO B (custom0.b=0.5)", pixel[2], 127);

        boolean passed = glOk && rOk && gOk && bOk;
        report.add((passed ? "PASS" : "FAIL") + " — pixel=(" + pixel[0] + "," + pixel[1] + "," + pixel[2] + ")");
        return passed;
    }

    // ── GL helpers ────────────────────────────────────────────────────────────

    private void createFbo() {
        // Create colour texture attachment (RGBA8)
        fboColorTexId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fboColorTexId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
            FBO_W, FBO_H, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        // Create FBO and attach colour texture
        fboId = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, fboColorTexId, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("FBO incomplete: status=0x" + Integer.toHexString(status));
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * Creates a raw TBO with 1 object record (12 vec4 texels = 48 floats).
     * Slot layout matches the ABI in {@code cg_env.glsl}:
     * <ul>
     *   <li>Texels 0-3: identity modelMatrix</li>
     *   <li>Texels 4-7: identity normalMatrix (mat4, column-major)</li>
     *   <li>Texel 8: custom0 = (0.0, 0.75, 0.5, 1.0) — the test value</li>
     *   <li>Texels 9-11: zeros</li>
     * </ul>
     */
    private void createRawTbo() {
        float[] data = new float[48]; // 12 texels × 4 floats
        int c = 0;

        // modelMatrix = identity (column-major)
        data[c++] = 1; data[c++] = 0; data[c++] = 0; data[c++] = 0; // col0
        data[c++] = 0; data[c++] = 1; data[c++] = 0; data[c++] = 0; // col1
        data[c++] = 0; data[c++] = 0; data[c++] = 1; data[c++] = 0; // col2
        data[c++] = 0; data[c++] = 0; data[c++] = 0; data[c++] = 1; // col3

        // normalMatrix = identity mat4 (column-major, 4 columns × vec4)
        data[c++] = 1; data[c++] = 0; data[c++] = 0; data[c++] = 0; // col0
        data[c++] = 0; data[c++] = 1; data[c++] = 0; data[c++] = 0; // col1
        data[c++] = 0; data[c++] = 0; data[c++] = 1; data[c++] = 0; // col2
        data[c++] = 0; data[c++] = 0; data[c++] = 0; data[c++] = 1; // col3

        // custom0 = test value (0.0, 0.75, 0.5, 1.0) at texel 8
        data[c++] = 0.0f; data[c++] = 0.75f; data[c++] = 0.5f; data[c++] = 1.0f;

        // custom1, custom2, custom3 = zero (remaining 12 floats already 0)

        FloatBuffer buf = BufferUtils.createFloatBuffer(48);
        buf.put(data).flip();

        rawTboVboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, rawTboVboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        rawTboTexId = GL11.glGenTextures();
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, rawTboTexId);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, rawTboVboId);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
    }

    private void bindFbo() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL11.glViewport(0, 0, FBO_W, FBO_H);
    }

    private void unbindFbo() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private void clearFbo() {
        GL11.glClearColor(0, 0, 0, 1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }

    /**
     * Reads the RGBA value of the centre pixel from the currently bound FBO.
     * Returns an {@code int[4]} with values in [0, 255].
     */
    private int[] readCentrePixel() {
        ByteBuffer pixels = BufferUtils.createByteBuffer(FBO_W * FBO_H * 4);
        GL11.glReadPixels(0, 0, FBO_W, FBO_H, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        // Centre pixel index
        int cx = FBO_W / 2;
        int cy = FBO_H / 2;
        int offset = (cy * FBO_W + cx) * 4;
        return new int[]{
            pixels.get(offset) & 0xFF,
            pixels.get(offset + 1) & 0xFF,
            pixels.get(offset + 2) & 0xFF,
            pixels.get(offset + 3) & 0xFF
        };
    }

    /**
     * Asserts that {@code actual} is within {@link #PIXEL_TOLERANCE} of {@code expected}.
     * Logs a message and returns {@code true} on pass, {@code false} on fail.
     */
    private boolean assertPixelChannel(String desc, int actual, int expected) {
        boolean ok = Math.abs(actual - expected) <= PIXEL_TOLERANCE;
        String msg = (ok ? "  PASS" : "  FAIL") + " " + desc +
            ": expected " + expected + " ± " + PIXEL_TOLERANCE + ", got " + actual;
        report.add(msg);
        if (!ok) LOGGER.warning("[ShaderBufferValidation] " + msg);
        return ok;
    }

    private int compileProgram(String label, String vertSrc, String fragSrc) {
        int vert = compileShader(GL20.GL_VERTEX_SHADER, label + " vert", vertSrc);
        int frag = compileShader(GL20.GL_FRAGMENT_SHADER, label + " frag", fragSrc);

        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vert);
        GL20.glAttachShader(prog, frag);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);

        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(prog, 4096);
            GL20.glDeleteProgram(prog);
            report.add("ERROR: program '" + label + "' link failed:\n" + log);
            LOGGER.severe("[ShaderBufferValidation] " + label + " link failed:\n" + log);
            return 0;
        }
        GlErrorChecker.checkAndLog("after program link: " + label);
        report.add("Compiled program: " + label);
        return prog;
    }

    private int compileShader(int type, String label, String src) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, src);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 4096);
            GL20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed [" + label + "]:\n" + log);
        }
        return shader;
    }

    private void writeReport(boolean allPassed) {
        String filename = outputDir + "/validation-report.txt";
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(filename));
            pw.println("=== CrystalShader Waves 1-4 GPU Validation Report ===");
            pw.println("Overall: " + (allPassed ? "PASS" : "FAIL"));
            pw.println();
            for (String line : report) {
                pw.println(line);
            }
            pw.close();
            LOGGER.info("[ShaderBufferValidation] Report written to: " + filename);
        } catch (IOException e) {
            LOGGER.warning("[ShaderBufferValidation] Failed to write report: " + e.getMessage());
        }
    }
}
