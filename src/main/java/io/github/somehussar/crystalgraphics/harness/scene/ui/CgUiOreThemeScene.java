package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * Interactive harness scene exercising the ported LDLib2 "Ore" theme
 * ({@code assets/crystalgui/ui/styles/ore.css} + {@code assets/crystalgui/ui/sprites/ore.json} +
 * {@code assets/crystalgui/textures/gui/ore_styles.png}), applied via
 * {@link StyleSheetRegistry#of(String)} — deliberately no competing inline flat-color CSS (unlike
 * {@code CgUiButtonScene}/{@code CgUiCheckboxScene}) so the theme's actual visual effect is what's
 * on screen.
 *
 * <p>Left panel is live (click the button, toggle the checkbox to disable it). Right panel is a
 * forced-state matrix — every visual state is driven programmatically by {@link #forceStates()} so
 * hover/pressed/checked can be verified from a static screenshot with no cursor involved.</p>
 *
 * <p>Manual verification checklist:</p>
 * <ul>
 *   <li>Button renders with the Ore 9-slice texture; hover swaps to a visibly different sprite,
 *       pressed adds a gray (NOT red) multiply, disabled has a gray rather than black border.</li>
 *   <li>Checkbox box is white when unchecked and green with a white check when checked, with
 *       distinct hover/pressed variants in both colours.</li>
 *   <li>Only the box + label are clickable — the empty space to their right must NOT toggle it
 *       (the checkbox hugs its content via {@code align-self: flex-start} precisely to avoid an
 *       invisible-but-clickable stretched region).</li>
 *   <li>The {@code .panel} card renders with the Ore rounded-panel 9-slice background.</li>
 *   <li>Focused rows show a 1px white ring via the layout-free {@code outline} layer — on the
 *       button's whole box, and on the checkbox's 12x12 mark only (not the whole row).</li>
 *   <li>Third panel is the {@code overlay-size} matrix: the same 10x10 check sprite in four
 *       oversized boxes — {@code fill} distorts, {@code none} stays 10x10, {@code contain} fits
 *       inside preserving aspect, {@code cover} overflows preserving aspect.</li>
 * </ul>
 */
public class CgUiOreThemeScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** Logical-to-surface scale, as the harness\'s other new-engine scenes use. */
    private static final float SCALE = 2f;

    private UIDocument document;
    private Button demoButton;
    private Button hoverButton;
    private Button pressedButton;
    private Checkbox hoverBox;
    private Checkbox pressedBox;
    private Checkbox checkedHoverBox;
    private Button focusButton;
    private Checkbox focusBox;
    private int clickCount = 0;

    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(false);

        UIElement root = createDemo();
        this.document = new UIDocument().markFrameThread();
        this.document.boxes().setUiScale(SCALE);
        UIElement sceneRoot = root;
        // THE ROOT FILLS THE DOCUMENT. On the old engine the scene's root WAS the window's
        // root and took the window's size; here the DOCUMENT is the root and this is an
        // ordinary child, which sizes to its content -- so without this the scene lays out
        // at nothing and draws nothing. DEFAULT origin, so a scene sheet still wins.
        StyleGroup.defaultPipeline(sceneRoot.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f));
        this.document.append(sceneRoot);
        this.document.styles().addStylesheet(StyleSheet.DEFAULT);
        this.document.styles().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        // Demo-only rules for the overlay-size matrix — deliberately NOT in ore.css, which is the
        // shippable theme.
        this.document.styles().addStylesheet(StyleSheet.parse(FIT_DEMO_STYLES));
    }

    private static final String FIT_DEMO_STYLES = """
            .fit-demo {
                background: asset("crystalgui:ore", "checkbox-box-green");
                overlay: asset("crystalgui:ore", "checkbox-check");
            }
            .fit-fill    { overlay-size: fill; }
            .fit-none    { overlay-size: none; }
            .fit-contain { overlay-size: contain; }
            .fit-cover   { overlay-size: cover; }

            /* SDF stroke outlines — no texture involved. The ring is a CgUiRoundedRect with a
               transparent fill, so it follows border-radius for free. */
            .ring-demo { background-color: #3C3C50; }
            .ring-square  { outline: 1px #4488FF; }
            .ring-offset  { outline: 1px #4488FF; outline-offset: 3px; }
            .ring-rounded { border-radius: 6px; outline: 1px #4488FF; }
            .ring-thick   { border-radius: 6px; outline: 3px #FFAA00; outline-offset: 2px; }
            """;

    private UIElement createDemo() {
        // Plain outer container purely for screen margin. Deliberately does NOT set padding/gap on
        // the panel itself — Java .layout(...) writes at INLINE origin, which outranks stylesheets,
        // so anything set here would suppress the theme's own .panel rules.
        UIElement root = new UIElement()
                .layout(l -> l.paddingAll(10).flexDirection(FlexDirection.ROW).gapAll(10))
                .setFocusPolicy(FocusPolicy.NONE);

        // ── Interactive panel ──
        // Only width is set in Java; background/padding/gap all come from `.panel` in ore.css.
        // Default flex-direction COLUMN + align-items STRETCH make children fill the panel width,
        // which is exactly how LDLib2's own demo gets its full-width button.
        UIElement panel = new UIElement().layout(l -> l.width(108));
        panel.addClass("panel");
        root.append(panel);

        demoButton = new Button("Button");
        demoButton.attachListener(() -> clickCount++);
        panel.append(demoButton);

        Checkbox disableToggle = new Checkbox("Toggle");
        disableToggle.attachListener(isChecked -> demoButton.setEnabled(!isChecked));
        panel.append(disableToggle);

        // ── Forced-state matrix ──
        // Every visual state driven programmatically (see forceStates()), so hover/pressed/checked
        // can be verified from a static screenshot without a live cursor. This is what proves the
        // descendant re-match fix: the mark is styled entirely by `checkbox:<state> .__mark__`
        // rules keyed on the ROOT's state, so if ancestor-state changes didn't invalidate the
        // child's cached match, every checkbox below would render identically.
        // gap set in Java (INLINE origin) rather than a CSS class: a `.dense` class would tie with
        // `.panel` on specificity (both single-class) and lose. This panel has a lot of rows and
        // needs to stay on-screen.
        UIElement states = new UIElement().layout(l -> l.width(108).gapAll(1));
        states.addClass("panel");
        root.append(states);

        hoverButton = new Button("Btn hover");
        pressedButton = new Button("Btn pressed");
        focusButton = new Button("Btn focus");
        Button disabledButton = new Button("Btn disabled");
        disabledButton.setEnabled(false);
        states.append(new Button("Btn default"), hoverButton, pressedButton, focusButton, disabledButton);

        hoverBox = new Checkbox("hover");
        pressedBox = new Checkbox("pressed");
        focusBox = new Checkbox("focus");
        checkedHoverBox = new Checkbox("checked+hover");
        checkedHoverBox.setChecked(true);
        Checkbox checkedBox = new Checkbox("checked");
        checkedBox.setChecked(true);
        Checkbox disabledBox = new Checkbox("disabled");
        disabledBox.setEnabled(false);
        states.append(new Checkbox("default"), hoverBox, pressedBox, focusBox,
                checkedBox, checkedHoverBox, disabledBox);

        // ── overlay-size matrix ──
        // The same 10x10 check sprite as an `overlay:` on four deliberately oversized, non-square
        // 40x24 boxes, one per fit mode. Proves the general feature rather than just the checkbox's
        // use of it: `fill` distorts to the box, `none` stays 10x10, `contain` fits inside keeping
        // aspect, `cover` overflows keeping aspect. All are centered (overlay-position default).
        UIElement fits = new UIElement().layout(l -> l.width(86));
        fits.addClass("panel");
        root.append(fits);
        for (String mode : new String[]{"fill", "none", "contain", "cover"}) {
            UIElement demo = new UIElement().layout(l -> l.width(34).height(20));
            demo.addClass("fit-demo");
            demo.addClass("fit-" + mode);
            fits.append(demo);
        }

        // ── SDF outline-stroke matrix ──
        // Pure `outline: <width> <color>` — no texture. Generous gaps so offset rings (which draw
        // OUTSIDE the element box) don't overlap their neighbours.
        UIElement rings = new UIElement().layout(l -> l.width(70).gapAll(9));
        rings.addClass("panel");
        root.append(rings);
        for (String variant : new String[]{"square", "offset", "rounded", "thick"}) {
            UIElement demo = new UIElement().layout(l -> l.width(30).height(16));
            demo.addClass("ring-demo");
            demo.addClass("ring-" + variant);
            rings.append(demo);
        }

        return root;
    }

    /** Re-asserted every frame before paintFrame() so the style engine sees them during
     * calculateStyle, and so ordinary hover bookkeeping can't clear them. */
    private void forceStates() {
        hoverButton.setHovered(true);
        pressedButton.setPressed(true);
        focusButton.setFocused(true);
        hoverBox.setHovered(true);
        pressedBox.setPressed(true);
        focusBox.setFocused(true);
        checkedHoverBox.setHovered(true);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        forceStates();
        document.frame(frame.getDeltaTime(), ctx.getScreenWidth() / SCALE, ctx.getScreenHeight() / SCALE);

        // AND THE PAINT. `paintFrame()` did both; `frame()` only advances, so a scene that
        // lost this half advanced perfectly and drew nothing.
        CgUiPaintContext paintContext = CgUiPaintContext.getInstance();
        paintContext.beginFrame(ctx.getScreenWidth(), ctx.getScreenHeight());
        document.paint(paintContext);
        paintContext.endFrame();

        var context = CgUiPaintContext.getInstance();
        String status = String.format("clicks: %d | button enabled: %s", clickCount, demoButton.isEnabled());
        context.text().draw().at(0, 0).text(status).font(context.getFont().atSize(16)).submit();

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
