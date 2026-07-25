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
 *   <li><b>Row 3 — scissor</b>: same overflowing-red-child setup as row 1, but using {@code clip: scissor}
 *       instead of {@code clip: mask}. Left box clips the red overflow to a hard axis-aligned rectangle
 *       (no rounding, unlike mask); right box doesn't clip at all. Exercises {@code UIElement#paintChildren}'s
 *       scissor path directly (not the mask/opacity FBO path row 1/2 exercise).</li>
 *   <li><b>Row 4 — mask override</b>: left box sets an explicit {@code mask:} (a dim, mostly-transparent
 *       white) — everything inside should visibly darken/fade, since children get multiplied by the
 *       mask's low alpha. Right box has no explicit {@code mask:} — the default mask re-renders the
 *       background fill (fully opaque green), so nothing fades, only shape-clipping applies. Exercises
 *       {@code UIElement#buildDefaultMask}'s {@code mask:} override path.</li>
 * </ul>
 *
 * <p>Register in {@link io.github.somehussar.crystalgraphics.harness.SceneRegistry}
 * under scene id {@code "cgui-visual-layers"}.</p>
 */
public class CgUiVisualLayersScene implements InteractiveSceneLifecycle, SystemInput.Keyboard, SystemInput.Mouse {

    private UIWindow uiWindow;
    // TEMP diagnostic (Item 4a corner hit-test) — set only by the isolated corner-test branch in createDemo().
    private UIElement cornerTestBox;
    private UIElement cornerTestMarker;
    private boolean cornerHitTestRan = false;

    private static final String STYLE_SHEET = """
            .row {
                gap-all: 40;
                margin-top: 10;
            }

            .mask-box:hover {
                background: #44FFFF;
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
            .mask-child:hover {
                background: #FF444488;
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

            .scissor-box {
                background: #33AA66;
                width: 48;
                height: 48;
            }
            .scissor-on {
                clip: scissor;
            }
            .scissor-child {
                background: #FF4444;
                width: 40;
                height: 40;
                margin-left: -14;
                margin-top: -14;
            }

            .mask-override-box {
                background: #33AA66;
                border-radius: 16px;
                border-width: 3px;
                border-color: #224488;
                padding-all: 4;
                clip: mask;
            }
            .mask-override-on {
                mask: rgba(255, 255, 255, 0.3);
            }

            .corner-test-box {
                background: #33AA66;
                border-radius: 30px;
                padding-all: 10;
                width: 80;
                height: 80;
                clip: scissor;
            }
            .corner-test-marker {
                background: #FF4444;
                width: 60;
                height: 60;
            }

            .sprite-mask-box {
                background: sprite("harness:textures/9slice_frame.png", "0 0 24 24", "4 4 4 4");
                width: 80;
                height: 80;
                clip: mask;
            }
            .sprite-mask-marker {
                background: #FFEE33;
                width: 80;
                height: 80;
            }
            """;

    @Override
    public void init(HarnessContext ctx) {
        UIElement root = createDemo();
        this.uiWindow = new UIWindow(Ui.of(root));
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheet.parse(STYLE_SHEET));

        // Captures a screenshot then exits so it can be inspected directly instead of relying
        // on a human to relay one back.
        ctx.getTaskScheduler().schedule(0.5, "capture", () -> ctx.getArtifactService().requestCapture("snapshot"));
        ctx.getTaskScheduler().schedule(1.0, "shutdown", () -> shutdownRequested = true);
    }

    private volatile boolean shutdownRequested = false;

    private UIElement createDemo() {
        UIElement root = new UIElement()
                .layout(l -> l
                        .paddingAll(20)
                        .flexDirection(FlexDirection.COLUMN)
                        .alignItems(AlignItems.CENTER)
                );

        // TEMP diagnostic — isolate to just one mask box.
//        if (true) {
//            UIElement maskOn = new UIElement().layout(l -> l.width(48).height(48));
//            maskOn.addClass("mask-box");
//            maskOn.addClass("mask-on");
//            UIElement maskOnChild = new UIElement();
//            maskOnChild.addClass("mask-child");
//            maskOn.addChild(maskOnChild);
//            root.addChild(maskOn);
//            return root;
//        }

        // TEMP diagnostic — isolate to just the mask-override row (verifying the `mask:` override path).
//        if (true) {
//            UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
//            row.addClass("row");
//            root.addChild(row);
//
//            UIElement on = new UIElement().layout(l -> l.width(48).height(48));
//            on.addClass("mask-override-box");
//            on.addClass("mask-override-on");
//            UIElement onChild = new UIElement();
//            onChild.addClass("mask-child");
//            on.addChild(onChild);
//            row.addChild(on);
//
//            UIElement off = new UIElement().layout(l -> l.width(48).height(48));
//            off.addClass("mask-override-box");
//            UIElement offChild = new UIElement();
//            offChild.addClass("mask-child");
//            off.addChild(offChild);
//            row.addChild(off);
//
//            return root;
//        }

        // TEMP diagnostic — isolate to just the heavily-rounded corner hit-test box (Item 4a).
        // border-radius:30 + padding:10 shrinks the content-box's own corner radius to 20 (30-10),
        // still positive — so there's a real "corner gap" between the content-box's rounded shape and
        // its plain AABB, where a 60x60 marker (exactly matching content-box size) sits. Sweeping
        // getHoveredElement() near the box's outer corner (see render()) confirmed: points in that gap
        // resolve to the parent box (recursion into the marker correctly blocked there), while points
        // further into the content area correctly resolve to the marker — i.e. isMouseOverContent is
        // corner-radius-aware, not a plain rectangle test.
//        if (true) {
//            UIElement box = new UIElement().layout(l -> l.width(80).height(80));
//            box.addClass("corner-test-box");
//            UIElement marker = new UIElement();
//            marker.addClass("corner-test-marker");
//            box.addChild(marker);
//            root.addChild(box);
//            this.cornerTestBox = box;
//            this.cornerTestMarker = marker;
//            return root;
//        }

        // TEMP diagnostic — isolate to just the 9-slice sprite mask box (Items 3+5). The sprite
        // (assets/harness/textures/9slice_frame.png) has an opaque blue 4px border and a fully
        // transparent center — with no explicit `mask:` override, the default mask re-renders this
        // same sprite, so a bright-yellow marker filling the whole box should show through only in
        // the border-band ring (mask alpha=1 there) and be fully masked out in the center square
        // (mask alpha=0 there) — a shape no synthesized solid rounded rect could produce.
        // Verified: the yellow marker shows through only as a ring in the border band (texture
        // opaque there) and is fully masked out in the center (texture transparent there) — the
        // default mask correctly follows the sprite's own alpha shape, not a synthesized rounded rect.
//        if (true) {
//            UIElement box = new UIElement().layout(l -> l.width(80).height(80));
//            box.addClass("sprite-mask-box");
//            UIElement marker = new UIElement();
//            marker.addClass("sprite-mask-marker");
//            box.addChild(marker);
//            root.addChild(box);
//            return root;
//        }

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

        UIElement scissorRow = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
        scissorRow.addClass("row");
        root.addChild(scissorRow);

        UIElement scissorOn = new UIElement().layout(l -> l.width(48).height(48));
        scissorOn.addClass("scissor-box");
        scissorOn.addClass("scissor-on");
        UIElement scissorOnChild = new UIElement();
        scissorOnChild.addClass("scissor-child");
        scissorOn.addChild(scissorOnChild);
        scissorRow.addChild(scissorOn);

        UIElement scissorOff = new UIElement().layout(l -> l.width(48).height(48));
        scissorOff.addClass("scissor-box");
        UIElement scissorOffChild = new UIElement();
        scissorOffChild.addClass("scissor-child");
        scissorOff.addChild(scissorOffChild);
        scissorRow.addChild(scissorOff);

        UIElement maskOverrideRow = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
        maskOverrideRow.addClass("row");
        root.addChild(maskOverrideRow);

        // Left: explicit `mask:` override (a dim, mostly-transparent white) — should visibly darken/fade
        // everything inside, unlike the default (mask = background reused, fully opaque, no fade at all).
        UIElement maskOverrideOn = new UIElement().layout(l -> l.width(48).height(48));
        maskOverrideOn.addClass("mask-override-box");
        maskOverrideOn.addClass("mask-override-on");
        UIElement maskOverrideOnChild = new UIElement();
        maskOverrideOnChild.addClass("mask-child");
        maskOverrideOn.addChild(maskOverrideOnChild);
        maskOverrideRow.addChild(maskOverrideOn);

        // Right: no explicit `mask:` — default mask re-renders the background fill (Item 5), fully
        // opaque green, so nothing inside gets faded; only shape clipping applies.
        UIElement maskOverrideOff = new UIElement().layout(l -> l.width(48).height(48));
        maskOverrideOff.addClass("mask-override-box");
        UIElement maskOverrideOffChild = new UIElement();
        maskOverrideOffChild.addClass("mask-child");
        maskOverrideOff.addChild(maskOverrideOffChild);
        maskOverrideRow.addChild(maskOverrideOff);

        return root;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        uiWindow.init(ctx.getScreenWidth(), ctx.getScreenHeight());
        uiWindow.paintFrame();
        // TEMP diagnostic — capture the first several frames individually to inspect startup pop-in.
        if (frame.getFrameNumber() <= 10) {
            ctx.getArtifactService().requestCapture("startupframe" + frame.getFrameNumber());
        }

        // TEMP diagnostic (Item 4a) — sweep a small grid around where the corner-test box's exact
        // outer top-left corner is expected (root padding 20 logical, uiScale 2, root origin at
        // physical (0,0)), and report what getHoveredElement() resolves to at each point. Before the
        // fix, points right at the geometric corner (outside the rounded shape, inside the plain
        // AABB) would resolve to the marker; after the fix, they should not.
        if (cornerTestBox != null && !cornerHitTestRan && frame.getFrameNumber() == 5) {
            cornerHitTestRan = true;
            for (int x = 0; x < ctx.getScreenWidth(); x += 10) {
                for (int y = 0; y < ctx.getScreenHeight(); y += 10) {
                    UIElement hovered = uiWindow.getHoveredElement(x, y);
                    if (hovered == cornerTestMarker) {
                        System.out.println("TEMPDEBUG cornerHitTest MARKER at (" + x + "," + y + ")");
                    } else if (hovered == cornerTestBox) {
                        System.out.println("TEMPDEBUG cornerHitTest box at (" + x + "," + y + ")");
                    }
                }
            }
        }
    }

    @Override
    public void dispose() {
        uiWindow = null;
    }

    @Override
    public boolean isRunning() {
        return !shutdownRequested;
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
