package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.text.UIText;
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

    /** Logical-to-surface scale, as the harness's other new-engine scenes use. */
    private static final float SCALE = 2f;

    private UIDocument document;
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
        this.document = new UIDocument().markFrameThread();
        this.document.boxes().setUiScale(SCALE);
        UINode sceneRoot = createDemo();
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

    private UINode createDemo() {
        UINode root = new UINode()
                .layout(l -> l.paddingAll(12).flexDirection(FlexDirection.COLUMN).gapAll(6))
                .setFocusPolicy(FocusPolicy.NONE);
        root.addClass("panel");

        live = new Slider();
        live.setValue(0.45f);
        root.append(row("drag me", live));

        hovered = new Slider();
        hovered.setValue(0.45f);
        root.append(row("hover", hovered));

        pressed = new Slider();
        pressed.setValue(0.45f);
        root.append(row("active", pressed));

        focusedSlider = new Slider();
        focusedSlider.setValue(0.45f);
        root.append(row("focus", focusedSlider));

        Slider disabled = new Slider();
        disabled.setValue(0.45f);
        disabled.setEnabled(false);
        root.append(row("disabled", disabled));

        // Stepped: 5 positions (0..4). The value snaps and the root picks up `__stepped__`; the bar
        // is NOT drawn segmented, by design — see Slider.STEPPED_CLASS. The only visual difference
        // is whatever the stylesheet chooses to hang off that class (here, a blue fill).
        Slider stepped = new Slider();
        stepped.setRange(0f, 4f).setStep(1f).setValue(2f);
        root.append(row("step 1/4", stepped));

        return root;
    }

    private UINode row(String label, UINode widget) {
        UINode row = new UINode();
        row.addClass("row");
        // Fixed-width slot: UIText pushes its own width at IMPORTANT origin, outranking any
        // stylesheet width, so wrapping it is what keeps the rows aligned.
        UINode slot = new UINode().layout(l -> l.width(58));
        UIText t = new UIText(label);
        t.addClass("label");
        slot.append(t);
        row.append(slot);
        row.append(widget);
        return row;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        hovered.setHovered(true);
        pressed.setPressed(true);
        focusedSlider.setFocused(true);
        document.frame(frame.getDeltaTime(), ctx.getScreenWidth() / SCALE, ctx.getScreenHeight() / SCALE);

        // AND THE PAINT. `paintFrame()` did both; `frame()` only advances, so a scene that
        // lost this half advanced perfectly and drew nothing.
        CgUiPaintContext paintContext = CgUiPaintContext.getInstance();
        paintContext.beginFrame(ctx.getScreenWidth(), ctx.getScreenHeight());
        document.paint(paintContext);
        paintContext.endFrame();

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
