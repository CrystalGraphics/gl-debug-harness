package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.gl.render.CgCurveRenderer;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
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
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.ui.AnchoredPlacement;
import com.crystalgui.ui.elements.Dropdown;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.MenuItem;
import com.crystalgui.ui.elements.Switch;
import com.crystalgui.ui.elements.Tab;
import com.crystalgui.ui.elements.TabView;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.Tooltip;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.core.command.Command;
import com.crystalgui.ui.input.keymap.KeyEventType;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.ui.elements.list.ListRenderer;
import com.crystalgui.ui.elements.list.ListView;
import com.crystalgui.ui.elements.list.SelectionMode;
import com.crystalgui.ui.elements.tree.TreeDataSource;
import com.crystalgui.ui.elements.tree.TreeRenderer;
import com.crystalgui.ui.elements.tree.TreeRow;
import com.crystalgui.ui.elements.tree.TreeView;
import com.crystalgui.ui.text.TextRange;
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
public class CgUiGalleryScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

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
            /* `height: 0` is load-bearing, and it is the fix for a real overflow.
             *
             * flex-shrink defaults to ZERO in this engine (a deliberate Taffy divergence — see AGENTS.md),
             * so a flex item can never shrink below its content. With twenty ore-themed tabs the strip's
             * content is ~360px against ~284 available, and the tabview simply overflowed its parent: the
             * `__panes__` background stretches to the tabview's height, so the dark pane spilled past the
             * frame that contains it, and the rail never scrolled because it was never constrained.
             *
             * `height: 0` + `flex-grow: 1` is the classic flexbox answer — basis zero, then grow into
             * exactly the space that is left — and it makes the row robust at any window size instead of
             * only at large ones. Setting `flex-shrink: 1` would work too, but only after content already
             * exceeded the box; this never lets it get there. */
            .gallery-tabs  { flex-grow: 1; height: 0; width: 100%; }
            /* Twenty pages in a sidebar. The rail scrolls correctly — its max scroll covers the whole
             * content, verified — but default.css deliberately gives the strip a 2px scrollbar, which is
             * below the size at which anything is grabbable. Its own comment says a theme wanting more
             * thickens it, so: compact the tabs enough that twenty fit without scrolling at the harness's
             * default window size, AND widen the bar so the moment a twenty-first is added the overflow
             * is visible and draggable rather than silently unreachable. */
            .gallery-tabs tab            { height: 13px; font-size: 7; }
            .gallery-tabs .__strip-bar__ { width: 5px; }
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
            /* Canvas for the curve page. A plain element with a background — everything drawn on it
             * comes from ctx.curve() in paintSelf, so its only job is to give the strokes a box with
             * a known origin and a visible backdrop to sit on. */
            .curve-canvas  { width: 420px; height: 74px; background: #23262E; border-radius: 4px; }
            .curve-mid     { height: 104px; }
            .curve-tall    { height: 150px; }
            /* The curve page holds far more than a pane's worth, so it scrolls. `height: 0` +
             * `flex-grow: 1` for the same reason .gallery-tabs needs it: flex-shrink is 0 in this
             * engine, so without a zero basis the scroller sizes to its content and never scrolls. */
            .curve-scroll  { flex-grow: 1; height: 0; width: 100%; }
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

            /* text-css page (5.2). Every one of these is pure CSS — the scene sets no text properties
             * from Java, which is the point: they cascade, they inherit (all but text-overflow), and
             * they theme.
             *
             * The `.tx-box` width is load-bearing. A UIText self-sizes only while nothing has
             * constrained it, so an unconstrained label would have no leftover space and every
             * alignment would look identical — the wrapper is what gives alignment something to
             * align WITHIN. Same reason `.desc` above carries a width. */
            .tx-box        { width: 190px; background: #2E3540; padding-all: 4px; }
            /* 6.1.1 — the CSS Custom Highlight API.
             *
             * Java registers WHERE (named TextRanges on the element); CSS says WHAT. That split is the
             * whole point of the mechanism on the web, and it is why none of these colours live in the
             * scene any more: a theme can restyle every highlight below without touching a line of Java.
             *
             * ::highlight() accepts only properties that cannot affect layout — colour, background,
             * text-decoration-line, text-shadow. CSS Pseudo-Elements 4 restricts it that way so a
             * highlight can never reflow the text it highlights, and the engine drops anything else with
             * a warning rather than accepting a rule that does nothing. */
            .tx-rich       { width: 300px; background: #23282F; padding-all: 5px; color: #D6DEE8; }

            /* A syntax theme, in CSS, exactly as it should be. One Dark's palette. */
            .tx-rich ::highlight(keyword)  { color: #C678DD; }
            .tx-rich ::highlight(function) { color: #61AFEF; }
            .tx-rich ::highlight(variable) { color: #E06C75; }

            /* Decoration-only: no colour at all, so the text keeps its own and stays readable. This is
             * what a spell-check mark or a search hit actually wants. */
            .tx-rich ::highlight(spelling) { text-decoration-line: underline; }
            .tx-rich ::highlight(removed)  { text-decoration-line: line-through; color: #7F848E; }
            /* A search hit, recoloured and underlined rather than banded.
             *
             * `background-color` IS valid on ::highlight() per CSS, and is deliberately not used here
             * because this engine cannot paint it yet: a band behind a character range needs per-range
             * rects from the text layout, and CgStyleSpan carries colour and decorations only. The engine
             * logs that distinction rather than silently dropping the declaration. */
            .tx-rich ::highlight(search)   { color: #E5C07B; text-decoration-line: underline; }

            /* Light on purpose, and the only row here that is.
             *
             * A text shadow is a quarter-brightness copy offset by 1px. On this page's dark background
             * that is very nearly the background colour, so the row rendered identically whether the
             * shadow was right, wrong or absent — a demo that cannot fail is not a demo. Against a light
             * panel the bug this row guards against (a highlight colour painting its shadow at FULL
             * brightness, because a span colour beats the draw colour downstream) shows up unmistakably
             * as a bright halo. */
            .tx-shadow-row { text-shadow: true; background: #C9D2DD; color: #14181D; }
            .tx-shadow-row ::highlight(red)   { color: #CC0000; }
            .tx-shadow-row ::highlight(green) { color: #00892B; }
            .tx-shadow-row ::highlight(blue)  { color: #1240D0; }

            .tx-left       { text-align: left; }
            .tx-center     { text-align: center; }
            .tx-right      { text-align: right; }
            /* nowrap collapses to one line and OVERFLOWS the 190px box rather than widening it —
             * max-width caps the box, which is exactly CSS and exactly why ellipsis exists. Paired
             * with overflow: hidden so the spill is clipped instead of painting over the next row. */
            .tx-nowrap     { white-space: nowrap; overflow: hidden; }
            /* ...and the same line with the ellipsis.
             *
             * NOTE the `.label` descendant: `text-overflow` must sit on the UIText ITSELF, because it
             * does not inherit (CSS UI 4 — the property belongs to the block container that owns the
             * line, and here that container IS the UIText, not this wrapper). `white-space` DOES inherit,
             * which is what made the first version of this page so misleading: the nowrap half arrived
             * on the label from the wrapper, the truncation half silently did not, and the row rendered
             * as a plain clipped line that looked exactly like the row above it.
             *
             * The wrapper still owns `overflow: hidden` — clipping is the box's job either way. */
            .tx-ellipsis   { white-space: nowrap; overflow: hidden; }
            .tx-ellipsis .label { text-overflow: ellipsis; }
            /* Registered long before anything drew it. Second pass at +1px in a quarter-brightness
             * copy of the colour, alpha preserved — the MC convention. Inherits, so setting it on the
             * wrapper reaches the label. */
            .tx-shadow     { text-shadow: true; }
            .tx-inherit    { white-space: nowrap; overflow: hidden; text-align: right; }

            /* keymap page (6.1.2).
             *
             * Sizes are small on purpose: a pane's content box is only ~230 logical px at the harness's
             * default window size, and the first version of this page put two 230px panels in one row.
             * They did not wrap, they overflowed and the second one was clipped away entirely — the same
             * trap as the sidebar, since nothing shrinks below its content here.
             *
             * Two focusable panels so scoping is visible: the SAME chord is bound in both, to different
             * commands, and which fires depends only on where focus is. */
            .km-panel      { width: 62px; height: 30px; background: #2E3540; padding-all: 4px;
                             outline: 1px solid #46505E; font-size: 7; }
            .km-panel:focus{ outline: 2px solid #61AFEF; }
            .km-log        { width: 140px; height: 18px; background: #1B1F25; padding-all: 3px;
                             color: #98C379; font-size: 7; }
            .km-pending    { width: 140px; height: 18px; padding-all: 3px; color: #E5C07B; font-size: 7; }
            .km-field      { width: 140px; }
            .km-hint       { color: #7F848E; font-size: 7; }

            /* list page (6.1.3). The row height here MUST match ListView.setItemHeight -- the strategy
             * decides where a row is positioned, the sheet decides how it looks, and if they disagree the
             * rows overlap or leave gaps. A future measured strategy is what removes the duplication. */
            .lv            { width: 200px; height: 130px; background: #23282F; outline: 1px solid #46505E; }
            .lv-row        { padding-left: 4px; font-size: 7; }
            .lv-row:hover  { background: #2E3540; }
            .lv-row:focus  { background: #3A4553; }
            .lv-row.__selected__ { background: #2C5A8C; }
            .lv-stat       { color: #98C379; font-size: 7; }

            /* tree page (6.1.4). Indentation is applied by the VIEW from each row's depth, so the row
             * template is identical at every level — the sheet only styles the twisty, which the view
             * marks with __expanded__ / __collapsed__ / __leaf__. */
            /* 300 wide matches `.desc` and `.tx-rich`, so this row is no wider than ones the page
             * already has — deliberately, since nothing shrinks below its content here and a row wider
             * than the pane is clipped rather than wrapped. Height is the free axis: the page scrolls. */
            .tv            { width: 300px; height: 280px; background: #23282F; outline: 1px solid #46505E; }
            .tv-row        { flex-direction: row; align-items: center; gap-all: 3px; font-size: 7; }
            .tv-row:hover  { background: #2E3540; }
            .tv-row.__selected__ { background: #2C5A8C; }
            /* Focus and selection are different things in a tree — the APG says so explicitly — and
             * without a separate focus style there is no way to tell which row the arrows will act on. */
            .tv-row:focus  { outline: 1px solid #61AFEF; }
            /* padding on the TWISTY, not on the panel: rows are absolutely positioned and TreeView owns
             * their padding-left (it is the depth indent), so a sheet rule there would out-specify the
             * indent and flatten the whole tree. Insetting the glyph inside its own box cannot collide. */
            .tv-twisty     { width: 11px; padding-left: 3px; color: #98C379; font-size: 7; }
            .tv-twisty:hover { color: #FFFFFF; }
            .tv-status     { color: #E5C07B; font-size: 7; }

            /* focus page (5.3). The strip is a real TabView; the buttons on either side of it are what
             * make the roving tabindex visible — Tab must go before -> the SELECTED tab -> after,
             * skipping the other tabs entirely, and arrows must still move between them. */
            .fc-strip      { width: 300px; height: 78px; }
            .fc-btn        { width: 96px; }

            /* modal page. `.md-stage` is the containing block, so the backdrop covers IT rather than the
             * whole gallery -- the dialog is promoted, and `100%` on the backdrop resolves against the
             * initial containing block, which is the root. That means the scrim dims the entire window,
             * which is correct for a real modal and worth seeing here rather than a tidy inset rectangle. */
            .md-stage      { width: 340px; height: 120px; background: #23272E; padding-all: 8px;
                             gap-all: 6px; }
            .md-dialog     { width: 190px; height: 96px; }
            .md-btn        { width: 150px; }

            /* menus page. Nothing here positions anything -- AnchoredPlacement owns left/top, and a rule
             * setting either would fight it every frame. Widths only. */
            .mn-stage      { width: 340px; height: 130px; background: #23272E; padding-all: 8px;
                             gap-all: 6px; }
            .mn-drop       { width: 130px; }
            /* The right-click surface. Deliberately tall enough to aim at, and it reports where the menu
             * was opened so pointer->root conversion is visibly correct rather than merely plausible. */
            .mn-canvas     { width: 300px; height: 56px; background: #31363F; padding-all: 6px; }
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

        // After the window exists, because commands live on it — see installKeymap.
        installKeymap(uiWindow);
        installListStats(uiWindow);
        installTreeStats(uiWindow);
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
        resizePage(page("resize", "In-flow boxes get 3 handles, like CSS. The Dialog page has all 8."));
        dialogPage(page("Dialog", "Drag to move, click to raise, X closes. New windows cascade."));
        textCssPage(page("text-css", "CSS text properties, plus ::highlight() ranges styled from CSS."));
        focusPage(page("focus", "Tab enters the tablist ONCE. Arrows move inside it."));
        modalPage(page("modal", "showModal(): backdrop, focus trap, Escape. Everything else inert."));
        menuPage(page("menus", "Dropdown, context menu, submenu. Click outside or Escape to dismiss."));
        keymapPage(page("keymap", "Bindings are scoped to the focused subtree. Grey text says what to press."));
        listPage(page("list", "100,000 rows. Watch the realised count while you scroll - it does not grow."));
        treePage(page("tree", "Right opens without moving focus; Left closes, or goes to the parent."));
        curvePage(page("curve", "ctx.curve() Bezier strokes - scroll for all 15 rows. The last two are the correctness checks."));

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

    /**
     * 5.2 — the four CSS text properties, every one of them applied from a stylesheet rather than Java.
     *
     * <p>What to look for: the three alignments must land at the left edge, the centre and the right
     * edge of the same 190px box; {@code nowrap} must be one clipped line where the wrapping row above
     * it is several; the ellipsis row must be that same line ending in a real "…"; and the shadowed row
     * must show a dark offset copy one pixel down-right. The last row sets {@code white-space} and
     * {@code text-align} on the <em>wrapper</em>, so it also proves both inherit.</p>
     */
    private void textCssPage(UIElement pane) {
        pane.addChild(row(slot("wrapping"), txBox("this label wraps because its box is narrower than the text", null)));
        pane.addChild(row(slot("align left"), txBox("aligned left", "tx-left")));
        pane.addChild(row(slot("align center"), txBox("aligned center", "tx-center")));
        pane.addChild(row(slot("align right"), txBox("aligned right", "tx-right")));
        pane.addChild(row(slot("nowrap"), txBox("one long line that will not wrap and so overflows", "tx-nowrap")));
        pane.addChild(row(slot("ellipsis"), txBox("one long line that gets cut short with an ellipsis", "tx-ellipsis")));
        pane.addChild(row(slot("shadow"), txBox("drop shadow behind me", "tx-shadow")));
        pane.addChild(row(slot("inherited"), txBox("set on the WRAPPER, not the text", "tx-inherit")));

        // ── 6.1.1: ::highlight() ──────────────────────────────────────────────
        //
        // The CSS Custom Highlight API — the web's own way of styling ranges of text WITHOUT wrapping
        // them in elements, which exists on the web for our exact reason: an editor cannot afford a
        // <span> per token, and here every element is a real Taffy node. Java says where, CSS says what.
        //
        // The first row is a line of GLSL because that is the real consumer waiting downstream: the
        // shader graph's node inspector, and 6.1.7's code editor after it.
        pane.addChild(row(slot("syntax"), highlightBox("vec3 n = normalize(pos);", null, hl -> hl
                .mark("keyword", "vec3")
                .mark("function", "normalize")
                .mark("variable", "pos"))));

        // Decoration and background WITHOUT a colour: the text keeps its own, which is what makes a
        // search hit or a spelling mark readable rather than merely visible.
        pane.addChild(row(slot("decoration"), highlightBox("a misspelled word, and a deleted one", null,
                hl -> hl
                        .mark("spelling", "misspelled")
                        .mark("removed", "deleted"))));

        // Two ranges under ONE name — what a search actually produces, and the case that makes the
        // registry's sorted/disjoint rule worth having.
        pane.addChild(row(slot("search hits"), highlightBox("find the needle, then the next needle", null,
                hl -> hl.mark("search", "needle").mark("search", "needle"))));

        // Compare against the rows above: each coloured word's shadow must be a DARKER version of that
        // word, never the same brightness and never uniformly grey.
        pane.addChild(row(slot("+shadow"), highlightBox("red green blue, each with its own shadow",
                "tx-shadow-row", hl -> hl
                        .mark("red", "red")
                        .mark("green", "green")
                        .mark("blue", "blue"))));

        // The crash path: truncation paints a PREFIX, so a range reaching past the cut would fail the
        // backend's validation mid-paint. This one deliberately straddles the ellipsis.
        //
        // `.tx-ellipsis`, NOT `.tx-nowrap` — they differ by one declaration and render almost
        // identically, which is exactly the trap the stylesheet comment above `.tx-nowrap` warns about.
        pane.addChild(row(slot("+ellipsis"), highlightBox(
                "a highlighted range that runs straight past where this line gets cut", "tx-ellipsis",
                hl -> hl.mark("search", "runs straight past where this line gets cut"))));
    }

    /**
     * Registers ranges by <b>substring search</b> rather than by literal indices.
     *
     * <p>Hand-counted offsets in a demo rot the moment anybody edits the string, and silently: the wrong
     * word lights up, or the range no longer exists. Searching forward from the previous match also
     * keeps the ranges under one name sorted and disjoint, which {@code HighlightRegistry} requires.</p>
     */
    private static final class HighlightBuilder {
        private final String text;
        private final UIText label;
        private int cursor;

        HighlightBuilder(String text, UIText label) {
            this.text = text;
            this.label = label;
        }

        HighlightBuilder mark(String name, String needle) {
            int at = text.indexOf(needle, cursor);
            if (at < 0) throw new IllegalArgumentException("gallery: '" + needle + "' is not in " + text);
            cursor = at + needle.length();
            label.highlights().add(name, TextRange.of(at, cursor));
            return this;
        }
    }

    /** A fixed-width box around a label — the label alone would self-size and leave nothing to align in. */
    private UIElement txBox(String text, String extraClass) {
        UIElement box = new UIElement();
        box.addClass("tx-box");
        if (extraClass != null) box.addClass(extraClass);
        UIText label = new UIText(text);
        label.addClass("label");
        box.addChild(label);
        return box;
    }

    /** A `.tx-rich` box whose label carries named highlight ranges, styled entirely from the sheet. */
    private UIElement highlightBox(String text, String extraClass,
                                   java.util.function.Consumer<HighlightBuilder> build) {
        UIElement box = new UIElement();
        box.addClass("tx-box");
        box.addClass("tx-rich");
        if (extraClass != null) box.addClass(extraClass);

        UIText label = new UIText(text);
        label.addClass("label");
        box.addChild(label);
        build.accept(new HighlightBuilder(text, label));
        return box;
    }

    /**
     * 6.1.2 — the keymap.
     *
     * <p><b>What to look for.</b> Click "editor", then press <b>Mod+A</b>: the log says
     * {@code editor.selectAll}. Click "canvas" and press the same chord: {@code canvas.selectAll}. Neither
     * panel knows the other exists — resolution walks the focus path outward and takes the innermost
     * match, which is what VS Code needs a {@code when} clause to express and we get from having a
     * tree.</p>
     *
     * <p><b>Mod+Shift+P</b> works from anywhere, because it is bound on the page root rather than on a
     * panel. Application-wide is not a special case; it is just an outer scope.</p>
     *
     * <p><b>Mod+K</b> then <b>Mod+S</b> is a chord: the amber line shows the pending prefix, and it
     * clears on completion, on an unrelated key, on a focus change, or after five seconds. Without that
     * feedback a half-entered chord is indistinguishable from a dead keyboard.</p>
     *
     * <p><b>The trap row.</b> {@code B} is bound as a tool shortcut on the root. Press it with a panel
     * focused and the log fires; type it in the text field and it must type a "b" and fire nothing. That
     * guard is why every single-key shortcut in Photoshop does not corrupt every filename box.</p>
     *
     * <p><b>Space</b> is bound twice — press and release — which is the axis space-to-pan is built on.</p>
     */
    private void keymapPage(UIElement pane) {
        UIText log = new UIText("(nothing yet)");
        // Never blank. An empty line is indistinguishable from a broken one, and this row exists
        // precisely to prove that a half-entered chord is visible — so it has to say what to press.
        UIText pending = new UIText("press Mod+K");

        // The tree is built before any window exists, so commands and bindings are installed later —
        // see installKeymap.
        pendingLabel = pending;
        keymapLog = log;

        pane.addChild(row(slot("scoped"), focusPanel("editor"), focusPanel("canvas"),
                hint("same chord, two panels")));

        UIElement logBox = new UIElement();
        logBox.addClass("km-log");
        logBox.addChild(log);
        pane.addChild(row(slot("last command"), logBox, hint("Mod+A in a panel")));

        UIElement pendingBox = new UIElement();
        pendingBox.addClass("km-pending");
        pendingBox.addChild(pending);
        pane.addChild(row(slot("chord"), pendingBox, hint("then Mod+S -> saveAll (page-scoped)")));

        TextField typing = new TextField();
        typing.addClass("km-field");
        typing.setPlaceholder("click, then type b");
        // Spelled out as a two-step check, because the interesting outcome here is a NON-event: the
        // point is that `last command` does NOT change. A hint that only names the rule leaves the
        // reader with nothing to look at.
        pane.addChild(row(slot("B vs typing"), typing,
                hint("1. press B in a panel -> tool.brush")));
        pane.addChild(row(slot(""), hint("2. type b in the box -> last command must NOT change")));

        pane.addChild(row(slot("global"), hint("Mod+Shift+P - bound on the window root, so it needs no focus")));
        pane.addChild(row(slot("hold"), hint("Space fires on press AND release")));

        keymapRoot = pane;
    }

    /** Grey annotation text. The bindings are invisible by nature, so the page has to say what to press —
     * a demo nobody can operate proves nothing. */
    private UIElement hint(String text) {
        UIText label = new UIText(text);
        label.addClass("km-hint");
        return label;
    }

    /** A focusable panel that owns its own binding for a chord the other panel also binds. */
    private UIElement focusPanel(String name) {
        UIElement panel = new UIElement();
        panel.addClass("km-panel");
        panel.setFocusPolicy(FocusPolicy.CLICK);
        UIText label = new UIText(name);
        // pointer-events: none, and it is REQUIRED, not tidiness.
        //
        // Click-focus tests the hit TARGET's own policy — it does not walk up to a focusable
        // ancestor (see UIInputHandler, and Dialog's title bar, which calls requestFocus
        // explicitly for exactly this reason). A label filling its panel therefore swallows every
        // click, the panel never focuses, and since no binding can resolve without a focused
        // element the whole page goes dead.
        //
        // It looked fine while the panels were large, because there was bare panel left to click
        // around the text. Shrinking them to fit the pane is what exposed it — and Tab still
        // focused them perfectly throughout, which is the clue separating "not focusable" from
        // "unclickable".
        label.setHitTest(false);
        panel.addChild(label);
        keymapPanels.put(name, panel);
        return panel;
    }

    private final java.util.Map<String, UIElement> keymapPanels = new java.util.LinkedHashMap<>();
    private UIElement keymapRoot;
    private UIText keymapLog;
    private UIText pendingLabel;

    /**
     * Registers the commands and bindings once a window exists.
     *
     * <p>Separate from page construction because commands live on the {@code UIWindow} — deliberately,
     * rather than in a global static, so two windows can disagree about what an id means and so tests do
     * not leak registrations into each other.</p>
     */
    private void installKeymap(UIWindow window) {
        var commands = window.getCommands();
        for (var entry : keymapPanels.entrySet()) {
            String id = entry.getKey() + ".selectAll";
            commands.register(Command.of(id, "Select All in " + entry.getKey()).run(() -> logCommand(id)));
            entry.getValue().keymap().bind("Mod+A", id);
        }
        commands.register(Command.of("palette.open", "Command Palette").run(() -> logCommand("palette.open")));
        commands.register(Command.of("edit.saveAll", "Save All").run(() -> logCommand("edit.saveAll")));
        commands.register(Command.of("tool.brush", "Brush").run(() -> logCommand("tool.brush")));
        commands.register(Command.of("pan.begin", "Pan").run(() -> logCommand("pan.begin")));
        commands.register(Command.of("pan.end", "Pan end").run(() -> logCommand("pan.end")));

        // On the WINDOW ROOT, not on this page's pane — and the difference is the whole point of the
        // scoping model, so getting it wrong here made the demo lie.
        //
        // The resolver walks the focus path OUTWARD. A binding on the page pane is reachable only from
        // inside that pane, so with nothing focused the walk starts at the window root and never
        // descends into the page: descendants are not ancestors. "Works from anywhere" therefore has to
        // mean "bound on the outermost scope there is", which is exactly what the root is.
        window.ui.rootElement.keymap().bind("Mod+Shift+P", "palette.open");
        keymapRoot.keymap().bind("Mod+K Mod+S", "edit.saveAll");
        keymapRoot.keymap().bind("B", "tool.brush");
        keymapRoot.keymap().bind("Space", "pan.begin");
        keymapRoot.keymap().bind("Space", "pan.end").on(KeyEventType.RELEASE);

        window.getInputHandler().getKeymapResolver().onPendingChanged.connect(chord ->
                pendingLabel.setText(chord == null ? "press Mod+K" : chord + " ... waiting"));
    }

    private void logCommand(String id) {
        keymapLog.setText(id);
    }

    /**
     * 6.1.3 — the virtualised list.
     *
     * <p><b>What to look for.</b> The model has a hundred thousand rows. The "realised" counter is the
     * only way to see that virtualisation is happening at all: scroll as far and as fast as you like and
     * it stays in the mid-teens, while "created" stops climbing the moment the pool reaches its steady
     * size. A list that built an element per row would show 100,000 and take a while getting there.</p>
     *
     * <p>"pooled" is the recycling bench — elements that have left the window and are waiting to be
     * re-bound. Together the three numbers say the whole story: bounded window, bounded allocation,
     * everything else reused.</p>
     *
     * <p>Click a row, then scroll it far out of view and back. Focus returns to <em>the same row index</em>
     * rather than to whatever element inherited its slot — the view tracks the index, because a recycled
     * element is not a stable identity.</p>
     */
    private void listPage(UIElement pane) {
        ObservableList<String> model = new ObservableList<>();
        for (int i = 0; i < 100_000; i++) model.add("row " + i);

        ListView<String> list = new ListView<>(model);
        list.addClass("lv");
        list.setItemHeight(12f);
        list.setSelectionMode(SelectionMode.MULTIPLE);
        list.setRenderer(new ListRenderer<String>() {
            @Override
            public UIElement createTemplate() {
                // Structure and listeners ONCE. There is deliberately nowhere to put a listener in
                // bind(), which is what stops a recycled row accumulating one per scroll step.
                UIElement row = new UIElement();
                row.addClass("lv-row");
                row.setFocusPolicy(FocusPolicy.CLICK);
                UIText label = new UIText("");
                label.setHitTest(false);   // or the label eats the click and the row never focuses
                row.addChild(label);
                return row;
            }

            @Override
            public void bind(String item, int index, UIElement template) {
                ((UIText) template.getChildren().get(0)).setText(item);
            }
        });
        pane.addChild(row(slot("100k rows"), list));

        UIText stats = new UIText("...");
        stats.addClass("lv-stat");
        pane.addChild(row(slot("counts"), stats));
        pane.addChild(row(slot(""), hint("realised stays bounded; created stops growing")));
        pane.addChild(row(slot(""), hint("click a row, scroll far away and back - focus returns")));
        pane.addChild(row(slot(""), hint("arrows / Home / End / PageUp / PageDown all navigate")));
        pane.addChild(row(slot(""), hint("Shift+arrow extends, Ctrl+arrow moves without selecting")));
        pane.addChild(row(slot(""), hint("then Space ADDS that row; Enter replaces with just it")));

        listView = list;
        listStats = stats;
    }

    private ListView<String> listView;
    private UIText listStats;

    /** Live counters. A ticker rather than a per-frame poll in render(), because the numbers are UI state
     * and the engine already has a place for that. */
    private void installListStats(UIWindow window) {
        window.registerTicker(delta -> {
            if (listView == null || listStats == null) return false;
            listStats.setText("realised " + listView.realisedCount()
                    + "   pooled " + listView.pooledCount()
                    + "   of " + listView.getModel().size());
            return true;
        });
    }

    /**
     * 6.1.4 — the tree.
     *
     * <p><b>What to look for.</b> Click a folder, then use the arrows. <b>Right</b> on a closed node
     * opens it and the focus ring <em>stays where it is</em> — press it again to step into the first
     * child. <b>Left</b> closes an open node, and on an already-closed one jumps to the parent. That
     * asymmetry is the ARIA tree contract, and it is the part implementations usually get wrong.</p>
     *
     * <p><b>*</b> opens every sibling at the current level.</p>
     *
     * <p>The synthetic tree is 20 roots of 20 of 20 — eight thousand nodes if fully opened — and children
     * are generated on demand, so nothing exists until you ask for it. Everything else (virtualisation,
     * recycling, selection, focus surviving a scroll) is inherited from the list and is not tree code.</p>
     */
    private void treePage(UIElement pane) {
        TreeDataSource<String> source = new TreeDataSource<>() {
            @Override
            public java.util.List<String> roots() {
                java.util.List<String> out = new java.util.ArrayList<>();
                for (int i = 0; i < 20; i++) out.add("folder " + i);
                return out;
            }

            @Override
            public java.util.List<String> children(String parent) {
                java.util.List<String> out = new java.util.ArrayList<>();
                for (int i = 0; i < 20; i++) out.add(parent + "/" + i);
                return out;
            }

            @Override
            public boolean hasChildren(String item) {
                // Three levels deep, then leaves — enough to show nesting without being endless.
                return item.chars().filter(c -> c == '/').count() < 2;
            }
        };

        TreeView<String> tree = new TreeView<>(source);
        tree.addClass("tv");
        tree.setItemHeight(12f);
        tree.setSelectionMode(SelectionMode.MULTIPLE);
        tree.setRenderer(new TreeRenderer<String>() {
            @Override
            public UIElement createTemplate() {
                UIElement row = new UIElement();
                row.addClass("tv-row");
                row.setFocusPolicy(FocusPolicy.CLICK);
                UIText twisty = new UIText("");
                twisty.addClass("tv-twisty");
                // The twisty is HITTABLE, unlike the label. Clicking it toggles; clicking anywhere else
                // in the row just focuses and selects. Without this the tree can only be opened from the
                // keyboard, which is how the first version of this page came out looking completely inert.
                //
                // The listener is attached ONCE per pooled element and reads the row's CURRENT index at
                // click time — it cannot capture an index, because this element represents a different
                // row every time it is recycled. That is exactly the split createTemplate/bind exists for.
                twisty.onMouseDown.attachListener((el, event) -> {
                    int index = treeView.indexOfRowElement(el.getParent());
                    if (index >= 0) treeView.toggleExpandedAt(index);
                }, false, false);
                row.addChild(twisty);
                UIText label = new UIText("");
                label.setHitTest(false);
                row.addChild(label);
                return row;
            }

            @Override
            public void bind(String item, TreeRow<String> row, int index, UIElement template) {
                ((UIText) template.getChildren().get(0))
                        .setText(!row.expandable() ? " " : row.expanded() ? "v" : ">");
                String name = item.substring(item.lastIndexOf('/') + 1);
                ((UIText) template.getChildren().get(1)).setText(row.depth() == 0 ? item : name);
            }
        });
        treeView = tree;
        pane.addChild(row(slot("tree"), tree));

        treeStatus = new UIText("...");
        treeStatus.addClass("tv-status");
        pane.addChild(row(slot("state"), treeStatus));

        pane.addChild(row(slot(""), hint("click the > to open, or use the arrows")));
        pane.addChild(row(slot(""), hint("Right opens WITHOUT moving focus; again steps in")));
        pane.addChild(row(slot(""), hint("Left closes, or jumps to the parent when closed")));
        pane.addChild(row(slot(""), hint("* opens every sibling at this level")));
        pane.addChild(row(slot(""), hint("8,000 nodes if fully opened; children made on demand")));
    }

    private TreeView<String> treeView;
    private UIText treeStatus;

    /** Live tree state. The focused index is the one the arrows act on, and seeing it is the difference
     * between "Right is broken" and "nothing was focused". */
    private void installTreeStats(UIWindow window) {
        window.registerTicker(delta -> {
            if (treeView == null || treeStatus == null) return false;
            treeStatus.setText("focus " + treeView.getFocusedIndex()
                    + "   rows " + treeView.visibleRows().size()
                    + "   realised " + treeView.realisedCount());
            return true;
        });
    }

    /**
     * 5.3 — a tablist is one Tab stop, however many tabs it has.
     *
     * <p>What to look for, all by keyboard: click "before", then press <b>Tab</b>. Focus must land on the
     * <em>selected</em> tab and on no other — one press, not three. <b>Left/Right</b> then move between
     * the tabs (they are still focusable, just not tabbable), and the stop <em>roves</em>: whichever tab
     * you leave selected is the one Tab returns to. Another <b>Tab</b> must leave the strip entirely for
     * "after", and <b>Shift+Tab</b> must retrace the same three stops backwards — asymmetry there would
     * be a keyboard trap.</p>
     *
     * <p>Clicking any tab must still focus it. That is the trap this page exists to catch: the click path
     * used to compare the policy for equality with {@code CLICK}, which would leave every unselected tab
     * dead to the mouse the moment it stopped being the selected one.</p>
     */
    private void focusPage(UIElement pane) {
        Button before = new Button("before");
        before.addClass("fc-btn");
        pane.addChild(before);

        TabView strip = new TabView();
        strip.addClass("fc-strip");
        for (String name : new String[] { "one", "two", "three" }) {
            UIText body = new UIText("pane " + name);
            body.addClass("label");
            strip.addTab(name).content().addChild(body);
        }
        pane.addChild(strip);

        Button after = new Button("after");
        after.addClass("fc-btn");
        pane.addChild(after);
    }

    /**
     * {@code showModal()} — the inert primitive and the top layer doing their jobs together.
     *
     * <p>What to look for, in this order:</p>
     * <ol>
     *   <li>Open it. A <b>scrim</b> covers the whole window, not just this page — a modal blocks the
     *       document, and the backdrop resolves against the initial containing block like the dialog
     *       itself does.</li>
     *   <li><b>Click the buttons behind it.</b> Nothing happens, and the counter does not move: everything
     *       outside the modal is inert, so hit-testing passes straight over it.</li>
     *   <li><b>Press Tab repeatedly.</b> Focus cycles inside the dialog forever and never reaches the
     *       page behind. There is no trap code — that is inertness.</li>
     *   <li><b>Press Escape.</b> It closes, because a modal establishes a close watcher. Then reopen and
     *       tick "veto Escape": {@code preventDefault()} on the cancel event keeps it open, and only the
     *       X still closes it.</li>
     *   <li>Compare with the <b>modeless</b> button: no scrim, the page stays live, Escape does nothing.
     *       That asymmetry is the spec, not an oversight.</li>
     * </ol>
     */
    private void modalPage(UIElement pane) {
        UIElement stage = new UIElement();
        stage.addClass("md-stage");
        pane.addChild(stage);

        UIText counter = new UIText("clicks behind the modal: 0");
        counter.addClass("label");
        stage.addChild(counter);

        int[] behindClicks = { 0 };
        Button behind = new Button("click me (should be blocked)");
        behind.addClass("md-btn");
        behind.attachListener(() -> counter.setText("clicks behind the modal: " + (++behindClicks[0])));
        stage.addChild(behind);

        Dialog modal = new Dialog("a modal dialog");
        modal.addClass("md-dialog");
        // Two focusables, so the Tab cycle is visibly a cycle rather than a single stuck stop.
        modal.getContent().addChild(new Button("first"));
        modal.getContent().addChild(new Button("second"));
        stage.addChild(modal);

        Checkbox veto = new Checkbox("veto Escape");
        modal.onCancel.attachListener((el, event) -> {
            if (veto.isChecked()) event.preventDefault();
        }, false, false);
        modal.getContent().addChild(veto);

        UIElement controls = new UIElement();
        controls.addClass("page-row");
        Button openModal = new Button("showModal()");
        openModal.attachListener(() -> {
            modal.moveTo(60f, 20f);
            modal.showModal();
        });
        controls.addChild(openModal);
        Button openModeless = new Button("show()");
        openModeless.attachListener(() -> {
            modal.moveTo(60f, 20f);
            modal.show();
        });
        controls.addChild(openModeless);
        pane.addChild(controls);
    }

    /**
     * Menus — one widget, three shapes: a dropdown, a context menu, and a submenu.
     *
     * <p>What to look for:</p>
     * <ol>
     *   <li>Open the <b>dropdown</b>. Pick an option — the button's label follows the selection. Press the
     *       button again while it is open: it <b>closes</b> rather than flickering, which is the invoker
     *       carve-out in light dismiss doing its job.</li>
     *   <li><b>Right-click the dark canvas.</b> The same {@code Menu} class opens at the pointer instead of
     *       under an element — a context menu is not a second widget. The label reports the point it was
     *       opened at, so the pointer-to-root conversion is visible rather than assumed.</li>
     *   <li>With a menu open, <b>click anywhere else</b>: it dismisses, and the thing you clicked still
     *       reacts. <b>Escape</b> also closes it, innermost first.</li>
     *   <li>Open the dropdown and hover <b>"More..."</b> then press it — a <b>submenu</b> opens to the
     *       right and its parent <em>stays open</em>. Clicking back in the parent closes only the submenu.
     *       Those two behaviours are opposites and both have to hold.</li>
     *   <li><b>Arrow keys</b> walk the items and wrap; the whole menu is a single Tab stop.</li>
     * </ol>
     */
    private void menuPage(UIElement pane) {
        UIElement stage = new UIElement();
        stage.addClass("mn-stage");
        pane.addChild(stage);

        UIText report = new UIText("nothing chosen yet");
        report.addClass("label");
        stage.addChild(report);

        Dropdown quality = new Dropdown("quality...");
        quality.addClass("mn-drop");
        quality.addOptions("Low", "Medium", "High", "Ultra");
        quality.attachSelectionListener(index -> report.setText("chose " + quality.getSelectedOption()));
        stage.addChild(quality);

        // A submenu of the dropdown's own menu. addSubmenu wires all of it: the item does not close its
        // parent, the child anchors to the row, and it prefers Side.RIGHT so it sits beside rather than over.
        Menu more = new Menu();
        more.addItem("Ultra+");
        more.addItem("Ridiculous");
        more.onItemActivated.connect(item -> report.setText("chose " + item.getText()));
        stage.addChild(more);

        quality.getMenu().addSubmenu("More...", more);

        UIElement canvas = new UIElement();
        canvas.addClass("mn-canvas");
        UIText hint = new UIText("right-click me");
        hint.addClass("label");
        hint.setHitTest(false);
        canvas.addChild(hint);
        stage.addChild(canvas);

        Menu context = new Menu();
        context.addItem("Add node");
        context.addItem("Paste");
        context.addItem("Select all");
        context.onItemActivated.connect(item -> report.setText(item.getText() + " (context)"));
        stage.addChild(context);

        canvas.onMouseDown.attachListener((el, event) -> {
            if (event.getButtonId() != CgMouseCodes.RIGHT_BUTTON) return;
            var pos = event.getPosition();
            var at = AnchoredPlacement.pointerToRoot(canvas.getAttachedWindow(), pos.x(), pos.y());
            // NO invoker: an invoker is spared by light dismiss, which is what a toggle button needs and
            // what a context menu must not have. Naming the canvas here made the whole canvas unable to
            // dismiss the menu, so left-clicking the area you had just right-clicked did nothing.
            context.showAt(at.x(), at.y(), null);
            report.setText(String.format("context menu at %.0f, %.0f", at.x(), at.y()));
        }, false, false);
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

    /**
     * {@code CgUiPaintContext.curve()} — Bézier strokes as an ordinary painting capability, available
     * to any element's {@code paintSelf} exactly as {@code fillRect} is.
     *
     * <p>The renderer itself is covered against a raw GL surface by the harness's
     * {@code curve-renderer-test} scene. What this page covers is what the UI layer adds:</p>
     * <ul>
     *   <li><b>Pose</b> — strokes are placed in element-local coordinates. Were the {@code PoseStack}
     *       not applied they would land at raw screen coordinates, which reads as a layout bug rather
     *       than a missing matrix, and would drift with {@code uiScale} rather than being obviously
     *       wrong at 1.</li>
     *   <li><b>Interleaving</b> — the load-bearing row. Quads and curves use different materials and
     *       different instance buffers, so alternating them forces a material switch each way, and
     *       painter's order has to survive it. A switch that failed to flush the outgoing path would
     *       draw geometry in material order instead of submission order, putting every stroke on top.</li>
     *   <li><b>Layer opacity</b> — {@code _LayerOpacity} is a material property, so it must be applied
     *       to {@code gui_curve.shader} in its own right; a curve that stayed opaque inside
     *       {@code withLayerOpacity} would mean it was only ever synced onto the quad material.</li>
     * </ul>
     */
    private void curvePage(UIElement pane) {
        // More rows than a pane can hold, deliberately — this is the one page where the interesting
        // cases are visual rather than interactive, so it is worth showing all of them and scrolling.
        ScrollerView scroll = new ScrollerView();
        scroll.addClass("curve-scroll");
        pane.addChild(scroll);

        for (CurveCanvas.Mode mode : CurveCanvas.Mode.values()) {
            scroll.addChild(row(slot(mode.label), new CurveCanvas(mode)));
        }
    }

    /**
     * A plain {@link UIElement} that paints strokes through {@code ctx.curve()} in its own local
     * space — deliberately not a new widget, since the point is that no setup and no material
     * handling is required of the caller.
     */
    private static final class CurveCanvas extends UIElement {

        /** Wall-clock origin for the animated rows. Static so every canvas shares one phase. */
        private static final long START_NANOS = System.nanoTime();

        private enum Mode {
            BASICS("basics", ""),
            CAPS("caps", ""),
            FEATHER("feather", ""),
            WIDTH("width", ""),
            GRADIENT("gradient", ""),
            NEON("neon", ""),
            CUBIC("cubic", ""),
            WAVE("wave", ""),
            RIBBON("ribbon", "curve-mid"),
            NODE_GRAPH("node wires", "curve-mid"),
            FAN("fan", "curve-tall"),
            SPIRAL("spiral", "curve-tall"),
            LISSAJOUS("lissajous", "curve-tall"),
            INTERLEAVED("over/under", ""),
            OPACITY("layer opacity", "");

            final String label;
            /** Extra height class, or empty for the default 74px canvas. */
            final String sizeClass;

            Mode(String label, String sizeClass) {
                this.label = label;
                this.sizeClass = sizeClass;
            }
        }

        private final Mode mode;

        CurveCanvas(Mode mode) {
            this.mode = mode;
            addClass("curve-canvas");
            if (!mode.sizeClass.isEmpty()) addClass(mode.sizeClass);
            setFocusPolicy(FocusPolicy.NONE);
        }

        @Override
        protected void paintSelf(CgUiPaintContext ctx) {
            super.paintSelf(ctx);
            // Absolute layout origin, the same pair UIElement.paintSelf itself paints its background
            // from. The strokes below are offset from it, so they follow the element wherever the
            // gallery's layout puts it rather than being pinned to screen coordinates — which also
            // means they stay correct while the page scrolls, with no scroll-aware code here at all.
            float x = getRuntimeCache().getX();
            float y = getRuntimeCache().getY();
            float t = (System.nanoTime() - START_NANOS) / 1_000_000_000f;

            switch (mode) {
                case BASICS -> paintBasics(ctx, x, y);
                case CAPS -> paintCaps(ctx, x, y);
                case FEATHER -> paintFeather(ctx, x, y);
                case WIDTH -> paintWidth(ctx, x, y);
                case GRADIENT -> paintGradient(ctx, x, y);
                case NEON -> paintNeon(ctx, x, y, t);
                case CUBIC -> paintCubic(ctx, x, y, t);
                case WAVE -> paintWave(ctx, x, y, t);
                case RIBBON -> paintRibbon(ctx, x, y, t);
                case NODE_GRAPH -> paintNodeGraph(ctx, x, y, t);
                case FAN -> paintFan(ctx, x, y, t);
                case SPIRAL -> paintSpiral(ctx, x, y, t);
                case LISSAJOUS -> paintLissajous(ctx, x, y, t);
                case INTERLEAVED -> paintInterleaved(ctx, x, y);
                case OPACITY -> paintOpacity(ctx, x, y);
            }
            ctx.flush();
        }

        /** Line, arc, taper, gradient — the four things one stroke can vary. */
        private void paintBasics(CgUiPaintContext ctx, float x, float y) {
            ctx.curve().line(x + 12, y + 16, x + 96, y + 16).width(1.5f).color(0xFFE0E0E0).submit();
            ctx.curve().from(x + 12, y + 60).via(x + 54, y + 28).to(x + 96, y + 60)
                    .width(2.5f).color(0xFF6CC4FF).submit();
            ctx.curve().from(x + 120, y + 58).via(x + 162, y + 22).to(x + 204, y + 58)
                    .width(7f, 1f).color(0xFF6CC4FF).submit();
            ctx.curve().from(x + 228, y + 58).via(x + 270, y + 22).to(x + 312, y + 58)
                    .width(4f).colors(0xFFFF4D6D, 0xFF4DFFC3).submit();
            ctx.curve().from(x + 336, y + 20).via(x + 336, y + 62).to(x + 404, y + 50)
                    .width(3f).colors(0xFFFFC24D, 0xFFB36CFF).submit();
        }

        /**
         * The three caps at a width where they actually differ, against ticks at the nominal
         * endpoints — the diagonal group matters because a square cap's corner reaches
         * halfWidth*sqrt(2) along one axis only when the stroke is not axis-aligned.
         */
        private void paintCaps(CgUiPaintContext ctx, float x, float y) {
            int[] caps = { CgCurveRenderer.CAP_BUTT, CgCurveRenderer.CAP_ROUND, CgCurveRenderer.CAP_SQUARE };
            for (int i = 0; i < caps.length; i++) {
                float cy = y + 16 + i * 21;
                ctx.curve().line(x + 40, cy, x + 150, cy).width(7f).cap(caps[i]).color(0xFFFFC24D).submit();
                for (int e = 0; e < 2; e++) {
                    float tx = x + 40 + e * 110;
                    ctx.curve().line(tx, cy - 12, tx, cy + 12).width(0.5f).color(0xFF7A7A7A).submit();
                }
            }
            for (int i = 0; i < caps.length; i++) {
                float cx = x + 210 + i * 62;
                ctx.curve().line(cx, y + 16, cx + 34, y + 50)
                        .width(9f).cap(caps[i]).color(0xFFFFC24D).submit();
            }
            ctx.curve().line(x + 330, y + 16, x + 330, y + 58).width(0.5f).color(0xFF3A3F48).submit();
        }

        /** Feather sweep: a hard edge at 0, a soft glow by 12. Same width and colour throughout. */
        private void paintFeather(CgUiPaintContext ctx, float x, float y) {
            for (int i = 0; i < 8; i++) {
                float cx = x + 26 + i * 50;
                float feather = i * 1.7f;
                ctx.curve().line(cx, y + 14, cx, y + 60)
                        .width(6f).feather(feather).cap(CgCurveRenderer.CAP_ROUND)
                        .color(0xFF6CC4FF).submit();
            }
        }

        /** Width sweep, hairline to slab — the range a graph editor actually spans. */
        private void paintWidth(CgUiPaintContext ctx, float x, float y) {
            for (int i = 0; i < 9; i++) {
                float cx = x + 10 + i * 46;
                float w = 0.4f + i * 1.5f;
                ctx.curve().from(cx, y + 58).via(cx + 12, y + 18).to(cx + 24, y + 58)
                        .width(w).cap(CgCurveRenderer.CAP_ROUND).color(hue(i / 9f)).submit();
            }
        }

        /** Gradients, including taper and gradient together — near-free once the record exists. */
        private void paintGradient(CgUiPaintContext ctx, float x, float y) {
            for (int i = 0; i < 5; i++) {
                float cy = y + 14 + i * 12;
                float h0 = i / 5f;
                ctx.curve().from(x + 16, cy).via(x + 210, cy + (i - 2) * 9).to(x + 404, cy)
                        .width(1f + i * 1.6f, 1f + (4 - i) * 1.6f)
                        .colors(hue(h0), hue(h0 + 0.4f))
                        .cap(CgCurveRenderer.CAP_ROUND)
                        .submit();
            }
        }

        /**
         * Neon: the same curve three times — wide and heavily feathered for the halo, then medium,
         * then a thin bright core. Feather is what makes this work at all; without a per-instance
         * softness the halo would be a hard-edged slab.
         */
        private void paintNeon(CgUiPaintContext ctx, float x, float y, float t) {
            float bow = 22f + 10f * (float) Math.sin(t * 1.3f);
            int tint = hue(t * 0.12f);
            float[] widths = { 13f, 6f, 2f };
            float[] feathers = { 16f, 7f, 1.5f };
            int[] alphas = { 0x30, 0x60, 0xFF };
            for (int pass = 0; pass < 3; pass++) {
                int argb = (alphas[pass] << 24) | (tint & 0x00FFFFFF);
                ctx.curve().from(x + 20, y + 52).via(x + 210, y + 52 - bow * 2f).to(x + 400, y + 52)
                        .width(widths[pass]).feather(feathers[pass])
                        .cap(CgCurveRenderer.CAP_ROUND).color(argb).submit();
            }
        }

        /** Cubics — one submit() each, split CPU-side into 1-4 quadratics. Gradient must not band. */
        private void paintCubic(CgUiPaintContext ctx, float x, float y, float t) {
            for (int i = 0; i < 3; i++) {
                float cx = x + 16 + i * 136;
                float wob = 26f + 16f * (float) Math.sin(t * 0.9f + i * 1.1f);
                ctx.curve()
                        .cubic(cx, y + 38,
                                cx + 40, y + 38 - wob,
                                cx + 84, y + 38 + wob,
                                cx + 124, y + 38)
                        .width(6f, 2f)
                        .colors(hue(i / 3f), hue(i / 3f + 0.35f))
                        .cap(CgCurveRenderer.CAP_ROUND)
                        .submit();
            }
        }

        /** A travelling sine, built from a run of quadratics — the polyline case. */
        private void paintWave(CgUiPaintContext ctx, float x, float y, float t) {
            final int segments = 34;
            float span = 396f;
            float midY = y + 37;
            for (int i = 0; i < segments; i++) {
                float t0 = i / (float) segments;
                float t1 = (i + 1) / (float) segments;
                float x0 = x + 12 + span * t0;
                float x1 = x + 12 + span * t1;
                float y0 = midY + waveAt(t0, t);
                float y1 = midY + waveAt(t1, t);
                float xm = (x0 + x1) * 0.5f;
                // Control point placed so the quadratic passes THROUGH the true midpoint of the sine
                // rather than chording between the endpoints: B = 2*M - (P0 + P2)/2. Visibly smoother
                // at this segment count, and the same construction the lissajous row uses.
                float trueMidY = midY + waveAt((t0 + t1) * 0.5f, t);
                float ym = 2f * trueMidY - (y0 + y1) * 0.5f;
                ctx.curve().from(x0, y0).via(xm, ym).to(x1, y1)
                        .width(3.5f).colors(hue(t0 + t * 0.1f), hue(t1 + t * 0.1f))
                        .cap(CgCurveRenderer.CAP_ROUND).submit();
            }
        }

        private static float waveAt(float u, float t) {
            return (float) (Math.sin(u * Math.PI * 4 + t * 2.2) * 20 + Math.sin(u * Math.PI * 7 - t * 1.4) * 7);
        }

        /** Stacked tapered arcs — taper plus gradient plus overlap, the "does it look good" row. */
        private void paintRibbon(CgUiPaintContext ctx, float x, float y, float t) {
            final int strands = 14;
            for (int i = 0; i < strands; i++) {
                float p = i / (float) (strands - 1);
                float phase = t * 0.7f + p * 2.4f;
                float lift = 34f + 26f * (float) Math.sin(phase);
                ctx.curve()
                        .from(x + 18, y + 84)
                        .via(x + 210, y + 84 - lift * 2f)
                        .to(x + 402, y + 84)
                        .width(6f * (1f - p) + 0.6f, 0.6f + 6f * p)
                        .colors(hue(p * 0.5f + t * 0.05f), hue(p * 0.5f + 0.3f + t * 0.05f))
                        .cap(CgCurveRenderer.CAP_ROUND)
                        .submit();
            }
        }

        /**
         * A mock node graph — the actual thing 6.2 is being built toward. Ports are quads, wires are
         * cubics tinted from source port to destination port, which is the standard idiom and the
         * reason gradient was worth having in the instance record at all.
         */
        private void paintNodeGraph(CgUiPaintContext ctx, float x, float y, float t) {
            float[] srcY = { y + 26, y + 52, y + 78 };
            float[] dstY = { y + 34, y + 70 };
            int[] srcColor = { 0xFFFF6B6B, 0xFF6CC4FF, 0xFFFFC24D };
            int[] dstColor = { 0xFF4DFFC3, 0xFFB36CFF };

            float srcX = x + 78;
            float dstX = x + 342;

            // Node bodies first, so the wires cross over them — submission order is the z-order.
            ctx.fillRect(x + 16, y + 14, 62, 78, 0xFF2E333D);
            ctx.fillRect(x + 342, y + 22, 62, 60, 0xFF2E333D);

            for (int s = 0; s < srcY.length; s++) {
                for (int d = 0; d < dstY.length; d++) {
                    if ((s + d) % 2 == 1) continue;   // a subset, so the wires stay readable
                    float sag = 10f * (float) Math.sin(t * 1.1f + s * 0.8f + d);
                    ctx.curve()
                            .cubic(srcX, srcY[s],
                                    srcX + 96, srcY[s] + sag,
                                    dstX - 96, dstY[d] - sag,
                                    dstX, dstY[d])
                            .width(2.5f)
                            .colors(srcColor[s], dstColor[d])
                            .cap(CgCurveRenderer.CAP_ROUND)
                            .submit();
                }
            }
            ctx.flush();

            for (int s = 0; s < srcY.length; s++) ctx.fillRect(srcX - 4, srcY[s] - 4, 8, 8, srcColor[s]);
            for (int d = 0; d < dstY.length; d++) ctx.fillRect(dstX - 4, dstY[d] - 4, 8, 8, dstColor[d]);
        }

        /** Rotating spokes, each a tapered gradient arc. The harness scene's crowd-pleaser. */
        private void paintFan(CgUiPaintContext ctx, float x, float y, float t) {
            float cx = x + 210;
            float cy = y + 75;
            final int spokes = 18;
            float radius = 58f + 8f * (float) Math.sin(t * 0.9f);
            for (int i = 0; i < spokes; i++) {
                double a = (i / (double) spokes) * Math.PI * 2 + t * 0.35;
                float ex = cx + (float) Math.cos(a) * radius;
                float ey = cy + (float) Math.sin(a) * radius;
                float bx = cx + (float) Math.cos(a - 0.5) * radius * 0.55f;
                float by = cy + (float) Math.sin(a - 0.5) * radius * 0.55f;
                ctx.curve().from(cx, cy).via(bx, by).to(ex, ey)
                        .width(4.5f, 1f)
                        .colors(0xFFFFFFFF, hue(i / (float) spokes + t * 0.08f))
                        .cap(CgCurveRenderer.CAP_ROUND)
                        .submit();
            }
        }

        /** An expanding spiral in quadratic segments — many strokes, one draw call. */
        private void paintSpiral(CgUiPaintContext ctx, float x, float y, float t) {
            float cx = x + 210;
            float cy = y + 75;
            final int steps = 70;
            float turns = 3.2f;
            float maxR = 66f;
            float spin = t * 0.5f;
            for (int i = 0; i < steps; i++) {
                float u0 = i / (float) steps;
                float u1 = (i + 1) / (float) steps;
                double a0 = u0 * turns * Math.PI * 2 + spin;
                double a1 = u1 * turns * Math.PI * 2 + spin;
                double am = (a0 + a1) * 0.5;
                float r0 = maxR * u0, r1 = maxR * u1, rm = maxR * (u0 + u1) * 0.5f;
                // Radius of the control point pushed out so the quadratic bulges onto the arc rather
                // than chording it — the same trick the wave row uses, in polar form.
                float bulge = 1f / (float) Math.cos((a1 - a0) * 0.5);
                ctx.curve()
                        .from(cx + (float) Math.cos(a0) * r0, cy + (float) Math.sin(a0) * r0)
                        .via(cx + (float) Math.cos(am) * rm * bulge, cy + (float) Math.sin(am) * rm * bulge)
                        .to(cx + (float) Math.cos(a1) * r1, cy + (float) Math.sin(a1) * r1)
                        .width(0.8f + 5f * u0, 0.8f + 5f * u1)
                        .colors(hue(u0 + t * 0.1f), hue(u1 + t * 0.1f))
                        .cap(CgCurveRenderer.CAP_ROUND)
                        .submit();
            }
        }

        /** A Lissajous figure — long, self-crossing, and the best look at antialiasing quality. */
        private void paintLissajous(CgUiPaintContext ctx, float x, float y, float t) {
            float cx = x + 210;
            float cy = y + 75;
            float rx = 180f, ry = 60f;
            final int steps = 90;
            float phase = t * 0.4f;
            for (int i = 0; i < steps; i++) {
                float u0 = i / (float) steps;
                float u1 = (i + 1) / (float) steps;
                float um = (u0 + u1) * 0.5f;
                float x0 = cx + rx * lx(u0, phase), y0 = cy + ry * ly(u0, phase);
                float x1 = cx + rx * lx(u1, phase), y1 = cy + ry * ly(u1, phase);
                float xm = cx + rx * lx(um, phase), ym = cy + ry * ly(um, phase);
                // Control point that makes the quadratic pass through the true midpoint:
                // B = 2*M - (P0 + P2)/2.
                ctx.curve()
                        .from(x0, y0)
                        .via(2f * xm - (x0 + x1) * 0.5f, 2f * ym - (y0 + y1) * 0.5f)
                        .to(x1, y1)
                        .width(2.6f)
                        .colors(hue(u0 * 2f + t * 0.07f), hue(u1 * 2f + t * 0.07f))
                        .cap(CgCurveRenderer.CAP_ROUND)
                        .submit();
            }
        }

        private static float lx(float u, float phase) {
            return (float) Math.sin(u * Math.PI * 2 * 3 + phase);
        }

        /** Second axis at a 2:3 ratio against {@link #lx}, drifting so the figure keeps reshaping. */
        private static float ly(float u, float phase) {
            return (float) Math.sin(u * Math.PI * 2 * 2 + phase * 0.6);
        }

        /**
         * Stroke, then a panel over it, then a stroke over that — submitted in that order, so the
         * correct result is a sandwich. Mirrored on the right so an ordering rule that only holds one
         * way round still fails visibly.
         */
        private void paintInterleaved(CgUiPaintContext ctx, float x, float y) {
            // Both strokes must cross the panel's VERTICAL span, not just its horizontal one — a
            // stroke that merely grazes the panel's edge proves nothing about ordering, because
            // "hidden behind it" and "drawn over it" look identical when they barely touch.
            ctx.curve().line(x + 12, y + 26, x + 190, y + 26).width(8f).color(0xFFFF4D6D).submit();
            ctx.fillRect(x + 70, y + 14, 70, 40, 0xFF4D8CFF);
            ctx.curve().line(x + 12, y + 44, x + 190, y + 44).width(6f).color(0xFF4DFFC3).submit();

            ctx.fillRect(x + 250, y + 14, 70, 40, 0xFF4D8CFF);
            ctx.curve().line(x + 220, y + 34, x + 400, y + 34).width(8f).color(0xFFFFC24D).submit();
        }

        /** The lower stroke and the lower bar must fade by the same amount. */
        private void paintOpacity(CgUiPaintContext ctx, float x, float y) {
            ctx.curve().line(x + 12, y + 24, x + 180, y + 24).width(6f).color(0xFF6CC4FF).submit();
            ctx.flush();
            ctx.withLayerOpacity(0.35f, () -> {
                ctx.curve().line(x + 12, y + 52, x + 180, y + 52).width(6f).color(0xFF6CC4FF).submit();
                ctx.flush();
            });

            ctx.fillRect(x + 220, y + 18, 180, 12, 0xFF6CC4FF);
            ctx.withLayerOpacity(0.35f, () -> ctx.fillRect(x + 220, y + 46, 180, 12, 0xFF6CC4FF));
        }

        /** Opaque ARGB from a hue phase — deterministic, and only ever used for demo colour. */
        private static int hue(float phase) {
            float h = (phase % 1f + 1f) % 1f;
            float r = clamp01(Math.abs(h * 6f - 3f) - 1f);
            float g = clamp01(2f - Math.abs(h * 6f - 2f));
            float b = clamp01(2f - Math.abs(h * 6f - 4f));
            return 0xFF000000 | ((int) (r * 255f) << 16) | ((int) (g * 255f) << 8) | (int) (b * 255f);
        }

        private static float clamp01(float v) {
            return v < 0f ? 0f : (v > 1f ? 1f : v);
        }
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
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        if (event.pressed()) {
            switch (event.key()) {
                case CgKeyCodes.KEY_RBRACKET -> {
                    setScale(Math.min(8f, uiWindow.getUiScale() + 0.1f));
                    return true;
                }
                case CgKeyCodes.KEY_LBRACKET -> {
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
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return uiWindow.getInputHandler().consumeMouseEvent(event);
    }
}
