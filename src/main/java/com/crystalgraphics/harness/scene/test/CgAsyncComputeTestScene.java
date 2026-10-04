package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.compute.ops.CgRng;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
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
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The gate for async compute (gpu-compute C8): a frame of blurs in a compute pass, a fill-bound raster pass that
 * touches none of it, and a pass reading the blur. Timed four ways, back to back in every frame for 100 frames: the
 * compute alone, the drawing alone, both in order, and both with the compute pass {@code async()}. Then one frame in
 * order and one async, every output read back and compared byte for byte.
 *
 * <pre>
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=async-compute --device=vulkan" -Dcrystalgraphics.vulkan.syncValidation=true
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=async-compute --device=vulkan" -Dcrystalgraphics.vulkan.asyncCompute=graphics
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=async-compute"
 * </pre>
 *
 * <ul>
 *   <li>PASS needs identical outputs everywhere, and where the device has a compute queue, the async frame faster than
 *       the in-order one by a quarter of the shorter half at least: the drawing ran beside the compute.</li>
 *   <li>{@code -Dcrystalgraphics.harness.async.blurs} and {@code .quads} size the two halves (8 and 48).</li>
 * </ul>
 */
public class CgAsyncComputeTestScene implements InteractiveSceneLifecycle {

    private static final int WARMUP = 10, MEASURED = 100, DRAIN = 30;
    private static final int W = 1920, H = 1080;

    /** What a timed frame holds, and its zone. */
    private enum Mode {
        COMPUTE("compute alone", true, false, false),
        DRAW("drawing alone", false, true, false),
        IN_ORDER("in order", true, true, false),
        ASYNC("async", true, true, true);

        final String zone;
        final boolean compute, draw, async;

        Mode(String zone, boolean compute, boolean draw, boolean async) {
            this.zone = zone;
            this.compute = compute;
            this.draw = draw;
            this.async = async;
        }
    }

    private static final Mode[] MODES = Mode.values();
    private static final CgFrameBufferFormat HALF = CgFrameBufferFormat.builder("async-compute")
            .color(0, CgTextureType.RGBA16F).build();

    private final CgFrameBuilder builder = new CgFrameBuilder();
    private final CgFrameGraph graph = new CgFrameGraph();
    private final CgRecording recording = new CgRecording();
    private final List<CgFrameBuffer> owned = new ArrayList<>();
    private CgMaterial pattern, down;
    private CgGraphTexture source, blurred, canvas, result;
    private CgGraphBuffer word;
    private int blurs, quads, frame, wordId;
    private boolean wasTracingGpu, running = true;
    private String mismatch;

    @Override
    public void init(HarnessContext ctx) {
        blurs = Integer.getInteger("crystalgraphics.harness.async.blurs", 8);
        quads = Integer.getInteger("crystalgraphics.harness.async.quads", 48);
        pattern = CgMaterial.newInstance("assets/harness/shader/raster_levels_test.shader");
        down = CgMaterial.newInstance("assets/harness/shader/raster_levels_test.shader");
        down.enableKeyword("DOWN");
        source = texture("source", W, H, true);
        blurred = texture("blurred", W, H, false);
        canvas = texture("canvas", W, H, false);
        result = texture("result", W / 2, H / 2, false);
        wordId = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, wordId);
        CgGL.glBufferData(CgGL.GL_COPY_WRITE_BUFFER, 4L, CgGL.GL_DYNAMIC_DRAW);
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, 0);
        word = CgGraphBuffer.imported("word", wordId, 4);

        wasTracingGpu = CgTrace.isEnabled(CgGpuTrace.GPU);
        CgTrace.setEnabled(CgGpuTrace.GPU, true);
        CgGpuTrace.resetTotals();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        CgGpuTrace.collect();
        int timed = WARMUP + MEASURED;
        if (frame < timed) {
            execute(null, false, false, false, false);   // untimed: an earlier frame's wait lands here
            // Back to back, so the GPU never idles between them, in an order turning each frame.
            for (int k = 0; k < MODES.length; k++) {
                Mode mode = MODES[(frame + k) % MODES.length];
                execute(frame >= WARMUP ? mode.zone : null, mode.compute, mode.draw, mode.compute && mode.draw,
                        mode.async);
            }
        } else if (frame == timed) {
            execute(null, true, true, true, false);
            List<ByteBuffer> inOrder = readAll();
            execute(null, true, true, true, true);
            mismatch = compare(inOrder, readAll());
        } else if (frame >= timed + DRAIN || everyQueryLanded()) {
            report();
            running = false;
        }
        frame++;
    }

    /** One graph: the blurs, the drawing and the pass reading the blur, as asked; timed in {@code zone} when given. */
    private void execute(String zone, boolean compute, boolean draw, boolean consume, boolean async) {
        recording.reset();
        if (compute) {
            CgComputePass blur = recording.compute("async-compute blurs");
            if (async) blur.async();
            for (int i = 0; i < blurs; i++) CgGpuOps.blur(blur, source, 0, blurred, 0, 6f);
            blur.end();
        }
        if (draw) {
            CgRasterPass pass = recording.raster(canvas, CgLoad.clear(0, 0, 0, 0), new CgPassConstants().resolution(W, H),
                    null, CgOrder.LOOKBACK);
            CgChunkBuilder c = recording.chunks().begin();
            c.draw(pattern.pipeline(CgInstanceKind.OBJECT), pattern.captureBindings(recording.bindings()), CgMesh.quads(1));
            for (int q = 0; q < quads; q++) c.instance();
            pass.add(c.end());
            pass.end();
        }
        if (consume) {
            CgRasterPass pass = recording.raster(result, CgLoad.clear(0, 0, 0, 0),
                    new CgPassConstants().resolution(W / 2, H / 2), null, CgOrder.LOOKBACK);
            int bindings = recording.bindings().withTexture(down.captureBindings(recording.bindings()), 0, blurred);
            CgChunkBuilder c = recording.chunks().begin();
            c.draw(down.pipeline(CgInstanceKind.OBJECT), bindings, CgMesh.quads(1));
            c.instance();
            pass.add(c.end());
            pass.end();
        }
        if (!compute && !draw) {
            CgComputePass opener = recording.compute("async-compute opener");
            CgGpuOps.fill(opener, word, 0, CgGpuCount.of(1));
            opener.end();
        }
        CgFrame built = builder.build(graph.add(recording.seal()));
        graph.clear();
        if (zone != null) CgGpuTrace.begin(zone);
        CgExecutor.execute(built, true);
        if (zone != null) CgGpuTrace.end();
        builder.recycle(built);
    }

    private List<ByteBuffer> readAll() {
        List<ByteBuffer> out = new ArrayList<>();
        for (CgGraphTexture t : new CgGraphTexture[] {blurred, canvas, result}) {
            CgFrameBuffer fb = t.framebuffer();
            ByteBuffer pixels = ByteBuffer.allocateDirect(fb.getWidth() * fb.getHeight() * 8).order(ByteOrder.nativeOrder());
            CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, fb.getColorTexture(0).getId());
            CgGL.glGetTexImage(CgGL.GL_TEXTURE_2D, 0, CgGL.GL_RGBA, CgGL.GL_HALF_FLOAT, pixels);
            CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, 0);
            out.add(pixels);
        }
        return out;
    }

    private static String compare(List<ByteBuffer> inOrder, List<ByteBuffer> async) {
        String[] names = {"blurred", "canvas", "result"};
        for (int i = 0; i < names.length; i++) {
            int at = inOrder.get(i).mismatch(async.get(i));
            if (at >= 0) return names[i] + " differs at byte " + at + " between the in-order and the async frame";
        }
        return null;
    }

    private static boolean everyQueryLanded() {
        Map<String, long[]> totals = CgGpuTrace.totals();
        for (Mode mode : MODES) {
            long[] t = totals.get(mode.zone);
            if (t == null || t[1] < MEASURED) return false;
        }
        return true;
    }

    private void report() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? CgGL.glGetString(CgGL.GL_RENDERER) : device.name();
        boolean queue = CgCapabilities.detect().asyncCompute();
        Map<String, long[]> totals = CgGpuTrace.totals();
        double[] ms = new double[MODES.length];
        for (int i = 0; i < MODES.length; i++) {
            long[] t = totals.get(MODES[i].zone);
            ms[i] = t == null || t[1] == 0 ? Double.NaN : t[0] / 1e6 / t[1];
            System.out.printf("[async-compute]   %-14s gpu %7.3f ms%n", MODES[i].zone, ms[i]);
        }
        double shorter = Math.min(ms[0], ms[1]), saved = ms[2] - ms[3];
        System.out.printf("[async-compute]   async saves %.3f ms of the %.3f ms the shorter half takes (%.0f%%)%n",
                saved, shorter, 100 * saved / shorter);
        int validation = PlatformServiceHarness.validationErrors();
        if (GlErrorChecker.checkAndLog("async-compute")) {
            System.out.println("[async-compute] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[async-compute] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (mismatch != null) {
            System.out.println("[async-compute] FAIL on " + on + ": " + mismatch);
        } else if (!queue) {
            System.out.println("[async-compute] PASS on " + on + ": no compute queue, async ran in order with the same output");
        } else if (Double.isNaN(saved) || saved < shorter / 4) {
            System.out.println("[async-compute] FAIL on " + on + ": the async frame did not overlap the drawing");
        } else {
            System.out.println("[async-compute] PASS on " + on + ": the drawing ran beside the compute, the same output");
        }
    }

    private CgGraphTexture texture(String name, int w, int h, boolean noise) {
        CgFrameBuffer fb = CgFrameBuffer.createOwned("async-compute " + name, w, h, HALF, 1);
        if (noise) {
            ByteBuffer pixels = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder());
            for (int i = 0; i < w * h * 4; i++) pixels.putFloat((CgRng.rng(w, i, 3, 0) >>> 8) / (float) (1 << 24));
            pixels.flip();
            CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, fb.getColorTexture(0).getId());
            CgGL.glTexSubImage2D(CgGL.GL_TEXTURE_2D, 0, 0, 0, w, h, CgGL.GL_RGBA, CgGL.GL_FLOAT, pixels);
            CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, 0);
        }
        owned.add(fb);
        return CgGraphTexture.imported(name, fb);
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return true; }

    @Override
    public void dispose() {
        CgTrace.setEnabled(CgGpuTrace.GPU, wasTracingGpu);
        CgGL.glDeleteBuffers(wordId);
        for (CgFrameBuffer fb : owned) fb.delete();
    }
}
