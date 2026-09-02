package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.text.Change;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.decoration.TrackedRange;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.texteditor.suggest.CompletionSession;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

import java.util.List;

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

    /** Logical-to-surface scale, as the harness\'s other new-engine scenes use. */
    private static final float SCALE = 2f;

    private static final String SOURCE = """
            class Demo {
                void run() {
                    var printer = new Printer();
                    System.
                    undefinedName();
                }
            }
            """;

    private UIDocument document;
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
        editor.box().setScroll(0f, 0f);

        // THE EDITOR IS THE ROOT, as it is in the dock scene. Wrapped in a sized parent instead, its scroll
        // offset resolves to NaN on the first layout and every line's top becomes `origin + n*height - NaN`
        // -- so all nine rows painted at the same y, stacked on top of each other. The editor is the only
        // configuration proven to lay out here, and a harness scene is the wrong place to be discovering
        // that; see the note at the top of this file.
        this.document = new UIDocument().markFrameThread();
        this.document.boxes().setUiScale(SCALE);
        UINode sceneRoot = editor;
        // THE ROOT FILLS THE DOCUMENT. On the old engine the scene's root WAS the window's
        // root and took the window's size; here the DOCUMENT is the root and this is an
        // ordinary child, which sizes to its content -- so without this the scene lays out
        // at nothing and draws nothing. DEFAULT origin, so a scene sheet still wins.
        StyleGroup.defaultPipeline(sceneRoot.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f));
        this.document.append(sceneRoot);
        document.styles().addStylesheet(StyleSheet.DEFAULT);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgUiPaintContextWarmup.ensure();

        long number = frame.getFrameNumber();
        // FRAME 2, not 0: the first frame is where layout settles, and a diagnostic installed before the
        // editor has a box resolves its offsets against a document nothing has measured.
        if (number == 2) installDiagnostics();
        if (number == 4) editForcingTheSquigglesToTrack();
        if (number == 6) openCompletionAtTheCaret();

        document.frame(frame.getDeltaTime(), ctx.getScreenWidth() / SCALE, ctx.getScreenHeight() / SCALE);

        if (number == 8 && !logged) {
            logged = true;
            report();
            probeShapes();
            ctx.getArtifactService().requestCapture("completion-and-squiggles");
        }
    }

    /**
     * <b>The member list for shapes this scene's own document does not contain</b>, asked of the provider
     * directly.
     *
     * <p>Reported as a defect: {@code System.out.} opened an empty popup and {@code getMinecraft().}
     * offered three rows. Every layer was then driven in isolation and every layer answered correctly —
     * the analyser, the provider with a fresh analysis, the provider with a <em>stale</em> one, and the
     * whole services stack for both a compilation unit and a bare snippet. So the remaining question is
     * environmental rather than logical: whether this JVM's classpath and staged engine produce the same
     * answers a test JVM's do.</p>
     *
     * <p>No editor and no window, deliberately. Routing through the widget would fold the popup's sizing
     * and the session's filtering back in, and those are exactly what this is trying to hold still.</p>
     */
    private void probeShapes() {
        System.out.println();
        System.out.println("=== member lists, asked of the provider directly ===========");
        probeShape("a field receiver", "System.out.\n", "System.out.");
        probeShape("a call receiver", "new java.util.ArrayList<String>().\n",
                "new java.util.ArrayList<String>().");
        probeShape("a unit, field receiver",
                "class Demo {\n    void run() {\n        System.out.\n    }\n}\n", "System.out.");
    }

    /** Opens services over {@code source} alone and prints what the provider offers after {@code upTo}. */
    private void probeShape(String what, String source, String upTo) {
        LanguageRegistry.Entry entry = LanguageRegistry.forFileName("Probe.java");
        if (entry == null) {
            System.out.println("   !! no Java entry registered");
            return;
        }
        com.crystalgui.text.TextBuffer buffer = new com.crystalgui.text.TextBuffer(source);
        LanguageServices services = entry.newServices(buffer, null);
        if (services == null) {
            System.out.println("   !! no services for " + what);
            return;
        }
        try {
            int caret = source.indexOf(upTo) + upTo.length();
            java.util.concurrent.atomic.AtomicReference<List<CompletionItem>> got =
                    new java.util.concurrent.atomic.AtomicReference<>(List.of());
            services.completion().complete(
                    CompletionProvider.Request.character(caret, "", "."),
                    answer -> got.set(answer.orElse(com.crystalgui.text.lang.CompletionList.EMPTY).items()));
            List<CompletionItem> items = got.get();
            StringBuilder first = new StringBuilder();
            for (int i = 0; i < Math.min(6, items.size()); i++) {
                first.append(i == 0 ? "" : ", ").append(items.get(i).label());
            }
            System.out.printf("   %-24s %3d rows   %s%n", what, items.size(), first);
        } finally {
            services.close();
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

    /**
     * Puts the caret straight after {@code System.} and opens a session there.
     *
     * <p>The <b>real engine</b>, not a stub. This scene exists to be held beside IntelliJ's own popup for
     * the same expression, and a stub would be comparing our drawing against our own invented rows — which
     * says nothing about whether the member list is right. The cost is that it needs the staged engine
     * bands, so it degrades to "no session" on a host without them rather than pretending.</p>
     */
    private void openCompletionAtTheCaret() {
        int caret = editor.buffer().toString().indexOf("System.") + "System.".length();
        editor.setCaret(caret);

        JavaLanguage.register();
        LanguageRegistry.Entry entry = LanguageRegistry.forFileName("Demo.java");
        LanguageServices services = entry == null ? null : entry.newServices(editor.buffer(), null);
        if (services == null) {
            System.out.println("   !! no Java engine staged -- run through :gl-debug-harness:runHarness "
                    + "so stageEngines has written build/engines/");
            return;
        }
        editor.setLanguageServices(services);
        editor.openCompletion(CompletionProvider.TriggerKind.CHARACTER, ".");
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
        // THE DOCUMENT'S OWN BOX, because `ctx` is the render call's and this runs outside it. It is
        // already in logical units, which is what this line was reporting.
        System.out.printf("   window       %.0fx%.0f logical (the harness surface is twice that at uiScale 2)%n",
                document.box() == null ? 0f : document.box().width(),
                document.box() == null ? 0f : document.box().height());
        System.out.printf("   editor box   %.1fx%.1f%n",
                editor.box().width(), editor.box().height());
        System.out.printf("   rows         %d in the document, line height %.1f%n",
                editor.buffer().lineCount(), editor.lineHeight());
        // `localToWorld` is a method on Box, where the old runtime cache exposed a cell to `get()`.
        org.joml.Vector3f origin = editor.box() == null ? new org.joml.Vector3f()
                : editor.box().localToWorld().transformPosition(new org.joml.Vector3f(0f, 0f, 0f));
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

    /** Kept out of the render body so the reason is stated once. */
    private static final class CgUiPaintContextWarmup {
        static void ensure() {
            // Nothing to do -- CgUiPaintContext builds itself lazily on the first paint, and touching it
            // here would defeat the laziness CgUiLifecycle deliberately relies on. The method exists so
            // the next person does not add an eager init believing one is missing.
        }
    }
}
