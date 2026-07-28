package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.input.SystemInput;
import com.crystalgui.core.input.UIClipboard;
import com.crystalgui.core.property.Property;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;

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
public class CgUiTextFieldScene implements InteractiveSceneLifecycle, SystemInput.Keyboard, SystemInput.Mouse {

    private UIWindow uiWindow;
    private TextField plain;
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

        // Real system clipboard, via AWT. LWJGL2's Sys.getClipboard() can only read; AWT is the one
        // that can also write, which Ctrl+X/C need.
        CrystalGuiCore.setClipboard(new UIClipboard() {
            @Override
            public String get() {
                try {
                    var contents = Toolkit.getDefaultToolkit().getSystemClipboard()
                            .getContents(null);
                    if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        return (String) contents.getTransferData(DataFlavor.stringFlavor);
                    }
                } catch (Exception ignored) {
                    // Clipboard access can fail for reasons entirely outside our control (another app
                    // owning it, a headless/locked session). An empty read is the right degradation.
                }
                return "";
            }

            @Override
            public void set(String text) {
                try {
                    Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new StringSelection(text), null);
                } catch (Exception ignored) {
                }
            }
        });

        this.uiWindow = new UIWindow(Ui.of(createDemo()));
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheet.parse(STYLES));
    }

    private UIElement createDemo() {
        UIElement root = new UIElement()
                .layout(l -> l.width(320).height(230)
                        .paddingAll(10).flexDirection(FlexDirection.COLUMN).gapAll(4))
                .setFocusPolicy(FocusPolicy.NONE);
        root.addClass("panel");
        root.addClass("demo-root");

        plain = new TextField();
        plain.setText("edit me");
        root.addChild(row("plain", plain));

        placeholder = new TextField();
        placeholder.setPlaceholder("type something…");
        root.addChild(row("placeholder", placeholder));

        // Invalid until it parses AND fits the range — try "500". '-' is unreachable here because a
        // 0..100 range can never be negative, so the mode's keystroke filter drops it.
        number = new TextField();
        number.setMode(TextField.Mode.INTEGER).setRange(0, 100).setText("42");
        root.addChild(row("int 0..100", number));

        // ...whereas a range that CAN go negative admits '-'. Wheel steps by 5.
        signed = new TextField();
        signed.setMode(TextField.Mode.INTEGER).setRange(-50, 50).setStep(5).setText("-10");
        root.addChild(row("int ±50, step5", signed));

        // Decimal point and exponent, both typable. Wheel steps by 0.25.
        decimal = new TextField();
        decimal.setMode(TextField.Mode.DOUBLE).setRange(-10, 10).setStep(0.25).setText("1.5");
        root.addChild(row("double ±10", decimal));

        // Rejects non-digits at the keystroke, so nothing invalid can even be typed.
        digitsOnly = new TextField();
        digitsOnly.setCharPattern("[0-9]");
        root.addChild(row("digits only", digitsOnly));

        // The two update modes, side by side. `mirror` is bound to the same Property as `bound`, so
        // typing in one and pressing Enter (or tabbing away) fills the other; `immediate` publishes
        // on every keystroke instead, which the label below tracks live.
        bound = new TextField();
        bound.bindValueBidirectional(model);
        root.addChild(row("bound (Enter)", bound));

        mirror = new TextField();
        mirror.bindValueBidirectional(model);
        root.addChild(row("…mirrors it", mirror));

        immediate = new TextField();
        immediate.setUpdateMode(TextField.UpdateMode.IMMEDIATE);
        root.addChild(row("immediate", immediate));

        return root;
    }

    private UIElement row(String label, UIElement widget) {
        UIElement row = new UIElement();
        row.addClass("row");
        UIElement slot = new UIElement();
        slot.addClass("slot");
        UIText t = new UIText(label);
        t.addClass("label");
        slot.addChild(t);
        row.addChild(slot);
        widget.addClass("field");
        row.addChild(widget);
        return row;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        uiWindow.init(ctx.getScreenWidth(), ctx.getScreenHeight());
        uiWindow.paintFrame();

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
        return uiWindow.getInputHandler().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(SystemInput.Mouse.Event event) {
        return uiWindow.getInputHandler().consumeMouseEvent(event);
    }
}
