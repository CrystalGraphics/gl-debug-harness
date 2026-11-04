package com.crystalgraphics.harness.ui;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.runtime.HarnessWindow;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.app.crystaleditor.CrystalEditor;
import com.crystalgui.app.vfxlab.VfxLab;
import com.crystalgui.core.async.HostThread;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.host.HostServices;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.ui.dom.DocumentDriver;

import javax.annotation.Nullable;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
 * <p>A scene that is the desktop opens it at its start, takes the keys its windows leave, and drives frames:</p>
 *
 * <pre>{@code
 * ui.openAtStart(() -> desktop.addWindow(new WindowFrame("Hello")));   // built on the document, behind the loading screen
 * ui.sceneKeys(event -> event.key() == CgKeyCodes.KEY_F2 && spawnWindow());
 * ui.afterFrames(frame -> probe.frame());
 * ui.replaceNextFrame(() -> UiGpu.presentAgain(w, h));                 // once, in place of the desktop's frame
 * }</pre>
 *
 * <ul>
 *   <li>F6 is VFX Lab's in every scene and F10 Crystal Editor's, on the scratch project {@link HarnessServer} serves;
 *       nothing opens unasked, so the desktop Escape opens starts empty. A scene binding a key another binding has
 *       throws.</li>
 *   <li>A bound key whose application runs brings it forward, raised and given the keyboard, as a single-instance
 *       kind's second launch does; one whose window was closed starts it fresh. Escape closes the desktop.</li>
 *   <li>An application is an {@link ApplicationKind}: its window, its ticks and its cleanup are its own, so it runs
 *       on any CrystalGUI host. A kind bound to a key is installed on the desktop as soon as the desktop shows.</li>
 *   <li>3D scenes only; {@code ctx.getUi()} is null in a 2D one, which is a CrystalGUI document of its own.</li>
 * </ul>
 */
public final class HarnessUi implements CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** The harness's own bindings, in every scene. */
    private static final Map<Integer, ApplicationKind> GLOBAL = Map.of(
            CgKeyCodes.KEY_F6, VfxLab.KIND,
            CgKeyCodes.KEY_F10, CrystalEditor.KIND);

    /** What the desktop's workspace is served by, as a single-player world's server. */
    private final HarnessServer server = new HarnessServer();

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
    @Nullable
    private Predicate<CgSystemInput.Keyboard.Event> sceneKeys;
    @Nullable
    private Consumer<FrameInfo> afterFrames;
    @Nullable
    private Runnable replacement;

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
        // The host's tick first: it binds the workspace an application launches against
        session.frame(0f);
        shown = true;
        installBound(session);
        // A single-instance kind already running is activated rather than launched again
        session.inDocument(() -> {
            Desktop desktop = session.desktop();
            if (desktop.applications().byId(kind.id()) == null) desktop.applications().install(kind);
            desktop.applications().launch(kind, session.workspace());
        });
    }

    /** Shows the desktop and touches no window. Render thread. */
    public void showDesktop() {
        if (!HostSession.mayOpen()) return;
        HostSession.session().shown();
        shown = true;
        installBound(HostSession.session());
    }

    /**
     * Installs every application bound to a key, so the desktop offers it before it is first opened: in Start,
     * and as a pin a previous run left on the taskbar.
     */
    private void installBound(HostSession session) {
        session.inDocument(() -> {
            Desktop desktop = session.desktop();
            if (desktop == null) return;
            for (ApplicationKind kind : keys.values()) {
                if (desktop.applications().byId(kind.id()) == null) desktop.applications().install(kind);
            }
        });
    }

    /**
     * Shows the desktop from the scene's first frame, for a scene that is the desktop, and runs {@code build} on its
     * document. The loading screen holds until {@code build} has run. From the scene's {@code init}, which is behind the
     * loading screen, where {@link #showDesktop} refuses.
     */
    public void openAtStart(Runnable build) {
        HostSession session = HostSession.session();
        session.shown();
        session.frame(0f);
        shown = true;
        installBound(session);
        DocumentDriver<?> driver = session.document().driver();
        if (driver == null || !driver.isAsync()) {
            session.inDocument(build);
            return;
        }
        // On the document's own sequence: a cold desktop and editor are over half a second the render thread would hold
        CgLoadPlan.Span building = CgLoadPlan.get().begin("harness-ui:build");
        driver.post(() -> {
            try {
                build.run();
            } finally {
                building.close();
            }
        });
    }

    /**
     * The scene's keys while the desktop is up: offered each press and release the desktop left, on the render thread,
     * a frame late when the document runs on its own sequence. True takes it; Escape nobody took closes the desktop.
     */
    public void sceneKeys(@Nullable Predicate<CgSystemInput.Keyboard.Event> keys) {
        sceneKeys = keys;
    }

    /** Runs {@code after} on the render thread after every frame's desktop, shown or not. For a probe. */
    public void afterFrames(@Nullable Consumer<FrameInfo> after) {
        afterFrames = after;
    }

    /** Runs {@code present} once, at the next frame's desktop and in place of it. For a probe presenting a frame itself. */
    public void replaceNextFrame(Runnable present) {
        replacement = present;
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
        server.tick(frame.getDeltaTime());
        Runnable present = replacement;
        replacement = null;
        if (present != null) {
            present.run();
        } else if (shown) {
            HostSession session = HostSession.session();
            float delta = session.frameDelta();
            session.frame(delta);
            session.paint(DesktopPresentation.DESKTOP, delta, paintHost);
        }
        Consumer<FrameInfo> after = afterFrames;
        if (after != null) after.accept(frame);
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
        if (!taken) left(event);
        return false;
    }

    /** A key the desktop left: the scene's, else Escape closes the desktop, as a game's screen. */
    private void left(CgSystemInput.Keyboard.Event event) {
        Predicate<CgSystemInput.Keyboard.Event> keys = sceneKeys;
        if (keys != null && keys.test(event)) return;
        if (event.pressed() && event.key() == CgKeyCodes.KEY_ESCAPE) hide();
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
        @Override public CgProtocolConnection<Object> connection() { return server.client(); }
        @Override public Locale locale() { return Locale.getDefault(); }

        /** A key the desktop left, a frame late. */
        @Override
        public void reinjectKey(CgSystemInput.Keyboard.Event key) {
            left(key);
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
