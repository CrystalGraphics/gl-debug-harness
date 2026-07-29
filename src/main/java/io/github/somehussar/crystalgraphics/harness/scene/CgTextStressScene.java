package io.github.somehussar.crystalgraphics.harness.scene;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgraphics.text.render.context.CgTextRenderContext;
import com.crystalgraphics.util.profiling.CgProfiler;
import com.crystalgraphics.util.profiling.CgProfilerReport;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.util.HarnessFontUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.util.profiling.CgGpuProfiler;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Pure-CrystalGraphics twin of {@code CgUiTextStressScene}: the same text load, none of the UI stack.
 *
 * <h3>Why both exist</h3>
 * <p>The UI version measures {@code UIText} — which means it also measures Taffy layout, the style
 * engine, and CrystalGUI's one-material-bind-per-element draw path. Those dominated: at 1000 labels
 * the material binds alone were ~200 ms/frame, and {@code UIText.recompute} was another 30% of the
 * reshape path. None of that is the text engine.
 *
 * <p>This scene holds {@link CgShapedParagraph} and {@link CgTextLayout} directly and draws through
 * {@link CgTextRenderer}, so every number it produces belongs to CrystalGraphics and the
 * FreeType/HarfBuzz bindings. Comparing the two isolates exactly what the UI layer adds.
 *
 * <h3>Modes</h3>
 * <p>Identical to the UI scene so the two are directly comparable:
 * <ul>
 *   <li>{@code STATIC} — nothing changes; the retained-shape floor.</li>
 *   <li>{@code SAME_LENGTH} — reshape every label every frame at constant length.
 *       <b>STATIC → SAME_LENGTH is the cost of shaping.</b></li>
 *   <li>{@code VARYING_LENGTH} — reshape with changing length, so wrapping work changes too.</li>
 * </ul>
 *
 * <p>Unlike the UI scene there is no reflow tier here: nothing sizes a box around this text, so
 * VARYING_LENGTH differs from SAME_LENGTH only in how much line-breaking each string needs.
 */
public class CgTextStressScene implements InteractiveSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(CgTextStressScene.class.getName());

    private static final int LABEL_COUNT = 1000;
    private static final int LABEL_CHARS = 20;
    private static final int FONT_SIZE_PX = 12;

    /**
     * Wrap width in px. <strong>Not zero, deliberately.</strong>
     *
     * <p>An earlier version passed {@code 0f} (CrystalGraphics' "unbounded" convention), which meant
     * every label was a single line and {@code CgLineBreaker} did almost no work — so the numbers
     * measured shaping plus a trivial layout, not shaping plus a real one. Real UI labels wrap.
     * This is narrow enough that a {@value #LABEL_CHARS}-character string breaks across lines, so
     * the line breaker is actually exercised.
     */
    private static final float WRAP_WIDTH = 70f;

    /** Matches the UI scene: drawing is a separate question from shaping. */
    private static final boolean DRAW_LABELS = true;

    private static final double MODE_SECONDS = 3.0;
    private static final double WARMUP_SECONDS = 2.0;

    private enum Mode {
        STATIC("text never changes (the retained-shape floor)"),
        SAME_LENGTH("reshape every frame, constant length"),
        VARYING_LENGTH("reshape every frame, varying length");

        final String description;

        Mode(String description) {
            this.description = description;
        }
    }

    /**
     * Every CSV column, as (header, kind, profiler-name) triples.
     *
     * <p>Declared as one table rather than a header array plus a positional format string, because
     * that split silently drifts. Two real bugs came from it: two declared columns were never
     * written, shifting every later value one place left and making unrelated scopes look free; and
     * {@code submitQuadsMs} read a scope named {@code submitBatchedQuads} while the code emits
     * {@code submitSortedQuads}, so the column reported 0.000 rather than its real cost. Here a
     * column's name and its source are the same entry and cannot disagree.
     */
    private enum Kind { SCOPE, SAMPLE, COUNTER }

    private record Column(String header, Kind kind, String profilerName) {
        static Column scope(String header, String name) { return new Column(header, Kind.SCOPE, name); }
        static Column sample(String header, String name) { return new Column(header, Kind.SAMPLE, name); }
        static Column counter(String header, String name) { return new Column(header, Kind.COUNTER, name); }
    }

    private static final Column[] COLUMNS = {
            // ── reshape ────────────────────────────────────────────────────────────────────
            Column.scope("reshapeMs", "text.reshape"),
            Column.scope("shapeRunsMs", "shape.runs"),
            Column.scope("shapeBidiMs", "shape.bidi"),
            Column.scope("shapeCollectMs", "shape.collectRuns"),
            Column.scope("shapeResolveRunsMs", "shape.resolveRuns"),
            Column.scope("shapeHarfbuzzMs", "shape.harfbuzz"),
            Column.scope("hbCreateMs", "hb.bufferCreate"),
            Column.scope("hbFillMs", "hb.bufferFill"),
            Column.scope("hbShapeMs", "hb.shape"),
            Column.scope("hbReadBackMs", "hb.readBack"),
            Column.scope("hbDestroyMs", "hb.bufferDestroy"),
            Column.scope("shapeReshaperMs", "shape.reshaper"),
            Column.scope("shapeEllipsisMs", "shape.ellipsis"),
            Column.scope("wrapBreakLinesMs", "wrap.breakLines"),
            Column.scope("wrapBoundariesMs", "wrap.breakBoundaries"),
            Column.scope("wrapJustifyMs", "wrap.justify"),
            Column.scope("wrapBakeMs", "wrap.bakeGlyphs"),

            // ── draw: glyph resolution ─────────────────────────────────────────────────────
            Column.scope("drawMs", "text.draw"),
            Column.scope("resolveGlyphsMs", "resolveGlyphs"),
            Column.scope("placementLookupMs", "placementCache.lookup"),
            Column.scope("flattenMs", "flatten"),
            Column.scope("resolvePlacementsMs", "resolvePlacements"),
            Column.scope("placementPutMs", "placementCache.put"),

            // ── draw: quad submission ──────────────────────────────────────────────────────
            Column.scope("submitQuadsMs", "submitSortedQuads"),
            Column.scope("quadLoopMs", "quadLoop"),
            Column.scope("sortKeysMs", "sortKeys"),
            Column.scope("visibilityScanMs", "visibilityScan"),
            Column.scope("resolveDecorMs", "resolveDecorations"),
            Column.scope("syncProjectionMs", "syncProjection"),
            Column.scope("matDoBindMs", "material.doBind"),
            Column.scope("dbStateSaveMs", "doBind.stateSave"),

            // ── draw: the batch path (previously unmeasured entirely) ──────────────────────
            // Text draws through CgQuadRenderer (instanced), so these are the real GPU tail.
            Column.scope("qrFlushMs", "quadRenderer.flush"),
            Column.scope("qrUploadMs", "quadRenderer.upload"),
            Column.scope("qrBindBufMs", "quadRenderer.bindBuffer"),
            Column.scope("qrDrawInstMs", "quadRenderer.drawInstanced"),
            Column.counter("qrFlushes", "quadRenderer.flush.count"),
            Column.sample("qrInstances", "quadRenderer.instances"),
            Column.scope("ssboMapMs", "streamBuffer.ssbo.map"),
            Column.scope("ssboWriteMs", "streamBuffer.ssbo.write"),
            Column.scope("ssboCommitMs", "streamBuffer.ssbo.commit"),
            // CgBatchRenderer is a different (non-text) path; kept for when it is exercised.
            Column.scope("batchFlushMs", "batch.flush"),
            Column.scope("batchMapMs", "batch.map"),
            Column.scope("batchCopyMs", "batch.copyToMapped"),
            Column.scope("batchCommitMs", "batch.commit"),
            Column.scope("batchBindVaoMs", "batch.bindVao"),
            Column.scope("batchRebindPtrMs", "batch.rebindPointers"),
            Column.scope("batchIndexBufMs", "batch.indexBuffer"),
            Column.scope("batchDrawElemMs", "batch.drawElements"),
            Column.scope("batchAfterSubmitMs", "batch.afterSubmit"),
            Column.scope("batchStagingResetMs", "batch.stagingReset"),
            Column.scope("glFlushMs", "glFlush"),

            // ── counts and cache behaviour ─────────────────────────────────────────────────
            Column.sample("glyphCount", "draw.glyphCount"),
            Column.counter("materialTransitions", "materialTransition"),
            Column.counter("batchFlushes", "batch.flush.count"),
            Column.sample("batchQuads", "batch.flush.quads"),
            Column.sample("batchBytes", "batch.flush.bytes"),
            Column.counter("layoutCacheHit", "layoutCache.hit"),
            Column.counter("layoutCacheMiss", "layoutCache.miss"),
            Column.counter("placementCacheHit", "placementCache.hit"),
            Column.counter("placementCacheMiss", "placementCache.miss"),
            Column.counter("freetypeRasterized", "glyph.bitmap.syncRasterized"),
            Column.scope("freetypeRasterizeMs", "freetype.rasterize"),
    };

    private static final String[] FIXED_HEADER = {"frame", "elapsedSec", "mode", "measured", "frameDtMs"};

    /** One "label": its text plus the retained shape/layout, mirroring what UIText holds. */
    private static final class Label {
        String text;
        CgShapedParagraph shaped;
        CgTextLayout layout;
        float x;
        float y;
    }

    private HarnessContext ctx;
    private CgFont font;
    private CgFontFamily family;
    private CgTextRenderer renderer;
    private final PoseStack poseStack = new PoseStack();
    private final List<Label> labels = new ArrayList<>(LABEL_COUNT);

    private Mode mode = Mode.STATIC;
    private double modeStartedAt = -1.0;
    private final List<Double> samples = new ArrayList<>();
    private final List<String> summary = new ArrayList<>();
    private final List<String> csvRows = new ArrayList<>();
    private boolean running = true;

    /** Late enough that the atlas has converged, early enough to land inside the run. */
    private static final double ASSET_RELOAD_AT_SECONDS = 7.0;
    private boolean assetReloadDone = false;

    @Override
    public void init(HarnessContext ctx) {
        this.ctx = ctx;
        CgProfiler.setEnabled(true);

        font = CgFont.load(HarnessFontUtil.LATIN_FONT, CgFontStyle.REGULAR, FONT_SIZE_PX);
        family = CgFontFamily.of(font);
        renderer = CgTextRenderer.createManualSized();

        for (int i = 0; i < LABEL_COUNT; i++) {
            Label label = new Label();
            label.text = textOfExactLength(i);
            label.shaped = CgTextLayout.of(label.text, family).shape();
            label.layout = label.shaped.layout(WRAP_WIDTH, 0f);
            // Grid placement only so drawing (when enabled) touches realistic screen positions.
            label.x = 8f + (i % 8) * 150f;
            label.y = 8f + (i / 8) * 14f;
            labels.add(label);
        }
        StringBuilder header = new StringBuilder(String.join(",", FIXED_HEADER));
        for (Column column : COLUMNS) header.append(',').append(column.header());
        csvRows.add(header.toString());
        CgGpuProfiler.enable();
        LOGGER.info("[text-stress] GPU timing " + (CgGpuProfiler.isAvailable()
                ? "enabled (GL_TIME_ELAPSED)" : "UNAVAILABLE on this context"));
        LOGGER.info("[text-stress] " + LABEL_COUNT + " labels x " + LABEL_CHARS
                + " chars, draw=" + DRAW_LABELS);
    }

    private static String textOfExactLength(int value) {
        String s = String.format(Locale.ROOT, "Item %04d Lorem ipsum dolor", value);
        return s.substring(0, LABEL_CHARS);
    }

    private static String sameLengthTextFor(int index, long frame) {
        return textOfExactLength((int) ((index + frame) % 10000));
    }

    private static String varyingLengthTextFor(int index, long frame) {
        int value = (int) ((index + frame) % 10000);
        int len = LABEL_CHARS - 8 + (int) ((index + frame) % 17); // 12..28 chars
        String s = String.format(Locale.ROOT, "Item %04d Lorem ipsum dolor sit amet", value);
        return s.substring(0, Math.min(len, s.length()));
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        double now = frame.getElapsedTime();
        if (modeStartedAt < 0) modeStartedAt = now;
        long f = frame.getFrameNumber();

        // Reshape: the direct analogue of UIText.setText -> shapedParagraph = null -> recompute().
        try (CgProfiler.Scope ignored = CgProfiler.scope("text.reshape")) {
            for (int i = 0; i < labels.size(); i++) {
                Label label = labels.get(i);
                String next = switch (mode) {
                    case STATIC -> label.text;
                    case SAME_LENGTH -> sameLengthTextFor(i, f);
                    case VARYING_LENGTH -> varyingLengthTextFor(i, f);
                };
                if (next.equals(label.text)) {
                    continue; // matches Property.set's equality suppression in the UI scene
                }
                label.text = next;
                label.shaped = CgTextLayout.of(next, family).shape();
                label.layout = label.shaped.layout(WRAP_WIDTH, 0f);
            }
        }

        if (DRAW_LABELS) {
            // GPU timing wraps the whole text draw. Results arrive some frames later (see
            // CgGpuProfiler), so this is a per-run average rather than a per-frame figure — which is
            // the only honest way to read it without stalling the pipeline being measured.
            CgGpuProfiler.begin("gpu.textDraw");
            try (CgProfiler.Scope ignored = CgProfiler.scope("text.draw")) {
                renderer.context(CgTextRenderContext.orthographic(ctx.getScreenWidth(), ctx.getScreenHeight()));
                renderer.beginBatch();
                for (int i = 0; i < labels.size(); i++) {
                    Label label = labels.get(i);
                    renderer.draw().layout(label.layout).font(font)
                            .at(label.x, label.y).color(0xFFFFFF)
                            .pose(poseStack).submit();
                }
                renderer.endBatch();
            }
            CgGpuProfiler.end();
        }
        CgGpuProfiler.endFrame();

        maybeMeasureAssetReload(now);

        double inMode = now - modeStartedAt;
        boolean measured = inMode >= WARMUP_SECONDS;

        CgProfilerReport report = CgProfiler.report();
        recordRow(frame, measured, report);
        if (measured) samples.add(frame.getDeltaTime() * 1000.0);
        if (inMode >= MODE_SECONDS) advanceMode();
        CgProfiler.reset();
    }

    /**
     * Fires one asset reload (the F3+T path) mid-run and reports what it cost.
     *
     * <p>Hot-reload drops and rebuilds every texture, shader and material on the render thread, so
     * it can only be measured with a live context — not from a unit test. Triggered once, late,
     * after the atlas has converged, so it neither perturbs the warmup numbers nor measures a
     * half-populated cache. One shot only: the interesting cost is rebuilding a warm set of
     * resources, and repeating it would just measure rebuilding what it already rebuilt.
     */
    private void maybeMeasureAssetReload(double now) {
        if (assetReloadDone || now < ASSET_RELOAD_AT_SECONDS) return;
        assetReloadDone = true;

        long start = System.nanoTime();
        CgAssetReloader.reload();
        double ms = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf(Locale.ROOT,
                "[text-stress] asset reload (F3+T path) took %.2f ms%n", ms);
    }

    private void advanceMode() {
        summary.add(formatRow(mode, samples));
        System.out.println("[text-stress] " + summary.get(summary.size() - 1));

        Mode[] all = Mode.values();
        int next = mode.ordinal() + 1;
        if (next >= all.length) {
            dump();
            running = false;
            return;
        }
        mode = all[next];
        modeStartedAt = -1.0;
        samples.clear();
    }

    private void recordRow(FrameInfo frame, boolean measured, CgProfilerReport report) {
        if (report == null) return;
        StringBuilder row = new StringBuilder(512);
        row.append(frame.getFrameNumber()).append(',')
                .append(String.format(Locale.ROOT, "%.3f", frame.getElapsedTime())).append(',')
                .append(mode.name()).append(',')
                .append(measured ? 1 : 0).append(',')
                .append(String.format(Locale.ROOT, "%.3f", frame.getDeltaTime() * 1000.0));
        for (Column column : COLUMNS) {
            row.append(',');
            switch (column.kind()) {
                case SCOPE -> row.append(String.format(Locale.ROOT, "%.3f", scope(report, column.profilerName())));
                case SAMPLE -> row.append(String.format(Locale.ROOT, "%.0f", sample(report, column.profilerName())));
                case COUNTER -> row.append(counter(report, column.profilerName()));
            }
        }
        csvRows.add(row.toString());
    }

    private static double scope(CgProfilerReport report, String name) {
        double total = 0;
        for (CgProfilerReport.ScopeEntry e : report.scopes()) {
            if (e.name().equals(name)) total += e.totalNanos() / 1_000_000.0;
        }
        return total;
    }

    private static double sample(CgProfilerReport report, String name) {
        var s = report.samples().get(name);
        return s == null ? 0 : s.last();
    }

    private static long counter(CgProfilerReport report, String name) {
        Long v = report.counters().get(name);
        return v == null ? 0L : v;
    }

    private static String formatRow(Mode mode, List<Double> samples) {
        if (samples.isEmpty()) return mode.name() + " — no measured frames";
        double[] sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double sum = 0;
        for (double v : sorted) sum += v;
        int n = sorted.length;
        return String.format(Locale.ROOT,
                "%-15s n=%-4d median %7.2f ms | mean %7.2f | p99 %7.2f | ~%5.1f fps",
                mode.name(), n, sorted[n / 2], sum / n, sorted[(int) (n * 0.99)], 1000.0 / sorted[n / 2]);
    }

    private void dump() {
        System.out.println();
        System.out.println("=== CrystalGraphics text stress: " + LABEL_COUNT + " labels x "
                + LABEL_CHARS + " chars, draw=" + DRAW_LABELS + " ===");
        for (String row : summary) System.out.println("  " + row);
        System.out.println();

        // GPU time, averaged over the whole run: results come back some frames after the work, so
        // per-frame attribution is not available without stalling the pipeline being measured.
        if (CgGpuProfiler.isAvailable()) {
            System.out.println("  --- GPU time (GL_TIME_ELAPSED, run average) ---");
            for (var e : CgGpuProfiler.report().entrySet()) {
                System.out.printf(Locale.ROOT, "  %-22s %8.3f ms/frame over %d samples%n",
                        e.getKey(), e.getValue().avgMillis(), e.getValue().samples());
            }
        } else {
            System.out.println("  --- GPU timing unavailable on this context ---");
        }
        System.out.println();

        File dir = new File(ctx.getOutputDir());
        if (!dir.isDirectory() && !dir.mkdirs()) {
            LOGGER.warning("[text-stress] could not create " + dir);
            return;
        }
        File csv = new File(dir, "text-stress-profile.csv");
        try (PrintWriter w = new PrintWriter(new FileWriter(csv))) {
            for (String row : csvRows) w.println(row);
            LOGGER.info("[text-stress] wrote " + csvRows.size() + " rows to " + csv.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.warning("[text-stress] failed writing CSV: " + e.getMessage());
        }
    }

    @Override
    public void dispose() {
        if (renderer != null) renderer.delete();
        renderer = null;
        labels.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return true;
    }
}
