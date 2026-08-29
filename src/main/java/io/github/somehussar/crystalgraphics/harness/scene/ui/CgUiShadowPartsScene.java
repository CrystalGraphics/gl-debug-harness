package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.shadow.ShadowButton;
import com.crystalgui.ui.shadow.ShadowRoot;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * <b>Spike S2</b> — a shadow-rooted {@code Button} beside the stock one, under <b>one</b> stylesheet
 * that tries to reach into both. {@code plan_ui_rewrite.md} M0.
 *
 * <p>The mechanism is asserted headlessly in {@code ShadowEncapsulationTest}; what this scene adds is
 * the part a test cannot show — that an encapsulated widget still <em>draws and behaves</em> like a
 * widget, with a real cascade, real layout, real hit-testing and real focus.</p>
 *
 * <h3>What to look for</h3>
 * <ul>
 *   <li><b>Left column, stock {@code Button}.</b> The sheet's {@code text { color: #FF4040 }} reaches
 *       its label, so the label is <b>red</b>. Nothing in that widget asked for this and nothing could
 *       have prevented it — that is the defect S2 exists to price.</li>
 *   <li><b>Right column, {@code ShadowButton}.</b> The same rule cannot match inside the shadow tree,
 *       so its label keeps the colour its own {@code ::part(label)} rule gives it — <b>green</b>. The
 *       {@code * { }} rule does not reach it either.</li>
 *   <li><b>Both</b> respond to hover, press and Tab identically: encapsulation is a cascade property,
 *       not a behavioural one.</li>
 *   <li>The status line reports <b>what focus retargets to</b>. Tab onto the shadow button and it
 *       still reads {@code shadowbutton}, never {@code text} — which is what keeps a command's
 *       {@code DataContext} walk from starting inside a widget's internals.</li>
 * </ul>
 */
public class CgUiShadowPartsScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    private UIWindow uiWindow;
    private ShadowButton shadowButton;
    private int stockClicks = 0;
    private int shadowClicks = 0;

    /**
     * One sheet, aimed at both columns. Every rule here is the kind {@code ua/} is full of — a bare
     * type selector, a universal, a descendant — and the whole demonstration is which of them land.
     */
    private static final String STYLE_SHEET = """
            .card {
                background-color: #FF2A2A3A;
                border-width: 1px;
                border-color: #555555;
                padding-all: 10px;
                gap-all: 6px;
            }
            .heading { color: #FFCCCCCC; font-size: 12; }
            .note    { color: #FF888899; font-size: 10; }

            button, shadowbutton {
                background-color: #FF3C3C50;
                border-width: 1px;
                border-color: #777777;
                padding-all: 8px;
            }
            button:hover, shadowbutton:hover { background-color: #FF50506A; }
            button:active, shadowbutton:active { background-color: #FF20202C; }
            button:focus, shadowbutton:focus { border-color: #FF4488FF; border-width: 1.5px; padding-all: 7.5px; }

            /* THE RULE THAT DOES THE DEMONSTRATING. A bare descendant type selector, exactly the shape
               ua/widgets.css uses to style every composite's label today. It reaches the stock button's
               label and cannot reach the shadow button's. */
            text { color: #FFFF4040; }

            /* ...and the universal, which reaches even further. */
            * { text-offset-y: 0; }

            /* The only way into the shadow tree, and it has to be asked for by name. */
            shadowbutton::part(label) { color: #FF40FF88; }
            shadowbutton::part(pre-icon) { background-color: #FF40FF88; width: 6px; height: 6px; }
            """;

    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(false);

        UIElement root = new UIElement()
                .layout(l -> l.paddingAll(16).flexDirection(FlexDirection.ROW).gapAll(16)
                        .alignItems(AlignItems.FLEX_START))
                .setFocusPolicy(FocusPolicy.NONE);

        root.addChild(stockColumn());
        root.addChild(shadowColumn());

        this.uiWindow = new UIWindow(Ui.of(root));
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheet.parse(STYLE_SHEET));
    }

    private UIElement stockColumn() {
        UIElement card = new UIElement();
        card.addClass("card");
        card.addChild(heading("Button (internal children)"));

        Button button = new Button("Click Me");
        button.attachListener(() -> stockClicks++);
        card.addChild(button);

        card.addChild(note("`text { }` reached its label -> RED"));
        return card;
    }

    private UIElement shadowColumn() {
        UIElement card = new UIElement();
        card.addClass("card");
        card.addChild(heading("ShadowButton (shadow root + parts)"));

        shadowButton = new ShadowButton("Click Me");
        shadowButton.preIcon();
        shadowButton.onPressed.connect(() -> shadowClicks++);
        card.addChild(shadowButton);

        card.addChild(note("same rule cannot reach in -> GREEN, from ::part(label)"));
        return card;
    }

    private static UIElement heading(String text) {
        UIText label = new UIText(text);
        label.addClass("heading");
        return label;
    }

    private static UIElement note(String text) {
        UIText label = new UIText(text);
        label.addClass("note");
        return label;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        uiWindow.init(ctx.getScreenWidth(), ctx.getScreenHeight());
        uiWindow.paintFrame();

        UIElement focused = uiWindow.getInputHandler().getFocusedElement();
        UIElement retargeted = ShadowRoot.retarget(focused);
        String status = String.format(
                "stock clicks: %d | shadow clicks: %d | focused: %s | retargets to: %s",
                stockClicks, shadowClicks,
                focused == null ? "-" : focused.tagName(),
                retargeted == null ? "-" : retargeted.tagName());

        var context = CgUiPaintContext.getInstance();
        context.text().draw().at(0, 0).text(status).font(context.getFont().atSize(16)).submit();

        if (frame.getFrameNumber() == 5) {
            ctx.getArtifactService().requestCapture("startup");
        }
    }

    @Override
    public void dispose() {
        uiWindow = null;
        shadowButton = null;
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
