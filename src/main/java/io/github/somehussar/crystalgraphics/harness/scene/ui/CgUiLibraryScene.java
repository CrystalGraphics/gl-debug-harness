package io.github.somehussar.crystalgraphics.harness.scene.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.lwjgl.input.Keyboard;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.app.uibuilder.library.LibraryCatalog;
import com.crystalgui.app.uibuilder.library.LibraryPanel;
import com.crystalgui.app.uibuilder.library.PreviewBuilds;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.Button;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.util.HarnessThemes;

/**
 * The UI builder's Library on its own: every placeable kind as a card, every category open, over the default theme.
 *
 * <p>For judging the cards — each kind's sample, its width and value, the glyph a kind with nothing to draw
 * shows — and the panel around them: strips re-flowing as the window is resized, search, the rows toggle's
 * compact rows, and the detail strip for a clicked card. Double-clicking asks to place, which does nothing here:
 * no builder is open.</p>
 *
 * <p>Writes {@code top} and {@code bottom} captures, scrolled to each end, once every card's sample is built.</p>
 *
 * <ul>
 *   <li>{@code -Dcrystalgui.harness.library.width=140} lays the panel out that many logical pixels wide, as a docked one is.</li>
 *   <li>{@code -Dcrystalgui.harness.library.bench=scroll|resize|search} runs one workload forever — scrolling end to
 *       end, alternating two widths that re-flow the strips, or typing and clearing queries — printing frame-time
 *       percentiles every 240 frames, after the opening frames' worst. Pair with
 *       {@code -Dcrystalgui.frameprofile=true} for where the time goes.</li>
 * </ul>
 */
public class CgUiLibraryScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    private static final float SCALE = 2f;

    private static final float WIDTH = Float.parseFloat(System.getProperty("crystalgui.harness.library.width", "0"));
    private static final String BENCH = System.getProperty("crystalgui.harness.library.bench", "");

    /** Frames a capture waits after the samples are built: a new sample lays out, then fits. */
    private static final int SETTLE = 6;

    /** A re-flow's two widths, in logical pixels: four cards a strip and three. */
    private static final float WIDE = 360f, NARROW = 270f;

    /** What the search workload types, a keystroke every few frames; empty clears. */
    private static final String[] QUERIES = {"b", "bu", "but", "", "s", "sl", "sli", "", "t", "ta", "tab", "", "f", "fi", ""};

    /** Logical pixels scrolled per frame, about a wheel notch's worth spread over a few frames. */
    private static final float SCROLL_STEP = 12f;

    private float scrollDirection = 1f;
    private final long[] frameNanos = new long[240];
    private int frameCount;
    private long openingWorst;
    private int queryEmits;

    /** The frame each step happened on, or -1 before it has. */
    private long builtAt = -1, bottomAt = -1;

    private UIDocument document;
    private LibraryPanel library;

    @Override
    public void init(HarnessContext ctx) {
        Keyboard.enableRepeatEvents(true);
        UIElementRegistry.bootstrap();
        document = new UIDocument().markFrameThread();
        document.boxes().setUiScale(SCALE);
        HarnessThemes.install(document.styles(), "crystalgui:crystal-dark");

        library = new LibraryPanel(LibraryCatalog.current());
        UIElement root = new UIElement();
        StyleGroup.defaultPipeline(root.getStyle().getLayoutGroup(), l -> l.widthPercent(100f).heightPercent(100f));
        if (WIDTH > 0f) StyleGroup.inlinePipeline(library.getStyle().getLayoutGroup(), l -> l.width(WIDTH));
        root.append(library);
        library.search().searchBox().onQueryChanged.connect(() -> queryEmits++);
        document.append(root);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();
        long n = frame.getFrameNumber();
        // EVERY CATEGORY OPEN once the panel has listed its roots, which it does when it first connects.
        if (n == 2) openEverything();

        boolean benching = bottomAt >= 0 && n > bottomAt + SETTLE;
        if (benching) workload(n);
        long started = System.nanoTime();
        document.frame(frame.getDeltaTime(), w / SCALE, h / SCALE);
        CgUiPaintContext paint = CgUiPaintContext.getInstance();
        paint.beginFrame(w, h);
        document.paint(paint);
        paint.endFrame();
        long spent = System.nanoTime() - started;
        if (benching) {
            if (!BENCH.isEmpty()) record(spent);
        } else {
            openingWorst = Math.max(openingWorst, spent);
        }

        if (builtAt < 0 && n > 2 && PreviewBuilds.of(document).isIdle()) {
            builtAt = n;
            if (!BENCH.isEmpty()) {
                System.out.printf("[library-%s] samples built by frame %d, opening frames' worst %.2fms%n",
                        BENCH, n, openingWorst / 1e6);
            }
        }
        if (builtAt >= 0 && n == builtAt + SETTLE) ctx.getArtifactService().requestCapture("top");
        // A FRAME LATER, so the capture is of the frame it was asked on.
        if (builtAt >= 0 && n == builtAt + SETTLE + 2) scrollToEnd();
        if (builtAt >= 0 && n == builtAt + SETTLE * 2) {
            ctx.getArtifactService().requestCapture("bottom");
            bottomAt = n;
        }
    }

    private void workload(long n) {
        switch (BENCH) {
            case "scroll" -> scrollStep();
            case "resize" -> {
                if (n % 20 == 0) {
                    float width = (n / 20) % 2 == 0 ? WIDE : NARROW;
                    StyleGroup.inlinePipeline(library.getStyle().getLayoutGroup(), l -> l.width(width));
                }
            }
            case "search" -> {
                if (n % 6 != 0) return;
                String query = QUERIES[(int) ((n / 6) % QUERIES.length)];
                int before = queryEmits;
                library.search().searchBox().setText(query);
                // A programmatic set may not announce itself; typing always does, so list as typing would.
                if (queryEmits == before) library.search().refresh();
            }
            default -> { }
        }
    }

    private void openEverything() {
        List<LibraryPanel.Row> folders = new ArrayList<>();
        for (TreeRow<LibraryPanel.Row> row : library.tree().visibleRows()) {
            if (row.item() instanceof LibraryPanel.Folder) folders.add(row.item());
        }
        library.tree().expandSubtrees(folders);
        // A SELECTED CARD in every capture, so its look is judged beside the rest.
        library.select(LibraryCatalog.current().entry(Button.NAME));
    }

    private void scrollStep() {
        Box tree = library.tree().box();
        if (tree == null) return;
        float top = tree.scrollTop() + SCROLL_STEP * scrollDirection;
        if (top >= tree.maxScrollTop() || top <= 0f) scrollDirection = -scrollDirection;
        tree.setScroll(0f, Math.max(0f, Math.min(tree.maxScrollTop(), top)));
    }

    private void record(long nanos) {
        frameNanos[frameCount++] = nanos;
        if (frameCount < frameNanos.length) return;
        frameCount = 0;
        long[] sorted = frameNanos.clone();
        Arrays.sort(sorted);
        System.out.printf("[library-" + BENCH + "] p50 %.2fms  p90 %.2fms  p99 %.2fms  max %.2fms%n",
                percentile(sorted, 0.50), percentile(sorted, 0.90), percentile(sorted, 0.99), percentile(sorted, 1.0));
    }

    /** In milliseconds, from nanos sorted ascending. */
    private static double percentile(long[] sorted, double fraction) {
        return sorted[(int) Math.min(sorted.length - 1, Math.floor(fraction * sorted.length))] / 1e6;
    }

    private void scrollToEnd() {
        Box tree = library.tree().box();
        if (tree != null) tree.setScroll(0f, tree.maxScrollTop());
    }

    @Override
    public void dispose() {
        document = null;
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

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        return document.input().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return document.input().consumeMouseEvent(event);
    }
}
