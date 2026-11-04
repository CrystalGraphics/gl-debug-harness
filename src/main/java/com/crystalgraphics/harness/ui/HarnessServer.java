package com.crystalgraphics.harness.ui;

import com.crystalgraphics.net.CgInMemoryTransport;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.net.protocol.CgProtocols;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.storage.StorageLayout;
import com.crystalgui.core.trace.UiTrace;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceHost;
import com.crystalgui.fs.server.WorkspacePermission;
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

/**
 * The harness as a single-player game's server: one in-memory connection, its server end serving the scratch project
 * through {@link WorkspaceHost}, its client end the one {@link HarnessUi} hands the desktop. So the desktop's
 * workspace, the editor's files and their watches go over the protocol exactly as in a world.
 *
 * <pre>{@code
 * HarnessServer server = new HarnessServer();   // once, before the desktop is built
 * services.connection() -> server.client();
 * server.tick(delta);                           // every frame, on the render thread
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

    /** The one player this process has. */
    private static final Object PEER = new Object();

    private final WorkspaceHost workspace;
    private final CgProtocolConnection<Object> serverEnd;
    private final CgProtocolConnection<Object> clientEnd;

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
        CgInMemoryTransport<Object>[] link = CgInMemoryTransport.pair();
        // Each end's pump delivers what it sent, so a tick moves, drains and expires in one call
        serverEnd = CgProtocols.open(link[0], CgPlainOps.INSTANCE, link[0]::deliver, PEER);
        clientEnd = CgProtocols.open(link[1], CgPlainOps.INSTANCE, link[1]::deliver, null);
    }

    /** The client's end, as a game's connection to its server. */
    public CgProtocolConnection<Object> client() {
        return clientEnd;
    }

    /** One network tick and the workspace's: what both ends sent is delivered, and file changes fan out. */
    public void tick(float deltaSeconds) {
        try (CgTrace.Zone ignored = CgTrace.zone(UiTrace.FRAME, "workspace:transport")) {
            serverEnd.tick();
            clientEnd.tick();
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
        Path root = StorageLayout.projectsIn(Paths.get(".").toAbsolutePath().normalize()).resolve("scratch").normalize();
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
