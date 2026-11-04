package com.crystalgraphics.harness.scene.test;

import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.Resource;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.text.UIText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * kit-cues' cue picker, an application on the harness's desktop: a window with a column per namespace, each its cues
 * as a tree of their paths' folders. A press on a cue plays it; on a folder, folds it. The cue playing is lit, and the
 * folders over its path in every column. Styled by {@code harness:kit-cues}, which Ctrl+R re-reads.
 *
 * <pre>{@code
 * CueSelector picker = new CueSelector(ids, index -> picked = index);   // ids as "ns:path/to/cue"
 * ctx.getUi().bind(CgKeyCodes.KEY_LMENU, picker.kind());
 * HostSession.session().inDocument(() -> picker.current(3));           // after N or B
 * }</pre>
 *
 * <ul>
 *   <li>Its window, {@link #current} and {@code picked} run on the document's thread.</li>
 * </ul>
 */
final class CueSelector {

    private static final String STYLE = "harness:kit-cues";
    private static final String WINDOW_KEY = "harness:kit-cues";

    /** A folder of a cue path, or a cue; or both, a cue with cues under it. */
    private static final class Node {
        final String name, path;
        final List<Node> children = new ArrayList<>();
        int cue = -1;

        Node(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    private final List<String> ids;
    private final IntConsumer picked;
    private final Map<String, List<Node>> roots = new LinkedHashMap<>();
    private final List<TreeView<Node>> trees = new ArrayList<>();
    private int current;
    /** Whether its sheet is on the document already: a fresh window each launch, one sheet. */
    private boolean styled;
    private final ApplicationKind kind = ApplicationKind.of("harness:kit-cues", "Cues")
            .icon("harness:cues")
            .keywords("cues", "hits", "kit")
            .singleInstance()
            .standalone()
            .launch(context -> new Instance(context.desktop()));

    /** @param ids the cues, as {@code namespace:path}, in the order {@code picked} numbers them */
    CueSelector(List<String> ids, IntConsumer picked) {
        this.ids = ids;
        this.picked = picked;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            int colon = id.indexOf(':');
            List<Node> level = roots.computeIfAbsent(id.substring(0, colon), ns -> new ArrayList<>());
            Node node = null;
            String path = "";
            for (String part : id.substring(colon + 1).split("/")) {
                path = path.isEmpty() ? part : path + "/" + part;
                node = null;
                for (Node n : level) if (n.name.equals(part)) node = n;
                if (node == null) level.add(node = new Node(part, path));
                level = node.children;
            }
            node.cue = i;
        }
        for (List<Node> level : roots.values()) sort(level);
    }

    private static void sort(List<Node> level) {
        level.sort((a, b) -> a.name.compareTo(b.name));
        for (Node n : level) sort(n.children);
    }

    private static void folders(List<Node> level, List<Node> out) {
        for (Node n : level) {
            if (n.children.isEmpty()) continue;
            out.add(n);
            folders(n.children, out);
        }
    }

    /** The picker as an application: one window a desktop. */
    ApplicationKind kind() {
        return kind;
    }

    /** The window's content: a column per namespace and the hint under them. */
    private UIElement build() {
        trees.clear();
        UIElement screen = new UIElement();
        screen.addClass("cues");
        UIElement columns = new UIElement();
        columns.addClass("cues-columns");
        for (Map.Entry<String, List<Node>> ns : roots.entrySet()) columns.append(column(ns.getKey(), ns.getValue()));
        UIText hint = new UIText("Click a cue to play it  |  a folder to fold it");
        hint.addClass("cues-hint");
        screen.append(columns, hint);
        return screen;
    }

    private UIElement column(String namespace, List<Node> top) {
        UIElement panel = new UIElement();
        panel.addClass("cues-panel");
        UIText header = new UIText(namespace + "/cues");
        header.addClass("cues-header");
        TreeView<Node> tree = new TreeView<>(new TreeDataSource<>() {
            @Override public List<Node> roots() { return top; }
            @Override public List<Node> children(Node parent) { return parent.children; }
            @Override public boolean hasChildren(Node item) { return !item.children.isEmpty(); }
        });
        tree.addClass("cues-tree");
        tree.setItemHeight(18f);
        tree.setRenderer(new Rows(tree));
        List<Node> open = new ArrayList<>();
        folders(top, open);
        tree.setExpandedItems(open);
        trees.add(tree);
        panel.append(header, tree);
        return panel;
    }

    /** Lights cue {@code index} and the folders over its path. */
    void current(int index) {
        current = index;
        for (TreeView<Node> tree : trees) tree.refresh();
    }

    private boolean lit(Node node) {
        String path = ids.get(current).substring(ids.get(current).indexOf(':') + 1);
        return !node.children.isEmpty() && path.startsWith(node.path + "/");
    }

    private static final class Row extends UIElement {
        final UIElement twisty = new UIElement();
        final UIText label = new UIText("");
        Node node;

        Row() {
            addClass("cues-row");
            twisty.addClass("__twisty__");
            append(twisty, label);
        }
    }

    private final class Rows implements TreeRenderer<Node> {
        private final TreeView<Node> tree;

        Rows(TreeView<Node> tree) {
            this.tree = tree;
        }

        @Override
        public UIElement createTemplate() {
            Row row = new Row();
            // A press acts, as a game's list does: a folder folds, a cue plays
            row.onMouseDown.attachListener((element, event) -> {
                Node node = row.node;
                if (node == null) return;
                if (!node.children.isEmpty()) tree.requestToggle(node);
                else picked.accept(node.cue);
                event.stopPropagation();
            }, false, true);
            return row;
        }

        @Override
        public void bind(Node item, TreeRow<Node> row, int index, UIElement template) {
            Row view = (Row) template;
            view.node = item;
            view.label.setText(item.name);
            view.toggleClass("cues-current", item.cue == current && item.cue >= 0);
            view.toggleClass("cues-lit", lit(item));
        }
    }

    /** The picker's window on a desktop; closing it quits the picker, and the next launch opens a fresh one. */
    private final class Instance implements Application {

        private final WindowFrame window;

        Instance(Desktop desktop) {
            if (!styled) desktop.document().styles().addStylesheet(StyleSheetRegistry.of(STYLE));
            styled = true;
            window = new WindowFrame("Cues");
            window.setKey(WINDOW_KEY);
            window.setApplication(kind);
            window.markApplicationMain();
            window.setContent(build());
            desktop.addWindow(window);
            // After addWindow, which places and sizes it.
            window.resizeTo(820f, 460f);
        }

        @Override public ApplicationKind kind() { return kind; }
        @Override public WindowFrame mainWindow() { return window; }
        @Override public boolean open(Resource resource) { return false; }

        @Override
        public void activate() {
            Desktop desktop = Desktop.ifPresent(window.document());
            if (desktop != null) desktop.activate(window, true);
        }

        @Override
        public void dispose() {
            window.destroy();
        }
    }
}
