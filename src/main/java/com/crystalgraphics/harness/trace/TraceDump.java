package com.crystalgraphics.harness.trace;

import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceExport;
import com.crystalgraphics.trace.CgTraceNames;
import com.crystalgraphics.trace.CgTraceReport;
import com.crystalgraphics.trace.CgTraceSnapshot;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes {@link TraceReport}s to a text file a person or an agent reads after a run.
 *
 * <pre>{@code
 * long mark = CgTrace.currentFrameIndex();   // after warm-up
 * ...
 * File out = TraceDump.dumpAllThreads(outputDir, "text-3d", mark);   // cg-profile-text-3d-<stamp>.txt
 * }</pre>
 */
public final class TraceDump {

    private static final Logger LOGGER = Logger.getLogger(TraceDump.class.getName());

    private TraceDump() {
    }

    /** This thread's report over the frames since {@code frameIndex}. @return the file, or null */
    public static File dump(File directory, String label, long frameIndex) {
        return write(directory, label, Map.of("render", TraceReport.since(frameIndex)));
    }

    /** Every thread's, including background workers. @return the file, or null */
    public static File dumpAllThreads(File directory, String label, long frameIndex) {
        return write(directory, label, TraceReport.allThreadsSince(frameIndex));
    }

    /**
     * Everything one profiled run can say, from frame {@code fromFrame} on, into a folder whose name does
     * not change — so an agent's loop is "run, read {@code report.txt}, edit, run, diff against
     * {@code .prev}".
     *
     * <pre>{@code
     * TraceDump.profile(outputDir, "harness-300f", mark);
     * //   profile-<label>/report.txt   read this first: the breakdown, and the slowest, idlest and a
     * //                                typical frame in detail
     * //   profile-<label>/report-full.txt   every zone and counter, for a zone-by-zone comparison
     * //   profile-<label>/tree.txt     every thread's call tree, ms and calls per frame
     * //   profile-<label>/trace.json   for ui.perfetto.dev -- only with -Dcrystalgraphics.harness.profile.json=true
     * //   profile-<label>.prev/        the run before, for a diff
     * }</pre>
     *
     * @return the report, or null if it could not be written
     */
    public static File profile(File directory, String label, long fromFrame) {
        return profile(directory, label, fromFrame, Long.MAX_VALUE);
    }

    /**
     * {@link #profile(File, String, long)} over frames {@code fromFrame} up to but not including {@code toFrame}: one
     * phase of a run, as {@link #firstMarkerFrame} finds it.
     *
     * <pre>{@code
     * long split = TraceDump.firstMarkerFrame("vfx.blast", from);
     * TraceDump.profile(outputDir, "harness-300f-before", from, split);
     * TraceDump.profile(outputDir, "harness-300f-after", split, Long.MAX_VALUE);
     * }</pre>
     */
    public static File profile(File directory, String label, long fromFrame, long toFrame) {
        File folder = new File(directory, "profile-" + label);
        try {
            rotate(folder, new File(directory, "profile-" + label + ".prev"));
            Files.createDirectories(folder.toPath());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[TraceDump] could not prepare " + folder, e);
            return null;
        }
        CgTraceSnapshot range = CgTrace.snapshot().between(fromFrame, toFrame);
        SourcePaths sources = SourcePaths.forRepository();
        File report = new File(folder, "report.txt");
        File tree = new File(folder, "tree.txt");
        try {
            CgTraceReport full = CgTraceReport.of(range).sources(sources);
            Files.write(report.toPath(), full.breakdown().getBytes(StandardCharsets.UTF_8));
            Files.write(new File(folder, "report-full.txt").toPath(),
                    full.render(CgTraceReport.Tier.FULL).getBytes(StandardCharsets.UTF_8));
            Files.write(tree.toPath(), callTree(fromFrame, toFrame, range.frames().size(), sources)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[TraceDump] failed to write the profile", e);
            return null;
        }
        if (Boolean.getBoolean("crystalgraphics.harness.profile.json")) {
            File json = new File(folder, "trace.json");
            try (Writer out = Files.newBufferedWriter(json.toPath(), StandardCharsets.UTF_8)) {
                CgTraceExport.writeChromeJson(out, range);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "[TraceDump] failed to write the trace", e);
            }
        }
        LOGGER.info("[TraceDump] wrote " + folder.getAbsolutePath() + " -- read report.txt first");
        return report;
    }

    /** Moves the last run's folder aside, replacing the one before it. */
    private static void rotate(File current, File previous) throws IOException {
        if (!current.isDirectory()) return;
        if (previous.exists()) deleteTree(previous.toPath());
        Files.move(current.toPath(), previous.toPath());
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> all = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path each : all) Files.deleteIfExists(each);
        }
    }

    /** Every thread's call tree over the profiled frames, in ms and calls per frame, with sources. */
    private static String callTree(long fromFrame, long toFrame, int frames, SourcePaths sources) {
        Map<String, TraceReport> threads = TraceReport.allThreadsBetween(fromFrame, toFrame);
        int per = Math.max(1, frames);
        StringBuilder out = new StringBuilder(16384);
        out.append("CALL TREE  every thread, ").append(frames)
                .append(" frames. ms per frame (total, self), calls per frame, the longest single call\n");
        for (Map.Entry<String, TraceReport> thread : threads.entrySet()) {
            List<TraceReport.ScopeEntry> scopes = thread.getValue().scopes();
            int width = "zone".length();
            for (TraceReport.ScopeEntry e : scopes) width = Math.max(width, e.depth() * 2 + e.name().length() + (CgTrace.isWait(e.name()) ? 7 : 0));
            out.append("\n[").append(thread.getKey()).append("]\n");
            out.append(String.format(Locale.ROOT, "  %-" + width + "s %10s %10s %10s %9s   %s%n",
                    "zone", "total/fr", "self/fr", "calls/fr", "max ms", "source"));
            for (TraceReport.ScopeEntry e : scopes) {
                String source = CgTraceNames.sourceOf(CgTraceNames.intern(e.name()));
                out.append(String.format(Locale.ROOT, "  %-" + width + "s %10.3f %10.3f %10.1f %9.3f   %s%n",
                        "  ".repeat(e.depth()) + e.name() + (CgTrace.isWait(e.name()) ? " [wait]" : ""),
                        e.totalNanos() / 1e6d / per, e.selfNanos() / 1e6d / per, e.callCount() / (double) per,
                        e.maxNanos() / 1e6d, source == null ? "" : sources.apply(source)));
            }
        }
        return out.toString();
    }

    /**
     * The frame holding the first marker named {@code name} at or after frame {@code fromFrame}, or -1 for none: where a
     * run's phase begins (a blast, a load) when its scene leaves a marker there.
     */
    public static long firstMarkerFrame(String name, long fromFrame) {
        CgTraceSnapshot snapshot = CgTrace.snapshot();
        long since = Long.MAX_VALUE;
        for (CgFrameRecord frame : snapshot.frames()) {
            if (frame.index() >= fromFrame) since = Math.min(since, frame.beginNanos());
        }
        long first = Long.MAX_VALUE;
        for (CgTraceSnapshot.MarkerView marker : snapshot.markers()) {
            if (marker.name().equals(name) && marker.nanos() >= since) first = Math.min(first, marker.nanos());
        }
        if (first == Long.MAX_VALUE) return -1L;
        for (CgFrameRecord frame : snapshot.frames()) {
            if (frame.index() >= fromFrame && frame.endNanos() > first) return frame.index();
        }
        return -1L;
    }

    private static File write(File directory, String label, Map<String, TraceReport> reports) {
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                LOGGER.warning("[TraceDump] could not create " + directory);
                return null;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            File out = new File(directory, "cg-profile-" + label + "-" + stamp + ".txt");
            try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
                pw.println("=== CrystalGraphics profile: " + label + " @ " + stamp + " ===");
                pw.println("threads: " + reports.size());
                pw.println();
                for (Map.Entry<String, TraceReport> entry : reports.entrySet()) {
                    pw.println("--- thread: " + entry.getKey() + " ---");
                    writeReport(pw, entry.getValue());
                    pw.println();
                }
            }
            LOGGER.info("[TraceDump] wrote " + out.getAbsolutePath());
            return out;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "[TraceDump] failed to write profile", e);
            return null;
        }
    }

    private static void writeReport(PrintWriter pw, TraceReport report) {
        pw.printf(Locale.ROOT, "  %-40s %12s %12s %8s %10s%n", "scope", "total ms", "self ms", "calls", "max ms");
        for (TraceReport.ScopeEntry e : report.scopes()) {
            pw.printf(Locale.ROOT, "  %-40s %12.3f %12.3f %8d %10.3f%n", "  ".repeat(e.depth()) + e.name(),
                    e.totalNanos() / 1_000_000.0, e.selfNanos() / 1_000_000.0, e.callCount(), e.maxNanos() / 1_000_000.0);
        }
        if (!report.samples().isEmpty()) {
            pw.println("  --- counters (sum, and per recorded value: avg / min / max / last) ---");
            for (Map.Entry<String, TraceReport.SampleSummary> e : report.samples().entrySet()) {
                TraceReport.SampleSummary s = e.getValue();
                pw.printf(Locale.ROOT, "  %-40s %12d  n=%d avg=%.2f min=%.2f max=%.2f last=%.2f%n", e.getKey(),
                        report.counter(e.getKey()), s.count(), s.avg(), s.min(), s.max(), s.last());
            }
        }
    }
}
