package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgui.language.LanguageStack;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.language.js.JsLanguage;
import com.crystalgui.language.run.ScriptPolicy;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.LocalFileSystem;
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
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.UIElement;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Both halves of a P6.1.10 workspace, in one process — server, client, and the transport between them.
 *
 * <p>Exists so a scene that wants real files does not have to reproduce the wiring. <b>Nothing here
 * shortcuts the protocol:</b> the client holds no reference to the filesystem, only a
 * {@link WorkspaceClient}, exactly as a client across a Minecraft connection would. That is the whole
 * value of the arrangement — a scene using this is exercising the real RPC path, not a local file API
 * wearing its clothes.</p>
 *
 * <h3>The scratch project is a real directory on disk</h3>
 *
 * <p>{@code workspace/} beside the harness, seeded once and never overwritten. Deliberately <b>not</b>
 * under {@code harness-output/}, which {@code gradle clean} deletes: everything in there is a regenerable
 * artifact, and this is the user's own scratch project. Losing it to an unrelated clean would be losing
 * work.</p>
 */
final class HarnessWorkspace {

    static final String PROJECT_ID = "harness.scratch";

    private final InMemoryTransport<Object> fromServer;
    private final InMemoryTransport<Object> fromClient;
    private final ServerUiSession<Object> server;
    private final WorkspaceRpc<Object> rpc;
    private final ClientUiSession<Object> session;
    private final WorkspaceClient<Object> client;

    /** Seconds until the next watcher poll. Every poll stats each watched file, so a per-frame poll would
     * be a stat storm at 60 Hz for no benefit a human could perceive. The cadence is the HOST's call. */
    private float untilPoll;

    HarnessWorkspace() {
        // BEFORE anything opens a document, because LanguageRegistry is consulted when an editor is built
        // and a file already open would keep whichever tokenizer it was given. core/ ships word-list
        // lexers so it can load with no natives; this puts the real parsers in front of them, which is
        // what makes a declaration distinguishable from a call and a constant from an identifier.
        //
        // The grammars, ECJ and Rhino, in one call. This used to be three blocks here and three more in
        // the Minecraft client, and the two copies had already diverged on the one thing that matters:
        // this one caught nothing, so a band that is present but UNOPENABLE threw NoClassDefFoundError
        // straight out of the constructor. Which engines exist and what a missing one means are facts
        // about language/, so they live there now -- a host only says when.
        //
        // Reports rather than throws where the bands are not staged (`./gradlew :language:stageEngines`,
        // which runHarness depends on). That is a legitimate environment: the editor colours and does
        // not analyse.
        LanguageStack.registerAll();

        applyScriptPolicy();

        Path root = seedScratchProject();
        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject(PROJECT_ID, "Scratch", root)));

        // ALLOW_ALL, and worth being explicit about: there is no player here to guard against, and the
        // default is DENY_ALL precisely so a real host has to make this choice on purpose.
        WorkspaceService service = new WorkspaceService(
                registry, new LocalFileSystem(registry), WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        fromServer = pair[0];
        fromClient = pair[1];

        server = new ServerUiSession<>(1, new UIElement(), fromServer, PlainOps.INSTANCE);
        rpc = new WorkspaceRpc<>(service, WorkspaceActor.LOCAL);
        rpc.installOn(server::onCall);
        server.open();

        session = new ClientUiSession<>(fromClient, PlainOps.INSTANCE);
        client = new WorkspaceClient<>(session, PlainOps.INSTANCE);
    }

    WorkspaceClient<Object> client() {
        return client;
    }

    void onFileChanged(Consumer<WorkspaceClient.FileChanged> listener) {
        client.onFileChanged(listener::accept);
    }

    /**
     * True once the session has a window id.
     *
     * <p>Before that the server discards every packet addressed to another window, so a call made too
     * early is thrown away with <b>no error at all</b> -- and the tree simply stays empty with nothing to
     * explain it. Whoever asks for the project list has to wait for this.</p>
     */
    boolean isConnected() {
        return session.windowId() >= 0;
    }

    /** One network tick, plus the watcher poll when it is due. Called once a frame. */
    void pump(float deltaSeconds) {
        fromServer.deliver();
        fromClient.deliver();
        session.tick();
        server.tick();

        untilPoll -= deltaSeconds;
        if (untilPoll <= 0f) {
            untilPoll = 0.5f;
            rpc.pollAndNotify((method, args) -> server.call(method, args, null, null), PlainOps.INSTANCE);
        }
    }

    void read(CgPath path, Consumer<WorkspaceClient.Document> onLoaded, Consumer<String> onFailure) {
        client.read(path, onLoaded::accept, failure -> onFailure.accept(failure.code()));
    }

    /** Saves, reporting a conflict distinctly — a stale write is the one failure with a recovery path
     * rather than an error message. */
    void save(CgPath path, String text, Runnable onSaved, Consumer<Boolean> onFailure) {
        client.save(path, text.getBytes(StandardCharsets.UTF_8),
                etag -> onSaved.run(),
                failure -> onFailure.accept(failure.isConflict()));
    }

    /** Overrides the default filter — a prefix list, or {@code none} to switch it off. @see #applyScriptPolicy */
    public static final String POLICY_PROPERTY = "cgui.harness.scriptPolicy";

    /**
     * What the harness refuses unless told otherwise — {@code UNSAFE} plus file access.
     *
     * <p>{@code unsafe} is {@link ScriptPolicy#UNSAFE}: reflection, method handles, {@code ClassLoader},
     * {@code Runtime}, {@code ProcessBuilder}, {@code java.security} and the internals.</p>
     *
     * <p><b>{@code java.io.File} and not {@code java.io}</b>, which was the first spelling and was
     * unusable: {@code System.out} is a {@code java.io.PrintStream}, so refusing the package refuses
     * <em>printing</em> — every script that logs anything, which in a harness is all of them. The class
     * a demonstration actually wants is the one that touches the disk. It is a reminder that a denial is
     * a veto with no way to punch a hole in it: "{@code java.io} except {@code PrintStream}" cannot be
     * spelled, and should not be, so the entry has to be the narrow one.</p>
     */
    private static final String DEFAULT_POLICY = "unsafe,java.io.File";

    /**
     * Applies the script class filter — <b>on by default</b>, at {@link #DEFAULT_POLICY}.
     *
     * <p>On rather than opt-in because a filter nobody switches on is a filter nobody tests, and because
     * the harness is the only place this stack is exercised end to end. A deployment that means to run
     * scripts unguarded should say so; so should this one.</p>
     *
     * <pre>
     *   -Dcgui.harness.scriptPolicy=none                     # off
     *   -Dcgui.harness.scriptPolicy=unsafe                   # ScriptPolicy.UNSAFE only
     *   -Dcgui.harness.scriptPolicy=unsafe,java.io,java.net  # and these too
     * </pre>
     *
     * <p>{@code unsafe} expands to {@link ScriptPolicy#UNSAFE}. Anything else in the list is a package or
     * class prefix. <b>Both languages are restricted together</b>, because a filter that applies to one
     * engine and not the other is not a filter, it is a note about which engine somebody remembered.</p>
     *
     * <p><b>What being on by default costs, stated rather than discovered:</b> Java refuses a script as a
     * WHOLE FILE, before it starts, so one refused reach takes the file with it. {@code RunTest.java} has
     * a reflection section, so under the default it does not run at all — where {@code RunTest.js} would
     * lose only the reaches themselves, because Rhino's shutter is asked per access. That asymmetry is
     * real and is what the {@code SandboxTest} pair exists to show; {@code =none} is the way back.</p>
     */
    private static void applyScriptPolicy() {
        String requested = System.getProperty(POLICY_PROPERTY, DEFAULT_POLICY);
        if (requested.trim().isEmpty() || requested.trim().equalsIgnoreCase("none")
                || requested.trim().equalsIgnoreCase("off")) {
            System.out.println("[harness] script class filter OFF (-D" + POLICY_PROPERTY + "=none)");
            return;
        }

        List<String> denied = new ArrayList<>();
        for (String entry : requested.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.equalsIgnoreCase("unsafe")) {
                denied.addAll(ScriptPolicy.UNSAFE);
            } else {
                denied.add(trimmed);
            }
        }
        if (denied.isEmpty()) return;

        ScriptPolicy policy = ScriptPolicy.denying(denied);
        JavaLanguage.restrictTo(policy);
        JsLanguage.restrictTo(policy);
        System.out.println("[harness] script class filter on: " + policy);
    }

    private static Path seedScratchProject() {
        Path root = Paths.get("workspace").toAbsolutePath().normalize();
        try {
            Files.createDirectories(root.resolve("src"));
            writeIfAbsent(root.resolve("README.md"),
                    "# Scratch project\n\nEdit these on disk, then save here to see a conflict.\n");
            writeIfAbsent(root.resolve("src/Main.java"),
                    "public class Main {\n    public static void main(String[] args) {\n"
                            + "        System.out.println(\"hello\");\n    }\n}\n");
            writeIfAbsent(root.resolve("src/notes.txt"), "one\ntwo\nthree\n");
            // THE JAVASCRIPT FIXTURE, from a resource rather than from a string literal here. It is a
            // page long and grows a section per M10 milestone, so inlining it would put a document
            // nobody can read inside a method about directory setup -- and, worse, would make the copy
            // that ships and the copy under review two different things. `writeIfAbsent` still applies:
            // once it is on disk it is the user's scratch file and a rebuild must not overwrite it.
            copyIfAbsent(root.resolve("src/Main.js"), "harness/workspace/Main.js");
            // AND THE ONE FOR RUNNING. `Main.js` is about what the EDITOR knows before anything runs;
            // this is a transcript of what the RUNTIME does, which is a different question and fails in
            // different ways -- an engine can look entirely correct in an editor and die at its first
            // `Java.type`. Same pairing `RunTest.java` has with `Main.java`.
            copyIfAbsent(root.resolve("src/RunTest.js"), "harness/workspace/RunTest.js");
            // AND THE PAIR WRITTEN TO BE REFUSED. Both reach for classes a locked-down deployment would
            // not allow, and both are inert until the filter is switched on -- see applyScriptPolicy.
            // They are a PAIR because the two engines refuse differently and that difference is the
            // thing worth looking at: Java is refused as a whole file before it starts, JavaScript one
            // reach at a time as the shutter is asked.
            copyIfAbsent(root.resolve("src/SandboxTest.java"), "harness/workspace/SandboxTest.java");
            copyIfAbsent(root.resolve("src/SandboxTest.js"), "harness/workspace/SandboxTest.js");
            // AND THE PAIR FOR DOCUMENTATION. `Main.java` and `Main.js` are the fixtures for COLOURS;
            // these are for what Mod+Q draws. Each holds every construct its language's doc comments
            // allow -- including the ones nothing here reads yet, marked PARITY, so a gap is visible
            // rather than merely absent. The .js half also carries the Java-interop section, since a
            // Java member's documentation reaches JavaScript through a bridge that can break on its own.
            copyIfAbsent(root.resolve("src/DocShowcase.java"), "harness/workspace/DocShowcase.java");
            copyIfAbsent(root.resolve("src/DocShowcase.js"), "harness/workspace/DocShowcase.js");
            // AND THE ONE FOR THE LIBRARY VIEWER. Three Ctrl+B targets taking three different routes --
            // a JDK type with attached source, a library type shipping none so it decompiles, and a
            // nested type that lives in its outer class file. One file, so which of the three works is
            // readable in one pass rather than assembled from three.
            copyIfAbsent(root.resolve("src/Viewer.java"), "harness/workspace/Viewer.java");

            // AND THE CROSS-FILE FIXTURE (M15), which is the first thing here laid out as a real project.
            //
            // Under `src/main/java` on purpose: a source root is what turns a path into a package, and
            // the project index derives every qualified name from the path. Seeded into `src/` like the
            // files above, these would be OUTSIDE every declared root -- so the index would decline to
            // name them, nothing would resolve, and the fixture would silently prove the opposite of what
            // it is for.
            //
            // Three files, three ways of reaching another one: Main imports Greeter across packages,
            // Greeter reaches Formatter with no import at all, and Main depends on Formatter without
            // ever naming it. @see Main.java's own comment for what should be true.
            copyIfAbsent(root.resolve("src/main/java/com/example/Main.java"),
                    "harness/workspace/imports/Main.java");
            copyIfAbsent(root.resolve("src/main/java/com/example/util/Greeter.java"),
                    "harness/workspace/imports/Greeter.java");
            copyIfAbsent(root.resolve("src/main/java/com/example/util/Formatter.java"),
                    "harness/workspace/imports/Formatter.java");
        } catch (IOException e) {
            throw new IllegalStateException("could not create the scratch project at " + root, e);
        }
        return root;
    }

    private static void writeIfAbsent(Path file, String content) throws IOException {
        if (!Files.exists(file)) Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Seeds a file from a classpath resource, once.
     *
     * <p>A missing resource is reported and not fatal, because the scratch project is a convenience:
     * losing one fixture must not stop the harness booting, and a stack trace here would look like a
     * rendering failure. @see #writeIfAbsent</p>
     */
    private static void copyIfAbsent(Path file, String resource) throws IOException {
        if (Files.exists(file)) return;
        // THE DIRECTORIES TOO. Every seed until M15 landed directly in `src/`, so this never had to make
        // one -- and the first fixture with a package path failed on `Files.write` with a
        // NoSuchFileException naming the file rather than the directory that was missing.
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (InputStream stream =
                     HarnessWorkspace.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                System.err.println("[harness] no seed resource " + resource + "; skipping " + file);
                return;
            }
            Files.write(file, stream.readAllBytes());
        }
    }
}
