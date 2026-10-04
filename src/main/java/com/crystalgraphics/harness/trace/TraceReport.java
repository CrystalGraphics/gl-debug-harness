package com.crystalgraphics.harness.trace;

import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceSnapshot;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a scene's frames spent, read back from the trace ring: scopes as call paths with inclusive and
 * self time, counters summed, and every counter's values as a sample.
 *
 * <pre>{@code
 * TraceReport last = TraceReport.lastFrame();          // this thread, the frame just committed
 * last.scopeMillis("text.draw");                       // any path ending in that name
 *
 * long mark = CgTrace.currentFrameIndex();             // after warm-up
 * ...
 * TraceReport run = TraceReport.since(mark);           // every frame since, this thread
 * Map<String, TraceReport> all = TraceReport.allThreadsSince(mark);
 * }</pre>
 *
 * <p>Only frames the ring still holds and only zones on channels that were recording. A frame is
 * committed at the NEXT {@code frameBegin}, so the frame being drawn is never in a report yet.</p>
 */
public record TraceReport(String threadName, List<ScopeEntry> scopes, Map<String, Long> counters,
                          Map<String, SampleSummary> samples) {

    /**
     * One call path's statistics.
     *
     * @param path       {@code "draw/resolve/flatten"}
     * @param selfNanos  total minus the direct children's totals
     */
    public record ScopeEntry(String path, int depth, long totalNanos, long selfNanos, long callCount, long maxNanos) {

        /** The path's last segment. */
        public String name() {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }

    /** Every value a counter was given over the report's frames. */
    public record SampleSummary(long count, double sum, double min, double max, double last) {
        public double avg() {
            return count == 0 ? 0.0 : sum / count;
        }
    }

    // ── Building one ────────────────────────────────────────────────────────────────────────

    /** This thread's scopes and every counter of the frame just committed; empty before any frame. */
    public static TraceReport lastFrame() {
        CgFrameRecord frame = CgTrace.frame(CgTrace.currentFrameIndex() - 1L);
        return frame == null ? empty(Thread.currentThread().getName())
                : of(Thread.currentThread().getName(), List.of(frame));
    }

    /** This thread's scopes and every counter over the committed frames from {@code frameIndex} on. */
    public static TraceReport since(long frameIndex) {
        return of(Thread.currentThread().getName(), framesSince(frameIndex));
    }

    /** One report per thread that recorded a zone, over the committed frames from {@code frameIndex} on. */
    public static Map<String, TraceReport> allThreadsSince(long frameIndex) {
        return allThreadsBetween(frameIndex, Long.MAX_VALUE);
    }

    /** {@link #allThreadsSince}, over the committed frames from {@code from} up to but not including {@code to}. */
    public static Map<String, TraceReport> allThreadsBetween(long from, long to) {
        List<CgFrameRecord> frames = framesBetween(from, to);
        Map<String, TraceReport> out = new LinkedHashMap<>();
        if (frames.isEmpty()) return out;
        String self = Thread.currentThread().getName();
        out.put(self, of(self, frames));
        for (CgTraceSnapshot.ZoneView zone : zonesOf(frames)) {
            if (!out.containsKey(zone.thread())) out.put(zone.thread(), null);
        }
        for (Map.Entry<String, TraceReport> entry : out.entrySet()) {
            if (entry.getValue() == null) entry.setValue(of(entry.getKey(), frames, false));
        }
        return out;
    }

    private static List<CgFrameRecord> framesSince(long frameIndex) {
        return framesBetween(frameIndex, Long.MAX_VALUE);
    }

    private static List<CgFrameRecord> framesBetween(long from, long to) {
        List<CgFrameRecord> out = new ArrayList<>();
        for (CgFrameRecord frame : CgTrace.frames()) {
            if (frame.index() >= from && frame.index() < to) out.add(frame);
        }
        return out;
    }

    private static TraceReport empty(String thread) {
        return new TraceReport(thread, List.of(), Map.of(), Map.of());
    }

    private static TraceReport of(String thread, List<CgFrameRecord> frames) {
        return of(thread, frames, true);
    }

    /** Counters have no thread, so only the asking thread's report carries them. */
    private static TraceReport of(String thread, List<CgFrameRecord> frames, boolean withCounters) {
        if (frames.isEmpty()) return empty(thread);
        List<CgTraceSnapshot.ZoneView> zones = new ArrayList<>();
        for (CgTraceSnapshot.ZoneView zone : zonesOf(frames)) {
            if (!zone.isOpen() && thread.equals(zone.thread())) zones.add(zone);
        }
        Map<String, Long> counters = new TreeMap<>();
        Map<String, double[]> samples = new TreeMap<>();
        if (withCounters) {
            for (CgFrameRecord frame : frames) {
                for (CgTraceSnapshot.CounterView counter : CgTrace.countersIn(frame)) {
                    counters.merge(counter.name(), counter.value(), Long::sum);
                    double v = counter.value();
                    double[] s = samples.computeIfAbsent(counter.name(),
                            k -> new double[] {0, 0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0});
                    s[0]++;
                    s[1] += v;
                    s[2] = Math.min(s[2], v);
                    s[3] = Math.max(s[3], v);
                    s[4] = v;
                }
            }
        }
        Map<String, SampleSummary> summaries = new TreeMap<>();
        for (Map.Entry<String, double[]> e : samples.entrySet()) {
            double[] s = e.getValue();
            summaries.put(e.getKey(), new SampleSummary((long) s[0], s[1], s[2], s[3], s[4]));
        }
        return new TraceReport(thread, tree(zones), Collections.unmodifiableMap(counters),
                Collections.unmodifiableMap(summaries));
    }

    private static List<CgTraceSnapshot.ZoneView> zonesOf(List<CgFrameRecord> frames) {
        return CgTrace.zonesBetween(frames.get(0).beginNanos(), frames.get(frames.size() - 1).endNanos());
    }

    /** Zones nested by containment into paths, then each path's statistics with self time. */
    private static List<ScopeEntry> tree(List<CgTraceSnapshot.ZoneView> zones) {
        zones.sort((a, b) -> a.startNanos() != b.startNanos()
                ? Long.compare(a.startNanos(), b.startNanos())
                : Long.compare(b.endNanos(), a.endNanos()));
        Map<String, long[]> stats = new HashMap<>();   // total, calls, max
        Deque<CgTraceSnapshot.ZoneView> open = new ArrayDeque<>();
        Deque<String> paths = new ArrayDeque<>();
        for (CgTraceSnapshot.ZoneView zone : zones) {
            while (!open.isEmpty() && zone.endNanos() > open.peek().endNanos()) {
                open.pop();
                paths.pop();
            }
            String path = paths.isEmpty() ? zone.name() : paths.peek() + "/" + zone.name();
            long took = zone.durationNanos();
            long[] s = stats.computeIfAbsent(path, k -> new long[3]);
            s[0] += took;
            s[1]++;
            s[2] = Math.max(s[2], took);
            open.push(zone);
            paths.push(path);
        }
        List<String> sorted = new ArrayList<>(stats.keySet());
        Collections.sort(sorted);
        List<ScopeEntry> out = new ArrayList<>(sorted.size());
        for (String path : sorted) {
            long[] s = stats.get(path);
            long children = 0L;
            String prefix = path + "/";
            for (String other : sorted) {
                if (other.startsWith(prefix) && other.indexOf('/', prefix.length()) < 0) children += stats.get(other)[0];
            }
            int depth = 0;
            for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '/') depth++;
            out.add(new ScopeEntry(path, depth, s[0], s[0] - children, s[1], s[2]));
        }
        return Collections.unmodifiableList(out);
    }

    // ── Reading one ─────────────────────────────────────────────────────────────────────────

    /** Total milliseconds of every path ending in {@code name}. */
    public double scopeMillis(String name) {
        long nanos = 0L;
        for (ScopeEntry e : scopes) if (e.name().equals(name)) nanos += e.totalNanos();
        return nanos / 1_000_000.0;
    }

    /** Calls of every path ending in {@code name}. */
    public long scopeCalls(String name) {
        long calls = 0L;
        for (ScopeEntry e : scopes) if (e.name().equals(name)) calls += e.callCount();
        return calls;
    }

    /** One exact path, or null. */
    @Nullable
    public ScopeEntry scope(String path) {
        for (ScopeEntry e : scopes) if (e.path().equals(path)) return e;
        return null;
    }

    public long counter(String name) {
        Long value = counters.get(name);
        return value == null ? 0L : value;
    }

    @Nullable
    public SampleSummary sample(String name) {
        return samples.get(name);
    }

    /** An indented call tree, then counters, then samples. */
    public String format() {
        StringBuilder sb = new StringBuilder("TraceReport[").append(threadName).append("]\n");
        if (!scopes.isEmpty()) {
            sb.append("  scopes:\n");
            for (ScopeEntry s : scopes) {
                sb.append("    ").append("  ".repeat(s.depth())).append(s.name())
                        .append(String.format(Locale.ROOT, " - total %.3fms, self %.3fms, calls %d, max %.3fms%n",
                                s.totalNanos() / 1e6, s.selfNanos() / 1e6, s.callCount(), s.maxNanos() / 1e6));
            }
        }
        if (!counters.isEmpty()) {
            sb.append("  counters:\n");
            for (Map.Entry<String, Long> e : counters.entrySet()) {
                sb.append("    ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            }
        }
        return sb.toString();
    }
}
