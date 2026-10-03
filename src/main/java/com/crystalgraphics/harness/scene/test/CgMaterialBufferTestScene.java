package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.cpu.CgCpuBuffer;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
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
 * The gate for a material reading a kernel's buffers: a kernel writes a record per cell of an 8 by 8 grid and whether
 * it is live, the live cells are compacted on the GPU, and one indirect draw places a quad per live cell from the
 * records, coloured from them in the fragment stage. Every cell is checked against Java's answer.
 *
 * <ul>
 *   <li>{@code material-buffer.png}: one frame graph, the buffers transient.</li>
 *   <li>{@code material-buffer-world.png}: {@code CgWorldRenderer}, the kernel recorded ahead of it in the opaque
 *       stage, the buffers persistent.</li>
 * </ul>
 *
 * <p>Prints {@code [material-buffer] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgMaterialBufferTestScene implements HarnessSceneLifecycle {

    private static final int CELLS = 64;
    private static final int ORIGIN = 16;
    private static final int BACKGROUND_R = 20, BACKGROUND_G = 20, BACKGROUND_B = 26;

    private CgMaterial material;
    private CgCompute kernels;

    @Override
    public void init(HarnessContext ctx) {
        material = CgMaterial.load("assets/harness/shader/material_buffer_test.shader");
        kernels = CgCompute.load("harness:shaders/material_buffer.compute");
        kernels.kernel("Cells").cpu(d -> {                      // the CPU tier's
            CgCpuBuffer cells = d.buffer("CELLS"), live = d.buffer("LIVE");
            int rect = cells.field("rect").word(), color = cells.field("color").word();
            for (int e = d.first(); e < d.end(); e++) {
                cells.setFloat(e, rect, e % 8 * 8 + 1);
                cells.setFloat(e, rect + 1, e / 8 * 8 + 1);
                cells.setFloat(e, rect + 2, 6);
                cells.setFloat(e, rect + 3, 6);
                int rgb = rgb(e);
                cells.setFloat(e, color, (rgb >> 16 & 0xFF) / 255f);
                cells.setFloat(e, color + 1, (rgb >> 8 & 0xFF) / 255f);
                cells.setFloat(e, color + 2, (rgb & 0xFF) / 255f);
                cells.setFloat(e, color + 3, 1f);
                live.setInt(e, live(e) ? 1 : 0);
            }
        });
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        Matrix4f projection = new Matrix4f().setOrtho(0, w, h, 0, -1, 1);
        HarnessFboHelper target = HarnessFboHelper.create(w, h, false);
        target.bind();

        target.clear(BACKGROUND_R / 255f, BACKGROUND_G / 255f, BACKGROUND_B / 255f, 1f);
        CgFrameBuilder builder = new CgFrameBuilder();
        CgFrame built = builder.build(new CgFrameGraph().add(record(w, h, projection).seal()));
        CgExecutor.execute(built);
        builder.recycle(built);
        target.captureToFile(ctx.getOutputDir(), "material-buffer.png");

        target.clear(BACKGROUND_R / 255f, BACKGROUND_G / 255f, BACKGROUND_B / 255f, 1f);
        Buffers world = new Buffers(CgGraphBuffer::persistent);
        drawWorld(world, target, w, h, projection);
        target.captureToFile(ctx.getOutputDir(), "material-buffer-world.png");

        CgRecording release = new CgRecording();
        release.release(world.cells);
        release.release(world.flags);
        release.release(world.live);
        release.release(world.count);
        CgImmediate.execute(release);
        target.unbind();
        target.delete();

        compare(ctx, GlErrorChecker.checkAndLog("material-buffer"));
    }

    /** What the kernel writes, what compaction makes of it, and the count the draw takes. */
    private record Buffers(CgGraphBuffer cells, CgGraphBuffer flags, CgGraphBuffer live, CgGraphBuffer count) {
        Buffers(Storage storage) {
            this(storage.make("cells", CgBufferDesc.elements(CELLS, 32, CgBufferUsage.STORAGE)),
                    storage.make("flags", CgBufferDesc.elements(CELLS, 4, CgBufferUsage.STORAGE)),
                    storage.make("live", CgBufferDesc.elements(CELLS, 4, CgBufferUsage.STORAGE)),
                    storage.make("count", CgBufferDesc.of(16, CgBufferUsage.STORAGE)));
        }
    }

    private interface Storage {
        CgGraphBuffer make(String name, CgBufferDesc desc);
    }

    private void cells(CgComputePass pass, Buffers b) {
        pass.dispatch(kernels.kernel("Cells"), CELLS).bind("CELLS", b.cells()).bind("LIVE", b.flags());
        CgGpuOps.compact(pass, b.flags(), null, CgGpuCount.of(CELLS), b.live(), b.count(), 0);
    }

    private CgRecording record(int w, int h, Matrix4f projection) {
        CgRecording rec = new CgRecording();
        Buffers b = new Buffers(CgGraphBuffer::transientBuffer);
        CgComputePass compute = rec.compute("cells");
        cells(compute, b);
        compute.end();

        material.buffer("CELLS", b.cells()).buffer("LIVE", b.live());
        CgPassConstants constants = new CgPassConstants().resolution(w, h);
        constants.projection.set(projection);
        CgRasterPass pass = rec.raster(CgGraphTexture.current(), CgLoad.load(), constants, null, CgOrder.LOOKBACK);
        CgChunkBuilder c = rec.chunks().begin();
        c.draw(material.pipeline(CgInstanceKind.OBJECT), material.captureBindings(rec.bindings()), CgMesh.quads(CELLS))
                .indirect(b.count(), 0, CgIndirect.INDICES, 6);
        int at = c.instance();
        float[] r = c.data();
        r[at] = r[at + 5] = r[at + 10] = r[at + 15] = 1f;
        r[at + 32] = ORIGIN;
        r[at + 33] = ORIGIN;
        pass.add(c.end());
        pass.end();
        return rec;
    }

    private void drawWorld(Buffers b, HarnessFboHelper target, int w, int h, Matrix4f projection) {
        CgWorldRenderer world = CgWorldRenderer.get();
        world.install();
        CgRenderStage.Registration simulating = CgRenderStage.WORLD_OPAQUE.register(CgWorldRenderer.ORDER - 1, stage -> {
            CgComputePass compute = stage.recording().compute("cells");
            cells(compute, b);
            compute.end();
        });
        material.buffer("CELLS", b.cells()).buffer("LIVE", b.live());
        world.draw(CgMesh.quads(CELLS), material).indirect(b.count(), 0, CgIndirect.INDICES, 6)
                .custom(0, ORIGIN, ORIGIN, 0f, 0f).submit();
        Matrix4f view = new Matrix4f();
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, w, h, target.getFboId()).view().set(0, 0, 0, view, projection);
            stage.fire();
        }
        simulating.close();
    }

    private static boolean live(int cell) {
        return cell % 3 != 1;
    }

    private static int rgb(int cell) {
        return (cell * 4) << 16 | (255 - cell * 3) << 8 | (cell * 37) % 256;
    }

    private static void compare(HarnessContext ctx, boolean glErrors) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) {
            System.out.println("[material-buffer] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        if (validation > 0) {
            System.out.println("[material-buffer] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
            return;
        }
        if ("recording".equals(on)) {
            System.out.println("[material-buffer] PASS on recording: every command validated; a recording device draws nothing to compare");
            return;
        }
        boolean pass = true;
        for (String capture : List.of("material-buffer.png", "material-buffer-world.png")) {
            try {
                String failure = check(ImageIO.read(new File(ctx.getOutputDir(), capture)));
                if (failure != null) {
                    pass = false;
                    System.out.println("[material-buffer] FAIL on " + on + ": " + capture + " " + failure);
                }
            } catch (IOException e) {
                pass = false;
                System.out.println("[material-buffer] FAIL: " + capture + " could not be read: " + e);
            }
        }
        if (pass) {
            System.out.println("[material-buffer] PASS on " + on + ": every live cell placed and coloured from the "
                    + "kernel's records, in a graph and in the world");
        }
    }

    /** Null when each cell is its colour if live, the background if not, with the gap around it; else what is wrong. */
    private static String check(BufferedImage image) {
        int background = BACKGROUND_R << 16 | BACKGROUND_G << 8 | BACKGROUND_B;
        for (int cell = 0; cell < CELLS; cell++) {
            int x = ORIGIN + cell % 8 * 8, y = ORIGIN + cell / 8 * 8;
            int inside = image.getRGB(x + 4, y + 4) & 0xFFFFFF, gap = image.getRGB(x, y) & 0xFFFFFF;
            int expected = live(cell) ? rgb(cell) : background;
            if (inside != expected) {
                return "cell " + cell + " is 0x" + Integer.toHexString(inside) + " where it should be 0x"
                        + Integer.toHexString(expected);
            }
            if (gap != background) return "cell " + cell + "'s gap is 0x" + Integer.toHexString(gap) + ": a rect read wrong";
        }
        return null;
    }

    @Override
    public void dispose() {
    }
}
