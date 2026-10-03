package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.cpu.CgCpuBuffer;
import com.crystalgraphics.compute.cpu.CgCpuImage;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgChunkBuilder;
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
import com.crystalgraphics.render.graph.CgTextureDesc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * gpu-compute C3's gate: a chain of compute and raster passes in one frame graph, recorded and built on a worker
 * thread, every barrier derived by the executor. Kernels seed a history and step it twice, write an indirect
 * dispatch's group counts, and paint an image from the history's newest two versions through that dispatch; a raster
 * pass samples the image. {@code compute-graph.png} must match {@code compute-graph-ref.png}, the same raster pass
 * over the expected pixels written on the CPU, and so must {@code compute-graph-again.png}, the frame executed again:
 * the steps skipped, the arguments and the image made again.
 *
 * <p>Prints {@code [compute-graph] PASS} or {@code FAIL}; on Vulkan a validation error fails it. With
 * {@code -Dcrystalgraphics.graph.barriers=false} and {@code -Dcrystalgraphics.vulkan.syncValidation=true} the
 * synchronization checks must report hazards: the proof the clean run's barriers were the executor's.</p>
 */
public class CgComputeGraphTestScene implements HarnessSceneLifecycle {

    private static final int SIZE = 64, ORIGIN = 32;

    private CgMaterial material;
    private CgCompute kernels;

    @Override
    public void init(HarnessContext ctx) {
        material = CgMaterial.load("assets/harness/shader/quad_renderer_test.shader");
        material.applyProperties(b -> b.sampler("_MainTex", 0, CgFallbackTextures.WHITE_1x1));
        kernels = CgCompute.load("harness:shaders/compute_graph.compute");
        giveBodies();
    }

    /** The kernels' Java bodies: what the CPU tier runs. */
    private void giveBodies() {
        kernels.kernel("Args").cpu(d -> {
            CgCpuBuffer args = d.buffer("ARGS");
            args.setInt(0, 0, 8);
            args.setInt(0, 1, 8);
            args.setInt(0, 2, 1);
            args.setInt(0, 3, 0);
        });
        kernels.kernel("Seed").cpu(d -> {
            CgCpuBuffer out = d.buffer("OUT");
            for (int e = d.first(); e < d.end(); e++) {
                out.setFloat(e, 0, d.x(e) * 4);
                out.setFloat(e, 1, d.y(e) * 4);
                out.setFloat(e, 2, 0f);
                out.setFloat(e, 3, 255f);
            }
        });
        kernels.kernel("Step").cpu(d -> {
            CgCpuBuffer in = d.buffer("IN"), out = d.buffer("OUT");
            for (int e = d.first(); e < d.end(); e++) {
                for (int c = 0; c < 4; c++) out.setFloat(e, c, in.getFloat(e, c) + (c == 2 ? 32f : 0f));
            }
        });
        kernels.kernel("Paint").cpu(d -> {
            CgCpuBuffer in = d.buffer("IN"), before = d.buffer("BEFORE");
            CgCpuImage picture = d.image("PICTURE");
            for (int e = d.first(); e < d.end(); e++) {
                int x = d.x(e), y = d.y(e), i = x + 64 * y;
                picture.store(x, y, 0, in.getFloat(i, 0) / 255f, in.getFloat(i, 1) / 255f,
                        (in.getFloat(i, 2) + before.getFloat(i, 2)) / 255f, in.getFloat(i, 3) / 255f);
            }
        });
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        CgGraphBuffer state = CgGraphBuffer.history("state", CgBufferDesc.elements(SIZE * SIZE, 16, CgBufferUsage.STORAGE));
        CgFrameBuilder builder = new CgFrameBuilder();
        CgFrame built;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            built = worker.submit(() -> builder.build(new CgFrameGraph().add(record(state, w, h).seal()))).get();
        } catch (Exception e) {
            throw new IllegalStateException("recording or building on the worker failed", e);
        } finally {
            worker.shutdownNow();
        }

        HarnessFboHelper target = HarnessFboHelper.create(w, h, false);
        target.bind();
        target.clear(0.08f, 0.08f, 0.1f, 1f);
        CgExecutor.execute(built);
        target.captureToFile(ctx.getOutputDir(), "compute-graph.png");
        target.clear(0.08f, 0.08f, 0.1f, 1f);
        CgExecutor.executeAgain(built, false);
        target.captureToFile(ctx.getOutputDir(), "compute-graph-again.png");
        builder.recycle(built);

        CgRecording release = new CgRecording();
        release.release(state);
        CgImmediate.execute(release);

        target.clear(0.08f, 0.08f, 0.1f, 1f);
        CgTexture2D reference = reference();
        CgRecording rec = new CgRecording();
        show(rec, reference, w, h);
        CgImmediate.execute(rec);
        target.captureToFile(ctx.getOutputDir(), "compute-graph-ref.png");
        reference.delete();
        target.unbind();
        target.delete();

        compare(ctx, GlErrorChecker.checkAndLog("compute-graph"));
    }

    /** The frame: arguments, a seeded history stepped twice, an image painted from it, the image drawn. */
    private CgRecording record(CgGraphBuffer state, int w, int h) {
        CgGraphBuffer args = CgGraphBuffer.transientBuffer("args",
                CgBufferDesc.of(16, CgBufferUsage.STORAGE, CgBufferUsage.INDIRECT));
        CgGraphTexture picture = CgGraphTexture.transientTexture("picture", new CgTextureDesc(SIZE, SIZE, CgTextureDesc.RGBA8));
        CgRecording rec = new CgRecording();

        CgComputePass sizes = rec.compute("args");
        sizes.dispatch(kernels.kernel("Args"), 1).bind("ARGS", args);
        sizes.end();

        CgComputePass seed = rec.compute("seed");
        seed.dispatch(kernels.kernel("Seed"), SIZE, SIZE, 1).bind("OUT", state);
        seed.end();

        CgComputePass simulate = rec.compute("simulate");
        for (int i = 0; i < 2; i++) {
            simulate.dispatch(kernels.kernel("Step"), SIZE, SIZE, 1).bind("IN", state).bind("OUT", state);
        }
        simulate.end();

        CgComputePass paint = rec.compute("paint");
        paint.dispatchIndirect(kernels.kernel("Paint"), args, 0)
                .bind("IN", state)
                .bind("BEFORE", state.previous())
                .image("PICTURE", picture);
        paint.end();

        show(rec, picture, w, h);
        return rec;
    }

    /** One quad sampling {@code texture} texel for pixel, into whatever is bound. */
    private void show(CgRecording rec, CgTexture texture, int w, int h) {
        CgPassConstants constants = new CgPassConstants().resolution(w, h);
        constants.projection.setOrtho(0, w, h, 0, -1, 1);
        CgRasterPass pass = rec.raster(CgGraphTexture.current(), CgLoad.load(), constants, null, CgOrder.LOOKBACK);
        int bindings = rec.bindings().withTexture(material.captureBindings(rec.bindings()), 0, texture);
        CgChunkBuilder c = rec.chunks().begin();
        c.draw(material.pipeline(CgInstanceKind.QUAD), bindings);
        int at = c.instance();
        float[] r = c.data();
        r[at] = ORIGIN;
        r[at + 1] = ORIGIN;
        r[at + 4] = SIZE;
        r[at + 9] = SIZE;
        r[at + 14] = 1f;
        r[at + 15] = 1f;
        r[at + 16] = r[at + 17] = r[at + 18] = r[at + 19] = 1f;
        c.bounds(ORIGIN, ORIGIN, ORIGIN + SIZE, ORIGIN + SIZE);
        pass.add(c.end());
        pass.end();
    }

    /** What the kernels must paint: red and green from the texel, blue 64 and 32 from the two versions. */
    private static CgTexture2D reference() {
        ByteBuffer pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) pixels.put((byte) (x * 4)).put((byte) (y * 4)).put((byte) 96).put((byte) 255);
        }
        pixels.flip();
        return CgTexture2D.createFromPixels(SIZE, SIZE, pixels, CgTextureSpec.RGBA8_NEAREST);
    }

    private static void compare(HarnessContext ctx, boolean glErrors) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) {
            System.out.println("[compute-graph] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        if (validation > 0) {
            System.out.println("[compute-graph] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
            return;
        }
        try {
            BufferedImage ref = ImageIO.read(new File(ctx.getOutputDir(), "compute-graph-ref.png"));
            boolean pass = true;
            for (String capture : List.of("compute-graph.png", "compute-graph-again.png")) {
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
                    System.out.println("[compute-graph] FAIL on " + on + ": " + capture + " differs in " + differing
                            + " pixels, the first at (" + firstX + ", " + firstY + "): 0x"
                            + Integer.toHexString(made.getRGB(firstX, firstY)) + " where the reference has 0x"
                            + Integer.toHexString(ref.getRGB(firstX, firstY)));
                }
            }
            if (pass) System.out.println("[compute-graph] PASS on " + on + ": the graph's picture, and again, match the reference");
        } catch (IOException e) {
            System.out.println("[compute-graph] FAIL: the captures could not be read: " + e);
        }
    }

    @Override
    public void dispose() {
    }
}
