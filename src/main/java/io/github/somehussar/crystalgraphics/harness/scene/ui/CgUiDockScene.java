package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.fs.CgPath;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.chrome.ProblemsPanel;
import com.crystalgui.ui.elements.editor.EditorCommands;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.tree.TreeRenderer;
import com.crystalgui.ui.elements.tree.TreeRow;
import com.crystalgui.ui.elements.tree.TreeView;
import com.crystalgui.ui.elements.chrome.ChromeCommands;
import com.crystalgui.ui.elements.dock.DockArea;
import com.crystalgui.ui.elements.dock.DockBranch;
import com.crystalgui.ui.elements.dock.DockCommands;
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockGroup;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLayoutCodec;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPanelRegistry;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * The docking workspace — drag tabs between panes, split, reorder, save and restore.
 *
 * <h3>What to do in here</h3>
 * <ul>
 *   <li><b>Drag a tab onto the middle of another pane</b> — it joins that pane's strip. This is the
 *       most-used drop in the whole system and the one an edge-zones-only implementation forgets.</li>
 *   <li><b>Drag a tab near a pane's edge</b> — it splits. The preview covers the half it would take.</li>
 *   <li><b>Drag a tab within its own strip</b> — a caret shows where it lands; it reorders, never splits.</li>
 *   <li><b>Drag to the very edge of the whole area</b> — a full-height column beside everything, which is
 *       the one thing VS Code's per-group targets cannot express.</li>
 *   <li><b>Click a file in Project</b> — it opens in <b>its own tab</b>, named after the file, loaded
 *       over the real RPC from a real
 *       directory on disk ({@code workspace/} beside the harness). Clicking it again focuses that tab
 *       rather than opening a second. <b>Ctrl+S writes the ACTIVE tab back.</b> Edit the
 *       same file in another editor first and the server refuses the stale write.</li>
 *   <li><b>Ctrl+Shift+S / Ctrl+O</b> — serialise the pane arrangement and restore it, through the real
 *       codec. Moved off Ctrl+S once there were real files to save.</li>
 *   <li><b>Ctrl+\ , Ctrl+W, Ctrl+M, Ctrl+K</b> — split, close, maximize, cycle groups, via commands.</li>
 *   <li><b>F2 / F8</b> — jump between the four problems seeded into main.glsl; Shift for backwards.
 *       Squiggles under the text, marks in the scrollbar groove, counts in the top-right corner.</li>
 *   <li><b>Click a row in Problems</b> — the caret lands on that line in the editor.</li>
 *   <li><b>Ctrl+Shift+P</b> — the command palette, over the very commands above. Type to filter, arrows
 *       to move, Enter to run, Escape or a click outside to dismiss.</li>
 * </ul>
 *
 * <p>The palette lives here rather than in a scene of its own because it is only interesting over a real
 * command set: a palette listing two toy commands demonstrates a list box. What it has to get right —
 * that a dock command is offered at all, and that it acts on the group you were in rather than on the
 * palette's own search field — is only observable when there is a focused context to lose.</p>
 *
 * <p>Panels are deliberately trivial coloured boxes: what is being exercised is the layout tree and the
 * drop geometry, and a scene full of real editors would make a broken split look like a broken editor.</p>
 */
public class CgUiDockScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    private static final String STYLES = """
            /* Room for the harness's own status line, which is painted at y=0 over everything. */
            .demo-root { width: 100%; height: 100%; padding-all: 8px; padding-top: 22px; }
            .panel-body { flex-grow: 1; padding-all: 6px; }
            .p-graph  { background-color: #2F4858; }
            .p-code   { background-color: #33475B; }
            .p-nodes  { background-color: #3F3552; }
            .p-props  { background-color: #4A3B2E; }
            .p-console{ background-color: #2E3F2E; }
            .panel-editor { flex-grow: 1; font-size: 7px; }
            .panel-files { flex-grow: 1; background-color: #252526; padding-all: 4px; font-size: 7px; }
            .file-row { height: 12px; padding-left: 2px; }
            .file-row:hover { background-color: #2A2D2E; }
            """;

    private UIWindow uiWindow;
    private DockArea area;
    private DockPanelRegistry<UIElement> registry;

    /** The last saved arrangement, as the codec produced it. Null until Ctrl+S. */
    private Object saved;
    private String note = "drag a tab to begin";

    /** One-shot: the scene hands focus to the dock once there is a group to hand it to. */
    private boolean initialFocusGiven;

    /** Built once and reused: a dock rebuild must never replace the element it is showing, and the editor
     * carries the diagnostics the Problems panel is bound to. */
    private TextEditor editor;
    private ProblemsPanel problems;

    /** Both halves of a real workspace, in this process. */
    private final HarnessWorkspace workspace = new HarnessWorkspace();
    private TreeView<CgPath> fileTree;

    /** Which item each pooled tree row currently shows -- read per event, never captured. */
    private final java.util.Map<UIElement, CgPath> fileRowItems = new java.util.HashMap<>();

    /** One editor per open file, so a dock rebuild hands back the same widget and its unsaved edits. */
    private final java.util.Map<CgPath, TextEditor> fileEditors = new java.util.HashMap<>();


    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(true);

        registry = new DockPanelRegistry<>();
        registry.register(DockPanelDescriptor.document("graph", "Shader Graph"), ref -> body("p-graph"));
        // A REAL editor, carrying real diagnostics. The other panels are coloured boxes on purpose -- what
        // they exercise is the layout tree, and a broken split among real widgets reads as a broken widget.
        // This one is the exception because squiggles, the error stripe and the inspection widget have no
        // meaning without text under them, and until now they existed only inside assertions.
        registry.register(DockPanelDescriptor.document("code", "main.glsl"), ref -> editorPanel());
        // THE REAL FILE TREE, over the real RPC. Not a mock and not a local file API: the client here
        // holds a WorkspaceClient and nothing else, so clicking a file is a genuine round trip through
        // WorkspaceService -- the same path a Minecraft client would take to a dedicated server.
        registry.register(DockPanelDescriptor.singleton("files", "Project"), ref -> filesPanel());
        // A DOCUMENT type, so one registration serves every file: the panel ref carries the path in its
        // state and TITLE overrides the tab name per instance.
        registry.register(DockPanelDescriptor.document("file", "File"), this::fileEditorPanel);
        registry.register(DockPanelDescriptor.singleton("props", "Inspector"), ref -> body("p-props"));
        registry.register(DockPanelDescriptor.singleton("problems", "Problems"), ref -> problemsPanel());
        registry.register(DockPanelDescriptor.singleton("console", "Console"), ref -> body("p-console"));

        area = new DockArea(registry, defaultLayout());

        UIElement root = new UIElement()
                .layout(l -> l.flexDirection(FlexDirection.COLUMN))
                .setFocusPolicy(FocusPolicy.NONE);
        root.addClass("demo-root");
        root.addChild(area);

        uiWindow = new UIWindow(Ui.of(root));
        uiWindow.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        uiWindow.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        uiWindow.getStyleEngine().addStylesheet(StyleSheet.parse(STYLES));
        DockCommands.install(uiWindow);
        ChromeCommands.install(uiWindow);
        // Built here rather than waiting for the dock's first rebuild to ask for it, because the bindings
        // below need the element to hang a keymap on.
        editorPanel();
        // Bound on the EDITOR, not the window: editor keys are scoped to the widget, so F2 only navigates
        // problems while the editor has focus and does nothing while you are in the node library. That is
        // also what puts them in the palette only when the editor is the active context.
        EditorCommands.install(uiWindow, editor);
    }

    /**
     * The arrangement an IDE opens on: a library down the left, the work area in the middle with two
     * documents sharing a strip, an inspector on the right, and a console beneath.
     *
     * <p>The work area is the <b>central</b> leaf — it cannot be closed or absorbed, which is what stops
     * the layout being reducible to nothing but tool panels.</p>
     */
    private DockLayout defaultLayout() {
        DockLeaf centre = new DockLeaf(new DockPanelRef("graph"), new DockPanelRef("code"));
        centre.setCentral(true);
        DockLayout layout = DockLayout.of(centre);

        layout.drop(centre, DockDropZone.SPLIT_LEFT, new DockLeaf(new DockPanelRef("files")));
        layout.drop(centre, DockDropZone.SPLIT_RIGHT, new DockLeaf(new DockPanelRef("props")));
        // Problems shares the bottom strip with the console, which is where both live in every IDE that
        // has them -- and it means the panel and the editor it reports on are on screen together, which is
        // the only arrangement in which "click a row, land on the line" is worth watching.
        layout.drop(centre, DockDropZone.SPLIT_DOWN,
                new DockLeaf(new DockPanelRef("problems"), new DockPanelRef("console")));

        // Weights, explicitly -- and worth understanding rather than copying.
        //
        // A split halves the TARGET's share and gives the other half to the newcomer, which is right (every
        // OTHER pane keeps the proportion the user gave it) and means three splits off the same centre pane
        // leave it at an eighth. Insertion order then decides the picture: the first thing split off is the
        // biggest, so building an IDE layout in the obvious order hands half the screen to the node library.
        //
        // The lesson is that a DEFAULT layout is authored, not accumulated. A user's layout comes out of
        // their drags and needs no help; a starting one has to state what it wants.
        layout.root().child(0).size(0.20f);   // node library
        layout.root().child(1).size(0.62f);   // the work column
        layout.root().child(2).size(0.18f);   // inspector

        DockBranch workColumn = (DockBranch) layout.root().child(1);
        workColumn.child(0).size(0.75f);      // documents
        workColumn.child(1).size(0.25f);      // console
        return layout;
    }

    /**
     * The project tree, over the live workspace.
     *
     * <p>A single click on a directory expands it; a single click on a file loads it into the editor. That
     * is one gesture rather than the workspace scene's double-click-to-open, because here there is exactly
     * one editor pane to load into — a selection that does not open anything would have nowhere to show
     * itself.</p>
     */
    private UIElement filesPanel() {
        if (fileTree != null) return fileTree;
        fileTree = new TreeView<>(workspace.tree());
        fileTree.addClass("panel-files");
        fileTree.setRenderer(new TreeRenderer<CgPath>() {
            @Override
            public UIElement createTemplate() {
                UIElement row = new UIElement();
                row.addClass("file-row");
                UIText label = new UIText("");
                // The label refuses the click so the press lands on the row. Click targeting takes the
                // exact element hit and never walks up to a handler-bearing ancestor, which is why every
                // composite in this engine does this.
                label.setHitTest(false);
                row.addChild(label);
                // THE ROW'S ITEM IS READ, NEVER CAPTURED. Templates are pooled and rebound as the list
                // scrolls, and a listener may only be attached once -- capturing here would freeze this
                // row on whatever it first displayed and keep working until somebody scrolled.
                row.onMouseDown.attachListener((el, event) -> {
                    CgPath item = fileRowItems.get(row);
                    if (item != null) activateFile(item);
                }, false, false);
                return row;
            }

            @Override
            public void bind(CgPath item, TreeRow<CgPath> row, int index, UIElement template) {
                fileRowItems.put(template, item);
                String name = item.isProjectRoot() ? workspace.tree().displayNameOf(item) : item.name();
                ((UIText) template.getChildren().get(0)).setText("  ".repeat(row.depth())
                        + (row.expandable() ? (row.expanded() ? "- " : "+ ") : "   ") + name);
            }

            @Override
            public void unbind(UIElement template) {
                fileRowItems.remove(template);
            }
        });
        return fileTree;
    }

    /**
     * A panel reference identifying one open file.
     *
     * <p>{@code path} is what makes two file panels distinct — {@code DockPanelRef} compares on typeId
     * <em>and</em> state, so this is also what lets "is it already open?" be an equality check rather than
     * a search for something that looks similar. {@code TITLE} overrides the descriptor's title, which is
     * how one {@code document} type produces a differently-named tab per file.</p>
     */
    private static DockPanelRef refFor(CgPath path) {
        return new DockPanelRef("file")
                .withState("path", path.toString())
                .withState(DockPanelRef.TITLE, path.name());
    }

    private void activateFile(CgPath path) {
        if (workspace.tree().isDirectory(path)) {
            fileTree.setExpanded(path, !fileTree.isExpanded(path));
            fileTree.refresh();
            return;
        }
        DockPanelRef ref = refFor(path);

        // Already open: focus its tab rather than opening a second one. Every editor does this, and
        // without it a file clicked twice becomes two tabs that can disagree with each other.
        for (DockLeaf leaf : area.layout().leaves()) {
            if (leaf.indexOf(ref) < 0) continue;
            leaf.activate(ref);
            // syncGroups, NOT requestRebuild: only the selection changed, and this runs inside the tree
            // row.s own mouse-down -- rebuilding would detach the element being clicked.
            area.syncGroups();
            area.setActiveGroup(area.groupFor(leaf));
            note = "focused " + path.name();
            return;
        }

        // READ FIRST, add the tab second. A tab created before the content arrives is a tab that stays
        // empty when the read fails -- and the failure has nowhere to go except a status line nobody was
        // looking at, so the user is left with a blank editor and no reason for it.
        workspace.read(path, document -> {
            fileEditorFor(path).setText(document.text());
            DockLeaf target = centralLeaf();
            target.add(ref);
            target.activate(ref);
            area.requestRebuild();
            area.setActiveGroup(area.groupFor(target));
            note = "opened " + path.name();
        }, code -> note = "open failed: " + code);
    }

    /** Where a newly opened file goes. The central leaf is the work area by definition — it cannot be
     * closed or absorbed — so it is the one pane guaranteed to still exist to open into. */
    private DockLeaf centralLeaf() {
        for (DockLeaf leaf : area.layout().leaves()) {
            if (leaf.isCentral()) return leaf;
        }
        return area.layout().leaves().get(0);
    }

    /**
     * The editor showing {@code path}, created on first use.
     *
     * <p>Cached per path, which is what makes the dock's rebuilds harmless: {@code DockArea} asks the
     * registry for a panel's content whenever it rebuilds, and handing back a <em>new</em> editor each
     * time would discard the user's unsaved edits on every split, drag or close.</p>
     */
    private TextEditor fileEditorFor(CgPath path) {
        return fileEditors.computeIfAbsent(path, p -> {
            TextEditor created = new TextEditor("");
            created.addClass("panel-editor");
            if (uiWindow != null) EditorCommands.install(uiWindow, created);
            return created;
        });
    }

    /** Built by the dock when it needs a file panel — including after a layout restore, where the read
     * has not happened yet, so the content arrives late into an editor that already exists. */
    private UIElement fileEditorPanel(DockPanelRef ref) {
        CgPath path = CgPath.parse(ref.state("path", ""));
        TextEditor target = fileEditorFor(path);
        if (target.getText().isEmpty()) {
            workspace.read(path, document -> target.setText(document.text()),
                    code -> note = "open failed: " + code);
        }
        return target;
    }

    /**
     * The file behind the active tab, or null when the active tab is not a file.
     *
     * <p>Derived from the dock rather than remembered in a field: with a tab per file, "which file does
     * Ctrl+S write?" has exactly one correct answer and it is whichever tab you are looking at. A
     * remembered path would save the last file <em>opened</em>, which is the wrong one the moment you
     * switch tabs — and silently, since it would report success.</p>
     */
    private CgPath activeFilePath() {
        DockGroup group = area.activeGroup();
        if (group == null) return null;
        DockPanelRef panel = group.leaf().activePanel();
        if (panel == null || !"file".equals(panel.typeId())) return null;
        return CgPath.parse(panel.state("path", ""));
    }

    private void saveOpenFile() {
        CgPath target = activeFilePath();
        if (target == null) {
            note = "no file tab active — click one in Project";
            return;
        }
        TextEditor source = fileEditors.get(target);
        if (source == null) {
            note = "no editor for " + target.name();
            return;
        }
        workspace.save(target, source.getText(),
                () -> note = "saved " + target.name(),
                conflict -> note = conflict
                        ? "CONFLICT: " + target.name() + " changed on disk — reopen to take theirs"
                        : "save failed: " + target.name());
    }

    /** Nonsense GLSL, chosen so the diagnostics below have somewhere plausible to point. */
    private static final String SHADER_SOURCE = String.join("\n",
            "#version 330 core",
            "",
            "in vec2 v_uv;",
            "out vec4 fragColor;",
            "",
            "uniform sampler2D _MainTex;",
            "uniform vec4 _Color;",
            "",
            "void main() {",
            "    vec4 base = texture(_MainTex, v_uv);",
            "    vec3 tint = base.rgb * cg_ShadowParams.xyz;",
            "    float a = base.a * _Color.a",
            "    fragColor = vec4(tint, a);",
            "}");

    /**
     * The editor, pre-loaded with problems.
     *
     * <p>Hand-written rather than compiled, because there is no compiler wired up yet — that is C7, and it
     * is the one item blocked on what the host actually exposes. The shape of what a driver reports is
     * already known though: the error on row 11 is the real message GLSL gives for an undeclared name, in
     * the real format ({@code 0(278) : error C1503}), which is what the eventual adapter has to parse.</p>
     */
    private UIElement editorPanel() {
        if (editor == null) {
            editor = new TextEditor(SHADER_SOURCE);
            editor.addClass("panel-editor");
            // Rows are 0-BASED and were off by one on three of these four, which is worth leaving a note
            // about because the symptom was so misleading: the squiggles all rendered, all in plausible
            // places, one line below the text they described -- and because a column past the end of a
            // row clamps to its end, they landed under whatever happened to finish that line. It read as
            // a rendering bug in the squiggles rather than as bad input to them.
            //
            //  0 #version 330 core          5 uniform sampler2D _MainTex;   10     vec3 tint = ... cg_ShadowParams
            //  2 in vec2 v_uv;              8 void main() {                 11     float a = ... _Color.a   <- no ';'
            editor.diagnostics().setAll(java.util.List.of(
                    new Diagnostic(new TextPoint(10, 27), new TextPoint(10, 42),
                            DiagnosticSeverity.ERROR,
                            "error C1503: undefined variable \"cg_ShadowParams\"", "glsl", "C1503"),
                    new Diagnostic(new TextPoint(11, 30), new TextPoint(11, 31),
                            DiagnosticSeverity.ERROR,
                            "error C0000: syntax error, unexpected identifier, expecting ';'",
                            "glsl", "C0000"),
                    new Diagnostic(new TextPoint(5, 18), new TextPoint(5, 26),
                            DiagnosticSeverity.WARNING,
                            "uniform '_MainTex' is declared but never read", "glsl", null),
                    new Diagnostic(new TextPoint(2, 8), new TextPoint(2, 12),
                            DiagnosticSeverity.INFORMATION,
                            "'v_uv' could be flat-qualified", "glsl", null)));
        }
        return editor;
    }

    /**
     * The Problems panel, bound to the editor's set.
     *
     * <p>Choosing a row navigates <b>here</b>, not in the panel: the panel reports a choice and nothing
     * else, because in a real workspace the problem may be in a file that is not open and opening it is a
     * workspace-level act. This scene has exactly one document, so the wiring is one line — which is the
     * point of the split rather than an argument against it.</p>
     */
    private UIElement problemsPanel() {
        if (problems == null) {
            problems = new ProblemsPanel();
            problems.bindTo(editorPanel() instanceof TextEditor ? editor.diagnostics() : null);
            problems.onProblemChosen.connect(diagnostic -> {
                if (editor == null) return;
                editor.setCaret(editor.buffer().pointToOffset(diagnostic.start()));
                uiWindow.getInputHandler().requestFocus(editor);
                note = "jumped to line " + (diagnostic.start().row() + 1);
            });
        }
        return problems;
    }

    private UIElement body(String cssClass) {
        UIElement element = new UIElement();
        element.addClass("panel-body");
        element.addClass(cssClass);
        return element;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // ONE NETWORK TICK, before anything reads the tree. In a real client this is the network tick;
        // the scene does it explicitly so the asynchrony stays visible rather than pretended away.
        if (workspace.pump(frame.getDeltaTime(), () -> { if (fileTree != null) fileTree.refresh(); })
                && fileTree != null) {
            fileTree.refresh();
        }

        uiWindow.init(ctx.getScreenWidth(), ctx.getScreenHeight());
        uiWindow.paintFrame();
        giveInitialFocus();

        var context = CgUiPaintContext.getInstance();
        context.text().draw().at(0, 14)
                .text(diagnose())
                .font(context.getFont().atSize(12)).submit();
        context.text().draw().at(0, 0)
                .text(String.format("Dock — %d panes.  Ctrl+S save file · Ctrl+Shift+P palette · "
                                + "Ctrl+Shift+S save layout · Ctrl+O restore · Ctrl+\\ split · F2 next problem   [%s]",
                        area.layout().leaves().size(), note))
                .font(context.getFont().atSize(14)).submit();

        if (frame.getFrameNumber() == 5) {
            ctx.getArtifactService().requestCapture("startup");
        }
    }

    /**
     * Focuses the active group once the first rebuild has produced one.
     *
     * <p>An application decides where focus starts; an IDE opens with its editor focused. Without this the
     * window opens with focus <b>null</b>, and every command whose {@code enabledWhen} walks up the tree
     * from the focused element is unavailable — so the command palette opened dimmed almost end to end and
     * looked broken. That is a real property of an unfocused window rather than a harness quirk, which is
     * why the palette now <em>lists</em> unavailable commands rather than hiding them; this just stops the
     * scene sitting in the degenerate state from the first frame.</p>
     *
     * <p>{@code requestPointerFocus}, not {@code requestFocus}: the latter is PROGRAMMATIC and therefore
     * rings, so the scene would open with a focus outline nobody asked for.</p>
     */
    private void giveInitialFocus() {
        if (initialFocusGiven) return;
        DockGroup group = area.activeGroup();
        if (group == null) return;
        if (uiWindow.getInputHandler().getFocusedElement() == null) {
            uiWindow.getInputHandler().requestPointerFocus(group);
        }
        initialFocusGiven = true;
    }

    /**
     * Reports the view against the model, every frame.
     *
     * <p>Here rather than in a test because the failure has not reproduced headlessly: a two-group drag
     * and a drag in this exact layout both come out clean, so whatever differs is something only the real
     * loop does. This says what is actually true on screen instead of what a screenshot suggests.</p>
     */
    /**
     * Every tab painting as selected, with the group it is in and the state it reports.
     *
     * <p>Here because a headless probe cannot see it: the model is provably correct — one selected tab per
     * group, always the right one, across the reported flow, with both stylesheets loaded — and yet several
     * tabs render green at once on screen. So this reads what is <b>drawn</b> alongside what is
     * <b>believed</b>, and prints the pair. If {@code checked} and {@code padTop} disagree, the cascade has
     * gone stale; if they agree, the green is not {@code tab:checked} at all and the theme is the suspect.</p>
     *
     * <p>Ore's {@code tab:checked} raises the label with a padding shift (3/5 becomes 5/3), so
     * {@code padTop} is a proxy for what the sheet actually applied, read from the resolved layout rather
     * than from the flag the code sets.</p>
     */
    private String tabStates() {
        StringBuilder out = new StringBuilder();
        for (var leaf : area.layout().leaves()) {
            DockGroup group = area.groupFor(leaf);
            if (group == null) continue;
            java.util.List<com.crystalgui.ui.elements.Tab> tabs = new java.util.ArrayList<>();
            collectTabs(group, tabs);
            for (com.crystalgui.ui.elements.Tab tab : tabs) {
                // THE RESOLVED BACKGROUND, not the flag the widget sets. Checked and unchecked resolve to
                // different drawable instances (ore swaps tab-on for tab-off), so its identity is what the
                // cascade actually decided -- the one thing a readout must not take from the same source
                // as the code it is checking.
                Object background = tab.getStyle().getGeneralGroup().background();
                String skin = background == null ? "none"
                        : Integer.toHexString(System.identityHashCode(background));
                skin = skin.substring(Math.max(0, skin.length() - 3));
                // EVERY pseudo-class that could be painting it, because only one tab can be :checked and
                // only one element can hold focus -- so several tabs looking selected at once means the
                // green is coming from something else, and this says which.
                out.append(' ').append(tab.getText()).append('[')
                        .append(tab.isChecked() ? "chk" : "---")
                        .append(tab.isHovered() ? "+hov" : "")
                        .append(tab.isPressed() ? "+act" : "")
                        .append(tab.isFocused() ? "+foc" : "")
                        .append(tab.isFocusVisible() ? "+ring" : "")
                        .append(':').append(skin).append(']');
            }
        }
        return out.toString();
    }

    private static void collectTabs(UIElement element,
                                    java.util.List<com.crystalgui.ui.elements.Tab> out) {
        if (element instanceof com.crystalgui.ui.elements.Tab tab) out.add(tab);
        for (UIElement child : element.getChildren()) collectTabs(child, out);
    }

    private String diagnose() {
        int leaves = area.layout().leaves().size();
        int attached = countGroups(area);
        StringBuilder mismatches = new StringBuilder();
        for (var leaf : area.layout().leaves()) {
            var group = area.groupFor(leaf);
            if (group == null) {
                mismatches.append(" [no group]");
                continue;
            }
            // Tab ELEMENTS, not getTabCount(). The first version of this readout trusted the tab list
            // and printed "strips OK" while three dead tabs sat in the rails -- markAsInternal() recurses,
            // so removeChild had been silently refusing them and the list stayed correct throughout.
            int tabs = countTabs(group);
            if (tabs != leaf.panelCount()) {
                mismatches.append(String.format(" [%s: %d tabs vs %d panels]",
                        leaf.panelCount() > 0 ? leaf.panel(0).typeId() : "empty", tabs, leaf.panelCount()));
            }
        }
        return String.format("groups attached=%d leaves=%d%s", attached, leaves,
                mismatches.length() == 0 ? "  strips OK" : "  MISMATCH" + mismatches)
                + "  sel:" + tabStates();
    }

    private static int countGroups(UIElement element) {
        int count = element instanceof com.crystalgui.ui.elements.dock.DockGroup ? 1 : 0;
        for (UIElement child : element.getChildren()) count += countGroups(child);
        return count;
    }

    private static int countTabs(UIElement element) {
        int count = element instanceof com.crystalgui.ui.elements.Tab ? 1 : 0;
        for (UIElement child : element.getChildren()) count += countTabs(child);
        return count;
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
            boolean ctrl = org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LCONTROL)
                    || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RCONTROL);
            boolean shift = org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LSHIFT)
                    || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RSHIFT);
            // Ctrl+S SAVES THE FILE; the layout moved to Ctrl+Shift+S. Now that there are real files on
            // disk behind this scene, Ctrl+S has one obvious meaning and it is not "serialise the pane
            // arrangement" -- a key that writes the wrong thing is worse than one that does nothing, and
            // the layout is the demo of the two.
            if (ctrl && !shift && event.key() == org.lwjgl.input.Keyboard.KEY_S) {
                saveOpenFile();
                return true;
            }
            if (ctrl && shift && event.key() == org.lwjgl.input.Keyboard.KEY_S) {
                // Read the user's divider positions back out first, or the save records the weights the
                // layout was BUILT with rather than the ones on screen.
                area.pullWeightsIntoLayout();
                saved = DockLayoutCodec.encode(area.layout(), PlainOps.INSTANCE,
                        uiWindow.getScreenWidth(), uiWindow.getScreenHeight());
                note = "saved layout";
                return true;
            }
            if (ctrl && event.key() == org.lwjgl.input.Keyboard.KEY_O) {
                if (saved == null) {
                    note = "nothing saved yet";
                    return true;
                }
                DockLayout restored = DockLayoutCodec.decode(saved, PlainOps.INSTANCE, registry);
                if (restored == null) {
                    // The codec's honest answer, and a normal outcome rather than an error path.
                    area.setLayout(defaultLayout());
                    note = "blob refused — default layout";
                } else {
                    area.setLayout(restored);
                    note = "restored";
                }
                return true;
            }
        }
        return uiWindow.getInputHandler().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return uiWindow.getInputHandler().consumeMouseEvent(event);
    }
}
