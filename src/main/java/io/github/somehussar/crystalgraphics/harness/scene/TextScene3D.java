package io.github.somehussar.crystalgraphics.harness.scene;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.*;
import com.crystalgraphics.api.text.*;
import com.crystalgraphics.text.render.CgTextRenderContext;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgraphics.text.richtext.CgMarkupParser;
import com.crystalgui.core.input.SystemInput;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.config.TextSceneConfig;
import io.github.somehussar.crystalgraphics.harness.config.ViewportState;
import io.github.somehussar.crystalgraphics.harness.util.GlStateResetHelper;
import io.github.somehussar.crystalgraphics.harness.util.HarnessFontUtil;
import io.github.somehussar.crystalgraphics.harness.util.WorldTextRenderHelper;
import lombok.Setter;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
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
public class TextScene3D implements InteractiveSceneLifecycle, SystemInput.Mouse {

    private static final Logger LOGGER = Logger.getLogger(TextScene3D.class.getName());
    
    private static final double MTSDF_PREWARM_SECONDS = 3.0;
    private static final float[][] INVESTIGATION_CAPTURES = new float[][] {
            {-0.78f, 0.16f, -4.85f, 359.85f, 0.15f},
            {-0.75f, 0.16f, -4.97f, 3.60f, -2.20f},
            {-0.83f, 0.16f, -4.97f, 4.95f, -4.65f},
            {-0.47f, 0.13f, -4.95f, 357.85f, -1.65f}
    };
    private static final String[] INVESTIGATION_CAPTURE_NAMES = new String[] {
            "ar-join-notch-overview",
            "ar-join-notch-zoom-a",
            "ar-join-notch-zoom-b",
            "bracket-corner-rounding"
    };

    // ── Interactive mode state ──
    private boolean running = true;
    /**
     * -- SETTER --
     *  Configures whether the program should shut down when this scene completes.
     *
     * @param shutdown true to exit on completion (default), false to continue
     */
    @Setter
    private boolean shutdownOnComplete = false;

    private HarnessContext ctx;
    private WorldTextRenderHelper helper;
    private WorldTextRenderHelper arHelper;
    private WorldTextRenderHelper jpHelper;

    // ── Real Cgui UI (rendered when paused) ──
    private final Matrix4f orthoProjection = new Matrix4f();

    @Override
    public void init(HarnessContext ctx) {
        this.ctx = ctx;
        Camera3D camera = ctx.getCamera3D();

        // Typed config is resolved before execution and available via context.
        // For interactive scenes, the config is set on ctx before init() is called.
        TextSceneConfig config = (TextSceneConfig) ctx.getSceneConfig();
        String fontPath = HarnessFontUtil.resolveFontPath(config.getFontPath());
        int fontSizePx = config.getFontSizePx();
        String text = config.getText();
        int layoutWidth = config.getWidth();
        int layoutHeight = config.getHeight();

        LOGGER.info("[Harness] World text scene (interactive): font=" + fontPath);
        LOGGER.info("[Harness] World text scene (interactive): size=" + fontSizePx + "px"
                + ", mtsdf=" + config.isMtsdf());
        //CgTextRenderer.diagnosticLogging = true;

        // Initialize the shared render helper (validates GL caps, loads font, builds layouts)
        helper = new WorldTextRenderHelper(fontPath, fontSizePx, text, layoutWidth, layoutHeight,
                config.getAtlasSize(), config.isMtsdf());
        helper.init();

        //jp  "さあ 剽悍な双眸を エーカム そうさ 先頭に e"
        jpHelper = new WorldTextRenderHelper(HarnessFontUtil.JAPANESE_FONT, fontSizePx,
                config.kanji, layoutWidth, layoutHeight,
                config.getAtlasSize(), config.isMtsdf());
        jpHelper.init();
//HI بيانات الاستفسار e
        arHelper = new WorldTextRenderHelper(HarnessFontUtil.ARABIC_FONT, fontSizePx, "HI HI HI HI HI HI HI", layoutWidth,
                layoutHeight,
                config.getAtlasSize(), config.isMtsdf());
        arHelper.init();

        camera.moveCamera(0, 0, 0);
        camera.setYaw(337.0f);
        camera.setPitch(0);


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


        renderer = CgTextRenderer.createScreenSized();
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
    
    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // Build view matrix from camera
        Matrix4f viewMatrix = this.ctx.getCamera3D().getViewMatrix();

        ViewportState vp = this.ctx.getViewport();
        int screenWidth = vp.getWidth();
        int screenHeight = vp.getHeight();

        // Delegate all world-text rendering to the shared helper.
        // The helper handles perspective projection, model-view setup,
        // text positioning, and world-text draw() with correct winding order.

        PoseStack poseStack = new PoseStack();
        Matrix4f modelView = poseStack.last().pose();
        modelView.set(viewMatrix);
        float worldScale = 0.001f;
        float textWorldWidth = arHelper.getWorldLayout().totalWidth() * worldScale;
        modelView.translate(-textWorldWidth * 0.5f, 2.75f, -0f);
        modelView.scale(worldScale, -worldScale, worldScale);
//        jpHelper.renderWorld(screenWidth, screenHeight, frame.getFrameNumber(), poseStack);


        poseStack = new PoseStack();
        modelView = poseStack.last().pose();
        modelView.set(viewMatrix);
        worldScale = 0.01f;
        textWorldWidth = arHelper.getWorldLayout().totalWidth() * worldScale;
        modelView.translate(-textWorldWidth * 0.5f, 0.15f, -2f);
        modelView.scale(worldScale, -worldScale, worldScale);
        //arHelper.renderWorld(screenWidth, screenHeight, frame.getFrameNumber(), poseStack);

        // ── GL state cleanup after world text rendering ──
        // world-text draw() internally saves/restores state via CgStateBoundary, but in the
        // standalone harness (no coremod), the GLStateMirror is in UNKNOWN state which
        // can cause incomplete restoration. Use the shared reset helper to guarantee
        // floor, HUD, and pause overlay render correctly in subsequent passes.
        GlStateResetHelper.resetAfterScene();


        //Render rich-format text paragraphs
        if (true) {
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

    @Override
    public void dispose() {
        CgTextRenderer.diagnosticLogging = false;
        if (helper != null) {
            helper.dispose();
        }
        LOGGER.info("[Harness] World text scene (interactive) cleaned up.");
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return shutdownOnComplete;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Signals this scene to stop its render loop.
     */
    public void requestStop() {
        running = false;
    }

    @Override
    public boolean uses3DCamera() {
        return true;
    }

    @Override
    public boolean consumeMouseEvent(Event event) {
        scrollDelta = event.wheelDelta();
        
         if (scrollDelta > 0) scrollScale -= 0.1f;
         else if (scrollDelta < 0) scrollScale += 0.1f;
         if(scrollDelta!=0)
        System.out.println(scrollDelta);

        if (event.button() == 1) scrollScale = 1;
        
        return false;
    }

    /**
     Rich-text pipeline showcase: paragraphs, HTML markup, MC codes, alignment/ellipsis, Arabic RTL, 
     and a combination -- to text-showcase.png
     */
    public List<Section> buildSections(CgFontFamily regularFamily, CgFontFamilyGroup group, int wrapWidth) {
        List<Section> sections = new ArrayList<>();

        sections.add(section("1) Plain paragraph -- multi-line wrap, no markup",
                CgTextLayoutRequest.of(
                        "This is a plain paragraph with no markup at all: just ordinary text "
                                + "wrapped across several lines at a fixed width, exactly like "
                                + "Draw.text(String) has always worked.",
                        regularFamily)
                ));

        sections.add(section("2) HTML-like markup: <b>, <i>, <u>, <s>, <overline>, <color=#RRGGBB>",
                CgTextLayoutRequest.of(
                                "This line has <b>bold</b>, <i>italic</i>, <u>underlined</u>, "
                                        + "<s>strikethrough</s>, <overline>overlined</overline>, "
                                        + "and <color=#FF0000>colored</color> words all together.",
                          group)
                        .markup(CgMarkupParser.HTML)
                ));

        sections.add(section("3) Minecraft formatting codes: §l, §o, §n, §r",
                CgTextLayoutRequest.of(
                                "§lBold§r §aplain§r §nunderlined§r §bplain§r "
                                        + "§o§lbold and §litalic§f §mtogether§r §cplain§r again.",
                        minecraftGroup)
                        .markup(CgMarkupParser.MINECRAFT)
                ));

        sections.add(section("4) Alignment: CENTER across lines of different widths",
                CgTextLayoutRequest.of(
                        "Centered line one\nA noticeably longer second line that still centers\nShort",
                        regularFamily)
                        .align(CgTextAlign.CENTER)
                ));

        sections.add(section("5) Max-lines + ellipsis: truncated after 2 lines",
                CgTextLayoutRequest.of(
                        "This paragraph has far more lines than we allow to display, so it "
                                + "should truncate after two lines and show an ellipsis marker "
                                + "instead of silently cutting off.\nSecond line here.\n"
                                + "Third line never shown.\nFourth line never shown either.",
                        regularFamily)
                        .maxLines(2)
                        .ellipsis("...")
                ));

        sections.add(section("6) Arabic RTL -- font-fallback resolves the Arabic face automatically",
                CgTextLayoutRequest.of(
                        "مرحبا بكم! هذا "
                                + "نص عربي يُكتب "
                                + "من اليمين إلى "
                                + "اليسار، مع التفاف "
                                + "لخطوط متعددة.",
                        regularFamily)
                        .align(CgTextAlign.RIGHT)
                ));

        sections.add(section("7) Combination: bold HTML span containing Arabic RTL, plus color, wrapped",
                CgTextLayoutRequest.of(
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
    private static Section section(String label, CgTextLayoutRequest request) {
        return new Section(label, request.shape());
    }

    record Section(String label, CgShapedParagraph paragraph) {
    }
}
