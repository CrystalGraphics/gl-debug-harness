package com.crystalgraphics.harness.ui;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.desktop.host.DesktopHost;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.style.StyleGroup;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgui.viewport.CgUiViewport;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

import javax.annotation.Nullable;

/**
 * A 2D scene that is one CrystalGUI document filling the window: the scene builds its tree and this runs it. It owns
 * the document and its viewport, the scale, the stylesheets, input and teardown, so a scene writes only what it shows.
 *
 * <pre>{@code
 * public final class MyScene extends UiDocumentScene {
 *     @Override
 *     protected UIElement build(HarnessContext ctx) {
 *         UIElement root = new UIElement();
 *         root.append(new UIText("Hello"));
 *         return root;                       // fills the document
 *     }
 *
 *     @Override
 *     protected void style(StyleEngine styles) {
 *         styles.addStylesheet(StyleSheet.parse(".card { padding: 8px; }"));
 *     }
 * }
 * }</pre>
 *
 * <p>The optional hooks, for a scene with more to do:</p>
 *
 * <pre>{@code
 * @Override protected String theme() { return "crystalgui:crystal-dark"; }        // the theme its stack activates
 * @Override protected boolean key(Keyboard.Event e) { ... }                        // keys the document left
 * @Override protected void beforePaint(CgUiPaintContext paint) { paint.textGamma(gamma); }
 * @Override protected void paintOver(CgUiPaintContext paint) { paint.text().draw()...submit(); }
 * @Override protected void tick(HarnessContext ctx, FrameInfo frame) { if (frame.getFrameNumber() == 8) ... }
 * @Override protected CgUiViewport.Threading threading() { return CgUiViewport.Threading.INLINE; }   // a scene timing its frame
 * @Override protected CgUiViewport.Content content() { ... }                      // layout only, say
 * }</pre>
 *
 * <ul>
 *   <li>{@link #build}, {@link #attached}, {@link #style}, {@link #key}, {@link #beforePaint} and {@link #paintOver} run on the document's
 *       thread; {@link #tick} and {@link #afterFrame} on the render thread, which reaches the tree only through
 *       {@link #inDocument}.</li>
 *   <li>The stylesheets are {@code HarnessThemes}' stack (the user-agent sheet, the theme's rules and tokens), then
 *       {@link #style}'s; {@link #bare()} leaves only {@link #style}'s, for a scene compared against nothing.
 *       {@code -Dcrystalgui.theme} picks another theme for any scene.</li>
 *   <li>The document is offered every key first; Ctrl+= and Ctrl+- zoom the scale and Ctrl+0 resets it, as a browser's.</li>
 *   <li>Every size is in logical pixels, the window's divided by the scale.</li>
 *   <li>The document draws on CrystalGraphics' {@code SCREEN} stage, which the harness fires over every 2D scene as a
 *       game fires it over a screen: its size, target and clock are the stage's, and {@link #afterFrame} runs after.</li>
 * </ul>
 */
public abstract class UiDocumentScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    private static final float MIN_SCALE = 0.5f, MAX_SCALE = 4f, ZOOM_STEP = 0.25f;

    private UIDocument document;
    private CgUiViewport viewport;
    private CgUiViewport.Content content;
    private CgRenderStage.Registration screen;
    private HarnessContext context;
    private FrameInfo lastFrame;
    private volatile boolean running = true;

    // ── What a scene writes ─────────────────────────────────────────────────────────────────────

    /** The scene's tree, on the document; it is sized to fill the document, which a scene sheet may override. */
    protected abstract UIElement build(HarnessContext ctx);

    /** Once {@link #build}'s root is in the document, on it: for a tree that must be attached before it is driven. */
    protected void attached(UIElement root) {
    }

    /** The scene's own stylesheets, after the theme stack. */
    protected void style(StyleEngine styles) {
    }

    /** The theme the stack activates, {@code "namespace:name"}; null for the stack with none. */
    @Nullable
    protected String theme() {
        return null;
    }

    /** True for no stack at all: only {@link #style}'s sheets, not even the user-agent one. */
    protected boolean bare() {
        return false;
    }

    /** The scale at start, logical pixels to the window's. */
    protected float scale() {
        return DesktopHost.UI_SCALE;
    }

    /** How the document runs: from the flags by default. {@code INLINE} for a scene that times its frame on this thread. */
    protected CgUiViewport.Threading threading() {
        return CgUiViewport.Threading.fromFlags();
    }

    /** A press or release the document did not take, on the document. @return whether the scene took it */
    protected boolean key(CgSystemInput.Keyboard.Event event) {
        return false;
    }

    /** Before the document paints, in its frame: for state the paint context reads, such as its text gamma. */
    protected void beforePaint(CgUiPaintContext paint) {
    }

    /** After the document paints, in the same frame, over everything. */
    protected void paintOver(CgUiPaintContext paint) {
    }

    /** Each frame on the render thread, before the document's frame. */
    protected void tick(HarnessContext ctx, FrameInfo frame) {
    }

    /** Each frame on the render thread, after the document's frame: in the {@code SCREEN} stage's turn. */
    protected void afterFrame(HarnessContext ctx, FrameInfo frame) {
    }

    /**
     * The document's frame, for a scene that draws it some other way than whole: by default the document painted
     * between {@link #beforePaint} and {@link #paintOver}.
     */
    protected CgUiViewport.Content content() {
        return CgUiViewport.Content.whole(document, this::beforePaint, this::paintOver);
    }

    /** At teardown, on the document, before it closes. */
    protected void disposed() {
    }

    // ── What a scene uses ───────────────────────────────────────────────────────────────────────

    /** The document. Its tree is touched on its own thread only: from a hook above, or {@link #inDocument}. */
    protected final UIDocument document() {
        return document;
    }

    protected final CgUiViewport viewport() {
        return viewport;
    }

    /** Runs {@code work} on the document and waits for it. For a key, a probe or a script; never per frame. */
    protected final void inDocument(Runnable work) {
        viewport.run(work);
    }

    /** Ends the run: the loop stops, and the program exits if {@link #shouldShutdownOnComplete} says so. */
    protected final void finish() {
        running = false;
    }

    // ── The lifecycle ───────────────────────────────────────────────────────────────────────────

    @Override
    public final void init(HarnessContext ctx) {
        document = new UIDocument();
        viewport = CgUiViewport.open(document, threading(), getClass().getSimpleName());
        content = content();
        context = ctx;
        screen = CgRenderStage.SCREEN.register(CgRenderStage.VIEWPORT_WIDGETS, stage -> {
            if (viewport == null || lastFrame == null) return;
            viewport.frame(stage, content);
            afterFrame(context, lastFrame);
        });
        viewport.run(() -> {
            document.boxes().setUiScale(scale());
            StyleEngine styles = document.styles();
            if (!bare()) HarnessThemes.install(styles, theme());
            style(styles);
            UIElement root = build(ctx);
            // At the default origin, so a scene sheet still sizes its root
            StyleGroup.defaultPipeline(root.getStyle().getLayoutGroup(), l -> l.widthPercent(100f).heightPercent(100f));
            document.append(root);
            attached(root);
        });
    }

    @Override
    public final void render(HarnessContext ctx, FrameInfo frame) {
        context = ctx;
        lastFrame = frame;
        tick(ctx, frame);
    }

    @Override
    public final void dispose() {
        if (screen != null) screen.close();
        screen = null;
        if (viewport == null) return;
        viewport.run(this::disposed);
        viewport.close();
        viewport = null;
        document = null;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public final boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return false;
    }

    // ── Input ───────────────────────────────────────────────────────────────────────────────────

    @Override
    public final boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        return viewport.offerKey(event, () -> document.input().consumeKeyboardEvent(event) || zoom(event) || key(event));
    }

    @Override
    public final boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return viewport.offerMouse(event, () -> document.input().consumeMouseEvent(event));
    }

    private boolean zoom(CgSystemInput.Keyboard.Event event) {
        if (!event.pressed() || !ctrlDown()) return false;
        float now = document.boxes().uiScale();
        float next = switch (event.key()) {
            case CgKeyCodes.KEY_EQUALS -> Math.min(MAX_SCALE, now + ZOOM_STEP);
            case CgKeyCodes.KEY_MINUS -> Math.max(MIN_SCALE, now - ZOOM_STEP);
            case CgKeyCodes.KEY_0 -> scale();
            default -> -1f;
        };
        if (next < 0f) return false;
        document.boxes().setUiScale(next);
        return true;
    }

    private static boolean ctrlDown() {
        return CgInput.isKeyDown(CgKeyCodes.KEY_LCONTROL) || CgInput.isKeyDown(CgKeyCodes.KEY_RCONTROL);
    }
}
