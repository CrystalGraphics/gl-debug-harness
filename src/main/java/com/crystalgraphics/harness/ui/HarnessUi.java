package com.crystalgraphics.harness.ui;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.runtime.HarnessWindow;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.app.vfxlab.VfxLab;
import com.crystalgui.core.async.HostThread;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.host.HostServices;
import com.crystalgui.desktop.host.HostSession;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The harness as a CrystalGUI host, as Minecraft's screen and HUD are: one desktop a run, over every 3D scene, its
 * applications opened by key. Up, it is a game's screen: the cursor is free, the camera holds, every key and click is
 * the desktop's but Ctrl+R, and Escape that nothing in it wants closes it. Down, Escape (or T) opens it, as a game's opens
 * its pause screen, and its pinned windows stay over the world.
 *
 * <pre>{@code
 * // a scene's init: its own application, on a key for this run
 * ctx.getUi().bind(CgKeyCodes.KEY_LMENU, MyPicker.KIND);
 *
 * // any application, by code
 * ctx.getUi().open(VfxLab.KIND);
 * ctx.getUi().hide();
 * }</pre>
 *
 * <ul>
 *   <li>F6 is VFX Lab's in every scene; nothing opens unasked, so the desktop Escape opens starts empty. A scene
 *       binding a key another binding has throws.</li>
 *   <li>A bound key whose application runs brings it forward, raised and given the keyboard, as a single-instance
 *       kind's second launch does; one whose window was closed starts it fresh. Escape closes the desktop.</li>
 *   <li>An application is an {@link ApplicationKind}: its window, its ticks and its cleanup are its own, so it runs
 *       on any CrystalGUI host. A kind not installed on the desktop is installed as it is first opened.</li>
 *   <li>3D scenes only; {@code ctx.getUi()} is null in a 2D one, which is a CrystalGUI document of its own.</li>
 * </ul>
 */
public final class HarnessUi implements CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** The harness's own bindings, in every scene. */
    private static final Map<Integer, ApplicationKind> GLOBAL = Map.of(CgKeyCodes.KEY_F6, VfxLab.KIND);

    private final Map<Integer, ApplicationKind> keys = new HashMap<>(GLOBAL);
    private final Queue<Runnable> onRenderThread = new ConcurrentLinkedQueue<>();
    private final Thread renderThread = Thread.currentThread();
    private final HostSession.PaintHost paintHost = new HostSession.PaintHost() {
        @Override public boolean ownScreenUp() { return shown; }
        @Override public boolean anyScreenUp() { return shown; }
        // Every harness draw is CgGL's: there is no toolkit's state to hand over and back
        @Override public void enter() { }
        @Override public void leave() { }
    };
    private volatile boolean shown;

    /** The runtime's, once a run, on the render thread. */
    public HarnessUi(HarnessContext ctx) {
        // No primary: every application opens on its own key, and the desktop Escape opens starts empty
        HostSession.install(new Services(ctx), null);
        // The pinned windows over the world while the desktop is down
        HostSession.attachStages(paintHost);
    }

    /** Binds {@code key} ({@code CgKeyCodes}) to {@code kind} for this run: pressed in the scene, it opens it. */
    public void bind(int key, ApplicationKind kind) {
        ApplicationKind taken = keys.putIfAbsent(key, kind);
        if (taken != null) throw new IllegalStateException(kind.id() + " cannot take key " + key + ": " + taken.id() + " has it");
    }

    /** Shows the desktop with {@code kind} launched, or brought forward if it runs. Render thread. */
    public void open(ApplicationKind kind) {
        if (!HostSession.mayOpen()) return;
        HostSession session = HostSession.session();
        session.shown();
        shown = true;
        // A single-instance kind already running is activated rather than launched again
        session.inDocument(() -> {
            Desktop desktop = session.desktop();
            if (!desktop.applications().installed().contains(kind)) desktop.applications().install(kind);
            desktop.applications().launch(kind, session.workspace());
        });
    }

    /** Shows the desktop and touches no window. Render thread. */
    public void showDesktop() {
        if (!HostSession.mayOpen()) return;
        HostSession.session().shown();
        shown = true;
    }

    /** Closes the desktop; every window on it is kept for the next time. */
    public void hide() {
        if (!shown) return;
        shown = false;
        HostSession.session().hidden();
    }

    /**
     * Whether it is up: the runtime then frees the cursor and holds the camera without pausing, and the runner's
     * single-letter bindings stand down.
     */
    public boolean shown() {
        return shown;
    }

    /** Each frame over the HUD. The runtime's. */
    public void render(FrameInfo frame) {
        for (Runnable work; (work = onRenderThread.poll()) != null; ) work.run();
        if (!shown) return;
        HostSession session = HostSession.session();
        float delta = session.frameDelta();
        session.frame(delta);
        session.paint(DesktopPresentation.DESKTOP, delta, paintHost);
    }

    /** Down, a bound key opens its kind and every other key passes on. Up, a bound key brings its kind forward and every
     * other key is the desktop's but Ctrl+R. */
    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        ApplicationKind bound = event.pressed() && !event.repeat() ? keys.get(event.key()) : null;
        if (!shown) {
            if (bound != null) open(bound);
            // Escape opens the desktop, as a game's opens its pause screen; T, the harness's old pause key, too
            else if (event.pressed() && !event.repeat() && (event.key() == CgKeyCodes.KEY_ESCAPE || event.key() == CgKeyCodes.KEY_T)) {
                showDesktop();
                return false;
            }
            return bound == null;
        }
        if (bound != null) {
            open(bound);
            return false;
        }
        if (event.key() == CgKeyCodes.KEY_R && ctrlDown()) return true;
        // A desktop on its own thread takes every key and hands back what it left (reinjectKey); one on this
        // thread answers now, and Escape it did not want closes it, as a game's screen
        boolean taken = HostSession.session().input().consumeKeyboardEvent(event);
        if (!taken && event.pressed() && event.key() == CgKeyCodes.KEY_ESCAPE) hide();
        return false;
    }

    private static boolean ctrlDown() {
        return CgPlatform.input().isKeyDown(CgKeyCodes.KEY_LCONTROL) || CgPlatform.input().isKeyDown(CgKeyCodes.KEY_RCONTROL);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        if (!shown) return true;
        HostSession.session().input().consumeMouseEvent(event);
        return false;
    }

    /** At the run's end. The runtime's. */
    public void dispose() {
        if (HostSession.isInstalled()) HostSession.session().dispose();
    }

    /** What the harness answers a host's questions with, and its threads. */
    private final class Services implements HostServices {

        private final HarnessContext ctx;

        Services(HarnessContext ctx) {
            this.ctx = ctx;
        }

        @Override public Path installationDirectory() { return ctx.installation(); }
        @Override public Path localWorldDirectory() { return null; }
        @Override public float uiScale() { return DEFAULT_UI_SCALE; }
        @Override public int surfaceWidth() { return HarnessWindow.width(); }
        @Override public int surfaceHeight() { return HarnessWindow.height(); }
        @Override public String desktopId() { return "harness"; }
        @Override public CgProtocolConnection<Object> connection() { return null; }
        @Override public Locale locale() { return Locale.getDefault(); }

        /** A key the desktop left, a frame late: Escape nothing in it wanted closes it, as a game's screen. */
        @Override
        public void reinjectKey(CgSystemInput.Keyboard.Event key) {
            if (key.pressed() && key.key() == CgKeyCodes.KEY_ESCAPE) hide();
        }

        @Override
        public HostThread.Binding clientThread() {
            return new HostThread.Binding() {
                @Override public void execute(Runnable work) { onRenderThread.add(work); }
                @Override public boolean isCurrent() { return Thread.currentThread() == renderThread; }
            };
        }

        @Override public HostThread.Binding serverThread() { return null; }
    }
}
