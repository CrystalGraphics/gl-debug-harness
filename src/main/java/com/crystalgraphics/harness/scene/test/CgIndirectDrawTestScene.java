package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.graph.CgFrameGraph;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * gpu-compute C4's gate: a kernel writes four counts, and four indirect draws read them in the frame that wrote them,
 * one per {@link CgIndirect} mode and one whose count is past its mesh. Each is drawn on the left, and on the right a
 * direct draw of what the count means, worked out on the CPU: the halves must match, and each band must hold the cells
 * its count says.
 *
 * <ul>
 *   <li>{@code indirect-draw.png}: chunk draws in a frame graph, the kernel in a compute pass of the same graph.</li>
 *   <li>{@code indirect-draw-again.png}: that frame executed again, the commands written again.</li>
 *   <li>{@code indirect-draw-world.png}: {@code CgWorldRenderer} draws, the kernel recorded into the opaque world
 *       stage's frame by a renderer ahead of the world renderer's.</li>
 * </ul>
 *
 * <p>Prints {@code [indirect-draw] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgIndirectDrawTestScene implements HarnessSceneLifecycle {

    private static final int HALF = 160;
    private static final int BACKGROUND_R = 20, BACKGROUND_G = 20, BACKGROUND_B = 26;

    /** One indirect draw and its direct twin: where it is, how a cell is chosen, its count and what that means. */
    private record Band(float x, float y, int by, int rgb, CgMesh mesh, CgIndirect mode, int factor, int uint,
                        CgMesh reference, int referenceCount, int cells) {

        /** How the twin chooses a cell: drawn direct, an instance's cell is a quad's. */
        int referenceBy() {
            return by == 1 ? 0 : by;
        }
    }

    private static final List<Band> BANDS = List.of(
            new Band(8, 8, 0, 0xFF3030, CgMesh.quads(64), CgIndirect.INDICES, 6, 0, CgMesh.quads(64), 37 * 6, 37),
            new Band(88, 8, 1, 0x30FF30, CgMesh.quads(1), CgIndirect.INSTANCES, 2, 1, CgMesh.quads(64), 10 * 6, 10),
            new Band(8, 88, 2, 0x3060FF, CgMesh.vertices(192, CgMeshTopology.TRIANGLES), CgIndirect.VERTICES, 3, 2,
                    CgMesh.vertices(192, CgMeshTopology.TRIANGLES), 27, 9),
            new Band(88, 88, 0, 0xFFE030, CgMesh.quads(64), CgIndirect.INDICES, 6, 3, CgMesh.quads(64), 64 * 6, 64));

    private CgMaterial material;
    private CgCompute kernels;

    @Override
    public void init(HarnessContext ctx) {
        material = CgMaterial.load("assets/harness/shader/indirect_draw_test.shader");
        kernels = CgCompute.load("harness:shaders/indirect_draw.compute");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (!CgCapabilities.detect().compute() || !CgCapabilities.detect().drawIndirect()) {
            System.out.println("[indirect-draw] SKIP: this context has no compute shaders or indirect draws");
            return;
        }
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        CgBufferDesc desc = CgBufferDesc.of(16, CgBufferUsage.STORAGE);
        CgGraphBuffer counts = CgGraphBuffer.persistent("counts", desc);
        CgGraphBuffer worldCounts = CgGraphBuffer.persistent("world-counts", desc);
        Matrix4f projection = new Matrix4f().setOrtho(0, w, h, 0, -1, 1);

        HarnessFboHelper target = HarnessFboHelper.create(w, h, false);
        target.bind();
        target.clear(BACKGROUND_R / 255f, BACKGROUND_G / 255f, BACKGROUND_B / 255f, 1f);
        CgFrameBuilder builder = new CgFrameBuilder();
        CgFrame built = builder.build(new CgFrameGraph().add(record(counts, w, h, projection).seal()));
        CgExecutor.execute(built);
        target.captureToFile(ctx.getOutputDir(), "indirect-draw.png");
        target.clear(BACKGROUND_R / 255f, BACKGROUND_G / 255f, BACKGROUND_B / 255f, 1f);
        CgExecutor.executeAgain(built, false);
        target.captureToFile(ctx.getOutputDir(), "indirect-draw-again.png");
        builder.recycle(built);

        target.clear(BACKGROUND_R / 255f, BACKGROUND_G / 255f, BACKGROUND_B / 255f, 1f);
        drawWorld(worldCounts, target, w, h, projection);
        target.captureToFile(ctx.getOutputDir(), "indirect-draw-world.png");

        CgRecording release = new CgRecording();
        release.release(counts);
        release.release(worldCounts);
        CgImmediate.execute(release);
        target.unbind();
        target.delete();

        compare(ctx, GlErrorChecker.checkAndLog("indirect-draw"));
    }

    /** The counts written, then every band's indirect draw on the left and its direct twin on the right. */
    private CgRecording record(CgGraphBuffer counts, int w, int h, Matrix4f projection) {
        CgRecording rec = new CgRecording();
        CgComputePass count = rec.compute("counts");
        count.dispatch(kernels.kernel("Count"), 1).bind("COUNTS", counts);
        count.end();

        CgPassConstants constants = new CgPassConstants().resolution(w, h);
        constants.projection.set(projection);
        CgRasterPass pass = rec.raster(CgGraphTexture.current(), CgLoad.load(), constants, null, CgOrder.LOOKBACK);
        CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
        int bindings = material.captureBindings(rec.bindings());
        CgChunkBuilder c = rec.chunks().begin();
        for (Band band : BANDS) {
            c.draw(pipeline, bindings, band.mesh()).indirect(counts, band.uint() * 4L, band.mode(), band.factor());
            record(c, band, 0, band.by());
            c.draw(pipeline, bindings, band.reference()).range(0, 0, band.referenceCount());
            record(c, band, HALF, band.referenceBy());
        }
        pass.add(c.end());
        pass.end();
        return rec;
    }

    /** A draw's one record: the identity, the band's origin and how it picks a cell, its colour. */
    private static void record(CgChunkBuilder c, Band band, float dx, int by) {
        int at = c.instance();
        float[] r = c.data();
        r[at] = r[at + 5] = r[at + 10] = r[at + 15] = 1f;
        r[at + 32] = band.x() + dx;
        r[at + 33] = band.y();
        r[at + 34] = by;
        r[at + 36] = (band.rgb() >> 16 & 0xFF) / 255f;
        r[at + 37] = (band.rgb() >> 8 & 0xFF) / 255f;
        r[at + 38] = (band.rgb() & 0xFF) / 255f;
        r[at + 39] = 1f;
    }

    /** The same draws through the world renderer, the counts written in the opaque stage's own frame. */
    private void drawWorld(CgGraphBuffer counts, HarnessFboHelper target, int w, int h, Matrix4f projection) {
        CgWorldRenderer world = CgWorldRenderer.get();
        world.install();
        CgRenderStage.Registration counting = CgRenderStage.WORLD_OPAQUE.register(0, stage -> {
            CgComputePass count = stage.recording().compute("counts");
            count.dispatch(kernels.kernel("Count"), 1).bind("COUNTS", counts);
            count.end();
        });
        for (Band band : BANDS) {
            float r = (band.rgb() >> 16 & 0xFF) / 255f, g = (band.rgb() >> 8 & 0xFF) / 255f, b = (band.rgb() & 0xFF) / 255f;
            world.draw(band.mesh(), material).indirect(counts, band.uint() * 4L, band.mode(), band.factor())
                    .custom(0, band.x(), band.y(), band.by(), 0f).custom(1, r, g, b, 1f).submit();
            world.draw(band.reference(), material).indices(0, band.referenceCount())
                    .custom(0, band.x() + HALF, band.y(), band.referenceBy(), 0f).custom(1, r, g, b, 1f).submit();
        }
        Matrix4f view = new Matrix4f();
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, w, h, target.getFboId()).view().set(0, 0, 0, view, projection);
            stage.fire();
        }
        counting.close();
    }

    private static void compare(HarnessContext ctx, boolean glErrors) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) {
            System.out.println("[indirect-draw] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        if (validation > 0) {
            System.out.println("[indirect-draw] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
            return;
        }
        if ("recording".equals(on)) {
            System.out.println("[indirect-draw] PASS on recording: every command validated; a recording device draws nothing to compare");
            return;
        }
        boolean pass = true;
        for (String capture : List.of("indirect-draw.png", "indirect-draw-again.png", "indirect-draw-world.png")) {
            try {
                String failure = check(ImageIO.read(new File(ctx.getOutputDir(), capture)));
                if (failure != null) {
                    pass = false;
                    System.out.println("[indirect-draw] FAIL on " + on + ": " + capture + " " + failure);
                }
            } catch (IOException e) {
                pass = false;
                System.out.println("[indirect-draw] FAIL: " + capture + " could not be read: " + e);
            }
        }
        if (pass) {
            System.out.println("[indirect-draw] PASS on " + on + ": every indirect draw matches its direct twin, "
                    + "in a graph, executed again, and in the world");
        }
    }

    /** Null when the left half is the right one and each band holds its cells; else what is wrong. */
    private static String check(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < HALF; x++) {
                if (image.getRGB(x, y) != image.getRGB(x + HALF, y)) {
                    return "differs from its direct twin first at (" + x + ", " + y + "): 0x"
                            + Integer.toHexString(image.getRGB(x, y)) + " where the twin has 0x"
                            + Integer.toHexString(image.getRGB(x + HALF, y));
                }
            }
        }
        for (Band band : BANDS) {
            int filled = 0;
            for (int cell = 0; cell < 64; cell++) {
                int x = (int) band.x() + (cell % 8) * 8 + 2, y = (int) band.y() + (cell / 8) * 8 + 2;
                if ((image.getRGB(x, y) & 0xFFFFFF) == band.rgb()) filled++;
            }
            if (filled != band.cells()) {
                return band.mode() + " x " + band.factor() + " drew " + filled + " cells where its count means "
                        + band.cells();
            }
        }
        return null;
    }

    @Override
    public void dispose() {
    }
}
