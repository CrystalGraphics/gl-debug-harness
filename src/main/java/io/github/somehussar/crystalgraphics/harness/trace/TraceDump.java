package io.github.somehussar.crystalgraphics.harness.trace;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
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
