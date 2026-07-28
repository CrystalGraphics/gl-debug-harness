package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgui.core.input.SystemInput;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Switch;
import com.crystalgui.ui.elements.UIText;
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
public class CgUiSwitchScene implements InteractiveSceneLifecycle, SystemInput.Keyboard, SystemInput.Mouse {

    private UIWindow uiWindow;
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
        this.uiWindow = new UIWindow(Ui.of(createDemo()));
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheet.parse(STYLES));
    }

    private UIElement createDemo() {
        UIElement root = new UIElement()
                .layout(l -> l.paddingAll(12).flexDirection(FlexDirection.COLUMN).gapAll(8))
                .setFocusPolicy(FocusPolicy.NONE);
        root.addClass("panel");

        root.addChild(row("off", new Switch()));

        Switch on = new Switch();
        on.setChecked(true);
        root.addChild(row("on", on));

        animated = new Switch();
        animated.attachListener(v -> toggleCount++);
        root.addChild(row("animating", animated));

        focused = new Switch();
        root.addChild(row("focused", focused));

        Switch disabled = new Switch();
        disabled.setEnabled(false);
        root.addChild(row("disabled", disabled));

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
        slot.addChild(t);
        row.addChild(slot);
        row.addChild(widget);
        return row;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        uiWindow.init(ctx.getScreenWidth(), ctx.getScreenHeight());
        focused.setFocused(true);

        long f = frame.getFrameNumber();
        // Toggle once, then capture the following frames so the knob is caught mid-slide.
        if (f == 6) animated.setChecked(true);
        uiWindow.paintFrame();

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
        uiWindow = null;
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
    public boolean consumeKeyboardEvent(SystemInput.Keyboard.Event event) {
        return uiWindow.getInputHandler().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(SystemInput.Mouse.Event event) {
        return uiWindow.getInputHandler().consumeMouseEvent(event);
    }
}
