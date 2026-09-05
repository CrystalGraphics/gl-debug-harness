package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.display.ProgressBar;
import com.crystalgui.widget.text.UIText;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.util.HarnessThemes;
import org.joml.Matrix4f;

/**
 * <b>RPG-Core's Status screen, as a stylesheet fixture.</b> {@code --mode=rpg-console}.
 *
 * <h3>What it is for</h3>
 *
 * <p>Authoring {@code rpgcore:console} and {@code rpgcore:menu} without launching Minecraft. A dev
 * client takes minutes to reach a screen; this reaches the same pixels in seconds, and
 * <b>Ctrl+R re-reads both files</b> — the theme through {@code UiThemeManager.reloadFromDisk()} and
 * the sheets through {@code StyleEngine.reloadStylesheets()}, which the runner pairs because neither
 * covers the other.</p>
 *
 * <h3>It is a fixture, not the product</h3>
 *
 * <p>The tree here is built from stock CrystalGUI widgets wearing the {@code .rpg-*} classes the
 * plan defines. RPG-Core's own screens will build the same shapes from the same classes when
 * {@code plan/ui/plan.md} reaches U2, and the CSS carries across untouched — that is the whole point
 * of the split, and the same relationship {@code cgui-gallery} has with the workbench. Nothing in
 * this file may become the product: it holds no game state, reads no character, and its numbers are
 * the ones in {@code plan/ui/OPEN_STATUS_SCREEN.png} typed out by hand.</p>
 *
 * <p><b>It needs RPG-Core's resources on the resolver</b>, which is not automatic:</p>
 *
 * <pre>
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=rpg-console" \
 *     -Pharness.assetRoots=X:/projects/RPG-Core-NeoForge/src/main/resources
 * </pre>
 *
 * <p>Put that property in {@code ~/.gradle/gradle.properties} once and it applies to every run.
 * Without it the theme is refused with a log line naming the file it could not find, and the scene
 * draws on the bare user-agent sheet — which is a legible state rather than a broken one, and is
 * also what {@code -Dcrystalgui.theme=none} asks for deliberately.</p>
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

    private static final String[] TABS =
            {"Status", "Skills", "Abilities", "Forms", "Styles", "Slots", "Options"};

    /** {@code CoreAttributes}, verbatim — these are DATA, which is why they are not in the sheet. */
    private static final String[][] ATTRIBUTES = {
            {"STR", "0xFFFF0000"}, {"CON", "0xFFFF6A00"}, {"DEX", "0xFFFFD800"},
            {"WIL", "0xFF00FF77"}, {"SPI", "0xFFB200FF"}, {"FOC", "0xFF00FFFF"},
    };

    private static final String[][] RESOURCES = {
            {"Energy", "0", "3", "5"},
            {"Stamina", "0", "0.01", "2"},
            {"Mobility", "0", "5", "100"},
            {"Posture", "0", "0.02", "10"},
            {"Health", "0", "0.10", "20"},
    };

    private static final String[][] STATS = {
            {"Energy Power", "0"}, {"Melee Power", "0"},
            {"Speed", "100"}, {"Combat Speed", "100"},
    };

    private UIDocument document;
    private ProgressBar health;
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
    }

    /**
     * Fixture-only rules: the two placeholders and the widget strip.
     *
     * <p>Separate from {@code menu.css} on purpose. Anything in here is scaffolding for something the
     * mod has and the engine does not yet — a radar chart (U4) and a live entity preview (U6) — plus a
     * strip of stock widgets that exists so the theme's widget rules are visible on screen. If a rule
     * here starts describing how RPG-Core should look, it belongs in {@code menu.css} instead.</p>
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
            .fx-note { color: var(--fg-hint); }
            .fx-strip {
                width: 100%;
                flex-direction: row;
                align-items: center;
                gap-all: 10px;
                flex-shrink: 0;
                padding-all: 8px;
            }
            .fx-strip textfield { width: 130px; }
            .fx-strip progressbar { width: 90px; }
            """;

    private UIElement buildRoot() {
        UIElement backdrop = new UIElement().addClass("rpg-backdrop");
        backdrop.append(buildRail());

        UIElement content = new UIElement().addClass("rpg-content");
        content.append(buildRibbon());
        content.append(new UIElement().addClass("rpg-hairline"));
        content.append(buildColumns());
        content.append(buildWidgetStrip());
        backdrop.append(content);
        return backdrop;
    }

    private UIElement buildRail() {
        UIElement rail = new UIElement().addClass("rpg-tabs");
        for (int i = 0; i < TABS.length; i++) {
            Button tab = new Button(TABS[i]);
            tab.addClass("rpg-tab");
            // The open one. A CLASS rather than :checked, because these are buttons that swap whole
            // screens -- the screen knows which is current, the widget cannot.
            if (i == 0) tab.addClass("__selected__");
            rail.append(tab);
        }
        return rail;
    }

    private UIElement buildRibbon() {
        UIElement ribbon = new UIElement().addClass("rpg-ribbon");
        UIElement body = new UIElement().addClass("rpg-ribbon-body");
        body.append(new UIText("Status").addClass("rpg-ribbon-title"));
        body.append(new UIText("  \u2014  Dev").addClass("rpg-ribbon-subject"));
        ribbon.append(body);
        ribbon.append(new UIElement().addClass("rpg-ribbon-cap"));
        return ribbon;
    }

    private UIElement buildColumns() {
        UIElement columns = new UIElement().addClass("rpg-columns");
        columns.append(buildAttributes());
        columns.append(buildPreview());
        columns.append(buildResources());
        return columns;
    }

    private UIElement buildAttributes() {
        UIElement column = new UIElement().addClass("rpg-column");
        column.append(new UIText("Attributes").addClass("rpg-heading"));

        UIElement radar = new UIElement().addClass("rpg-plate").addClass("fx-placeholder").addClass("fx-radar");
        radar.append(new UIText("RadarChart \u2014 U4").addClass("fx-note"));
        column.append(radar);

        for (String[] attribute : ATTRIBUTES) {
            UIElement row = new UIElement().addClass("rpg-row");
            UIElement name = new UIText(attribute[0]).addClass("rpg-row-name").addClass("rpg-radar-label");
            // INLINE, and legitimately so: an attribute's colour is registry data, and a mod may
            // register one in any hue, so no stylesheet can enumerate them.
            int argb = (int) Long.parseLong(attribute[1].substring(2), 16);
            StyleGroup.inlinePipeline(name.getStyle().getGeneralGroup(), g -> g.color(argb));
            row.append(name);

            UIElement value = new UIElement().addClass("rpg-value");
            value.append(new UIText("1"));
            row.append(value);

            Button upgrade = new Button("+");
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
        plate.append(new UIText("EntityPreview \u2014 U6").addClass("fx-note"));
        column.append(plate);
        return column;
    }

    private UIElement buildResources() {
        UIElement column = new UIElement().addClass("rpg-column");

        column.append(new UIText("Resources").addClass("rpg-heading"));
        for (String[] resource : RESOURCES) {
            column.append(new UIText(resource[0] + ":").addClass("rpg-heading"));
            column.append(labelled("Regen:", resource[2]));
            column.append(labelled("Max Value:", resource[3]));
        }

        column.append(new UIText("Stats").addClass("rpg-heading"));
        for (String[] stat : STATS) {
            column.append(labelled(stat[0] + ":", stat[1]));
        }
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

    /**
     * Stock widgets, so the theme's own rules are visible.
     *
     * <p>Not part of the screen and never will be. It is here because {@code console.css} styles the
     * button bevel, the checkbox ring, the slider and the field, and a screen made only of plates and
     * labels would show none of them — a theme rule that matches nothing looks exactly like a theme
     * rule that works.</p>
     */
    private UIElement buildWidgetStrip() {
        UIElement strip = new UIElement().addClass("rpg-plate").addClass("fx-strip");
        strip.append(new Button("Button"));

        Button disabled = new Button("Disabled");
        disabled.setEnabled(false);
        strip.append(disabled);

        strip.append(new Checkbox("Unchecked"));
        strip.append(new Checkbox("Checked").setChecked(true));

        Slider slider = new Slider();
        slider.setRange(0f, 100f);
        slider.setValue(40f);
        strip.append(slider);

        strip.append(new TextField().setPlaceholder("type anything"));

        health = new ProgressBar();
        strip.append(health);
        return strip;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();
        float delta = frame.getDeltaTime();

        // A walking bar, so one glance says the accent reaches a fill that is being written every
        // frame -- a frozen bar and a correct one look identical at rest.
        elapsed += delta;
        health.setFraction((elapsed % 4f) / 4f);

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
        health = null;
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
