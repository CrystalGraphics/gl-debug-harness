package io.github.somehussar.crystalgraphics.harness.scene;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.text.render.CgTextRenderer;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.capture.ArtifactService;
import io.github.somehussar.crystalgraphics.harness.config.*;
import io.github.somehussar.crystalgraphics.harness.scheduler.TaskScheduler;
import io.github.somehussar.crystalgraphics.harness.util.GlStateResetHelper;
import io.github.somehussar.crystalgraphics.harness.util.HarnessFontUtil;
import io.github.somehussar.crystalgraphics.harness.util.WorldTextRenderHelper;
import io.github.somehussar.crystalgraphics.harness.validation.ValidationCaptureStep;
import io.github.somehussar.crystalgraphics.harness.validation.ValidationChoreographer;

import org.joml.Matrix4f;

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
public class TextScene3D implements InteractiveSceneLifecycle {

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

        // Schedule automated screenshots for validation if runtime services are available
        if (ctx.getRuntimeServices() != null) {
           // scheduleValidationScreenshots();
        }

        // Initialize CgUiRuntime with text support from the first helper's renderer/font
//        testUi = CgUiTest.create();
//        uiInjector = new UiEventInjector(testUi);
//        validationState = new UiValidationState(testUi);

//        wireButtonValidation("btn-confirm");
//        wireButtonValidation("btn-apply");
//        wireButtonValidation("btn-cancel");
//        wireTextboxValidation("textbox-input");
//
//        // Wire live LWJGL input forwarding to the UI when paused
//        if (ctx.getRuntimeServices() != null) {
//            UiInputForwarder forwarder = new UiInputForwarder();
//            forwarder.setContainer(testUi);
//            ctx.getRuntimeServices().setUiInputForwarder(forwarder);
//        }
//
//        UiEventInjector.enableDebugLogging();
//
//        // Schedule scripted UI event injection for validation
//        if (ctx.getRuntimeServices() != null) {
//            scheduleUiEventValidation();
//        }

        LOGGER.info("[Harness] World text scene (interactive) initialized.");
    }

    /**
     * Schedules automated screenshots through the shared validation choreography.
     */
    private void scheduleValidationScreenshots() {
        final TaskScheduler scheduler = ctx.getTaskScheduler();
        final Camera3D camera = ctx.getCamera3D();
        final RuntimeServices runtime = ctx.getRuntimeServices();
        final ArtifactService artifacts = ctx.getArtifactService();

        ValidationChoreographer choreographer = new ValidationChoreographer(
                camera, scheduler, artifacts, runtime);

        double captureTime = MTSDF_PREWARM_SECONDS + 0.5;
        for (int i = 0; i < INVESTIGATION_CAPTURES.length; i++) {
            float[] capture = INVESTIGATION_CAPTURES[i];
            choreographer.addStep(ValidationCaptureStep.builder(INVESTIGATION_CAPTURE_NAMES[i], captureTime)
                    .cameraPosition(capture[0], capture[1], capture[2])
                    .cameraOrientation(capture[3], capture[4])
                    .build());
            captureTime += 0.5;
        }

   
        choreographer.onShutdown(() -> {
            LOGGER.info("[InteractiveWorldTextScene] All screenshots captured.");
             //   running = false;
        });
        choreographer.scheduleAll();
    }

//    private void wireButtonValidation(String buttonId) {
//        UIElement el = testUi.getRoot().findById(buttonId);
//        if (el instanceof UiButton) {
//            final String id = buttonId;
//            ((UiButton) el).clicked.connect(() -> validationState.recordButtonClick(id));
//        }
//    }

//    private void wireTextboxValidation(String textboxId) {
//        UIElement el = testUi.getRoot().findById(textboxId);
//        if (el instanceof UiTextbox) {
//            final String id = textboxId;
//            UiTextbox tb = (UiTextbox) el;
//            tb.submitted.connect(() -> validationState.recordTextboxSubmit(id));
//            tb.textChanged.connect(value -> validationState.recordTextChanged());
//        }
//    }
//
//    /**
//     * Schedules scripted UI interaction events using layout-derived coordinates
//     * for deterministic validation of the CrystalGUI event/input/focus system.
//     */
//    private void scheduleUiEventValidation() {
//        final TaskScheduler scheduler = ctx.getTaskScheduler();
//
//        scheduler.schedule(MTSDF_PREWARM_SECONDS + 0.2, "ui-pause-for-test", () -> {
//            ctx.getRuntimeServices().setPaused(true);
//            testUi.computeLayout(
//                    ctx.getViewport().getWidth(),
//                    ctx.getViewport().getHeight());
//            LOGGER.info("[TextScene3D] Paused for UI event validation");
//        });
//
//        scheduler.schedule(MTSDF_PREWARM_SECONDS + 0.4, "ui-hover-btn-confirm", () -> {
//            float[] center = getElementCenter("btn-confirm");
//            if (center != null) {
//                uiInjector.mouseMove(center[0], center[1]);
//                validationState.assertHovered("btn-confirm");
//                LOGGER.info("[TextScene3D] Injected: hover over btn-confirm at (" + center[0] + ", " + center[1] + ")");
//            }
//        });
//
//        scheduler.schedule(MTSDF_PREWARM_SECONDS + 0.6, "ui-click-btn-confirm", () -> {
//            float[] center = getElementCenter("btn-confirm");
//            if (center != null) {
//                uiInjector.mouseClick(center[0], center[1], 0);
//                validationState.assertFocused("btn-confirm");
//                validationState.assertClickedContains("btn-confirm");
//                LOGGER.info("[TextScene3D] Injected: click on btn-confirm at (" + center[0] + ", " + center[1] + ")");
//            }
//        });
//
//        scheduler.schedule(MTSDF_PREWARM_SECONDS + 0.8, "ui-tab-to-btn-apply", () -> {
//            uiInjector.tabFocus(false);
//            validationState.recordFocusTransition(validationState.getFocusedElementId());
//            validationState.assertFocused("btn-apply");
//            LOGGER.info("[TextScene3D] Injected: tab focus -> expected btn-apply");
//        });
//
//        scheduler.schedule(MTSDF_PREWARM_SECONDS + 1.0, "ui-shift-tab-back", () -> {
//            uiInjector.tabFocus(true);
//            validationState.recordFocusTransition(validationState.getFocusedElementId());
//            validationState.assertFocused("btn-confirm");
//            LOGGER.info("[TextScene3D] Injected: shift+tab -> expected btn-confirm");
//        });
//
//        scheduler.schedule(MTSDF_PREWARM_SECONDS + 1.2, "ui-click-btn-cancel", () -> {
//            float[] center = getElementCenter("btn-cancel");
//            if (center != null) {
//                uiInjector.mouseClick(center[0], center[1], 0);
//                validationState.assertFocused("btn-cancel");
//                validationState.assertClickedContains("btn-cancel");
//                LOGGER.info("[TextScene3D] Injected: click on btn-cancel at (" + center[0] + ", " + center[1] + ")");
//            }
//        });
//
//        scheduler.schedule(MTSDF_PREWARM_SECONDS + 1.4, "ui-click-textbox", () -> {
//            float[] center = getElementCenter("textbox-input");
//            if (center != null) {
//                uiInjector.mouseClick(center[0], center[1], 0);
//                validationState.assertFocused("textbox-input");
//                LOGGER.info("[TextScene3D] Injected: click on textbox-input -> focus acquired");
//            }
//        });
//
//        scheduler.schedule(MTSDF_PREWARM_SECONDS + 1.6, "ui-type-into-textbox", () -> {
//            uiInjector.typeString("Hi");
//            validationState.assertTextboxContent("textbox-input", "Hi");
//            validationState.assertTextboxCaretPosition("textbox-input", 2);
//            uiInjector.backspace();
//            validationState.assertTextboxContent("textbox-input", "H");
//            validationState.assertTextboxCaretPosition("textbox-input", 1);
//            LOGGER.info("[TextScene3D] Injected: typed 'Hi' then backspace -> text='H', caret=1");
//        });
//
//        // Extended textbox validation: multi-char, submit signal, textChanged count, tab-away
//        scheduler.schedule(MTSDF_PREWARM_SECONDS + 1.8, "ui-textbox-extended", () -> {
//            // Type more characters to exercise multi-char editing
//            uiInjector.typeString("ello");
//            validationState.assertTextboxContent("textbox-input", "Hello");
//            validationState.assertTextboxCaretPosition("textbox-input", 5);
//
//            // Press Enter to fire submitted signal
//            uiInjector.keyDown(com.crystalgui.core.event.CgUiKeyCodes.KEY_ENTER, 0);
//            validationState.assertTextboxSubmitted("textbox-input");
//
//            // Verify textChanged fired for each character typed (H, Hi, H, He, Hel, Hell, Hello = 7)
//            validationState.assertTextChangedCountAtLeast(7);
//
//            LOGGER.info("[TextScene3D] Injected: extended textbox validation -> text='Hello', submitted, textChanged>=7");
//        });
//
//        scheduler.schedule(MTSDF_PREWARM_SECONDS + 2.0, "ui-tab-away-from-textbox", () -> {
//            // Textbox should currently be focused
//            validationState.assertFocused("textbox-input");
//            // Tab away: should move focus to next focusable element (btn-confirm)
//            uiInjector.tabFocus(false);
//            validationState.recordFocusTransition(validationState.getFocusedElementId());
//            validationState.assertFocused("btn-confirm");
//            LOGGER.info("[TextScene3D] Injected: tab away from textbox -> focus moved to btn-confirm");
//        });
//
//        scheduler.schedule(MTSDF_PREWARM_SECONDS + 2.2, "ui-validation-summary", () -> {
//            validationState.logSummary();
//            validationState.throwIfFailed();
//        });
//    }

//    private float[] getElementCenter(String id) {
//        UIElement el = testUi.getRoot().findById(id);
//        if (el == null) {
//            LOGGER.warning("[TextScene3D] Element not found by id: " + id);
//            return null;
//        }
//        UiRect box = el.getLayoutState().getLayoutBox();
//        return new float[]{ box.getX() + box.getWidth() / 2f, box.getY() + box.getHeight() / 2f };
//    }

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
        float textWorldWidth = arHelper.getWorldLayout().getTotalWidth() * worldScale;
        modelView.translate(-textWorldWidth * 0.5f, 25.75f, -5f);
        modelView.scale(worldScale, -worldScale, worldScale);
        jpHelper.renderWorld(screenWidth, screenHeight, frame.getFrameNumber(), poseStack);


        poseStack = new PoseStack();
        modelView = poseStack.last().pose();
        modelView.set(viewMatrix);
        worldScale = 0.01f;
        textWorldWidth = arHelper.getWorldLayout().getTotalWidth() * worldScale;
        modelView.translate(-textWorldWidth * 0.5f, 0.15f, -2f);
        modelView.scale(worldScale, -worldScale, worldScale);
        //arHelper.renderWorld(screenWidth, screenHeight, frame.getFrameNumber(), poseStack);

        // ── GL state cleanup after world text rendering ──
        // world-text draw() internally saves/restores state via CgStateBoundary, but in the
        // standalone harness (no coremod), the GLStateMirror is in UNKNOWN state which
        // can cause incomplete restoration. Use the shared reset helper to guarantee
        // floor, HUD, and pause overlay render correctly in subsequent passes.
        GlStateResetHelper.resetAfterScene();

        // ── Real Cgui test UI (rendered when paused) ──
//        if (ctx.getRuntimeServices() != null && ctx.getRuntimeServices().isPaused() && testUi != null) {
//            testUi.computeLayout(screenWidth, screenHeight);
//            orthoProjection.setOrtho(0, screenWidth, screenHeight, 0, -1, 1);
//            testUi.getPaintContext().setTextFrame(frame.getFrameNumber());
//            testUi.render(orthoProjection);
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

    /**
     * Configures whether the program should shut down when this scene completes.
     *
     * @param shutdown true to exit on completion (default), false to continue
     */
    public void setShutdownOnComplete(boolean shutdown) {
        this.shutdownOnComplete = shutdown;
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
}
