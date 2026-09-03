package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * Exercises scrolling — wheel over a list, or drag a scrollbar thumb.
 *
 * <p>The left column is the point: it's a <b>plain {@code UIElement}</b> with {@code overflow: hidden},
 * no widget at all. It scrolls because scrolling is an element capability in this engine, the same way
 * any {@code <div>} scrolls in a browser. The right column is {@code ScrollerView}, which adds nothing
 * but the two visible bars.</p>
 *
 * <p>Note both take their rows via plain {@code addChild} — there is no viewport or content wrapper to
 * reach through, which is the structural difference from LDLib2's ScrollerView.</p>
 */
public class CgUiScrollerScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** Logical-to-surface scale, as the harness's other new-engine scenes use. */
    private static final float SCALE = 2f;

    private UIDocument document;
    private UIElement bare;
    private ScrollerView withBars;

    private static final String STYLES = """
            .demo-root { width: 340px; height: 220px; flex-direction: row; gap-all: 10px; }
            .col       { width: 150px; height: 190px; flex-direction: column; }
            /* A bare element is PROGRAMMATIC-ONLY: scrollTop works, the wheel does nothing, whatever
             * the overflow. Wheel handling belongs to a widget that opts in (ScrollerView), so a
             * stray clipped element can never silently swallow scroll input. This column proves the
             * capability by being driven from Java in render(). */
            .bare      { overflow: hidden; background-color: #23232A; }
            .barred    { background-color: #23232A; }
            /* Turning the step buttons on is pure CSS — they're wired but display:none by default. */
//            .barred .__head__, .barred .__tail__ { display: flex; }
            .row       { height: 26px; background-color: #3A4A6A; padding-all: 4px; }
            .row-alt   { height: 26px; background-color: #4A3A5A; padding-all: 4px; width: 120%; }
            """;

    @Override
    public void init(HarnessContext ctx) {
        org.lwjgl.input.Keyboard.enableRepeatEvents(true);
        this.document = new UIDocument().markFrameThread();
        this.document.boxes().setUiScale(SCALE);
        UIElement sceneRoot = createDemo();
        // THE ROOT FILLS THE DOCUMENT. On the old engine the scene's root WAS the window's
        // root and took the window's size; here the DOCUMENT is the root and this is an
        // ordinary child, which sizes to its content -- so without this the scene lays out
        // at nothing and draws nothing. DEFAULT origin, so a scene sheet still wins.
        StyleGroup.defaultPipeline(sceneRoot.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f));
        this.document.append(sceneRoot);
        this.document.styles().addStylesheet(StyleSheet.DEFAULT);
        this.document.styles().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        this.document.styles().addStylesheet(StyleSheet.parse(STYLES));
    }

    private UIElement createDemo() {
        UIElement root = new UIElement()
                .layout(l -> l.paddingAll(10).flexDirection(FlexDirection.ROW).gapAll(10))
                .setFocusPolicy(FocusPolicy.NONE);
        root.addClass("panel");
        root.addClass("demo-root");

        // A PLAIN element. No widget — just overflow, exactly like a scrolling <div>.
        bare = new UIElement();
        bare.addClass("col");
        bare.addClass("bare");
        fill(bare, "bare");
        root.append(bare);

        // Same thing plus visible bars.
        withBars = new ScrollerView();
        withBars.addClass("col");
        withBars.addClass("barred");
        fill(withBars, "bars");
        root.append(withBars);

        return root;
    }

    /** Rows go in via plain addChild — top-layer children, no content host to reach through. */
    private void fill(UIElement container, String tag) {
        for (int i = 0; i < 30; i++) {
            UIElement row = new UIElement();
            row.addClass(i % 2 == 0 ? "row" : "row-alt");
            UIText t = new UIText(tag + " row " + i);
            if (i == 20) row.setFocusPolicy(FocusPolicy.FOCUSABLE);
            t.addClass("label");
            row.append(t);
            container.append(row);
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // Content size is only known after a layout, so the bars are synced once it exists.
        withBars.refreshScrollers();

        // The bare column ignores the wheel by design, so it's driven from code — which is exactly
        // the point it's here to make: scrolling is a capability any element has, reachable through
        // scrollTop, with no widget and no wrapper. It mirrors the right column so the two can be
        // compared directly.
        if (bare.box() != null && withBars.box() != null) {
            bare.box().setScroll(bare.box().scrollLeft(), withBars.box().scrollTop());
        }

        document.frame(frame.getDeltaTime(), ctx.getScreenWidth() / SCALE, ctx.getScreenHeight() / SCALE);

        // AND THE PAINT. `paintFrame()` did both; `frame()` only advances, so a scene that
        // lost this half advanced perfectly and drew nothing.
        CgUiPaintContext paintContext = CgUiPaintContext.getInstance();
        paintContext.beginFrame(ctx.getScreenWidth(), ctx.getScreenHeight());
        document.paint(paintContext);
        paintContext.endFrame();

        var context = CgUiPaintContext.getInstance();
        context.text().draw().at(0, 0)
                .text(String.format("Wheel/drag the RIGHT column; the left is scrolled from code."
                                + "  bare=%.0f  bars=%.0f",
                        bare.scrollTop(), withBars.scrollTop()))
                .font(context.getFont().atSize(14)).submit();

        if (frame.getFrameNumber() == 5) {
            ctx.getArtifactService().requestCapture("startup");
        }
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
