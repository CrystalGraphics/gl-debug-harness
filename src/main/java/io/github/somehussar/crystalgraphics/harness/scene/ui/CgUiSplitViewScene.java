package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgui.core.input.SystemInput;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.SplitView;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * Exercises {@code SplitView} — drag the dividers to resize.
 *
 * <p>Three things are being verified that a single split wouldn't show:</p>
 * <ul>
 *   <li><b>Both orientations.</b> The vertical one proves the drag tracks the Y delta rather than X,
 *       and that {@code splitview.__vertical__ .__divider__} flips the divider's own axis from CSS.</li>
 *   <li><b>Nesting.</b> A split inside another split's pane is LDLib2's answer to more than two panes,
 *       and it's the case where a mixed-up coordinate basis would show immediately.</li>
 *   <li><b>The oversized-content trap.</b> The bottom-left pane holds a deliberately huge child. Without
 *       {@code flex-basis: 0} + {@code min-width: 0} on the panes, flexbox's {@code min-size: auto}
 *       would refuse to shrink it and the split would jam — a failure completely invisible in a demo
 *       built from empty panes, which is exactly why one is included here.</li>
 * </ul>
 */
public class CgUiSplitViewScene implements InteractiveSceneLifecycle, SystemInput.Keyboard, SystemInput.Mouse {

    private UIWindow uiWindow;
    private SplitView horizontal;
    private SplitView vertical;

    private static final String STYLES = """
            .pane-a  { background-color: #3A4A6A; padding-all: 4px; }
            .pane-b  { background-color: #4A3A5A; padding-all: 4px; }
            .pane-c  { background-color: #3A5A4A; padding-all: 4px; }
            .pane-d  { background-color: #5A4A3A; padding-all: 4px; }
            .huge    { background-color: #8A3A3A; width: 2000px; height: 20px; }
            /* No min-width/min-height needed here any more. `overflow: hidden` now feeds Taffy, so
             * the pane holding the 2000px child below contributes a zero automatic minimum size and
             * stops dragging this frame (and the whole panel) out to 2000px wide. It used to. */
            .demo-root { width: 330px; height: 260px; }
            .frame     { width: 310px; height: 110px; }
            """;

    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(true);
        this.uiWindow = new UIWindow(Ui.of(createDemo()));
//        this.uiWindow.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheet.parse(STYLES));
    }

    private UIElement createDemo() {
        // Sized from the stylesheet (.demo-root below), which only works because UIWindow now
        // recomputes the root's placement per layout instead of once in init(). It used to have to be
        // set in Java: init() measured the root before any stylesheet had been applied and then
        // early-returned forever, leaving a CSS-sized root permanently mis-positioned.
        UIElement root = new UIElement()
                .layout(l -> l.paddingAll(10).flexDirection(FlexDirection.COLUMN).gapAll(8))
                .setFocusPolicy(FocusPolicy.NONE);
        root.addClass("panel");
        root.addClass("demo-root");

        // Horizontal, with a nested vertical split in its right pane.
        horizontal = new SplitView();
        horizontal.setPercentage(40f);
        horizontal.first().addClass("pane-a");
        horizontal.first().addChild(label("left 40%"));

        SplitView nested = new SplitView();
        nested.setOrientation(SplitView.Orientation.VERTICAL);
        nested.setPercentage(50f);
        nested.first().addClass("pane-b");
        nested.first().addChild(label("nested top"));
        nested.second().addClass("pane-c");
        nested.second().addChild(label("nested bottom"));
        horizontal.second().addChild(nested);

        root.addChild(frame(horizontal));

        // Vertical, whose top pane holds far more content than its share.
        vertical = new SplitView();
        vertical.setOrientation(SplitView.Orientation.VERTICAL);
        vertical.setPercentage(35f);
        vertical.first().addClass("pane-d");
        vertical.first().addChild(label("top 35% — holds a 2000px child"));
        UIElement huge = new UIElement();
        huge.addClass("huge");
        vertical.first().addChild(huge);
        vertical.second().addClass("pane-a");
        vertical.second().addChild(label("bottom"));

        root.addChild(frame(vertical));

        return root;
    }

    /** SplitView is 100%x100% by default, so it needs a sized host to live in. */
    private UIElement frame(UIElement content) {
        UIElement frame = new UIElement();
        frame.addClass("frame");
        frame.addChild(content);
        return frame;
    }

    private UIElement label(String text) {
        UIText t = new UIText(text);
        t.addClass("label");
        return t;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        uiWindow.init(ctx.getScreenWidth(), ctx.getScreenHeight());
        uiWindow.paintFrame();

        var context = CgUiPaintContext.getInstance();
        context.text().draw().at(0, 0)
                .text(String.format("SplitView — drag the dividers.  h=%.1f%%  v=%.1f%%",
                        horizontal.getPercentage(), vertical.getPercentage()))
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
    public boolean consumeKeyboardEvent(SystemInput.Keyboard.Event event) {
        return uiWindow.getInputHandler().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(SystemInput.Mouse.Event event) {
        return uiWindow.getInputHandler().consumeMouseEvent(event);
    }
}
