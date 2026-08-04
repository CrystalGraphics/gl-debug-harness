package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgui.fs.CgFileEntry;
import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.ProjectInfo;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.ui.elements.tree.TreeDataSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link TreeDataSource} over an asynchronous workspace client.
 *
 * <p>Answers from what has arrived and requests what has not. A directory whose listing is still in
 * flight reports no children — which is honest, and resolves itself when the response lands and the
 * view is refreshed. Every remote file browser works this way; the alternative is blocking the render
 * thread on a round trip.</p>
 *
 * <p><b>Extracted from {@code CgUiWorkspaceScene}</b> when a second scene needed it. It was always
 * self-contained — it takes a client and nothing else — so this is a pure move, and sharing it matters
 * more than the file count: the request-once / retry-on-failure / directories-first logic in here is
 * exactly the kind that gets fixed in one copy and left wrong in the other.</p>
 */
final class HarnessWorkspaceTree implements TreeDataSource<CgPath> {

    private final WorkspaceClient<?> client;
    private final List<CgPath> roots = new ArrayList<>();
    private final Map<String, String> projectNames = new HashMap<>();
    private final Map<CgPath, List<CgPath>> children = new HashMap<>();
    private final Set<CgPath> directories = new HashSet<>();
    private final Set<CgPath> requested = new HashSet<>();
    private volatile boolean dirty;

    HarnessWorkspaceTree(WorkspaceClient<?> client) {
        this.client = client;
    }

    private String failure;

    /** The last failure, so the status line can show it. Null when nothing has gone wrong. */
    String failure() {
        return failure;
    }

    void loadProjects(Runnable onLoaded) {
        client.projects(infos -> {
            roots.clear();
            for (ProjectInfo info : infos) {
                CgPath root = info.root();
                roots.add(root);
                directories.add(root);
                projectNames.put(info.id(), info.displayName());
            }
            dirty = true;
            onLoaded.run();
        }, error -> {
            // REPORTED, not swallowed. The first version of this was an empty lambda with a comment
            // claiming the status line covered it -- so when the call was being dropped outright, the
            // scene showed an empty tree and no reason for it.
            failure = "projects failed: " + error.code();
            dirty = true;
        });
    }

    String displayNameOf(CgPath projectRoot) {
        return projectNames.getOrDefault(projectRoot.project(), projectRoot.project());
    }

    boolean isDirectory(CgPath path) {
        return directories.contains(path);
    }

    /** True once since the last call — the frame uses it to decide whether to refresh the view. */
    boolean drainRefresh() {
        if (!dirty) return false;
        dirty = false;
        return true;
    }

    @Override
    public List<CgPath> roots() {
        return roots;
    }

    @Override
    public List<CgPath> children(CgPath parent) {
        List<CgPath> known = children.get(parent);
        if (known != null) return known;
        request(parent);
        return List.of();
    }

    @Override
    public boolean hasChildren(CgPath item) {
        // Every directory claims children, even before its listing arrives -- otherwise it would
        // render as a leaf and there would be nothing to click to trigger the request.
        return directories.contains(item);
    }

    private void request(CgPath directory) {
        if (!requested.add(directory)) return;
        client.list(directory, entries -> {
            List<CgPath> paths = new ArrayList<>(entries.size());
            for (CgFileEntry entry : entries) {
                CgPath child = directory.resolve(entry.name());
                paths.add(child);
                if (entry.isDirectory()) directories.add(child);
            }
            paths.sort((x, y) -> {
                boolean dx = directories.contains(x), dy = directories.contains(y);
                if (dx != dy) return dx ? -1 : 1;      // directories first, as every file tree does
                return x.name().compareToIgnoreCase(y.name());
            });
            children.put(directory, paths);
            dirty = true;
        }, failure -> {
            // Allow a retry rather than latching the failure -- the listing may have failed because
            // the directory was being written to.
            requested.remove(directory);
            if (failure.error() != CgFileError.FILE_NOT_FOUND) children.put(directory, List.of());
        });
    }
}
