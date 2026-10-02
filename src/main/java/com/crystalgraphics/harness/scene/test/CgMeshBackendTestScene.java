package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.shader.CgShaderProgram;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.gl.CgGL;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Mesh rewrite M1's gate on a real device: what the mesh store draws with -- base-vertex draws, integer and
 * half-float attributes, draws reading no attribute, and buffer copies ordered with the draws around them -- into
 * {@code mesh-backend-new.png}, and the same picture drawn with only plain float attributes and plain draws into
 * {@code mesh-backend-ref.png}. The two match on every device, within a level of interpolation rounding.
 */
public class CgMeshBackendTestScene implements HarnessSceneLifecycle {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.MeshBackendTest");

    private static final float SQUARE = 80f;
    /** Mixed into a base-vertex quad's colour by its corner, the vertex's index in its own mesh. */
    private static final float[][] CORNERS = {{1, 0, 0, 1}, {0, 1, 0, 1}, {0, 0, 1, 1}, {1, 1, 1, 1}};
    private static final float CORNER_WEIGHT = 0.25f;
    /** A quad's six vertices, as two triangles over its unit corners. */
    private static final int[][] TRIANGLES = {{0, 0}, {1, 0}, {1, 1}, {0, 0}, {1, 1}, {0, 1}};
    private static final float[][] PALETTE = {{0.9f, 0.3f, 0.2f, 1}, {0.2f, 0.8f, 0.3f, 1}, {0.25f, 0.4f, 0.95f, 1},
            {0.95f, 0.85f, 0.2f, 1}};

    private static final String FRAG = "#version 330 core\nin vec4 v_col;\nout vec4 o;\nvoid main() { o = v_col; }\n";
    private static final String PLACE = "uniform vec2 u_size;\nuniform vec2 u_offset;\n"
            + "vec4 place(vec2 p) { return vec4((p + u_offset) / u_size * 2.0 - 1.0, 0.0, 1.0); }\n";

    private static final String PLAIN = "#version 330 core\n" + PLACE
            + "layout(location = 0) in vec2 a_pos;\nlayout(location = 1) in vec4 a_col;\nout vec4 v_col;\n"
            + "void main() { v_col = a_col; gl_Position = place(a_pos); }\n";

    private static final String BASE_VERTEX = "#version 330 core\n" + PLACE
            + "layout(location = 0) in vec2 a_pos;\nlayout(location = 1) in vec4 a_col;\nout vec4 v_col;\n"
            + "uniform int u_vertexBase;\n"
            + "const vec4 CORNERS[4] = vec4[4](vec4(1, 0, 0, 1), vec4(0, 1, 0, 1), vec4(0, 0, 1, 1), vec4(1, 1, 1, 1));\n"
            + "void main() {\n"
            + "    v_col = mix(a_col, CORNERS[gl_VertexID - u_vertexBase], " + CORNER_WEIGHT + ");\n"
            + "    gl_Position = place(a_pos + vec2(0.0, float(gl_InstanceID) * " + (SQUARE + 20f) + "));\n"
            + "}\n";

    private static final String INTEGERS = "#version 330 core\n" + PLACE
            + "layout(location = 0) in vec2 a_pos;\nlayout(location = 1) in uvec4 a_u8;\n"
            + "layout(location = 2) in ivec2 a_s16;\nlayout(location = 3) in uint a_u32;\nout vec4 v_col;\n"
            + "void main() {\n"
            + "    v_col = vec4(float(a_u8.x + a_u8.y + a_u8.z + a_u8.w) / 1020.0,\n"
            + "                 float(a_s16.x - a_s16.y + 2000) / 4000.0, float(a_u32 % 1000u) / 999.0, 1.0);\n"
            + "    gl_Position = place(a_pos);\n"
            + "}\n";

    private static final String NO_ATTRIBUTES = "#version 330 core\n" + PLACE + "out vec4 v_col;\n"
            + "const vec2 TRIANGLES[6] = vec2[6](vec2(0, 0), vec2(1, 0), vec2(1, 1), vec2(0, 0), vec2(1, 1), vec2(0, 1));\n"
            + "const vec4 PALETTE[4] = vec4[4](vec4(0.9, 0.3, 0.2, 1), vec4(0.2, 0.8, 0.3, 1), vec4(0.25, 0.4, 0.95, 1),"
            + " vec4(0.95, 0.85, 0.2, 1));\n"
            + "void main() {\n"
            + "    int quad = gl_VertexID / 6;\n"
            + "    v_col = PALETTE[quad % 4] * (0.5 + 0.5 * float(quad / 4) / 2.0);\n"
            + "    gl_Position = place(vec2(float(quad % 4) * 50.0, float(quad / 4) * 50.0) + TRIANGLES[gl_VertexID % 6] * 40.0);\n"
            + "}\n";

    private CgShaderProgram plain, baseVertex, integers, noAttributes;

    @Override
    public void init(HarnessContext ctx) {
        plain = CgShaderProgram.compile(PLAIN, FRAG, null);
        baseVertex = CgShaderProgram.compile(BASE_VERTEX, FRAG, null);
        integers = CgShaderProgram.compile(INTEGERS, FRAG, null);
        noAttributes = CgShaderProgram.compile(NO_ATTRIBUTES, FRAG, null);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        CgGL.glDisable(CgGL.GL_BLEND);
        CgGL.glDisable(CgGL.GL_DEPTH_TEST);
        CgGL.glDisable(CgGL.GL_CULL_FACE);
        CgGL.glDisable(CgGL.GL_SCISSOR_TEST);

        capture(ctx, w, h, "mesh-backend-new.png", true);
        capture(ctx, w, h, "mesh-backend-ref.png", false);
        LOG.info("[mesh-backend] two captures written; glError={}", GlErrorChecker.checkAndLog("mesh-backend"));
    }

    private void capture(HarnessContext ctx, int w, int h, String name, boolean modern) {
        HarnessFboHelper fbo = HarnessFboHelper.create(w, h, false);
        fbo.bind();
        fbo.clear(0.08f, 0.08f, 0.1f, 1f);
        if (modern) {
            baseVertexDraws(w, h);
            integerDraws(w, h);
            halfFloatDraws(w, h);
            attributelessDraws(w, h);
            copies(w, h);
        } else {
            plainPanels(w, h);
        }
        fbo.captureToFile(ctx.getOutputDir(), name);
        fbo.unbind();
        fbo.delete();
    }

    // ── The draws under test ───────────────────────────────────────────────────

    /** Two quads in one buffer, each drawn twice by instance from one index range, at base 0 and base 4. */
    private void baseVertexDraws(int w, int h) {
        int vao = vertexArray();
        ByteBuffer v = bytes(8 * 12);
        for (int quad = 0; quad < 2; quad++) {
            for (int c = 0; c < 4; c++) {
                float[] p = square(20f + quad * 120f, 20f, c);
                v.putFloat(p[0]).putFloat(p[1]);
                int[] col = baseColour(quad, c);
                v.put((byte) col[0]).put((byte) col[1]).put((byte) col[2]).put((byte) 255);
            }
        }
        ByteBuffer ix = bytes(12 * 4);
        for (int i : new int[]{0, 1, 2, 0, 2, 3, 1, 2, 3, 1, 3, 0}) ix.putInt(i);
        int vbo = buffer(CgGL.GL_ARRAY_BUFFER, v.flip(), CgGL.GL_STATIC_DRAW);
        int ibo = buffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, ix.flip(), CgGL.GL_STATIC_DRAW);
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vbo);
        CgGL.glVertexAttribPointer(0, 2, CgGL.GL_FLOAT, false, 12, 0);
        CgGL.glVertexAttribPointer(1, 4, CgGL.GL_UNSIGNED_BYTE, true, 12, 8);
        CgGL.glEnableVertexAttribArray(0);
        CgGL.glEnableVertexAttribArray(1);
        use(baseVertex, w, h, 0f, 0f);
        for (int quad = 0; quad < 2; quad++) {
            for (int range = 0; range < 2; range++) {
                baseVertex.setUniform1i(baseVertex.getUniformLocation("u_vertexBase"), quad * 4);
                baseVertex.setUniform2f(baseVertex.getUniformLocation("u_offset"), range * 240f, 0f);
                CgGL.glDrawElementsInstancedBaseVertex(CgGL.GL_TRIANGLES, 6, CgGL.GL_UNSIGNED_INT, range * 24L, 2,
                        quad * 4);
            }
        }
        done(vao);
        CgGL.glDeleteBuffers(vbo);
        CgGL.glDeleteBuffers(ibo);
    }

    /** Four quads whose colour the shader computes from unsigned bytes, signed shorts and a 32-bit unsigned int. */
    private void integerDraws(int w, int h) {
        int vao = vertexArray();
        ByteBuffer v = bytes(24 * 20);
        for (int quad = 0; quad < 4; quad++) {
            for (int[] t : TRIANGLES) {
                v.putFloat(20f + quad * 100f + t[0] * SQUARE).putFloat(240f + t[1] * SQUARE);
                int[] u8 = u8(quad, t), s16 = s16(quad, t);
                for (int b : u8) v.put((byte) b);
                v.putShort((short) s16[0]).putShort((short) s16[1]);
                v.putInt(u32(quad, t));
            }
        }
        int vbo = buffer(CgGL.GL_ARRAY_BUFFER, v.flip(), CgGL.GL_STATIC_DRAW);
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vbo);
        CgGL.glVertexAttribPointer(0, 2, CgGL.GL_FLOAT, false, 20, 0);
        CgGL.glVertexAttribIPointer(1, 4, CgGL.GL_UNSIGNED_BYTE, 20, 8);
        CgGL.glVertexAttribIPointer(2, 2, CgGL.GL_SHORT, 20, 12);
        CgGL.glVertexAttribIPointer(3, 1, CgGL.GL_UNSIGNED_INT, 20, 16);
        for (int i = 0; i < 4; i++) CgGL.glEnableVertexAttribArray(i);
        use(integers, w, h, 0f, 0f);
        CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 24);
        done(vao);
        CgGL.glDeleteBuffers(vbo);
    }

    /** Four quads whose colour is half floats. */
    private void halfFloatDraws(int w, int h) {
        int vao = vertexArray();
        ByteBuffer v = bytes(24 * 16);
        for (int quad = 0; quad < 4; quad++) {
            for (int[] t : TRIANGLES) {
                v.putFloat(440f + quad * 100f + t[0] * SQUARE).putFloat(240f + t[1] * SQUARE);
                for (float c : halfColour(quad, t)) v.putShort(Float.floatToFloat16(c));
            }
        }
        int vbo = buffer(CgGL.GL_ARRAY_BUFFER, v.flip(), CgGL.GL_STATIC_DRAW);
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vbo);
        CgGL.glVertexAttribPointer(0, 2, CgGL.GL_FLOAT, false, 16, 0);
        CgGL.glVertexAttribPointer(1, 4, CgGL.GL_HALF_FLOAT, false, 16, 8);
        CgGL.glEnableVertexAttribArray(0);
        CgGL.glEnableVertexAttribArray(1);
        use(plain, w, h, 0f, 0f);
        CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 24);
        done(vao);
        CgGL.glDeleteBuffers(vbo);
    }

    /** Twelve quads made from {@code gl_VertexID} alone: eight instanced from 0, four drawn from vertex 48. */
    private void attributelessDraws(int w, int h) {
        int vao = vertexArray();
        use(noAttributes, w, h, 20f, 360f);
        CgGL.glDrawArraysInstanced(CgGL.GL_TRIANGLES, 0, 48, 1);
        CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 48, 24);
        done(vao);
    }

    /**
     * Copies into device-local storage, each drawn before the next overwrites it; a copy between host-visible
     * buffers; a copy between device-local ones.
     */
    private void copies(int w, int h) {
        int vao = vertexArray();
        long quadBytes = 6 * 24;
        int staging = buffer(CgGL.GL_ARRAY_BUFFER, quadVertices(0), CgGL.GL_STREAM_DRAW);
        int local = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, local);
        CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, quadBytes, CgGL.GL_STATIC_DRAW);
        int visible = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, visible);
        CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, quadBytes, CgGL.GL_DYNAMIC_DRAW);
        int second = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, second);
        CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, quadBytes, CgGL.GL_STATIC_DRAW);
        use(plain, w, h, 0f, 0f);

        copy(staging, local, quadBytes);
        drawPlain(local, 6);
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, staging);
        CgGL.glBufferSubData(CgGL.GL_ARRAY_BUFFER, 0, quadVertices(1));
        copy(staging, local, quadBytes);
        drawPlain(local, 6);

        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, staging);
        CgGL.glBufferSubData(CgGL.GL_ARRAY_BUFFER, 0, quadVertices(2));
        copy(staging, visible, quadBytes);
        drawPlain(visible, 6);

        copy(local, second, quadBytes);
        plain.setUniform2f(plain.getUniformLocation("u_offset"), 0f, SQUARE + 20f);
        drawPlain(second, 6);

        done(vao);
        for (int b : new int[]{staging, local, visible, second}) CgGL.glDeleteBuffers(b);
    }

    private static void copy(int from, int to, long size) {
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, from);
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, to);
        CgGL.glCopyBufferSubData(CgGL.GL_COPY_READ_BUFFER, CgGL.GL_COPY_WRITE_BUFFER, 0, 0, size);
    }

    // ── The same picture, plainly ──────────────────────────────────────────────

    private void plainPanels(int w, int h) {
        int vao = vertexArray();
        use(plain, w, h, 0f, 0f);
        int floats = 0;
        float[] all = new float[4096];
        for (int quad = 0; quad < 2; quad++) {
            for (int range = 0; range < 2; range++) {
                int[] order = range == 0 ? new int[]{0, 1, 2, 0, 2, 3} : new int[]{1, 2, 3, 1, 3, 0};
                for (int instance = 0; instance < 2; instance++) {
                    for (int c : order) {
                        float[] p = square(20f + quad * 120f + range * 240f, 20f + instance * (SQUARE + 20f), c);
                        int[] col = baseColour(quad, c);
                        floats = vertex(all, floats, p[0], p[1], mix(col[0] / 255f, CORNERS[c][0]),
                                mix(col[1] / 255f, CORNERS[c][1]), mix(col[2] / 255f, CORNERS[c][2]), 1f);
                    }
                }
            }
        }
        for (int quad = 0; quad < 4; quad++) {
            for (int[] t : TRIANGLES) {
                int[] u8 = u8(quad, t), s16 = s16(quad, t);
                floats = vertex(all, floats, 20f + quad * 100f + t[0] * SQUARE, 240f + t[1] * SQUARE,
                        (u8[0] + u8[1] + u8[2] + u8[3]) / 1020f, (s16[0] - s16[1] + 2000) / 4000f,
                        (u32(quad, t) % 1000) / 999f, 1f);
            }
        }
        for (int quad = 0; quad < 4; quad++) {
            for (int[] t : TRIANGLES) {
                float[] c = halfColour(quad, t);
                floats = vertex(all, floats, 440f + quad * 100f + t[0] * SQUARE, 240f + t[1] * SQUARE, c[0], c[1], c[2], c[3]);
            }
        }
        for (int quad = 0; quad < 12; quad++) {
            float shade = 0.5f + 0.5f * (quad / 4) / 2f;
            float[] c = PALETTE[quad % 4];
            for (int[] t : TRIANGLES) {
                floats = vertex(all, floats, 20f + (quad % 4) * 50f + t[0] * 40f, 360f + (quad / 4) * 50f + t[1] * 40f,
                        c[0] * shade, c[1] * shade, c[2] * shade, c[3] * shade);
            }
        }
        ByteBuffer copies = bytes(4 * 6 * 24);
        for (int q : new int[]{0, 1, 2}) copies.put(quadVertices(q));
        ByteBuffer shifted = quadVertices(1);
        for (int i = 0; i < 6; i++) shifted.putFloat(i * 24 + 4, shifted.getFloat(i * 24 + 4) + SQUARE + 20f);
        copies.put(shifted).flip();

        ByteBuffer v = bytes(floats * 4);
        for (int i = 0; i < floats; i++) v.putFloat(all[i]);
        int vbo = buffer(CgGL.GL_ARRAY_BUFFER, v.flip(), CgGL.GL_STATIC_DRAW);
        drawPlain(vbo, floats / 6);
        int copied = buffer(CgGL.GL_ARRAY_BUFFER, copies, CgGL.GL_STATIC_DRAW);
        drawPlain(copied, 24);
        done(vao);
        CgGL.glDeleteBuffers(vbo);
        CgGL.glDeleteBuffers(copied);
    }

    // ── Shared data ────────────────────────────────────────────────────────────

    /** Corner {@code c} of a square at (x, y), counter-clockwise from its first corner. */
    private static float[] square(float x, float y, int c) {
        return new float[]{x + (c == 1 || c == 2 ? SQUARE : 0f), y + (c >= 2 ? SQUARE : 0f)};
    }

    private static int[] baseColour(int quad, int c) {
        return quad == 0 ? new int[]{200, 40 + c * 40, 40, 255} : new int[]{40, 80 + c * 32, 220, 255};
    }

    private static float mix(float a, float b) {
        return a * (1f - CORNER_WEIGHT) + b * CORNER_WEIGHT;
    }

    private static int[] u8(int quad, int[] t) {
        return new int[]{quad * 60 + t[0] * 40, 200 - quad * 30, t[1] * 255, 17 + quad * 50};
    }

    private static int[] s16(int quad, int[] t) {
        return new int[]{-700 + quad * 400 + t[0] * 300, 300 - t[1] * 600};
    }

    private static int u32(int quad, int[] t) {
        return 1_000_000 + quad * 250 + t[0] * 600 + t[1] * 130;
    }

    private static float[] halfColour(int quad, int[] t) {
        return new float[]{(quad * 4 + 2) / 16f, (t[0] * 12 + 3) / 16f, (t[1] * 10 + 4) / 16f, 1f};
    }

    /** One quad in the plain format (position, then float colour), the copy panel's {@code n}th. */
    private static ByteBuffer quadVertices(int n) {
        ByteBuffer v = bytes(6 * 24);
        float[] c = PALETTE[n + 1];
        for (int[] t : TRIANGLES) {
            v.putFloat(460f + n * 100f + t[0] * SQUARE).putFloat(360f + t[1] * SQUARE);
            v.putFloat(c[0]).putFloat(c[1]).putFloat(c[2]).putFloat(c[3]);
        }
        return v.flip();
    }

    private static int vertex(float[] out, int at, float x, float y, float r, float g, float b, float a) {
        out[at] = x;
        out[at + 1] = y;
        out[at + 2] = r;
        out[at + 3] = g;
        out[at + 4] = b;
        out[at + 5] = a;
        return at + 6;
    }

    private void drawPlain(int vbo, int vertices) {
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vbo);
        CgGL.glVertexAttribPointer(0, 2, CgGL.GL_FLOAT, false, 24, 0);
        CgGL.glVertexAttribPointer(1, 4, CgGL.GL_FLOAT, false, 24, 8);
        CgGL.glEnableVertexAttribArray(0);
        CgGL.glEnableVertexAttribArray(1);
        CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, vertices);
    }

    private static void use(CgShaderProgram program, int w, int h, float x, float y) {
        program.bind();
        program.setUniform2f(program.getUniformLocation("u_size"), w, h);
        program.setUniform2f(program.getUniformLocation("u_offset"), x, y);
    }

    private static int vertexArray() {
        int vao = CgGL.glGenVertexArrays();
        CgGL.glBindVertexArray(vao);
        return vao;
    }

    private static void done(int vao) {
        CgGL.glBindVertexArray(0);
        CgGL.glDeleteVertexArrays(vao);
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, 0);
    }

    private static int buffer(int target, ByteBuffer data, int usage) {
        int b = CgGL.glGenBuffers();
        CgGL.glBindBuffer(target, b);
        CgGL.glBufferData(target, data, usage);
        return b;
    }

    private static ByteBuffer bytes(int n) {
        return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
    }

    @Override
    public void dispose() {
        for (CgShaderProgram p : new CgShaderProgram[]{plain, baseVertex, integers, noAttributes}) {
            if (p != null) p.delete();
        }
    }
}
