package io.github.somehussar.crystalgraphics.harness.scene.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.lwjgl.input.Keyboard;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.app.uibuilder.library.LibraryCatalog;
import com.crystalgui.app.uibuilder.library.LibraryPanel;
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
 * <p>Writes {@code top} and {@code bottom} captures a few frames in, scrolled to each end.</p>
 *
 * <ul>
 *   <li>{@code -Dcrystalgui.harness.library.width=140} lays the panel out that many logical pixels wide, as a docked one is.</li>
 *   <li>{@code -Dcrystalgui.harness.library.scroll=true} then scrolls it end to end forever, logging frame-time percentiles
 *       every 240 frames — pair with {@code -Dcrystalgui.frameprofile=true} for where the time goes.</li>
 * </ul>
 */
public class CgUiLibraryScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    private static final float SCALE = 2f;

    private static final float WIDTH = Float.parseFloat(System.getProperty("crystalgui.harness.library.width", "0"));
    private static final boolean SCROLL = Boolean.getBoolean("crystalgui.harness.library.scroll");

    /** Logical pixels scrolled per frame, about a wheel notch's worth spread over a few frames. */
    private static final float SCROLL_STEP = 12f;

    private float scrollDirection = 1f;
    private final long[] frameNanos = new long[240];
    private int frameCount;

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
        document.append(root);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();
        long n = frame.getFrameNumber();
        // EVERY CATEGORY OPEN once the panel has listed its roots, which it does when it first connects.
        if (n == 2) openEverything();

        if (SCROLL && n > 30) scrollStep();
        long started = System.nanoTime();
        document.frame(frame.getDeltaTime(), w / SCALE, h / SCALE);
        CgUiPaintContext paint = CgUiPaintContext.getInstance();
        paint.beginFrame(w, h);
        document.paint(paint);
        paint.endFrame();
        if (SCROLL && n > 30) record(System.nanoTime() - started);

        if (n == 12) ctx.getArtifactService().requestCapture("top");
        if (n == 14) scrollToEnd();
        if (n == 24) ctx.getArtifactService().requestCapture("bottom");
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
        System.out.printf("[library-scroll] p50 %.2fms  p90 %.2fms  p99 %.2fms  max %.2fms%n",
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
