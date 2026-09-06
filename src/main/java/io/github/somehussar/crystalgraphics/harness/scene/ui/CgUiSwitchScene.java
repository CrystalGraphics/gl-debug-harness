package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Switch;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * Exercises {@code Switch}, whose knob slides by animating an invisible spacer's {@code flex-grow}.
 *
 * <p><b>The load-bearing check is the animation strip.</b> One switch is toggled programmatically at
 * a known frame and then captured on several consecutive frames. If the knob appears at
 * <em>intermediate</em> positions across those captures, the transition is genuinely interpolating a
 * layout property. If it only ever appears fully-left or fully-right, the interpolator binary-snapped
 * — which is what happens when the two {@code flex-grow} endpoints differ in unit — and the slide is
 * a lie.</p>
 *
 * <p>The other thing this proves is that the timing is <em>not</em> in the Java: changing only
 * {@code transition: flex-grow …} in {@code ore.css} must change the slide speed here.</p>
 */
public class CgUiSwitchScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** Logical-to-surface scale, as the harness's other new-engine scenes use. */
    private static final float SCALE = 2f;

    private UIDocument document;
    private Switch animated;
    private Switch focused;
    private int toggleCount = 0;

    /* .row and .label come from StyleSheet.DEFAULT now — only the scene-specific slot width is left. */
    private static final String STYLES = """
            .slot  { width: 74px; }
            """;

    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(false);
        this.document = new UIDocument().markFrameThread();
        this.document.boxes().setUiScale(SCALE);
        UIElement sceneRoot = createDemo();
        // THE ROOT FILLS THE DOCUMENT. On the old engine the scene's root WAS the window's
        // root and took the window's size; here the DOCUMENT is the root and this is an
        // ordinary child, which sizes to its content -- so without this the scene lays out
        // at nothing and draws nothing. DEFAULT origin, so a scene sheet still wins.
        StyleGroup.defaultPipeline(sceneRoot.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f));
        this.document.append(sceneRoot);
        this.document.styles().addStylesheet(StyleSheet.DEFAULT);
        this.document.styles().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        this.document.styles().addStylesheet(StyleSheet.parse(STYLES));
    }

    private UIElement createDemo() {
        UIElement root = new UIElement()
                .layout(l -> l.paddingAll(12).flexDirection(FlexDirection.COLUMN).gapAll(8))
                .setFocusPolicy(FocusPolicy.NONE);
        root.addClass("panel");

        root.append(row("off", new Switch()));

        Switch on = new Switch();
        on.setChecked(true);
        root.append(row("on", on));

        animated = new Switch();
        animated.attachListener(v -> toggleCount++);
        root.append(row("animating", animated));

        focused = new Switch();
        root.append(row("focused", focused));

        Switch disabled = new Switch();
        disabled.setEnabled(false);
        root.append(row("disabled", disabled));

        return root;
    }

    private UIElement row(String label, UIElement widget) {
        UIElement row = new UIElement();
        row.addClass("row");
        // Fixed-width slot: UIText pushes its own width at IMPORTANT origin, which outranks any
        // stylesheet width, so the label has to be wrapped to keep the rows aligned.
        UIElement slot = new UIElement().layout(l -> l.width(64));
        UIText t = new UIText(label);
        t.addClass("label");
        slot.append(t);
        row.append(slot);
        row.append(widget);
        return row;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        focused.setFocused(true);

        long f = frame.getFrameNumber();
        // Toggle once, then capture the following frames so the knob is caught mid-slide.
        if (f == 6) animated.setChecked(true);
        document.frame(frame.getDeltaTime(), ctx.getScreenWidth() / SCALE, ctx.getScreenHeight() / SCALE);

        // AND THE PAINT. `paintFrame()` did both; `frame()` only advances, so a scene that
        // lost this half advanced perfectly and drew nothing.
        CgUiPaintContext paintContext = CgUiPaintContext.getInstance();
        paintContext.beginFrame(ctx.getScreenWidth(), ctx.getScreenHeight());
        document.paint(paintContext);
        paintContext.endFrame();

        var context = CgUiPaintContext.getInstance();
        context.text().draw().at(0, 0)
                .text("Switch — knob slides via flex-grow; timing from ore.css. toggles=" + toggleCount)
                .font(context.getFont().atSize(14)).submit();

        if (f == 5) ctx.getArtifactService().requestCapture("before");
        if (f == 7) ctx.getArtifactService().requestCapture("mid1");
        if (f == 9) ctx.getArtifactService().requestCapture("mid2");
        if (f == 40) ctx.getArtifactService().requestCapture("after");
    }

    @Override
    public void dispose() {
        document = null;
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return false;
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        return document.input().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return document.input().consumeMouseEvent(event);
    }
}
