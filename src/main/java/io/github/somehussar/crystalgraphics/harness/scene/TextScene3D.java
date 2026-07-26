package io.github.somehussar.crystalgraphics.harness.scene;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontFamilyGroup;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.text.CgTextAlign;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.text.CgTextLayoutRequest;
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

        int wrapWidth = 1000 - 2 * MARGIN;


        renderer = CgTextRenderer.createScreenSized();

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

    CgFont labelFont;
    CgFont latinRegular;
    CgFont minecraftFont;
    CgFontFamilyGroup minecraftGroup;
    
    List<Section> sections;
    CgTextRenderer renderer;
    
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
        float worldScale = 0.01f;
        float textWorldWidth = arHelper.getWorldLayout().totalWidth() * worldScale;
        modelView.translate(-textWorldWidth * 0.5f, 25.75f, -5f);
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

     
        PoseStack pose = new PoseStack();
        pose.scale(scrollScale, scrollScale, 1);
        renderer.beginBatch();
        float y = MARGIN;
        for (Section s : sections) {
            if (s.label != null) {
                renderer.context().clearHistory();
                CgTextLayout labelLayout = CgTextLayoutRequest.of(s.label, labelFont).build();
                renderer.draw().layout(labelLayout).font(labelFont).at(MARGIN, y)
                        .color(LABEL_COLOR).pose(pose).submit();
                y += LABEL_FONT_SIZE_PX + LABEL_TO_BODY_GAP;
            }

            renderer.context().clearHistory();
            renderer.draw().layout(s.layout).font(latinRegular).at(MARGIN, y)
                    .color(BODY_COLOR).pose(pose).submit();
            y += s.layout.totalHeight() + SECTION_GAP;
        }
        renderer.endBatch();
//        }
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
         
         if(event.button() == 1) scrollScale = 1;
        
        return false;
    }

    /**
     Rich-text pipeline showcase: paragraphs, HTML markup, MC codes, alignment/ellipsis, Arabic RTL, 
     and a combination -- to text-showcase.png
     */
    public List<Section> buildSections(CgFontFamily regularFamily, CgFontFamilyGroup group, int wrapWidth) {
        List<Section> sections = new ArrayList<>();

        sections.add(new Section("1) Plain paragraph -- multi-line wrap, no markup",
                CgTextLayoutRequest.of(
                        "This is a plain paragraph with no markup at all: just ordinary text "
                                + "wrapped across several lines at a fixed width, exactly like "
                                + "Draw.text(String) has always worked.",
                        regularFamily)
                        .maxWidth(wrapWidth)
                        .build()));

        sections.add(new Section("2) HTML-like markup: <b>, <i>, <u>, <s>, <overline>, <color=#RRGGBB>",
                      CgTextLayoutRequest.of(
                                "This line has <b>bold</b>, <i>italic</i>, <u>underlined</u>, "
                                        + "<s>strikethrough</s>, <overline>overlined</overline>, "
                                        + "and <color=#FF0000>colored</color> <color=#00FF00>words</color> all together.",
                          group)
                        .markup(CgMarkupParser.HTML)
                        .maxWidth(wrapWidth)
                        .build()));

        sections.add(new Section("3) Minecraft formatting codes: §l, §o, §n, §r",
                     CgTextLayoutRequest.of(
                                "§lBold§r §aplain§r §nunderlined§r plain "
                                        + "§o§lbold and §d§litalic§f §mtogether§r plain again.",
                        minecraftGroup)
                        .markup(CgMarkupParser.MINECRAFT)
                        .maxWidth(wrapWidth)
                        .build()));

        sections.add(new Section("4) Alignment: CENTER across lines of different widths",
                CgTextLayoutRequest.of(
                        "Centered line one\nA noticeably longer second line that still centers\nShort",
                        regularFamily)
                        .maxWidth(wrapWidth)
                        .align(CgTextAlign.CENTER)
                        .build()));

        sections.add(new Section("5) Max-lines + ellipsis: truncated after 2 lines",
                CgTextLayoutRequest.of(
                        "This paragraph has far more lines than we allow to display, so it "
                                + "should truncate after two lines and show an ellipsis marker "
                                + "instead of silently cutting off.\nSecond line here.\n"
                                + "Third line never shown.\nFourth line never shown either.",
                        regularFamily)
                        .maxWidth(wrapWidth)
                        .maxLines(2)
                        .ellipsis("...")
                        .build()));

        sections.add(new Section("6) Arabic RTL -- font-fallback resolves the Arabic face automatically",
                CgTextLayoutRequest.of(
                        "مرحبا بكم! هذا "
                                + "نص عربي يُكتب "
                                + "من اليمين إلى "
                                + "اليسار، مع التفاف "
                                + "لخطوط متعددة.",
                        regularFamily)
                        .maxWidth(wrapWidth)
                        .align(CgTextAlign.RIGHT)
                        .build()));

        sections.add(new Section("7) Combination: bold HTML span containing Arabic RTL, plus color, wrapped",
                CgTextLayoutRequest.of(
                                "Hello <b>bold text with مرحبا Arabic "
                                        + "shaped right inside it</b>, followed by "
                                        + "<color=#88CCFF>a colored finish</color>, all wrapped "
                                        + "across multiple lines to show everything working "
                                        + "together at once: markup, fallback fonts, RTL, and color.",
                        group)
                        .markup(CgMarkupParser.HTML)
                        .maxWidth(wrapWidth)
                        .build()));

        return sections;
    }

    record Section(String label, CgTextLayout layout) {
    }
}
