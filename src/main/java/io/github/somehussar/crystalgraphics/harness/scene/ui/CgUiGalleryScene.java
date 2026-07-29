package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgui.core.input.SystemInput;
import com.crystalgui.core.input.keyboard.CgUiKeyCodes;
import com.crystalgui.core.property.Property;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.property.visual.Resize;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Checkbox;
import com.crystalgui.ui.elements.Dialog;
import com.crystalgui.ui.elements.DialogManager;
import com.crystalgui.ui.elements.CheckboxGroup;
import com.crystalgui.ui.elements.ScrollerView;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.SplitView;
import com.crystalgui.ui.elements.Switch;
import com.crystalgui.ui.elements.Tab;
import com.crystalgui.ui.elements.TabView;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.Tooltip;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.UIDragController;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * Every CrystalGUI widget, one page each, in one navigable window — the front door.
 *
 * <p>The other {@code cgui-*} scenes are focused regression tests: they force states programmatically,
 * stagger pixel captures across known frames, and each covers one widget in depth. This one covers
 * everything shallowly, so "what can CrystalGUI do today" is one command rather than fourteen. Reach
 * for the focused scene when you are changing a widget's behaviour; reach for this one when you want
 * to look at the library.</p>
 *
 * <p><b>The theme toggle is the other half of the point.</b> {@code default.css} (user-agent origin)
 * gives every widget functional geometry with no theme loaded; {@code ore.css} gives it appearance.
 * Toggling between them exercises both, and a widget that looks broken with Ore off has geometry
 * missing from {@code default.css} — which is a real bug, not a missing theme.</p>
 *
 * <p><b>Nothing here is lazy.</b> {@code TabView.addTab} eagerly builds both the tab and its pane, and
 * switching tabs toggles {@code display} rather than touching the tree — which is exactly what makes
 * element identity, listeners and scroll positions survive a switch. So every page below is
 * constructed during {@code init}. That is fine at this size; a page heavy enough to matter should
 * populate itself from an {@code onTabSelected} listener instead.</p>
 */
public class CgUiGalleryScene implements InteractiveSceneLifecycle, SystemInput.Keyboard, SystemInput.Mouse {

    private UIWindow uiWindow;
    private TabView pages;
    private Button themeToggle;

    /** Held, not re-fetched, so {@code removeStylesheet} has the identical instance to remove.
     * {@code StyleSheetRegistry} caches by path so a re-fetch would work too — this just says so. */
    private StyleSheet oreSheet;
    /** This scene's own layout rules. Must be re-added LAST on every toggle — see {@link #toggleTheme}. */
    private StyleSheet sceneSheet;
    private boolean oreOn = true;

    /** Backs the two-way-bound TextField pair, so the binding is visible rather than described. */
    private final Property<String> boundText = new Property<>("bound");

    private static final String STYLES = """
            /* Percent root: UIWindow resolves it against the logical screen size, so the gallery
             * fills whatever window it is given instead of being pinned to 800x600 like the
             * single-widget scenes. */
            .gallery-root  { width: 100%; height: 100%; flex-direction: column;
                             padding-all: 8px; gap-all: 6px; }
            .gallery-head  { flex-direction: row; align-items: center; gap-all: 8px; }
            .gallery-tabs  { flex-grow: 1; width: 100%; }
            .theme-btn     { width: 118px; }

            .page          { flex-direction: column; gap-all: 6px; padding-all: 4px; }
            .page-row      { flex-direction: row; align-items: center; gap-all: 8px; }
            /* An explicit width is what makes a UIText wrap: it self-sizes only while no ancestor
             * has constrained it (see UIText.recompute's selfSizesWidth). */
            .desc          { width: 300px; color: #A8B0B8; font-size: 8; }
            .slot          { width: 86px; }
            .field         { width: 150px; }

            .swatch        { width: 14px; height: 14px; background: #6FA8DC; }
            .box           { width: 96px; height: 40px; }
            .box-flat      { background: #4A6E9A; }
            /* `border-radius`, not `border-radius-all` — unlike the box-model shorthands this one
             * has no `-all` alias; it expands into 8 corner longhands at parse time. */
            .box-round     { background: #9A6E4A; border-radius: 10px; }
            .box-sprite    { background: asset("crystalgui:ore", "panel"); }
            /* Regression rows for the bug where a radius on a BACKGROUNDLESS element painted an opaque
             * white box: resolveRoundedFill treated CgUiDrawable.EMPTY as a white fill, and paintSelf's
             * guard for "no background" sat after the rounded path's early return. Every pre-existing
             * radius case in every scene paired the radius with a background, which is why nothing
             * caught it. `.box-none-round` must render NOTHING; `.box-border-only` must render a clean
             * ring with a transparent middle and no colour fringe on its inner edge.
             *
             * Both set their own radius rather than relying on a sheet, so they exercise the rounded
             * path under either theme — default.css rounds per widget and never `*`, and Ore resets
             * the widgets it has sprites for, so neither sheet would reach a bare div like these. */
            .box-none-round   { border-radius: 6px; }
            .box-border-only  { border-radius: 6px; border-width: 2px; border-color: #C86464; }

            /* overflow: hidden, because a resizable box should contain its content. Without it the
             * label spills once you shrink the panel below the text -- which is correct CSS for
             * overflow: visible, and is also the second (better) reason the spec restricts `resize`
             * to scroll containers. We keep the divergence; the clipping is the demo's job. */
            .rz            { width: 110px; height: 60px; background: #3A4450; padding-all: 6px;
                             overflow: hidden; }
            .rz-capped     { max-width: 160px; max-height: 90px; min-width: 0px; min-height: 0px; }

            /* Dialogs are position:absolute, so they resolve left/top against their containing
             * block -- this stage, not the whole page. It also gives the clamp something to bite. */
            .dlg-stage     { width: 340px; height: 190px; background: #23272E; }
            .dlg-a         { width: 150px; height: 90px; }
            .dlg-b         { width: 160px; height: 80px; }

            .chip          { width: 70px; height: 22px; background: #4A6E9A; padding-left: 6px;
                             align-items: center; }
            /* Hidden until a drag activates. The controller only learns about a ghost at mousedown
             * (setGhost), so without this every chip would render a second copy of itself in flow
             * from the moment the page is built. show/hide use IMPORTANT origin, which outranks this. */
            .chip-ghost    { display: none; opacity: 0.75; }
            .bin           { width: 110px; height: 48px; background: #3A4450; padding-all: 6px; }
            .bin-hot       { background: #3C8527; }

            .scroll-demo   { width: 300px; height: 96px; }
            .scroll-row    { height: 22px; width: 100%; background: #3A4450; }
            .scroll-row-alt{ height: 22px; width: 100%; background: #FFAAAA; }
            .split-demo    { width: 320px; height: 110px; }
            .pane-a        { background: #3A4A6A; padding-all: 4px; }
            .pane-b        { background: #4A3A5A; padding-all: 4px; }
            .pane-c        { background: #3A5A4A; padding-all: 4px; }
            .nested-tabs   { width: 320px; height: 110px; }
            .pane-filler   { width: 100%; height: 30px; background: #45525F; }

            /* transform page. Every one of these is pure CSS — the scene makes no transform calls.
             *
             * The two `chain` rules are the same two functions in the two orders, and they must NOT
             * land in the same place: translate-then-scale moves by 12 and then doubles the moved
             * space, scale-then-translate doubles first so the same 12 becomes 24. Seeing them line
             * up would mean the ordered op list has silently collapsed back into a decomposition.
             *
             * `transform-origin` is what the middle rows isolate: identical scales, different anchors.
             * The default is 50% 50%, so tf-scale grows about its centre and tf-origin about its own
             * top-left corner and drifts right. */
            .tf-scale      { transform: scale(1.6); }
            .tf-origin     { transform: scale(1.6); transform-origin: 0 0; }
            .tf-rotate     { transform: rotate(-8deg); }
            .tf-skew       { transform: skewX(20deg); }
            .tf-chain      { transform: translate(12px) scale(2); transform-origin: left center; }
            .tf-chain-rev  { transform: scale(2) translate(12px); transform-origin: left center; }
            /* Transitionable like any other interpolatable property, and this is the case CSS's
             * list-matching rule covers: both ends are a single scale(), so it lerps rather than
             * snapping at the halfway point. */
            .tf-hover      { transition: transform 160ms ease; }
            .tf-hover:hover{ transform: scale(1.35); }
            """;

    @Override
    public void init(HarnessContext ctx) {
        // Held keys matter on the Slider, TextField and Scroller pages.
        org.lwjgl.input.Keyboard.enableRepeatEvents(true);

        this.oreSheet = StyleSheetRegistry.of("crystalgui:ore");
        this.sceneSheet = StyleSheet.parse(STYLES);

        this.uiWindow = new UIWindow(Ui.of(createDemo()));
        var engine = uiWindow.getStyleEngine();
        engine.addStylesheet(StyleSheet.DEFAULT);   // USER_AGENT origin — stays through every toggle
        engine.addStylesheet(oreSheet);
        engine.addStylesheet(sceneSheet);
    }

    /**
     * Swaps the Ore theme in and out on the live window.
     *
     * <p>Cheap: both mutators just mark every element dirty-match, and the re-match happens at the top
     * of the next {@code paintFrame}. No rebuild, no reattach.</p>
     *
     * <p><b>The scene sheet must be removed and re-added last.</b> {@code sourceOrder} packs the sheet
     * index above the rule index, so re-adding a sheet puts it back at the <em>end</em> of the list —
     * i.e. at the highest priority, not its original position. Leave the scene sheet in place and
     * re-adding Ore would start winning this scene's own layout rules at equal specificity.</p>
     */
    private void toggleTheme() {
        var engine = uiWindow.getStyleEngine();
        engine.removeStylesheet(sceneSheet);
        if (oreOn) {
            engine.removeStylesheet(oreSheet);
        } else {
            engine.addStylesheet(oreSheet);
        }
        engine.addStylesheet(sceneSheet);
        oreOn = !oreOn;
        themeToggle.setText(oreOn ? "theme: oreg" : "theme: defaultg");
    }

    private UIElement createDemo() {
        UIElement root = new UIElement()
                .layout(l -> l.paddingAll(8).flexDirection(FlexDirection.COLUMN).gapAll(6))
                .setFocusPolicy(FocusPolicy.NONE);
        root.addClass("gallery-root");
        root.addClass("panel");

        root.addChild(header());

        pages = new TabView();
        pages.addClass("gallery-tabs");
        // A sidebar, not a top strip: twelve tabs across the top is the overflow case cgui-tabview
        // exists to demonstrate. The rail is a ScrollerView either way, so a long list still scrolls.
        pages.setTabSide(TabView.TabSide.LEFT);
        root.addChild(pages);

        buttonPage(page("Button", "Press-and-release on the same element. Space/Enter when focused."));
        checkboxPage(page("Checkbox", "Standalone toggles, plus a CheckboxGroup that refuses to empty."));
        switchPage(page("Switch", "The knob slides via a CSS transition on an invisible spacer."));
        sliderPage(page("Slider", "Drag, click-to-jump, arrows, Home/End, wheel."));
        textFieldPage(page("TextField", "Two validation tiers, and a two-way binding you can watch."));
        textPage(page("UIText", "Self-sizing, or wrapping once an ancestor constrains its width."));
        scrollerPage(page("Scroller", "ScrollerView opts into the wheel; a bare element does not."));
        splitViewPage(page("SplitView", "Draggable dividers, nestable."));
        tabViewPage(page("TabView", "A TabView inside a TabView pane."));
        elementPage(page("UIElement", "The styleable div everything else is built from."));
        transformPage(page("transform", "CSS transform + transform-origin. Layout never sees them; clicks follow."));
        tooltipPage(page("Tooltip", "Top layer: hover a row INSIDE the scroller - the tooltip escapes the clip."));
        dragPage(page("Drag", "Drag a chip onto a bin. Ghost follows the cursor; Escape cancels."));
        resizePage(page("resize", "8 handles: 4 edges + 4 corners. Leading edges move the box too."));
        dialogPage(page("Dialog", "Drag to move, click to raise, X closes. New windows cascade."));

        return root;
    }

    private UIElement header() {
        UIElement head = new UIElement();
        head.addClass("gallery-head");

        UIText title = new UIText("CrystalGUI - widget gallery");
        title.addClass("label");
        head.addChild(title);

        UIElement spacer = new UIElement();
        spacer.addClass("spacer");
        head.addChild(spacer);

        themeToggle = new Button("theme: ore");
        themeToggle.addClass("theme-btn");
        themeToggle.attachListener(this::toggleTheme);
        head.addChild(themeToggle);

        return head;
    }

    /** Adds a tab, gives its pane the shared page styling and a one-line description, returns the pane. */
    private UIElement page(String label, String description) {
        Tab tab = pages.addTab(label);
        UIElement pane = tab.content();
        pane.addClass("page");

        UIText desc = new UIText(description);
        desc.addClass("desc");
        pane.addChild(desc);
        return pane;
    }

    private UIElement row(UIElement... children) {
        UIElement row = new UIElement();
        row.addClass("page-row");
        for (UIElement child : children) row.addChild(child);
        return row;
    }

    /** A fixed-width text cell, because a bare UIText sizes itself and columns would not line up. */
    private UIElement slot(String text) {
        UIElement slot = new UIElement();
        slot.addClass("slot");
        UIText label = new UIText(text);
        label.addClass("label");
        slot.addChild(label);
        return slot;
    }

    private UIElement swatch() {
        UIElement swatch = new UIElement();
        swatch.addClass("swatch");
        return swatch;
    }

    // ── Pages ───────────────────────────────────────────────────────────────────────────────────

    private void buttonPage(UIElement pane) {
        Button plain = new Button("click me");
        plain.attachListener(() -> buttonClicks++);

        Button withIcon = new Button("with an icon");
        withIcon.setPreIcon(swatch());

        Button disabled = new Button("disabled");
        disabled.setEnabled(false);

        pane.addChild(row(slot("plain"), plain));
        pane.addChild(row(slot("pre-icon"), withIcon));
        pane.addChild(row(slot("disabled"), disabled));
    }

    private int buttonClicks = 0;

    private void checkboxPage(UIElement pane) {
        Checkbox one = new Checkbox("free-standing");
        Checkbox two = new Checkbox("starts checked");
        two.setChecked(true);

        // allowEmpty(false) gives radio semantics: the last checked box refuses to un-check.
        CheckboxGroup group = new CheckboxGroup().allowEmpty(false);
        UIElement radios = row(slot("group, required"));
        for (String name : new String[]{"red", "green", "blue"}) {
            Checkbox option = new Checkbox(name);
            option.setGroup(group);
            radios.addChild(option);
        }

        pane.addChild(row(slot("standalone"), one, two));
        pane.addChild(radios);
    }

    private void switchPage(UIElement pane) {
        Switch off = new Switch();
        Switch on = new Switch();
        on.setChecked(true);
        pane.addChild(row(slot("off"), off));
        pane.addChild(row(slot("on"), on));
    }

    private Slider continuous;
    private Slider stepped;

    private void sliderPage(UIElement pane) {
        continuous = new Slider();
        continuous.setRange(0, 100).setValue(35);

        stepped = new Slider();
        stepped.setRange(0, 100).setStep(25).setValue(50);

        pane.addChild(row(slot("continuous"), continuous));
        pane.addChild(row(slot("step 25"), stepped));
    }

    private TextField plainField;

    private void textFieldPage(UIElement pane) {
        plainField = new TextField();
        plainField.setText("edit me");
        plainField.addClass("field");

        TextField placeholder = new TextField();
        placeholder.setPlaceholder("placeholder...");
        placeholder.addClass("field");

        // A 0..100 integer range can never be negative, so the mode's keystroke filter drops '-'
        // outright — while "500" is typable and merely lands the field in :invalid.
        TextField number = new TextField();
        number.setMode(TextField.Mode.INTEGER).setRange(0, 100).setText("42");
        number.addClass("field");

        TextField bound = new TextField();
        bound.bindValueBidirectional(boundText);
        bound.addClass("field");

        TextField mirror = new TextField();
        mirror.bindValueBidirectional(boundText);
        mirror.addClass("field");

        pane.addChild(row(slot("plain"), plainField));
        pane.addChild(row(slot("placeholder"), placeholder));
        pane.addChild(row(slot("int 0..100"), number));
        pane.addChild(row(slot("bound"), bound));
        pane.addChild(row(slot("...mirrors it"), mirror));
    }

    private void textPage(UIElement pane) {
        UIText self = new UIText("Self-sized: the element is exactly as wide as the glyphs.");
        self.addClass("label");

        UIText wrapped = new UIText(
                "Constrained by an ancestor, so it wraps instead: UIText only self-sizes its width "
                        + "while nothing else has given it one, and writes its measured height back "
                        + "as an !important candidate either way.");
        wrapped.addClass("desc");

        pane.addChild(self);
        pane.addChild(wrapped);
    }

    private ScrollerView scroller;

    private void scrollerPage(UIElement pane) {
        scroller = new ScrollerView();
        scroller.addClass("scroll-demo");
        for (int i = 1; i <= 14; i++) {
            UIElement rowEl = new UIElement();
            rowEl.addClass("scroll-row");
            if (i % 2 == 0) rowEl.addClass("scroll-row-alt");
            UIText label = new UIText("row " + i);
            label.addClass("label");
            rowEl.addChild(label);
            scroller.addChild(rowEl);
        }
        pane.addChild(scroller);
    }

    /**
     * The <b>top layer</b> — CSS Position 4 §top-layer, the machinery behind {@code <dialog>} and
     * popovers.
     *
     * <p>The scrolling list is the whole demo and the reason the feature exists. Hover any row: the
     * tooltip is anchored to a row <em>inside</em> an {@code overflow} container, yet draws outside
     * it. Before the top layer that was impossible — {@code drawSubtree} paints depth-first under
     * every ancestor's scissor, so the tooltip was clipped to the list. Scroll while hovering and it
     * tracks its row, because placement is recomputed per frame rather than cached.</p>
     *
     * <p>The other two rows are the placement fallbacks: one hard against the right edge (clamps
     * instead of overflowing) and one near the bottom (flips above its anchor).</p>
     */
    private void tooltipPage(UIElement pane) {
        Button plain = new Button("hover me");
        Tooltip.attach(plain, "A tooltip in the top layer.");

        Button multi = new Button("longer text");
        Tooltip.attach(multi, "Long enough to wrap against the tooltip's max-width from default.css.");

        pane.addChild(row(slot("basic"), plain));
        pane.addChild(row(slot("wrapping"), multi));

        // Right edge: a left-aligned tooltip would overflow, so placement clamps it inward.
        UIElement edgeRow = row(slot("clamps"), new UIElement());
        Button atEdge = new Button("near right edge");
        Tooltip.attach(atEdge, "Clamped inside the window instead of overflowing.");
        UIElement pusher = new UIElement();
        pusher.addClass("spacer");
        edgeRow.addChild(pusher);
        edgeRow.addChild(atEdge);
        pane.addChild(edgeRow);

        // The one that matters: anchors inside a clipping, scrolling container.
        ScrollerView list = new ScrollerView();
        list.addClass("scroll-demo");
        for (int i = 1; i <= 14; i++) {
            UIElement rowEl = new UIElement();
            rowEl.addClass("scroll-row");
            if (i % 2 == 0) rowEl.addClass("scroll-row-alt");
            UIText label = new UIText("row " + i + " - hover me");
            label.addClass("label");
            rowEl.addChild(label);
            Tooltip.attach(rowEl, "Row " + i + ": anchored inside the scroller, drawn outside it.");
            list.addChild(rowEl);
        }
        pane.addChild(list);
    }

    /**
     * Payload drag with drop targets, a ghost, and the cancel path — everything P2 added.
     *
     * <p>Drag a chip onto a bin. Things to look for, each of which is a bug if it misbehaves:</p>
     * <ul>
     *   <li>The <b>ghost</b> follows the cursor from wherever you grabbed the chip, and draws above
     *       everything — it is in the top layer, so the scroller below cannot clip it.</li>
     *   <li>A bin <b>highlights on enter and un-highlights on leave</b>, including when you cancel.
     *       A bin left lit is the symptom of a missing symmetric leave.</li>
     *   <li>A small movement is still a <b>click</b>, not a drag — that is the activation threshold.</li>
     *   <li><b>Escape</b> mid-drag aborts: no drop fires, the ghost disappears, the bin un-highlights.</li>
     *   <li>Nothing <b>hover-flickers</b> while you drag across the UI. That is pointer capture; before
     *       it existed, `:hover` fired on everything the cursor crossed.</li>
     * </ul>
     */
    private void dragPage(UIElement pane) {
        UIText status = new UIText("drag a chip onto a bin");
        status.addClass("label");
        pane.addChild(status);

        UIElement chips = new UIElement();
        chips.addClass("page-row");
        for (String name : new String[]{"alpha", "beta", "gamma"}) {
            chips.addChild(draggableChip(name, status));
        }
        pane.addChild(chips);

        UIElement bins = new UIElement();
        bins.addClass("page-row");
        bins.addChild(dropBin("bin one", status));
        bins.addChild(dropBin("bin two", status));
        pane.addChild(bins);
    }

    private UIElement draggableChip(String name, UIText status) {
        UIElement chip = new UIElement();
        chip.addClass("chip");
        UIText label = new UIText(name);
        label.addClass("label");
        label.setHitTest(false);
        chip.addChild(label);

        // The ghost lives INSIDE the chip on purpose: the drag controller excludes the source and
        // its descendants from drop targeting, so a ghost parented here can never become the drop
        // target for its own drag. It is display:none until a drag activates.
        UIElement ghost = new UIElement();
        ghost.addClass("chip");
        ghost.addClass("chip-ghost");
        UIText ghostLabel = new UIText(name);
        ghostLabel.addClass("label");
        ghost.addChild(ghostLabel);
        chip.addChild(ghost);

        chip.onMouseDown.attachListener((el, event) -> {
            var handler = chip.getAttachedWindow().getInputHandler();
            var drag = handler.getDragController();
            drag.setGhost(ghost);
            // Payload overload => default activation threshold, so a plain click stays a click.
            drag.startDrag(chip, event.getPosition().x(), event.getPosition().y(), name,
                    new UIDragController.DragListener() {
                        @Override public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) { }
                        @Override public void onDragEnd(float mx, float my) {
                            status.setText(drag.getDropTarget() == null ? name + ": dropped on nothing" : status.getText());
                        }
                        @Override public void onDragCancel() { status.setText(name + ": cancelled"); }
                    });
        }, false, false);
        return chip;
    }

    private UIElement dropBin(String name, UIText status) {
        UIElement bin = new UIElement();
        bin.addClass("bin");
        UIText label = new UIText(name);
        label.addClass("label");
        label.setHitTest(false);
        bin.addChild(label);

        // preventDefault() is how a target accepts a drop — HTML5 DnD's one good idea, kept: an
        // element that never opts in cannot silently become a drop target.
        bin.onDragOver.attachListener((el, event) -> event.preventDefault(), false, false);
        bin.onDragEnter.attachListener((el, event) -> bin.addClass("bin-hot"), false, false);
        bin.onDragLeave.attachListener((el, event) -> bin.removeClass("bin-hot"), false, false);
        bin.onDrop.attachListener((el, event) -> {
            bin.removeClass("bin-hot");
            status.setText(event.getPayload() + " -> " + name);
        }, false, false);
        return bin;
    }

    /**
     * The CSS {@code resize} property (CSS UI 4) — drag a panel's corner grabber.
     *
     * <p>What to look for:</p>
     * <ul>
     *   <li>Each panel has a {@code __resizer__} grabber in its bottom-right corner. It appears purely
     *       because the cascade says {@code resize:} — nothing here constructs a widget.</li>
     *   <li><b>both</b> resizes freely; <b>horizontal</b> and <b>vertical</b> move one axis only.</li>
     *   <li>The <b>capped</b> panel refuses to grow past its {@code max-width}/{@code max-height} or
     *       shrink below its {@code min-*}. That clamping is Taffy's, not the resizer's — the spec
     *       says min/max are the <em>only</em> constraints on a resize.</li>
     *   <li>Resizing does not reflow the content inside the panel: the grabber is out of flow.</li>
     *   <li>Contrary to the spec, these are <b>not scroll containers</b> and it still works — see
     *       {@code Resize}'s javadoc for why we drop that restriction deliberately.</li>
     * </ul>
     */
    /**
     * CSS {@code resize} with <b>eight</b> handles -- four edges and four corners.
     *
     * <p>Not a divergence: CSS UI 4 says only that the UA "presents a bidirectional resizing
     * mechanism" and never prescribes a single corner grabber. Browsers ship one because theirs is
     * drawn in the scrollbar gutter and has nowhere else to go.</p>
     *
     * <p>What to look for:</p>
     * <ul>
     *   <li>Corners are tinted; <b>edges are invisible but grabbable</b> -- a background is not what
     *       makes something hittable, and four visible bars around every panel would be noise.</li>
     *   <li><b>Leading edges move the box as well as resizing it.</b> Drag the LEFT edge and the right
     *       edge stays put. That is the case CSS avoids needing by only ever offering bottom-right.</li>
     *   <li>{@code horizontal} shows the two side edges and <b>no corners</b> -- a corner would imply
     *       a vertical resize the mode forbids. Same for {@code vertical}.</li>
     *   <li>Corners sit above the edge strips they cross, so a corner drag wins the hit test.</li>
     * </ul>
     */
    private void resizePage(UIElement pane) {
        pane.addChild(row(slot("both"), resizablePanel("resize: both", Resize.BOTH, false)));
        pane.addChild(row(slot("horizontal"), resizablePanel("width only", Resize.HORIZONTAL, false)));
        pane.addChild(row(slot("vertical"), resizablePanel("height only", Resize.VERTICAL, false)));
        pane.addChild(row(slot("min/max"), resizablePanel("clamped 70-160 x 40-90", Resize.BOTH, true)));
    }

    private UIElement resizablePanel(String label, Resize mode, boolean capped) {
        UIElement panel = new UIElement();
        panel.addClass("rz");
        if (capped) panel.addClass("rz-capped");
        panel.generalStyle(g -> g.resize(mode));

        UIText text = new UIText(label);
        text.addClass("label");
        text.setHitTest(false);
        panel.addChild(text);
        return panel;
    }

    /**
     * {@link Dialog} — the web's {@code <dialog>}, modeless form, plus movement.
     *
     * <p>What to look for:</p>
     * <ul>
     *   <li>Drag a <b>title bar</b> to move a dialog. It tracks from the first pixel — no activation
     *       threshold, unlike a payload drag.</li>
     *   <li>Dialogs <b>clamp to the stage</b>, so neither can be dragged out of reach. That clamping
     *       is ours: no spec covers a movable window.</li>
     *   <li>They <b>stack against each other by z-index</b>, not by being in the top layer — a
     *       modeless dialog stays in ordinary stacking, and only {@code showModal()} would promote.
     *       Click "raise" to bring one forward.</li>
     *   <li><b>The X closes</b> it, and focus returns to whatever held it before. <b>Escape does
     *       NOT</b> close a modeless dialog — only showModal() establishes a close watcher, so
     *       browsers do not either. Escape mid-drag cancels the drag instead.</li>
     *   <li>Resizing works on them too: the second dialog sets {@code resize: both}, so the two
     *       features compose.</li>
     * </ul>
     */
    private void dialogPage(UIElement pane) {
        UIElement stage = new UIElement();
        stage.addClass("dlg-stage");
        pane.addChild(stage);

        // The manager owns placement and stacking. Everything the page used to do by hand — a z
        // counter, per-title-bar raise listeners, manual moveTo calls — is now its job.
        DialogManager manager = new DialogManager(stage);

        Dialog first = manager.manage(new Dialog("panel one"));
        first.addClass("dlg-a");
        UIText firstBody = new UIText("drag my title bar");
        firstBody.addClass("label");
        firstBody.setHitTest(false);
        first.getContent().addChild(firstBody);

        Dialog second = manager.manage(new Dialog("panel two (resizable)"));
        second.addClass("dlg-b");
        second.generalStyle(g -> g.resize(Resize.BOTH));
        second.getContent().addChild(new Button("a button"));

        Dialog third = manager.manage(new Dialog("panel three"));
        third.addClass("dlg-a");
        UIText thirdBody = new UIText("click me to raise");
        thirdBody.addClass("label");
        thirdBody.setHitTest(false);
        third.getContent().addChild(thirdBody);

        UIElement controls = new UIElement();
        controls.addClass("page-row");
        Button reopen = new Button("open all");
        reopen.attachListener(manager::showAll);
        controls.addChild(reopen);
        Button closeAll = new Button("close all");
        closeAll.attachListener(manager::closeAll);
        controls.addChild(closeAll);
        Button spawn = new Button("new window");
        spawn.attachListener(() -> {
            Dialog extra = manager.manage(new Dialog("panel " + (manager.getDialogs().size())));
            extra.addClass("dlg-a");
            extra.show();
        });
        controls.addChild(spawn);
        pane.addChild(controls);

        manager.showAll();
    }

    private void splitViewPage(UIElement pane) {
        SplitView split = new SplitView();
        split.addClass("split-demo");
        // Percentages here are 0..100, not 0..1 — matching LDLib2's 5..95 defaults.
        split.setPercentage(40f).setLimits(15f, 85f);

        split.first().addClass("pane-a");
        UIText left = new UIText("first pane");
        left.addClass("label");
        split.first().addChild(left);

        // Splits nest, and the nested one is where a divider-drag bug would show first.
        SplitView nested = new SplitView();
        nested.setOrientation(SplitView.Orientation.VERTICAL);
        nested.first().addClass("pane-b");
        nested.second().addClass("pane-c");
        UIText top = new UIText("nested top");
        top.addClass("label");
        UIText bottom = new UIText("nested bottom");
        bottom.addClass("label");
        nested.first().addChild(top);
        nested.second().addChild(bottom);
        split.second().addChild(nested);

        pane.addChild(split);
    }

    private void tabViewPage(UIElement pane) {
        TabView nested = new TabView();
        nested.addClass("nested-tabs");
        nested.setTabSide(TabView.TabSide.TOP);
        for (String name : new String[]{"one", "two", "three"}) {
            UIElement filler = new UIElement();
            filler.addClass("pane-filler");
            UIText label = new UIText("pane " + name);
            label.addClass("label");
            filler.addChild(label);
            nested.addTab(name).content().addChild(filler);
        }
        pane.addChild(nested);
    }

    private void elementPage(UIElement pane) {
        UIElement flat = new UIElement();
        flat.addClass("box");
        flat.addClass("box-flat");

        UIElement rounded = new UIElement();
        rounded.addClass("box");
        rounded.addClass("box-round");

        UIElement sprite = new UIElement();
        sprite.addClass("box");
        sprite.addClass("box-sprite");

        pane.addChild(row(slot("colour"), flat));
        pane.addChild(row(slot("border-radius"), rounded));
        pane.addChild(row(slot("9-slice"), sprite));
        pane.addChild(row(slot("radius, no bg"), box("box-none-round")));
        pane.addChild(row(slot("border only"), box("box-border-only")));
    }

    private UIElement box(String cssClass) {
        UIElement element = new UIElement();
        element.addClass("box");
        element.addClass(cssClass);
        return element;
    }

    /**
     * Everything here is driven from the scene stylesheet's {@code .tf-*} rules — no Java transform
     * calls at all, which is the point: {@code transform} is a cascading property like any other.
     *
     * <p>Each row holds a real {@link Button}, so the transformed widgets stay clickable. That is the
     * thing worth checking by hand: click the rotated one and the scaled one and confirm the counter
     * moves, because rendering and hit-testing derive their matrices from separate code paths and a
     * disagreement between them looks perfectly fine on screen.</p>
     */
    private void transformPage(UIElement pane) {
        pane.addChild(row(slot("none"), transformDemo("tf-none")));
        pane.addChild(row(slot("scale(1.6)"), transformDemo("tf-scale")));
        pane.addChild(row(slot("origin 0 0"), transformDemo("tf-origin")));
        pane.addChild(row(slot("rotate(-8deg)"), transformDemo("tf-rotate")));
        pane.addChild(row(slot("skewX(20deg)"), transformDemo("tf-skew")));
        pane.addChild(row(slot("translate + scale"), transformDemo("tf-chain")));
        pane.addChild(row(slot("scale + translate"), transformDemo("tf-chain-rev")));
        pane.addChild(row(slot("on :hover"), transformDemo("tf-hover")));
    }

    private UIElement transformDemo(String cssClass) {
        Button button = new Button("click");
        button.addClass(cssClass);
        button.attachListener(() -> buttonClicks++);
        return button;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────────────────────

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        uiWindow.init(ctx.getScreenWidth(), ctx.getScreenHeight());
        uiWindow.paintFrame();

        var context = CgUiPaintContext.getInstance();
        Tab selected = pages.getSelectedTab();
        context.text().draw().at(0, 0)
                .text(String.format("Gallery — page=%s   theme=%s   uiScale=%.2f ([ ])   clicks=%d   slider=%.0f",
                        selected == null ? "none" : selected.getText(),
                        oreOn ? "ore" : "default",
                        uiWindow.getUiScale(),
                        buttonClicks,
                        continuous.getValue()))
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
        if (event.pressed()) {
            switch (event.key()) {
                case CgUiKeyCodes.KEY_RBRACKET -> {
                    setScale(Math.min(8f, uiWindow.getUiScale() + 0.1f));
                    return true;
                }
                case CgUiKeyCodes.KEY_LBRACKET -> {
                    setScale(Math.max(0.1f, uiWindow.getUiScale() - 0.1f));
                    return true;
                }
                default -> { }
            }
        }
        return uiWindow.getInputHandler().consumeKeyboardEvent(event);
    }

    /**
     * {@code [} / {@code ]} step {@code uiScale} in quarters — fractional values on purpose, since
     * those are the ones that expose pixel-alignment bugs and every other cgui scene runs at a fixed
     * 2. Punctuation rather than letters or arrows: TabView owns the arrows, and a letter would be
     * swallowed by a focused TextField.
     *
     * <p>{@code init(0, 0)} forces the re-init a scale change needs — {@code init} early-returns when
     * the physical dimensions are unchanged, and they are. Same trick {@code CgUiTextScene} uses.</p>
     */
    private void setScale(float scale) {
        uiWindow.setUiScale(scale);
        uiWindow.init(0, 0);
    }

    @Override
    public boolean consumeMouseEvent(SystemInput.Mouse.Event event) {
        return uiWindow.getInputHandler().consumeMouseEvent(event);
    }
}
