package io.github.somehussar.crystalgraphics.harness.scene;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.*;
import com.crystalgraphics.api.text.*;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.text.render.CgTextRenderContext;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgraphics.text.richtext.CgMarkupParser;
import com.crystalgraphics.util.profiling.CgProfiler;
import com.crystalgraphics.util.profiling.CgProfilerReport;
import com.crystalgui.core.input.SystemInput;
import com.crystalgui.core.input.keyboard.CgUiKeyCodes;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.config.TextSceneConfig;
import io.github.somehussar.crystalgraphics.harness.tool.AtlasDumper;
import io.github.somehussar.crystalgraphics.harness.util.HarnessFontUtil;
import io.github.somehussar.crystalgraphics.harness.util.WorldTextRenderHelper;
import lombok.Getter;
import lombok.Setter;
import org.joml.Matrix4f;

import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Interactive 3D world text scene for the {@code text-3d} mode.
 *
 * <p>Provides a continuous render loop with first-person camera controls
 * for inspecting world-space text from any angle. The scene includes:
 * floor plane, HUD overlay, pause support, and scheduled screenshot
 * capture for automated validation.</p>
 *
 * <h3>Output</h3>
 * <ul>
 *   <li>{@code harness-output/text-3d/{name}-normal.png}</li>
 *   <li>{@code harness-output/text-3d/{name}-paused.png}</li>
 *   <li>{@code harness-output/text-3d/{name}-topdown.png}</li>
 * </ul>
 *
 * <h3>Validation sequence</h3>
 * The scene schedules three automated screenshots:
 * <ol>
 *   <li><b>normal</b> (t=1.0s) — Front view: floor, text, HUD visible</li>
 *   <li><b>paused</b> (t=1.5s) — Same view with pause overlay at bottom</li>
 *   <li><b>topdown</b> (t=2.0s) — Camera looking straight down at floor + text</li>
 * </ol>
 *
 * <p>All shared rendering logic (font loading, layout construction, GL cap
 * validation, world-space text drawing) is delegated to
 * {@link WorldTextRenderHelper}. This class only owns the interactive
 * lifecycle, camera setup, and screenshot scheduling.</p>
 *
 * @see WorldTextRenderHelper
 */
public class TextScene3D implements InteractiveSceneLifecycle, SystemInput.Mouse, SystemInput.Keyboard {

    private static final Logger LOGGER = Logger.getLogger(TextScene3D.class.getName());
    
    // ── Interactive mode state ──
    @Getter
    private boolean running = true;
    @Setter
    private boolean shutdownOnComplete = false;

    private HarnessContext ctx;
    private CgFont kanjiFont;
    private CgTextLayout kanjiWorldLayout;
    private int kanjiFontSizePx;

    // ── CgProfiler instrumentation for the kanji world-text draw call (see class javadoc's
    // "Kanji warmup profiling" section) — scoped strictly to this one draw call and everything
    // it calls into; nothing else in this scene is instrumented. ──
    private static final double PROFILE_WINDOW_SECONDS = 5;

    /**
     * {@code -Dcrystalgraphics.profile.autoOrbit=true} slowly dollies the camera during the
     * profile window. Not cosmetic: a moving camera makes
     * {@code PerspectiveScaleResolver#updateProjectedSize} recompute {@code projectedSizeHint}
     * every frame, which shifts {@code effectiveTargetPx}, which makes
     * {@code CgGlyphPlacementCache.Entry#matches} reject the cached entry (an exact
     * {@code effectiveTargetPx} match is required whenever {@code distanceField} is false — the
     * entire MSDF warmup). That turns every frame into a full re-resolve of the whole layout,
     * which is the path a static-camera profiling run never exercises at all.
     */
    private static final boolean PROFILE_AUTO_ORBIT =
            Boolean.getBoolean("crystalgraphics.profile.autoOrbit");
    private final List<String> profileCsvRows = new ArrayList<>();
    private final List<String> profileTreeDumps = new ArrayList<>();
    /** Whole-second mark of the last captured tree dump, so one lands per second of the window. */
    private int lastTreeDumpSecond = -1;

    /**
     * Running totals from the JVM's garbage collectors, sampled once per frame so the CSV can
     * carry a per-frame delta.
     *
     * <p>Exists to settle a hypothesis rather than to optimise anything: several warmup frames
     * cost 20-30 ms with <em>every</em> {@code CgProfiler} scope reading ~0, which is the
     * signature of time being spent outside any instrumented code. A GC pause is the obvious
     * candidate, but "obvious candidate" is exactly what was wrong twice already this session
     * (the half-float and shader-compile hypotheses), so this measures it directly: if a slow
     * frame coincides with a collection-count increment, it is GC; if collections are flat
     * across those frames, it is not and the search continues elsewhere.</p>
     */
    private long lastGcCount = -1;
    private long lastGcTimeMs = -1;
    private long frameGcCountDelta;
    private long frameGcTimeDelta;

    /** Samples cumulative GC counters and updates this frame's deltas. */
    private void sampleGc() {
        long count = 0;
        long timeMs = 0;
        for (java.lang.management.GarbageCollectorMXBean gc
                : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = gc.getCollectionCount();
            long t = gc.getCollectionTime();
            if (c > 0) count += c;
            if (t > 0) timeMs += t;
        }
        frameGcCountDelta = lastGcCount < 0 ? 0 : count - lastGcCount;
        frameGcTimeDelta = lastGcTimeMs < 0 ? 0 : timeMs - lastGcTimeMs;
        lastGcCount = count;
        lastGcTimeMs = timeMs;
    }

    @Override
    public void init(HarnessContext ctx) {
        this.ctx = ctx;
        Camera3D camera = ctx.getCamera3D();
        camera.moveCamera(0, 0.1f, 0);

        CgProfiler.setEnabled(true);

        // Typed config is resolved before execution and available via context.
        // For interactive scenes, the config is set on ctx before init() is called.
        TextSceneConfig config = (TextSceneConfig) ctx.getSceneConfig();
        String fontPath = HarnessFontUtil.resolveFontPath(config.getFontPath());
        int fontSizePx = config.getFontSizePx();
        int layoutWidth = config.getWidth();

        LOGGER.info("[Harness] World text scene (interactive): font=" + fontPath);
        LOGGER.info("[Harness] World text scene (interactive): size=" + fontSizePx + "px" + ", mtsdf=" + config.isMtsdf());
        
        //jp  "さあ 剽悍な双眸を エーカム そうさ 先頭に e"
        kanjiFontSizePx = fontSizePx;
        kanjiFont = CgFont.load(HarnessFontUtil.JAPANESE_FONT, CgFontStyle.REGULAR, fontSizePx);
        kanjiWorldLayout = CgTextLayout.of(config.kanji, kanjiFont).maxWidth((float) layoutWidth).build();

        // STYLES
        String latinPath = HarnessFontUtil.LATIN_FONT;
        String arabicPath = HarnessFontUtil.ARABIC_FONT;
        String minecraftPath = HarnessFontUtil.MINECRAFT_FONT;
        LOGGER.info("[Harness] Text showcase: latin=" + latinPath + ", arabic=" + arabicPath);

        latinRegular = CgFont.load(latinPath, CgFontStyle.REGULAR, FONT_SIZE_PX);
        CgFont arabicRegular = CgFont.load(arabicPath, CgFontStyle.REGULAR, FONT_SIZE_PX);
        labelFont = CgFont.load(latinPath, CgFontStyle.REGULAR, LABEL_FONT_SIZE_PX);
        minecraftFont = CgFont.load(minecraftPath, CgFontStyle.REGULAR, FONT_SIZE_PX);

        // Latin-primary family with Arabic as a fallback source, so mixed Latin+Arabic
        // text in one paragraph automatically resolves the right font per codepoint
        // (CgFontFamily#resolveSourceForCodePoint) -- no manual text splitting needed.
        //
        // No distinct BOLD/ITALIC font files exist for this demo -- CgFontFamilyGroup.ofRegular
        // means every requested style falls back to REGULAR, which is exactly what triggers
        // CgShapedRun's synthetic bold/italic flags (see CgTextLayoutEngine#applyStyle) so the
        // rasterizer fakes them via FTFace.outlineEmbolden/outlineShear (bitmap tier) or
        // MSDFShapeSynthesis (MSDF tier), instead of the old same-file-different-size hack that
        // caused the mixed-baseTargetPx letter-spacing bug.
        CgFontFamily regularFamily = CgFontFamily.of(latinRegular, arabicRegular);
        CgFontFamilyGroup group = CgFontFamilyGroup.ofRegular(regularFamily);
        
        CgFontFamily mcFamily = CgFontFamily.of(minecraftFont);
        minecraftGroup = CgFontFamilyGroup.ofRegular(mcFamily);

        wrapWidth = 1000 - 2 * MARGIN;
        
        renderer = CgTextRenderer.create();
        orthoContext = renderer.context();
        perspectiveContext = CgTextRenderContext.world(ctx.getProjection(), ctx.getScreenWidth(),
                ctx.getScreenHeight());
        
        sections = buildSections(regularFamily, group, wrapWidth);
        LOGGER.info("[Harness] World text scene (interactive) initialized.");
    }

    private static final int FONT_SIZE_PX = 22;
    private static final int LABEL_FONT_SIZE_PX = 14;
    private static final int MARGIN = 20;
    private static final int SECTION_GAP = 24;
    private static final int LABEL_TO_BODY_GAP = 6;

    private static final int LABEL_COLOR = 0xFFF985C7;
    private static final int BODY_COLOR = 0xFFFFFFFF;

    // ── World-space (3D/perspective) mode constants ──
    // The sections are laid out in the exact same local logical-pixel space (MARGIN, y-stacking,
    // wrapWidth) regardless of mode -- only the PoseStack differs. In world mode, that local
    // space is placed in front of the camera via: view matrix -> translate to WORLD_ORIGIN_* ->
    // scale down by WORLD_SCALE (with a Y-flip, since layout Y is screen-down but world Y is up).
    /** Local logical px -> world units. Smaller = the whole text block appears smaller/farther. */
    private static final float WORLD_SCALE = 0.01f;
    /** World-space X offset of the local origin (MARGIN, 0) -- 0 centers nothing; see renderWorldSections. */
    private static final float WORLD_ORIGIN_X = 0f;
    /** World-space Y offset (height above the floor) of the local origin. */
    private static final float WORLD_ORIGIN_Y = 1.5f;
    /** World-space Z offset (negative = in front of the camera at yaw 0). */
    private static final float WORLD_ORIGIN_Z = -1f;

    CgFont labelFont;
    CgFont latinRegular;
    CgFont minecraftFont;
    CgFontFamilyGroup minecraftGroup;

    List<Section> sections;
    CgTextRenderer renderer;
    int wrapWidth;
    
    /** Flip to switch the sections' render path between orthographic (2D/UI) and world (3D/perspective). */
    boolean renderSectionsInWorldSpace = false;
    CgTextRenderContext perspectiveContext, orthoContext;

    float scrollDelta;
    float scrollScale = 1;

    public static String getArialPrintableChars() {
        // Initialize the Arial font
        Font arial = new Font("Arial", Font.PLAIN, 12);
        StringBuilder arialChars = new StringBuilder();

        // Loop through the Basic Multilingual Plane (BMP) starting past control codes
        for (int i = 32; i <= Character.MAX_VALUE; i++) {
            // Skip control characters and surrogates
            if (Character.isISOControl(i) || Character.isSurrogate((char) i)) {
                continue;
            }

            char c = (char) i;
            // Verify if Arial can display this specific character
            if (arial.canDisplay(c)) {
                arialChars.append(c);
            }
        }

        return arialChars.toString();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // Build view matrix from camera
        Matrix4f viewMatrix = this.ctx.getCamera3D().getViewMatrix();

        // Delegate all world-text rendering to the shared helper.
        // The helper handles perspective projection, model-view setup,
        // text positioning, and world-text draw() with correct winding order.

        float worldScale = 0.0005f;
        float textWorldWidth = kanjiWorldLayout.totalWidth() * worldScale;
        
        PoseStack poseStack = new PoseStack();
        Matrix4f modelView = poseStack.last().pose();
        modelView.set(viewMatrix);
        modelView.translate(-textWorldWidth * 0.5f, 1.5f, -0.2f);
        modelView.scale(worldScale, -worldScale, worldScale);

        try (CgProfiler.Scope ignored = CgProfiler.scope("kanjiWorldDraw")) {
            perspectiveContext.projection(ctx.getProjection()).updateProjectedSize(modelView, ctx.getProjection(), kanjiFontSizePx);
            renderer.context(perspectiveContext);

           // renderer.draw().layout(kanjiWorldLayout).at(0.0f, 0.0f).pose(poseStack).submit();
            renderer.draw().text(getArialPrintableChars()).at(0.0f, 2112.0f).constraints(500,0).font(labelFont).pose(poseStack).submit();
        }

        if (CgProfiler.isEnabled()) {
            // Dolly forward/back a little each frame so the projected-size hint (and hence
            // effectiveTargetPx) actually moves -- see PROFILE_AUTO_ORBIT's javadoc.
            if (PROFILE_AUTO_ORBIT) {
                float dz = (float) Math.sin(frame.getElapsedTime() * 0.7) * 0.01f;
                //ctx.getCamera3D().moveCamera(0f, 0f, dz);
            }

            sampleGc();
            int pendingAsync = CgFontRegistry.get().getPendingAsyncGlyphCount();
            CgProfiler.sample("async.pendingGlyphs", pendingAsync);
            recordProfileFrame(frame, CgProfiler.endFrame(), pendingAsync);

            if (frame.getElapsedTime() >= PROFILE_WINDOW_SECONDS) {
                dumpProfile(ctx);
                // Dump the atlases alongside the profile so packing efficiency is captured from
                // the same converged state the timings describe, rather than needing the '['
                // keybind pressed by hand at an arbitrary moment (which makes before/after
                // packing comparisons non-reproducible).
                dumpKanjiFontAtlas();
                CgProfiler.setEnabled(false); // fully zero-cost from here on -- see CgProfiler's javadoc
                // running = false;
            }
        }

        //////////////////////////////////////////////////////////
        /////////////////////////////////////////////////////////

        //Render rich-format text paragraphs
        if (false) {
            PoseStack pose = new PoseStack();
            renderSectionsInWorldSpace = false;
            if (renderSectionsInWorldSpace) {
                // World mode: same local logical-pixel layout (MARGIN, y-stacking) as ortho, just
                // placed in front of the camera instead of pinned to the screen. Order matters:
                // start from the camera's view matrix, translate to where the block should sit in
                // world space, then scale local px down to world units -- WORLD_SCALE is negated on
                // Y because layout Y grows downward (screen convention) but world Y grows upward.
                renderer.context(perspectiveContext);
                Matrix4f mv = pose.last().pose();
                mv.set(viewMatrix);
                mv.translate(WORLD_ORIGIN_X, WORLD_ORIGIN_Y, WORLD_ORIGIN_Z);
                mv.scale(WORLD_SCALE, -WORLD_SCALE, WORLD_SCALE);
            } else {
                renderer.context(orthoContext);
                pose.scale(scrollScale, scrollScale, 1);
            }
            renderer.beginBatch();
            // y (and MARGIN below) are tracked in DESIGN-SPACE screen pixels -- the same fixed,
            // scale-invariant convention wrapWidth already uses via .constraints() -- NOT local
            // pre-transform coordinates. .at() divides by scrollScale right before submitting so
            // that, once `pose`'s scale multiplies it back out, the glyph lands at the intended
            // fixed screen position instead of drifting with zoom. Without this, MARGIN (and y)
            // were passed straight to .at() as local coordinates, so they got scaled UP by the
            // same pose as everything else -- meaning the pen origin visibly drifted to the right
            // (and down) as scrollScale grew, while wrapWidth's box stayed pinned at a fixed
            // screen width, so the two increasingly disagreed on where the box's left edge/column
            // actually was (invisible at scale 1, where MARGIN*1 == MARGIN, growing linearly with
            // scale beyond that -- exactly the drift observed at higher scales).
            float y = MARGIN + 20;
            for (Section s : sections) {
                if (s.label != null) {
                    // .text(...)+.constraints(...), NOT a prebuilt .layout(...) -- a prebuilt
                    // CgTextLayout is drawn verbatim and ignores .constraints() entirely (see
                    // Draw's "layout vs. paragraph/text" field-priority rules), which is why
                    // constraints(wrapWidth, 0) previously did nothing here. Going through
                    // .text() lets CgTextRenderer build (and cache, via CgTextLayoutCache) the
                    // layout itself, applying the same scale-aware constraint division .paragraph()
                    // gets below.
                    CgTextRenderer.Draw labelDraw = renderer.draw().text(s.label).constraints(wrapWidth, 0)
                            .font(labelFont).pose(pose);
                    float labelHeightDesign = labelDraw.measure().totalHeight() * scrollScale;
                    labelDraw.at(MARGIN / scrollScale, y / scrollScale).color(LABEL_COLOR).submit();
                    // Same *scrollScale conversion as sectionHeightDesign below -- now that the
                    // label can wrap to multiple lines, its measured height (not a fixed
                    // single-line constant) has to drive the advance, same reasoning as the body.
                    y += labelHeightDesign + LABEL_TO_BODY_GAP * scrollScale;
                }

                // .paragraph(...) (instead of a frozen .layout(...)) re-wraps against the current
                // PoseStack scale every draw -- see CgTextRenderer.Draw's "layout vs. paragraph"
                // javadoc -- so wrapWidth keeps meaning "960 on-screen pixels" under any scrollScale
                // instead of silently growing in screen space like a prebuilt CgTextLayout would.
                // At higher scale the effective (screen-space-constant) wrap width is narrower in
                // local units, so the paragraph needs MORE lines -- its local height is NOT the
                // scale==1 height, it grows with scale too. .measure() asks the renderer for the
                // real height it's about to draw at (same resolution submit() uses, memoized, so
                // this costs nothing extra) instead of assuming a fixed unscaled height, which is
                // what caused sections to overlap at scrollScale > 1.
                //
                // measure() returns the resolved layout's height in LOCAL units (it was wrapped
                // against the already-scale-divided effective width), so it has to be multiplied
                // back up by scrollScale before accumulating into the design-space y tracker --
                // otherwise y would be a mix of design-space (MARGIN/SECTION_GAP) and local-space
                // (sectionHeight) units.
                CgTextRenderer.Draw bodyDraw = renderer.draw().paragraph(s.paragraph).constraints(wrapWidth, 0)
                                                       .font(latinRegular).pose(pose);
                float sectionHeightDesign = bodyDraw.measure().totalHeight() * scrollScale;
                bodyDraw.at(MARGIN / scrollScale, y / scrollScale).color(BODY_COLOR).submit();
                y += sectionHeightDesign + SECTION_GAP;
            }

            drawWrapWidthGuide(pose, y);

            pose = new PoseStack();
            pose.scale(2, 2, 1);
            renderer.draw().text(String.format("Scale: %.1fx ", scrollScale)).font(labelFont).at(10, 0).pose(pose).submit();

            renderer.endBatch();
        }
    }

    /**
     * Draws a thin vertical guide line at design-space X = MARGIN + wrapWidth, down to
     * {@code designHeight} -- i.e. exactly where the paragraphs above wrap. Reuses the exact
     * same quad batch/atlas white-texel machinery {@code CgTextRenderer} already uses for
     * decoration lines (underline/strikethrough/overline) -- no new CgQuadRenderer, no new
     * rendering code at all. A {@link CgTextDecorationRect} is normally a horizontal bar
     * (x0/x1 span its width, thickness is its height); swapping which axis is "thin" turns the
     * same primitive into a vertical line for free. Wrapped in a single-line, zero-glyph
     * {@link CgTextLayout} since {@code CgTextRenderer} only looks at decorations once
     * {@code lines()} is non-empty.
     *
     * <p>{@code pose} must be the SAME scaled pose the sections above were drawn with (not a
     * fresh/identity one) -- every coordinate here is divided by {@code scrollScale} before
     * being handed to the decoration rect (which is local-space, like a glyph pen position), so
     * that once {@code pose} multiplies it back out, the line lands at its intended fixed
     * design-space screen position, exactly like the sections' own {@code .at()} calls above.</p>
     */
    private void drawWrapWidthGuide(PoseStack pose, float designHeight) {
        float x = (MARGIN + wrapWidth) / scrollScale;
        float thicknessPx = 2f / scrollScale;
        float height = designHeight / scrollScale;
        CgFontKey fontKey = latinRegular.getKey();
        CgTextDecorationRect guide = new CgTextDecorationRect(x, x + thicknessPx, height / 2f, height, 0xFF00FF00, fontKey);
        CgBakedGlyphs baked = new CgBakedGlyphs(
                0, new CgFontKey[0], new CgFont[0], new int[0],
                new float[0], new float[0], new float[0], new int[0], new boolean[0],
                new float[]{0f}, new int[]{0, 0}, new CgTextDecorationRect[]{guide},
                new boolean[0], new boolean[0]);
        CgTextLayout guideLayout = new CgTextLayout(List.of(List.of()), 0f, 0f, latinRegular.getMetrics(), baked);
        renderer.draw().layout(guideLayout).font(latinRegular).at(0f, 0f).color(0xFF00FF00).pose(pose).submit();
    }

    public void dispose() {}

    public boolean shouldShutdownOnComplete() {return shutdownOnComplete;}

    public void requestStop() {running = false;}

    public boolean uses3DCamera() {return true;}

    @Override
    public boolean consumeMouseEvent(SystemInput.Mouse.Event event) {
        scrollDelta = event.wheelDelta();
         if (scrollDelta > 0) scrollScale -= 0.1f;
         else if (scrollDelta < 0) scrollScale += 0.1f;
        if (event.button() == 1) scrollScale = 1;
        return false;
    }

    @Override
    public boolean consumeKeyboardEvent(SystemInput.Keyboard.Event event) {
        if (event.pressed() && !event.repeat() && event.key() == CgUiKeyCodes.KEY_LBRACKET) 
            dumpKanjiFontAtlas();
        return true;
    }

    /**
     * Dumps every populated atlas page belonging to {@code jpHelper}'s kanji font via
     * {@link AtlasDumper#dumpFontAtlas}. Bound to {@code [} — see {@link #consumeKeyboardEvent}.
     */
    private void dumpKanjiFontAtlas() {
        File harnessOutputRoot = new File(ctx.getOutputDir()).getParentFile();
        AtlasDumper.dumpFontAtlas(labelFont, harnessOutputRoot.getPath());
    }

    /**
     Rich-text pipeline showcase: paragraphs, HTML markup, MC codes, alignment/ellipsis, Arabic RTL, 
     and a combination -- to text-showcase.png
     */
    public List<Section> buildSections(CgFontFamily regularFamily, CgFontFamilyGroup group, int wrapWidth) {
        List<Section> sections = new ArrayList<>();

        sections.add(section("1) Plain paragraph -- multi-line wrap, no markup",
                CgTextLayout.of(
                        "This is a plain paragraph with no markup at all: just ordinary text "
                                + "wrapped across several lines at a fixed width, exactly like "
                                + "Draw.text(String) has always worked.",
                        regularFamily)
                ));

        sections.add(section("2) HTML-like markup: <b>, <i>, <u>, <s>, <overline>, <color=#RRGGBB>",
                CgTextLayout.of(
                                "This line has <b>bold</b>, <i>italic</i>, <u>underlined</u>, "
                                        + "<s>strikethrough</s>, <overline>overlined</overline>, "
                                        + "and <color=#FF0000>colored</color> words all together.",
                          group)
                        .markup(CgMarkupParser.HTML)
                ));

        sections.add(section("3) Minecraft formatting codes: §l, §o, §n, §r",
                CgTextLayout.of(
                                "§lBold§r §aplain§r §nunderlined§r §bplain§r "
                                        + "§o§lbold and §litalic§f §mtogether§r §cplain§r again.",
                        minecraftGroup)
                        .markup(CgMarkupParser.MINECRAFT)
                ));

        sections.add(section("4) Alignment: CENTER across lines of different widths",
                CgTextLayout.of(
                        "Centered line one\nA noticeably longer second line that still centers\nShort",
                        regularFamily)
                        .align(CgTextAlign.CENTER)
                ));

        sections.add(section("5) Max-lines + ellipsis: truncated after 2 lines",
                CgTextLayout.of(
                        "This paragraph has far more lines than we allow to display, so it "
                                + "should truncate after two lines and show an ellipsis marker "
                                + "instead of silently cutting off.\nSecond line here.\n"
                                + "Third line never shown.\nFourth line never shown either.",
                        regularFamily)
                        .maxLines(2)
                        .ellipsis("...")
                ));

        sections.add(section("6) Arabic RTL -- font-fallback resolves the Arabic face automatically",
                CgTextLayout.of(
                        "مرحبا بكم! هذا "
                                + "نص عربي يُكتب "
                                + "من اليمين إلى "
                                + "اليسار، مع التفاف "
                                + "لخطوط متعددة.",
                        regularFamily)
                        .align(CgTextAlign.RIGHT)
                ));

        sections.add(section("7) Combination: bold HTML span containing Arabic RTL, plus color, wrapped",
                CgTextLayout.of(
                                "Hello <b>bold text with مرحبا Arabic "
                                        + "shaped right inside it</b>, followed by "
                                        + "<color=#88CCFF>a colored finish</color>, all wrapped "
                                        + "across multiple lines to show everything working "
                                        + "together at once: markup, fallback fonts, RTL, and color.",
                        group)
                        .markup(CgMarkupParser.HTML)
                ));

        return sections;
    }

    /**
     * Shapes {@code request} without baking a fixed {@code maxWidth}/{@code CgTextLayout} --
     * the retained {@link CgShapedParagraph} is what lets {@code render()} re-wrap against the
     * live PoseStack scale every frame (see {@code CgTextRenderer.Draw#paragraph}), and query
     * its real per-frame height via {@code CgTextRenderer.Draw#measure()} for stacking, instead
     * of freezing either the wrap points or the measured height at build-time scale.
     */
    private static Section section(String label, CgTextLayout.Request request) {
        return new Section(label, request.shape());
    }

    record Section(String label, CgShapedParagraph paragraph) {
    }

    // ────────────────────────────────────────────────────────────────
    //  Kanji warmup profiling (see the CgProfiler.scope("kanjiWorldDraw") call in render())
    // ────────────────────────────────────────────────────────────────

    private static final String[] PROFILE_CSV_HEADER = {
            "frame", "elapsedSec", "frameDtMs", "drawTotalMs", "resolveGlyphsMs", "flattenMs",
            "resolvePlacementsMs", "msdfGenMs", "freetypeRasterMs", "submitQuadsMs", "glFlushMs",
            "materialTransitionMs", "tickFrameMs", "drainMs",
            "glyphCount", "glFlushCount", "materialTransitions",
            "cacheHit", "cacheMiss", "missEffectiveTargetPx", "missResultDistanceField",
            "msdfAtlasHit", "msdfSyncGenerated", "msdfBudgetExhaustedOrFailed",
            "msdfFellBackToBitmap", "bitmapAtlasHit", "bitmapSyncRasterized",
            "asyncGlyphsUploaded", "asyncBytesUploaded", "pendingAsyncGlyphs",
            "growCount", "growMs", "reallocMs", "replayMs", "replayLayers", "replayBytes",
            "reusedDF", "queried",
            "visScanMs", "decorMs", "sortMs", "syncProjMs", "quadLoopMs",
            "matRecompileMs", "matCompileVariantMs", "matDoBindMs",
            "uboMapMs", "uboWriteMs", "uboCommitMs", "uboBytes",
            "vtxMapMs", "vtxWriteMs", "vtxCommitMs", "vtxBytes",
            "ssboMapMs", "ssboCommitMs", "ssboBytes",
            "uboSmallUploads", "gcCount", "gcTimeMs",
            "dbWireMs", "dbStateSaveMs", "dbPropsUpMs", "dbUboBindMs", "dbSamplersMs", "dbRenderStateMs", "dbShaderBindMs"
    };

    /** Appends one CSV row (and, for a couple of representative frames, a full indented
     * {@link CgProfilerReport#format()} tree dump) summarizing this frame's
     * {@code "kanjiWorldDraw"} scope tree/counters/samples — buffered in memory and flushed once
     * by {@link #dumpProfile} so the file I/O itself never pollutes the very timings being
     * measured. */
    private void recordProfileFrame(FrameInfo frame, CgProfilerReport report, int pendingAsync) {
        if (report == null) return; // CgProfiler.isEnabled() was already checked by the caller

        if (profileCsvRows.isEmpty()) {
            profileCsvRows.add(String.join(",", PROFILE_CSV_HEADER));
        }

        String draw = "kanjiWorldDraw";
        String resolveGlyphs = draw + "/resolveGlyphs";
        String resolvePlacements = resolveGlyphs + "/resolvePlacements";
        String submitQuads = draw + "/submitSortedQuads";

        profileCsvRows.add(String.format(Locale.ROOT,
                "%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,"
                        + "%.0f,%d,%d,%d,%d,%.1f,%.2f,%d,%d,%d,%d,%d,%d,%d,%d,%d",
                frame.getFrameNumber(), frame.getElapsedTime(), frame.getDeltaTime() * 1000.0,
                scopeTotalMillis(report, draw),
                scopeTotalMillis(report, resolveGlyphs),
                scopeTotalMillis(report, resolveGlyphs + "/flatten"),
                scopeTotalMillis(report, resolvePlacements),
                scopeTotalMillis(report, resolvePlacements + "/msdfgen.generate"),
                scopeTotalMillis(report, resolvePlacements + "/freetype.rasterize"),
                scopeTotalMillis(report, submitQuads),
                scopeTotalMillis(report, submitQuads + "/glFlush"),
                scopeTotalMillis(report, submitQuads + "/materialTransition"),
                scopeTotalMillis(report, "registry.tickFrame"),
                scopeTotalMillis(report, "registry.tickFrame/drainCompletedGlyphs"),
                sampleValue(report, "draw.glyphCount"),
                counterValue(report, "glFlush.count"),
                counterValue(report, "materialTransition"),
                counterValue(report, "placementCache.hit"), counterValue(report, "placementCache.miss"),
                sampleValue(report, "placementCache.miss.effectiveTargetPx"),
                sampleValue(report, "placementCache.miss.resultDistanceField"),
                counterValue(report, "glyph.msdf.atlasHit"), counterValue(report, "glyph.msdf.syncGenerated"),
                counterValue(report, "glyph.msdf.syncBudgetExhaustedOrFailed"),
                counterValue(report, "glyph.msdf.fellBackToBitmap"),
                counterValue(report, "glyph.bitmap.atlasHit"), counterValue(report, "glyph.bitmap.syncRasterized"),
                counterValue(report, "asyncCommit.glyphsUploaded"), counterValue(report, "asyncCommit.bytesUploaded"),
                (long) pendingAsync)
                // Growth diagnostics appended separately -- these scopes fire from wherever the
                // triggering glyph commit happened (registry.tickFrame's drain, or inside the
                // draw), so they are looked up by bare scope name across the whole frame rather
                // than at one fixed path.
                + String.format(Locale.ROOT, ",%d,%.3f,%.3f,%.3f,%.0f,%d",
                counterValue(report, "atlas.growCapacity.count"),
                anyScopeTotalMillis(report, "atlas.growCapacity"),
                anyScopeTotalMillis(report, "texArray.realloc"),
                anyScopeTotalMillis(report, "texArray.replayLayers"),
                sampleValue(report, "texArray.replayLayers.count"),
                counterValue(report, "texArray.replayLayers.bytes"))
                + String.format(Locale.ROOT, ",%d,%d",
                counterValue(report, "resolvePlacements.reusedDistanceField"),
                counterValue(report, "resolvePlacements.queried"))
                // submitSortedQuads internals + material compile — looked up by bare scope name
                // since a material bind can fire from more than one path in a frame.
                + String.format(Locale.ROOT, ",%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f",
                anyScopeTotalMillis(report, "visibilityScan"),
                anyScopeTotalMillis(report, "resolveDecorations"),
                anyScopeTotalMillis(report, "sortKeys"),
                anyScopeTotalMillis(report, "syncProjection"),
                anyScopeTotalMillis(report, "quadLoop"),
                anyScopeTotalMillis(report, "material.recompile"),
                anyScopeTotalMillis(report, "material.getOrCompileVariant"),
                anyScopeTotalMillis(report, "material.doBind"))
                // Stream-buffer upload path, split by GL target — tells us whether any stall is
                // UBO-specific (a usage-pattern problem) or spread across vertex/SSBO too (a
                // problem in the shared streaming machinery every buffer sits on).
                + String.format(Locale.ROOT, ",%.3f,%.3f,%.3f,%d,%.3f,%.3f,%.3f,%d,%.3f,%.3f,%d",
                anyScopeTotalMillis(report, "streamBuffer.ubo.map"),
                anyScopeTotalMillis(report, "streamBuffer.ubo.write"),
                anyScopeTotalMillis(report, "streamBuffer.ubo.commit"),
                counterValue(report, "streamBuffer.ubo.bytes"),
                anyScopeTotalMillis(report, "streamBuffer.vertex.map"),
                anyScopeTotalMillis(report, "streamBuffer.vertex.write"),
                anyScopeTotalMillis(report, "streamBuffer.vertex.commit"),
                counterValue(report, "streamBuffer.vertex.bytes"),
                anyScopeTotalMillis(report, "streamBuffer.ssbo.map"),
                anyScopeTotalMillis(report, "streamBuffer.ssbo.commit"),
                counterValue(report, "streamBuffer.ssbo.bytes"))
                + String.format(Locale.ROOT, ",%d", counterValue(report, "streamBuffer.ubo.smallUpload"))
                + String.format(Locale.ROOT, ",%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f",
                anyScopeTotalMillis(report, "doBind.wire"),
                anyScopeTotalMillis(report, "doBind.stateSave"),
                anyScopeTotalMillis(report, "doBind.propsUpload"),
                anyScopeTotalMillis(report, "doBind.uboBind"),
                anyScopeTotalMillis(report, "doBind.samplers"),
                anyScopeTotalMillis(report, "doBind.renderState"),
                anyScopeTotalMillis(report, "doBind.shaderBind")));

        // Full indented tree for the first few frames (worst case) plus one per second across the
        // whole warmup window -- everything else only needs the CSV's numbers.
        boolean isEarlyFrame = frame.getFrameNumber() <= 3;
        boolean isNewSecond = (int) frame.getElapsedTime() > lastTreeDumpSecond;
        if (isEarlyFrame || isNewSecond) {
            profileTreeDumps.add("=== frame " + frame.getFrameNumber()
                    + " (elapsed " + String.format(Locale.ROOT, "%.2f", frame.getElapsedTime()) + "s"
                    + ", dt " + String.format(Locale.ROOT, "%.1f", frame.getDeltaTime() * 1000.0) + "ms"
                    + ", pendingAsync " + pendingAsync + ") ===\n"
                    + report.format());
            if (isNewSecond) lastTreeDumpSecond = (int) frame.getElapsedTime();
        }
    }

    /** Sums every scope whose path ends in {@code name}, wherever it nested this frame — for
     * scopes that can fire from more than one call path (see the growth diagnostics). */
    private static double anyScopeTotalMillis(CgProfilerReport report, String name) {
        double total = 0;
        for (CgProfilerReport.ScopeEntry entry : report.scopes()) {
            if (entry.name().equals(name)) total += entry.totalNanos() / 1_000_000.0;
        }
        return total;
    }

    private static double scopeTotalMillis(CgProfilerReport report, String path) {
        for (CgProfilerReport.ScopeEntry entry : report.scopes()) {
            if (entry.path().equals(path)) return entry.totalNanos() / 1_000_000.0;
        }
        return 0.0;
    }

    private static long counterValue(CgProfilerReport report, String name) {
        Long value = report.counters().get(name);
        return value != null ? value : 0L;
    }

    private static double sampleValue(CgProfilerReport report, String name) {
        CgProfilerReport.SampleSummary summary = report.samples().get(name);
        return summary != null ? summary.last() : Double.NaN;
    }

    /** Flushes the buffered per-frame CSV rows and tree-dump snapshots to
     * {@code harness-output/text-3d/} once, when the {@link #PROFILE_WINDOW_SECONDS} warmup
     * window has elapsed. */
    private void dumpProfile(HarnessContext ctx) {
        String outputDirPath = ctx.getOutputDir();
        File dir = new File(outputDirPath != null ? outputDirPath : "harness-output/text-3d");
        dir.mkdirs();

        File csvFile = new File(dir, "kanji-warmup-profile.csv");
        File treeFile = new File(dir, "kanji-warmup-profile-trees.txt");
        try {
            try (PrintWriter pw = new PrintWriter(new FileWriter(csvFile))) {
                for (String row : profileCsvRows) pw.println(row);
            }
            try (PrintWriter pw = new PrintWriter(new FileWriter(treeFile))) {
                for (String dump : profileTreeDumps) pw.println(dump);
            }
            LOGGER.info("[Harness] Kanji warmup profile written: " + csvFile.getAbsolutePath()
                    + " (" + (profileCsvRows.size() - 1) + " frames), " + treeFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to write kanji warmup profile", e);
        }
    }
}
