package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.input.SystemInput;
import com.crystalgui.core.sound.UISoundSystem;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * Interactive harness scene exercising {@code Button} — CrystalGUI's first interactive widget.
 *
 * <p>Manual verification checklist (see the plan this scene validates):</p>
 * <ul>
 *   <li>Press-and-hold over "Click Me" shows the pressed style but does NOT increment the counter
 *       until release.</li>
 *   <li>Press over "Click Me", drag off, release elsewhere — counter must NOT increment (press
 *       cancelled, no {@code ButtonEvent.Pressed}).</li>
 *   <li>Press over "Click Me", release back over it — counter increments, and the sound log below
 *       gains a "button_click" entry (via a test {@link UISoundSystem} that just counts calls, since
 *       the harness has no real audio backend).</li>
 *   <li>Tab to "Keyboard Test" (focus ring should show via the {@code :focus} pseudo-class), then
 *       press Space (hold + release) or Enter — both should activate it exactly like a mouse click.</li>
 * </ul>
 */
public class CgUiButtonScene implements InteractiveSceneLifecycle, SystemInput.Keyboard, SystemInput.Mouse {

    private UIWindow uiWindow;
    private int clickCount = 0;
    private int keyboardActivationCount = 0;
    private int soundPlayCount = 0;

    private static final String STYLE_SHEET = """
            .card {
                background-color: #FF2A2A3A;
                border-width: 1px;
                border-color: #555555;
                padding-all: 8px;
            }
            button {
                background-color: #FF3C3C50;
                border-width: 1px;
                border-color: #777777;
                padding-all: 8px;
                transition: border-color 250ms, border-width 250ms;
            }
            button:hover {
                background-color: #FF50506A;
            }
            button:active {
                background-color: #FF20202C;
            }
            button:focus {
                border-color: #0000FF;
                border-width: 1.5px;
            }
            .label {
                color: #FFFFFF;
                font-size: 12;
            }
            """;

    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(false);
        CrystalGuiCore.setSoundSystem(soundId -> soundPlayCount++);

        UIElement root = createButtonDemo();
        this.uiWindow = new UIWindow(Ui.of(root));
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheet.parse(STYLE_SHEET));
    }

    private UIElement createButtonDemo() {
        UIElement root = new UIElement()
                .layout(l -> l
                        .paddingAll(16)
                        .flexDirection(FlexDirection.ROW)
                        .gapAll(16)
                        .alignItems(AlignItems.FLEX_START))
                .setFocusPolicy(FocusPolicy.NONE);

        UIElement clickCard = new UIElement();
        clickCard.addClass("card");
        Button clickButton = new Button("Click Me");
        clickButton.addClass("button");
        clickButton.attachListener(() -> clickCount++);
        clickCard.addChild(clickButton);
        root.addChild(clickCard);

        UIElement keyboardCard = new UIElement();
        keyboardCard.addClass("card");
        Button keyboardButton = new Button("Keyboard Test");
        keyboardButton.addClass("button");
        keyboardButton.attachListener(() -> keyboardActivationCount++);
        keyboardCard.addChild(keyboardButton);
        root.addChild(keyboardCard);

        return root;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        uiWindow.init(ctx.getScreenWidth(), ctx.getScreenHeight());
        uiWindow.paintFrame();

        var context = CgUiPaintContext.getInstance();
        String status = String.format(
                "clicks: %d | keyboard activations: %d | sound plays: %d",
                clickCount, keyboardActivationCount, soundPlayCount);
        context.text().draw().at(0, 0).text(status).font(context.getFont().atSize(16)).submit();
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
