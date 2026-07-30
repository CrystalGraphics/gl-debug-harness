package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * Exercises {@code Slider} — continuous and stepped, with the thumb's interaction states.
 *
 * <p>Live rows are draggable: grabbing the thumb tracks by delta (so it doesn't jump under the
 * cursor), clicking the bar jumps to that spot and then tracks. Arrow keys nudge by one step
 * (1% when continuous), Home/End go to the ends, and the wheel steps.</p>
 *
 * <p>Forced-state rows drive {@code :hover}/{@code :active}/{@code :focus} programmatically so the
 * thumb's states are verifiable from a static capture, the same technique
 * {@code CgUiOreThemeScene} uses.</p>
 *
 * <p>Note the structure being verified: the root is as tall as the THUMB and paints nothing, while
 * {@code __fill__}/{@code __spacer__} draw the thin bar. A bar-height root would leave the taller
 * thumb overhanging its hit box, so clicks near the thumb's top and bottom edges would miss.</p>
 *
 * <p>The last row also verifies the discrete-slider story: a stepped slider is styled entirely
 * through the {@code __stepped__} class it tags itself with, since the bar is deliberately not
 * drawn segmented.</p>
 */
public class CgUiSliderScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    private UIWindow uiWindow;
    private Slider live;
    private Slider hovered;
    private Slider pressed;
    private Slider focusedSlider;

    /* The `slider.__stepped__` rule is the point of the last line: it is defined ONLY here, in a
     * scene-local stylesheet, and Slider tags itself with that class purely as a side effect of
     * setStep(). The stepped row below drawing a different fill from the continuous rows is the
     * proof that the discrete-mode hook is reachable from CSS with no engine or element support.
     */
    private static final String STYLES = """
            slider.__stepped__ .__fill__ { background: asset("crystalgui:ore", "checkbox-box"); }
            """;

    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(true);
        this.uiWindow = new UIWindow(Ui.of(createDemo()));
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheet.parse(STYLES));
    }

    private UIElement createDemo() {
        UIElement root = new UIElement()
                .layout(l -> l.paddingAll(12).flexDirection(FlexDirection.COLUMN).gapAll(6))
                .setFocusPolicy(FocusPolicy.NONE);
        root.addClass("panel");

        live = new Slider();
        live.setValue(0.45f);
        root.addChild(row("drag me", live));

        hovered = new Slider();
        hovered.setValue(0.45f);
        root.addChild(row("hover", hovered));

        pressed = new Slider();
        pressed.setValue(0.45f);
        root.addChild(row("active", pressed));

        focusedSlider = new Slider();
        focusedSlider.setValue(0.45f);
        root.addChild(row("focus", focusedSlider));

        Slider disabled = new Slider();
        disabled.setValue(0.45f);
        disabled.setEnabled(false);
        root.addChild(row("disabled", disabled));

        // Stepped: 5 positions (0..4). The value snaps and the root picks up `__stepped__`; the bar
        // is NOT drawn segmented, by design — see Slider.STEPPED_CLASS. The only visual difference
        // is whatever the stylesheet chooses to hang off that class (here, a blue fill).
        Slider stepped = new Slider();
        stepped.setRange(0f, 4f).setStep(1f).setValue(2f);
        root.addChild(row("step 1/4", stepped));

        return root;
    }

    private UIElement row(String label, UIElement widget) {
        UIElement row = new UIElement();
        row.addClass("row");
        // Fixed-width slot: UIText pushes its own width at IMPORTANT origin, outranking any
        // stylesheet width, so wrapping it is what keeps the rows aligned.
        UIElement slot = new UIElement().layout(l -> l.width(58));
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
        hovered.setHovered(true);
        pressed.setPressed(true);
        focusedSlider.setFocused(true);
        uiWindow.paintFrame();

        var context = CgUiPaintContext.getInstance();
        context.text().draw().at(0, 0)
                .text(String.format("Slider — drag/click/arrows/wheel.  live value = %.3f", live.getValue()))
                .font(context.getFont().atSize(14)).submit();

        if (frame.getFrameNumber() == 5) {
            ctx.getArtifactService().requestCapture("startup");
        }
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
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        return uiWindow.getInputHandler().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return uiWindow.getInputHandler().consumeMouseEvent(event);
    }
}
