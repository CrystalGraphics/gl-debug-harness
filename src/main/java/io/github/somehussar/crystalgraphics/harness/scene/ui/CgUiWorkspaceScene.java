package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.fs.CgFileEntry;
import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.LocalFileSystem;
import com.crystalgui.fs.ProjectInfo;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspaceProject;
import com.crystalgui.fs.WorkspaceRpc;
import com.crystalgui.fs.WorkspaceService;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.SplitView;
import com.crystalgui.ui.elements.Tab;
import com.crystalgui.ui.elements.TabView;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.tree.TreeDataSource;
import com.crystalgui.ui.elements.tree.TreeRow;
import com.crystalgui.ui.elements.tree.TreeView;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P6.1.10 end to end: a remote project workspace, both halves in this process.
 *
 * <p>A server owns a real directory on disk; a client browses and edits it through the RPC protocol,
 * over {@code InMemoryTransport}. Nothing here shortcuts the protocol — the client has no reference to
 * the filesystem, only to a {@link WorkspaceClient}, exactly as a client across a Minecraft connection
 * would.</p>
 *
 * <h3>The scratch project is a REAL directory</h3>
 * <p>Written to {@code harness-output/workspace/} and seeded on first run. That is what makes the
 * conflict path demonstrable rather than described: edit one of these files in another editor, save, then
 * try to save here — the server refuses the stale write and the banner offers a reload. An in-memory
 * filesystem would have made this scene prove strictly less.</p>
 *
 * <h3>Why the tree is lazy and cached</h3>
 * <p>{@link TreeDataSource} is synchronous and {@link WorkspaceClient} is not, because one is a UI
 * contract and the other is a network round trip. {@link WorkspaceTree} bridges them the way every remote
 * file browser does: answer from what has arrived, request what has not, and refresh when it lands.</p>
 */
public class CgUiWorkspaceScene implements InteractiveSceneLifecycle,
        CgSystemInput.Keyboard, CgSystemInput.Mouse {

    private static final String PROJECT_ID = "harness.scratch";

    private UIWindow uiWindow;

    // ── The server half ─────────────────────────────────────────────────────────────────────────
    private ServerUiSession<Object> server;
    private InMemoryTransport<Object> fromServer;
    private InMemoryTransport<Object> fromClient;

    // ── The client half ─────────────────────────────────────────────────────────────────────────
    private ClientUiSession<Object> session;
    private WorkspaceClient<Object> workspace;
    private WorkspaceTree tree;

    private TreeView<CgPath> treeView;
    private TabView tabs;
    private UIText status;
    private UIElement banner;
    private UIText bannerText;

    /** Open documents, by path, so a second click on a file focuses its tab rather than opening another. */
    private final Map<CgPath, Tab> openTabs = new LinkedHashMap<>();
    private final Map<CgPath, TextEditor> editors = new HashMap<>();

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
        WorkspaceService service = new WorkspaceService(
                registry, new LocalFileSystem(registry), WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        fromServer = pair[0];
        fromClient = pair[1];

        server = new ServerUiSession<>(1, new UIElement(), fromServer, PlainOps.INSTANCE);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();

        session = new ClientUiSession<>(fromClient, PlainOps.INSTANCE);
        workspace = new WorkspaceClient<>(session, PlainOps.INSTANCE);
        tree = new WorkspaceTree(workspace);

        uiWindow = new UIWindow(Ui.of(buildUi()));
        uiWindow.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        uiWindow.getStyleEngine().addStylesheet(StyleSheetRegistry.of("harness:workspace"));

        // DELIBERATELY NOT loading projects here. The client's window id is -1 until OpenWindow
        // arrives, and the server discards any packet for another window -- so a call made now is thrown
        // away with no error at all. See WorkspaceSceneOrderTest. The first frame that has a window does
        // it instead.
    }

    /** True once the session is connected and the project list has been asked for. */
    private boolean projectsRequested;

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
        head.addChild(save);
        status = new UIText("");
        status.addClass("ws-status");
        head.addChild(status);
        root.addChild(head);

        banner = new UIElement();
        banner.addClass("ws-banner");
        bannerText = new UIText("");
        bannerText.addClass("ws-banner-text");
        banner.addChild(bannerText);
        Button reload = new Button("Load File System Changes");
        reload.addClass("ws-btn");
        reload.attachListener(this::reloadConflicted);
        banner.addChild(reload);
        Button keep = new Button("Keep Memory Changes");
        keep.addClass("ws-btn");
        keep.attachListener(this::keepConflicted);
        banner.addChild(keep);
        root.addChild(banner);
        showBanner(null);

        SplitView split = new SplitView();
        split.addClass("ws-split");
        split.setPercentage(20);

        treeView = new TreeView<>(tree);
        treeView.addClass("ws-tree");
        treeView.setRenderer(new com.crystalgui.ui.elements.tree.TreeRenderer<>() {
            @Override
            public UIElement createTemplate() {
                UIElement row = new UIElement();
                row.addClass("ws-row");
                UIText label = new UIText("");
                // The LABEL does not take the click, so the press lands on the row -- the same trick every
                // composite in this engine uses, since click targeting takes the exact element hit and
                // never walks up to a handler-bearing ancestor.
                label.setHitTest(false);
                row.addChild(label);
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
                ((UIText) template.getChildren().get(0))
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
        split.first().addChild(treeView);

        tabs = new TabView();
        tabs.addClass("ws-tabs");
        split.second().addChild(tabs);
        root.addChild(split);

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
        workspace.read(path, document -> {
            TextEditor editor = new TextEditor(document.text());
            editor.addClass("ws-editor");
            Tab tab = tabs.addTab(path.name());
            tab.content().addChild(editor);
            tabs.selectTab(tab);
            openTabs.put(path, tab);
            editors.put(path, editor);
            note = "opened " + path;
        }, failure -> note = "open failed: " + failure.code());
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
        workspace.save(path, editor.getText().getBytes(StandardCharsets.UTF_8),
                etag -> {
                    note = "saved " + path;
                    showBanner(null);
                },
                failure -> {
                    if (failure.isConflict()) {
                        conflicted = path;
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
        workspace.read(path, document -> {
            editors.get(path).setText(document.text());
            showBanner(null);
            note = "reloaded " + path;
        }, failure -> note = "reload failed: " + failure.code());
    }

    /**
     * Keeps the local edit, overwriting what is on disk — IntelliJ's second button.
     *
     * <p>Goes through {@code overwrite}, not {@code save}: the whole point is to write without an etag
     * check, and {@link WorkspaceClient#save} would quote the stale one and be refused again.</p>
     */
    private void keepConflicted() {
        CgPath path = conflicted;
        if (path == null) return;
        workspace.overwrite(path, editors.get(path).getText().getBytes(StandardCharsets.UTF_8),
                etag -> {
                    showBanner(null);
                    note = "kept local changes to " + path;
                },
                failure -> note = "keep failed: " + failure.code());
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
        if (tree.drainRefresh()) treeView.refresh();

        uiWindow.init(ctx.getScreenWidth(), ctx.getScreenHeight());
        String treeError = tree.failure();
        status.setText(treeError != null ? treeError : note);
        uiWindow.paintFrame();

        var context = CgUiPaintContext.getInstance();
        context.text().draw().at(0, 0)
                .text("workspace: " + PROJECT_ID + "  |  click a file to open, Ctrl+S to save")
                .font(context.getFont().atSize(14)).submit();

        if (frame.getFrameNumber() == 5) ctx.getArtifactService().requestCapture("startup");
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
        // Ctrl+S before the editor sees it, or 's' is typed into the document.
        if (event.pressed() && !event.repeat() && event.key() == org.lwjgl.input.Keyboard.KEY_S
                && (org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LCONTROL)
                 || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RCONTROL))) {
            saveActive();
            return false;
        }
        return uiWindow.getInputHandler().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return uiWindow.getInputHandler().consumeMouseEvent(event);
    }

    // ── The async-to-sync bridge ────────────────────────────────────────────────────────────────

    /**
     * A {@link TreeDataSource} over an asynchronous client.
     *
     * <p>Answers from what has arrived and requests what has not. A directory whose listing is still in
     * flight reports no children — which is honest, and resolves itself when the response lands and the
     * view is refreshed. Every remote file browser works this way; the alternative is blocking the render
     * thread on a round trip.</p>
     */
    private static final class WorkspaceTree implements TreeDataSource<CgPath> {

        private final WorkspaceClient<?> client;
        private final List<CgPath> roots = new ArrayList<>();
        private final Map<String, String> projectNames = new HashMap<>();
        private final Map<CgPath, List<CgPath>> children = new HashMap<>();
        private final Set<CgPath> directories = new HashSet<>();
        private final Set<CgPath> requested = new HashSet<>();
        private volatile boolean dirty;

        WorkspaceTree(WorkspaceClient<?> client) {
            this.client = client;
        }

        private String failure;

        /** The last failure, so the status line can show it. Null when nothing has gone wrong. */
        String failure() {
            return failure;
        }

        void loadProjects(Runnable onLoaded) {
            client.projects(infos -> {
                roots.clear();
                for (ProjectInfo info : infos) {
                    CgPath root = info.root();
                    roots.add(root);
                    directories.add(root);
                    projectNames.put(info.id(), info.displayName());
                }
                dirty = true;
                onLoaded.run();
            }, error -> {
                // REPORTED, not swallowed. The first version of this was an empty lambda with a comment
                // claiming the status line covered it -- so when the call was being dropped outright, the
                // scene showed an empty tree and no reason for it.
                failure = "projects failed: " + error.code();
                dirty = true;
            });
        }

        String displayNameOf(CgPath projectRoot) {
            return projectNames.getOrDefault(projectRoot.project(), projectRoot.project());
        }

        boolean isDirectory(CgPath path) {
            return directories.contains(path);
        }

        /** True once since the last call — the frame uses it to decide whether to refresh the view. */
        boolean drainRefresh() {
            if (!dirty) return false;
            dirty = false;
            return true;
        }

        @Override
        public List<CgPath> roots() {
            return roots;
        }

        @Override
        public List<CgPath> children(CgPath parent) {
            List<CgPath> known = children.get(parent);
            if (known != null) return known;
            request(parent);
            return List.of();
        }

        @Override
        public boolean hasChildren(CgPath item) {
            // Every directory claims children, even before its listing arrives -- otherwise it would
            // render as a leaf and there would be nothing to click to trigger the request.
            return directories.contains(item);
        }

        private void request(CgPath directory) {
            if (!requested.add(directory)) return;
            client.list(directory, entries -> {
                List<CgPath> paths = new ArrayList<>(entries.size());
                for (CgFileEntry entry : entries) {
                    CgPath child = directory.resolve(entry.name());
                    paths.add(child);
                    if (entry.isDirectory()) directories.add(child);
                }
                paths.sort((x, y) -> {
                    boolean dx = directories.contains(x), dy = directories.contains(y);
                    if (dx != dy) return dx ? -1 : 1;      // directories first, as every file tree does
                    return x.name().compareToIgnoreCase(y.name());
                });
                children.put(directory, paths);
                dirty = true;
            }, failure -> {
                // Allow a retry rather than latching the failure -- the listing may have failed because
                // the directory was being written to.
                requested.remove(directory);
                if (failure.error() != CgFileError.FILE_NOT_FOUND) children.put(directory, List.of());
            });
        }
    }
}
