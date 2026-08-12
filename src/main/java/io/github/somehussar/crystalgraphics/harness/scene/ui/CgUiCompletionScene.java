package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.text.Change;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.decoration.TrackedRange;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.Versioned;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.editor.CompletionSession;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

import java.util.List;
import java.util.function.Consumer;

/**
 * M8 and M9 on screen at once: a live completion popup over text carrying tracked squiggles.
 *
 * <h3>Scripted, not interactive — and that is the point</h3>
 *
 * <p>Both features only exist in a transient state. A squiggle that has drifted off its word looks exactly
 * like one that has not unless you saw the edit, and a completion popup is gone the moment you look away
 * from the keyboard. So this scene <b>performs</b> the interaction on a fixed frame schedule and captures
 * the result, which means the artifact is comparable between runs rather than a screenshot of whatever the
 * hands happened to be doing.</p>
 *
 * <p>It also prints what it did. A capture proves a popup was drawn; the log proves it was drawn with the
 * right rows in the right order, which no amount of looking at a 340px-wide list can settle.</p>
 *
 * <h3>KNOWN DEFECT, not this scene's and not yet fixed</h3>
 *
 * <p>A {@code TextEditor} built standalone here has a <b>NaN scroll offset</b> from its first layout. Every
 * view part computes a line's top as {@code origin + line * lineHeight - scrollTop}, so all nine rows
 * resolve to the same y and paint on top of each other — which is what the capture shows. It survives an
 * explicit {@code setScrollImmediate(0, 0)} and survives making the editor the window root, so something
 * recomputes it during layout; {@code getMaxScrollTop} reading a viewport that has not been measured is the
 * likeliest source.</p>
 *
 * <p>It is <b>not</b> a completion or diagnostics fault and it does not affect the dock scene, where the
 * same widget renders correctly under {@code CrystalEditor}. It does mean the capture is a faithful proof of
 * the <em>popup</em> and only a partial one of the text beneath it — which is why the log above carries the
 * tracked ranges as text, where the stacking cannot hide them. {@code TextEditor.updateCompletionAnchor}
 * defends against the NaN at its own seam so the popup is placed correctly regardless; that defence is
 * deliberately local and is not a fix for the underlying offset.</p>
 *
 * <h3>A stub provider, deliberately, even though the real engine exists</h3>
 *
 * <p>{@code JavaLanguage} is registered in this harness and would answer for real. It is not used here
 * because the engine's answer depends on which band loaded and what is on the classpath — so a visual
 * baseline built on it would change for reasons that have nothing to do with the popup. The rows below are
 * chosen to exercise the drawing: every kind colour, a deprecated row, a long detail that must right-align,
 * and a scattered match whose banding is not a contiguous run.</p>
 */
public class CgUiCompletionScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard,
        CgSystemInput.Mouse {

    private static final String SOURCE = """
            class Demo {
                void run() {
                    var printer = new Printer();
                    printer.pr
                    undefinedName();
                }
            }
            """;

    /** The rows the popup is shown with — one per kind, so every palette entry is on screen at once. */
    private static final List<CompletionItem> ITEMS = List.of(
            CompletionItem.builder("println", SymbolKind.METHOD)
                    .detail("void").filterText("println").build(),
            CompletionItem.builder("printf", SymbolKind.METHOD)
                    .detail("PrintStream").filterText("printf").build(),
            CompletionItem.builder("print", SymbolKind.METHOD)
                    .detail("void").filterText("print").build(),
            CompletionItem.builder("prefix", SymbolKind.FIELD)
                    .detail("java.lang.String").filterText("prefix").build(),
            CompletionItem.builder("precision", SymbolKind.LOCAL_VARIABLE)
                    .detail("int").filterText("precision").build(),
            CompletionItem.builder("PRECISION_LIMIT", SymbolKind.CONSTANT)
                    .detail("int").filterText("PRECISION_LIMIT").build(),
            CompletionItem.builder("Printer", SymbolKind.CLASS)
                    .detail("com.example.output").filterText("Printer").build(),
            CompletionItem.builder("Printable", SymbolKind.INTERFACE)
                    .detail("java.awt.print").filterText("Printable").build(),
            CompletionItem.builder("Priority", SymbolKind.ENUM)
                    .detail("com.example").filterText("Priority").build(),
            // Deprecated, so the strike-through is in the baseline rather than only in a unit test.
            CompletionItem.builder("printStackTraceOld", SymbolKind.METHOD)
                    .detail("void").filterText("printStackTraceOld").deprecated(true).build(),
            // A scattered hit: "prn" reaches this only through the subsequence tier, so its banding is
            // three separate marks -- which is the case a contiguous highlighter draws wrongly.
            CompletionItem.builder("parseRelativeName", SymbolKind.METHOD)
                    .detail("java.nio.file.Path").filterText("parseRelativeName").build());

    private UIWindow uiWindow;
    private TextEditor editor;
    private boolean logged;

    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(true);

        editor = new TextEditor(SOURCE);
        editor.setLanguage(Language.JAVA);
        editor.layout(l -> l.width(560).height(260).marginLeft(40).marginTop(40));
        // An explicit face, so the capture shows where the editor IS as well as what it drew. Without it a
        // widget that laid out correctly and painted nothing is indistinguishable from one that was never
        // added -- which is exactly the ambiguity the first capture of this scene left.
        editor.generalStyle(g -> g.backgroundColor(0xFF1E1E1E));
        // AN EXPLICIT ZERO SCROLL. Without it this editor's scroll offset is NaN from the first frame, and
        // since every view part computes a line's top as `origin + line * height - scrollTop`, all nine
        // rows resolve to the same y and paint on top of each other. See the note in the scene javadoc --
        // the defect is the editor's, not this scene's, and this is a workaround rather than a fix.
        editor.setScrollImmediate(0f, 0f);

        // THE EDITOR IS THE ROOT, as it is in the dock scene. Wrapped in a sized parent instead, its scroll
        // offset resolves to NaN on the first layout and every line's top becomes `origin + n*height - NaN`
        // -- so all nine rows painted at the same y, stacked on top of each other. The editor is the only
        // configuration proven to lay out here, and a harness scene is the wrong place to be discovering
        // that; see the note at the top of this file.
        uiWindow = new UIWindow(Ui.of(editor));
        uiWindow.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgUiPaintContextWarmup.ensure();
        uiWindow.init(ctx.getScreenWidth(), ctx.getScreenHeight());

        long number = frame.getFrameNumber();
        // FRAME 2, not 0: the first frame is where layout settles, and a diagnostic installed before the
        // editor has a box resolves its offsets against a document nothing has measured.
        if (number == 2) installDiagnostics();
        if (number == 4) editForcingTheSquigglesToTrack();
        if (number == 6) openCompletionAtTheCaret();

        uiWindow.paintFrame();

        if (number == 8 && !logged) {
            logged = true;
            report();
            ctx.getArtifactService().requestCapture("completion-and-squiggles");
        }
    }

    /** Two problems, one of them on the line the edit below will push down. */
    private void installDiagnostics() {
        int undefinedRow = rowOf("undefinedName");
        editor.diagnostics().setAll(List.of(
                Diagnostic.error(new TextPoint(undefinedRow, 8), new TextPoint(undefinedRow, 21),
                        "undefinedName cannot be resolved"),
                Diagnostic.warning(new TextPoint(rowOf("var printer"), 12),
                        new TextPoint(rowOf("var printer"), 19), "printer is never read")));
    }

    /**
     * Inserts a line ABOVE both marks.
     *
     * <p>This is the whole of M8 in one gesture. Before tracked ranges the squiggles stayed at their old
     * offsets and ended up under whatever text moved into them — and it corrected itself on the next
     * compile, which is why it read as the analyser lagging rather than as a broken mark.</p>
     */
    private void editForcingTheSquigglesToTrack() {
        int at = editor.buffer().toString().indexOf("    void run()");
        editor.buffer().insert(at, "    // a line inserted above every mark\n");
    }

    /** Puts the caret after {@code printer.pr} and opens a session there. */
    private void openCompletionAtTheCaret() {
        int caret = editor.buffer().toString().indexOf("printer.pr") + "printer.pr".length();
        editor.setCaret(caret);
        editor.setLanguageServices(new StubServices());
        editor.openCompletion(CompletionProvider.TriggerKind.EXPLICIT, null);
    }

    /**
     * What is actually on screen, in text.
     *
     * <p>The capture says a popup was drawn. This says which rows, in what order, and where the tracked
     * marks ended up — the questions a 340px-wide list of identifiers cannot answer by being looked at.</p>
     */
    private void report() {
        // GEOMETRY FIRST. The capture showed a correct popup over an empty editor, which is a layout
        // question and not a completion one -- and a screenshot cannot distinguish "the text is not drawn"
        // from "the text is drawn somewhere off the capture".
        System.out.println("=== geometry ==============================================");
        System.out.printf("   window       %dx%d logical (the harness surface is twice that at uiScale 2)%n",
                (int) uiWindow.getScreenWidth(), (int) uiWindow.getScreenHeight());
        System.out.printf("   editor box   %.1fx%.1f%n",
                editor.getRuntimeCache().getWidth(), editor.getRuntimeCache().getHeight());
        System.out.printf("   rows         %d in the document, line height %.1f%n",
                editor.buffer().lineCount(), editor.lineHeight());
        org.joml.Vector3f origin = editor.getRuntimeCache().localToWorld.get()
                .transformPosition(new org.joml.Vector3f(0f, 0f, 0f));
        System.out.printf("   editor world (%.1f, %.1f) -- surface pixels, so uiScale is already in it%n",
                origin.x, origin.y);

        System.out.println();
        System.out.println("=== M8 — tracked diagnostic ranges =========================");
        for (TrackedRange range : editor.buffer().decorations()
                .inLane(TextEditor.DIAGNOSTIC_LANE)) {
            Diagnostic problem = range.payload(Diagnostic.class);
            String covered = editor.buffer().document()
                    .slice(range.from(), Math.min(range.to(), editor.buffer().length())).toString();
            System.out.printf("   %-9s reported row %-2d  now [%d,%d) covering %-16s %s%n",
                    problem == null ? "?" : problem.severity(),
                    problem == null ? -1 : problem.start().row(),
                    range.from(), range.to(), "\"" + covered + "\"",
                    covered.equals(expectedFor(problem)) ? "OK" : "!! DRIFTED");
        }

        System.out.println();
        System.out.println("=== M9 — completion popup =================================");
        CompletionSession session = editor.completionSession();
        if (session == null) {
            System.out.println("   !! no session -- nothing was drawn");
            return;
        }
        System.out.printf("   prefix %-8s rows %d%n", "\"" + session.prefix() + "\"",
                session.visibleRows().size());
        if (editor.completionPopup() != null) {
            System.out.printf("   anchor       (%.1f, %.1f) logical -- should be the caret's line, not the "
                            + "editor's corner%n",
                    editor.completionPopup().anchorX(), editor.completionPopup().anchorY());
        }
        int index = 0;
        for (CompletionSession.Row row : session.visibleRows()) {
            CompletionItem item = row.item();
            System.out.printf("   %s %-2d %-20s %-24s %-12s %s%n",
                    index == session.selectedIndex() ? ">" : " ", index,
                    item.label(), item.detail() == null ? "" : item.detail(),
                    item.kind(), row.match() == null ? "" : "banded " + row.match().ranges());
            index++;
        }
    }

    private String expectedFor(Diagnostic problem) {
        if (problem == null) return "";
        return problem.message().startsWith("undefinedName") ? "undefinedName" : "printer";
    }

    private int rowOf(String needle) {
        String text = editor.buffer().toString();
        int at = text.indexOf(needle);
        return at < 0 ? 0 : editor.buffer().offsetToPoint(at).row();
    }

    /** Answers the fixed list above. See the class note on why this is not the real engine. */
    private static final class StubServices implements com.crystalgui.text.lang.LanguageServices {

        @Override
        public String id() {
            return "harness-stub";
        }

        @Override
        public CompletionProvider completion() {
            return new CompletionProvider() {
                @Override
                public void complete(Request request, Consumer<Versioned<CompletionList>> answer) {
                    answer.accept(Versioned.of(0, CompletionList.complete(ITEMS)));
                }

                @Override
                public void resolveItem(CompletionItem item, Consumer<CompletionItem> answer) {
                    answer.accept(item);
                }
            };
        }

        @Override
        public void close() {
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
        return uiWindow.getInputHandler().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return uiWindow.getInputHandler().consumeMouseEvent(event);
    }

    /** Kept out of the render body so the reason is stated once. */
    private static final class CgUiPaintContextWarmup {
        static void ensure() {
            // Nothing to do -- CgUiPaintContext builds itself lazily on the first paint, and touching it
            // here would defeat the laziness CgUiLifecycle deliberately relies on. The method exists so
            // the next person does not add an eager init believing one is missing.
        }
    }
}
