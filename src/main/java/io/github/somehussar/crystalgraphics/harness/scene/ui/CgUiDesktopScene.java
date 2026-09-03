package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.language.run.view.ScriptWorkbench;
import com.crystalgui.app.editor.CrystalEditor;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.core.window.WindowPolicy;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.DesktopCommands;
import com.crystalgui.desktop.taskbar.TaskbarDesigner;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.LocalConfigStorage;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.ui.dom.UIElement;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.input.keymap.KeyChord;
import com.crystalgui.ui.input.keymap.Keymap;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.toolwindow.ToolWindowManager;
import com.crystalgui.workbench.toolwindow.ToolWindowType;

import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

import org.joml.Matrix4f;

import java.nio.file.Paths;

/**
 * CrystalOS, hands-on — the compositor scene, and the one place any of it can be driven by hand.
 *
 * <h3>This is the {@code windowing} scene, on the M6 engine</h3>
 *
 * <p>A PORT of that file rather than a fresh scene, and deliberately: it is the compositor's manual,
 * written a W at a time as {@code plan_windowing.md} shipped, and everything it tells you to try is a
 * thing somebody built the demonstration for on the day. Rewriting it would have thrown that away and
 * quietly replaced it with whatever this engine happened to make easy — the difference between a scene
 * that can find a regression and one that can only show what already works.</p>
 *
 * <p>So far: W1 geometry and chrome, W2 stacking and activation, W3 the lifecycle, W4 the taskbar,
 * W5 window-scoped modality, W6 maximise and restore, W7 the editor as a window, W8 tool-window
 * tear-out, W10 the switcher, W12 persistence and the no-steal rule, W13a the window command set and
 * its system menu, W13b Alt-drag, fullscreen and snap, W14 game mode.</p>
 *
 * <h3>What to do in here</h3>
 * <ul>
 *   <li><b>Drag a title bar.</b> The clamp is Windows', not a panel's: a window may hang off the sides
 *       and the bottom, and its caption can never leave the top. Push one into a corner to see which
 *       edges give. <i>(W1)</i></li>
 *   <li><b>Drag any edge or corner.</b> All eight handles come from {@code resize: both} in
 *       {@code ua/desktop.css} — nothing in {@code WindowFrame} builds them. <i>(W1)</i></li>
 *   <li><b>Overlap them and click the one behind.</b> It comes forward <em>and the click still
 *       lands</em> — press a button in a background window and watch it both raise the window and
 *       press. The caption of the active one is coloured; the rest go quiet. <i>(W2)</i></li>
 *   <li><b>Click a button in one window, switch to another, and click back.</b> Focus returns to the
 *       button you left, not to the first thing in the window — and clicking a title bar to drag does
 *       not count as leaving it. <i>(W2)</i></li>
 *   <li><b>Click bare desktop.</b> No window is active, which is a legal state and what every desktop
 *       does when you click its background. <i>(W2)</i></li>
 *   <li><b>Resize the harness window.</b> Every frame re-clamps against the new work area, and windows
 *       pushed in by a shrinking desktop go back to where you put them when the room returns.
 *       <i>(W1)</i></li>
 *   <li><b>F2 opens another window</b>, placed by the Win32 cascade — one caption height further along
 *       each time, wrapping back to the origin when the next step would put it off the work area.
 *       <i>(W1)</i></li>
 *   <li><b>Minimise a window and watch its tick counter stop.</b> That counter is the whole point of
 *       the freeze: a hidden window is <em>detached</em>, so it lays out nothing, paints nothing, holds
 *       no input state and runs nothing either. Its taskbar entry brings it back and the counter
 *       <em>resumes from where it stopped</em>, which is retention working rather than a window being
 *       rebuilt. <i>(W3)</i></li>
 *   <li><b>The strip along the bottom.</b> One entry per window that exists — a minimised one is
 *       dimmed, never dropped, because the entry IS the way back. Click an entry to bring its window
 *       forward; click the entry of the window you are already in and it minimises, which is the toggle
 *       that makes a taskbar a taskbar. The entries never move: open order, whatever you activate.
 *       <i>(W4)</i></li>
 *   <li><b>Press "Modal" and the dialog blocks ONE window.</b> The window it belongs to stops
 *       answering clicks and dims behind a backdrop; every other window, and the taskbar, stays
 *       completely live — drag them, click their buttons, minimise them. Escape closes the dialog
 *       first and the window second, which is the cascade in one gesture. <i>(W5)</i></li>
 *   <li><b>Maximise, or double-click a caption.</b> A maximised window fills the WORK AREA — the
 *       taskbar's row is not part of it, because the strip is laid out rather than overlaid — its
 *       resize handles go away, and its glyph turns into "restore". Then <b>drag its title bar</b>: it
 *       comes loose under the pointer, keeping the same fraction across the caption. A press alone does
 *       nothing, or a double-click could never work. <i>(W6)</i></li>
 *   <li><b>The Crystal Editor is a window here.</b> The whole workbench — file tree, tabs, dock,
 *       panels — inside a {@code WindowFrame}, over the harness's own workspace, sharing the desktop
 *       with everything else. <b>Its menu lives in the window's caption</b> — client-side decorations,
 *       so there is one header rather than two stacked on each other. <i>(W7)</i></li>
 *   <li><b>The close button</b> destroys by default, and the window behind it takes over. The Welcome
 *       window is deliberately {@code HIDE_ON_CLOSE} instead — close it and it minimises. <i>(W3)</i></li>
 *   <li><b>F4 opens a window WITHOUT taking focus</b> — the no-steal rule. Type in the editor while you
 *       press it: the caret never moves, the editor keeps its lit caption, and the new window goes in
 *       <em>behind</em> it. Its taskbar entry is highlighted until you click it, carries a <b>badge</b>,
 *       and <b>fills with progress</b> for four seconds before both go away. <b>Middle-click any
 *       entry</b> to close its window through its own policy. <i>(W12)</i></li>
 *   <li><b>Move the windows, quit, and run it again.</b> They come back where you left them, in the
 *       order you left them, with the minimised ones still minimised. <i>(W12)</i></li>
 *   <li><b>Right-click a title bar. Right-click a taskbar entry. Press {@code Alt+-}.</b> The same
 *       four rows in three places, because they are one command set with three renderers — Restore,
 *       Minimize, Maximize, Close, each greyed exactly when it does not apply. <b>Restore and Maximize
 *       are two rows</b>, as Win32's are, rather than one whose label changes under you. <i>(W13a)</i></li>
 *   <li><b>Right-click the entry of a window that is NOT in front, and choose Minimize.</b> It
 *       minimises <em>that</em> window, not the one you were looking at. A taskbar entry is not inside
 *       the window it stands for, so it has to say which window it means. <i>(W13a)</i></li>
 *   <li><b>Hold Alt and drag anywhere inside a window</b> — over its content, over a button, over the
 *       editor's text. The Linux WM staple, and the answer for a window whose title bar is tiny or
 *       covered by adopted chrome. It takes the press in the CAPTURE phase, which is the whole of what
 *       makes "anywhere" true. <i>(W13b)</i></li>
 *   <li><b>Drag a window's caption to the left or right edge.</b> A translucent rectangle shows where
 *       it will land — <em>behind</em> the window you are dragging, so it reads as a hole in the desktop
 *       rather than a sheet over your hand — and releasing snaps it to that half. <b>Drag to the top</b>
 *       to maximise. Press Escape mid-drag and the preview goes with it. <i>(W13b)</i></li>
 *   <li><b>F11 fullscreens</b> the active window: it fills the desktop and the taskbar goes with it.
 *       Nothing special had to be built — the strip is <em>laid out</em>, so hiding it re-flows the work
 *       area and the maximised window follows. <i>(W13b)</i></li>
 *   <li><b>Open the system menu and choose Move, then use the arrows.</b> Enter keeps the result,
 *       Escape puts the window back <em>exactly</em> where it was, Shift nudges finely, and any other
 *       key ends the mode and still does whatever it was going to do. This is the only way to move a
 *       window whose title bar has ended up off the work area. <i>(W13c)</i></li>
 *   <li><b>Right-click the taskbar itself</b> — the strip, not an entry — for <b>Show Desktop</b>.
 *       Everything minimises; run it again and exactly what it took comes back. Two menus on one strip,
 *       and the distinction is what they are about: an entry's names a window, the strip's names the
 *       desktop. <i>(W13c)</i></li>
 *   <li><b>Look for a chord beside Close. There isn't one.</b> {@code Ctrl+W} closes an editor tab —
 *       frequent, cheap, undoable — and a window takes its content with it, so the destructive one is
 *       deliberately not a keystroke away from the common one. <i>(W13a)</i></li>
 * </ul>
 *
 * <p>The readout window is deliberately a <em>live</em> one rather than text painted over the scene: it
 * is the only way to watch the clamp arithmetic while dragging, and it doubles as content that proves a
 * window clips what it holds.</p>
 *
 * <h3>What the port changed, and none of it is a choice</h3>
 * <ul>
 *   <li><b>The tick counter is an OWNED hook.</b> The old one was a {@code UIFrameTicker} that had to
 *       return false once its element left the tree, and re-register itself from {@code onShown}
 *       because registration was one-way — the widget's half of the freeze, and the half that is
 *       invisible when it is missing. {@code Animation.every(owner, hook)} is dropped when the owner
 *       is frozen, so the counter is only a counter now. The demonstration is unchanged; the contract
 *       it was demonstrating is gone.</li>
 *   <li><b>The modal is {@code attachOwned}</b> where the old scene called
 *       {@code uiWindow.addOverlay(dialog, owner.content())}. Same surface, named by the frame.</li>
 *   <li><b>Every size here is in LOGICAL pixels</b>, which is half the surface at this scene's 2x. The
 *       old scene ran at 1x and its numbers are its own — a 560x380 editor there is the whole desktop
 *       here, which is exactly what the first run of this port did.</li>
 * </ul>
 */
public class CgUiDesktopScene
        implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** The harness runs at 2x, like every other CrystalGUI scene, so text is legible in a screenshot. */
    private static final float SCALE = 2f;

    private UIDocument document;
    private Desktop desktop;

    /** The live geometry readout, refreshed per frame. */
    private UIText readout;

    /**
     * W12 is <em>seen</em> here rather than reasoned about — move the windows, close the harness, run it
     * again and they are where you left them. Its own id, so an M6 arrangement and the old scene's
     * cannot overwrite each other while both engines still run.
     */
    private static final String DESKTOP_ID = "harness-m6";

    /** Numbers the cascade, so a new window is identifiable once there are six of them. */
    private int spawned;

    /** The host's own root, which is all a host provides: the desktop fills whatever it is given. */
    private static final String STYLES = """
            .demo-root { width: 100%; height: 100%; }
            .desktop-editor { width: 100%; height: 100%; }
            """;

    /** Both halves of a real workspace, in this process — the same fixture the dock scene runs on. */
    private final HarnessWorkspace workspace = new HarnessWorkspace();

    private CrystalEditor editor;
    private ScriptWorkbench scripting;
    private boolean projectsAsked;
    private boolean focusGiven;
    private int backgroundWindows;

    @Override
    public void init(HarnessContext ctx) {
        document = new UIDocument().markFrameThread();
        document.styles().addStylesheet(StyleSheet.DEFAULT);
        document.styles().addStylesheet(StyleSheet.parse(STYLES));
        document.boxes().setRootTransform(new Matrix4f().scale(SCALE, SCALE, 1f));

        // ALL A HOST PROVIDES IS A ROOT WITH A SIZE. Nobody constructs a desktop -- `Desktop.of` finds
        // or builds the document's one, because the engine may not name a compositor, so the compositor
        // names the document.
        UIElement root = new UIElement();
        root.addClass("demo-root");
        document.append(root);
        desktop = Desktop.of(document);

        WindowFrame welcome = desktop.addWindow(new WindowFrame("Welcome"));
        // HIDE_ON_CLOSE, so one window in the scene demonstrates the other half of the policy: its
        // close button minimises rather than destroys.
        welcome.setPolicy(WindowPolicy.HIDE_ON_CLOSE).setKey("desktop:welcome");
        welcome.setIcon("crystalgui:package");
        welcome.moveTo(24, 24).resizeTo(250, 210);
        welcome.content().append(paragraph(
                "CrystalOS — geometry, chrome, stacking.",
                "",
                "Drag the title bar to move me.",
                "Drag any edge or corner to resize me.",
                "My caption cannot leave the top of the",
                "desktop, but my body may hang off the",
                "sides and the bottom.",
                "",
                "F2 opens a window; the strip below",
                "brings one back. Minimise me and my",
                "counter stops.",
                "",
                "F3 floats the editor's Project panel.",
                "F4 opens one WITHOUT taking focus.",
                "F5 opens the Taskbar Designer.",
                "F6 is GAME MODE: only PINNED windows",
                "paint, and they keep ticking.",
                "",
                // READ FROM THE KEYMAP, never spelled. The chord is a command's declared default and any
                // keymap above overrides it, so a literal here is a promise the scene cannot keep -- the
                // same rule every tooltip and menu accelerator in the engine follows.
                switcherHint()));
        installTickCounter(welcome);
        // TWO BUTTONS PER WINDOW, so focus memory has somewhere to be remembered: focus one, click the
        // other window, click back, and the same button should still hold it. One button per window
        // would pass by coincidence -- the focus delegate is the first focusable, which with one
        // control IS the remembered one.
        welcome.content().append(focusRow("Alpha", "Beta"));
        welcome.content().append(modalButton(welcome));

        // OVERLAPPING ON PURPOSE, and added second so it is the one on top: equal z-index sorts
        // later-inserted-first, and paint walks that list in reverse. Two windows that never touch
        // demonstrate nothing about stacking.
        WindowFrame inspector = desktop.addWindow(new WindowFrame("Geometry"));
        // A KEY IS WHAT A RECORD CAN NAME A WINDOW BY; without one a window is anonymous and comes back
        // at its default place every run.
        inspector.setKey("desktop:geometry");
        inspector.setIcon("crystalgui:code");
        inspector.moveTo(150, 250).resizeTo(300, 150);
        readout = new UIText("");
        inspector.content().append(readout);
        inspector.content().append(focusRow("Gamma", "Delta"));
        inspector.content().append(modalButton(inspector));

        openEditorWindow();

        // WHERE THE ARRANGEMENT LIVES -- W12 -- and LAST, so it is applied over whatever the scene above
        // set up rather than under it. Every window this scene opens carries a key, which is what a
        // record can name one by; a key from an older version of this scene simply matches nothing.
        // With no record at all it does nothing, so a first run looks exactly as it always has.
        desktop.persistTo(new LocalConfigStorage(
                Paths.get("workspace-config").toAbsolutePath().normalize()), DESKTOP_ID);
    }

    /**
     * "Ctrl+Tab cycles windows", with the chord <b>resolved</b> rather than written down.
     *
     * <p>Null scope on purpose: nothing is focused when the scene is built, and {@code acceleratorFor}
     * falls through to the command's own declared default — which is what a keymap-less harness has.</p>
     */
    private static String switcherHint() {
        KeyChord chord = Keymap.acceleratorFor(null, DesktopCommands.SWITCH_WINDOW);
        return chord == null ? "The window switcher is unbound." : chord + " cycles windows (MRU).";
    }

    /**
     * The whole editor, as one window on the desktop — W7's shape, run in the harness.
     *
     * <p>Opened <b>floating</b> here and maximised in game, and the difference is the point rather than
     * an inconsistency: in game there is nothing else on the desktop yet, so a maximised frame is the
     * full-screen editor that was there before W7 and nobody notices the migration. Here the whole
     * exercise is seeing it share a desktop, so it starts at a size that leaves the other windows
     * visible. Double-click its caption for the in-game default.</p>
     *
     * <p>{@code ScriptWorkbench} IS installed here now. It used to be {@code cgui-dock}'s, on the
     * division that this scene is about the compositor and that one about the editor with everything
     * on — sound while both could run the editor, and gone the moment `ScriptWorkbench` moved to the
     * new engine: {@code cgui-dock} is the old engine's and is deleted at 6.9b, so without this there
     * would be no scene that runs the Run panel at all.</p>
     */
    private void openEditorWindow() {
        editor = new CrystalEditor(workspace.client());
        // Beside the scratch workspace, never inside it: a session record is private and must not become
        // part of a project a resource pack ships. The dock scene keeps its own for the same reason.
        editor.useConfig(new LocalConfigStorage(
                Paths.get("workspace-config").toAbsolutePath().normalize()));
        editor.addClass("desktop-editor");

        // RUN AND STOP, for the file in front. Null when no engine band was staged, and the commands
        // are then deliberately NOT registered -- a Run row that cannot run anything teaches people
        // the feature is broken rather than unavailable.
        scripting = ScriptWorkbench.install(
                CommandRegistry.global(), editor.workbench(),
                Paths.get("build", "script-cache").toAbsolutePath().normalize());

        WindowFrame frame = desktop.addWindow(new WindowFrame("Crystal Editor"));
        // HIDE_ON_CLOSE: a workbench is not a dialog, so its close button minimises and its taskbar
        // entry is the way back -- with every document, the dock arrangement and the undo history intact.
        frame.setPolicy(WindowPolicy.HIDE_ON_CLOSE).setKey("editor:main");
        frame.setIcon("crystalgui:logo");
        frame.moveTo(300, 55).resizeTo(600, 400);
        // setContent, not content().append -- it is what ADOPTS the editor's menu bar into the caption.
        // Without it the editor keeps its own header and the window has two.
        frame.setContent(editor);
    }

    private UIElement paragraph(String... lines) {
        UIElement box = new UIElement();
        for (String line : lines) box.append(new UIText(line));
        return box;
    }

    /**
     * A per-window counter that ticks while the window is on the desktop and <b>stops dead while it is
     * hidden</b> — the freeze, made watchable.
     *
     * <p><b>An OWNED hook, where the old scene needed the widget's own half of the contract.</b> That
     * version was a {@code UIFrameTicker} which had to return false once its element left the tree, and
     * re-register itself from {@code onShown} because registration was one-way. Both are gone:
     * {@code Animation.every} is dropped when its owner is frozen or disconnected, and the
     * {@code onShown} line below asks for it back. Restoring resumes the count rather than resetting
     * it, which is the difference between retention and a window that was rebuilt.</p>
     */
    private void installTickCounter(WindowFrame frame) {
        UIText label = new UIText("ticks 0");
        frame.content().append(label);
        int[] ticks = {0};
        frame.onShown.connect(persisted -> document.animation().every(frame, delta -> {
            label.setText("ticks " + (++ticks[0]));
            return true;
        }));
        document.animation().every(frame, delta -> {
            label.setText("ticks " + (++ticks[0]));
            return true;
        });
    }

    /**
     * Opens a modal <b>owned by this window</b> — the W5 demonstration.
     *
     * <p>{@code attachOwned} parents it on the window's own overlay slot rather than in the global top
     * layer, which is what scopes the modality. Everything outside the window carries on working, which
     * is the entire point.</p>
     */
    private UIElement modalButton(WindowFrame owner) {
        Button open = new Button("Modal");
        open.onPressed.connect(() -> {
            Dialog dialog = new Dialog("Owned by " + owner.getTitle());
            owner.attachOwned(dialog);
            dialog.append(new UIText("This blocks " + owner.getTitle() + "."));
            dialog.append(new UIText("Everything else still works."));
            dialog.showModal();
        });
        UIElement row = new UIElement();
        row.layout(l -> l.flexDirection(FlexDirection.ROW).paddingTop(4));
        row.append(open);
        return row;
    }

    /** Two focusable controls that do nothing but hold focus — which is the whole point of them. */
    private UIElement focusRow(String first, String second) {
        UIElement row = new UIElement();
        row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4).paddingTop(4));
        row.append(new Button(first));
        row.append(new Button(second));
        return row;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // THE CLOCK EVERY NODE PREVIEW READS -- the shader graph's thumbnails animate off whatever clock
        // the application drives, and without this CG_TIME is permanently zero.
        CgRenderPipeline.getInstance().getFrameData().timeSecs = (float) frame.getElapsedTime();

        // ONE NETWORK TICK, before anything reads the workspace.
        workspace.pump(frame.getDeltaTime());
        if (!projectsAsked && workspace.isConnected()) {
            projectsAsked = true;
            // Deferred until the session has a window id: before that the server discards every packet
            // addressed to another window, so an earlier call is dropped with no error at all.
            editor.workbench().fileTree().loadProjects();
            // AFTER loadProjects: the restore parks the folders it wants expanded and retries until the
            // listings that reveal them arrive, so asking first would park everything.
            editor.restoreSession(HarnessWorkspace.PROJECT_ID);
        }

        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();
        document.frame(frame.getDeltaTime(), w / SCALE, h / SCALE);
        refreshReadout();

        CgUiPaintContext context = CgUiPaintContext.getInstance();
        context.beginFrame(w, h);
        // SIMULATED GAME MODE -- W14, and the closest a GL harness gets to Minecraft without being it.
        // F6 flips the scene to input-off and leaves ONLY pinned frames on screen. What it is really
        // testing is the thing no unit test can reach: that a pinned window keeps its hooks and its
        // transitions while every unpinned window is frozen, and that nothing runs the hover pipeline
        // meanwhile.
        //
        // ONE PAINT ENTRY, where the old scene had two. The old `UIWindow` took a
        // `DesktopPresentation` and painted a different subtree for the HUD; the new `enterHudMode`
        // HIDES the unpinned windows instead, so what is left on screen is already exactly the HUD and
        // an ordinary paint draws it. `DesktopPresentation` still exists -- it is one of the three
        // types in `core.window` both engines name -- and on this engine nothing reads it yet.
        document.paint(context);
        context.endFrame();

        // ONCE, not per frame. The dock scene can call this every frame because the editor is the whole
        // UI there; on a desktop it would haul focus back out of whatever window you just clicked.
        if (!focusGiven) {
            focusGiven = true;
            editor.giveInitialFocus();
        }

        // Late enough that the first window's placement, the entry animations and the editor's own
        // deferred rebuilds have all settled -- a capture at frame 5 photographs a desktop that is
        // still assembling itself and every diff against it is noise.
        if (frame.getFrameNumber() == 40) ctx.getArtifactService().requestCapture("startup");
    }

    /**
     * Written before the paint so the numbers are this frame's, not last frame's — the same ordering
     * reason the dock scene defers its own dump until after a paint: input is consumed before layout
     * runs, so anything read on the way in describes boxes that have not been measured yet.
     */
    private void refreshReadout() {
        UIElement area = desktop.windowLayer();
        WindowFrame active = desktop.activeWindow();
        Box areaBox = area == null ? null : area.box();
        StringBuilder text = new StringBuilder();
        text.append("work area  ")
                .append(areaBox == null ? 0 : Math.round(areaBox.width())).append(" x ")
                .append(areaBox == null ? 0 : Math.round(areaBox.height()))
                .append("      active: ").append(active == null ? "(none)" : active.getTitle())
                .append('\n');
        // THE REGISTRY, not the layer -- so hidden windows are listed too. A minimised window that
        // appears nowhere is a minimised window with no way back, which is the failure W4's taskbar
        // exists to prevent.
        for (WindowFrame window : desktop.windows()) {
            boolean hidden = window.state() == WindowState.HIDDEN;
            Box box = window.box();
            text.append(window == active ? "> " : "  ")
                    .append(window.getTitle()).append("  ")
                    .append(hidden ? "hidden" : Math.round(window.left()) + ", " + Math.round(window.top()))
                    .append("   ")
                    .append(box == null ? 0 : Math.round(box.width())).append(" x ")
                    .append(box == null ? 0 : Math.round(box.height()))
                    // THE DEPTH, because raise is the one W2 operation with no visible effect of its
                    // own when the windows happen not to overlap.
                    .append("   z ").append(window.getStyle().getGeneralGroup().zIndex())
                    .append('\n');
        }
        readout.setText(text.toString());
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        if (event.pressed() && event.key() == CgKeyCodes.KEY_F2) {
            spawnCascadedWindow();
            return true;
        }
        if (event.pressed() && event.key() == CgKeyCodes.KEY_F3) {
            toggleFloatingProjectPanel();
            return true;
        }
        if (event.pressed() && event.key() == CgKeyCodes.KEY_F4) {
            spawnBackgroundWindow();
            return true;
        }
        // THE MODE OWNS THE KEYBOARD except for its own way out. In game the keyboard is the game's;
        // here the scene has to stand in for that, and a mode nobody can leave is worse than no mode.
        if (event.pressed() && event.key() == CgKeyCodes.KEY_F6) {
            if (desktop.isHudMode()) desktop.exitHudMode(); else desktop.enterHudMode();
            return true;
        }
        if (desktop.isHudMode()) return true;
        // F5 IS A CONVENIENCE, NOT THE AFFORDANCE. The designer is a registered command with its own
        // chord and a taskbar context-menu entry, so it is reachable identically here and in game --
        // this key exists only because a harness scene is where it gets opened forty times an hour.
        if (event.pressed() && event.key() == CgKeyCodes.KEY_F5) {
            WindowFrame existing = desktop.registry().byKey("taskbar-designer");
            if (existing != null) existing.requestClose();
            else TaskbarDesigner.open(document);
            return true;
        }
        return document.input().consumeKeyboardEvent(event);
    }

    /**
     * W12's no-steal rule and the three things a taskbar entry can say, in one keystroke.
     *
     * <p>What to look at, and most of it is what does <b>not</b> happen. The window appears and
     * <em>nothing else moves</em>: the window you were in keeps its caption lit, keeps the keyboard, and
     * is not covered — the new one goes in behind it. That is Win32's foreground lock, X11's urgency
     * hint and macOS's bouncing dock icon, all of which exist because the alternative is a server
     * pushing a UI that eats the next thing you type.</p>
     *
     * <p>Its taskbar entry is what says it arrived: highlighted until you click it, and never again
     * after — activation is the only thing that clears it, because a flash on a timer is a notification
     * you can miss by looking away. The entry also carries a <b>badge</b> and fills with
     * <b>progress</b>, which is the same entry answering three separate questions at once and the
     * reason they were built together.</p>
     */
    private void spawnBackgroundWindow() {
        WindowFrame frame = new WindowFrame("Server push " + (++backgroundWindows));
        frame.setBadge(String.valueOf(backgroundWindows));
        UIText note = new UIText("Opened without focus.\nThe editor kept the keyboard.");
        note.generalStyle(g -> g.color(0xFFB4B8BF));
        frame.content().append(note);
        frame.resizeTo(220f, 110f).moveTo(40f + backgroundWindows * 16f, 340f);
        // FALSE is the no-steal rule -- the second argument is whether to activate.
        desktop.addWindow(frame, false);
        // A REAL DURATION, ticked rather than set once: progress that is only ever written at the moment
        // the window opens draws a bar nobody sees move, which demonstrates the slot and not the feature.
        frame.setProgress(0f);
        document.animation().every(frame, delta -> {
            if (frame.state() == WindowState.DESTROYED) return false;
            float next = frame.progress() + delta * 0.25f;
            frame.setProgress(next);
            if (next < 1f) return true;
            // Done: the fill goes away and the badge with it, which is the half that rots when nobody
            // writes it -- a badge only ever added says "3 errors" for the rest of the session.
            frame.setProgress(-1f);
            frame.setBadge(null);
            return false;
        });
    }

    /**
     * W8 in one keystroke: the Project panel out of its region and into an owned window, and back.
     *
     * <p>The gesture a user actually performs is dragging a rail button into the editor area — a drop
     * that resolves to no region, which is IntelliJ's own tear-out zone. That is hard to do
     * <em>reliably</em> from a scene, and impossible to do at all before the first frame, so the key is
     * here as well: it drives exactly the same two calls the drop does.</p>
     *
     * <p>What to look at, because most of W8 is only visible in what does <b>not</b> happen. The float
     * is a real {@code WindowFrame} — drag its caption, resize its edges — and it is <b>owned</b>: move
     * the editor window and the float travels with it, minimise the editor and the float goes too, and
     * it never appears in the taskbar, because it is not independently reachable. The panel keeps its
     * expansion and scroll across the trip, because the same container is moved rather than rebuilt. And
     * there is <b>one header</b>: the panel's own is adopted into the frame's caption.</p>
     */
    private void toggleFloatingProjectPanel() {
        if (editor == null) return;
        ToolWindowManager manager = editor.workbench().toolWindowManager();
        if (manager.typeOf(Workbench.PROJECT_TYPE) == ToolWindowType.FLOATING) {
            manager.dockPanel(Workbench.PROJECT_TYPE);
            return;
        }
        // Offset from the editor window's own corner, which is the space a float's insets are measured
        // in -- it is owned by that window, so (0,0) is the editor's top-left and not the desktop's.
        manager.floatPanel(Workbench.PROJECT_TYPE, 100f, 70f);
    }

    /**
     * A window with <b>no position at all</b> — which is the case the cascade exists for, and the only
     * way to exercise it. Placement then happens on this frame's layout pass, once the caption and the
     * work area have been measured; there is nothing to place against before that.
     */
    private void spawnCascadedWindow() {
        spawned++;
        WindowFrame window = desktop.addWindow(new WindowFrame("Window " + spawned));
        window.setIcon("crystalgui:file-text");
        window.resizeTo(200, 120);
        window.content().append(new UIText("Cascaded, not placed."));
        window.content().append(focusRow("One", "Two"));
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        // DISPLAY-ONLY IS THE RULE THAT MAKES THE HUD SOUND. In game the cursor is grabbed, so its
        // reported position is wherever the player last had a menu open -- delivering a move against it
        // would enter and leave elements under a pointer that is not there. Refusing here is the
        // harness standing in for a grab it has no way to perform.
        if (desktop.isHudMode()) return true;
        return document.input().consumeMouseEvent(event);
    }

    @Override
    public void dispose() {
        // ASKED FOR EXPLICITLY, because this scene tears down without ever detaching anything -- and
        // detaching is the moment each of these would otherwise write itself. WHAT to write is still
        // theirs; this only says when.
        if (desktop != null) desktop.savePersistedState();
        if (editor != null) {
            editor.saveState();
            Disposer.dispose(editor);
        }
        editor = null;
        document = null;
        desktop = null;
        readout = null;
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
}
