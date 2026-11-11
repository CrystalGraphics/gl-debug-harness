package com.crystalgraphics.harness.ui;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgui.app.machine.MachineExample;
import com.crystalgui.core.storage.CrystalGuiStorage;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.trace.UiTrace;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceHost;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.net.window.WindowProtocol;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.language.js.JsLanguage;
import com.crystalgui.language.run.ScriptPolicy;
import com.crystalgui.text.syntax.LanguageRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The harness as a single-player game's server: CrystalGraphics' network over a channel looping back in this process,
 * one player joined and the client connected, the server serving the scratch project through {@link WorkspaceHost}.
 * So the desktop reaches its workspace through {@code CgNetwork.client()}, and the editor's files and their watches go
 * over the protocol exactly as in a world.
 *
 * <pre>{@code
 * HarnessServer server = new HarnessServer();   // once, before the desktop is built
 * server.tick(delta);                           // every frame, on the render thread: the server's 20 Hz tick
 * }</pre>
 *
 * <ul>
 *   <li>The scratch project is {@code crystalgui/projects/scratch/} under the harness's directory, seeded once from
 *       {@code harness/workspace/} and never overwritten: it is the user's to edit.</li>
 *   <li>Scripts run under {@link #POLICY_PROPERTY}'s class filter, {@code unsafe,java.io.File} unless told otherwise.</li>
 * </ul>
 */
public final class HarnessServer {

    /** The scratch project's id, which a saved resource names it by. */
    public static final String PROJECT_ID = "harness.scratch";

    /** Overrides the script class filter: a prefix list, {@code unsafe} for {@link ScriptPolicy#UNSAFE}, or {@code none}. */
    public static final String POLICY_PROPERTY = "cgui.harness.scriptPolicy";

    /**
     * What the harness refuses unless told otherwise. {@code java.io.File} and not {@code java.io}: {@code System.out} is
     * a {@code java.io.PrintStream}, so refusing the package refuses printing.
     */
    private static final String DEFAULT_POLICY = "unsafe,java.io.File";

    /** The one player this process has: its handle on the channel, and its profile id. */
    private static final Object PLAYER = new Object();
    private static final UUID PLAYER_ID = new UUID(0L, 1L);

    private static final float WORLD_TICK = 1f / 20f;

    private float worldClock;
    private final WorkspaceHost workspace;

    public HarnessServer() {
        // Paid here rather than on the keystroke that opens the first editor
        LanguageRegistry.bootstrap();
        applyScriptPolicy();
        Path root = seedScratchProject();
        // ALLOW_ALL: there is no other player to guard against, and DENY_ALL is the default so a real host chooses
        workspace = new WorkspaceHost(PROJECT_ID, "Scratch", new WorkspaceHost.Host() {
            @Override public Path root() { return root; }
            @Override public WorkspacePermission permission() { return WorkspacePermission.ALLOW_ALL; }
            @Override public WorkspaceActor actorFor(Object peer) { return WorkspaceActor.LOCAL; }
        });
        // Before the server end opens: a contribution binds to the connections opened after it
        workspace.contribute();
        // CgUiCommonLifecycle.onInit, by hand: preInit mounts every asset root, which the scenes that scan a kit
        // themselves then register a second time.
        WindowProtocol.register();
        MachineExample.registerServer();
        // After the contributions, which bind to the connections opened after them
        CgNetwork.install(new Loopback(), player -> player == PLAYER ? PLAYER_ID : null);
        CgNetwork.playerJoined(PLAYER_ID, "harness", () -> PLAYER);
        CgNetwork.clientConnected();
    }

    /**
     * The server's tick and the workspace's: the server's connection sends at its 20 Hz tick, the client's receives at
     * the runtime's client tick, and file changes fan out.
     */
    public void tick(float deltaSeconds) {
        // The world's 20 Hz, as a server's tick: what listeners registered with onServerTick run on
        worldClock += deltaSeconds;
        while (worldClock >= WORLD_TICK) {
            worldClock -= WORLD_TICK;
            CgEngineLifecycle.serverTick();
        }
        try (CgTrace.Zone ignored = CgTrace.zone(UiTrace.FRAME, "workspace:hubUpdate")) {
            workspace.tick(deltaSeconds);
        }
    }

    /**
     * On by default: a filter nobody switches on is a filter nobody tests. Java refuses a script as a whole file before
     * it starts, JavaScript one reach at a time; {@code SandboxTest.java} and {@code .js} show both.
     *
     * <pre>
     *   -Dcgui.harness.scriptPolicy=none                     # off
     *   -Dcgui.harness.scriptPolicy=unsafe                   # ScriptPolicy.UNSAFE only
     *   -Dcgui.harness.scriptPolicy=unsafe,java.io,java.net  # and these too
     * </pre>
     */
    private static void applyScriptPolicy() {
        String requested = System.getProperty(POLICY_PROPERTY, DEFAULT_POLICY).trim();
        if (requested.isEmpty() || requested.equalsIgnoreCase("none") || requested.equalsIgnoreCase("off")) {
            System.out.println("[harness] script class filter OFF (-D" + POLICY_PROPERTY + "=none)");
            return;
        }
        List<String> denied = new ArrayList<>();
        for (String entry : requested.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.equalsIgnoreCase("unsafe")) denied.addAll(ScriptPolicy.UNSAFE);
            else if (!trimmed.isEmpty()) denied.add(trimmed);
        }
        if (denied.isEmpty()) return;
        // Both languages together: a filter on one engine and not the other is not a filter
        ScriptPolicy policy = ScriptPolicy.denying(denied);
        JavaLanguage.restrictTo(policy);
        JsLanguage.restrictTo(policy);
        System.out.println("[harness] script class filter on: " + policy);
    }

    /** {@code crystalgui/projects/scratch/}, outside {@code harness-output/}, which a clean deletes. */
    private static Path seedScratchProject() {
        Path root = CrystalGuiStorage.projectsIn(Paths.get(".").toAbsolutePath().normalize()).resolve("scratch").normalize();
        try {
            Files.createDirectories(root.resolve("src"));
            writeIfAbsent(root.resolve("README.md"), "# Scratch project\n\nEdit these on disk, then save here to see a conflict.\n");
            writeIfAbsent(root.resolve("src/Main.java"), "public class Main {\n    public static void main(String[] args) {\n"
                    + "        System.out.println(\"hello\");\n    }\n}\n");
            writeIfAbsent(root.resolve("src/notes.txt"), "one\ntwo\nthree\n");
            // Opens as a checklist where a workbench registers `.notes`: a wired document kind, not one that compiles
            writeIfAbsent(root.resolve("todo.notes"), "[x] read the example\nwrite a document kind\ntick this off\n");
            // The editor's fixtures: colours, what runs, what a locked-down deployment refuses, doc comments, and the
            // library viewer's three Ctrl+B routes
            for (String name : List.of("Main.js", "RunTest.js", "SandboxTest.java", "SandboxTest.js", "DocShowcase.java",
                    "DocShowcase.js", "Viewer.java")) {
                copyIfAbsent(root.resolve("src/" + name), "harness/workspace/" + name);
            }
            // Cross-file resolution, under the source roots that turn a path into a package: Main imports Greeter
            // across packages, Greeter reaches Formatter with no import, Main depends on Formatter without naming it
            copyIfAbsent(root.resolve("src/main/java/com/example/Main.java"), "harness/workspace/imports/Main.java");
            copyIfAbsent(root.resolve("src/main/java/com/example/util/Greeter.java"), "harness/workspace/imports/Greeter.java");
            copyIfAbsent(root.resolve("src/main/java/com/example/util/Formatter.java"), "harness/workspace/imports/Formatter.java");
            // The same chain in JavaScript, so the two engines compare on one workspace
            copyIfAbsent(root.resolve("src/main/js/App.js"), "harness/workspace/imports/App.js");
            copyIfAbsent(root.resolve("src/main/js/util/Greeter.js"), "harness/workspace/imports/Greeter.js");
            copyIfAbsent(root.resolve("src/main/js/util/Formatter.js"), "harness/workspace/imports/Formatter.js");
        } catch (IOException e) {
            throw new IllegalStateException("could not create the scratch project at " + root, e);
        }
        return root;
    }

    /** A channel whose two ends are this process: what the client sends arrives from the player, and back. */
    private static final class Loopback implements CgNetworkChannel {

        private volatile BiConsumer<Object, byte[]> inbound = (sender, frame) -> { };

        @Override public int maxFrameBytes() { return 1 << 20; }
        @Override public void sendToServer(byte[] frame) { inbound.accept(PLAYER, frame); }
        @Override public void sendToPlayer(Object player, byte[] frame) { inbound.accept(null, frame); }
        @Override public void setInboundHandler(BiConsumer<Object, byte[]> handler) { inbound = handler; }
        @Override public boolean isAvailable() { return true; }
    }

    private static void writeIfAbsent(Path file, String content) throws IOException {
        if (!Files.exists(file)) Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** A missing seed is reported, never fatal: the scratch project is a convenience. */
    private static void copyIfAbsent(Path file, String resource) throws IOException {
        if (Files.exists(file)) return;
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (InputStream stream = HarnessServer.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                System.err.println("[harness] no seed resource " + resource + "; skipping " + file);
                return;
            }
            Files.write(file, stream.readAllBytes());
        }
    }
}
