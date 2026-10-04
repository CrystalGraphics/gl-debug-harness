package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.compute.ops.CgGpuOps.Element;
import com.crystalgraphics.compute.ops.CgGpuOps.Filter;
import com.crystalgraphics.compute.ops.CgGpuOps.Fold;
import com.crystalgraphics.compute.ops.CgGpuOps.Order;
import com.crystalgraphics.compute.ops.CgGpuOps.Scan;
import com.crystalgraphics.compute.ops.CgRng;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.graph.CgFrameGraph;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What each {@code CgGpuOps} op costs, on the GPU and on the CPU that submits it: every op at a million elements, and
 * the image ops over a 1920x1080 chain, each executed as a graph of its own inside a GPU timer zone, over 60 frames
 * after 10 of warm-up. Prints a table of means and exits. Run it per tier, as {@code gpu-ops} is.
 *
 * <pre>
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=gpu-ops-cost"
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=gpu-ops-cost" -Dcrystalgraphics.compute.tier=G40
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=gpu-ops-cost --device=vulkan"
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=gpu-ops-cost" -Dcrystalgraphics.harness.opsCost.count=65536
 * </pre>
 *
 * <ul>
 *   <li>{@code -Dcrystalgraphics.harness.opsCost.count} sets the element count (1048576),
 *       {@code .ops=sort,scan} keeps the ops whose names start with one of those, and {@code .warmup} the frames run
 *       before measuring (10). A CPU column compared across devices wants {@code .warmup=600}: under ten thousand
 *       calls, the tracked backend's methods are not yet compiled.</li>
 *   <li>A sort's keys are copied back before each run, outside its zone, so every frame sorts the same unsorted keys.</li>
 *   <li>The CPU column is {@code CgExecutor.execute}'s wall time: the graph is built before the zone opens. At the CPU
 *       tier it is the op itself.</li>
 * </ul>
 */
public class CgGpuOpsCostScene implements InteractiveSceneLifecycle {

    private static final int MEASURED = 60, DRAIN = 30;
    /** Frames run before measuring: the tracked backend's Java needs hundreds before the JIT has compiled it. */
    private static final int WARMUP = Integer.getInteger("crystalgraphics.harness.opsCost.warmup", 10);
    private static final int WIDTH = 1920, HEIGHT = 1080;

    private record Op(String name, Consumer<CgRecording> setup, Consumer<CgComputePass> body) {}

    private final List<Op> ops = new ArrayList<>();
    private final List<Integer> buffers = new ArrayList<>();
    private final List<CgFrameBuffer> textures = new ArrayList<>();
    private final CgFrameBuilder builder = new CgFrameBuilder();
    private final CgFrameGraph graph = new CgFrameGraph();
    private final CgRecording recording = new CgRecording();
    private Consumer<CgRecording> opener;
    private long[] cpuNanos;
    private int count, frame;
    private boolean wasTracingGpu, running = true;

    @Override
    public void init(HarnessContext ctx) {
        count = Integer.getInteger("crystalgraphics.harness.opsCost.count", 1 << 20);
        String only = System.getProperty("crystalgraphics.harness.opsCost.ops", "");
        CgGpuCount all = CgGpuCount.of(count);

        int[] uints = new int[count], floats = new int[count], flags = new int[count], points = new int[4 * count];
        for (int i = 0; i < count; i++) {
            uints[i] = CgRng.rng(count, i, 0, 0);
            floats[i] = Float.floatToRawIntBits((CgRng.rng(count, i, 0, 1) >>> 16) / 977f - 30f);
            flags[i] = Integer.remainderUnsigned(CgRng.rng(count, i, 0, 2), 3) == 0 ? 1 : 0;
        }
        for (int i = 0; i < points.length; i++) {
            points[i] = Float.floatToRawIntBits((CgRng.rng(count, i, 1, 0) >>> 12) / 4099f - 500f);
        }
        long words = 4L * count;
        CgGraphBuffer keysSource = buffer("keys source", uints);
        CgGraphBuffer floatValues = buffer("floats", floats);
        CgGraphBuffer flagWords = buffer("flags", flags);
        CgGraphBuffer pointWords = buffer("points", points);
        CgGraphBuffer valuesSource = buffer("values source", sequence(count));
        CgGraphBuffer out = buffer("out", new int[count]);
        CgGraphBuffer keys = buffer("keys", uints);
        CgGraphBuffer values = buffer("values", new int[count]);
        CgGraphBuffer results = buffer("results", new int[16]);
        CgGraphBuffer bins = buffer("bins", new int[64]);
        CgGraphBuffer word = buffer("word", new int[1]);
        opener = rec -> {
            CgComputePass pass = rec.compute("ops-cost opener");
            CgGpuOps.fill(pass, word, 0, CgGpuCount.of(1));
            pass.end();
        };
        Consumer<CgRecording> unsorted = rec -> {
            rec.copy(keysSource, 0, keys, 0, words);
            rec.copy(valuesSource, 0, values, 0, words);
        };

        CgGraphTexture chain = texture("chain", CgTextureType.RGBA16F, CgTexture.fullChain(WIDTH, HEIGHT));
        CgGraphTexture depth = texture("depth", CgTextureType.R32F, CgTexture.fullChain(WIDTH, HEIGHT));
        CgGraphTexture blurred = texture("blurred", CgTextureType.RGBA16F, 1);

        add(only, "fill", null, p -> CgGpuOps.fill(p, out, 7, all));
        add(only, "iota", null, p -> CgGpuOps.iota(p, out, 0, 1, all));
        add(only, "copy", null, p -> CgGpuOps.copy(p, keysSource, out, all));
        add(only, "reduce uint", null, p -> CgGpuOps.reduce(p, Fold.SUM, Element.UINT, keysSource, all, results, 0));
        add(only, "reduce float", null, p -> CgGpuOps.reduce(p, Fold.SUM, Element.FLOAT, floatValues, all, results, 1));
        add(only, "bounds", null, p -> CgGpuOps.bounds(p, pointWords, 4, 0, all, results, 2));
        add(only, "scan", null, p -> CgGpuOps.scan(p, Scan.EXCLUSIVE, Fold.SUM, Element.UINT, keysSource, all, out));
        add(only, "compact", null, p -> CgGpuOps.compact(p, flagWords, null, all, out, results, 8));
        add(only, "histogram 64", null, p -> CgGpuOps.histogram(p, keysSource, all, bins, 64, 26));
        add(only, "sort 32 bits", unsorted, p -> CgGpuOps.sort(p, Element.UINT, Order.ASCENDING, keys, values, all));
        add(only, "sort 12 bits", unsorted, p -> CgGpuOps.sort(p, 12, Order.ASCENDING, keys, values, all));
        add(only, "downsample rgba16f", null, p -> CgGpuOps.downsample(p, chain, Filter.AVERAGE));
        add(only, "downsample r32f max", null, p -> CgGpuOps.downsample(p, depth, Filter.MAX));
        add(only, "blur rgba16f s2", null, p -> CgGpuOps.blur(p, chain, 0, blurred, 0, 2f));
        add(only, "blur rgba16f s8", null, p -> CgGpuOps.blur(p, chain, 0, blurred, 0, 8f));
        add(only, "blur level 2 s4", null, p -> CgGpuOps.blur(p, chain, 2, chain, 2, 4f));
        cpuNanos = new long[ops.size()];

        wasTracingGpu = CgTrace.isEnabled(CgGpuTrace.GPU);
        CgTrace.setEnabled(CgGpuTrace.GPU, true);
        CgGpuTrace.resetTotals();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        CgGpuTrace.collect();
        boolean measuring = frame >= WARMUP && frame < WARMUP + MEASURED;
        if (frame < WARMUP + MEASURED) {
            // Untimed, so the wait for an earlier frame's GPU work lands here rather than in the first op.
            execute(opener, null, -1, false);
            for (int i = 0; i < ops.size(); i++) run(i, measuring);
        } else if (frame >= WARMUP + MEASURED + DRAIN || everyQueryLanded()) {
            report();
            running = false;
        }
        frame++;
    }

    private void run(int index, boolean measuring) {
        Op op = ops.get(index);
        if (op.setup() != null) execute(op.setup(), null, -1, false);
        execute(rec -> {
            CgComputePass pass = rec.compute("ops-cost " + op.name());
            op.body().accept(pass);
            pass.end();
        }, op.name(), index, measuring);
    }

    private void execute(Consumer<CgRecording> record, String zone, int index, boolean measuring) {
        recording.reset();
        record.accept(recording);
        CgFrame built = builder.build(graph.add(recording.seal()));
        graph.clear();
        if (measuring) CgGpuTrace.begin(zone);
        long start = System.nanoTime();
        CgExecutor.execute(built, true);
        if (measuring) {
            cpuNanos[index] += System.nanoTime() - start;
            CgGpuTrace.end();
        }
        builder.recycle(built);
    }

    private boolean everyQueryLanded() {
        Map<String, long[]> totals = CgGpuTrace.totals();
        for (Op op : ops) {
            long[] t = totals.get(op.name());
            if (t == null || t[1] < MEASURED) return false;
        }
        return true;
    }

    private void report() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = (device == null ? CgGL.glGetString(CgGL.GL_RENDERER) : device.name()) + " at "
                + CgCapabilities.detect().computeTier();
        System.out.println("[gpu-ops-cost] " + on + ", " + count + " elements, images " + WIDTH + "x" + HEIGHT
                + ", ms per run over " + MEASURED + " frames");
        if (CgGpuTrace.support() == CgGpuTrace.Support.UNSUPPORTED) {
            System.out.println("[gpu-ops-cost]   this context has no timer queries: CPU only");
        }
        Map<String, long[]> totals = CgGpuTrace.totals();
        for (int i = 0; i < ops.size(); i++) {
            String name = ops.get(i).name();
            long[] t = totals.get(name);
            String gpu = t == null || t[1] == 0 ? "      -" : String.format("%7.3f", t[0] / 1e6 / t[1]);
            System.out.printf("[gpu-ops-cost]   %-22s gpu %s   cpu %7.3f%n", name, gpu, cpuNanos[i] / 1e6 / MEASURED);
        }
    }

    private void add(String only, String name, Consumer<CgRecording> setup, Consumer<CgComputePass> body) {
        if (!only.isEmpty() && Arrays.stream(only.split(",")).map(String::trim).noneMatch(name::startsWith)) return;
        ops.add(new Op(name, setup, body));
    }

    private CgGraphBuffer buffer(String name, int[] words) {
        ByteBuffer data = ByteBuffer.allocateDirect(words.length * 4).order(ByteOrder.nativeOrder());
        for (int w : words) data.putInt(w);
        data.flip();
        int buffer = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, buffer);
        CgGL.glBufferData(CgGL.GL_COPY_WRITE_BUFFER, data, CgGL.GL_STATIC_DRAW);   // device-local on a device
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, 0);
        buffers.add(buffer);
        return CgGraphBuffer.imported(name, buffer, words.length * 4L);
    }

    private CgGraphTexture texture(String name, CgTextureType type, int levels) {
        CgFrameBufferFormat format = CgFrameBufferFormat.builder("ops_cost_" + name).color(0, type).build();
        CgFrameBuffer fb = CgFrameBuffer.createOwned("ops-cost " + name, WIDTH, HEIGHT, format, levels);
        ByteBuffer pixels = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 16).order(ByteOrder.nativeOrder());
        for (int i = 0; i < WIDTH * HEIGHT * 4; i++) pixels.putFloat((CgRng.rng(WIDTH, i, 2, 0) >>> 8) / (float) (1 << 24));
        pixels.flip();
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, fb.getColorTexture(0).getId());
        CgGL.glTexSubImage2D(CgGL.GL_TEXTURE_2D, 0, 0, 0, WIDTH, HEIGHT, CgGL.GL_RGBA, CgGL.GL_FLOAT, pixels);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, 0);
        textures.add(fb);
        return CgGraphTexture.imported(name, fb);
    }

    private static int[] sequence(int n) {
        int[] words = new int[n];
        for (int i = 0; i < n; i++) words[i] = i;
        return words;
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return true; }

    @Override
    public void dispose() {
        CgTrace.setEnabled(CgGpuTrace.GPU, wasTracingGpu);
        for (int buffer : buffers) CgGL.glDeleteBuffers(buffer);
        for (CgFrameBuffer fb : textures) fb.delete();
    }
}
