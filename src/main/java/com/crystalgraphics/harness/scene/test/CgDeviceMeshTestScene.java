package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshWriter;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.gl.CgGLImports;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindingSet;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.command.CgPassDesc;
import com.crystalgraphics.platform.rhi.command.CgRenderPass;
import com.crystalgraphics.platform.rhi.command.CgTransition;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgRHIResource;
import com.crystalgraphics.platform.rhi.resource.CgTextureRegion;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.platform.rhi.sync.CgGpuReadback;
import com.crystalgraphics.render.draw.CgDeviceStates;
import com.crystalgraphics.render.draw.CgDrawStateCache;
import com.crystalgraphics.render.mesh.CgMeshStore;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Meshes drawn through {@code CgMeshStore}'s device draws ({@code drawOn}, {@code drawJoinedOn}) on {@code CgRHI}, their
 * vertices pulled from the store's words ({@code vertexWords}) with no vertex input: an indexed mesh in its format's
 * pool, one drawn without indices (the store's run of 0, 1, 2 ...), a frame mesh on the ring, and two joined into one
 * indirect call, each a band of its own colour, read back and compared with the picture worked out here. On
 * {@code --device=vulkan} and {@code --device=gl}.
 *
 * <pre>
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=device-mesh --device=vulkan"
 * </pre>
 */
public class CgDeviceMeshTestScene implements HarnessSceneLifecycle {

    static final int SIZE = 64;
    /** Each band's colour, ARGB: the slab's, the run's, the ring's, the joined pair's. */
    static final int[] COLOURS = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00};

    /** POS2_UV2_COL4UB's position, the first two of its five words a vertex, pulled at the store's slot (1, 4). */
    private static final String VERTEX = "#version 450\n"
            + "layout(std430, set = 1, binding = 4) readonly buffer W { uint w[]; };\n"
            + "void main() { uint v = uint(gl_VertexIndex) * 5u;\n"
            + "    gl_Position = vec4(uintBitsToFloat(w[v]), uintBitsToFloat(w[v + 1u]), 0.5, 1.0); }\n";
    private static final String FRAGMENT = "#version 450\nlayout(push_constant) uniform P { vec4 colour; } p;\n"
            + "layout(location = 0) out vec4 o;\nvoid main() { o = p.colour; }\n";

    private boolean done;

    @Override
    public void init(HarnessContext ctx) {
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (done) return;
        done = true;
        String on = PlatformServiceHarness.deviceInfo() != null ? PlatformServiceHarness.deviceInfo().name() : "gl";
        try {
            System.out.println("[device-mesh] " + check(on));
        } catch (RuntimeException e) {
            System.out.println("[device-mesh] FAIL on " + on + ": " + e);
            e.printStackTrace();
        }
    }

    /** A band's quad, x from {@code x0} to {@code x1} in clip space, y from -0.5 to 0.5. */
    private static void quad(CgMeshWriter m, float x0, float x1, boolean indexed) {
        if (indexed) {
            int a = m.vertex().position(x0, -0.5f).uv(0, 0).color(0xFFFFFFFF).end(), b = m.vertex().position(x1, -0.5f).uv(0, 0).color(0xFFFFFFFF).end();
            int c = m.vertex().position(x1, 0.5f).uv(0, 0).color(0xFFFFFFFF).end(), d = m.vertex().position(x0, 0.5f).uv(0, 0).color(0xFFFFFFFF).end();
            m.triangle(a, b, c).triangle(a, c, d);
        } else {
            m.vertex().position(x0, -0.5f).uv(0, 0).color(0xFFFFFFFF).end();
            m.vertex().position(x1, -0.5f).uv(0, 0).color(0xFFFFFFFF).end();
            m.vertex().position(x1, 0.5f).uv(0, 0).color(0xFFFFFFFF).end();
            m.vertex().position(x0, -0.5f).uv(0, 0).color(0xFFFFFFFF).end();
            m.vertex().position(x1, 0.5f).uv(0, 0).color(0xFFFFFFFF).end();
            m.vertex().position(x0, 0.5f).uv(0, 0).color(0xFFFFFFFF).end();
        }
    }

    private static String check(String on) {
        CgVertexFormat format = CgVertexFormat.POS2_UV2_COL4UB;
        CgMesh slab = CgMesh.build(format, m -> quad(m, -1f, -0.5f, true));
        CgMesh run = CgMesh.build(format, CgMesh.Usage.DYNAMIC, m -> quad(m, -0.5f, 0f, false));
        CgMesh ring = CgMesh.build(format, CgMesh.Usage.FRAME, m -> quad(m, 0f, 0.5f, true));
        CgMesh left = CgMesh.build(format, m -> quad(m, 0.5f, 0.75f, true));
        CgMesh right = CgMesh.build(format, m -> quad(m, 0.75f, 1f, true));
        CgMeshStore store = CgMeshStore.get();
        // Placed and uploaded first: the store's uploads go through CgGL, outside the device's section.
        for (CgMesh m : new CgMesh[] {slab, run, ring, left, right}) store.place(m);
        store.upload();

        CgRHI rhi = CgRHI.get();
        List<CgRHIResource> made = new ArrayList<>();
        CgDeviceSection.begin();
        try {
            CgGlslCompiler.Explicit program = CgGlslCompiler.get().compileExplicit(VERTEX, FRAGMENT, Map.of(), "device-mesh");
            CgShaderModule vs = keep(made, rhi.createShaderModule(CgShaderModule.Stage.VERTEX, program.vertex(), "device-mesh"));
            CgShaderModule fs = keep(made, rhi.createShaderModule(CgShaderModule.Stage.FRAGMENT, program.fragment(), "device-mesh"));
            CgPipelineLayout layout = CgDeviceStates.layout(rhi, program);
            CgBindingSet pooled = keep(made, words(rhi, layout, store.vertexWords(slab)));
            CgBindingSet paged = keep(made, words(rhi, layout, store.vertexWords(ring)));
            CgGraphicsPipeline pipeline = keep(made, rhi.createPipeline(new CgGraphicsPipelineDesc("device-mesh",
                    layout, vs, fs, List.of(),
                    CgMeshStore.topology(slab), CgGraphicsPipelineDesc.Raster.DEFAULT, CgGraphicsPipelineDesc.DepthStencil.OFF,
                    List.of(new CgGraphicsPipelineDesc.ColorTarget(CgFormat.RGBA8_UNORM, null, 0xF)), null, 1)));
            CgGpuTexture target = keep(made, rhi.createTexture(new CgGpuTexture.Desc("device-mesh target",
                    CgGpuTexture.Kind.D2, CgFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1, 1, CgGpuTexture.Usage.ALL)));
            CgGpuReadback back = new CgGpuReadback(rhi, "device-mesh pixels", SIZE * SIZE * 4);
            CgDrawStateCache cache = new CgDrawStateCache();
            ByteBuffer colour = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());

            CgCommandList cmd = rhi.encoder();
            CgRenderPass pass = cmd.beginPass(new CgPassDesc("device-mesh", List.of(new CgPassDesc.Color(
                    CgTextureView.whole(target), CgPassDesc.LoadOp.CLEAR, 0, 0, 0, 1, CgPassDesc.StoreOp.STORE)), null, SIZE, SIZE));
            pass.setViewport(0, 0, SIZE, SIZE, 0, 1);
            pass.setScissor(0, 0, SIZE, SIZE);
            cache.reset();
            cache.pipeline(pass, pipeline);
            push(pass, colour, COLOURS[0]);
            pass.setBindingSet(1, pooled);
            store.drawOn(pass, cache, slab, 1, 0, -1, 0, -1);
            push(pass, colour, COLOURS[1]);
            store.drawOn(pass, cache, run, 1, 0, -1, 0, -1);
            push(pass, colour, COLOURS[2]);
            pass.setBindingSet(1, paged);
            store.drawOn(pass, cache, ring, 1, 0, -1, 0, -1);
            push(pass, colour, COLOURS[3]);
            pass.setBindingSet(1, pooled);
            if (!store.join(left, 1, -1, 0, -1, 0) || !store.join(right, 1, -1, 0, -1, 0)) {
                store.dropJoined();
                throw new IllegalStateException("the two static quads did not join");
            }
            store.drawJoinedOn(pass, cache);
            pass.end();
            cmd.transition(new CgTransition().texture(CgTextureView.whole(target), CgAccess.COLOR_WRITE, CgAccess.COPY_READ));
            back.enqueue(cmd, target, CgTextureRegion.of2D(0, 0, 0, SIZE, SIZE));
            for (int i = 0; i < 8 && !back.ready(); i++) rhi.endFrame();
            if (!back.ready()) throw new IllegalStateException("the readback did not land within 8 frames");
            ByteBuffer bytes = back.data();
            int differing = 0, first = -1, firstGot = 0;
            for (int i = 0; i < SIZE * SIZE; i++) {
                int r = bytes.get(i * 4) & 0xFF, g = bytes.get(i * 4 + 1) & 0xFF, b = bytes.get(i * 4 + 2) & 0xFF;
                int got = 0xFF000000 | r << 16 | g << 8 | b;
                if (got == expected(i % SIZE, i / SIZE)) continue;
                if (differing++ == 0) {
                    first = i;
                    firstGot = got;
                }
            }
            back.release();
            int validation = PlatformServiceHarness.validationErrors();
            if (validation > 0) return "FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above";
            if (differing > 0) {
                return "FAIL on " + on + ": " + differing + " pixels differ, the first at (" + first % SIZE + ", " + first / SIZE
                        + "): 0x" + Integer.toHexString(firstGot) + " for 0x" + Integer.toHexString(expected(first % SIZE, first / SIZE));
            }
            return "PASS on " + on + ": a pooled indexed mesh, an unindexed one, a ring frame mesh and two joined into one "
                    + "indirect call, drawn on the device through the store, match the picture pixel for pixel";
        } finally {
            for (CgRHIResource o : made) rhi.release(o);
            CgDeviceSection.end();
            for (CgMesh m : new CgMesh[] {slab, run, ring, left, right}) m.release();
        }
    }

    /** A set of the store's words {@code name} at (1, 4), where the program pulls from. */
    private static CgBindingSet words(CgRHI rhi, CgPipelineLayout layout, int name) {
        CgGLImports imports = CgGLImports.get();
        CgGpuBuffer buffer = imports.buffer(name);
        long offset = imports.bufferOffset(name);
        return rhi.createBindingSet("device-mesh words", layout.sets().get(1),
                new CgBindings().buffer(4, CgBindingLayout.Type.STORAGE_BUFFER, buffer, offset, buffer.size() - offset));
    }

    private static void push(CgRenderPass pass, ByteBuffer colour, int argb) {
        colour.clear();
        colour.putFloat((argb >> 16 & 0xFF) / 255f).putFloat((argb >> 8 & 0xFF) / 255f).putFloat((argb & 0xFF) / 255f)
                .putFloat(1f).flip();
        pass.pushConstants(0, colour);
    }

    /** ARGB: four bands across the middle half of the rows, black elsewhere. */
    static int expected(int x, int y) {
        if (y < SIZE / 4 || y >= 3 * SIZE / 4) return 0xFF000000;
        return COLOURS[Math.min(3, x / (SIZE / 4))];
    }

    private static <T extends CgRHIResource> T keep(List<CgRHIResource> made, T object) {
        made.add(object);
        return object;
    }

    @Override
    public void dispose() {
    }
}
