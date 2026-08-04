package io.github.somehussar.crystalgraphics.harness.scene.ui;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private final HarnessWorkspaceTree tree;

    /** Seconds until the next watcher poll. Every poll stats each watched file, so a per-frame poll would
     * be a stat storm at 60 Hz for no benefit a human could perceive. The cadence is the HOST's call. */
    private float untilPoll;

    private boolean projectsRequested;

    HarnessWorkspace() {
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
        tree = new HarnessWorkspaceTree(client);
    }

    WorkspaceClient<Object> client() {
        return client;
    }

    HarnessWorkspaceTree tree() {
        return tree;
    }

    void onFileChanged(Consumer<WorkspaceClient.FileChanged> listener) {
        client.onFileChanged(listener::accept);
    }

    /**
     * One network tick, plus the watcher poll when it is due.
     *
     * <p>Called once a frame. <b>Projects are requested here, not at construction</b>: the client's window
     * id is {@code -1} until {@code OpenWindow} arrives, and the server discards any packet addressed to
     * another window — so a call made before then is thrown away with no error at all, and the scene shows
     * an empty tree with nothing to explain it.</p>
     *
     * @return true when the tree changed and the view should be refreshed
     */
    boolean pump(float deltaSeconds, Runnable onProjectsLoaded) {
        fromServer.deliver();
        fromClient.deliver();
        session.tick();
        server.tick();

        if (!projectsRequested && session.windowId() >= 0) {
            projectsRequested = true;
            tree.loadProjects(onProjectsLoaded);
        }

        untilPoll -= deltaSeconds;
        if (untilPoll <= 0f) {
            untilPoll = 0.5f;
            rpc.pollAndNotify((method, args) -> server.call(method, args, null, null), PlainOps.INSTANCE);
        }
        return tree.drainRefresh();
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
        } catch (IOException e) {
            throw new IllegalStateException("could not create the scratch project at " + root, e);
        }
        return root;
    }

    private static void writeIfAbsent(Path file, String content) throws IOException {
        if (!Files.exists(file)) Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
