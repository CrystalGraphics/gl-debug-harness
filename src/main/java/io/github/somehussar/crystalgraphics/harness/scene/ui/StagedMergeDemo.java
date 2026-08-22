package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgui.text.diff.ThreeWayMerge;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Dialog;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.workbench.DiffView;
import com.crystalgui.ui.elements.workbench.MergeView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <b>F7 — a real three-way merge over a real file, from git.</b>
 *
 * <p>Exists because {@link MergeView} cannot be judged from a fixture. Two synthetic paragraphs make every
 * merge tool look fine; what a merge view has to survive is a real source file — long lines, deep
 * indentation, a conflict fifty lines below the fold — and the only cheap source of those is the repository
 * the harness is sitting in.</p>
 *
 * <h3>Where the three sides come from</h3>
 *
 * <p>Git already keeps exactly the three texts a merge needs, which is why this reads them rather than
 * inventing them:</p>
 *
 * <ul>
 *   <li><b>base</b> — {@code HEAD:<path>}, the common ancestor</li>
 *   <li><b>theirs</b> — {@code :<path>}, the <em>index</em>: what has been staged</li>
 *   <li><b>mine</b> — the working tree, i.e. what is on disk right now</li>
 * </ul>
 *
 * <p>So staging an edit and then editing the same lines again produces a genuine conflict, and staging one
 * region while editing another produces a genuine auto-merge. The person driving the harness controls which,
 * with ordinary git commands and no fixture to maintain.</p>
 *
 * <h3>Every outcome says which one it was</h3>
 *
 * <p>"No staged changes", "git is not on the PATH", "the repository could not be found" and "it merged
 * cleanly" are four different things that all look like a window with nothing interesting in it. Each one
 * therefore logs a line naming itself — the same rule the workspace's project listing had to learn, where a
 * report of the symptom could only ever be <em>"it was empty"</em>.</p>
 */
final class StagedMergeDemo {

    private static final String TAG = "[merge-demo] ";

    private StagedMergeDemo() {
    }

    /**
     * What was found, so the dialog title can say so rather than implying the merge is always real.
     *
     * @param dense whether this was built to have differences in it, and so should never be second-guessed
     */
    private record Sides(String title, String base, String mine, String theirs, boolean dense) {
        Sides(String title, String base, String mine, String theirs) {
            this(title, base, mine, theirs, false);
        }
    }

    /**
     * <b>F6 — a two-way commit diff: what {@code HEAD} has against what is on disk.</b>
     *
     * <p>The natural source for a diff, and unlike the merge it is almost never degenerate: a working tree
     * with an edit in it is the ordinary state of a repository. Falls back to the synthesised pair when
     * the tree is clean, for the same reason F7 does.</p>
     */
    static void openCommitDiff(UIWindow window, UIElement over) {
        Path root = repositoryRoot();
        String path = root == null ? null : firstJava(gitLines(root, "diff", "--name-only"));
        String head = path == null ? null : gitShow(root, "HEAD:" + path);
        String worktree = path == null ? null : read(root.resolve(path));

        String title;
        String left;
        String right;
        if (head != null && worktree != null) {
            System.out.println(TAG + "commit diff of " + path + " (HEAD vs working tree)");
            title = path;
            left = head;
            right = worktree;
        } else {
            System.out.println(TAG + "no modified .java in the working tree - using the synthesised pair");
            Sides sides = synthesised();
            title = "synthesised";
            left = sides.base();
            right = sides.mine();
        }

        DiffView view = new DiffView("HEAD", left, "Working tree", right);
        System.out.println(TAG + view.differenceCount() + " difference(s)");

        Dialog dialog = new Dialog(title + " - commit diff");
        dialog.getContent().addChild(view);

        UIElement actions = new UIElement();
        actions.addClass(MergeView.DIALOG_ACTIONS_CLASS);
        dialog.getContent().addChild(actions);
        Button close = new Button("Close");
        actions.addChild(close);
        close.onPressed.connect(dialog::close);

        window.addOverlay(dialog, over);
        dialog.onClosed.connect(dialog::removeSelf);
        dialog.showModal();
        window.getInputHandler().requestFocus(close);
    }

    /** Shift+F7 — the synthesised merge, which always has a conflict in it. */
    static void openSynthesised(UIWindow window, UIElement over) {
        show(window, over, synthesised());
    }

    static void open(UIWindow window, UIElement over) {
        Sides sides = collect();
        // FALL THROUGH WHEN THERE IS NOTHING TO LOOK AT. A working tree with nothing staged produces a
        // merge whose "theirs" made no change at all, so every region auto-resolves and the three panes
        // are the same document -- which is a correct answer to a question nobody asked. The demo exists
        // to exercise the view, so it prefers real data only while the real data has conflicts in it.
        if (sides != null && !sides.dense()) {
            ThreeWayMerge probe = ThreeWayMerge.of(sides.base(), sides.mine(), sides.theirs());
            if (probe.conflictCount() == 0) {
                System.out.println(TAG + "the repository merge has no conflicts, so it would show three "
                        + "copies of one file - using the synthesised fixture instead. Shift+F7 always "
                        + "does; stage an edit and change the same lines again for the real thing.");
                sides = null;
            }
        }
        show(window, over, sides == null ? synthesised() : sides);
    }

    private static void show(UIWindow window, UIElement over, Sides sides) {
        if (sides == null) {
            System.out.println(TAG + "nothing to merge - no repository, no git, and the fallback file "
                    + "could not be read. Nothing opened.");
            return;
        }

        ThreeWayMerge merge = ThreeWayMerge.of(sides.base(), sides.mine(), sides.theirs());
        System.out.println(TAG + sides.title() + " - " + merge.regions().size() + " changed region(s), "
                + merge.conflictCount() + " conflict(s)");
        if (merge.conflictCount() == 0) {
            System.out.println(TAG + "it merged CLEANLY. That is a real result, not an empty window - "
                    + "stage an edit and then change the same lines again to produce a conflict.");
        }

        MergeView view = new MergeView(merge);

        Dialog dialog = new Dialog(sides.title());
        dialog.getContent().addChild(view);

        UIElement actions = new UIElement();
        actions.addClass(MergeView.DIALOG_ACTIONS_CLASS);
        dialog.getContent().addChild(actions);

        UIText where = new UIText("");
        Button write = new Button("Write merged…");
        Button close = new Button("Close");
        actions.addChild(where);
        actions.addChild(write);
        actions.addChild(close);

        // GATED THE SAME WAY THE REAL ONE IS, so the harness exercises the gate rather than a copy of the
        // view with the safety taken off. An undecided conflict still produces text.
        Runnable sync = () -> {
            boolean ready = view.isResolved();
            write.setEnabled(ready);
            write.setHitTest(ready);
        };
        view.onChanged.connect(sync);
        sync.run();

        write.onPressed.connect(() -> {
            Path out = Paths.get("merge-output.txt").toAbsolutePath().normalize();
            try {
                Files.write(out, view.mergedText().getBytes(StandardCharsets.UTF_8));
                where.setText("wrote " + out.getFileName());
                System.out.println(TAG + "wrote merged result to " + out);
            } catch (IOException failed) {
                where.setText("could not write");
                System.out.println(TAG + "could not write " + out + ": " + failed);
            }
        });
        close.onPressed.connect(dialog::close);

        window.addOverlay(dialog, over);
        dialog.onClosed.connect(dialog::removeSelf);
        dialog.showModal();
        window.getInputHandler().requestFocus(close);
    }

    // ── Where the text comes from ───────────────────────────────────────────────────────────────

    private static Sides collect() {
        Path root = repositoryRoot();
        if (root == null) {
            System.out.println(TAG + "no repository above " + Paths.get("").toAbsolutePath()
                    + " - falling back to a synthesised merge");
            return synthesised();
        }

        List<String> staged = gitLines(root, "diff", "--cached", "--name-only");
        String path = firstJava(staged);
        if (path != null) {
            String base = gitShow(root, "HEAD:" + path);
            String index = gitShow(root, ":" + path);
            String worktree = read(root.resolve(path));
            if (base != null && index != null && worktree != null) {
                return new Sides(path + " — staged vs working tree", base, worktree, index);
            }
            System.out.println(TAG + "found staged file " + path + " but could not read all three sides");
        } else {
            System.out.println(TAG + "no STAGED .java changes (git diff --cached found "
                    + staged.size() + " path(s))");
        }

        // Second best: modified but not staged. The index equals HEAD, so "theirs" made no change at all
        // and every region auto-merges to mine -- which demonstrates the auto-merge path honestly and has
        // no conflicts in it by construction. Said out loud, because a conflict-free merge looks like a
        // broken one to somebody who expected to be asked something.
        String modified = firstJava(gitLines(root, "diff", "--name-only"));
        if (modified != null) {
            String base = gitShow(root, "HEAD:" + modified);
            String worktree = read(root.resolve(modified));
            if (base != null && worktree != null) {
                System.out.println(TAG + "using UNSTAGED changes in " + modified
                        + " - theirs == base, so this shows auto-merge and cannot conflict");
                return new Sides(modified + " — unstaged (no conflicts by construction)",
                        base, worktree, base);
            }
        }

        System.out.println(TAG + "working tree is clean - falling back to a synthesised merge");
        return synthesised();
    }

    /**
     * A small file, edited two ways, with a difference on nearly every line.
     *
     * <p><b>Deliberately not a real source file with a few edits in it.</b> That is what this used to be,
     * and it made a poor demo for a reason worth recording: three or four changes scattered through four
     * hundred lines means the panes are almost entirely identical text, so the view looks like three
     * copies of one document and the changes have to be hunted for by scrolling. A demo of a diff view
     * should be mostly diff.</p>
     *
     * <p>Every kind of region is present and they are packed close enough to sit on one screen:</p>
     *
     * <ul>
     *   <li>a <b>conflict</b> both sides changed differently (the {@code sum} declaration)</li>
     *   <li>a <b>resolvable</b> conflict - two edits to different parts of one line, which
     *       {@code MagicResolve} can settle ({@code total()}'s signature and its body)</li>
     *   <li><b>mine-only</b> and <b>theirs-only</b> changes, which must auto-merge silently</li>
     *   <li>an <b>insertion</b> from each side, in different places</li>
     *   <li>a <b>deletion</b> by one side</li>
     *   <li>a change <b>both sides made identically</b>, which must not be reported as a conflict</li>
     * </ul>
     */
    private static Sides synthesised() {
        List<String> base = List.of(
                "package demo;",
                "",
                "import java.util.List;",
                "",
                "public class Inventory {",
                "",
                "    private final List<Item> items;",
                "    private int discount;",
                "",
                "    public Inventory(List<Item> items) {",
                "        this.items = items;",
                "    }",
                "",
                "    public int total() {",
                "        int sum = 0;",
                "        for (Item item : items) {",
                "            sum += item.price();",
                "        }",
                "        return sum;",
                "    }",
                "",
                "    public boolean isEmpty() {",
                "        return items.isEmpty();",
                "    }",
                "",
                "    public String describe() {",
                "        return name + \": \" + total();",
                "    }",
                "}");

        List<String> mine = List.of(
                "package demo;",
                "",
                "import java.util.ArrayList;",            // insertion, mine
                "import java.util.List;",
                "",
                "public class Inventory {",
                "",
                "    private final List<Item> items;",
                "    private int discount;",
                "",
                "    public Inventory(List<Item> items) {",
                "        this.items = new ArrayList<>(items);",   // mine only
                "    }",
                "",
                "    public long total() {",                      // resolvable half: the signature
                "        long sum = 0L;",                         // CONFLICT with theirs
                "        for (Item item : items) {",
                "            sum += item.price() * item.quantity();",   // mine only
                "        }",
                "        return sum;",
                "    }",
                "",
                "    public boolean isEmpty() {",
                "        return items == null || items.isEmpty();",      // both sides, identically
                "    }",
                "",
                "    public String describe() {",
                // RESOLVABLE conflict: mine touches the left of this line, theirs the right.
                "        return label + \": \" + total();",
                "    }",
                "}");

        List<String> theirs = List.of(
                "package demo;",
                "",
                "import java.util.List;",
                "import java.util.Objects;",              // insertion, theirs
                "",
                "public class Inventory {",
                "",
                "    private final List<Item> items;",
                "",                                        // deletion, theirs: the discount field
                "    public Inventory(List<Item> items) {",
                "        this.items = items;",
                "    }",
                "",
                "    public int total() {",
                "        int sum = startingBalance;",       // CONFLICT with mine
                "        for (Item item : items) {",
                "            sum += item.price();",
                "        }",
                "        return Math.max(sum, 0);",         // theirs only
                "    }",
                "",
                "    public boolean isEmpty() {",
                "        return items == null || items.isEmpty();",      // both sides, identically
                "    }",
                "",
                "    public String describe() {",
                "        return name + \": \" + count();",
                "    }",
                "}");

        return new Sides("synthesised - a difference on nearly every line",
                join(base), join(mine), join(theirs), true);
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines) + "\n";
    }

    private static String firstJava(List<String> paths) {
        for (String path : paths) {
            if (path.endsWith(".java")) return path;
        }
        return null;
    }

    /** Walks up for the CrystalGUI checkout — the harness is a submodule, so its own {@code .git} is not it. */
    private static Path repositoryRoot() {
        Path at = Paths.get("").toAbsolutePath().normalize();
        while (at != null) {
            if (Files.isDirectory(at.resolve("core/src/main/java/com/crystalgui"))
                    && Files.exists(at.resolve(".git"))) {
                return at;
            }
            at = at.getParent();
        }
        return null;
    }

    private static String gitShow(Path root, String spec) {
        List<String> lines = gitLines(root, "show", spec);
        return lines.isEmpty() ? null : String.join("\n", lines) + "\n";
    }

    /**
     * Runs git and returns its stdout.
     *
     * <p>Arguments are passed as separate array entries and never spliced into a command line, so a path
     * out of the repository cannot become anything but a filename however it is spelled.</p>
     */
    private static List<String> gitLines(Path root, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(root.toString());
        command.addAll(List.of(args));

        List<String> out = new ArrayList<>();
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            try (InputStream stream = process.getInputStream()) {
                String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    System.out.println(TAG + "git timed out: " + String.join(" ", args));
                    return out;
                }
                if (process.exitValue() != 0) return out;
                for (String line : text.split("\n", -1)) {
                    out.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
                }
                // split(-1) leaves a trailing empty entry for a newline-terminated stream.
                if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
            }
        } catch (IOException notInstalled) {
            System.out.println(TAG + "git is not on the PATH: " + notInstalled.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            System.out.println(TAG + "could not read " + file + ": " + unreadable.getMessage());
            return null;
        }
    }
}
