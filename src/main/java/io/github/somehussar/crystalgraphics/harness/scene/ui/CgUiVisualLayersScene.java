package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.core.input.SystemInput;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * Minimal, focused harness scene for the Visual Layers feature (opacity isolation + {@code clip: mask}
 * compositing) — deliberately small and uncluttered so a screenshot is easy to read, unlike
 * {@link CgUiStylingScene}'s everything-at-once demo.
 *
 * <p>Two side-by-side comparisons, left = feature ON, right = feature OFF, so any difference is the
 * feature's actual effect, not guesswork:</p>
 * <ul>
 *   <li><b>Row 1 — mask</b>: a rounded, bordered green box with a red child deliberately positioned to
 *       overflow past its top-left corner. Left box has {@code clip: mask} — the red overflow should be
 *       clipped to the inner rounded region. Right box doesn't — the red square should visibly poke out
 *       past the border, unclipped, identically to how the left one looked before the mask was wired up.</li>
 *   <li><b>Row 2 — opacity</b>: two overlapping translucent squares (red, blue) inside a fractional-opacity
 *       parent (left) vs. a full-opacity parent (right). Left should show no seam at the overlap (the pair
 *       blends as one unit before fading); right should show a visible double-blend seam where they cross,
 *       since each child fades against the other independently.</li>
 * </ul>
 *
 * <p>Register in {@link io.github.somehussar.crystalgraphics.harness.SceneRegistry}
 * under scene id {@code "cgui-visual-layers"}.</p>
 */
public class CgUiVisualLayersScene implements InteractiveSceneLifecycle, SystemInput.Keyboard, SystemInput.Mouse {

    private UIWindow uiWindow;

    private static final String STYLE_SHEET = """
            .row {
                gap-all: 40;
                margin-top: 20;
            }

            .mask-box {
                background: #33AA66;
                border-radius: 16px;
                border-width: 3px;
                border-color: #224488;
                padding-all: 4;
            }
            .mask-on {
                clip: mask;
            }
            .mask-child {
                background: #FF4444;
                width: 40;
                height: 40;
                margin-left: -14;
                margin-top: -14;
            }

            .opacity-box {
                width: 60;
                height: 60;
            }
            .opacity-on {
                opacity: 0.5;
            }
            .opacity-child-a {
                background: rgba(255, 0, 0, 0.6);
                width: 36;
                height: 36;
            }
            .opacity-child-b {
                background: rgba(0, 128, 255, 0.6);
                width: 36;
                height: 36;
                margin-left: -18;
                margin-top: 18;
            }
            """;

    @Override
    public void init(HarnessContext ctx) {
        UIElement root = createDemo();
        this.uiWindow = new UIWindow(Ui.of(root));
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheet.parse(STYLE_SHEET));

        // TEMP diagnostic — remove after investigation. Captures a screenshot then exits so it
        // can be inspected directly instead of relying on a human to relay one back.
        ctx.getTaskScheduler().schedule(0.5, "capture", () -> ctx.getArtifactService().requestCapture("snapshot"));
        ctx.getTaskScheduler().schedule(1.0, "shutdown", () -> shutdownRequested = true);
    }

    private volatile boolean shutdownRequested = false;

    private UIElement createDemo() {
        UIElement root = new UIElement()
                .layout(l -> l
                        .width(300)
                        .height(220)
                        .paddingAll(20)
                        .flexDirection(FlexDirection.COLUMN)
                        .alignItems(AlignItems.CENTER)
                );

        // TEMP diagnostic — isolate to just one mask box.
        if (true) {
            UIElement maskOn = new UIElement().layout(l -> l.width(48).height(48));
            maskOn.addClass("mask-box");
            maskOn.addClass("mask-on");
            UIElement maskOnChild = new UIElement();
            maskOnChild.addClass("mask-child");
            maskOn.addChild(maskOnChild);
            root.addChild(maskOn);
            return root;
        }

        UIElement maskRow = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
        maskRow.addClass("row");
        root.addChild(maskRow);

        UIElement maskOn = new UIElement().layout(l -> l.width(48).height(48));
        maskOn.addClass("mask-box");
        maskOn.addClass("mask-on");
        UIElement maskOnChild = new UIElement();
        maskOnChild.addClass("mask-child");
        maskOn.addChild(maskOnChild);
        maskRow.addChild(maskOn);

        UIElement maskOff = new UIElement().layout(l -> l.width(48).height(48));
        maskOff.addClass("mask-box");
        UIElement maskOffChild = new UIElement();
        maskOffChild.addClass("mask-child");
        maskOff.addChild(maskOffChild);
        maskRow.addChild(maskOff);

        UIElement opacityRow = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
        opacityRow.addClass("row");
        root.addChild(opacityRow);

        UIElement opacityOn = new UIElement();
        opacityOn.addClass("opacity-box");
        opacityOn.addClass("opacity-on");
        UIElement opacityOnA = new UIElement();
        opacityOnA.addClass("opacity-child-a");
        UIElement opacityOnB = new UIElement();
        opacityOnB.addClass("opacity-child-b");
        opacityOn.addChild(opacityOnA);
        opacityOn.addChild(opacityOnB);
        opacityRow.addChild(opacityOn);

        UIElement opacityOff = new UIElement();
        opacityOff.addClass("opacity-box");
        UIElement opacityOffA = new UIElement();
        opacityOffA.addClass("opacity-child-a");
        UIElement opacityOffB = new UIElement();
        opacityOffB.addClass("opacity-child-b");
        opacityOff.addChild(opacityOffA);
        opacityOff.addChild(opacityOffB);
        opacityRow.addChild(opacityOff);

        return root;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        uiWindow.init(ctx.getScreenWidth(), ctx.getScreenHeight());
        uiWindow.paintFrame();
    }

    @Override
    public void dispose() {
        uiWindow = null;
    }

    @Override
    public boolean isRunning() {
        return !shutdownRequested; // TEMP diagnostic — remove after investigation
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return true; // TEMP diagnostic — remove after investigation
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
