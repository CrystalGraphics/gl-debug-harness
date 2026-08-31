package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.widget.layout.PageStack;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.overlay.DialogManager;
import com.crystalgui.widget.overlay.InputDialog;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.control.CheckboxGroup;
import com.crystalgui.widget.control.ProgressBar;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.Switch;
import com.crystalgui.widget.control.SymbolIcon;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.form.ColorSelector;
import com.crystalgui.widget.form.SearchField;
import com.crystalgui.widget.overlay.Dropdown;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.MenuItem;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.scroll.ScrollerView;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import org.joml.Matrix4f;

/**
 * <b>Every widget the M6 port has moved, on the NEW engine, in one scrolling column.</b>
 *
 * <p>{@code --mode=cgui-new-gallery}. The counterpart to {@code cgui-gallery}, which is the same idea
 * over {@code UIWindow}/{@code UIElement} — this one builds a {@link UIDocument} and paints through
 * the box tree, so what it shows is the ported widget rendering itself under the ported cascade.</p>
 *
 * <h3>What it is for, and what it can only be told by eye</h3>
 *
 * <p>The port has tests for the things a test can see: a kind is registered, a part carries its name,
 * a subclass answers its own tag, a state change reports once. What none of them can see is whether a
 * widget <em>draws</em>. A part that stayed a class still exists, still lays out, and still paints —
 * as an unstyled box. A {@code ::part()} twin that was added to the wrong sheet, or with the host's
 * pseudo-class in the wrong place, produces a rule that matches nothing and reports nothing. That is
 * the whole failure mode {@code SheetPortTest} exists to bound and cannot actually detect, and it is
 * what one look at this scene answers.</p>
 *
 * <h3>It scrolls, and that is a widget under test too</h3>
 *
 * <p>The column is a {@link ScrollerView}, so the scene exercises the one composite in the batch that
 * takes a caller's content — through a {@link com.crystalgui.ui.dom.UISlot}, which is what replaced
 * {@code acceptsPublicChildren}. If the slot were missing, every section below would be in the tree
 * and on screen nowhere, which is exactly the shape that reads as "the scene did not build".</p>
 *
 * <p>Interactive: mouse and keyboard go to {@link UIDocument}'s input service, so hover, press, focus,
 * dragging a slider and typing in a field are all real here. A scene that only painted would miss
 * every state rule in the sheets, which is most of them.</p>
 */
public class CgUiNewEngineGalleryScene
        implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /**
     * The same {@code uiScale} the other CrystalGUI scenes run at.
     *
     * <p>Given to the BOX TREE ({@code setRootTransform}) and never applied to the paint pose by hand.
     * That is the engine's own rule with one word changed: the root transform is the single definition
     * of what {@code uiScale} means, so layout composes every box's world matrix through it, the
     * painter takes its pose from that matrix, and hit-testing inverts the same one. Scale the pose
     * instead and the two disagree by exactly the scale factor — the picture is right and every click
     * lands at half the distance, which is what "the mouse input Y is shifted down" was.</p>
     *
     * <p>Deliberately not in the ortho projection either: {@code CgTextRenderer} picks its glyph
     * raster size off the pose scale, so a projection-side scale rasterizes glyphs at logical size and
     * magnifies them — blurry text, and only the text.</p>
     */
    private static final float SCALE = 2f;

    private UIDocument document;
    private ProgressBar determinate;
    private float elapsed;

    @Override
    public void init(HarnessContext ctx) {
        document = new UIDocument().markFrameThread();
        // THE USER-AGENT SHEET, which is not installed for you -- a scene that asserts on default.css
        // behaviour without this exercises no CSS at all and looks like a styling regression.
        document.styles().addStylesheet(StyleSheet.DEFAULT);
        document.styles().addStylesheet(StyleSheet.parse(SCENE_CSS));
        document.boxes().setRootTransform(new Matrix4f().scale(SCALE, SCALE, 1f));
        document.append(buildRoot());
    }

    /**
     * The scene's own layout only — every widget's appearance comes from the UA sheet.
     *
     * <p>Nothing here names a widget: if a button is the wrong size or a slider draws no track, that
     * is the port to fix and not this string. A scene sheet that styled the subjects would be a scene
     * that passes whatever the widgets do.</p>
     */
    private static final String SCENE_CSS = """
            #page {
                /* Stated rather than relied on. It USED to be load-bearing: BoxStyle wrote CSS's
                   initials for anything unset, so an unset direction was `row` where the old bridge
                   defaulted to `column`, and this scene laid out sideways with every section on one
                   line and zero-high -- which is what "nothing is drawing" turned out to be. D5.8 was
                   reversed at 6.1 and both engines answer `column` now; the declaration stays because
                   a layout this scene depends on should not be inherited from a default. */
                flex-direction: column;
                width: 100%;
                height: 100%;
                background-color: #1E1E1E;
                padding-all: 12;
            }
            #column {
                width: 100%;
                flex-direction: column;
                gap-all: 14;
                /* Room for the vertical bar, which is absolutely positioned and overlays the content. */
                padding-right: 14;
            }
            .section {
                width: 100%;
                flex-direction: column;
                align-items: flex-start;
                gap-all: 6;
                padding-all: 10;
                background-color: #252526;
                border-radius: 4;
            }
            .heading {
                color: #4FC1FF;
                font-size: 13;
            }
            .row {
                width: 100%;
                flex-direction: row;
                gap-all: 10;
                align-items: center;
            }
            /* THE THREE THAT NEED A BOX TO LIVE IN. A SplitView divides what it is given and a TabView's
               panes fill what is left, so both measure to nothing inside a content-sized column -- and
               a dialog stage is a positioning context for absolutely placed windows, which has no
               content to be sized by at all. Fixed heights here, not in the widgets. */
            .stage {
                width: 100%;
                height: 240;
                background-color: #1B1B1B;
                border-radius: 4;
            }
            .split-demo { width: 100%; height: 180; }
            .tabs-demo  { width: 100%; height: 200; }
            .pane-body {
                width: 100%;
                height: 0;
                flex-grow: 1;
                flex-direction: column;
                align-items: center;
                justify-content: center;
                background-color: #2D2D30;
            }
            """;

    private UINode buildRoot() {
        UINode page = new UINode().setId("page");

        ScrollerView scroller = new ScrollerView();
        StyleGroup.inlinePipeline(scroller.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).height(0f).flexGrow(1f));
        page.append(scroller);

        UINode column = new UINode().setId("column");
        scroller.append(column);

        Button disabled = new Button("Disabled");
        disabled.setEnabled(false);
        column.append(section("Button", row(
                new Button("Ordinary"), disabled,
                new Button("With icon").setPreIcon(new UINode()))));

        Checkbox one = new Checkbox("First");
        Checkbox two = new Checkbox("Second");
        CheckboxGroup group = new CheckboxGroup().allowEmpty(false);
        one.setGroup(group);
        two.setGroup(group);
        one.setChecked(true);
        column.append(section("Checkbox + CheckboxGroup (exclusive, allowEmpty false)",
                row(new Checkbox("Standalone"), one, two)));

        column.append(section("Switch", row(new Switch(), new Switch().setChecked(true))));

        Slider slider = new Slider();
        slider.setRange(0f, 100f);
        slider.setValue(35f);
        StyleGroup.inlinePipeline(slider.getStyle().getLayoutGroup(), l -> l.width(220f));
        column.append(section("Slider (drag it)", row(slider)));

        determinate = new ProgressBar();
        determinate.setFraction(0.4f);
        StyleGroup.inlinePipeline(determinate.getStyle().getLayoutGroup(), l -> l.width(180f).height(6f));
        ProgressBar indeterminate = new ProgressBar();
        indeterminate.setFraction(-1f);
        StyleGroup.inlinePipeline(indeterminate.getStyle().getLayoutGroup(), l -> l.width(180f).height(6f));
        column.append(section("ProgressBar — determinate (animates) and indeterminate (sweeps)",
                row(determinate, indeterminate)));

        TextField field = new TextField();
        field.setText("Type in me");
        StyleGroup.inlinePipeline(field.getStyle().getLayoutGroup(), l -> l.width(220f));
        column.append(section("TextField", row(field)));

        SearchField search = new SearchField();
        StyleGroup.inlinePipeline(search.getStyle().getLayoutGroup(), l -> l.width(240f));
        column.append(section("SearchField (icon, field, clear)", row(search)));

        Dropdown dropdown = new Dropdown("Pick one");
        dropdown.addOption("Alpha");
        dropdown.addOption("Beta");
        dropdown.addOption("Gamma");
        column.append(section("Dropdown (click to open its Menu)", row(dropdown)));

        Button menuAnchor = new Button("Open a menu");
        Menu menu = new Menu();
        menu.addItem(new MenuItem("Cut"));
        menu.addItem(new MenuItem("Copy"));
        menu.addItem(new MenuItem("Paste"));
        page.append(menu);
        menuAnchor.attachListener(() -> menu.showFor(menuAnchor, menuAnchor));
        column.append(section("Menu + MenuItem", row(menuAnchor)));

        Button tipped = new Button("Hover me");
        Tooltip.attach(tipped, "A tooltip, placed after layout");
        column.append(section("Tooltip", row(tipped)));

        Button popAnchor = new Button("Toggle a popover");
        Popover popover = new Popover();
        UIText popText = new UIText("A bare Popover: light children, no shadow root.");
        popover.append(popText);
        page.append(popover);
        popAnchor.attachListener(() -> {
            if (popover.isOpen()) popover.hide();
            else popover.showFor(popAnchor, popAnchor);
        });
        column.append(section("Popover", row(popAnchor)));

        SymbolIcon cls = new SymbolIcon();
        cls.show(com.crystalgui.text.lang.SymbolKind.CLASS, java.util.Set.of());
        SymbolIcon iface = new SymbolIcon();
        iface.show(com.crystalgui.text.lang.SymbolKind.INTERFACE, java.util.Set.of());
        SymbolIcon marked = new SymbolIcon();
        marked.show(com.crystalgui.text.lang.SymbolKind.METHOD,
                java.util.Set.of(com.crystalgui.text.lang.SymbolModifier.STATIC,
                        com.crystalgui.text.lang.SymbolModifier.FINAL));
        column.append(section("SymbolIcon — class, interface, and a method with static+final marks",
                row(cls, iface, marked)));

        ColorSelector colours = new ColorSelector();
        column.append(section("ColorSelector — the deepest composite in the batch", row(colours)));

        // ── 6.2: the dialogs and the layout composites ─────────────────────
        column.append(section("SplitView — drag either divider; the right pane holds a nested vertical one",
                splitView()));
        column.append(section("TabView + Tab — click a tab, close one with its X, and the strip scrolls",
                tabView()));
        column.append(section("PageStack — one page at a time, built on first show",
                pageStack()));
        column.append(section("Dialog + DialogManager — drag a title bar, click to raise, X closes",
                dialogs()));
        column.append(section("InputDialog — a prompt and a confirm, both centred once measured",
                inputDialogs()));

        return page;
    }

    /**
     * Two dividers and a nested split, which is where a divider drag goes wrong first.
     *
     * <p>Nested because the outer split's travel is measured against its own content box minus its
     * dividers, and a split inside a pane is the shape that gets that arithmetic wrong — the inner
     * one's travel must come from the pane it was given, not from the window.</p>
     */
    private UINode splitView() {
        SplitView split = new SplitView();
        split.addClass("split-demo");
        // 0..100, not 0..1 -- matching LDLib2's 5..95 defaults, which is what the widget documents.
        split.setPercentage(40f).setLimits(15f, 85f);
        split.first().append(paneBody("first pane"));

        SplitView nested = new SplitView();
        nested.setOrientation(SplitView.Orientation.VERTICAL);
        nested.first().append(paneBody("nested top"));
        nested.second().append(paneBody("nested bottom"));
        split.second().append(nested);
        return split;
    }

    /**
     * Enough tabs to overflow the strip, so the rail scrolls and the strip bar appears.
     *
     * <p>The bar is the thing to watch: it is derived from measured sizes and refreshed from a
     * post-layout hook, which replaced the two overrides the old engine needed ({@code setScroll} and
     * {@code onLayoutChanged}). If it never appears, that hook is not running.</p>
     */
    private UINode tabView() {
        TabView tabs = new TabView();
        tabs.addClass("tabs-demo");
        tabs.setTabSide(TabView.TabSide.TOP);
        for (String name : new String[] {"one", "two", "three", "four", "five", "six", "seven"}) {
            Tab tab = tabs.addTab(name);
            tab.setClosable(true);
            tab.onCloseRequested.connect(() -> tabs.removeTab(tab));
            tab.content().append(paneBody("pane " + name));
        }
        return tabs;
    }

    /** One page at a time, each built by the factory the first time it is asked for. */
    private UINode pageStack() {
        PageStack<String> stack = new PageStack<>();
        stack.addClass("stage");
        stack.setPageFactory(key -> paneBody("page " + key));
        stack.setPlaceholder(paneBody("nothing shown"));

        UINode controls = row();
        for (String key : new String[] {"alpha", "beta", "gamma"}) {
            Button open = new Button(key);
            open.attachListener(() -> stack.show(key));
            controls.append(open);
        }
        Button none = new Button("none");
        none.attachListener(() -> stack.show(null));
        controls.append(none);

        UINode box = new UINode();
        StyleGroup.inlinePipeline(box.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).flexDirection(FlexDirection.COLUMN).gapAll(8f));
        box.append(controls);
        box.append(stack);
        return box;
    }

    /**
     * Three managed dialogs on a stage of their own.
     *
     * <p>The stage is an ordinary node: {@link DialogManager} places and stacks what it is given, and
     * a dialog moves by INLINE insets rather than a transform. <b>They do not resize</b> — D6 chose a
     * resize mode over an edge band and 6.0 did not build one, so the three {@code UIResizer} hooks
     * were deleted with the port, and this is what that looks like on screen.</p>
     */
    private UINode dialogs() {
        UINode stage = new UINode().addClass("stage");
        DialogManager manager = new DialogManager(stage);

        Dialog first = manager.manage(new Dialog("panel one"));
        first.getContent().append(hint("drag my title bar"));

        Dialog second = manager.manage(new Dialog("panel two"));
        second.getContent().append(new Button("a button"));

        Dialog third = manager.manage(new Dialog("panel three"));
        third.getContent().append(hint("click me to raise"));

        UINode controls = row();
        Button openAll = new Button("open all");
        openAll.attachListener(manager::showAll);
        controls.append(openAll);
        Button closeAll = new Button("close all");
        closeAll.attachListener(manager::closeAll);
        controls.append(closeAll);
        Button spawn = new Button("new window");
        spawn.attachListener(() -> manager.manage(
                new Dialog("panel " + (manager.getDialogs().size() + 1))).show());
        controls.append(spawn);

        UINode box = new UINode();
        StyleGroup.inlinePipeline(box.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).flexDirection(FlexDirection.COLUMN).gapAll(8f));
        box.append(controls);
        box.append(stage);
        return box;
    }

    /**
     * The two static prompts, and the one thing worth watching is WHERE they appear.
     *
     * <p>A prompt is out of flow, so its size is unknown until it has been laid out: it opens
     * transparent (a {@code __placing__} class the sheet owns) and centres itself from a post-layout
     * hook, then drops the class. A prompt that flashes at the top-left before settling means that
     * hook ran before layout; one that never appears at all means the class was never dropped.</p>
     */
    private UINode inputDialogs() {
        UINode controls = row();
        Button ask = new Button("ask for a name");
        ask.attachListener(() -> InputDialog.ask(ask, "New file", "name", "untitled.txt", name -> { }));
        controls.append(ask);
        Button confirm = new Button("confirm a deletion");
        confirm.attachListener(() -> InputDialog.confirm(
                confirm, "Delete", "Delete untitled.txt?", () -> { }));
        controls.append(confirm);
        return controls;
    }

    /** A filled pane body, so an empty split or tab reads as empty rather than as broken. */
    private UINode paneBody(String label) {
        UINode body = new UINode().addClass("pane-body");
        body.append(hint(label));
        return body;
    }

    /** A label that never eats a press — a pane's text must not shadow the pane. */
    private UIText hint(String text) {
        UIText label = new UIText(text);
        label.setHitTest(false);
        return label;
    }

    private UINode section(String heading, UINode body) {
        UINode section = new UINode().addClass("section");
        UIText title = new UIText(heading);
        title.addClass("heading");
        section.append(title);
        section.append(body);
        return section;
    }

    private UINode row(UINode... children) {
        UINode row = new UINode().addClass("row");
        StyleGroup.defaultPipeline(row.getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
        for (UINode child : children) {
            if (child != null) row.append(child);
        }
        return row;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();
        float delta = frame.getDeltaTime();

        // The determinate bar walks, so a single glance says whether a value write reaches the fill
        // -- a frozen bar at its first value is what the old ProgressBar's non-idempotent setter
        // looked like from the other side.
        elapsed += delta;
        determinate.setFraction((elapsed % 4f) / 4f);

        document.frame(delta, w / SCALE, h / SCALE);

        CgUiPaintContext context = CgUiPaintContext.getInstance();
        context.beginFrame(w, h);
        document.paint(context);
        context.endFrame();

        // A startup capture, so this scene contributes to the pixel-regression set. Without it the
        // scene runs and writes no artifact, and a "scenes diff to zero" check silently covers
        // nothing here.
        if (frame.getFrameNumber() == 5) ctx.getArtifactService().requestCapture("startup");
    }

    @Override
    public void dispose() {
        document = null;
        determinate = null;
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
