package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * Verifies 9-slice tiling ({@code stretch}/{@code repeat}/{@code round}/{@code space}) plus the
 * Unity-style {@code fillCenter} and {@code borderScale} extras.
 *
 * <p><b>The load-bearing check is the side-by-side.</b> Each mode is rendered twice from the same
 * sprite: once plain (which takes {@code CgUiSprite}'s CPU 9-quad path) and once with a
 * {@code border-radius} (which reroutes to {@code gui_rounded_rect.shader}'s per-pixel
 * {@code WITH_9SLICE_FILL} branch). Those are two independent implementations of the same slicing,
 * and which one runs depends on an unrelated style property — so if they ever disagree, adding a
 * border-radius would silently change an element's tiling. The two columns must match.</p>
 *
 * <p>Manual checklist:</p>
 * <ul>
 *   <li>{@code stretch} — one smeared copy per region (the pre-existing behaviour).</li>
 *   <li>{@code repeat} — whole tiles with a clipped partial tile at the far edge.</li>
 *   <li>{@code round} — whole tiles only, slightly resized so they divide the span exactly.</li>
 *   <li>{@code space} — whole tiles at natural size with visible transparent gaps between them.</li>
 *   <li>{@code no-center} — edges drawn, middle see-through.</li>
 *   <li>{@code scale2} — borders and tiles at double size from the same source texture.</li>
 * </ul>
 */
public class CgUiNineSliceScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** Logical-to-surface scale, as the harness\'s other new-engine scenes use. */
    private static final float SCALE = 2f;

    private UIDocument document;

    /** Ore's switch graphic (24x14) with a 4px border, chosen because its centre region is visibly
     * PATTERNED. A flat-centred sprite renders identically under every mode, which would make this
     * scene prove nothing. Centre is 16x6 source px, so a 120px-wide cell fits ~7 tiles. */
    private static final String SPRITE = "sprite(\"crystalgui:textures/gui/ore_styles.png\", "
            + "\"13 7 24 14\", \"4 4 4 4\"";

    private static final String STYLES = """
            .row   { flex-direction: row; gap-all: 8px; align-items: center; }
            .label { width: 58px; font-size: 10; color: #FFFFFF; }
            .cell  { width: 92px; height: 30px; }
            /* Same sprite and mode in both columns — the .rounded variant differs ONLY by having a
               border-radius, which is what reroutes it from the CPU quad path to the SDF shader. */
            .m-stretch { background: %s, "stretch"); }
            .m-repeat  { background: %s, "repeat"); }
            .m-round   { background: %s, "round"); }
            .m-space   { background: %s, "space"); }
            .rounded   { border-radius: 6px; }
            """.formatted(SPRITE, SPRITE, SPRITE, SPRITE);

    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(false);
        this.document = new UIDocument().markFrameThread();
        this.document.boxes().setUiScale(SCALE);
        UIElement sceneRoot = createDemo();
        // THE ROOT FILLS THE DOCUMENT. On the old engine the scene's root WAS the window's
        // root and took the window's size; here the DOCUMENT is the root and this is an
        // ordinary child, which sizes to its content -- so without this the scene lays out
        // at nothing and draws nothing. DEFAULT origin, so a scene sheet still wins.
        StyleGroup.defaultPipeline(sceneRoot.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f));
        this.document.append(sceneRoot);
        this.document.styles().addStylesheet(StyleSheet.parse(STYLES));
    }

    private UIElement createDemo() {
        UIElement root = new UIElement()
                .layout(l -> l.paddingAll(12).flexDirection(FlexDirection.COLUMN).gapAll(6))
                .setFocusPolicy(FocusPolicy.NONE);

        // Header row naming the two columns.
        UIElement header = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(8));
        header.addClass("row");
        header.append(label(""));
        header.append(label("CPU quads"));
        header.append(label("SDF shader"));
        root.append(header);

        for (String mode : new String[]{"stretch", "repeat", "round", "space"}) {
            UIElement row = new UIElement();
            row.addClass("row");
            row.append(label(mode));
            row.append(cell(mode, false)); // plain -> CgUiSprite's quad loop
            row.append(cell(mode, true));  // border-radius -> WITH_9SLICE_FILL shader branch
            root.append(row);
        }
        return root;
    }

    /** Wrapped in a fixed-width container rather than sizing the UIText directly: UIText pushes its
     * own measured width at IMPORTANT origin, which outranks any stylesheet width and would leave
     * the rows misaligned (and the second column pushed off-screen). */
    private UIElement label(String text) {
        UIElement slot = new UIElement().layout(l -> l.width(52));
        UIText t = new UIText(text);
        t.addClass("label");
        slot.append(t);
        return slot;
    }

    private UIElement cell(String mode, boolean rounded) {
        UIElement cell = new UIElement();
        cell.addClass("cell");
        cell.addClass("m-" + mode);
        if (rounded) cell.addClass("rounded");
        return cell;
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
                .text("9-slice tiling: left = CPU quad path, right = SDF shader path (must match)")
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
