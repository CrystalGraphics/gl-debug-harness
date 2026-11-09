package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindingSet;
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
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgRHIResource;
import com.crystalgraphics.platform.rhi.resource.CgTextureRegion;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.platform.rhi.sync.CgGpuReadback;
import com.crystalgraphics.trace.CgGpuTrace;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Draws and dispatches through {@code CgRHI} alone: a kernel writes a square's corners, a draw pulls them, coloured
 * through a binding set and scaled by a push constant, into a 64-pixel target read back without a stall, then compared
 * with the picture worked out here. The same commands on either device.
 *
 * <pre>
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=rhi-scene --rhi=vulkan"
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=rhi-scene --rhi=gl"
 * </pre>
 *
 * <p>Prints {@code [rhi-scene] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgRhiTestScene implements HarnessSceneLifecycle {

    static final int SIZE = 64;
    /** Exact in eight bits: 51, 153, 255. */
    static final float R = 0.2f, G = 0.6f, B = 1.0f;

    private static final String KERNEL = "#version 450\nlayout(local_size_x = 1) in;\n"
            + "layout(set = 0, binding = 0) buffer Corners { vec4 corners[]; };\n"
            + "const vec2 SQUARE[6] = vec2[](vec2(-0.5, -0.5), vec2(0.5, -0.5), vec2(0.5, 0.5),\n"
            + "                              vec2(-0.5, -0.5), vec2(0.5, 0.5), vec2(-0.5, 0.5));\n"
            + "void main() { uint i = gl_GlobalInvocationID.x; corners[i] = vec4(SQUARE[i], 0.5, 1.0); }\n";
    private static final String VERTEX = "#version 450\n"
            + "layout(set = 0, binding = 0) readonly buffer Corners { vec4 corners[]; };\n"
            + "void main() { gl_Position = corners[gl_VertexIndex]; }\n";
    private static final String FRAGMENT = "#version 450\n"
            + "layout(set = 1, binding = 0) uniform Colour { vec4 colour; };\n"
            + "layout(push_constant) uniform Scale { int scale; };\n"
            + "layout(location = 0) out vec4 o;\nvoid main() { o = colour * float(scale); }\n";

    @Override
    public void init(HarnessContext ctx) {
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (PlatformServiceHarness.deviceInfo() == null) {
            System.out.println("[rhi-scene] FAIL on gl: no RHI device (a context below 4.5, or crystalgraphics.harness.glDevice=false)");
            return;
        }
        String on = PlatformServiceHarness.deviceInfo().name();
        int[] made;
        try {
            made = throughRhi();
        } catch (RuntimeException e) {
            System.out.println("[rhi-scene] FAIL on " + on + ": " + e);
            throw e;
        }
        save(ctx, made, "rhi-scene.png");
        int validation = PlatformServiceHarness.validationErrors();
        if (validation > 0) {
            System.out.println("[rhi-scene] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
            return;
        }
        int differing = 0, first = -1;
        for (int i = 0; i < made.length; i++) {
            if (made[i] == expected(i % SIZE, i / SIZE)) continue;
            if (differing++ == 0) first = i;
        }
        if (differing == 0) {
            System.out.println("[rhi-scene] PASS on " + on + ": a kernel's square, drawn through CgRHI alone, matches the"
                    + " picture pixel for pixel");
        } else {
            System.out.println("[rhi-scene] FAIL on " + on + ": " + differing + " pixels differ, the first at ("
                    + first % SIZE + ", " + first / SIZE + "): 0x" + Integer.toHexString(made[first]) + " for 0x"
                    + Integer.toHexString(expected(first % SIZE, first / SIZE)));
        }
    }

    /** ARGB: the square's colour on its 32 by 32 pixels in the middle, black elsewhere. */
    static int expected(int x, int y) {
        boolean in = x >= SIZE / 4 && x < 3 * SIZE / 4 && y >= SIZE / 4 && y < 3 * SIZE / 4;
        return in ? 0xFF000000 | Math.round(R * 255) << 16 | Math.round(G * 255) << 8 | Math.round(B * 255) : 0xFF000000;
    }

    private int[] throughRhi() {
        CgRHI rhi = CgRHI.get();
        List<CgRHIResource> made = new ArrayList<>();
        // The engine's open work ends: until it draws again, the device's list is ours.
        CgDeviceSection.begin();
        try {
            CgBindingLayout corners = keep(made, rhi.createBindingLayout("rhi-scene corners",
                    List.of(new CgBindingLayout.Slot(0, CgBindingLayout.Type.STORAGE_BUFFER)), true));
            CgBindingLayout material = keep(made, rhi.createBindingLayout("rhi-scene material",
                    List.of(new CgBindingLayout.Slot(0, CgBindingLayout.Type.UNIFORM_BUFFER)), false));
            CgGlslCompiler.Explicit program = CgGlslCompiler.get().compileExplicit(VERTEX, FRAGMENT, Map.of(), "rhi-scene");
            CgComputePipeline kernel = keep(made, rhi.createComputePipeline("rhi-scene corners", module(rhi, made,
                    CgShaderModule.Stage.COMPUTE, CgGlslCompiler.get().compileExplicitCompute(KERNEL, "rhi-scene").spirv()),
                    CgPipelineLayout.of(corners)));
            CgGraphicsPipeline square = keep(made, rhi.createPipeline(new CgGraphicsPipelineDesc("rhi-scene square",
                    new CgPipelineLayout(List.of(corners, material), 4),
                    module(rhi, made, CgShaderModule.Stage.VERTEX, program.vertex()),
                    module(rhi, made, CgShaderModule.Stage.FRAGMENT, program.fragment()), List.of(),
                    CgGraphicsPipelineDesc.Topology.TRIANGLES, CgGraphicsPipelineDesc.Raster.DEFAULT,
                    CgGraphicsPipelineDesc.DepthStencil.OFF,
                    List.of(new CgGraphicsPipelineDesc.ColorTarget(CgFormat.RGBA8_UNORM, null, 0xF)), null, 1)));
            CgGpuBuffer cornerBuffer = keep(made, rhi.createBuffer(new CgGpuBuffer.Desc("rhi-scene corners", 6 * 16,
                    CgGpuBuffer.Usage.ALL, false)));
            CgGpuBuffer colour = keep(made, rhi.createBuffer(new CgGpuBuffer.Desc("rhi-scene colour", 16,
                    CgGpuBuffer.Usage.ALL, true)));
            colour.mapped().order(ByteOrder.nativeOrder()).putFloat(0, R).putFloat(4, G).putFloat(8, B).putFloat(12, 1f);
            CgBindingSet colourSet = keep(made, rhi.createBindingSet("rhi-scene colour", material,
                    new CgBindings().buffer(0, CgBindingLayout.Type.UNIFORM_BUFFER, colour, 0, 16)));
            CgGpuTexture target = keep(made, rhi.createTexture(new CgGpuTexture.Desc("rhi-scene target",
                    CgGpuTexture.Kind.D2, CgFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1, 1, CgGpuTexture.Usage.ALL)));
            CgGpuReadback back = new CgGpuReadback(rhi, "rhi-scene pixels", SIZE * SIZE * 4);
            CgBindings cornerBindings = new CgBindings().buffer(0, CgBindingLayout.Type.STORAGE_BUFFER, cornerBuffer, 0, 6 * 16);

            CgCommandList cmd = rhi.encoder();
            int zone = CgGpuTrace.name("rhi-scene");
            CgGpuTrace.begin(cmd, zone);
            CgComputePass write = cmd.beginCompute("rhi-scene corners");
            write.setPipeline(kernel);
            write.pushBindings(cornerBindings);
            write.dispatch(6, 1, 1);
            write.end();
            cmd.transition(new CgTransition().buffer(cornerBuffer, CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ));
            CgRenderPass draw = cmd.beginPass(new CgPassDesc("rhi-scene", List.of(new CgPassDesc.Color(
                    CgTextureView.whole(target), CgPassDesc.LoadOp.CLEAR, 0, 0, 0, 1, CgPassDesc.StoreOp.STORE)), null, SIZE, SIZE));
            draw.setViewport(0, 0, SIZE, SIZE, 0, 1);
            draw.setScissor(0, 0, SIZE, SIZE);
            draw.setPipeline(square);
            draw.pushBindings(cornerBindings);
            draw.setBindingSet(1, colourSet);
            draw.pushConstants(0, 1);
            draw.draw(6, 1, 0, 0);
            draw.end();
            cmd.transition(new CgTransition().texture(CgTextureView.whole(target), CgAccess.COLOR_WRITE, CgAccess.COPY_READ));
            back.enqueue(cmd, target, CgTextureRegion.of2D(0, 0, 0, SIZE, SIZE));
            CgGpuTrace.end(cmd);

            // Frames end, never waited on, until the copy has landed: a few, as the frames in flight retire.
            for (int i = 0; i < 8 && !back.ready(); i++) rhi.endFrame();
            if (!back.ready()) throw new IllegalStateException("the readback did not land within 8 frames");
            ByteBuffer bytes = back.data();
            int[] pixels = new int[SIZE * SIZE];
            for (int i = 0; i < pixels.length; i++) {
                int r = bytes.get(i * 4) & 0xFF, g = bytes.get(i * 4 + 1) & 0xFF, b = bytes.get(i * 4 + 2) & 0xFF;
                int a = bytes.get(i * 4 + 3) & 0xFF;
                pixels[i] = a << 24 | r << 16 | g << 8 | b;
            }
            back.release();
            return pixels;
        } finally {
            for (CgRHIResource o : made) rhi.release(o);
            CgDeviceSection.end();
        }
    }

    private static <T extends CgRHIResource> T keep(List<CgRHIResource> made, T object) {
        made.add(object);
        return object;
    }

    private static CgShaderModule module(CgRHI rhi, List<CgRHIResource> made, CgShaderModule.Stage stage, ByteBuffer spirv) {
        return keep(made, rhi.createShaderModule(stage, spirv, "rhi-scene"));
    }

    private static void save(HarnessContext ctx, int[] pixels, String name) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) image.setRGB(x, y, pixels[y * SIZE + x]);
        }
        try {
            ImageIO.write(image, "png", new File(ctx.getOutputDir(), name));
        } catch (IOException e) {
            System.out.println("[rhi-scene] the picture could not be written: " + e);
        }
    }

    @Override
    public void dispose() {
    }
}
