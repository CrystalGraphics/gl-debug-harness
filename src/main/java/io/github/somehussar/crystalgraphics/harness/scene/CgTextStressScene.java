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

    private static final String[] CSV_HEADER = {
            "frame", "elapsedSec", "mode", "measured", "frameDtMs",
            "reshapeMs", "drawMs",
            "shapeRunsMs", "shapeBidiMs", "shapeCollectMs", "shapeResolveRunsMs", "shapeHarfbuzzMs",
            "hbCreateMs", "hbFillMs", "hbShapeMs", "hbReadBackMs", "hbDestroyMs",
            "shapeReshaperMs", "shapeEllipsisMs",
            "wrapBreakLinesMs", "wrapBoundariesMs", "wrapJustifyMs", "wrapBakeMs",
            "resolveGlyphsMs", "submitQuadsMs", "quadLoopMs", "sortKeysMs",
            "matDoBindMs", "dbStateSaveMs", "glyphCount", "materialTransitions"
    };

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
        csvRows.add(String.join(",", CSV_HEADER));
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
        }

        double inMode = now - modeStartedAt;
        boolean measured = inMode >= WARMUP_SECONDS;

        CgProfilerReport report = CgProfiler.report();
        recordRow(frame, measured, report);
        if (measured) samples.add(frame.getDeltaTime() * 1000.0);
        if (inMode >= MODE_SECONDS) advanceMode();
        CgProfiler.reset();
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
        csvRows.add(String.format(Locale.ROOT,
                "%d,%.3f,%s,%d,%.3f,%.3f,%.3f,"
                        + "%.3f,%.3f,%.3f,%.3f,%.3f,"
                        + "%.3f,%.3f,%.3f,%.3f,%.3f,"
                        + "%.3f,%.3f,"
                        + "%.3f,%.3f,%.3f,%.3f,"
                        + "%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.0f,%d",
                frame.getFrameNumber(), frame.getElapsedTime(), mode.name(), measured ? 1 : 0,
                frame.getDeltaTime() * 1000.0,
                scope(report, "text.reshape"), scope(report, "text.draw"),
                scope(report, "shape.runs"), scope(report, "shape.bidi"),
                scope(report, "shape.collectRuns"), scope(report, "shape.resolveRuns"),
                scope(report, "shape.harfbuzz"),
                scope(report, "hb.bufferCreate"), scope(report, "hb.bufferFill"),
                scope(report, "hb.shape"), scope(report, "hb.readBack"),
                scope(report, "hb.bufferDestroy"),
                scope(report, "shape.reshaper"), scope(report, "shape.ellipsis"),
                scope(report, "wrap.breakLines"), scope(report, "wrap.breakBoundaries"),
                scope(report, "wrap.justify"), scope(report, "wrap.bakeGlyphs"),
                scope(report, "resolveGlyphs"), scope(report, "submitBatchedQuads"),
                scope(report, "quadLoop"), scope(report, "sortKeys"),
                scope(report, "material.doBind"), scope(report, "doBind.stateSave"),
                sample(report, "draw.glyphCount"), counter(report, "materialTransition")));
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
