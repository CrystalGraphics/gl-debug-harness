package io.github.somehussar.crystalgraphics.harness.scene.ui;

import io.github.somehussar.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgSoundService;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;
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
 *       gains a "button_click" entry (via a test {@link CgSoundService} that just counts calls, since
 *       the harness has no real audio backend).</li>
 *   <li>Tab to "Keyboard Test" (focus ring should show via the {@code :focus} pseudo-class), then
 *       press Space (hold + release) or Enter — both should activate it exactly like a mouse click.</li>
 * </ul>
 */
public class CgUiButtonScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** Logical-to-surface scale, as the harness\'s other new-engine scenes use. */
    private static final float SCALE = 2f;

    private UIDocument document;
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
                transition: all 250ms;
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
                padding-all: 7.5px;
            }
            .label {
                color: #FFFFFF;
                font-size: 12;
            }
            """;

    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(false);
        PlatformServiceHarness.getInstance().soundImpl = soundId -> soundPlayCount++;

        UINode root = createButtonDemo();
        this.document = new UIDocument().markFrameThread();
        this.document.boxes().setUiScale(SCALE);
        this.document.append(root);
        this.document.styles().addStylesheet(StyleSheet.parse(STYLE_SHEET));
    }

    private UINode createButtonDemo() {
        UINode root = new UINode()
                .layout(l -> l
                        .paddingAll(16)
                        .flexDirection(FlexDirection.ROW)
                        .gapAll(16)
                        .alignItems(AlignItems.FLEX_START))
                .setFocusPolicy(FocusPolicy.NONE);

        UINode clickCard = new UINode();
        clickCard.addClass("card");
        Button clickButton = new Button("Click Me");
        clickButton.addClass("button");
        clickButton.attachListener(() -> clickCount++);
        clickCard.append(clickButton);
        root.append(clickCard);

        UINode keyboardCard = new UINode();
        keyboardCard.addClass("card");
        Button keyboardButton = new Button("Keyboard Test");
        keyboardButton.addClass("button");
        keyboardButton.attachListener(() -> keyboardActivationCount++);
        keyboardCard.append(keyboardButton);
        root.append(keyboardCard);

        return root;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();
        // SURFACE pixels in, LOGICAL units to lay out in -- the scale lives on the box
        // tree's root transform, so this is the only place the two spaces meet.
        document.frame(frame.getDeltaTime(), w / SCALE, h / SCALE);

        CgUiPaintContext paintContext = CgUiPaintContext.getInstance();
        paintContext.beginFrame(w, h);
        document.paint(paintContext);
        paintContext.endFrame();

        var context = CgUiPaintContext.getInstance();
        String status = String.format(
                "clicks: %d | keyboard activations: %d | sound plays: %d",
                clickCount, keyboardActivationCount, soundPlayCount);
        context.text().draw().at(0, 0).text(status).font(context.getFont().atSize(16)).submit();

        // Startup capture so this scene contributes to the pixel-regression set. Without it the
        // scene runs but writes no artifact, and a "scenes diff to zero" check silently covers
        // nothing here.
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
