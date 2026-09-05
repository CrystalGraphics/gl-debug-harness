package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.composite.ColorSelector;
import com.crystalgui.widget.composite.SearchField;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.control.CheckboxGroup;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.Switch;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.display.ProgressBar;
import com.crystalgui.widget.composite.RadarChart;
import com.crystalgui.widget.display.SymbolIcon;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.overlay.DialogManager;
import com.crystalgui.widget.overlay.Dropdown;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.MenuItem;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.text.UIText;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.util.HarnessThemes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.joml.Matrix4f;

/**
 * <b>RPG-Core's menu, as a stylesheet fixture.</b> {@code --mode=rpg-console}.
 *
 * <h3>What it is for</h3>
 *
 * <p>Authoring {@code rpgcore:console} and {@code rpgcore:menu} without launching Minecraft. A dev
 * client takes minutes to reach a screen; this reaches the same pixels in seconds, and
 * <b>Ctrl+R re-reads both files</b> — the theme through {@code UiThemeManager.reloadFromDisk()} and
 * the sheets through {@code StyleEngine.reloadStylesheets()}, which the runner pairs because neither
 * covers the other.</p>
 *
 * <h3>Two pages, and the rail switches them</h3>
 *
 * <p><b>Status</b> is the screen the mod actually ships, and is what {@code menu.css} is judged
 * against. <b>Options</b> is a widget gallery — every stock control the theme restyles, on one
 * scrolling page, which is the only way to see whether a theme rule matches anything: a rule that
 * misses looks exactly like a rule that works. The remaining five tabs draw the placeholder the mod
 * draws, which is also what a rail button in its ordinary state looks like.</p>
 *
 * <p>The rail is buttons rather than a {@code TabView}, because the mod swaps whole screens rather
 * than panes. Switching rebuilds the body — safe here and only here, since the rail is outside it;
 * a widget must never rebuild the elements it is being clicked on.</p>
 *
 * <h3>It is a fixture, not the product</h3>
 *
 * <p>The tree is built from stock widgets wearing the {@code .rpg-*} classes the plan defines.
 * RPG-Core's own screens will build the same shapes from the same classes at U2, and the CSS carries
 * across untouched — the same relationship {@code cgui-gallery} has with the workbench. Nothing here
 * holds game state or reads a character; the numbers are the ones in
 * {@code plan/ui/OPEN_STATUS_SCREEN.png}, typed out.</p>
 *
 * <p><b>It needs RPG-Core's resources on the resolver</b>, which {@code ./gradlew runHarness} from
 * that project arranges. Run from here instead and the theme is refused with a log line naming the
 * file it could not find, leaving the bare user-agent sheet — which is legible rather than broken,
 * and is also what {@code -Dcrystalgui.theme=none} asks for on purpose.</p>
 */
public class RpgConsoleScene implements InteractiveSceneLifecycle,
        CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /**
     * What RPG-Core's own host answers, so the fixture measures what the game measures.
     *
     * <p>{@code HostServices.DEFAULT_UI_SCALE}. Given to the BOX TREE rather than to the paint pose:
     * the root transform is the one definition of what {@code uiScale} means, so layout, painting and
     * hit-testing all compose through it and agree. Scale the pose instead and the picture is right
     * while every click lands at half the distance.</p>
     */
    private static final float SCALE = 2f;

    private static final String STATUS = "Status";
    private static final String OPTIONS = "Options";
    private static final String[] TABS =
            {STATUS, "Skills", "Abilities", "Forms", "Styles", "Slots", OPTIONS};

    /**
     * {@code CoreAttributes}' six, with their registered colours — DATA, which is why none of this is
     * in the sheet. The values are the fixture's own and deliberately uneven: a sheet of equal values
     * draws a plain hexagon, which cannot show whether the chart maps a value to a radius at all.
     *
     * <p>Opaque, which is what {@code CoreAttributes} actually registers. The chart washes the fill
     * itself from {@code ::part(fill)} and draws the rim and the label at full strength, so a caller
     * hands over a palette and nothing else.</p>
     */
    private static final String[][] ATTRIBUTES = {
            {"STR", "FFFF0000", "8"}, {"CON", "FFFF6A00", "5"}, {"DEX", "FFFFD800", "6"},
            {"WIL", "FF00FF77", "3"}, {"SPI", "FFB200FF", "7"}, {"FOC", "FF00FFFF", "4"},
    };

    private static final String[][] RESOURCES = {
            {"Energy", "3", "5"},
            {"Stamina", "0.01", "2"},
            {"Mobility", "5", "100"},
            {"Posture", "0.02", "10"},
            {"Health", "0.10", "20"},
    };

    private static final String[][] STATS = {
            {"Energy Power", "0"}, {"Melee Power", "0"},
            {"Speed", "100"}, {"Combat Speed", "100"},
    };

    private UIDocument document;
    private UIElement body;
    private UIText ribbonTitle;
    private final List<Button> railButtons = new ArrayList<>();
    private ProgressBar walking;
    private float elapsed;

    @Override
    public void init(HarnessContext ctx) {
        document = new UIDocument().markFrameThread();

        // THE WHOLE STACK, and not `addStylesheet(StyleSheet.DEFAULT)` by hand. A theme's TOKENS reach
        // the screen through variable substitution and would work either way; its override RULES live
        // in a sheet UiThemeManager owns, which only installInto adds. Adding DEFAULT by hand gives a
        // three-quarters-themed scene that reports nothing about the missing quarter.
        HarnessThemes.install(document.styles(), "rpgcore:console");

        document.boxes().setRootTransform(new Matrix4f().scale(SCALE, SCALE, 1f));

        UIElement backdrop = buildRoot();
        document.append(backdrop);

        // SCOPED to the backdrop, exactly as the mod will add it -- so the fixture proves the scoping
        // works rather than assuming it. Added after the append because a scope root has to be in the
        // tree for anything under it to match.
        document.styles().addStylesheet(StyleSheetRegistry.of("rpgcore:menu"), backdrop);
        document.styles().addStylesheet(StyleSheet.parse(SCENE_CSS), backdrop);

        // Straight to the page being styled: -Dcrystalgui.rpg.tab=Options. runHarness forwards every
        // -Dcrystalgui.*, so this needs no Gradle plumbing.
        show(System.getProperty("crystalgui.rpg.tab", STATUS));
    }

    /**
     * Fixture-only rules: the two placeholders and the gallery's own layout.
     *
     * <p>Separate from {@code menu.css} on purpose. Everything here is scaffolding for something the
     * mod has and the engine does not yet — a radar chart (U4) and a live entity preview (U6) — or for
     * the gallery, which is not a screen the mod will ever build. If a rule here starts describing how
     * RPG-Core should look, it belongs in {@code menu.css} instead.</p>
     */
    private static final String SCENE_CSS = """
            .fx-placeholder {
                width: 100%;
                height: 0;
                flex-grow: 1;
                align-items: center;
                justify-content: center;
            }
            .fx-radar { height: 180px; flex-grow: 0; }
            .fx-readouts {
                width: 100%;
                flex-direction: column;
                gap-all: 8px;
                /* Clear of the scrollbar. The values are right-aligned, so without this the last
                   digit sits against the bar and reads as touching it. */
                padding-right: 10px;
            }
            .fx-note { color: var(--fg-hint); }

            /* The gallery: one scrolling column of labelled sections. */
            .fx-gallery {
                width: 100%;
                height: 0;
                flex-grow: 1;
            }
            .fx-column {
                width: 100%;
                flex-direction: column;
                gap-all: 10px;
                padding-right: 14px;
            }
            .fx-section {
                width: 100%;
                flex-direction: column;
                align-items: flex-start;
                gap-all: 6px;
                padding-all: 10px;
            }
            .fx-row {
                flex-direction: row;
                align-items: center;
                gap-all: 10px;
                flex-wrap: wrap;
            }
            .fx-wide { width: 220px; }
            .fx-bar { width: 180px; height: 6px; }
            .fx-stage {
                width: 100%;
                height: 200px;
                background-color: #13222A;
                border-radius: 4px;
            }
            .fx-split { width: 100%; height: 150px; }
            .fx-tabs { width: 100%; height: 170px; }
            .fx-pane {
                width: 100%;
                height: 0;
                flex-grow: 1;
                align-items: center;
                justify-content: center;
            }
            """;

    // ── The shell ───────────────────────────────────────────────────────────────────────────────

    private UIElement buildRoot() {
        UIElement backdrop = new UIElement().addClass("rpg-backdrop");
        // FIRST, so painter's order draws it under everything. Absolutely positioned, so it
        // contributes nothing to the row the rail and the content lay out in.
        backdrop.append(new UIElement().addClass("rpg-grid"));
        backdrop.append(buildRail());

        UIElement content = new UIElement().addClass("rpg-content");
        content.append(buildRibbon());
        content.append(new UIElement().addClass("rpg-hairline"));
        body = new UIElement().addClass("rpg-body");
        content.append(body);
        backdrop.append(content);
        return backdrop;
    }

    private UIElement buildRail() {
        UIElement rail = new UIElement().addClass("rpg-tabs");
        for (String name : TABS) {
            Button tab = new Button(name);
            tab.addClass("rpg-tab");
            tab.attachListener(() -> show(name));
            railButtons.add(tab);
            rail.append(tab);
        }
        return rail;
    }

    private UIElement buildRibbon() {
        UIElement ribbon = new UIElement().addClass("rpg-ribbon");
        UIElement bar = new UIElement().addClass("rpg-ribbon-body");
        ribbonTitle = new UIText(STATUS);
        ribbonTitle.addClass("rpg-ribbon-title");
        bar.append(ribbonTitle);
        // A HYPHEN, not an em dash. The bundled Minecraft font has no U+2014, so the screen drew a
        // tofu box where the separator should be -- the same gap `UIText` already documents for the
        // ellipsis, met by a fixture rather than by the engine.
        bar.append(new UIText("  -  Dev").addClass("rpg-ribbon-subject"));
        ribbon.append(bar);
        ribbon.append(new UIElement().addClass("rpg-ribbon-cap"));
        return ribbon;
    }

    /** Swaps the body and moves the rail's selection. The rail is outside the body, so this is safe. */
    private void show(String tab) {
        ribbonTitle.setText(tab);
        for (Button button : railButtons) {
            if (button.getText().equals(tab)) button.addClass("__selected__");
            else button.removeClass("__selected__");
        }
        body.removeAll();
        body.append(switch (tab) {
            case STATUS -> buildStatusPage();
            case OPTIONS -> buildGalleryPage();
            default -> buildPlaceholderPage(tab);
        });
    }

    // ── Status: the screen the mod ships ────────────────────────────────────────────────────────

    private UIElement buildStatusPage() {
        UIElement columns = new UIElement().addClass("rpg-columns");
        columns.append(buildAttributes());
        columns.append(buildPreview());
        columns.append(buildResources());
        return columns;
    }

    private UIElement buildAttributes() {
        UIElement column = new UIElement().addClass("rpg-column");
        column.append(new UIText("Attributes").addClass("rpg-heading"));

        RadarChart radar = new RadarChart();
        radar.addClass("rpg-radar");
        List<RadarChart.Axis> spokes = new ArrayList<>();
        for (String[] attribute : ATTRIBUTES) {
            // The detail is the CALLER's words -- the widget formats nothing, so a mod decides how its
            // own numbers read.
            spokes.add(new RadarChart.Axis(attribute[0], Double.parseDouble(attribute[2]),
                    (int) Long.parseLong(attribute[1], 16),
                    attribute[0] + ": " + attribute[2]));
        }
        // The fill fades toward the centre, where all six wedges converge -- see setAxisGradients.
        radar.setAxisGradients(true);
        radar.setAxes(spokes);
        column.append(radar);

        for (String[] attribute : ATTRIBUTES) {
            UIElement row = new UIElement().addClass("rpg-row");
            UIElement name = new UIText(attribute[0])
                    .addClass("rpg-row-name").addClass("rpg-radar-label");
            // INLINE, and legitimately so: an attribute's colour is registry data, and a mod may
            // register one in any hue, so no stylesheet can enumerate them.
            int argb = (int) Long.parseLong(attribute[1], 16);
            StyleGroup.inlinePipeline(name.getStyle().getGeneralGroup(), g -> g.color(argb));
            row.append(name);

            UIElement value = new UIElement().addClass("rpg-value");
            value.append(new UIText(attribute[2]));
            row.append(value);

            Button upgrade = new Button("+");
            upgrade.addClass("rpg-upgrade");
            upgrade.setEnabled(false);
            row.append(upgrade);
            column.append(row);
        }
        return column;
    }

    private UIElement buildPreview() {
        UIElement column = new UIElement().addClass("rpg-column");
        UIElement plate = new UIElement()
                .addClass("rpg-plate").addClass("rpg-lamp").addClass("fx-placeholder");
        plate.append(new UIText("EntityPreview - U6").addClass("fx-note"));
        column.append(plate);
        return column;
    }

    /**
     * The readouts, in a scroller.
     *
     * <p>The mod puts these lists in {@code ScrollPane}s with a capped height, and without one the
     * column simply overflowed its own box and drew across whatever was beneath it — the engine does
     * not clip a child that overruns unless something establishes a scroll container.</p>
     */
    private UIElement buildResources() {
        UIElement column = new UIElement().addClass("rpg-column");
        ScrollerView scroller = new ScrollerView();
        scroller.addClass("fx-gallery");

        // NOT .rpg-column. That carries the ROW fill idiom (`width: 0; flex-grow: 1`), which is right
        // for a column sitting in .rpg-columns and wrong inside a scroller: flex-grow works on the
        // MAIN axis, so in a column parent the width stayed 0, every row measured 0 wide, and the
        // right-aligned value drew straight over its own label.
        UIElement list = new UIElement().addClass("fx-readouts");
        list.append(new UIText("Resources").addClass("rpg-heading"));
        for (String[] resource : RESOURCES) {
            list.append(new UIText(resource[0] + ":").addClass("rpg-heading"));
            list.append(labelled("Regen:", resource[1]));
            list.append(labelled("Max Value:", resource[2]));
        }
        list.append(new UIText("Stats").addClass("rpg-heading"));
        for (String[] stat : STATS) {
            list.append(labelled(stat[0] + ":", stat[1]));
        }

        scroller.append(list);
        column.append(scroller);
        return column;
    }

    /** A dim name and a right-aligned number — the shape every readout on this screen takes. */
    private UIElement labelled(String name, String value) {
        UIElement row = new UIElement().addClass("rpg-row");
        row.append(new UIText(name).addClass("rpg-row-name").addClass("rpg-secondary"));
        UIElement box = new UIElement().addClass("rpg-value");
        box.append(new UIText(value));
        row.append(box);
        return row;
    }

    private UIElement buildPlaceholderPage(String tab) {
        UIElement plate = new UIElement().addClass("rpg-plate").addClass("fx-placeholder");
        plate.append(new UIText(tab + " has no content yet").addClass("fx-note"));
        return plate;
    }

    // ── Options: the widget gallery ─────────────────────────────────────────────────────────────

    /**
     * Every stock widget the theme restyles, on one scrolling page.
     *
     * <p>Deliberately not every widget the engine has: the ones left out ({@code ListView},
     * {@code TreeView}, {@code TableView}, {@code GraphView}, {@code ConfiguratorPanel}) need a model
     * and a renderer, which is fixture code that says nothing about a stylesheet. What is here is
     * everything {@code console.css} has a rule for plus the composites those rules reach through.</p>
     */
    private UIElement buildGalleryPage() {
        ScrollerView scroller = new ScrollerView();
        scroller.addClass("fx-gallery");
        UIElement column = new UIElement().addClass("fx-column");
        scroller.append(column);

        Button disabled = new Button("Disabled");
        disabled.setEnabled(false);
        column.append(section("Button - the plate bevel, its hover and its pressed state",
                row(new Button("Ordinary"), disabled)));

        Checkbox first = new Checkbox("First");
        Checkbox second = new Checkbox("Second");
        CheckboxGroup group = new CheckboxGroup().allowEmpty(false);
        first.setGroup(group);
        second.setGroup(group);
        first.setChecked(true);
        column.append(section("Checkbox - the steel ring, and the green tick that replaced a bare mark",
                row(new Checkbox("Standalone"), first, second)));

        column.append(section("Switch", row(new Switch(), new Switch().setChecked(true))));

        Slider slider = new Slider();
        slider.setRange(0f, 100f);
        slider.setValue(40f);
        slider.addClass("fx-wide");
        column.append(section("Slider - drag it", row(slider)));

        walking = new ProgressBar();
        walking.addClass("fx-bar");
        ProgressBar indeterminate = new ProgressBar();
        indeterminate.setFraction(-1f);
        indeterminate.addClass("fx-bar");
        column.append(section("ProgressBar - determinate walks, indeterminate sweeps",
                row(walking, indeterminate)));

        TextField field = new TextField();
        field.setPlaceholder("type anything");
        field.addClass("fx-wide");
        column.append(section("TextField - the recess, and the holo edge it takes on focus",
                row(field)));

        SearchField search = new SearchField();
        search.addClass("fx-wide");
        column.append(section("SearchField", row(search)));

        Dropdown dropdown = new Dropdown("Pick one");
        dropdown.addOption("Alpha");
        dropdown.addOption("Beta");
        dropdown.addOption("Gamma");
        column.append(section("Dropdown - click to open its Menu", row(dropdown)));

        Button menuAnchor = new Button("Open a menu");
        Menu menu = new Menu();
        menu.addItem(new MenuItem("Cut"));
        menu.addItem(new MenuItem("Copy"));
        menu.addItem(new MenuItem("Paste"));
        column.append(menu);
        menuAnchor.attachListener(() -> menu.showFor(menuAnchor, menuAnchor));

        Button tipped = new Button("Hover me");
        Tooltip.attach(tipped, "Black glass, like the menus");

        Button popAnchor = new Button("Toggle a popover");
        Popover popover = new Popover();
        popover.append(new UIText("A bare popover."));
        column.append(popover);
        popAnchor.attachListener(() -> {
            if (popover.isOpen()) popover.hide();
            else popover.showFor(popAnchor, popAnchor);
        });
        column.append(section("Menu, Tooltip and Popover - one overlay surface, three doors",
                row(menuAnchor, tipped, popAnchor)));

        SymbolIcon cls = new SymbolIcon();
        cls.show(SymbolKind.CLASS, Set.of());
        SymbolIcon iface = new SymbolIcon();
        iface.show(SymbolKind.INTERFACE, Set.of());
        SymbolIcon method = new SymbolIcon();
        method.show(SymbolKind.METHOD, Set.of(SymbolModifier.STATIC, SymbolModifier.FINAL));
        column.append(section("SymbolIcon - class, interface, and a method with its marks",
                row(cls, iface, method)));

        column.append(section("ColorSelector - the deepest composite the theme reaches",
                row(new ColorSelector())));

        column.append(section("SplitView - drag the divider", splitView()));
        column.append(section("TabView - the engine's own tabs, which are glass here too", tabView()));
        column.append(section("Dialog - the plate, its title bar, and the backdrop behind it", dialogs()));
        return scroller;
    }

    private UIElement splitView() {
        SplitView split = new SplitView();
        split.addClass("fx-split");
        split.setPercentage(40f).setLimits(15f, 85f);
        split.first().append(pane("first pane"));
        split.second().append(pane("second pane"));
        return split;
    }

    private UIElement tabView() {
        TabView tabs = new TabView();
        tabs.addClass("fx-tabs");
        for (String name : new String[] {"one", "two", "three"}) {
            Tab tab = tabs.addTab(name);
            tab.setClosable(true);
            tab.onCloseRequested.connect(() -> tabs.removeTab(tab));
            tab.content().append(pane("pane " + name));
        }
        return tabs;
    }

    private UIElement dialogs() {
        UIElement stage = new UIElement().addClass("fx-stage");
        DialogManager manager = new DialogManager(stage);

        Dialog panel = manager.manage(new Dialog("A dialog"));
        panel.getContent().append(new UIText("Drag my title bar."));

        Button open = new Button("open");
        open.attachListener(manager::showAll);
        Button close = new Button("close");
        close.attachListener(manager::closeAll);

        UIElement box = new UIElement().addClass("fx-column");
        box.append(row(open, close));
        box.append(stage);
        return box;
    }

    private UIElement pane(String label) {
        UIElement body = new UIElement().addClass("fx-pane");
        UIText text = new UIText(label);
        text.addClass("fx-note");
        // A pane's label must not shadow the pane it names.
        text.setHitTest(false);
        body.append(text);
        return body;
    }

    private UIElement section(String heading, UIElement content) {
        UIElement section = new UIElement().addClass("rpg-plate").addClass("fx-section");
        section.append(new UIText(heading).addClass("rpg-heading"));
        section.append(content);
        return section;
    }

    private UIElement row(UIElement... children) {
        UIElement row = new UIElement().addClass("fx-row");
        for (UIElement child : children) {
            if (child != null) row.append(child);
        }
        return row;
    }

    // ── Frame ───────────────────────────────────────────────────────────────────────────────────

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();
        float delta = frame.getDeltaTime();

        // A walking bar, so one glance says the accent reaches a fill that is written every frame --
        // a frozen bar and a correct one look identical at rest. Null on every page but the gallery.
        elapsed += delta;
        if (walking != null && walking.document() != null) {
            walking.setFraction((elapsed % 4f) / 4f);
        }

        document.frame(delta, w / SCALE, h / SCALE);

        CgUiPaintContext context = CgUiPaintContext.getInstance();
        context.beginFrame(w, h);
        document.paint(context);
        context.endFrame();

        if (frame.getFrameNumber() == 5) ctx.getArtifactService().requestCapture("startup");
    }

    @Override
    public void dispose() {
        document = null;
        body = null;
        ribbonTitle = null;
        walking = null;
        railButtons.clear();
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
