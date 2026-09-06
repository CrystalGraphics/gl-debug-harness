package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.core.property.Property;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;


/**
 * Exercises {@code TextField} — type, select, copy/paste, and the validation layers.
 *
 * <p>Click a field to focus it, then type. Tab moves between them, which also proves the
 * Space-activation fix: space reaches a field as a character while the button below still activates
 * on space.</p>
 *
 * <p>The number and filtered rows are the interesting ones: typing a bare {@code -} into the number
 * field leaves it {@code :invalid} but still editable (you have to be able to get to {@code -5}), and
 * the letters row simply refuses non-digit keystrokes outright.</p>
 */
public class CgUiTextFieldScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** Logical-to-surface scale, as the harness\'s other new-engine scenes use. */
    private static final float SCALE = 2f;

    private UIDocument document;
    private TextField plain;
    /** Focused and unselected so the caret is actually visible — see where it is built. */
    private TextField caretShowcase;
    private TextField placeholder;
    private TextField number;
    private TextField digitsOnly;
    private TextField signed;
    private TextField decimal;
    private TextField bound;
    private TextField mirror;
    private TextField immediate;

    /** Shared model behind the two bound fields — the thing `bindValueBidirectional` binds to. */
    private final Property<String> model = new Property<>("shared");

    private static final String STYLES = """
            .demo-root { width: 320px; height: 230px; flex-direction: column; gap-all: 4px; }
            .row       { flex-direction: row; align-items: center; gap-all: 6px; }
            .slot      { width: 96px; }
            .field     { width: 178px; }
            """;

    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(true);

        // The real system clipboard comes from the harness's InputAdapter (AWT-backed), so Ctrl+X/C/V
        // in this scene exercise the same path a loader would provide.
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
        this.document.styles().addStylesheet(StyleSheet.DEFAULT);
        this.document.styles().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        this.document.styles().addStylesheet(StyleSheet.parse(STYLES));
    }

    private UIElement createDemo() {
        UIElement root = new UIElement()
                .layout(l -> l.width(320).height(230)
                        .paddingAll(10).flexDirection(FlexDirection.COLUMN).gapAll(4))
                .setFocusPolicy(FocusPolicy.NONE);
        root.addClass("panel");
        root.addClass("demo-root");

        // ── The two text-cursor states, both forced ──────────────────────────────────────────────
        // Only ONE element can hold focus through real input; both rows below are focused anyway, the
        // same forced state matrix CgUiOreThemeScene uses for its hover/active/focus rows. This scene
        // is the only place in the harness a selection band or a caret is visible at all, which is why
        // both being 2px too tall went unnoticed for so long.

        plain = new TextField();
        plain.setText("edit me");
        // Pre-selected so the startup capture shows the selection highlight — that fill is
        // `selection-color`. It must ALSO be focused: the band is painted only while focused, so an
        // unfocused pre-selected field renders nothing and this demo would silently vanish.
        plain.selectAll();
        plain.setFocused(true);
        root.append(row("plain", plain));

        // The caret's counterpart. Three things are all required:
        //   * focused        — the caret only paints while focused
        //   * NO selection   — the guard is `isFocused() && !hasSelection()`, so the row above can
        //                      never show a caret and this one can never show a selection
        //   * blink disabled — otherwise the capture catches whichever half of the 0.53s cycle it
        //                      lands in, and the row is only in the screenshot half the time
        // Height is the font's ascender + descender, NOT the line box — 10 logical px for
        // MinecraftRegular at size 10 (8 + 2), where the full line box is 12 because it also carries
        // 2px of lineGap. That gap is leading between lines, and including it was what left both the
        // caret and the selection hanging into the field sprite's bottom bevel.
        caretShowcase = new TextField();
        caretShowcase.setText("caret");
        caretShowcase.setCaretBlinkSeconds(0f);
        caretShowcase.setFocused(true);
        root.append(row("caret", caretShowcase));

        placeholder = new TextField();
        placeholder.setPlaceholder("type something…");
        root.append(row("placeholder", placeholder));

        // Invalid until it parses AND fits the range — try "500". '-' is unreachable here because a
        // 0..100 range can never be negative, so the mode's keystroke filter drops it.
        number = new TextField();
        number.setMode(TextField.Mode.INTEGER).setRange(0, 100).setText("42");
        root.append(row("int 0..100", number));

        // ...whereas a range that CAN go negative admits '-'. Wheel steps by 5.
        signed = new TextField();
        signed.setMode(TextField.Mode.INTEGER).setRange(-50, 50).setStep(5).setText("-10");
        root.append(row("int ±50, step5", signed));

        // Decimal point and exponent, both typable. Wheel steps by 0.25.
        decimal = new TextField();
        decimal.setMode(TextField.Mode.DOUBLE).setRange(-10, 10).setStep(0.25).setText("1.5");
        root.append(row("double ±10", decimal));

        // Rejects non-digits at the keystroke, so nothing invalid can even be typed.
        digitsOnly = new TextField();
        digitsOnly.setCharPattern("[0-9]");
        root.append(row("digits only", digitsOnly));

        // The two update modes, side by side. `mirror` is bound to the same Property as `bound`, so
        // typing in one and pressing Enter (or tabbing away) fills the other; `immediate` publishes
        // on every keystroke instead, which the label below tracks live.
        bound = new TextField();
        bound.bindValueBidirectional(model);
        root.append(row("bound (Enter)", bound));

        mirror = new TextField();
        mirror.bindValueBidirectional(model);
        root.append(row("…mirrors it", mirror));

        immediate = new TextField();
        immediate.setUpdateMode(TextField.UpdateMode.IMMEDIATE);
        root.append(row("immediate", immediate));

        return root;
    }

    private UIElement row(String label, UIElement widget) {
        UIElement row = new UIElement();
        row.addClass("row");
        UIElement slot = new UIElement();
        slot.addClass("slot");
        UIText t = new UIText(label);
        t.addClass("label");
        slot.append(t);
        row.append(slot);
        widget.addClass("field");
        row.append(widget);
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
        context.text().draw().at(0, 0)
                .text(String.format("TextField — click to focus, Tab to move, Enter commits, Esc reverts."))
                .font(context.getFont().atSize(14)).submit();
        context.text().draw().at(0, 18)
                .text(String.format("int=%s%s   model=%s   immediate=%s",
                        number.getText(), number.isInvalid() ? " (invalid)" : "",
                        model.get(), immediate.getValue()))
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
