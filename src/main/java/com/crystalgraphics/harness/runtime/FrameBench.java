package com.crystalgraphics.harness.runtime;

import com.sun.management.ThreadMXBean;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Times an interactive scene's frames and stops it: wall time, the render thread's CPU time and what that
 * thread allocated, as percentiles over a fixed number of frames after a warm-up.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-desktop" \
 *     -Dcrystalgraphics.harness.bench=1500 -Dcrystalgraphics.harness.fps=0 -Dcrystalgraphics.harness.fixedDelta=0.0166667
 * }</pre>
 *
 * <ul>
 *   <li>Uncapped ({@code fps=0}) or the frame cap's sleep is what gets measured.</li>
 *   <li>{@code bench.warmup} (default 300) frames are discarded first: lazy allocation, shader compiles and
 *       the JIT's first tiers are startup, not frame cost.</li>
 *   <li>One run is one sample. Compare builds run to run on one machine, and alternate them.</li>
 * </ul>
 */
public final class FrameBench {

    private static final Logger LOGGER = Logger.getLogger(FrameBench.class.getName());

    private final int frames;
    private final int warmup = Integer.getInteger("crystalgraphics.harness.bench.warmup", 300);
    private final long[] wallNanos, cpuNanos, allocatedBytes;
    private final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private int seen, recorded;
    private long wallStart, cpuStart, allocStart;

    private FrameBench(int frames) {
        this.frames = frames;
        wallNanos = new long[frames];
        cpuNanos = new long[frames];
        allocatedBytes = new long[frames];
    }

    /** The bench {@code -Dcrystalgraphics.harness.bench=<frames>} asks for, or null. */
    public static FrameBench fromProperty() {
        int frames = Integer.getInteger("crystalgraphics.harness.bench", 0);
        return frames > 0 ? new FrameBench(frames) : null;
    }

    public void begin() {
        wallStart = System.nanoTime();
        cpuStart = threads.getCurrentThreadCpuTime();
        allocStart = threads.getCurrentThreadAllocatedBytes();
    }

    /** Ends a frame; true once enough have been recorded. */
    public boolean end() {
        if (seen++ >= warmup) {
            wallNanos[recorded] = System.nanoTime() - wallStart;
            cpuNanos[recorded] = threads.getCurrentThreadCpuTime() - cpuStart;
            allocatedBytes[recorded] = threads.getCurrentThreadAllocatedBytes() - allocStart;
            recorded++;
        }
        return recorded == frames;
    }

    /** Logs one line per measure, and returns the same text. */
    public String report(String scene) {
        long cpuTotal = 0;
        for (int i = 0; i < recorded; i++) cpuTotal += cpuNanos[i];
        String text = "[FrameBench] " + scene + " over " + recorded + " frames (after " + warmup + ")\n"
                + line("wall ms ", wallNanos, 1e-6)
                + line("alloc KB", allocatedBytes, 1.0 / 1024)
                // A thread's CPU clock ticks in 15.6 ms steps on Windows: only the total means anything.
                + String.format("  cpu ms   mean %8.3f%n", cpuTotal * 1e-6 / Math.max(1, recorded))
                + "  bytecode " + major("com/crystalgraphics/text/render/CgTextRenderer.class") + " (CrystalGraphics), "
                + major("com/crystalgui/ui/dom/UINode.class") + " (CrystalGUI)\n";
        LOGGER.info(text);
        return text;
    }

    private String line(String label, long[] values, double scale) {
        long[] sorted = Arrays.copyOf(values, recorded);
        Arrays.sort(sorted);
        double mean = 0;
        for (long v : sorted) mean += v;
        mean /= Math.max(1, recorded);
        return String.format("  %s mean %8.3f  p50 %8.3f  p90 %8.3f  p99 %8.3f  max %8.3f%n", label,
                mean * scale, at(sorted, 0.50) * scale, at(sorted, 0.90) * scale, at(sorted, 0.99) * scale,
                sorted.length == 0 ? 0 : sorted[sorted.length - 1] * scale);
    }

    /** The class-file major version a class was actually loaded from, or "-" if it is not on the classpath. */
    private static String major(String resource) {
        try (var in = FrameBench.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return "-";
            byte[] head = in.readNBytes(8);
            return String.valueOf(((head[6] & 0xFF) << 8) | (head[7] & 0xFF));
        } catch (IOException e) {
            return "?";
        }
    }

    private static double at(long[] sorted, double q) {
        if (sorted.length == 0) return 0;
        return sorted[Math.min(sorted.length - 1, (int) Math.floor(q * sorted.length))];
    }
}
