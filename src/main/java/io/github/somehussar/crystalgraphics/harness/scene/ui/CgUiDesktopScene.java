package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.app.editor.CrystalEditor;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkspaceRpc;
import com.crystalgui.fs.WorkspaceService;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.Switch;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.text.UIText;

import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;

import org.joml.Matrix4f;

/**
 * <b>CrystalOS on the M6 engine</b> — the compositor, stacking windows, and the editor as one of them.
 *
 * <h3>What this is for</h3>
 *
 * <p>{@code AGENTS.md} has described a {@code cgui-desktop} scene since {@code plan_windowing.md}
 * shipped, and the harness has never carried one — so the whole compositor has been asserted by
 * headless tests and never once looked at. This is the counterpart to {@code cgui-new-gallery}: that
 * scene answers whether a ported <em>widget</em> draws, and this one answers whether a ported
 * <em>window</em> behaves, which is a different question and not reachable from a fixture.</p>
 *
 * <p>Every behaviour {@code AGENTS.md} lists against {@code cgui-desktop} has something on screen to
 * exercise it: stacking, drag, resize, clamp, cascade, the taskbar, per-window modality, maximise,
 * the editor running as a window, and a tool window torn out into an owned float.</p>
 *
 * <h3>Things this scene is deliberately built to catch</h3>
 *
 * <ul>
 *   <li><b>A window nobody placed.</b> The compositor takes up no space until a window is open, so
 *       the first window on an empty desktop cannot be placed on the pass that adds it — the work
 *       area is 0x0 at that moment. It is the work area's own layout callback that places what is
 *       unplaced, and only a FIRST open can show whether that fires.</li>
 *   <li><b>The cascade counter.</b> It resets when the layer holds no other frame, so a window closed
 *       and reopened lands centred rather than one caption-step further across the screen each time.
 *       Reachable only by opening, closing and reopening — which is what {@code F5} is here.</li>
 *   <li><b>Per-window modality.</b> A modal is scoped to its window: pressing the blocked window's
 *       content must do nothing while the OTHER window stays fully live. A fixture with one modal
 *       cannot see the difference, because scoped and global agree.</li>
 *   <li><b>The editor as a window.</b> {@code CrystalEditor} is 500 lines of shell that has never
 *       been drawn on this engine, and it is the widest consumer of everything 6.7 ported.</li>
 * </ul>
 *
 * <h3>The workspace behind the editor</h3>
 *
 * <p>An {@link InMemoryFileSystem} over a paired {@link InMemoryTransport}, exactly as
 * {@code CgUiWorkspaceScene} does it — the editor talks to a {@link WorkspaceClient} and never to a
 * filesystem, which is the same shape as a client across a Minecraft connection. The SERVER half
 * still takes an old-engine root, because {@code ServerUiSession} is not retyped until 6.8; that is
 * the one place this scene touches the old engine and it is invisible to everything on screen.</p>
 */
public class CgUiDesktopScene
        implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /**
      * The harness runs at 2x, like every other CrystalGUI scene, so text is legible in a screenshot.
      *
      * <p><b>Every window size below is in LOGICAL pixels, which is half the surface.</b> At 1920x1080
      * this document is 960x540, so a window asked for at 900x560 is taller than the whole desktop —
      * which is what the first run of this scene did, and it read as `resizeTo` being ignored rather
      * than as arithmetic. The sizes here are chosen so all three windows are visible at once, since
      * a screenshot with one window on it cannot show stacking, raising or the cascade.</p>
      */
    private static final float SCALE = 2f;

    private UIDocument document;
    private Desktop desktop;
    private WorkspaceClient<Object> workspace;
    private CrystalEditor editor;

    /** Kept so F5 can close and reopen it — which is what exercises the cascade counter's reset. */
    private WindowFrame welcome;

    private ServerUiSession<Object> server;

    @Override
    public void init(HarnessContext ctx) {
        document = new UIDocument().markFrameThread();

        // THE USER-AGENT SHEET, which is not installed for you. `ua/desktop.css` is where every
        // window rule lives, and without it a frame is an unstyled box -- which reads as the
        // compositor not working rather than as a sheet not being loaded.
        document.styles().addStylesheet(StyleSheet.DEFAULT);
        document.styles().addStylesheet(StyleSheetRegistry.of("crystalgui:graph"));
        document.styles().addStylesheet(StyleSheet.parse(SCENE_CSS));
        document.boxes().setRootTransform(new Matrix4f().scale(SCALE, SCALE, 1f));

        // THE ROOT THE DESKTOP OVERLAYS. The compositor is an internal child of the document and
        // takes up NO SPACE until a window is open, so an application's own content sits underneath
        // it -- which is what this panel is here to prove is still reachable.
        document.append(buildBackdrop());

        desktop = Desktop.of(document);

        openWorkspace();
        openWelcome();
        openGeometry();
        openEditor();
    }

    // ── The desktop's own content, underneath every window ───────────────────────────────────────

    /**
     * The application's content, which the compositor sits over.
     *
     * <p>Load-bearing rather than decoration: an empty desktop that took up space would hit-test
     * across the whole document and eat every click that landed on this panel, and nothing about
     * that symptom points at a compositor. With a window open the surface is claimed and this is
     * behind it; with every window closed ({@code F5} twice) it must become clickable again.</p>
     */
    private UINode buildBackdrop() {
        UINode page = new UINode().setId("page");
        UIText title = new UIText("CrystalOS");
        title.addClass("title");
        page.append(title);
        UIText hint = new UIText(
                "F1 new window   F2 modal in this window   F3 tear out a float   "
                        + "F4 maximise   F5 close/reopen Welcome   F6 minimise all");
        hint.addClass("hint");
        page.append(hint);
        UIText behind = new UIText("This panel is the application UNDER the compositor. "
                + "Close every window and it must still take a click.");
        behind.addClass("hint");
        page.append(behind);
        Button probe = new Button("Press me — I am behind the desktop");
        probe.onPressed.connect(() -> hint.setText("the backdrop took a press at "
                + System.currentTimeMillis() % 100000));
        page.append(probe);
        return page;
    }

    // ── The windows ─────────────────────────────────────────────────────────────────────────────

    /** The first window, and therefore the one that proves a FIRST open gets placed at all. */
    private void openWelcome() {
        welcome = new WindowFrame("Welcome");
        UINode body = new UINode();
        body.addClass("body");
        body.append(new UIText("Drag my caption. Drag my edges. Double-click the caption to maximise."));
        body.append(new UIText("I am the first window on an empty desktop, which is the one case the "
                + "compositor cannot place on the pass that adds me."));
        Switch toggle = new Switch();
        body.append(toggle);
        Slider slider = new Slider();
        body.append(slider);
        welcome.setContent(body);
        welcome.resizeTo(300f, 170f);
        welcome.moveTo(24f, 40f);
        desktop.addWindow(welcome);
    }

    /**
     * A second window, so stacking, raising and per-window modality have two subjects.
     *
     * <p>Two is the minimum that can show any of it. A fixture with one window agrees with every
     * implementation of raise, and one modal makes scoped and global modality indistinguishable.</p>
     */
    private void openGeometry() {
        WindowFrame frame = new WindowFrame("Geometry");
        UINode body = new UINode();
        body.addClass("body");
        body.append(new UIText("Click me, then click Welcome, and watch the taskbar follow."));
        Button modal = new Button("Open a modal in THIS window");
        modal.onPressed.connect(() -> showModalIn(frame));
        body.append(modal);
        Button maximise = new Button("Maximise");
        maximise.onPressed.connect(frame::maximize);
        body.append(maximise);
        frame.setContent(body);
        frame.resizeTo(280f, 160f);
        frame.moveTo(60f, 230f);
        desktop.addWindow(frame);
    }

    /**
     * A modal, scoped to one window.
     *
     * <p>The whole point of opening it from a BUTTON in a specific frame: while it is up, that
     * window's content must refuse a press and the other window must not. A modal opened outside any
     * frame blocks the whole document, which is a different thing and is what desktop chrome's own
     * dialogs need.</p>
     */
    private void showModalIn(WindowFrame frame) {
        Dialog dialog = new Dialog("Scoped to this window");
        dialog.append(new UIText("Try pressing this window's buttons — nothing. "
                + "Now try the other window's. Everything."));
        Button close = new Button("Close");
        close.onPressed.connect(dialog::close);
        dialog.append(close);
        frame.attachOwned(dialog, true);
        dialog.showModal();
    }

    /** The editor, as an ordinary window — the widest consumer of everything 6.7 ported. */
    private void openEditor() {
        editor = new CrystalEditor(workspace);
        WindowFrame frame = new WindowFrame("Crystal Editor");
        frame.setContent(editor);
        frame.resizeTo(560f, 380f);
        frame.moveTo(370f, 70f);
        desktop.addWindow(frame);
    }

    /**
     * The in-memory workspace the editor talks to.
     *
     * <p>{@code ALLOW_ALL} deliberately: there is no player here to guard against, and the default is
     * {@code DENY_ALL} precisely so a real host has to choose. The server session still takes an
     * old-engine root — {@code ServerUiSession} is retyped at 6.8 — which is the single line in this
     * scene that names the engine being replaced.</p>
     */
    @SuppressWarnings("deprecation")
    private void openWorkspace() {
        ProjectRegistry registry = new ProjectRegistry();
        WorkspaceService service = new WorkspaceService(
                registry, new InMemoryFileSystem(), WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        server = new ServerUiSession<>(1, new com.crystalgui.ui.UIElement(), pair[0], PlainOps.INSTANCE);
        WorkspaceRpc<Object> rpc = new WorkspaceRpc<>(service, WorkspaceActor.LOCAL);
        rpc.installOn(server::onCall);
        server.open();

        workspace = new WorkspaceClient<>(new ClientUiSession<>(pair[1], PlainOps.INSTANCE),
                PlainOps.INSTANCE);
    }

    // ── Frame ───────────────────────────────────────────────────────────────────────────────────

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();

        document.frame(frame.getDeltaTime(), w / SCALE, h / SCALE);

        CgUiPaintContext context = CgUiPaintContext.getInstance();
        context.beginFrame(w, h);
        document.paint(context);
        context.endFrame();

        // Late enough that the first window's placement, the entry animations and the editor's own
        // deferred rebuilds have all settled -- a capture at frame 5 photographs a desktop that is
        // still assembling itself and every diff against it is noise.
        if (frame.getFrameNumber() == 60) ctx.getArtifactService().requestCapture("startup");
    }

    @Override
    public void dispose() {
        if (server != null) server.close("the scene was disposed");
        server = null;
        document = null;
        desktop = null;
        editor = null;
        welcome = null;
        workspace = null;
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

    // ── Input ───────────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        // The scene's own keys are read BEFORE the document, and only on the way down: everything
        // here opens or closes a window, which is a gesture no focused widget has an opinion about.
        if (event.pressed()) {
            switch (event.key()) {
                case com.crystalgraphics.platform.input.CgKeyCodes.KEY_F1 -> openGeometry();
                case com.crystalgraphics.platform.input.CgKeyCodes.KEY_F5 -> toggleWelcome();
                case com.crystalgraphics.platform.input.CgKeyCodes.KEY_F6 -> minimiseAll();
                default -> { }
            }
        }
        return document.input().consumeKeyboardEvent(event);
    }

    /**
     * Close it if it is open, open a fresh one if it is not.
     *
     * <p>The cascade counter resets when the layer holds no other frame, so the reopened window must
     * land CENTRED rather than one caption-step along — a drift of 20px per reopen is the shape of
     * that bug and it is invisible until something reopens.</p>
     */
    private void toggleWelcome() {
        if (welcome != null && welcome.state() == WindowState.VISIBLE) {
            welcome.requestClose();
        } else {
            openWelcome();
        }
    }

    private void minimiseAll() {
        for (WindowFrame frame : desktop.registry().windows()) frame.minimize();
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return document.input().consumeMouseEvent(event);
    }

    private static final String SCENE_CSS = """
            #page {
                flex-direction: column;
                width: 100%;
                height: 100%;
                background-color: #101014;
                padding-all: 20;
                gap-all: 10;
            }
            .title { font-size: 22; color: #E8E8EA; }
            .hint  { font-size: 11; color: #9A9AA2; }
            .body {
                flex-direction: column;
                width: 100%;
                height: 0;
                flex-grow: 1;
                padding-all: 12;
                gap-all: 8;
            }
            .body text { font-size: 11; color: #D4D4D8; }
            """;
}
