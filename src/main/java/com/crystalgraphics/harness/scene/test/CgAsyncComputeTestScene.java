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
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The gate for async compute (gpu-compute C8): a frame of blurs in a compute pass, a fill-bound raster pass that
 * touches none of it, and a pass reading the blur. Timed five ways, back to back in every frame for 100 frames: the
 * compute alone, the drawing alone, both in order, both with the compute pass {@code async()}, and async with the
 * drawing recorded after the pass reading the blur, which the builder must move ahead of it. Then across executions, as
 * stages are: a sort of a persistent buffer in one, the drawing in the next, a reader of the sorted keys in a third,
 * timed alone, in order and async. Then one frame of each in order and async, every output read back and compared.
 *
 * <pre>
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=async-compute --device=vulkan" -Dcrystalgraphics.vulkan.syncValidation=true
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=async-compute --device=vulkan" -Dcrystalgraphics.vulkan.asyncCompute=graphics
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=async-compute"
 * </pre>
 *
 * <ul>
 *   <li>PASS needs identical outputs everywhere, and where the device has a compute queue, each async frame faster than
 *       the in-order one by a quarter of the shorter half at least: the drawing ran beside the compute.</li>
 *   <li>{@code -Dcrystalgraphics.harness.async.blurs} and {@code .quads} size the two halves (8 and 48), and
 *       {@code .sortKeys} the sort (4M).</li>
 * </ul>
 */
public class CgAsyncComputeTestScene implements InteractiveSceneLifecycle {

    private static final int WARMUP = 10, MEASURED = 100, DRAIN = 30;
    private static final int W = 1920, H = 1080;
    /** Knuth's multiplicative hash: odd, so index times it is a permutation of the 32-bit words. */
    private static final int SCRAMBLE = 0x9E3779B1;
    /** How many of the sorted keys the third execution reads. */
    private static final int FIRST = 1024;

    /** What a timed frame holds, and its zone. */
    private enum Mode {
        COMPUTE("compute alone"),
        DRAW("drawing alone"),
        IN_ORDER("in order"),
        ASYNC("async"),
        ASYNC_DRAWN_LAST("async, drawn last"),
        SORT("sort alone"),
        ACROSS_IN_ORDER("across, in order"),
        ACROSS_ASYNC("across, async");

        final String zone;

        Mode(String zone) {
            this.zone = zone;
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
    private CgGraphBuffer word, keys, first;
    private int blurs, quads, sortKeys, frame, wordId, firstId;
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
        sortKeys = Integer.getInteger("crystalgraphics.harness.async.sortKeys", 1 << 22);
        keys = CgGraphBuffer.persistent("async-compute keys",
                CgBufferDesc.elements(sortKeys, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        firstId = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, firstId);
        CgGL.glBufferData(CgGL.GL_COPY_WRITE_BUFFER, FIRST * 4L, CgGL.GL_DYNAMIC_DRAW);
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, 0);
        first = CgGraphBuffer.imported("first keys", firstId, FIRST * 4L);

        wasTracingGpu = CgTrace.isEnabled(CgGpuTrace.GPU);
        CgTrace.setEnabled(CgGpuTrace.GPU, true);
        CgGpuTrace.resetTotals();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        CgGpuTrace.collect();
        int timed = WARMUP + MEASURED;
        if (frame < timed) {
            open();   // untimed: an earlier frame's wait lands here
            // Back to back, so the GPU never idles between them, in an order turning each frame.
            for (int k = 0; k < MODES.length; k++) {
                Mode mode = MODES[(frame + k) % MODES.length];
                time(frame >= WARMUP ? mode.zone : null, mode);
            }
        } else if (frame == timed) {
            time(null, Mode.IN_ORDER);
            List<ByteBuffer> inOrder = readAll();
            time(null, Mode.ASYNC);
            mismatch = compare(inOrder, readAll(), Mode.ASYNC);
            time(null, Mode.ASYNC_DRAWN_LAST);
            if (mismatch == null) mismatch = compare(inOrder, readAll(), Mode.ASYNC_DRAWN_LAST);
            int[] expected = expectedFirst();
            for (Mode mode : new Mode[] {Mode.ACROSS_IN_ORDER, Mode.ACROSS_ASYNC}) {
                time(null, mode);
                if (mismatch == null && !Arrays.equals(readFirst(), expected)) {
                    mismatch = "'" + mode.zone + "' read sorted keys other than Java's";
                }
            }
        } else if (frame >= timed + DRAIN || everyQueryLanded()) {
            report();
            running = false;
        }
        frame++;
    }

    /** {@code mode}'s graphs, each executed as recorded; timed together in {@code zone} when given. */
    private void time(String zone, Mode mode) {
        if (zone != null) CgGpuTrace.begin(zone);
        switch (mode) {
            case COMPUTE -> blur(false);
            case DRAW -> draw();
            case IN_ORDER, ASYNC -> {
                blur(mode == Mode.ASYNC);
                draw();
                reader();
            }
            case ASYNC_DRAWN_LAST -> {
                blur(true);
                reader();
                draw();
            }
            case SORT -> sort(false);
            case ACROSS_IN_ORDER, ACROSS_ASYNC -> {
                sort(mode == Mode.ACROSS_ASYNC);
                run();   // as a stage would: the stages between, then the one reading what the sort made
                draw();
                run();
                CgComputePass read = recording.compute("async-compute first keys");
                CgGpuOps.copy(read, keys, first, CgGpuCount.of(FIRST));
                read.end();
            }
        }
        run();
        if (zone != null) CgGpuTrace.end();
    }

    /** A graph of one tiny kernel. */
    private void open() {
        CgComputePass opener = recording.compute("async-compute opener");
        CgGpuOps.fill(opener, word, 0, CgGpuCount.of(1));
        opener.end();
        run();
    }

    /** Builds and executes what is recorded, and opens the recording again. */
    private void run() {
        CgFrame built = builder.build(graph.add(recording.seal()));
        graph.clear();
        CgExecutor.execute(built, true);
        builder.recycle(built);
        recording.reset();
    }

    private void blur(boolean async) {
        CgComputePass blur = recording.compute("async-compute blurs");
        if (async) blur.async();
        for (int i = 0; i < blurs; i++) CgGpuOps.blur(blur, source, 0, blurred, 0, 6f);
        blur.end();
    }

    /** The pass reading the blur. */
    private void reader() {
        CgRasterPass pass = recording.raster(result, CgLoad.clear(0, 0, 0, 0),
                new CgPassConstants().resolution(W / 2, H / 2), null, CgOrder.LOOKBACK);
        int bindings = recording.bindings().withTexture(down.captureBindings(recording.bindings()), 0, blurred);
        CgChunkBuilder c = recording.chunks().begin();
        c.draw(down.pipeline(CgInstanceKind.OBJECT), bindings, CgMesh.quads(1));
        c.instance();
        pass.add(c.end());
        pass.end();
    }

    /** Keys a scramble of their index, sorted: a permutation, so the order is known. */
    private void sort(boolean async) {
        CgComputePass sort = recording.compute("async-compute sort");
        if (async) sort.async();
        CgGpuCount n = CgGpuCount.of(sortKeys);
        CgGpuOps.iota(sort, keys, 0, SCRAMBLE, n);
        CgGpuOps.sort(sort, CgGpuOps.Element.UINT, CgGpuOps.Order.ASCENDING, keys, null, n);
        sort.end();
    }

    private int[] readFirst() {
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, firstId);
        ByteBuffer mapped = CgGL.glMapBufferRange(CgGL.GL_COPY_READ_BUFFER, 0, FIRST * 4L, CgGL.GL_MAP_READ_BIT, null);
        int[] words = new int[FIRST];
        mapped.order(ByteOrder.nativeOrder()).asIntBuffer().get(words);
        CgGL.glUnmapBuffer(CgGL.GL_COPY_READ_BUFFER);
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, 0);
        return words;
    }

    /** The first {@link #FIRST} keys once sorted, worked out in Java. */
    private int[] expectedFirst() {
        long[] all = new long[sortKeys];
        for (int i = 0; i < sortKeys; i++) all[i] = i * (long) SCRAMBLE & 0xFFFFFFFFL;
        Arrays.sort(all);
        int[] out = new int[FIRST];
        for (int i = 0; i < FIRST; i++) out[i] = (int) all[i];
        return out;
    }

    /** The fill-bound drawing, touching nothing the blurs do. */
    private void draw() {
        CgRasterPass pass = recording.raster(canvas, CgLoad.clear(0, 0, 0, 0), new CgPassConstants().resolution(W, H),
                null, CgOrder.LOOKBACK);
        CgChunkBuilder c = recording.chunks().begin();
        c.draw(pattern.pipeline(CgInstanceKind.OBJECT), pattern.captureBindings(recording.bindings()), CgMesh.quads(1));
        for (int q = 0; q < quads; q++) c.instance();
        pass.add(c.end());
        pass.end();
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

    private static String compare(List<ByteBuffer> inOrder, List<ByteBuffer> async, Mode mode) {
        String[] names = {"blurred", "canvas", "result"};
        for (int i = 0; i < names.length; i++) {
            int at = inOrder.get(i).mismatch(async.get(i));
            if (at >= 0) return names[i] + " differs at byte " + at + " between the in-order frame and '" + mode.zone + "'";
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
            System.out.printf("[async-compute]   %-17s gpu %7.3f ms%n", MODES[i].zone, ms[i]);
        }
        double shorter = Math.min(ms[Mode.COMPUTE.ordinal()], ms[Mode.DRAW.ordinal()]);
        double saved = ms[Mode.IN_ORDER.ordinal()] - ms[Mode.ASYNC.ordinal()];
        double savedDrawnLast = ms[Mode.IN_ORDER.ordinal()] - ms[Mode.ASYNC_DRAWN_LAST.ordinal()];
        double shorterAcross = Math.min(ms[Mode.SORT.ordinal()], ms[Mode.DRAW.ordinal()]);
        double savedAcross = ms[Mode.ACROSS_IN_ORDER.ordinal()] - ms[Mode.ACROSS_ASYNC.ordinal()];
        System.out.printf("[async-compute]   async saves %.3f ms of the %.3f ms the shorter half takes (%.0f%%)%n",
                saved, shorter, 100 * saved / shorter);
        System.out.printf("[async-compute]   drawn last, async saves %.3f ms (%.0f%%)%n",
                savedDrawnLast, 100 * savedDrawnLast / shorter);
        System.out.printf("[async-compute]   across executions, async saves %.3f ms of the %.3f ms the shorter half takes (%.0f%%)%n",
                savedAcross, shorterAcross, 100 * savedAcross / shorterAcross);
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
        } else if (Double.isNaN(savedDrawnLast) || savedDrawnLast < shorter / 4) {
            System.out.println("[async-compute] FAIL on " + on + ": drawn last, the drawing was not moved beside the compute");
        } else if (Double.isNaN(savedAcross) || savedAcross < shorterAcross / 4) {
            System.out.println("[async-compute] FAIL on " + on + ": across executions, the drawing did not run beside the sort");
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
        CgGL.glDeleteBuffers(firstId);
        for (CgFrameBuffer fb : owned) fb.delete();
    }
}
