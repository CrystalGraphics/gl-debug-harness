package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.shader.CgShaderProgram;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.emit.CgKernelTarget;
import com.crystalgraphics.compute.program.CgKernelProgram;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.device.command.CgAccess;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * gpu-compute C1's gate: the compute seam on every device. {@code compute-seam-new.png} draws what two kernels
 * wrote -- 48 quads a kernel placed and coloured, dispatched indirectly from CPU-written group counts, and a 64x64
 * image a kernel painted -- with quads 40 to 47 drawn by an indirect draw from CPU-written arguments.
 * {@code compute-seam-ref.png} is the same picture from CPU-written data through the same shaders. The scene
 * compares the two and prints {@code [compute-seam] PASS} or {@code FAIL}; the kernels use integer arithmetic, so
 * the pictures match exactly.
 *
 * <p>gpu-compute C2's gate beside it: {@code compute-seam-kernels.png} is the picture from
 * {@code harness:shaders/compute_seam.compute} through {@code CgKernelProgram}, a map, a general and an image kernel,
 * the general one counting its work group through subgroups; {@code compute-seam-emulated.png} the same with the
 * subgroups emulated, where the context has them. Both must match the reference too.</p>
 *
 * <p>On Vulkan a validation error fails it too. {@code -Dcrystalgraphics.harness.computeSeam.skipBarriers=true} leaves
 * the two barriers out: under {@code -Dcrystalgraphics.vulkan.syncValidation=true} the buffer's must then be reported
 * as a hazard, which is what shows the clean run was checked. The image's is not: a compute pass makes what it wrote
 * to an image visible when it ends.</p>
 *
 * <p>The compute half runs inside a scope declaring the domains it changes, which
 * {@code -Dcrystalgraphics.state.roundTrip=true} checks. It calls {@link CgGL} directly: it tests the seam the
 * compute engine is built on.</p>
 */
public class CgComputeSeamTestScene implements HarnessSceneLifecycle {

    private static final int QUADS = 48, DIRECT = 40, IMAGE = 64, QUAD_BYTES = 32;
    private static final boolean SKIP_BARRIERS = Boolean.getBoolean("crystalgraphics.harness.computeSeam.skipBarriers");

    private static final String QUAD_STRUCT = "struct Quad { vec4 rect; uint color; uint pad0, pad1, pad2; };\n";
    private static final String CORNERS =
            "const vec2 CORNERS[6] = vec2[6](vec2(0, 0), vec2(1, 0), vec2(1, 1), vec2(0, 0), vec2(1, 1), vec2(0, 1));\n";

    private static final String FILL = "#version 430 core\n"
            + "layout(local_size_x = 64) in;\n" + QUAD_STRUCT
            + "layout(std430, binding = 0) writeonly buffer Quads { Quad quads[]; };\n"
            + "uniform int u_count;\n"
            + "void main() {\n"
            + "    uint i = gl_GlobalInvocationID.x;\n"
            + "    if (i >= uint(u_count)) return;\n"
            + "    quads[i].rect = vec4(20.0 + float(i % 8u) * 60.0, 300.0 + float(i / 8u) * 60.0, 50.0, 50.0);\n"
            + "    quads[i].color = ((i * 37u) & 255u) | (((i * 91u) & 255u) << 8) | (((i * 53u) & 255u) << 16)"
            + " | (255u << 24);\n"
            + "}\n";

    private static final String PAINT = "#version 430 core\n"
            + "layout(local_size_x = 8, local_size_y = 8) in;\n"
            + "layout(rgba8, binding = 0) uniform writeonly image2D u_image;\n"
            + "void main() {\n"
            + "    ivec2 p = ivec2(gl_GlobalInvocationID.xy);\n"
            + "    imageStore(u_image, p, vec4(float(p.x * 4), float(p.y * 4), float(((p.x ^ p.y) * 4) & 255), 255.0)"
            + " / 255.0);\n"
            + "}\n";

    private static final String QUAD_VERTEX = "#version 430 core\n" + QUAD_STRUCT
            + "layout(std430, binding = 0) readonly buffer Quads { Quad quads[]; };\n"
            + "uniform vec2 u_size;\nuniform int u_first;\nout vec4 v_col;\n" + CORNERS
            + "void main() {\n"
            + "    Quad q = quads[u_first + gl_InstanceID];\n"
            + "    v_col = unpackUnorm4x8(q.color);\n"
            + "    gl_Position = vec4((q.rect.xy + CORNERS[gl_VertexID] * q.rect.zw) / u_size * 2.0 - 1.0, 0.0, 1.0);\n"
            + "}\n";
    private static final String QUAD_FRAGMENT = "#version 330 core\nin vec4 v_col;\nout vec4 o;\nvoid main() { o = v_col; }\n";

    private static final String IMAGE_VERTEX = "#version 330 core\n"
            + "uniform vec2 u_size;\nuniform vec4 u_rect;\nout vec2 v_uv;\n" + CORNERS
            + "void main() {\n"
            + "    v_uv = CORNERS[gl_VertexID];\n"
            + "    gl_Position = vec4((u_rect.xy + v_uv * u_rect.zw) / u_size * 2.0 - 1.0, 0.0, 1.0);\n"
            + "}\n";
    private static final String IMAGE_FRAGMENT = "#version 330 core\n"
            + "uniform sampler2D u_tex;\nin vec2 v_uv;\nout vec4 o;\nvoid main() { o = texture(u_tex, v_uv); }\n";

    private enum Source { SEAM, CPU, FILE, FILE_EMULATED }

    private int fill, paint;
    private CgShaderProgram quads, image;
    private CgKernelProgram place, colour, colourEmulated, painter;

    @Override
    public void init(HarnessContext ctx) {
        if (!CgCapabilities.detect().compute() || !CgCapabilities.detect().storageImages()) return;
        fill = computeProgram(FILL, "fill");
        paint = computeProgram(PAINT, "paint");
        quads = CgShaderProgram.compile(QUAD_VERTEX, QUAD_FRAGMENT, null);
        image = CgShaderProgram.compile(IMAGE_VERTEX, IMAGE_FRAGMENT, null);
        storageBlock(quads.getId());
        CgCompute seam = CgCompute.load("harness:shaders/compute_seam.compute");
        place = seam.kernel("Place").program();
        colour = seam.kernel("Colour").program();
        painter = seam.kernel("Paint").program();
        CgKernelTarget current = CgKernelTarget.current();
        if (current.nativeSubgroups()) {
            colourEmulated = CgKernelProgram.build(seam.source(), seam.source().kernel("Colour"), Set.of(),
                    current.withSubgroups(0));
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (fill == 0) {
            System.out.println("[compute-seam] SKIP: this context has no compute shaders or storage images");
            return;
        }
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        CgGL.glDisable(CgGL.GL_BLEND);
        CgGL.glDisable(CgGL.GL_DEPTH_TEST);
        CgGL.glDisable(CgGL.GL_CULL_FACE);
        CgGL.glDisable(CgGL.GL_SCISSOR_TEST);

        List<String> made = new ArrayList<>(List.of("compute-seam-new.png", "compute-seam-kernels.png"));
        capture(ctx, w, h, "compute-seam-new.png", Source.SEAM);
        capture(ctx, w, h, "compute-seam-ref.png", Source.CPU);
        capture(ctx, w, h, "compute-seam-kernels.png", Source.FILE);
        if (colourEmulated != null) {
            capture(ctx, w, h, "compute-seam-emulated.png", Source.FILE_EMULATED);
            made.add("compute-seam-emulated.png");
        }
        compare(ctx, GlErrorChecker.checkAndLog("compute-seam"), made);
    }

    private void capture(HarnessContext ctx, int w, int h, String name, Source source) {
        try (CgGlScope ignored = CgGlState.saveAll()) {
            draw(ctx, w, h, name, source);
        }
    }

    private void draw(HarnessContext ctx, int w, int h, String name, Source source) {
        HarnessFboHelper fbo = HarnessFboHelper.create(w, h, false);
        fbo.bind();
        fbo.clear(0.08f, 0.08f, 0.1f, 1f);
        int records = CgGL.glGenBuffers(), texture = texture(), vao = CgGL.glGenVertexArrays();
        try (CgGlScope ignored = CgGlState.save(CgGlSlot.PROGRAM, CgGlSlot.TEXTURES, CgGlSlot.VERTEX_INPUT,
                CgGlSlot.STORAGE_BUFFERS, CgGlSlot.IMAGES, CgGlSlot.INDIRECT_BUFFERS)) {
            switch (source) {
                case SEAM -> runKernels(records, texture);
                case CPU -> writeOnCpu(records, texture);
                default -> runFile(records, texture, source == Source.FILE ? colour : colourEmulated);
            }
            CgGL.glBindVertexArray(vao);
            drawQuads(records, w, h, source == Source.SEAM);
            drawImage(texture, w, h);
            CgGL.glBindVertexArray(0);
        }
        fbo.captureToFile(ctx.getOutputDir(), name);
        fbo.unbind();
        fbo.delete();
        CgGL.glDeleteVertexArrays(vao);
        CgGL.glDeleteBuffers(records);
        CgGL.glDeleteTextures(texture);
    }

    // ── The kernels under test ─────────────────────────────────────────────────

    /** The quads from an indirect dispatch, the image from a direct one; each made visible to its reader. */
    private void runKernels(int records, int texture) {
        CgGL.glBindBuffer(CgGL.GL_SHADER_STORAGE_BUFFER, records);
        CgGL.glBufferData(CgGL.GL_SHADER_STORAGE_BUFFER, (long) QUADS * QUAD_BYTES, CgGL.GL_DYNAMIC_COPY);
        int groups = buffer(CgGL.GL_DISPATCH_INDIRECT_BUFFER, ints(1, 1, 1));

        CgGL.glUseProgram(fill);
        CgGL.glUniform1i(CgGL.glGetUniformLocation(fill, "u_count"), QUADS);
        CgGL.glBindBufferBase(CgGL.GL_SHADER_STORAGE_BUFFER, 0, records);
        CgGL.glBindBuffer(CgGL.GL_DISPATCH_INDIRECT_BUFFER, groups);
        CgGL.glDispatchComputeIndirect(0);
        if (!SKIP_BARRIERS) CgGL.cgBufferBarrier(records, CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ);

        CgGL.glUseProgram(paint);
        CgGL.glUniform1i(CgGL.glGetUniformLocation(paint, "u_image"), 0);
        CgGL.glBindImageTexture(0, texture, 0, false, 0, CgGL.GL_WRITE_ONLY, CgGL.GL_RGBA8);
        CgGL.glDispatchCompute(IMAGE / 8, IMAGE / 8, 1);
        if (!SKIP_BARRIERS) CgGL.cgImageBarrier(texture, CgAccess.COMPUTE_WRITE, CgAccess.SAMPLED_READ);
        CgGL.glDeleteBuffers(groups);
    }

    /** The same picture from the .compute: placed, then coloured, then painted, each made visible to its reader. */
    private void runFile(int records, int texture, CgKernelProgram colourer) {
        CgGL.glBindBuffer(CgGL.GL_SHADER_STORAGE_BUFFER, records);
        CgGL.glBufferData(CgGL.GL_SHADER_STORAGE_BUFFER, (long) QUADS * QUAD_BYTES, CgGL.GL_DYNAMIC_COPY);
        place.use().buffer("QUADS", records).dispatch(QUADS);
        CgGL.cgBufferBarrier(records, CgAccess.COMPUTE_WRITE, CgAccess.COMPUTE_READ | CgAccess.COMPUTE_WRITE);
        colourer.use().buffer("QUADS", records).dispatch(QUADS);
        CgGL.cgBufferBarrier(records, CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ);
        painter.use().image("PICTURE", texture, 0).dispatch(IMAGE, IMAGE, 1);
        CgGL.cgImageBarrier(texture, CgAccess.COMPUTE_WRITE, CgAccess.SAMPLED_READ);
    }

    /** What the kernels write, computed here. */
    private static void writeOnCpu(int records, int texture) {
        ByteBuffer q = bytes(QUADS * QUAD_BYTES);
        for (int i = 0; i < QUADS; i++) {
            q.putFloat(20f + (i % 8) * 60f).putFloat(300f + (i / 8) * 60f).putFloat(50f).putFloat(50f);
            q.putInt((i * 37 & 255) | (i * 91 & 255) << 8 | (i * 53 & 255) << 16 | 255 << 24);
            q.putInt(0).putInt(0).putInt(0);
        }
        CgGL.glBindBuffer(CgGL.GL_SHADER_STORAGE_BUFFER, records);
        CgGL.glBufferData(CgGL.GL_SHADER_STORAGE_BUFFER, q.flip(), CgGL.GL_STATIC_DRAW);

        ByteBuffer texels = bytes(IMAGE * IMAGE * 4);
        for (int y = 0; y < IMAGE; y++) {
            for (int x = 0; x < IMAGE; x++) {
                texels.put((byte) (x * 4)).put((byte) (y * 4)).put((byte) ((x ^ y) * 4 & 255)).put((byte) 255);
            }
        }
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, texture);
        CgGL.glTexSubImage2D(CgGL.GL_TEXTURE_2D, 0, 0, 0, IMAGE, IMAGE, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, texels.flip());
    }

    // ── The draws ──────────────────────────────────────────────────────────────

    /** The first {@link #DIRECT} quads instanced; with kernels the rest by an indirect draw, without, instanced too. */
    private void drawQuads(int records, int w, int h, boolean indirect) {
        quads.bind();
        quads.setUniform2f(quads.getUniformLocation("u_size"), w, h);
        int first = quads.getUniformLocation("u_first");
        CgGL.glBindBufferBase(CgGL.GL_SHADER_STORAGE_BUFFER, 0, records);
        quads.setUniform1i(first, 0);
        CgGL.glDrawArraysInstanced(CgGL.GL_TRIANGLES, 0, 6, indirect ? DIRECT : QUADS);
        if (!indirect) return;
        int args = buffer(CgGL.GL_DRAW_INDIRECT_BUFFER, ints(6, QUADS - DIRECT, 0, 0));
        quads.setUniform1i(first, DIRECT);
        CgGL.glDrawArraysIndirect(CgGL.GL_TRIANGLES, 0);
        CgGL.glDeleteBuffers(args);
    }

    private void drawImage(int texture, int w, int h) {
        image.bind();
        image.setUniform2f(image.getUniformLocation("u_size"), w, h);
        image.setUniform4f(image.getUniformLocation("u_rect"), 520f, 300f, IMAGE * 4f, IMAGE * 4f);
        image.setSampler(image.getUniformLocation("u_tex"), 0);
        CgGL.glActiveTexture(CgGL.GL_TEXTURE0);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, texture);
        CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 6);
    }

    // ── The verdict ────────────────────────────────────────────────────────────

    private static void compare(HarnessContext ctx, boolean glErrors, List<String> captures) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) {
            System.out.println("[compute-seam] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        if (validation > 0) {
            System.out.println("[compute-seam] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
            return;
        }
        try {
            BufferedImage ref = ImageIO.read(new File(ctx.getOutputDir(), "compute-seam-ref.png"));
            boolean pass = true;
            for (String capture : captures) {
                BufferedImage made = ImageIO.read(new File(ctx.getOutputDir(), capture));
                int differing = 0, firstX = -1, firstY = -1;
                for (int y = 0; y < ref.getHeight(); y++) {
                    for (int x = 0; x < ref.getWidth(); x++) {
                        if (made.getRGB(x, y) == ref.getRGB(x, y)) continue;
                        if (differing++ == 0) { firstX = x; firstY = y; }
                    }
                }
                if (differing > 0) {
                    pass = false;
                    System.out.println("[compute-seam] FAIL on " + on + ": " + capture + " differs in " + differing
                            + " pixels, the first at (" + firstX + ", " + firstY + "): 0x"
                            + Integer.toHexString(made.getRGB(firstX, firstY)) + " where the reference has 0x"
                            + Integer.toHexString(ref.getRGB(firstX, firstY)));
                }
            }
            if (pass) System.out.println("[compute-seam] PASS on " + on + ": " + captures + " match the reference");
        } catch (IOException e) {
            System.out.println("[compute-seam] FAIL: the captures could not be read: " + e);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static int computeProgram(String source, String name) {
        int shader = CgGL.glCreateShader(CgGL.GL_COMPUTE_SHADER);
        CgGL.glShaderSource(shader, source);
        CgGL.glCompileShader(shader);
        if (CgGL.glGetShaderi(shader, CgGL.GL_COMPILE_STATUS) == CgGL.GL_FALSE) {
            throw new IllegalStateException(name + ": " + CgGL.glGetShaderInfoLog(shader, 4096));
        }
        int program = CgGL.glCreateProgram();
        CgGL.glAttachShader(program, shader);
        CgGL.glLinkProgram(program);
        CgGL.glDeleteShader(shader);
        if (CgGL.glGetProgrami(program, CgGL.GL_LINK_STATUS) == CgGL.GL_FALSE) {
            throw new IllegalStateException(name + ": " + CgGL.glGetProgramInfoLog(program, 4096));
        }
        storageBlock(program);
        return program;
    }

    /** The program's {@code Quads} block at binding point 0, said through GL rather than the source. */
    private static void storageBlock(int program) {
        int block = CgGL.glGetProgramResourceIndex(program, CgGL.GL_SHADER_STORAGE_BLOCK, "Quads");
        if (block != CgGL.GL_INVALID_INDEX) CgGL.glShaderStorageBlockBinding(program, block, 0);
    }

    private static int texture() {
        int texture = CgGL.glGenTextures();
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, texture);
        CgGL.glTexImage2D(CgGL.GL_TEXTURE_2D, 0, CgGL.GL_RGBA8, IMAGE, IMAGE, 0, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE,
                (ByteBuffer) null);
        CgGL.glTexParameteri(CgGL.GL_TEXTURE_2D, CgGL.GL_TEXTURE_MIN_FILTER, CgGL.GL_NEAREST);
        CgGL.glTexParameteri(CgGL.GL_TEXTURE_2D, CgGL.GL_TEXTURE_MAG_FILTER, CgGL.GL_NEAREST);
        return texture;
    }

    private static int buffer(int target, ByteBuffer data) {
        int b = CgGL.glGenBuffers();
        CgGL.glBindBuffer(target, b);
        CgGL.glBufferData(target, data, CgGL.GL_STATIC_DRAW);
        return b;
    }

    private static ByteBuffer ints(int... values) {
        ByteBuffer b = bytes(values.length * 4);
        for (int v : values) b.putInt(v);
        return b.flip();
    }

    private static ByteBuffer bytes(int n) {
        return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
    }

    @Override
    public void dispose() {
        if (fill != 0) CgGL.glDeleteProgram(fill);
        if (paint != 0) CgGL.glDeleteProgram(paint);
        if (quads != null) quads.delete();
        if (image != null) image.delete();
        if (colourEmulated != null) colourEmulated.delete();
    }
}
