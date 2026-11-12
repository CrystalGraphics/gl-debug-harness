package com.crystalgraphics.harness.ui;

import com.crystalgui.screen.CgUiScreen;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgGameDirectory;
import com.crystalgraphics.platform.service.CgGameThreads;
import com.crystalgraphics.platform.service.CgScreens;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.render.UiPresenter;
import com.crystalgui.viewport.CgUiViewport;

import javax.annotation.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The harness as a CrystalGUI host, as Minecraft's screen and HUD are: one desktop a run, over every 3D scene, its
 * applications opened by key. Up, it is a game's screen: the cursor is free, the camera holds, every key and click is
 * the desktop's but Ctrl+R, and Escape that nothing in it wants closes it. Down, Escape (or T, or Left Alt) opens it, as a game's opens
 * its pause screen, and its pinned windows stay over the world.
 *
 * <pre>{@code
 * // a scene's init: its own application, on a key for this run
 * ctx.getUi().bind(CgKeyCodes.KEY_F4, MyPicker.KIND);
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
 * ui.replaceNextFrame((stage, presenter) -> presenter.presentAgain(stage));   // once, in place of the desktop's paint
 * }</pre>
 *
 * <ul>
 *   <li>No key opens an application unasked (Escape, T and Left Alt open the desktop): the engine's applications (VFX Lab, Crystal Editor) are in the launcher on every scene,
 *       on the scratch project {@link HarnessServer} serves, and a scene gives its own applications a key with {@code bind}. Nothing opens
 *       unasked, so the desktop Escape opens starts empty. A scene binding a key another binding has throws.</li>
 *   <li>A bound key whose application runs brings it forward, raised and given the keyboard, as a single-instance
 *       kind's second launch does; one whose window was closed starts it fresh. Escape closes the desktop.</li>
 *   <li>An application is an {@link ApplicationKind}: its window, its ticks and its cleanup are its own, so it runs
 *       on any CrystalGUI host. A kind bound to a key is installed on the desktop as soon as the desktop shows.</li>
 *   <li>3D scenes only; {@code ctx.getUi()} is null in a 2D one, which is a CrystalGUI document of its own.</li>
 * </ul>
 */
public final class HarnessUi implements CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** What the desktop's workspace is served by, as a single-player world's server. */
    private final HarnessServer server = new HarnessServer();

    private final Map<Integer, ApplicationKind> keys = new HashMap<>();
    /** Kinds offered in the launcher with no key. */
    private final List<ApplicationKind> offered = new ArrayList<>();
    private final Queue<Runnable> onRenderThread = new ConcurrentLinkedQueue<>();
    private final Thread renderThread = Thread.currentThread();
    private volatile boolean shown;
    @Nullable
    private Predicate<CgSystemInput.Keyboard.Event> sceneKeys;
    @Nullable
    private Consumer<FrameInfo> afterFrames;

    /** The runtime's, once a run, on the render thread. */
    public HarnessUi(HarnessContext ctx) {
        // Nothing opens the primary application: every one opens on its own key, and the desktop Escape opens starts empty
        HarnessHost.bind(this, onRenderThread, renderThread, ctx.installation());
        CgUiScreen.install();
    }

    /** Binds {@code key} ({@code CgKeyCodes}) to {@code kind} for this run: pressed in the scene, it opens it. */
    public void bind(int key, ApplicationKind kind) {
        ApplicationKind taken = keys.putIfAbsent(key, kind);
        if (taken != null) throw new IllegalStateException(kind.id() + " cannot take key " + key + ": " + taken.id() + " has it");
    }

    /** Offers {@code kind} in the launcher for this run, with no key: the desktop installs it as it shows. */
    public void offer(ApplicationKind kind) {
        offered.add(kind);
    }

    /** Shows the desktop with {@code kind} brought forward if it runs, launched if not. Render thread. */
    public void open(ApplicationKind kind) {
        CgUiScreen.open();
        if (!shown) return;
        // The host's tick first: it binds the workspace an application launches against
        CgUiScreen.tick();
        installBound();
        // A KEY BRINGS BACK WHAT RUNS, and launches only when nothing does: a new instance is the menu's and Start's
        CgUiScreen.run(() -> {
            Desktop desktop = CgUiScreen.desktop();
            if (desktop.applications().byId(kind.id()) == null) desktop.applications().install(kind);
            desktop.applications().activateOrLaunch(kind, CgUiScreen.workspace());
        });
    }

    /** Shows the desktop and touches no window. Render thread. */
    public void showDesktop() {
        CgUiScreen.open();
        if (shown) installBound();
    }

    /**
     * Installs every application bound to a key, so the desktop offers it before it is first opened: in Start,
     * and as a pin a previous run left on the taskbar.
     */
    private void installBound() {
        CgUiScreen.run(() -> {
            Desktop desktop = CgUiScreen.desktop();
            if (desktop == null) return;
            for (ApplicationKind kind : keys.values()) install(desktop, kind);
            for (ApplicationKind kind : offered) install(desktop, kind);
        });
    }

    private static void install(Desktop desktop, ApplicationKind kind) {
        if (desktop.applications().byId(kind.id()) == null) desktop.applications().install(kind);
    }

    /**
     * Shows the desktop from the scene's first frame, for a scene that is the desktop, and runs {@code build} on its
     * document. The loading screen holds until {@code build} has run. From the scene's {@code init}, which is behind the
     * loading screen, where {@link #showDesktop} refuses.
     */
    public void openAtStart(Runnable build) {
        CgUiScreen.openBehindLoadingScreen();
        CgUiScreen.tick();
        installBound();
        CgUiViewport viewport = CgUiScreen.viewport();
        if (viewport == null || !viewport.isSequenced()) {
            CgUiScreen.run(build);
            return;
        }
        // On the document's own sequence: a cold desktop and editor are over half a second the render thread would hold
        CgLoadPlan.Span building = CgLoadPlan.get().begin("harness-ui:build");
        viewport.execute(() -> {
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

    /**
     * Runs {@code paint} once, at the next frame's desktop and in place of it, with that frame's {@code SCREEN} firing
     * and the desktop's presenter. For a probe presenting a frame itself. @see CgUiScreen#replaceNextPaint
     */
    public void replaceNextFrame(BiConsumer<CgStageFrame, UiPresenter> paint) {
        CgUiScreen.replaceNextPaint(paint);
    }

    /** Closes the desktop once it has played itself off screen; every window on it is kept for the next time. */
    public void hide() {
        if (shown) CgUiScreen.close();
    }

    /**
     * Whether it is up: the runtime then frees the cursor and holds the camera without pausing, and the runner's
     * single-letter bindings stand down.
     */
    public boolean shown() {
        return shown;
    }

    /**
     * Each frame, before the GUI stages: the client work posted to the render thread, and the server. The desktop itself
     * paints on {@code SCREEN}, which the runtime fires while it is {@link #shown()}. The runtime's.
     */
    public void tick(FrameInfo frame) {
        for (Runnable work; (work = onRenderThread.poll()) != null; ) work.run();
        server.tick(frame.getDeltaTime());
    }

    /** Each frame, after the GUI stages: the probe {@link #afterFrames} set. The runtime's. */
    public void afterFrame(FrameInfo frame) {
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
            // Escape opens the desktop, as a game's opens its pause screen; T, the harness's old pause key, and Left Alt too
            else if (event.pressed() && !event.repeat() && (event.key() == CgKeyCodes.KEY_ESCAPE || event.key() == CgKeyCodes.KEY_T
                    || event.key() == CgKeyCodes.KEY_LMENU)) {
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
        // A desktop on its own thread takes every key and gives back what it left (CgScreens.giveBack); one on this
        // thread answers now, and what it left goes the same way
        if (!CgUiScreen.keyboard().consumeKeyboardEvent(event) && !CgUiScreen.offerLeftKey(event)) CgScreens.giveBack(event);
        return false;
    }

    /** A key the desktop and its listeners left: the scene's, else Escape closes the desktop, as a game's screen. */
    private void left(CgSystemInput.Keyboard.Event event, CgScreens.Owner front) {
        Predicate<CgSystemInput.Keyboard.Event> keys = sceneKeys;
        if (keys != null && keys.test(event)) return;
        if (event.pressed() && event.key() == CgKeyCodes.KEY_ESCAPE) front.escape();
    }

    private static boolean ctrlDown() {
        return CgInput.isKeyDown(CgKeyCodes.KEY_LCONTROL) || CgInput.isKeyDown(CgKeyCodes.KEY_RCONTROL);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        if (!shown) return true;
        CgUiScreen.mouse().consumeMouseEvent(event);
        return false;
    }

    /** At the run's end. The runtime's. */
    public void dispose() {
        if (CgUiScreen.isInstalled()) CgUiScreen.dispose();
    }

    /**
     * What the harness answers CrystalGraphics' platform services with: its render thread is the client thread, its
     * window the surface, its installation the game directory, its one screen the desktop's, and there is no world.
     * Provided once, since each service keeps the host it is first given; each run points it at its own queue and UI.
     */
    private static final class HarnessHost implements CgGameThreads.Host, CgGameDirectory.Host,
            CgScreens.Host {

        private static final HarnessHost INSTANCE = new HarnessHost();
        private static volatile CgGameThreads.Binding client;
        private static volatile Path installation;
        private static volatile HarnessUi ui;
        @Nullable
        private static volatile CgScreens.Owner front;

        static void bind(HarnessUi runUi, Queue<Runnable> queue, Thread thread, Path installationDirectory) {
            ui = runUi;
            front = null;
            installation = installationDirectory;
            client = new CgGameThreads.Binding() {
                @Override public void execute(Runnable work) { queue.add(work); }
                @Override public boolean isCurrent() { return Thread.currentThread() == thread; }
            };
            CgPlatform.provide(CgGameThreads.HOST, INSTANCE);
            CgPlatform.provide(CgGameDirectory.HOST, INSTANCE);
            CgPlatform.provide(CgScreens.HOST, INSTANCE);
        }

        @Override public CgGameThreads.Binding client() { return client; }
        @Override public CgGameThreads.Binding server() { return null; }
        @Override public Path game() { return installation; }
        @Override public Path world() { return null; }

        /** Up at once: the runtime frees the cursor and fires {@code SCREEN} from the next frame. */
        @Override
        public void open(CgScreens.Owner owner) {
            if (front == owner) return;
            CgScreens.Owner was = front;
            front = owner;
            ui.shown = true;
            if (was != null) was.hidden();
            owner.shown();
        }

        /** Down at once: a close comes from the desktop's own frame or a key, never from inside its paint. */
        @Override
        public void close(CgScreens.Owner owner) {
            if (front != owner) return;
            front = null;
            ui.shown = false;
            owner.hidden();
        }

        @Override public CgScreens.Owner front() { return front; }
        @Override public boolean anyFront() { return front != null; }

        @Override
        public void giveBack(CgSystemInput.Keyboard.Event key) {
            CgScreens.Owner owner = front;
            if (owner != null) ui.left(key, owner);
        }

        /** The harness has no screens of its own: the desktop's is the only one. */
        @Override
        public void closeFront() {
            CgScreens.Owner owner = front;
            if (owner != null) CgScreens.close(owner);
        }

        @Override
        public void openChat() {
        }
    }
}
