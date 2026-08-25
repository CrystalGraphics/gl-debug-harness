package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.ui.elements.chrome.QuickPick;
import com.crystalgui.ui.elements.workbench.GoToFile;
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
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.ui.UIWindow;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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

    /** The picker the scripted flow opens, or null on an ordinary hand-driven run. */
    private QuickPick picker;

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

        // The counter's font, before any frame is timed. @see #overlayFont
        overlayFont();
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
            //
            // AND NOT AT ALL UNDER THE SCRIPTED FLOW, which is not tidiness -- it is the difference
            // between a measurement and a coin toss. The session records which documents were open, so
            // a scripted run REOPENS whatever the previous scripted run left behind: the second run of
            // this flow paid the whole cost of opening UIElement during startup, before the picker was
            // ever touched, and its OPENED stage then measured 60ms instead of 237ms. Both numbers were
            // real and neither answered the question. A flow that measures opening a class has to begin
            // with that class shut. @see #printFlowSummary
            if (!flowEnabled) editor.restoreSession(HarnessWorkspace.PROJECT_ID);
        }

        // BEFORE THE PAINT, so a gesture's cost lands in the frame the flow attributes it to.
        elapsed = frame.getElapsedTime();
        if (flowEnabled) advanceFlow();

        uiWindow.init(ctx.getScreenWidth(), ctx.getScreenHeight());
        long painted = System.nanoTime();
        uiWindow.paintFrame();
        editor.giveInitialFocus();
        paintNanos = System.nanoTime() - painted;

        // AFTER THE WHOLE TREE, in its own frame — see paintOverlay.
        sampleFrame(frame);
        long overlaid = System.nanoTime();
        paintOverlay(ctx);
        overlayNanos = System.nanoTime() - overlaid;

        if (dumpRequested) {
            dumpRequested = false;
            dumpProblemRows();
        }

        if (frame.getFrameNumber() == 5) ctx.getArtifactService().requestCapture("startup");
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════
    //  THE SCRIPTED MEASUREMENT
    // ════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * <b>{@code -Dcrystalgui.harness.perfflow=true} — the open-a-big-class flow, driven by a clock.</b>
     *
     * <p>The frame drop being chased — opening a ~3,000-line decompiled class through Go to File — is a
     * <em>sequence</em>, and measuring a sequence by hand costs a launch, a hand on a keyboard and a
     * screenshot per round. That was the real bottleneck rather than any one of the costs inside it. With
     * the flag the scene plays the gestures on a fixed schedule, prints the worst frame per gesture and
     * exits; without it nothing below fires and the scene is exactly the hand-driven one it was.</p>
     *
     * <p><b>Off by default and not the other way round.</b> A scene that types into itself is unusable for
     * the thing this scene is mostly for, and a flow that could be cancelled by touching the keyboard
     * would be a flow whose measurements depend on whether anybody breathed on the window.</p>
     */
    private static final boolean flowEnabled = Boolean.getBoolean("crystalgui.harness.perfflow");

    /** What gets typed into Go to File. Overridable, because the point is a big class and not this one. */
    private static final String QUERY = System.getProperty("crystalgui.harness.perfquery", "UIElement");

    private static final double OPEN_PICKER_AT = 5.0;
    private static final double ACCEPT_AT = 8.0;

    /**
     * When the run ends by itself.
     *
     * <p>Long enough after the accept for the frame rate to come back — the summary's whole claim is that
     * a stall belongs to one gesture, which is only true if the run outlives it.</p>
     */
    private static final double RUN_FOR = 20.0;

    private enum Stage {
        /** Everything up to the picker: the editor built, the project listed, the dock settling. */
        STARTUP,
        /** Go to File is open and the query is going in one character per frame. */
        TYPING,
        /** The query is in; idle until the accept clock. Should be back at the baseline. */
        SEARCHED,
        /** Enter has been pressed; the class is opening. */
        OPENED,
        /** The pointer is being swept across the open document. @see #sweepPointer */
        HOVERING
    }

    private static final double SWEEP_AT = 11.0;

    private Stage stage = Stage.STARTUP;
    private int typed;

    /** The clock {@link #isRunning} reads, which is handed no {@link FrameInfo} of its own. */
    private double elapsed;

    private void advanceFlow() {
        if (stage == Stage.STARTUP && elapsed >= OPEN_PICKER_AT) {
            long timed = FrameProfile.enter("FLOW open Go to File");
            picker = GoToFile.open(uiWindow, editor.workbench());
            FrameProfile.leave(timed, "FLOW open Go to File");
            enterStage(Stage.TYPING, "Go to File opened");
            return;
        }

        if (stage == Stage.TYPING) {
            // ONE CHARACTER PER FRAME, not the whole string in one call. The search runs off the field's
            // own change signal, so a bulk set would collapse nine queries into one and hide exactly the
            // per-keystroke cost this exists to measure — the FIRST keystroke most of all, which is the
            // one that pays for the classpath scan.
            char next = QUERY.charAt(typed++);
            String label = "FLOW keystroke '" + next + "' (" + typed + "/" + QUERY.length() + ")";
            long timed = FrameProfile.enter(label);
            press(next, keyFor(next));
            FrameProfile.leave(timed, label);
            if (typed >= QUERY.length()) enterStage(Stage.SEARCHED, "typed \"" + QUERY + "\"");
            return;
        }

        if (stage == Stage.SEARCHED && elapsed >= ACCEPT_AT) {
            long timed = FrameProfile.enter("FLOW Enter -- open the selected result");
            press('\n', CgKeyCodes.KEY_RETURN);
            FrameProfile.leave(timed, "FLOW Enter -- open the selected result");
            enterStage(Stage.OPENED, "accepted");
            return;
        }

        if (stage == Stage.OPENED && elapsed >= SWEEP_AT) {
            enterStage(Stage.HOVERING, "sweeping the pointer across the document");
            return;
        }

        if (stage == Stage.HOVERING) sweepPointer();
    }

    /**
     * Drags the pointer across the open document, one step per frame.
     *
     * <h3>Without this the flow could not see a whole CLASS of defect</h3>
     *
     * <p>Every gesture above is a keystroke, so the scripted run never moved the mouse — and a hover
     * change is the single most frequent invalidation an editor experiences, because it fires as fast as
     * the hand moves. A cascade that re-matched hundreds of elements per hover was therefore invisible
     * here and perfectly visible in the game, which is the worst possible split: the harness said smooth,
     * the client said 40fps, and both were reporting honestly about different things.</p>
     *
     * <p>A diagonal sweep rather than a straight line, so it crosses rows <em>and</em> columns — the
     * gutter, the text, the scrollbar, the tab strip — instead of hovering one band of identical
     * elements.</p>
     */
    private void sweepPointer() {
        int width = Math.max(1, (int) uiWindow.getScreenWidth());
        int height = Math.max(1, (int) uiWindow.getScreenHeight());
        sweepStep++;
        // A LISSAJOUS rather than a line: two incommensurate rates cover an area over time without ever
        // repeating the same path, which is what makes a fixed number of frames worth more than a sweep
        // that retraces itself.
        int x = (int) (width * (0.5 + 0.42 * Math.sin(sweepStep * 0.07)));
        int y = (int) (height * (0.5 + 0.42 * Math.sin(sweepStep * 0.031)));
        uiWindow.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                x, y, x - lastSweepX, y - lastSweepY, CgMouseCodes.NONE, false, 0f, -1L));
        lastSweepX = x;
        lastSweepY = y;
    }

    private int sweepStep;
    private int lastSweepX;
    private int lastSweepY;

    /**
     * One press and its release, through the real input path — nothing here bypasses dispatch.
     *
     * <p><b>The chord is not synthesised and the typing is.</b> {@code UIInputHandler} reads modifier
     * state from {@code CgPlatform.input()} — real hardware — so a fabricated Ctrl+P arrives with no Ctrl
     * held and resolves to a bare {@code p}. The picker is therefore opened through {@link GoToFile#open},
     * which is exactly what that accelerator resolves to, and only the unmodified keystrokes go through
     * here. Nothing being measured lives in the chord; all of it lives in the search and the open.</p>
     */
    private void press(char character, int key) {
        long now = System.currentTimeMillis();
        uiWindow.getInputHandler().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event(character, key, true, false, now));
        uiWindow.getInputHandler().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event(character, key, false, false, now));
    }

    /** A key code for a letter, so the field sees an ordinary keystroke — it inserts from the char. */
    private static int keyFor(char c) {
        char lower = Character.toLowerCase(c);
        if (lower >= 'a' && lower <= 'z') return CgKeyCodes.KEY_A + (lower - 'a');
        return CgKeyCodes.KEY_NONE;
    }

    private void enterStage(Stage next, String what) {
        recordStage();
        stage = next;
        stageWorstMs = 0f;
        stageWorstPaintMs = 0f;
        stageWorstOverlayMs = 0f;
        stageFrames = 0;
        System.out.println(String.format("[flow] t=%.2fs  %s", elapsed, what));
        FrameProfile.note("FLOW " + what);
    }

    private final Map<Stage, Worst> worstPerStage = new EnumMap<>(Stage.class);

    /** Files the stage that is ending under its own name, so the summary can be printed at the end. */
    private void recordStage() {
        if (stageFrames > 0) {
            worstPerStage.put(stage, new Worst(stageWorstMs, stageWorstPaintMs, stageWorstOverlayMs));
        }
    }

    /**
     * A gesture's worst frame, split by what the span covers. @see #sampleFrame
     *
     * @param deltaMs   what the harness saw between frames — everything, including the swap
     * @param paintMs   what {@code paintFrame()} cost on the frame thread
     * @param overlayMs what the counter itself cost, so the probe can be ruled out
     */
    private record Worst(float deltaMs, float paintMs, float overlayMs) {
    }

    /**
     * The whole point of the run, in four lines.
     *
     * <p>Printed rather than left to the overlay because the overlay only ever shows the <em>current</em>
     * stage: by the time the class is open, the number for the keystroke that scanned the classpath has
     * been gone for ten seconds. A run has to be readable after it has finished, or it is still a thing
     * somebody has to sit and watch.</p>
     */
    private void printFlowSummary() {
        recordStage();
        System.out.println("[flow] ---- worst frame per gesture -----------------------------");
        System.out.println("[flow]            delta    paint  overlay");
        for (Stage each : Stage.values()) {
            Worst worst = worstPerStage.get(each);
            if (worst == null) continue;
            System.out.println(String.format("[flow]   %-8s %6.1f   %6.1f   %6.1f ms   (%s)",
                    each, worst.deltaMs(), worst.paintMs(), worst.overlayMs(), describe(each)));
        }
        // SAID EVERY TIME, not left to be remembered. A delta far above its own paint is the shape of
        // something outside this process, and one run is one sample either way.
        System.out.println("[flow]   delta >> paint means the cost was not on the frame thread"
                + " (GPU, swap, or another application) -- re-run before believing it");
    }

    private static String describe(Stage stage) {
        switch (stage) {
            case STARTUP: return "editor built and settling";
            case TYPING: return "Go to File open, one keystroke per frame";
            case SEARCHED: return "query typed, idle -- should be back to baseline";
            case OPENED: return "Enter: the class opening";
            case HOVERING: return "pointer sweeping -- hover invalidation per frame";
            default: return "";
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════
    //  THE FRAME-RATE OVERLAY
    // ════════════════════════════════════════════════════════════════════════════════════════════

    private static final double FPS_WINDOW = 0.5;
    private static final double WORST_WINDOW = 3.0;

    private static final String OVERLAY_FONT = "crystalgui:ui/fonts/JetBrainsMono-Regular.ttf";

    private double fpsAccum;
    private int fpsFrames;
    private float fps;

    private double worstAccum;
    private float worstMs;
    private float lastMs;

    private float stageWorstMs;
    private int stageFrames;

    private void sampleFrame(FrameInfo frame) {
        float dt = frame.getDeltaTime();
        // THE FIRST FRAME HAS NO DELTA to speak of, and a zero would divide the rate to infinity.
        if (dt <= 0f) return;
        lastMs = dt * 1000f;

        fpsAccum += dt;
        fpsFrames++;
        if (fpsAccum >= FPS_WINDOW) {
            fps = (float) (fpsFrames / fpsAccum);
            fpsAccum = 0;
            fpsFrames = 0;
        }

        worstAccum += dt;
        if (lastMs > worstMs) worstMs = lastMs;
        if (worstAccum >= WORST_WINDOW) {
            worstAccum = 0;
            worstMs = lastMs;
        }

        // THE ONE NUMBER PER GESTURE. Not windowed and never decayed — a stall belongs to the gesture
        // that caused it, so it must still be readable after the frame rate has come back.
        //
        // The stage's first sample is skipped: a stage transition happens INSIDE a frame, so the delta
        // measured on that frame describes the one BEFORE the gesture. Counting it credits the previous
        // stage's tail to this one.
        if (++stageFrames > 1 && lastMs > stageWorstMs) stageWorstMs = lastMs;
        // THE SAME FRAME, MEASURED THREE WAYS, because the three cover different spans and a round of
        // this is otherwise spent explaining why they disagree.
        //
        // FrameProfile's [frame] line covers advanceFrame plus the tree's paint: CPU on the frame thread.
        // The delta the harness reports covers everything else as well -- this overlay, and the buffer
        // swap, where a GPU still working through the previous frame's draws finally blocks.
        //
        // The gap is also where ANYTHING OUTSIDE THIS PROCESS lands. One run here reported a 113ms
        // gl:draw and a 203ms delta against a 77ms [frame] line, and the cause was another application
        // on the machine hanging -- not the editor, not the harness. That is the single most expensive
        // way to read this log wrong, because an outlier arrives looking exactly like a finding. Treat
        // one run as one sample: a real regression reproduces across runs and shows up in the [frame]
        // line, and a spike that appears only in the delta is a reason to run it again before believing
        // it.
        if (stageFrames > 1) {
            stageWorstPaintMs = Math.max(stageWorstPaintMs, paintNanos / 1_000_000f);
            stageWorstOverlayMs = Math.max(stageWorstOverlayMs, overlayNanos / 1_000_000f);
        }
    }

    /** What {@code uiWindow.paintFrame()} cost this frame — CPU, the same span [frame] reports. */
    private long paintNanos;

    /** What the counter itself cost. A probe has to be able to rule itself out. @see #paintOverlay */
    private long overlayNanos;

    private float stageWorstPaintMs;
    private float stageWorstOverlayMs;

    /**
     * The counter, drawn in its own frame after the tree has finished.
     *
     * <h3>Its own {@code beginFrame}, rather than an element in the tree</h3>
     *
     * <p>A {@code UIText} promoted to the top layer would be simpler and would also be <em>measured</em>:
     * it would cascade, lay out and re-shape every frame, inside the very numbers it is reporting. A
     * second frame costs one full-screen composite and touches nothing the tree owns, which is what makes
     * the reading honest. It is also literally what "after all UI is rendered" means — the tree's own
     * frame has already resolved and composited by the time this begins.</p>
     */
    private void paintOverlay(HarnessContext ctx) {
        CgFontFamily family = overlayFont();
        if (family == null) return;

        String top = String.format("%.0f FPS   frame %.1fms   worst/3s %.1fms", fps, lastMs, worstMs);
        String bottom = flowEnabled
                ? String.format("t=%.1fs   %s   worst since %.1fms", elapsed, stage, stageWorstMs)
                : String.format("t=%.1fs", elapsed);

        CgUiPaintContext paint = CgUiPaintContext.getInstance();
        paint.beginFrame(ctx.getScreenWidth(), ctx.getScreenHeight());
        // A PLATE UNDER IT, because the thing being watched is an editor and white on light grey is
        // unreadable at exactly the moment somebody is squinting at a number.
        paint.fillRect(6, 6, 360, 50, 0xB0000000);
        paint.text().draw().at(14, 12).text(top).color(colourFor(lastMs)).family(family).submit();
        paint.text().draw().at(14, 32).text(bottom).color(0xFFB0B0B0).family(family).submit();
        paint.endFrame();
    }

    /**
     * The counter's own font, resolved once.
     *
     * <p><b>Resolved in {@link #init} rather than on first paint</b>, because {@code FontFamilyCache}
     * loads and parses the file on the first call and it was measured at <b>70.5ms on a startup frame</b>
     * — charged, by this scene's own summary, to the gesture it was supposed to be reporting on. A probe
     * that shows up in its own measurement is worse than no probe: the number is wrong and it is wrong in
     * the direction that invents findings.</p>
     */
    private CgFontFamily overlayFont() {
        if (overlayFontResolved) return overlayFont;
        overlayFontResolved = true;
        try {
            overlayFont = FontFamilyCache.resolve(List.of(OVERLAY_FONT), 16);
        } catch (RuntimeException noFont) {
            overlayFont = null;
        }
        return overlayFont;
    }

    private CgFontFamily overlayFont;
    private boolean overlayFontResolved;

    /** Green under the 120Hz budget, amber to 60, red below — so a drop reads without being parsed. */
    private static int colourFor(float ms) {
        if (ms <= 8.4f) return 0xFF7ED97E;
        if (ms <= 16.8f) return 0xFFE0C060;
        return 0xFFE06060;
    }

    @Override
    public void dispose() {
        if (flowEnabled) printFlowSummary();
        // AND THE SCRIPTED RUN WRITES NO SESSION -- the other half of the rule above. Restoring one
        // would be harmless if nothing ever wrote one; it is the write that makes run N+1 differ from
        // run N, so the flow leaves the record exactly as the last hand-driven run left it.
        if (!flowEnabled && editor != null && uiWindow != null) {
            editor.saveSession(HarnessWorkspace.PROJECT_ID,
                    (int) uiWindow.getScreenWidth(), (int) uiWindow.getScreenHeight());
            editor.savePreferences();
        }
        if (editor != null) Disposer.dispose(editor);
        uiWindow = null;
    }

    @Override
    public boolean isRunning() {
        // The scripted run ends by itself; a hand-driven one never does. A measurement nobody has to
        // close is one that can be run from a script, which is the whole difference being bought here.
        return !flowEnabled || elapsed < RUN_FOR;
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return flowEnabled;
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
