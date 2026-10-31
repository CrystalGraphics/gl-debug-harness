package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessShaderUtil;
import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * What one GL state change costs the frame on this driver, threaded optimisation included: each kind of change made
 * before every one of {@link #DRAWS} tiny draws a frame, against the same draws with no change, uncapped. A driver
 * defers most of a change's cost to the next draw's validation, so a change is timed with the draw it dirties, never
 * alone. Prints microseconds a change and exits (T11 P0.3).
 *
 * <pre>
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=gl-call-cost" -Dcrystalgraphics.harness.fps=0
 * </pre>
 *
 * <ul>
 *   <li>Run uncapped, or the cap's sleep hides the driver's thread.</li>
 *   <li>Every change alternates between two values, so the state shadow never skips it.</li>
 *   <li>Frame time is wall time render to render, swap included: what the driver's thread holds the frame to.</li>
 * </ul>
 */
public final class CgGlCallCostScene implements InteractiveSceneLifecycle {

    private static final int DRAWS = Integer.getInteger("crystalgraphics.harness.callCost.draws", 1000);
    private static final int WARM = 60, MEASURED = 180;

    private static final String VS = """
            #version 430 core
            void main() {
                vec2 p = vec2(gl_VertexID == 1 ? 0.01 : 0.0, gl_VertexID == 2 ? 0.01 : 0.0);
                gl_Position = vec4(p, 0.0, 1.0);
            }
            """;
    private static final String FS = """
            #version 430 core
            uniform int u_Value;
            uniform sampler2D u_Texture;
            layout(std140, binding = 0) uniform Block { vec4 tint; };
            layout(std430, binding = 0) readonly buffer Data { vec4 data[]; };
            out vec4 colour;
            void main() {
                colour = tint * texture(u_Texture, vec2(0.5)) + vec4(float(u_Value) * 1e-6) + data[0] * 1e-6;
            }
            """;

    /** A heavy draw's program: eight textures, four uniform blocks and four storage buffers, all read. */
    private static final String FS_HEAVY = """
            #version 430 core
            uniform sampler2D t0, t1, t2, t3, t4, t5, t6, t7;
            layout(std140, binding = 0) uniform B0 { vec4 b0; };
            layout(std140, binding = 1) uniform B1 { vec4 b1; };
            layout(std140, binding = 2) uniform B2 { vec4 b2; };
            layout(std140, binding = 3) uniform B3 { vec4 b3; };
            layout(std430, binding = 0) readonly buffer S0 { vec4 s0[]; };
            layout(std430, binding = 1) readonly buffer S1 { vec4 s1[]; };
            layout(std430, binding = 2) readonly buffer S2 { vec4 s2[]; };
            layout(std430, binding = 3) readonly buffer S3 { vec4 s3[]; };
            out vec4 colour;
            void main() {
                vec2 c = vec2(0.5);
                colour = texture(t0, c) + texture(t1, c) + texture(t2, c) + texture(t3, c) + texture(t4, c) + texture(t5, c)
                        + texture(t6, c) + texture(t7, c) + b0 + b1 + b2 + b3 + s0[0] + s1[0] + s2[0] + s3[0];
            }
            """;
    private static final String CS = """
            #version 430 core
            layout(local_size_x = 64) in;
            layout(std430, binding = 0) buffer Data { vec4 data[]; };
            void main() { data[gl_GlobalInvocationID.x] += vec4(1e-6); }
            """;

    /** A change made before each draw ({@code apply}), or a whole frame of work of its own ({@code frame}). */
    private record Change(String name, Runnable apply, Runnable frame) {
        Change(String name, Runnable apply) {
            this(name, apply, null);
        }
    }

    private final List<Change> changes = new ArrayList<>();
    private int programA, programB, programHeavy, kernel, vaoA, vaoB, textureA, textureB, samplerA, samplerB, ubo, ssboA, ssboB;
    private int fboA, fboB, colourA, colourB, uniformValue;
    private boolean flip, running = true;
    private int change = -1, frame;
    private long last;
    private final long[] times = new long[MEASURED];
    private double baseline, empty;
    private final StringBuilder table = new StringBuilder();

    @Override
    public void init(HarnessContext ctx) {
        programA = HarnessShaderUtil.compileProgram(VS, FS);
        programB = HarnessShaderUtil.compileProgram(VS, FS);
        programHeavy = HarnessShaderUtil.compileProgram(VS, FS_HEAVY);
        kernel = computeProgram(CS);
        CgGL.glUseProgram(programHeavy);
        for (int t = 0; t < 8; t++) CgGL.glUniform1i(CgGL.glGetUniformLocation(programHeavy, "t" + t), t);
        CgGL.glUseProgram(0);
        uniformValue = CgGL.glGetUniformLocation(programA, "u_Value");
        vaoA = CgGL.glGenVertexArrays();
        vaoB = CgGL.glGenVertexArrays();
        textureA = texture();
        textureB = texture();
        colourA = texture();
        colourB = texture();
        samplerA = CgGL.glGenSamplers();
        samplerB = CgGL.glGenSamplers();
        ubo = buffer(CgGL.GL_UNIFORM_BUFFER, 64 * 1024);
        ssboA = buffer(CgGL.GL_SHADER_STORAGE_BUFFER, 4096);
        ssboB = buffer(CgGL.GL_SHADER_STORAGE_BUFFER, 4096);
        fboA = framebuffer(colourA);
        fboB = framebuffer(colourB);

        changes.add(new Change("empty frame", null, () -> {}));
        changes.add(new Change("none", () -> {}));
        changes.add(new Change("glActiveTexture", () -> CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + (flip ? 1 : 2))));
        changes.add(new Change("glBindTexture", () -> CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, flip ? textureA : textureB)));
        changes.add(new Change("glActiveTexture+glBindTexture", () -> {
            CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + (flip ? 1 : 0));
            CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, flip ? textureB : textureA);
        }));
        changes.add(new Change("glBindSampler", () -> CgGL.glBindSampler(0, flip ? samplerA : samplerB)));
        changes.add(new Change("glTexParameteri", () -> CgGL.glTexParameteri(CgGL.GL_TEXTURE_2D, CgGL.GL_TEXTURE_MAX_LEVEL, flip ? 0 : 1000)));
        changes.add(new Change("glBindBufferRange(UNIFORM)", () -> CgGL.glBindBufferRange(CgGL.GL_UNIFORM_BUFFER, 0, ubo, flip ? 0 : 256, 16)));
        changes.add(new Change("glBindBufferBase(SSBO)", () -> CgGL.glBindBufferBase(CgGL.GL_SHADER_STORAGE_BUFFER, 0, flip ? ssboA : ssboB)));
        changes.add(new Change("glUniform1i", () -> CgGL.glUniform1i(uniformValue, flip ? 1 : 2)));
        changes.add(new Change("glUseProgram", () -> CgGL.glUseProgram(flip ? programA : programB)));
        changes.add(new Change("glBindVertexArray", () -> CgGL.glBindVertexArray(flip ? vaoA : vaoB)));
        changes.add(new Change("glEnable/glDisable(BLEND)", () -> {
            if (flip) CgGL.glEnable(CgGL.GL_BLEND); else CgGL.glDisable(CgGL.GL_BLEND);
        }));
        changes.add(new Change("glBlendFuncSeparate", () -> CgGL.glBlendFuncSeparate(flip ? CgGL.GL_ONE : CgGL.GL_SRC_ALPHA,
                CgGL.GL_ONE_MINUS_SRC_ALPHA, CgGL.GL_ONE, CgGL.GL_ZERO)));
        changes.add(new Change("glMemoryBarrier", () -> CgGL.glMemoryBarrier(CgGL.GL_SHADER_STORAGE_BARRIER_BIT)));
        changes.add(new Change("glBindFramebuffer", () -> CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, flip ? fboA : fboB)));
        changes.add(new Change("glFramebufferTexture2D", () -> CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER,
                CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_TEXTURE_2D, flip ? colourA : colourB, 0)));

        // Whole frames, each against the empty frame: what one operation of the kind costs.
        changes.add(new Change("draw, light program", null, () -> draw(() -> {})));
        changes.add(new Change("draw, 8 tex 4 ubo 4 ssbo", null, () -> {
            target();
            CgGL.glUseProgram(programHeavy);
            CgGL.glBindVertexArray(vaoA);
            for (int t = 0; t < 8; t++) {
                CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + t);
                CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, (t & 1) == 0 ? textureA : textureB);
            }
            CgGL.glActiveTexture(CgGL.GL_TEXTURE0);
            for (int b = 0; b < 4; b++) {
                CgGL.glBindBufferRange(CgGL.GL_UNIFORM_BUFFER, b, ubo, 256L * b, 16);
                CgGL.glBindBufferBase(CgGL.GL_SHADER_STORAGE_BUFFER, b, (b & 1) == 0 ? ssboA : ssboB);
            }
            for (int i = 0; i < DRAWS; i++) CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
            untarget();
        }));
        changes.add(new Change("dispatch", null, () -> {
            CgGL.glUseProgram(kernel);
            CgGL.glBindBufferBase(CgGL.GL_SHADER_STORAGE_BUFFER, 0, ssboA);
            for (int i = 0; i < DRAWS; i++) CgGL.glDispatchCompute(1, 1, 1);
            CgGL.glUseProgram(0);
        }));
        changes.add(new Change("dispatch + barrier", null, () -> {
            CgGL.glUseProgram(kernel);
            CgGL.glBindBufferBase(CgGL.GL_SHADER_STORAGE_BUFFER, 0, ssboA);
            for (int i = 0; i < DRAWS; i++) {
                CgGL.glDispatchCompute(1, 1, 1);
                CgGL.glMemoryBarrier(CgGL.GL_SHADER_STORAGE_BARRIER_BIT);
            }
            CgGL.glUseProgram(0);
        }));
        changes.add(new Change("dispatch, then draw", null, () -> {
            target();
            CgGL.glBindVertexArray(vaoA);
            CgGL.glBindBufferBase(CgGL.GL_SHADER_STORAGE_BUFFER, 0, ssboA);
            for (int i = 0; i < DRAWS; i++) {
                CgGL.glUseProgram(kernel);
                CgGL.glDispatchCompute(1, 1, 1);
                CgGL.glUseProgram(programA);
                CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
            }
            untarget();
        }));
    }

    private void target() {
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, fboA);
        CgGL.glViewport(0, 0, 64, 64);
    }

    private void untarget() {
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, 0);
        CgGL.glUseProgram(0);
        CgGL.glBindVertexArray(0);
    }

    private static int computeProgram(String source) {
        int shader = CgGL.glCreateShader(CgGL.GL_COMPUTE_SHADER);
        CgGL.glShaderSource(shader, source);
        CgGL.glCompileShader(shader);
        int program = CgGL.glCreateProgram();
        CgGL.glAttachShader(program, shader);
        CgGL.glLinkProgram(program);
        return program;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        long now = System.nanoTime();
        if (change >= 0 && frame >= WARM) times[frame - WARM] = now - last;
        last = now;
        if (change < 0 || ++frame >= WARM + MEASURED) {
            if (change >= 0) report();
            if (++change >= changes.size()) {
                finish();
                return;
            }
            frame = 0;
        }
        Change c = changes.get(change);
        if (c.frame() != null) c.frame().run();
        else draw(c.apply());
    }

    private void draw(Runnable apply) {
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, fboA);
        CgGL.glViewport(0, 0, 64, 64);
        CgGL.glUseProgram(programA);
        CgGL.glBindVertexArray(vaoA);
        CgGL.glActiveTexture(CgGL.GL_TEXTURE0);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, textureA);
        CgGL.glBindBufferRange(CgGL.GL_UNIFORM_BUFFER, 0, ubo, 0, 16);
        CgGL.glBindBufferBase(CgGL.GL_SHADER_STORAGE_BUFFER, 0, ssboA);
        for (int i = 0; i < DRAWS; i++) {
            flip = !flip;
            apply.run();
            CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        }
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, 0);
        CgGL.glUseProgram(0);
        CgGL.glBindVertexArray(0);
        CgGL.glDisable(CgGL.GL_BLEND);
    }

    private void report() {
        long[] sorted = times.clone();
        Arrays.sort(sorted);
        double median = sorted[MEASURED / 2] / 1e6;
        Change c = changes.get(change);
        if (change == 0) empty = median;
        if (c.name().equals("none")) baseline = median;
        // A change against draws alone; a frame of its own against the empty frame: one operation of the kind.
        boolean whole = c.frame() != null;
        double each = (median - (whole ? empty : baseline)) * 1000.0 / DRAWS;
        table.append(String.format(Locale.ROOT, "[gl-call-cost]   %-30s frame %7.3f ms   %+7.3f us %s%n",
                c.name(), median, each, whole ? "each" : "a change"));
    }

    private void finish() {
        System.out.print("[gl-call-cost] " + DRAWS + " draws a frame, median of " + MEASURED + " frames each\n" + table);
        running = false;
    }

    private static int texture() {
        int id = CgGL.glGenTextures();
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, id);
        CgGL.glTexImage2D(CgGL.GL_TEXTURE_2D, 0, CgGL.GL_RGBA8, 64, 64, 0, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        CgGL.glTexParameteri(CgGL.GL_TEXTURE_2D, CgGL.GL_TEXTURE_MIN_FILTER, CgGL.GL_LINEAR);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, 0);
        return id;
    }

    private static int buffer(int target, int bytes) {
        int id = CgGL.glGenBuffers();
        CgGL.glBindBuffer(target, id);
        CgGL.glBufferData(target, (long) bytes, CgGL.GL_STATIC_DRAW);
        CgGL.glBindBuffer(target, 0);
        return id;
    }

    private static int framebuffer(int colour) {
        int id = CgGL.glGenFramebuffers();
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, id);
        CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_TEXTURE_2D, colour, 0);
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, 0);
        return id;
    }

    @Override
    public void dispose() {
        CgGL.glDeleteProgram(programA);
        CgGL.glDeleteProgram(programB);
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return true; }
}
