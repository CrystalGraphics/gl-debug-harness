package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.compute.emit.CgKernelTarget;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.capture.HarnessCapture;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.DeviceErrors;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.command.CgComputePass;
import com.crystalgraphics.platform.rhi.command.CgPassDesc;
import com.crystalgraphics.platform.rhi.command.CgRenderPass;
import com.crystalgraphics.platform.rhi.command.CgTransition;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgRHIResource;
import com.crystalgraphics.platform.rhi.resource.CgTextureRegion;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.platform.rhi.sync.CgGpuReadback;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.graph.CgFrameGraph;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.gpu.resource.CgTextureDesc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * gpu-compute C1's gate, the compute seam on every device: two kernels write 48 quads (an indirect dispatch from
 * CPU-written group counts) and a 64x64 image, drawn through {@code CgRHI} with quads 40 to 47 by an indirect draw
 * ({@code compute-seam-new.png}); the same draws from CPU-written data are the reference ({@code compute-seam-ref.png}).
 * The kernels use integer arithmetic, so the two match exactly.
 *
 * <p>C2's beside it: {@code harness:shaders/compute_seam.compute}'s map, general and image kernels run through the
 * graph as the engine runs them, the general one counting its work group through subgroups; what they wrote, read
 * back, must be the reference's bytes ({@code compute-seam-kernels.png} is their image).</p>
 *
 * <pre>
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=compute-seam --rhi=vulkan"
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=compute-seam --rhi=gl"
 *   ... -Dcrystalgraphics.compute.emulateSubgroups=true       # the .compute's subgroups emulated
 *   ... -Dcrystalgraphics.harness.computeSeam.skipBarriers=true -Dcrystalgraphics.vulkan.syncValidation=true
 * </pre>
 *
 * <ul>
 *   <li>Prints {@code [compute-seam] PASS} or {@code FAIL}; a device error or a Vulkan validation error fails it.</li>
 *   <li>{@code .skipBarriers=true} leaves out the barrier between the quads' kernel and the draw reading them: sync
 *       validation must then report the hazard, which is what shows the clean run was checked.</li>
 * </ul>
 */
public class CgComputeSeamTestScene implements InteractiveSceneLifecycle {

    private static final int W = 800, H = 700;
    private static final int QUADS = 48, DIRECT = 40, IMAGE = 64, QUAD_BYTES = 32;
    /** Frames the readbacks may take to land before the scene gives up. */
    private static final int LAND_FRAMES = 30;
    private static final boolean SKIP_BARRIERS = Boolean.getBoolean("crystalgraphics.harness.computeSeam.skipBarriers");
    private static final CgFormat PICTURE = CgFormat.RGBA8_UNORM;

    private static final String QUAD_STRUCT = "struct Quad { vec4 rect; uint color; uint pad0, pad1, pad2; };\n";
    private static final String CORNERS =
            "const vec2 CORNERS[6] = vec2[6](vec2(0, 0), vec2(1, 0), vec2(1, 1), vec2(0, 0), vec2(1, 1), vec2(0, 1));\n";

    private static final String FILL = "#version 450\n"
            + "layout(local_size_x = 64) in;\n" + QUAD_STRUCT
            + "layout(set = 0, binding = 0) writeonly buffer Quads { Quad quads[]; };\n"
            + "layout(push_constant) uniform Count { int count; };\n"
            + "void main() {\n"
            + "    uint i = gl_GlobalInvocationID.x;\n"
            + "    if (i >= uint(count)) return;\n"
            + "    quads[i].rect = vec4(20.0 + float(i % 8u) * 60.0, 300.0 + float(i / 8u) * 60.0, 50.0, 50.0);\n"
            + "    quads[i].color = ((i * 37u) & 255u) | (((i * 91u) & 255u) << 8) | (((i * 53u) & 255u) << 16)"
            + " | (255u << 24);\n"
            + "    quads[i].pad0 = 0u; quads[i].pad1 = 0u; quads[i].pad2 = 0u;\n"
            + "}\n";

    private static final String PAINT = "#version 450\n"
            + "layout(local_size_x = 8, local_size_y = 8) in;\n"
            + "layout(set = 0, binding = 0, rgba8) uniform writeonly image2D picture;\n"
            + "void main() {\n"
            + "    ivec2 p = ivec2(gl_GlobalInvocationID.xy);\n"
            + "    imageStore(picture, p, vec4(float(p.x * 4), float(p.y * 4), float(((p.x ^ p.y) * 4) & 255), 255.0)"
            + " / 255.0);\n"
            + "}\n";

    private static final String QUAD_VERTEX = "#version 450\n" + QUAD_STRUCT
            + "layout(set = 0, binding = 0) readonly buffer Quads { Quad quads[]; };\n"
            + "layout(push_constant) uniform Draw { vec2 size; int first; };\n"
            + "layout(location = 0) out vec4 colour;\n" + CORNERS
            + "void main() {\n"
            + "    Quad q = quads[first + gl_InstanceIndex];\n"
            + "    colour = unpackUnorm4x8(q.color);\n"
            + "    gl_Position = vec4((q.rect.xy + CORNERS[gl_VertexIndex] * q.rect.zw) / size * 2.0 - 1.0, 0.0, 1.0);\n"
            + "}\n";
    private static final String QUAD_FRAGMENT = "#version 450\nlayout(location = 0) in vec4 colour;\n"
            + "layout(location = 0) out vec4 o;\nvoid main() { o = colour; }\n";

    private static final String IMAGE_VERTEX = "#version 450\n"
            + "layout(push_constant) uniform Draw { vec4 rect; vec2 size; };\n"
            + "layout(location = 0) out vec2 uv;\n" + CORNERS
            + "void main() {\n"
            + "    uv = CORNERS[gl_VertexIndex];\n"
            + "    gl_Position = vec4((rect.xy + uv * rect.zw) / size * 2.0 - 1.0, 0.0, 1.0);\n"
            + "}\n";
    private static final String IMAGE_FRAGMENT = "#version 450\n"
            + "layout(set = 0, binding = 0) uniform sampler2D picture;\nlayout(location = 0) in vec2 uv;\n"
            + "layout(location = 0) out vec4 o;\nvoid main() { o = texture(picture, uv); }\n";

    private final List<CgRHIResource> made = new ArrayList<>();
    private final CgFrameBuilder builder = new CgFrameBuilder();
    private final CgFrameGraph graph = new CgFrameGraph();
    private final CgRecording recording = new CgRecording();
    private final List<CgRequest> requests = new ArrayList<>();
    private CgGpuReadback seamPixels, cpuPixels;
    private CgGraphBuffer fileQuads;
    private ByteBuffer fileQuadBytes, fileImage;
    private String on, failure;
    private int frame;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        if (frame++ == 0) {
            CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
            if (device == null) {
                System.out.println("[compute-seam] FAIL on gl: no RHI device (a context below 4.5, or crystalgraphics.harness.glDevice=false)");
                running = false;
                return;
            }
            on = device.name();
            try {
                record();
                runFile();
            } catch (RuntimeException e) {
                System.out.println("[compute-seam] FAIL on " + on + ": " + e);
                running = false;
                throw e;
            }
            return;
        }
        boolean landed = seamPixels.ready() && cpuPixels.ready() && requests.stream().allMatch(CgRequest::done);
        if (!landed && frame < LAND_FRAMES) return;
        verdict(ctx, landed);
        running = false;
    }

    // ── The kernels under test, and the draws, through CgRHI ───────────────────

    /** Both pictures recorded: the kernels' and the CPU's, each drawn through the same pipelines and read back. */
    private void record() {
        CgRHI rhi = CgRHI.get();
        CgDeviceSection.begin();
        try {
            CgBindingLayout storage = keep(rhi.createBindingLayout("compute-seam storage",
                    List.of(new CgBindingLayout.Slot(0, CgBindingLayout.Type.STORAGE_BUFFER)), true));
            CgBindingLayout image = keep(rhi.createBindingLayout("compute-seam image",
                    List.of(new CgBindingLayout.Slot(0, CgBindingLayout.Type.STORAGE_IMAGE)), true));
            CgBindingLayout sampled = keep(rhi.createBindingLayout("compute-seam sampled",
                    List.of(new CgBindingLayout.Slot(0, CgBindingLayout.Type.SAMPLED_TEXTURE)), true));
            CgComputePipeline fill = keep(rhi.createComputePipeline("compute-seam fill", compute(rhi, FILL),
                    new CgPipelineLayout(List.of(storage), 4)));
            CgComputePipeline paint = keep(rhi.createComputePipeline("compute-seam paint", compute(rhi, PAINT),
                    CgPipelineLayout.of(image)));
            CgGraphicsPipeline quads = keep(pipeline(rhi, "compute-seam quads", QUAD_VERTEX, QUAD_FRAGMENT,
                    new CgPipelineLayout(List.of(storage), 12)));
            CgGraphicsPipeline picture = keep(pipeline(rhi, "compute-seam image", IMAGE_VERTEX, IMAGE_FRAGMENT,
                    new CgPipelineLayout(List.of(sampled), 24)));
            CgGpuSampler nearest = keep(rhi.createSampler(new CgGpuSampler.Desc(CgGpuSampler.Filter.NEAREST,
                    CgGpuSampler.Filter.NEAREST, CgGpuSampler.MipFilter.NEAREST, CgGpuSampler.Wrap.CLAMP_TO_EDGE,
                    CgGpuSampler.Wrap.CLAMP_TO_EDGE, CgGpuSampler.Wrap.CLAMP_TO_EDGE, null, 0f, 0f, 1f)));
            Pipelines p = new Pipelines(fill, paint, quads, picture, nearest);

            CgCommandList cmd = rhi.encoder();
            seamPixels = picture(rhi, cmd, p, true);
            cpuPixels = picture(rhi, cmd, p, false);
        } finally {
            CgDeviceSection.end();
        }
    }

    private record Pipelines(CgComputePipeline fill, CgComputePipeline paint, CgGraphicsPipeline quads,
                             CgGraphicsPipeline image, CgGpuSampler sampler) {}

    /** One picture: its quads and image from the kernels or from the CPU, drawn into a target read back after. */
    private CgGpuReadback picture(CgRHI rhi, CgCommandList cmd, Pipelines p, boolean kernels) {
        String what = kernels ? "kernels" : "cpu";
        CgGpuBuffer records = keep(rhi.createBuffer(new CgGpuBuffer.Desc("compute-seam " + what + " quads",
                (long) QUADS * QUAD_BYTES, CgGpuBuffer.Usage.ALL, false)));
        CgGpuTexture texture = keep(rhi.createTexture(new CgGpuTexture.Desc("compute-seam " + what + " image",
                CgGpuTexture.Kind.D2, CgFormat.RGBA8_UNORM, IMAGE, IMAGE, 1, 1, 1, CgGpuTexture.Usage.ALL)));
        CgBindings recordBindings = new CgBindings().buffer(0, CgBindingLayout.Type.STORAGE_BUFFER, records, 0,
                (long) QUADS * QUAD_BYTES);
        if (kernels) {
            CgGpuBuffer groups = keep(rhi.createBuffer(new CgGpuBuffer.Desc("compute-seam groups", 12,
                    CgGpuBuffer.Usage.ALL, false)));
            cmd.writeBuffer(groups, 0, ints(1, 1, 1));
            cmd.transition(new CgTransition().buffer(groups, CgAccess.COPY_WRITE, CgAccess.INDIRECT)
                    .texture(CgTextureView.whole(texture), CgAccess.RESTING, CgAccess.COMPUTE_WRITE));
            CgComputePass pass = cmd.beginCompute("compute-seam kernels");
            pass.setPipeline(p.fill());
            pass.pushBindings(recordBindings);
            pass.pushConstants(0, QUADS);
            pass.dispatchIndirect(groups, 0);
            pass.setPipeline(p.paint());
            pass.pushBindings(new CgBindings().image(0, CgTextureView.whole(texture)));
            pass.dispatch(IMAGE / 8, IMAGE / 8, 1);
            pass.end();
            CgTransition after = new CgTransition()
                    .texture(CgTextureView.whole(texture), CgAccess.COMPUTE_WRITE, CgAccess.SAMPLED_READ);
            if (!SKIP_BARRIERS) after.buffer(records, CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ);
            cmd.transition(after);
        } else {
            cmd.writeBuffer(records, 0, cpuQuads());
            cmd.writeTexture(texture, CgTextureRegion.of2D(0, 0, 0, IMAGE, IMAGE), cpuTexels());
            cmd.transition(new CgTransition().buffer(records, CgAccess.COPY_WRITE, CgAccess.VERTEX_READ)
                    .texture(CgTextureView.whole(texture), CgAccess.RESTING, CgAccess.SAMPLED_READ));
        }

        CgGpuTexture target = keep(rhi.createTexture(new CgGpuTexture.Desc("compute-seam " + what + " target",
                CgGpuTexture.Kind.D2, CgFormat.RGBA8_UNORM, W, H, 1, 1, 1, CgGpuTexture.Usage.ALL)));
        CgGpuBuffer args = null;
        if (kernels) {
            args = keep(rhi.createBuffer(new CgGpuBuffer.Desc("compute-seam draw args", 16, CgGpuBuffer.Usage.ALL, false)));
            cmd.writeBuffer(args, 0, ints(6, QUADS - DIRECT, 0, 0));
            cmd.transition(new CgTransition().buffer(args, CgAccess.COPY_WRITE, CgAccess.INDIRECT));
        }
        CgRenderPass draw = cmd.beginPass(new CgPassDesc("compute-seam " + what, List.of(new CgPassDesc.Color(
                CgTextureView.whole(target), CgPassDesc.LoadOp.CLEAR, 0.08f, 0.08f, 0.1f, 1f, CgPassDesc.StoreOp.STORE)),
                null, W, H));
        draw.setViewport(0, 0, W, H, 0, 1);
        draw.setScissor(0, 0, W, H);
        draw.setPipeline(p.quads());
        draw.pushBindings(recordBindings);
        draw.pushConstants(0, drawConstants(0));
        draw.draw(6, kernels ? DIRECT : QUADS, 0, 0);
        if (kernels) {
            // The rest by an indirect draw, from CPU-written arguments
            draw.pushConstants(0, drawConstants(DIRECT));
            draw.drawIndirect(args, 0, 1, 16);
        }
        draw.setPipeline(p.image());
        draw.pushBindings(new CgBindings().texture(0, CgTextureView.whole(texture), p.sampler()));
        draw.pushConstants(0, floats(520f, 300f, IMAGE * 4f, IMAGE * 4f, W, H));
        draw.draw(6, 1, 0, 0);
        draw.end();
        cmd.transition(new CgTransition().texture(CgTextureView.whole(target), CgAccess.COLOR_WRITE, CgAccess.COPY_READ));
        CgGpuReadback back = new CgGpuReadback(rhi, "compute-seam " + what + " pixels", W * H * 4);
        back.enqueue(cmd, target, CgTextureRegion.of2D(0, 0, 0, W, H));
        return back;
    }

    // ── The .compute's kernels, through the graph ──────────────────────────────

    /** Placed, coloured, painted, each pass after the last; their buffer and image read back once it ran. */
    private void runFile() {
        CgCompute seam = CgCompute.load("harness:shaders/compute_seam.compute");
        CgKernel place = seam.kernel("Place"), colour = seam.kernel("Colour"), paint = seam.kernel("Paint");
        fileQuads = CgGraphBuffer.persistent("compute-seam file quads",
                CgBufferDesc.of((long) QUADS * QUAD_BYTES, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        CgGraphTexture image = CgGraphTexture.requested("compute-seam file image", new CgTextureDesc(IMAGE, IMAGE, PICTURE));
        recording.reset();
        var placing = recording.compute("compute-seam place");
        placing.dispatch(place, QUADS).bind("QUADS", fileQuads);
        placing.end();
        var colouring = recording.compute("compute-seam colour");
        colouring.dispatch(colour, QUADS).bind("QUADS", fileQuads);
        colouring.end();
        var painting = recording.compute("compute-seam paint");
        painting.dispatch(paint, IMAGE, IMAGE, 1).image("PICTURE", image);
        painting.end();
        requests.add(recording.readback(fileQuads, 0, (long) QUADS * QUAD_BYTES, data -> fileQuadBytes = copy(data)));
        requests.add(recording.readback(image, 0, 0, 0, IMAGE, IMAGE, data -> fileImage = copy(data)));
        execute();
    }

    private void execute() {
        CgFrame built = builder.build(graph.add(recording.seal()));
        graph.clear();
        CgExecutor.execute(built);
        builder.recycle(built);
        recording.reset();
    }

    // ── The verdict ────────────────────────────────────────────────────────────

    private void verdict(HarnessContext ctx, boolean landed) {
        int validation = PlatformServiceHarness.validationErrors();
        boolean errors = DeviceErrors.checkAndLog("compute-seam");
        String subgroups = CgKernelTarget.current().nativeSubgroups() ? "native" : "emulated";
        if (landed) {
            ByteBuffer seam = seamPixels.data(), cpu = cpuPixels.data();
            HarnessCapture.write(HarnessCapture.image(seam, W, H, false), ctx.getOutputDir(), "compute-seam-new.png");
            HarnessCapture.write(HarnessCapture.image(cpu, W, H, false), ctx.getOutputDir(), "compute-seam-ref.png");
            if (fileImage != null) {
                HarnessCapture.write(HarnessCapture.image(fileImage, IMAGE, IMAGE, false), ctx.getOutputDir(),
                        "compute-seam-kernels.png");
            }
            failure = differs("the kernels' picture", seam, cpu, W);
            if (failure == null) failure = requestFailure();
            if (failure == null) failure = differs("the .compute's quads", fileQuadBytes, cpuQuads(), 0);
            if (failure == null) failure = differs("the .compute's image", fileImage, cpuTexels(), IMAGE);
        } else {
            failure = "the readbacks did not land within " + LAND_FRAMES + " frames";
        }
        seamPixels.release();
        cpuPixels.release();
        if (errors) {
            System.out.println("[compute-seam] FAIL on " + on + ": device errors, logged above");
        } else if (validation > 0) {
            System.out.println("[compute-seam] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (failure != null) {
            System.out.println("[compute-seam] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[compute-seam] PASS on " + on + ": the kernels' picture matches the CPU's, and the"
                    + " .compute's buffer and image its bytes, subgroups " + subgroups);
        }
    }

    private String requestFailure() {
        for (CgRequest r : requests) if (r.failed()) return r + ": " + r.failure();
        return null;
    }

    /** Null where {@code made} is {@code want} byte for byte; else where they part, as a pixel where {@code width} > 0. */
    private static String differs(String what, ByteBuffer made, ByteBuffer want, int width) {
        if (made == null) return what + " never came back";
        int at = made.duplicate().rewind().mismatch(want.duplicate().rewind());
        if (at < 0) return null;
        if (width == 0) return what + " differs at byte " + at;
        int texel = at / 4;
        return what + " differs from the reference, the first at (" + texel % width + ", " + texel / width + ")";
    }

    // ── What the kernels write, computed here ──────────────────────────────────

    private static ByteBuffer cpuQuads() {
        ByteBuffer q = bytes(QUADS * QUAD_BYTES);
        for (int i = 0; i < QUADS; i++) {
            q.putFloat(20f + (i % 8) * 60f).putFloat(300f + (i / 8) * 60f).putFloat(50f).putFloat(50f);
            q.putInt((i * 37 & 255) | (i * 91 & 255) << 8 | (i * 53 & 255) << 16 | 255 << 24);
            q.putInt(0).putInt(0).putInt(0);
        }
        return q.flip();
    }

    private static ByteBuffer cpuTexels() {
        ByteBuffer texels = bytes(IMAGE * IMAGE * 4);
        for (int y = 0; y < IMAGE; y++) {
            for (int x = 0; x < IMAGE; x++) {
                texels.put((byte) (x * 4)).put((byte) (y * 4)).put((byte) ((x ^ y) * 4 & 255)).put((byte) 255);
            }
        }
        return texels.flip();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private <T extends CgRHIResource> T keep(T object) {
        made.add(object);
        return object;
    }

    private CgShaderModule compute(CgRHI rhi, String glsl) {
        return keep(rhi.createShaderModule(CgShaderModule.Stage.COMPUTE,
                CgGlslCompiler.get().compileExplicitCompute(glsl, "compute-seam").spirv(), "compute-seam"));
    }

    private CgGraphicsPipeline pipeline(CgRHI rhi, String label, String vertex, String fragment, CgPipelineLayout layout) {
        CgGlslCompiler.Explicit program = CgGlslCompiler.get().compileExplicit(vertex, fragment, Map.of(), label);
        return rhi.createPipeline(new CgGraphicsPipelineDesc(label, layout,
                keep(rhi.createShaderModule(CgShaderModule.Stage.VERTEX, program.vertex(), label)),
                keep(rhi.createShaderModule(CgShaderModule.Stage.FRAGMENT, program.fragment(), label)), List.of(),
                CgGraphicsPipelineDesc.Topology.TRIANGLES, CgGraphicsPipelineDesc.Raster.DEFAULT,
                CgGraphicsPipelineDesc.DepthStencil.OFF,
                List.of(new CgGraphicsPipelineDesc.ColorTarget(CgFormat.RGBA8_UNORM, null, 0xF)), null, 1));
    }

    private static ByteBuffer drawConstants(int first) {
        ByteBuffer b = bytes(12);
        b.putFloat(W).putFloat(H).putInt(first);
        return b.flip();
    }

    private static ByteBuffer floats(float... values) {
        ByteBuffer b = bytes(values.length * 4);
        for (float v : values) b.putFloat(v);
        return b.flip();
    }

    private static ByteBuffer ints(int... values) {
        ByteBuffer b = bytes(values.length * 4);
        for (int v : values) b.putInt(v);
        return b.flip();
    }

    private static ByteBuffer copy(ByteBuffer data) {
        return bytes(data.remaining()).put(data).flip();
    }

    private static ByteBuffer bytes(int n) {
        return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return true; }

    @Override
    public void dispose() {
        CgRHI rhi = CgRHI.installed() ? CgRHI.get() : null;
        if (rhi != null && !made.isEmpty()) {
            CgDeviceSection.begin();
            try {
                for (CgRHIResource o : made) rhi.release(o);
            } finally {
                CgDeviceSection.end();
            }
        }
        if (fileQuads != null) {
            recording.release(fileQuads);
            execute();
        }
    }
}
