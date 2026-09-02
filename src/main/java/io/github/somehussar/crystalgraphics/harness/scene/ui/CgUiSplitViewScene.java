package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.text.UIText;
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
public class CgUiSplitViewScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** Logical-to-surface scale, as the harness\'s other new-engine scenes use. */
    private static final float SCALE = 2f;

    private UIDocument document;
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
//        this.document.styles().addStylesheet(StyleSheet.DEFAULT);
        this.document.styles().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        this.document.styles().addStylesheet(StyleSheet.parse(STYLES));
    }

    private UINode createDemo() {
        // Sized from the stylesheet (.demo-root below), which only works because UIDocument now
        // recomputes the root's placement per layout instead of once in init(). It used to have to be
        // set in Java: init() measured the root before any stylesheet had been applied and then
        // early-returned forever, leaving a CSS-sized root permanently mis-positioned.
        UINode root = new UINode()
                .layout(l -> l.paddingAll(10).flexDirection(FlexDirection.COLUMN).gapAll(8))
                .setFocusPolicy(FocusPolicy.NONE);
        root.addClass("panel");
        root.addClass("demo-root");

        // Horizontal, with a nested vertical split in its right pane.
        horizontal = new SplitView();
        horizontal.setPercentage(40f);
        horizontal.first().addClass("pane-a");
        horizontal.first().append(label("left 40%"));

        SplitView nested = new SplitView();
        nested.setOrientation(SplitView.Orientation.VERTICAL);
        nested.setPercentage(50f);
        nested.first().addClass("pane-b");
        nested.first().append(label("nested top"));
        nested.second().addClass("pane-c");
        nested.second().append(label("nested bottom"));
        horizontal.second().append(nested);

        root.append(frame(horizontal));

        // Vertical, whose top pane holds far more content than its share.
        vertical = new SplitView();
        vertical.setOrientation(SplitView.Orientation.VERTICAL);
        vertical.setPercentage(35f);
        vertical.first().addClass("pane-d");
        vertical.first().append(label("top 35% — holds a 2000px child"));
        UINode huge = new UINode();
        huge.addClass("huge");
        vertical.first().append(huge);
        vertical.second().addClass("pane-a");
        vertical.second().append(label("bottom"));

        root.append(frame(vertical));

        return root;
    }

    /** SplitView is 100%x100% by default, so it needs a sized host to live in. */
    private UINode frame(UINode content) {
        UINode frame = new UINode();
        frame.addClass("frame");
        frame.append(content);
        return frame;
    }

    private UINode label(String text) {
        UIText t = new UIText(text);
        t.addClass("label");
        return t;
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
