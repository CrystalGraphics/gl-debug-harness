package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.language.run.view.RunPanels;
import com.crystalgui.language.run.view.ScriptWorkbench;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.editor.CrystalEditor;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockRegion;
import com.crystalgui.ui.elements.dock.RegionSide;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.Ui;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.ui.UIWindow;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

import java.nio.file.Paths;

/**
 * The editor, running.
 *
 * <h3>What to do in here</h3>
 * <ul>
 *   <li><b>Click a file in Project</b> — it opens in its own tab, named after the file and coloured for
 *       its language, loaded over the real RPC from a real directory on disk ({@code workspace/} beside
 *       the harness). <b>Ctrl+S writes the active tab back</b>; edit the same file in another editor
 *       first and the server refuses the stale write.</li>
 *   <li><b>Shader Graph tab</b> — Space opens the create menu, wire into Output's Base Color and the GLSL
 *       beside it recompiles. Click a line in that source and the status line names the node that emitted
 *       it.</li>
 *   <li><b>Drag tabs</b> between panes, to a pane's edge to split, or to the window's edge for a
 *       full-height column. <b>Ctrl+Shift+P</b> for the palette, which lists everything else.</li>
 * </ul>
 *
 * <h3>What is left here, and why it is so little</h3>
 *
 * <p>The editor is {@link CrystalEditor} in {@code core/} — panels, layout, commands, keys and focus are
 * all its. This scene owns exactly two things a harness legitimately owns: <b>the fake half</b>
 * ({@link HarnessWorkspace} runs both ends of the workspace RPC in one process against a seeded scratch
 * directory, where a real host would have a server across a connection) and its status line at the
 * top. Everything else moved, because a debug scene should never be the only place an application
 * exists.</p>
 */
public class CgUiDockScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** No room reserved at the top any more: the status line is a real StatusBarView inside the
     * workbench now, so it is laid out rather than painted over everything. @see #init */
    private static final String STYLES = """
            .demo-root { width: 100%; height: 100%; padding-all: 8px; }
            """;

    /** Both halves of a real workspace, in this process — the one genuinely fake thing here. */
    private final HarnessWorkspace workspace = new HarnessWorkspace();

    private UIWindow uiWindow;
    private CrystalEditor editor;

    private boolean projectsAsked;

    /** Run and Stop for the active .java file, or null where no engine band is available. */
    private ScriptWorkbench scripting;

    /**
     * Eight stand-in tool windows, so the stripes have enough buttons to actually drag between.
     *
     * <h3>Here rather than in {@code core/}</h3>
     *
     * <p>They exist to be dragged, resized and reordered, which is a thing you do by hand — so they belong
     * in the harness, which is the only place this engine can be driven by hand at all. Registering them in
     * {@code CrystalEditor} would ship eight empty panels to every application built on it.</p>
     *
     * <p><b>Spread across all six slots on purpose</b>, and named after the IntelliJ tool windows that
     * really live there. Three buttons in one group tests almost nothing: the interactions that break are
     * crossing between rails, crossing between a rail's two groups, and reordering <em>within</em> a group —
     * and the last of those needs a group with more than two things in it.</p>
     *
     * <p>Registered but not shown. A button appears for any singleton panel type whether or not it is open,
     * and opening eight would leave no editor.</p>
     */
    private void registerDummyToolWindows() {
     //   dummy("terminal", "Terminal", "crystalgui:code", DockRegion.PANEL, RegionSide.PRIMARY);
      //  dummy("services", "Services", "crystalgui:package", DockRegion.PANEL, RegionSide.PRIMARY);
      //  dummy("run", "Run", "crystalgui:x", DockRegion.PANEL, RegionSide.SECONDARY);
     //   dummy("structure", "Structure", "crystalgui:file-text", DockRegion.SIDEBAR, RegionSide.PRIMARY);
       // dummy("bookmarks", "Bookmarks", "crystalgui:folder", DockRegion.SIDEBAR, RegionSide.PRIMARY);
       // dummy("commit", "Commit", "crystalgui:image", DockRegion.SIDEBAR, RegionSide.SECONDARY);
        // "notifications" is NOT a dummy any more -- Workbench registers the real NotificationsView under
        // that id, and a second registration here would replace it with an empty box.
        //dummy("outline", "Outline", "crystalgui:code", DockRegion.AUXILIARY, RegionSide.SECONDARY);
    }

    private void dummy(String typeId, String title, String icon, DockRegion region, RegionSide side) {
        editor.workbench().registerPanel(
                DockPanelDescriptor.singleton(typeId, title).icon(icon).region(region).side(side),
                ref -> {
                    // NAMED, so a drag that lands somewhere unexpected says which panel it was. An empty
                    // box would make all eight look identical the moment two end up in the same region.
                    UIElement body = new UIElement();
                    body.addChild(new UIText(title + " (dummy)"));
                    return body;
                });
    }

    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(true);

        editor = new CrystalEditor(workspace.client());
        // Beside the scratch workspace, not in it: a session record is private and must not become part of
        // the project a resource pack ships. See WorkbenchSession -- the same reason trash lives outside.
        editor.useConfig(new com.crystalgui.fs.LocalConfigStorage(
                java.nio.file.Paths.get("workspace-config").toAbsolutePath().normalize()));
        editor.addClass("demo-root");
        registerDummyToolWindows();

        uiWindow = new UIWindow(Ui.of(editor));
        uiWindow.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        //uiWindow.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        uiWindow.getStyleEngine().addStylesheet(StyleSheet.parse(STYLES));
        // Commands and their keys are the editor's, not the scene's -- so Ctrl+S, Ctrl+Shift+S and Ctrl+O
        // are registered commands here rather than a switch on scan codes, and appear in the palette with
        // their accelerators like everything else.
        // Nothing to install: constructing the editor registered its commands.

        // RUN AND STOP, for the .java file in front. Null when no engine band was staged, and the
        // commands are then deliberately NOT registered -- a Run row that cannot run anything teaches
        // people the feature is broken rather than unavailable.
        scripting = ScriptWorkbench.install(
                CommandRegistry.global(), editor.workbench(),
                Paths.get("build", "script-cache").toAbsolutePath().normalize());
        // OPEN ON LAUNCH, which is a harness decision and not the panel's: this scene exists to be
        // looked at while the console is being built. A real workbench leaves it on the rail until asked.
        if (scripting != null) editor.workbench().revealPanel(RunPanels.RUN_TYPE);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // THE CLOCK EVERY NODE PREVIEW READS. CgPreviewRenderer deliberately reuses the shared
        // CgRenderPipeline singleton's one CgFrameData rather than owning its own, which is what lets a
        // Time node's thumbnail animate for free off whatever clock the app already drives. Nothing else
        // in this scene drives it, so without this line timeSecs stays at its 0f default forever and
        // CG_TIME is permanently zero: a Time node reads 0, and anything downstream of one renders as if
        // it did. The visible result is a Multiply of Colour x SineTime that is BLACK whatever colour you
        // pick -- which reads as "the preview does not recompile" and is really sin(0).
        //
        // CgUiGalleryScene carries the same line, and the same comment, for the same reason. A host is
        // what owns this clock; in Minecraft the loader drives it.
        CgRenderPipeline.getInstance().getFrameData().timeSecs = (float) frame.getElapsedTime();

        // ONE NETWORK TICK, before anything reads the workspace. In a real client this is the network
        // tick; the scene does it explicitly so the asynchrony stays visible rather than pretended away.
        workspace.pump(frame.getDeltaTime());
        if (!projectsAsked && workspace.isConnected()) {
            projectsAsked = true;
            // Deferred until the session has a window id: before that the server discards every packet
            // addressed to another window, so an earlier call is dropped with no error at all.
            editor.workbench().fileTree().loadProjects();
            // AFTER loadProjects, not before: the restore parks the folders it wants expanded and retries
            // until the listings that reveal them arrive, so asking first would simply park everything.
            editor.restoreSession(HarnessWorkspace.PROJECT_ID);
        }

        uiWindow.init(ctx.getScreenWidth(), ctx.getScreenHeight());
        uiWindow.paintFrame();
        editor.giveInitialFocus();

        if (dumpRequested) {
            dumpRequested = false;
            dumpProblemRows();
        }

        if (frame.getFrameNumber() == 5) ctx.getArtifactService().requestCapture("startup");
    }

    @Override
    public void dispose() {
        if (editor != null && uiWindow != null) {
            editor.saveSession(HarnessWorkspace.PROJECT_ID,
                    (int) uiWindow.getScreenWidth(), (int) uiWindow.getScreenHeight());
            editor.savePreferences();
        }
        if (editor != null) Disposer.dispose(editor);
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

    /**
     * Whether no modifier is held — what makes the debug keys below <b>bare</b> F9 and F10.
     *
     * <p>Without this the scene ate every chord built on those keys: matching the key alone and returning
     * {@code true} meant <b>Shift+F10 never reached the UI at all</b>, so the Run command's own accelerator
     * was dead in this scene and read as a broken binding rather than as a harness debug key sitting on top
     * of it. The keyboard event carries no modifiers, so the state is read from the platform.</p>
     *
     * <p>The same shape as {@code TextField} refusing Ctrl chords but not Alt ones: a handler that tests a
     * key without testing the modifiers claims every accelerator that key is part of.</p>
     */
    private static boolean noModifiers() {
        return CgPlatform.input().getCurrentModifiers() == 0;
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        if (event.pressed() && noModifiers() && event.key() == CgKeyCodes.KEY_F6) {
            StagedMergeDemo.openCommitDiff(uiWindow, editor);
            return true;
        }
        // F7 reads the REPOSITORY; Shift+F7 synthesises. Two keys because they answer different
        // questions and only one of them is guaranteed to have an answer: a tree with nothing staged
        // produces a merge with no conflicts in it, which is correct and exercises none of the conflict
        // UI. Shift+F7 always has one conflict and two auto-merges, so it is the one to reach for when
        // the question is whether the view works rather than what the tree currently says.
        if (event.pressed() && event.key() == CgKeyCodes.KEY_F7) {
            int modifiers = CgPlatform.input().getCurrentModifiers();
            if (modifiers == CgModifiers.NONE) {
                StagedMergeDemo.open(uiWindow, editor);
                return true;
            }
            if (modifiers == CgModifiers.SHIFT) {
                StagedMergeDemo.openSynthesised(uiWindow, editor);
                return true;
            }
        }
        if (event.pressed() && noModifiers() && event.key() == CgKeyCodes.KEY_F8) {
            spawnDebugJobs();
            return true;
        }
        if (event.pressed() && noModifiers() && event.key() == CgKeyCodes.KEY_F9) {
            emitDebugNotifications();
            return true;
        }
        if (event.pressed() && noModifiers() && event.key() == CgKeyCodes.KEY_F10) {
            // DEFERRED to after the next frame is painted. Input is consumed BEFORE layout runs, so
            // dumping here reports every box at 0x0 -- the state rows are in between being rebuilt and
            // being measured, which says nothing about what is on screen.
            dumpRequested = true;
            return true;
        }
        return uiWindow.getInputHandler().consumeKeyboardEvent(event);
    }

    /**
     * <b>F8 — three real jobs, so the status bar's progress has something to show.</b>
     *
     * <p>Real {@link JobScheduler} jobs on real worker threads reporting through
     * {@code JobContext.progress()}, not a fake model written into the widget. That distinction is the
     * whole point of the design: the status bar <em>pulls</em> a snapshot on the frame, and a scene that
     * wrote to the widget directly would exercise the drawing and none of the threading.</p>
     *
     * <p>What to look for. <b>Nothing for the first 400ms</b> — work shorter than that is never drawn at
     * all, which is what stops the bar strobing on every keystroke. Then <b>one line and a {@code (3)}</b>,
     * because the chrome's width must not depend on how much is running; click it for the Processes popup,
     * where each job has its own bar and its own cancel. One of the three is <b>indeterminate</b> and
     * sweeps rather than fills, because it never reported a total. And <b>cancel greys a row but keeps its
     * bar</b> — cancellation is cooperative, so the work has not stopped yet and the row must not pretend
     * it has.</p>
     */
    private void spawnDebugJobs() {
        spawnDebugJob("Downloading engine band 17", 16_000_000L, 6f);
        spawnDebugJob("Indexing classpath", 4_000L, 4f);
        spawnDebugJob("Resolving manifest", -1L, 8f);
    }

    /**
     * One job that reports for {@code seconds}.
     *
     * <p>Reporting is <b>rate-limited</b> to twenty a second rather than per loop turn, which a real
     * transfer owes for the same reason: each report allocates a state so a reader sees a consistent one,
     * and a report per 8 KB chunk is thousands of allocations feeding a bar that redraws sixty times a
     * second.</p>
     */
    private void spawnDebugJob(String what, long total, float seconds) {
        JobKey key = JobKey.of(CgUiDockScene.class, what + "-" + (++debugJobsSpawned));
        JobScheduler.shared().job(key, JobLane.BACKGROUND, context -> {
            long steps = (long) (seconds * 20);
            context.progress().begin(what, total);
            for (long step = 0; step <= steps; step++) {
                if (context.isCancelled()) return "cancelled";
                if (total > 0) {
                    context.progress().advance(total * step / steps);
                    context.progress().detail("part " + step + " of " + steps);
                }
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return "interrupted";
                }
            }
            return "done";
        }).submit();
    }

    private int debugJobsSpawned;

    /**
     * <b>F10 — the Problems rows' real geometry, printed.</b>
     *
     * <p>A row's icon and its message looked a line apart in this scene and were provably centred in every
     * construction of it outside — six variations, all correct. That leaves something about <em>this</em>
     * environment, and the only way to see it is from inside it. Prints each row's box and each part's, so
     * the difference from the fixture is a subtraction rather than another guess.</p>
     */
    private boolean dumpRequested;

    private void dumpProblemRows() {
        UIElement panel = editor.workbench().querySelector("problemspanel");
        if (panel == null) {
            System.out.println("DUMP no problems panel in the tree");
            return;
        }
        var pb = panel.getRuntimeCache();
        System.out.println("DUMP panel x=" + pb.getX() + " y=" + pb.getY()
                + " w=" + pb.getWidth() + " h=" + pb.getHeight()
                + " uiScale=" + uiWindow.getUiScale());
        for (UIElement row : panel.getElementsByClassName("__problem__")) {
            var rb = row.getRuntimeCache();
            System.out.println("DUMP  row y=" + rb.getY() + " h=" + rb.getHeight()
                    + " centre=" + (rb.getY() + rb.getHeight() / 2f));
            for (UIElement part : row.getChildren()) {
                var qb = part.getRuntimeCache();
                String extra = part instanceof UIText
                        ? " ws=" + part.getStyle().getGeneralGroup().whiteSpace()
                                + " shown=" + ((UIText) part).displayedText().length()
                        : "";
                System.out.println("DUMP    " + part.getClasses() + " y=" + qb.getY()
                        + " h=" + qb.getHeight() + " w=" + qb.getWidth()
                        + " centre=" + (qb.getY() + qb.getHeight() / 2f) + extra);
            }
        }
    }

    /**
     * <b>F9 — one notification of each kind, for looking at them.</b>
     *
     * <h3>Why this is in the harness and not a command in core</h3>
     *
     * <p>Every notification the workbench produces is either a failure or something that happened while you
     * were not looking, and a local in-memory workspace has no failure surface — no permissions, no network,
     * no other writer — so an error is genuinely unreachable by using the application. Confirmations of
     * things you had just done were the only easy triggers, and those were noise and were removed.</p>
     *
     * <p>So the trigger belongs to the debug tool. Shipping it as a registered command in {@code core/}
     * would put "emit fake notifications" in every application's command palette, which is a worse trade
     * than a key that only exists here — the same reason the eight stand-in tool windows above live in this
     * scene rather than in {@code CrystalEditor}.</p>
     */
    private void emitDebugNotifications() {
        Notifications.info("Indexing finished");
        // A LONG ONE, because every fixture here was two or three words and the layout was tuned against
        // them. The first real message that wrapped -- a download report carrying a URL -- came out as six
        // lines of fragments with the severity icon floating beside line three, and nothing in this set
        // could have shown that. A fixture set whose messages are all short tests one message length.
        Notifications.info("JDK sources downloaded — 1432 files, 4.3 MB, "
                + "cached under the game directory");
        Notifications.show(Notification.warning("Disk space low")
                .withDetail("Less than 50 MiB is left on the system partition (C:)"));
        Notifications.show(Notification.error("HotSwap failed")
                .withDetail("Error during compilation: no such symbol 'foo'")
                .withAction("Review", () -> Notifications.info("Review clicked"))
                .withAction("Ignore", () -> Notifications.info("Ignore clicked")));
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return uiWindow.getInputHandler().consumeMouseEvent(event);
    }
}
