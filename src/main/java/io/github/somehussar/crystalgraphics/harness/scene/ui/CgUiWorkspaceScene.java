package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIElementTreeSource;
import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.fs.CgFileEvent;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.LocalFileSystem;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.protocol.FsError;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.protocol.FsMethods;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.WorkspaceService;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.workbench.explorer.WorkspaceTreeSource;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P6.1.10 end to end: a remote project workspace, both halves in this process.
 *
 * <p>A server owns a real directory on disk; a client browses and edits it through the RPC protocol,
 * over {@code InMemoryTransport}. Nothing here shortcuts the protocol — the client has no reference to
 * the filesystem, only to a {@link Workspace}, exactly as a client across a Minecraft connection
 * would.</p>
 *
 * <h3>The scratch project is a REAL directory</h3>
 * <p>Written to {@code harness-output/workspace/} and seeded on first run. That is what makes the
 * conflict path demonstrable rather than described: edit one of these files in another editor, save, then
 * try to save here — the server refuses the stale write and the banner offers a reload. An in-memory
 * filesystem would have made this scene prove strictly less.</p>
 *
 * <h3>Why the tree is lazy and cached</h3>
 * <p>{@link TreeDataSource} is synchronous and {@link Workspace} is not, because one is a UI
 * contract and the other is a network round trip. {@link WorkspaceTree} bridges them the way every remote
 * file browser does: answer from what has arrived, request what has not, and refresh when it lands.</p>
 */
public class CgUiWorkspaceScene implements InteractiveSceneLifecycle,
        CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** Logical-to-surface scale, as the harness's other new-engine scenes use. */
    private static final float SCALE = 2f;

    private static final String PROJECT_ID = "harness.scratch";

    private UIDocument document;

    // ── The server half ─────────────────────────────────────────────────────────────────────────
    private ServerUiSession<UIElement, Object> server;
    private InMemoryTransport<Object> fromServer;
    private InMemoryTransport<Object> fromClient;

    // ── The client half ─────────────────────────────────────────────────────────────────────────
    private ClientUiSession<UIElement, Object> session;
    private Workspace workspace;
    private WorkspaceTreeSource tree;

    private TreeView<CgPath> treeView;
    private TabView tabs;
    private UIText status;
    private UIElement banner;
    private UIText bannerText;

    /** Open documents, by path, so a second click on a file focuses its tab rather than opening another. */
    private final Map<CgPath, Tab> openTabs = new LinkedHashMap<>();
    private final Map<CgPath, TextEditor> editors = new HashMap<>();

    /** What each document held when it was last loaded or saved — what makes "dirty" answerable. */
    private final Map<CgPath, String> baseline = new HashMap<>();

    /** Seconds until the next watcher poll. Files are stat'd, so this is not free. */
    private float untilPoll;

    /** Which item each pooled tree row currently shows — see the renderer for why it is not captured. */
    private final Map<UIElement, CgPath> rowItems = new HashMap<>();

    /** The path whose save was refused, awaiting Reload or Keep. */
    private CgPath conflicted;

    private String note = "ready";

    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(true);

        Path root = seedScratchProject(ctx);
        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject(PROJECT_ID, "Scratch", root)));

        // ALLOW_ALL, and worth being explicit about: there is no player here to guard against, and the
        // default is DENY_ALL precisely so that a real host has to make this choice on purpose.
        service = new WorkspaceService(
                registry, new LocalFileSystem(registry), WorkspacePermission.ALLOW_ALL);
        hub = new WatchHub(service);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        fromServer = pair[0];
        fromClient = pair[1];

        server = new ServerUiSession<>(1, new UIElementTreeSource(new UIElement()),
                new UIElementMirror<>(PlainOps.INSTANCE), fromServer, PlainOps.INSTANCE);
        binding = new WorkspaceBinding<>(service, hub, WorkspaceActor.LOCAL, PEER, PlainOps.INSTANCE);
        binding.installOn(server::onCall);
        server.open();

        session = new ClientUiSession<>(new UIElementMirror<>(PlainOps.INSTANCE), fromClient, PlainOps.INSTANCE);
        workspace = Workspace.over(session::call, session::onNotify, PlainOps.INSTANCE);
        // ONE RECURSIVE WATCH over the project, which is a real subscription over the wire -- so this
        // scene pays the same round trip a client in a Minecraft world does.
        workspace.watch(Resource.of(CgPath.ofProject(PROJECT_ID)), true).onChanged
                .connect(changes -> changes.forEach(this::onFileChangedOnServer));
        tree = new WorkspaceTreeSource(workspace);

        this.document = new UIDocument().markFrameThread();
        this.document.boxes().setUiScale(SCALE);
        UIElement sceneRoot = buildUi();
        // THE ROOT FILLS THE DOCUMENT. On the old engine the scene's root WAS the window's
        // root and took the window's size; here the DOCUMENT is the root and this is an
        // ordinary child, which sizes to its content -- so without this the scene lays out
        // at nothing and draws nothing. DEFAULT origin, so a scene sheet still wins.
        StyleGroup.defaultPipeline(sceneRoot.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f));
        this.document.append(sceneRoot);
        document.styles().addStylesheet(StyleSheet.DEFAULT);
        document.styles().addStylesheet(StyleSheetRegistry.of("harness:workspace"));

        // DELIBERATELY NOT loading projects here. The client's window id is -1 until OpenWindow
        // arrives, and the server discards any packet for another window -- so a call made now is thrown
        // away with no error at all. See WorkspaceSceneOrderTest. The first frame that has a window does
        // it instead.
    }

    /** True once the session is connected and the project list has been asked for. */
    private boolean projectsRequested;

    /** The one peer this process has. A hub keys its subscriptions by it, so it need only be stable. */
    private static final Object PEER = new Object();

    /** What each open file was when it was last read or written — what a conditional save quotes. */
    private final Map<CgPath, String> etags = new HashMap<>();

    private WorkspaceService service;
    private WatchHub hub;
    private WorkspaceBinding<Object> binding;

    private void notifyChanges(Map<Object, List<FsMessages.FileChange>> byPeer) {
        List<FsMessages.FileChange> mine = binding.changesFor(byPeer);
        if (mine.isEmpty()) return;
        server.call(FsMethods.CHANGED, new StateMap<>(PlainOps.INSTANCE,
                FsMessages.changedNotification().encode(PlainOps.INSTANCE,
                        new FsMessages.ChangedNotification(mine))), null, null);
    }

    /**
     * Creates {@code gl-debug-harness/workspace/} with a few files, once.
     *
     * <p>Never overwrites: the point of a real directory is that edits survive a restart, and a scene
     * that reset it every launch would make the conflict demo impossible to set up.</p>
     *
     * <p><b>Not under {@code harness-output/}</b>, which {@code gradle clean} deletes. Everything else in
     * there is a regenerable artifact; this is the user's own scratch project, and losing it to an
     * unrelated clean would be losing work.</p>
     */
    private static Path seedScratchProject(HarnessContext ctx) {
        Path root = Paths.get("workspace").toAbsolutePath().normalize();
        try {
            Files.createDirectories(root.resolve("src"));
            writeIfAbsent(root.resolve("README.md"),
                    "# Scratch project\n\nEdit these on disk, then save here to see a conflict.\n");
            writeIfAbsent(root.resolve("src/Main.java"),
                    "public class Main {\n    public static void main(String[] args) {\n"
                            + "        System.out.println(\"hello\");\n    }\n}\n");
            writeIfAbsent(root.resolve("src/notes.txt"), "one\ntwo\nthree\n");
        } catch (IOException e) {
            throw new IllegalStateException("could not create the scratch project at " + root, e);
        }
        return root;
    }

    private static void writeIfAbsent(Path file, String content) throws IOException {
        if (!Files.exists(file)) Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    // ── UI ──────────────────────────────────────────────────────────────────────────────────────

    private UIElement buildUi() {
        UIElement root = new UIElement();
        root.addClass("ws-root");

        UIElement head = new UIElement();
        head.addClass("ws-head");
        Button save = new Button("Save (Ctrl+S)");
        save.addClass("ws-btn");
        save.attachListener(this::saveActive);
        head.append(save);
        status = new UIText("");
        status.addClass("ws-status");
        head.append(status);
        root.append(head);

        banner = new UIElement();
        banner.addClass("ws-banner");
        bannerText = new UIText("");
        bannerText.addClass("ws-banner-text");
        banner.append(bannerText);
        Button reload = new Button("Load File System Changes");
        reload.addClass("ws-btn");
        reload.attachListener(this::reloadConflicted);
        banner.append(reload);
        Button keep = new Button("Keep Memory Changes");
        keep.addClass("ws-btn");
        keep.attachListener(this::keepConflicted);
        banner.append(keep);
        root.append(banner);
        showBanner(null);

        SplitView split = new SplitView();
        split.addClass("ws-split");
        split.setPercentage(20);

        treeView = new TreeView<>(tree);
        treeView.addClass("ws-tree");
        treeView.setRenderer(new com.crystalgui.widget.collection.tree.TreeRenderer<>() {
            @Override
            public UIElement createTemplate() {
                UIElement row = new UIElement();
                row.addClass("ws-row");
                UIText label = new UIText("");
                // The LABEL does not take the click, so the press lands on the row -- the same trick every
                // composite in this engine uses, since click targeting takes the exact element hit and
                // never walks up to a handler-bearing ancestor.
                label.setHitTest(false);
                row.append(label);
                // THE ROW'S ITEM IS READ, NEVER CAPTURED. Templates are pooled and rebound as the list
                // scrolls, and a listener may only be attached once -- capturing the item here would
                // freeze this row on whatever it first displayed, and keep working right up until
                // somebody scrolled. Same rule the editor's fold arrows carry.
                row.onMouseDown.attachListener((el, event) -> {
                    CgPath item = rowItems.get(row);
                    if (item != null) activateRow(item);
                }, false, false);
                return row;
            }

            @Override
            public void bind(CgPath item, TreeRow<CgPath> row, int index, UIElement template) {
                rowItems.put(template, item);
                String name = item.isProjectRoot() ? tree.displayNameOf(item) : item.name();
                ((UIText) template.children().get(0))
                        .setText("  ".repeat(row.depth())
                                + (row.expandable() ? (row.expanded() ? "- " : "+ ") : "   ") + name);
            }

            @Override
            public void unbind(UIElement template) {
                rowItems.remove(template);
            }
        });
        // ListView's signal, carrying an INDEX -- TreeView adds no item-level one, so the row is
        // resolved through rowAt(). Double-click activates; a single click only selects.
        // Enter on the focused row does the same thing a click does. ListView raises this from the
        // keyboard only -- a renderer is expected to raise it for the mouse, which is what the template's
        // listener above effectively does by calling activateRow directly.
        treeView.onRowActivated.connect(index -> {
            TreeRow<CgPath> row = treeView.rowAt(index);
            if (row != null) activateRow(row.item());
        });
        split.first().append(treeView);

        tabs = new TabView();
        tabs.addClass("ws-tabs");
        split.second().append(tabs);
        root.append(split);

        return root;
    }

    private void showBanner(String message) {
        if (message == null) {
            banner.style(s -> s.getLayoutGroup().display(dev.vfyjxf.taffy.style.TaffyDisplay.NONE));
            conflicted = null;
        } else {
            bannerText.setText(message);
            banner.style(s -> s.getLayoutGroup().display(dev.vfyjxf.taffy.style.TaffyDisplay.FLEX));
        }
    }

    // ── Opening, saving, conflicts ──────────────────────────────────────────────────────────────

    /**
     * A single click acts: directories expand, files open.
     *
     * <p>Not the desktop convention of select-on-single, act-on-double — there is no separate twisty
     * element here to click, so requiring a double-click would leave a tree whose only affordance does
     * nothing on the gesture everybody tries first. Which is exactly how this scene shipped.</p>
     */
    private void activateRow(CgPath path) {
        if (path == null) return;
        if (tree.isDirectory(path)) {
            treeView.toggleExpanded(path);
            note = (treeView.isExpanded(path) ? "expanded " : "collapsed ") + path;
            return;
        }
        openPath(path);
    }

    private void openPath(CgPath path) {
        Tab existing = openTabs.get(path);
        if (existing != null) {
            tabs.selectTab(existing);
            return;
        }
        workspace.files().read(Resource.of(path)).then(answer -> {
            String text = new String(answer.content(), StandardCharsets.UTF_8);
            TextEditor editor = new TextEditor(text);
            editor.addClass("ws-editor");
            Tab tab = tabs.addTab(path.name());
            tab.content().append(editor);
            tabs.selectTab(tab);
            openTabs.put(path, tab);
            editors.put(path, editor);
            baseline.put(path, text);
            etags.put(path, answer.etag());
            note = "opened " + path;
        }).onError(failure -> note = "open failed: " + failure.code());
    }

    private CgPath activePath() {
        Tab selected = tabs.getSelectedTab();
        for (Map.Entry<CgPath, Tab> entry : openTabs.entrySet()) {
            if (entry.getValue() == selected) return entry.getKey();
        }
        return null;
    }

    private void saveActive() {
        CgPath path = activePath();
        if (path == null) {
            note = "nothing open";
            return;
        }
        TextEditor editor = editors.get(path);
        // QUOTING THE ETAG THIS CLIENT LAST SAW, which is what makes a stale write a refusal rather than
        // a silent overwrite of somebody else's edit.
        workspace.files().write(Resource.of(path),
                        editor.getText().getBytes(StandardCharsets.UTF_8), etags.get(path))
                .then(etag -> {
                    baseline.put(path, editor.getText());
                    etags.put(path, etag);
                    note = "saved " + path;
                    showBanner(null);
                })
                .onError(failure -> {
                    if (failure instanceof FsError problem && problem.is(FsError.CONFLICT)) {
                        conflicted = path;
                        // THE ETAG THE FILE ACTUALLY HOLDS, so Keep can write unconditionally without
                        // a second round trip to find out what it is now.
                        etags.put(path, problem.actualEtag());
                        showBanner("Changes have been made to '" + path.name()
                                + "' in memory and on disk.");
                        note = "conflict on " + path;
                    } else {
                        note = "save failed: " + failure.code();
                    }
                });
    }

    /** Takes what is on disk, discarding the local edit — IntelliJ's first button. */
    private void reloadConflicted() {
        CgPath path = conflicted;
        if (path == null) return;
        reloadFromServer(path, "reloaded ");
    }

    /**
     * Keeps the local edit, overwriting what is on disk — IntelliJ's second button.
     *
     * <p>Writes with a <b>null</b> etag, which is what "unconditional" means: quoting the stale one is
     * what was just refused, and quoting the current one would be the same write dressed up as a
     * condition it already knows the answer to.</p>
     */
    private void keepConflicted() {
        CgPath path = conflicted;
        if (path == null) return;
        workspace.files().write(Resource.of(path),
                        editors.get(path).getText().getBytes(StandardCharsets.UTF_8), null)
                .then(etag -> {
                    baseline.put(path, editors.get(path).getText());
                    etags.put(path, etag);
                    showBanner(null);
                    note = "kept local changes to " + path;
                })
                .onError(failure -> note = "keep failed: " + failure.code());
    }

    private void reloadFromServer(CgPath path, String verb) {
        workspace.files().read(Resource.of(path)).then(answer -> {
            String text = new String(answer.content(), StandardCharsets.UTF_8);
            editors.get(path).setText(text);
            baseline.put(path, text);
            etags.put(path, answer.etag());
            showBanner(null);
            note = verb + path;
        }).onError(failure -> note = "reload failed: " + failure.code());
    }

    private boolean isDirty(CgPath path) {
        TextEditor editor = editors.get(path);
        return editor != null && !editor.getText().equals(baseline.get(path));
    }

    /**
     * The server says a file we have open moved.
     *
     * <p>VS Code's rule, and the reason {@code fs.changed} is worth having: a document with no unsaved
     * edits is <b>reloaded silently</b>, because prompting about a file the user has not touched is noise.
     * Only a dirty one raises the banner — which is the case where something would genuinely be lost.</p>
     */
    private void onFileChangedOnServer(FsMessages.FileChange change) {
        CgPath path = CgPath.parse(change.path());
        if (!editors.containsKey(path)) return;

        if (change.kind() == FsMessages.ChangeKind.DELETED) {
            conflicted = null;
            showBanner("'" + path.name() + "' has been deleted on disk.");
            note = "deleted on disk: " + path;
            return;
        }
        if (isDirty(path)) {
            conflicted = path;
            showBanner("Changes have been made to '" + path.name() + "' in memory and on disk.");
            note = "changed on disk: " + path;
            return;
        }
        reloadFromServer(path, "auto-reloaded ");
    }

    // ── Frame ───────────────────────────────────────────────────────────────────────────────────

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // THE TRANSPORT IS PUMPED HERE, once a frame. In a real client this is the network tick; the
        // scene does it explicitly so the asynchrony is visible rather than pretended away.
        fromServer.deliver();
        fromClient.deliver();
        session.tick();
        server.tick();

        // The transport is live from the first frame, so this is where the workspace is actually opened.
        if (!projectsRequested && session.windowId() >= 0) {
            projectsRequested = true;
            tree.loadProjects(() -> treeView.refresh());
        }
        // The watcher's cadence is the HOST's call, not the engine's -- every poll stats each open file,
        // so a per-frame poll would be a stat storm at 60 Hz for no benefit a human could perceive.
        untilPoll -= frame.getDeltaTime();
        if (untilPoll <= 0f) {
            untilPoll = 0.5f;
            // THE EVENTS FIRST, then the reconciling rescan. A watcher loses events by design when its
            // queue overflows, so the poll makes the answer eventually right rather than replacing it.
            List<CgFileEvent> events = service.drainFileEvents();
            if (!events.isEmpty()) notifyChanges(hub.tick(WorkspaceActor.LOCAL, events));
            notifyChanges(hub.poll(WorkspaceActor.LOCAL));
        }

        if (tree.drainRefresh()) treeView.refresh();

        String treeError = tree.failure();
        status.setText(treeError != null ? treeError : note);
        document.frame(frame.getDeltaTime(), ctx.getScreenWidth() / SCALE, ctx.getScreenHeight() / SCALE);

        // AND THE PAINT. `paintFrame()` did both; `frame()` only advances, so a scene that
        // lost this half advanced perfectly and drew nothing.
        CgUiPaintContext paintContext = CgUiPaintContext.getInstance();
        paintContext.beginFrame(ctx.getScreenWidth(), ctx.getScreenHeight());
        document.paint(paintContext);
        paintContext.endFrame();

        var context = CgUiPaintContext.getInstance();
        context.text().draw().at(0, 0)
                .text("workspace: " + PROJECT_ID + "  |  click a file to open, Ctrl+S to save")
                .font(context.getFont().atSize(14)).submit();

        if (frame.getFrameNumber() == 5) ctx.getArtifactService().requestCapture("startup");
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
        // Ctrl+S before the editor sees it, or 's' is typed into the document.
        if (event.pressed() && !event.repeat() && event.key() == org.lwjgl.input.Keyboard.KEY_S
                && (org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LCONTROL)
                 || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RCONTROL))) {
            saveActive();
            return false;
        }
        return document.input().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return document.input().consumeMouseEvent(event);
    }
}
