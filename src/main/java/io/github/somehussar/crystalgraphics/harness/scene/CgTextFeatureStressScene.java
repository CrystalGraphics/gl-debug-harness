package io.github.somehussar.crystalgraphics.harness.scene;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontFamilyGroup;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.text.CgStyleSpan;
import com.crystalgraphics.text.richtext.CgMarkupParser;
import com.crystalgraphics.api.text.CgStyledText;
import com.crystalgraphics.api.text.CgTextDecoration;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgraphics.text.render.context.CgTextRenderContext;
import com.crystalgraphics.util.profiling.CgGpuProfiler;
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
import java.util.Set;
import java.util.logging.Logger;

/**
 * Benchmarks the text paths no other scene exercises.
 *
 * <p>{@code text-stress} and {@code cgui-text-stress} both draw plain, single-font, left-to-right
 * Latin. That covers the common case well and everything else not at all — so "the text stack is
 * fast" was only ever supported for one shape of workload. Every mode here isolates one path that
 * had no numbers whatsoever:
 *
 * <ul>
 *   <li>{@code LATIN_BASELINE} — the control. Same shape as text-stress, so every other mode can be
 *       read as a multiple of it rather than an unanchored absolute.</li>
 *   <li>{@code RTL_ARABIC} — right-to-left with real shaping (Arabic is cursive and contextual, so
 *       this exercises HarfBuzz far harder than Latin does) plus the BiDi algorithm.</li>
 *   <li>{@code BIDI_MIXED} — Arabic and Latin interleaved in one string, which forces actual
 *       bidirectional run splitting rather than a single uniform-direction paragraph.</li>
 *   <li>{@code FALLBACK_CHAIN} — codepoints deliberately spread across three fonts, so every run
 *       boundary is a fallback decision. Measures {@code CgFontFamily.resolveRuns} under load
 *       instead of the trivial single-font case.</li>
 *   <li>{@code SYNTHETIC_STYLE} — synthetic bold and italic, which transform the outline before
 *       rasterisation and therefore bypass any glyph already cached for the regular face.</li>
 *   <li>{@code DECORATIONS} — underline and strikethrough, which add geometry beyond the glyph
 *       quads and take a separate path through decoration resolution.</li>
 * </ul>
 *
 * <p>Each mode runs the same label count and character count as {@code text-stress} so the
 * comparison is apples to apples, and reshapes every frame so the cost being measured is the work
 * itself rather than a cache hit.
 */
public class CgTextFeatureStressScene implements InteractiveSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(CgTextFeatureStressScene.class.getName());

    /** Matches text-stress so numbers are directly comparable. */
    private static final int LABEL_COUNT = 1000;
    private static final int FONT_SIZE_PX = 12;
    private static final float WRAP_WIDTH = 70f;

    private static final double MODE_SECONDS = 3.0;
    private static final double WARMUP_SECONDS = 1.5;

    private enum Mode {
        LATIN_BASELINE("plain LTR Latin — the control"),
        RTL_ARABIC("right-to-left, cursive contextual shaping"),
        BIDI_MIXED("Arabic + Latin interleaved, real BiDi run splitting"),
        FALLBACK_CHAIN("codepoints spanning 3 fonts — fallback at every boundary"),
        SYNTHETIC_STYLE("synthetic bold + italic outline transforms"),
        DECORATIONS("underline + strikethrough geometry"),
        MARKUP_HTML("tag markup parsed per label (CgTagMarkupParser)"),
        MARKUP_MINECRAFT("MC color codes parsed per label (the common case in a MC mod)"),
        ELLIPSIS("maxLines truncation + ellipsis marker append");

        final String description;

        Mode(String description) {
            this.description = description;
        }
    }

    private HarnessContext ctx;
    private CgTextRenderer renderer;
    private final PoseStack poseStack = new PoseStack();

    private CgFont latinFont;
    private CgFont arabicFont;
    private CgFont cjkFont;
    private CgFontFamily latinOnly;
    private CgFontFamily arabicFirst;
    private CgFontFamily fallbackChain;
    private CgFontFamilyGroup latinGroup;

    private final List<CgTextLayout> layouts = new ArrayList<>();
    private Mode mode = Mode.LATIN_BASELINE;
    private double modeStartedAt = -1.0;
    private final List<Double> samples = new ArrayList<>();
    private final List<String> summary = new ArrayList<>();
    private final List<String> csvRows = new ArrayList<>();
    private boolean running = true;

    @Override
    public void init(HarnessContext context) {
        this.ctx = context;
        this.renderer = CgTextRenderer.create();

        latinFont = load(HarnessFontUtil.LATIN_FONT);
        arabicFont = load(HarnessFontUtil.ARABIC_FONT);
        cjkFont = load(HarnessFontUtil.JAPANESE_FONT);

        latinOnly = CgFontFamily.of(latinFont);
        arabicFirst = CgFontFamily.of(arabicFont, latinFont);
        // Order matters: Latin first means Arabic and CJK both miss it and fall through, so every
        // non-Latin codepoint costs a real fallback walk rather than resolving on the first try.
        fallbackChain = CgFontFamily.of(latinFont, arabicFont, cjkFont);
        latinGroup = CgFontFamilyGroup.ofRegular(latinOnly);

        CgProfiler.setEnabled(true);
        CgGpuProfiler.enable();
        csvRows.add("frame,elapsedSec,mode,measured,frameDtMs,reshapeMs,drawMs,"
                + "shapeRunsMs,shapeBidiMs,shapeResolveRunsMs,shapeHarfbuzzMs,hbShapeMs,"
                + "wrapBreakLinesMs,resolveGlyphsMs,submitQuadsMs,resolveDecorationsMs,glyphCount,"
                + "hbCreateMs,hbFillMs,hbReadBackMs,shapeCalls,runCount,"
                + "markupHtmlMs,markupMcMs,ellipsisMs,orthoResolves,perspResolves");
        LOGGER.info("[text-feature] " + LABEL_COUNT + " labels/mode, GPU timing "
                + (CgGpuProfiler.isAvailable() ? "on" : "unavailable"));
        rebuildLayouts();
    }

    private static CgFont load(String path) {
        return CgFont.load(path, CgFontStyle.REGULAR, FONT_SIZE_PX);
    }

    /**
     * Rebuilds every label for the current mode. Called on each mode switch, and again every frame
     * while measuring so nothing is served from a cache.
     */
    private void rebuildLayouts() {
        layouts.clear();
        for (int i = 0; i < LABEL_COUNT; i++) {
            layouts.add(buildLayout(i));
        }
    }

    private CgTextLayout buildLayout(int i) {
        return switch (mode) {
            case LATIN_BASELINE -> CgTextLayout.of(latinText(i), latinOnly).shape().layout(WRAP_WIDTH, 0f);
            case RTL_ARABIC -> CgTextLayout.of(arabicText(i), arabicFirst).shape().layout(WRAP_WIDTH, 0f);
            case BIDI_MIXED -> CgTextLayout.of(bidiText(i), arabicFirst).shape().layout(WRAP_WIDTH, 0f);
            case FALLBACK_CHAIN -> CgTextLayout.of(fallbackText(i), fallbackChain).shape().layout(WRAP_WIDTH, 0f);
            case SYNTHETIC_STYLE -> CgTextLayout.of(syntheticStyled(i), latinGroup).shape().layout(WRAP_WIDTH, 0f);
            case DECORATIONS -> CgTextLayout.of(decorated(i), latinGroup).shape().layout(WRAP_WIDTH, 0f);
            // markup(...) makes shape() run the parser first, so these measure parse + shape and
            // are read against LATIN_BASELINE (same text, no parse) to isolate the parser.
            case MARKUP_HTML -> CgTextLayout.of(htmlMarkup(i), latinGroup)
                    .markup(CgMarkupParser.HTML).shape().layout(WRAP_WIDTH, 0f);
            case MARKUP_MINECRAFT -> CgTextLayout.of(minecraftMarkup(i), latinGroup)
                    .markup(CgMarkupParser.MINECRAFT).shape().layout(WRAP_WIDTH, 0f);
            // maxLines below the natural line count forces truncation, so the ellipsis path
            // actually runs -- otherwise shape.ellipsis measures an untaken branch.
            case ELLIPSIS -> CgTextLayout.of(latinText(i), latinOnly)
                    .maxLines(1).ellipsis("…").shape().layout(WRAP_WIDTH, 0f);
        };
    }

    // ── Text generators. Each varies with i and frame so no two labels share a cache entry. ──

    private static String latinText(int i) {
        return String.format(Locale.ROOT, "Item %04d Lorem ip", i % 10000);
    }

    /** Arabic: cursive, contextual joining — every glyph's form depends on its neighbours. */
    private static String arabicText(int i) {
        String base = "مرحبا بالعالم النص";
        return base + " " + (i % 10000);
    }

    /** Arabic and Latin interleaved, so the paragraph genuinely splits into opposing runs. */
    private static String bidiText(int i) {
        return "Item " + (i % 1000) + " مرحبا world النص " + (i % 100);
    }

    /** Latin + Arabic + CJK in one string — three fonts, so run resolution can never short-circuit. */
    private static String fallbackText(int i) {
        return "Ab" + (i % 100) + " مر 日本語 cd";
    }

    /** Tag markup: several spans per label so the parser does real nesting work. */
    private static String htmlMarkup(int i) {
        return "<b>Item</b> <i>" + (i % 10000) + "</i> <u>Lorem</u> ip";
    }

    /** MC-style color codes — the realistic case: every chat line in a Minecraft mod hits this. */
    private static String minecraftMarkup(int i) {
        return "§aItem §e" + (i % 10000) + " §bLorem §fip";
    }

    private static CgStyledText syntheticStyled(int i) {
        String text = latinText(i);
        List<CgStyleSpan> spans = new ArrayList<>();
        spans.add(new CgStyleSpan(0, 5, true, false, Set.of(), 0xFFFFFFFF, null, null, 0f));
        spans.add(new CgStyleSpan(5, 10, false, true, Set.of(), 0xFFFFFFFF, null, null, 0f));
        spans.add(new CgStyleSpan(10, text.length(), true, true, Set.of(), 0xFFFFFFFF, null, null, 0f));
        return new CgStyledText(text, spans);
    }

    private static CgStyledText decorated(int i) {
        String text = latinText(i);
        List<CgStyleSpan> spans = new ArrayList<>();
        spans.add(new CgStyleSpan(0, 8, false, false,
                Set.of(CgTextDecoration.UNDERLINE), 0xFFFFFFFF, null, null, 0f));
        spans.add(new CgStyleSpan(8, text.length(), false, false,
                Set.of(CgTextDecoration.STRIKETHROUGH), 0xFFFFFFFF, null, null, 0f));
        return new CgStyledText(text, spans);
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

    @Override
    public void render(HarnessContext context, FrameInfo frame) {
        double now = frame.getElapsedTime();
        if (modeStartedAt < 0) modeStartedAt = now;

        try (CgProfiler.Scope ignored = CgProfiler.scope("text.reshape")) {
            rebuildLayouts();
        }

        CgGpuProfiler.begin("gpu." + mode.name());
        try (CgProfiler.Scope ignored = CgProfiler.scope("text.draw")) {
            renderer.context(CgTextRenderContext.orthographic(
                    context.getScreenWidth(), context.getScreenHeight()));
            renderer.beginBatch();
            for (int i = 0; i < layouts.size(); i++) {
                renderer.draw().layout(layouts.get(i)).font(latinFont)
                        .at(8f + (i % 8) * 90f, 8f + (i / 8) * 14f)
                        .color(0xFFFFFF).pose(poseStack).submit();
            }
            renderer.endBatch();
        }
        CgGpuProfiler.end();
        CgGpuProfiler.endFrame();

        double inMode = now - modeStartedAt;
        boolean measured = inMode >= WARMUP_SECONDS;
        recordRow(frame, measured, CgProfiler.report());
        if (measured) samples.add(frame.getDeltaTime() * 1000.0);
        if (inMode >= MODE_SECONDS) advanceMode();
        CgProfiler.reset();
    }

    private void advanceMode() {
        summary.add(formatRow(mode, samples));
        System.out.println("[text-feature] " + summary.get(summary.size() - 1));

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
        rebuildLayouts();
    }

    private void recordRow(FrameInfo frame, boolean measured, CgProfilerReport report) {
        if (report == null) return;
        csvRows.add(String.format(Locale.ROOT,
                "%d,%.3f,%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.0f,"
                        + "%.3f,%.3f,%.3f,%d,%d,"
                        + "%.3f,%.3f,%.3f,%d,%d",
                frame.getFrameNumber(), frame.getElapsedTime(), mode.name(), measured ? 1 : 0,
                frame.getDeltaTime() * 1000.0,
                scope(report, "text.reshape"), scope(report, "text.draw"),
                scope(report, "shape.runs"), scope(report, "shape.bidi"),
                scope(report, "shape.resolveRuns"), scope(report, "shape.harfbuzz"),
                scope(report, "hb.shape"), scope(report, "wrap.breakLines"),
                scope(report, "resolveGlyphs"), scope(report, "submitSortedQuads"),
                scope(report, "resolveDecorations"),
                sample(report, "draw.glyphCount"),
                scope(report, "hb.bufferCreate"),
                scope(report, "hb.bufferFill"),
                scope(report, "hb.readBack"),
                calls(report, "hb.shape"),
                calls(report, "shape.harfbuzz"),
                scope(report, "markup.parseHtml"),
                scope(report, "markup.parseMinecraft"),
                scope(report, "shape.ellipsis"),
                counter(report, "scaleResolver.ortho"),
                counter(report, "scaleResolver.perspective")));
    }

    private static long counter(CgProfilerReport report, String name) {
        Long v = report.counters().get(name);
        return v == null ? 0L : v;
    }

    private static double scope(CgProfilerReport report, String name) {
        double total = 0;
        for (CgProfilerReport.ScopeEntry e : report.scopes()) {
            if (e.name().equals(name)) total += e.totalNanos() / 1_000_000.0;
        }
        return total;
    }

    /** Call count for a scope — how many times it ran, as opposed to how long it took. */
    private static long calls(CgProfilerReport report, String name) {
        long total = 0;
        for (CgProfilerReport.ScopeEntry e : report.scopes()) {
            if (e.name().equals(name)) total += e.callCount();
        }
        return total;
    }

    private static double sample(CgProfilerReport report, String name) {
        var s = report.samples().get(name);
        return s == null ? 0 : s.last();
    }

    private static String formatRow(Mode mode, List<Double> samples) {
        if (samples.isEmpty()) return String.format(Locale.ROOT, "%-16s no samples", mode.name());
        List<Double> sorted = new ArrayList<>(samples);
        sorted.sort(Double::compare);
        double median = sorted.get(sorted.size() / 2);
        double sum = 0;
        for (double d : sorted) sum += d;
        return String.format(Locale.ROOT, "%-16s n=%-4d median %7.2f ms | mean %7.2f | ~%5.1f fps  (%s)",
                mode.name(), sorted.size(), median, sum / sorted.size(), 1000.0 / median, mode.description);
    }

    private void dump() {
        System.out.println();
        System.out.println("=== text feature stress: " + LABEL_COUNT + " labels/mode ===");
        for (String row : summary) System.out.println("  " + row);
        if (CgGpuProfiler.isAvailable()) {
            System.out.println("  --- GPU time (run average) ---");
            for (var e : CgGpuProfiler.report().entrySet()) {
                System.out.printf(Locale.ROOT, "  %-24s %8.3f ms/frame over %d samples%n",
                        e.getKey(), e.getValue().avgMillis(), e.getValue().samples());
            }
        }
        System.out.println();

        File dir = new File(ctx.getOutputDir());
        if (!dir.isDirectory() && !dir.mkdirs()) {
            LOGGER.warning("[text-feature] could not create " + dir);
            return;
        }
        File csv = new File(dir, "text-feature-profile.csv");
        try (PrintWriter pw = new PrintWriter(new FileWriter(csv))) {
            for (String row : csvRows) pw.println(row);
            LOGGER.info("[text-feature] wrote " + (csvRows.size() - 1) + " rows to " + csv.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.warning("[text-feature] failed to write csv: " + e);
        }
    }

    @Override
    public void dispose() {
        if (renderer != null) renderer.delete();
        CgGpuProfiler.dispose();
        for (CgFont f : new CgFont[]{latinFont, arabicFont, cjkFont}) {
            if (f != null) f.dispose();
        }
    }
}
