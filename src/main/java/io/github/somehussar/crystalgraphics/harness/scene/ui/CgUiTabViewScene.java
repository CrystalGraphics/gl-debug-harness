package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * Exercises {@code TabView} — click a tab, or Tab to one and use the arrow keys.
 *
 * <p>This scene carries the parts of the widget that {@code TabViewTest} deliberately cannot: a Tab
 * contains a {@code UIText}, so laying one out needs FreeType and the headless suite skips layout
 * entirely. Everything geometric therefore has to be verified here.</p>
 *
 * <ul>
 *   <li><b>All four sides.</b> The buttons across the top move the strip; each side changes the root's
 *       flex-direction and the state class, and {@code tabview.__left__ .__strip__} has to flip the
 *       strip's own axis from CSS alone.</li>
 *   <li><b>Strip overflow.</b> The second TabView has more tabs than fit, so the strip must pan on the
 *       wheel rather than overflowing the widget or squashing the tabs.</li>
 *   <li><b>Real content, not empty panes.</b> The panes hold actual widgets, because a hidden pane
 *       whose children are still hit-testable or still in the tab order is invisible in a demo made of
 *       empty boxes — precisely the bug found while building this (see
 *       {@code UINode.hasFocusableDescendant}).</li>
 * </ul>
 */
public class CgUiTabViewScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** Logical-to-surface scale, as the harness\'s other new-engine scenes use. */
    private static final float SCALE = 2f;

    private UIDocument document;
    private TabView tabs;
    private TabView crowded;

    private static final String STYLES = """
            /* Sized to fit 800x600 at the default uiScale of 2 — 380 logical is 760 physical. */
            .demo-root  { width: 380px; height: 270px; flex-direction: column; gap-all: 6px;
                          padding-all: 6px; background: #202020; }
            .sides      { flex-direction: row; gap-all: 4px; }
            .side-btn   { width: 44px; }
            .main       { width: 368px; height: 140px; }
            .crowded    { width: 180px; height: 64px; }
            .bottom     { flex-direction: row; gap-all: 6px; }
            .filler     { background: #4A5A6A; height: 34px; width: 100%; }
            """;

    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(true);
        this.document = new UIDocument().markFrameThread();
        this.document.boxes().setUiScale(SCALE);
        this.document.append(createDemo());
        this.document.styles().addStylesheet(StyleSheet.DEFAULT);
        this.document.styles().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        this.document.styles().addStylesheet(StyleSheet.parse(STYLES));
    }

    private UINode createDemo() {
        UINode root = new UINode()
                .layout(l -> l.width(380).height(270)
                        .paddingAll(6).flexDirection(FlexDirection.COLUMN).gapAll(6))
                .setFocusPolicy(FocusPolicy.NONE);
        root.addClass("demo-root");

        root.append(sideSwitcher());

        tabs = new TabView();
        tabs.addClass("main");
        root.append(tabs);

        // Focusable content in each pane: switching tabs must take the hidden pane's widgets out of
        // the tab order, not merely out of sight.
        Tab first = tabs.addTab("Widgets");
        first.content().append(new Button("a button"));
        first.content().append(new Checkbox("a checkbox"));

        Tab second = tabs.addTab("Slider");
        second.content().append(new Slider());

        Tab third = tabs.addTab("Text");
        third.content().append(new UIText("Just some text in the third pane."));

        UINode bottom = new UINode();
        bottom.addClass("bottom");
        root.append(bottom);

        // More tabs than the strip can show, so the wheel has to pan it.
        crowded = new TabView();
        crowded.addClass("crowded");
        for (int i = 1; i <= 8; i++) {
            UINode filler = new UINode();
            filler.addClass("filler");
            crowded.addTab("tab " + i).content().append(filler);
        }
        bottom.append(crowded);

        return root;
    }

    private UINode sideSwitcher() {
        UINode row = new UINode();
        row.addClass("sides");
        for (TabView.TabSide side : TabView.TabSide.values()) {
            Button button = new Button(side.name().toLowerCase());
            button.addClass("side-btn");
            button.attachListener(() -> tabs.setTabSide(side));
            row.append(button);
        }
        return row;
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
        Tab selected = tabs.getSelectedTab();
        context.text().draw().at(0, 0)
                .text(String.format("TabView — side=%s  selected=%s (%d/%d)  wheel over the small strip pans it",
                        tabs.getTabSide(),
                        selected == null ? "none" : selected.getText(),
                        tabs.getSelectedIndex() + 1, tabs.getTabCount()))
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
