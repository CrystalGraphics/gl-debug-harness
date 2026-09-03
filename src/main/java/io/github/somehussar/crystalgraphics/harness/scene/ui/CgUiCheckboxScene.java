package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.control.CheckboxGroup;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * Interactive harness scene exercising {@code Checkbox}/{@code CheckboxGroup}.
 *
 * <p>Manual verification checklist:</p>
 * <ul>
 *   <li>Click "Standalone A"/"Standalone B" anywhere in their hit region, including directly on the
 *       label text — each toggles independently, mark fades in/out via {@code :checked}.</li>
 *   <li>Click checkboxes in the "Group (allowEmpty)" cluster — checking one unchecks the previous;
 *       clicking the currently-checked one un-checks it (group allows empty).</li>
 *   <li>Click checkboxes in the "Group (required)" cluster — same exclusivity, but clicking the
 *       currently-checked one refuses to un-check it (group requires exactly one selected).</li>
 *   <li>Tab to any checkbox (focus ring via {@code :focus}), press Space (hold + release) or Enter —
 *       both toggle it exactly like a mouse click.</li>
 * </ul>
 */
public class CgUiCheckboxScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** Logical-to-surface scale, as the harness\'s other new-engine scenes use. */
    private static final float SCALE = 2f;

    private UIDocument document;
    private Checkbox standaloneA;
    private Checkbox standaloneB;
    private CheckboxGroup emptyAllowedGroup;
    private CheckboxGroup requiredGroup;

    private static final String STYLE_SHEET = """
            .card {
                background-color: #FF2A2A3A;
                border-width: 1px;
                border-color: #555555;
                padding-all: 8px;
                flex-direction: column;
                gap-all: 8px;
            }
            checkbox {
                background-color: #FF3C3C50;
                border-width: 1px;
                border-color: #777777;
                padding-all: 6px;
                gap-all: 6px;
                transition: border-color 250ms, border-width 250ms;
            }
            checkbox:hover {
                background-color: #FF50506A;
            }
            checkbox:active {
                background-color: #FF20202C;
            }
            checkbox:focus {
                border-color: #0000FF;
                border-width: 1.5px;
            }
            checkbox .__mark__ {
                width: 12px;
                height: 12px;
                background-color: #FF00FF00;
                opacity: 0;
                transition: opacity 100ms;
            }
            checkbox:checked .__mark__ {
                opacity: 1;
            }
            .label {
                color: #FFFFFF;
                font-size: 12;
            }
            """;

    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(false);

        UIElement root = createCheckboxDemo();
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
        this.document.styles().addStylesheet(StyleSheet.parse(STYLE_SHEET));
    }

    private UIElement createCheckboxDemo() {
        UIElement root = new UIElement()
                .layout(l -> l
                        .paddingAll(16)
                        .flexDirection(FlexDirection.ROW)
                        .gapAll(16)
                        .alignItems(AlignItems.FLEX_START))
                .setFocusPolicy(FocusPolicy.NONE);

        UIElement standaloneCard = new UIElement();
        standaloneCard.addClass("card");
        standaloneA = new Checkbox("Standalone A");
        standaloneB = new Checkbox("Standalone B");
        standaloneCard.append(standaloneA);
        standaloneCard.append(standaloneB);
        root.append(standaloneCard);

        emptyAllowedGroup = new CheckboxGroup().allowEmpty(true);
        UIElement emptyGroupCard = new UIElement();
        emptyGroupCard.addClass("card");
        for (String label : new String[]{"Option 1", "Option 2", "Option 3"}) {
            Checkbox cb = new Checkbox(label);
            cb.setGroup(emptyAllowedGroup);
            emptyGroupCard.append(cb);
        }
        root.append(emptyGroupCard);

        requiredGroup = new CheckboxGroup().allowEmpty(false);
        UIElement requiredGroupCard = new UIElement();
        requiredGroupCard.addClass("card");
        Checkbox first = null;
        for (String label : new String[]{"Choice A", "Choice B", "Choice C"}) {
            Checkbox cb = new Checkbox(label);
            if (first == null) first = cb;
            cb.setGroup(requiredGroup);
            requiredGroupCard.append(cb);
        }
        first.setChecked(true); // required group must start with exactly one checked
        root.append(requiredGroupCard);

        return root;
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
        String status = String.format(
                "A: %s | B: %s | allowEmpty current: %s | required current: %s",
                standaloneA.isChecked(), standaloneB.isChecked(),
                emptyAllowedGroup.getCurrent() == null ? "none" : emptyAllowedGroup.getCurrent().getLabel(),
                requiredGroup.getCurrent() == null ? "none" : requiredGroup.getCurrent().getLabel());
        context.text().draw().at(0, 0).text(status).font(context.getFont().atSize(16)).submit();

        // Startup capture so this scene contributes to the pixel-regression set. Without it the
        // scene runs but writes no artifact, and a "scenes diff to zero" check silently covers
        // nothing here.
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
