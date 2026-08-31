package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.elements.UIText;
import dev.vfyjxf.taffy.style.TaffyPosition;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.config.HarnessEngine;
import org.joml.Matrix4f;

/**
 * M5 5.4's acceptance picture: ONE fixed tree, described once, built on BOTH engines from the same
 * stylesheet, and drawn by each — the old engine through {@code UIWindow.paintFrame()}, the new
 * through {@code UIDocument.update()} + {@code UIDocument.paint()} over the box tree.
 *
 * <p>The tree carries every path a paint pass has to survive: flat and rounded backgrounds,
 * borders, an {@code opacity} layer, a rounded {@code overflow: hidden} (the mask path), a square
 * one (the scissor path), a scroll offset, a {@code transform}, and text. The scene alternates
 * engines every two seconds so a human can compare, and writes {@code engine_old}/{@code engine_new}
 * PNGs on frames 4 and 5 — {@code EngineParityTest} (headless) compares those within a tolerance,
 * because a readback finds what six screenshots do not.</p>
 */
public class CgUiEngineParityScene implements InteractiveSceneLifecycle, HarnessEngine.Aware {

    private static final float SCALE = 2f;

    private static final String STYLE_SHEET = """
            #panel { background-color: #FF2B3A55; border-radius: 10px; border-width: 2px; border-color: #FFAABBDD; }
            #flat { background-color: #FF446644; }
            #faded { opacity: 0.5; background-color: #FFAA4444; }
            #inner-a { background-color: #FF3366AA; }
            #inner-b { background-color: #FFDDAA33; border-radius: 6px; }
            #round-clip { overflow: hidden; border-radius: 14px; background-color: #FF223344; }
            #square-clip { overflow: hidden; background-color: #FF1E2A1E; }
            #tall { background-color: #FF667788; }
            #tall-2 { background-color: #FF886677; }
            #turned { transform: translate(30px) rotate(8deg); background-color: #FF55AA88; }
            #label { color: #FFF0F0F0; font-size: 14; }
            #outlined { background-color: #FF303030; outline-width: 2px; outline-color: #FF66CCFF; }
            """;

    private UIWindow uiWindow;
    private UIDocument document;
    private boolean scrollApplied;

    @Override
    public boolean supportsEngine(HarnessEngine engine) {
        return true;    // both, side by side, is the whole point
    }

    // ── One spec, two trees ──────────────────────────────────────────────────

    /** What one box in the fixed tree is: an id, a place, and children. */
    private interface Builder<E> {
        E make(String id, float x, float y, float width, float height);

        void add(E parent, E child);

        E text(String id, String content);
    }

    private <E> E build(Builder<E> b) {
        E root = b.make("root", 0, 0, 560, 360);
        E panel = b.make("panel", 20, 20, 240, 150);
        b.add(root, panel);
        b.add(panel, b.make("inner-a", 15, 15, 90, 40));
        b.add(panel, b.make("inner-b", 60, 40, 90, 40));
        E faded = b.make("faded", 130, 70, 90, 60);
        b.add(panel, faded);
        b.add(faded, b.make("inner-a", 10, 10, 40, 25));

        E roundClip = b.make("round-clip", 290, 20, 120, 100);
        b.add(root, roundClip);
        b.add(roundClip, b.make("tall", 10, 10, 100, 300));

        E squareClip = b.make("square-clip", 430, 20, 110, 100);
        b.add(root, squareClip);
        b.add(squareClip, b.make("tall-2", 10, 10, 90, 300));

        b.add(root, b.make("turned", 40, 200, 100, 50));
        b.add(root, b.make("outlined", 200, 210, 90, 40));
        E label = b.text("label", "The same picture, from a tree that never wrote a matrix during paint.");
        b.add(root, label);
        b.add(root, b.make("flat", 320, 210, 60, 30));
        return root;
    }

    @Override
    public void init(HarnessContext ctx) {
        // Old engine: UIElement/UIText under a UIWindow.
        UIElement oldRoot = build(new Builder<UIElement>() {
            @Override
            public UIElement make(String id, float x, float y, float width, float height) {
                UIElement e = new UIElement().setId(id);
                e.layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(x).top(y).width(width).height(height));
                return e;
            }

            @Override
            public void add(UIElement parent, UIElement child) {
                parent.addChild(child);
            }

            @Override
            public UIElement text(String id, String content) {
                UIText t = new UIText(content);
                t.setId(id);
                t.layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(20f).top(280f).width(520f));
                return t;
            }
        });
        uiWindow = new UIWindow(Ui.of(oldRoot));
        uiWindow.getStyleEngine().addStylesheet(StyleSheet.parse(STYLE_SHEET));

        // New engine: UINode/UIText under a UIDocument, the same stylesheet text.
        document = new UIDocument();
        UINode newRoot = build(new Builder<UINode>() {
            @Override
            public UINode make(String id, float x, float y, float width, float height) {
                UINode n = new UINode().setId(id);
                StyleGroup.inlinePipeline(n.getStyle().getLayoutGroup(),
                        l -> l.positionType(TaffyPosition.ABSOLUTE)
                                .left(x).top(y).width(width).height(height));
                return n;
            }

            @Override
            public void add(UINode parent, UINode child) {
                parent.append(child);
            }

            @Override
            public UINode text(String id, String content) {
                // Qualified, and the ONLY place in the repo that has to be: this scene builds one
                // tree on BOTH engines, so both UITexts are in scope at once and an import can only
                // name one of them. The old-engine cluster above keeps the plain import; the new
                // engine's twin says which it is. It resolves itself when the old engine goes at 6.9.
                com.crystalgui.widget.text.UIText t = new com.crystalgui.widget.text.UIText(content);
                t.setId(id);
                StyleGroup.inlinePipeline(t.getStyle().getLayoutGroup(),
                        l -> l.positionType(TaffyPosition.ABSOLUTE)
                                .left(20f).top(280f).width(520f));
                return t;
            }
        });
        document.append(newRoot);
        document.styles().addStylesheet(StyleSheet.parse(STYLE_SHEET));
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();

        // The scroll offsets, applied once after each engine has a first layout to clamp against.
        uiWindow.init(w, h);
        if (!scrollApplied) {
            document.update(w / SCALE, h / SCALE);
            UIElement oldClip = uiWindow.ui.rootElement.getElementById("round-clip");
            if (oldClip != null) oldClip.setScrollImmediate(0f, 60f);
            UIElement oldSquare = uiWindow.ui.rootElement.getElementById("square-clip");
            if (oldSquare != null) oldSquare.setScrollImmediate(0f, 120f);
            UINode newClip = (UINode) document.getElementById("round-clip");
            UINode newSquare = (UINode) document.getElementById("square-clip");
            if (newClip != null && newClip.box() != null) newClip.box().setScroll(0f, 60f);
            if (newSquare != null && newSquare.box() != null) newSquare.box().setScroll(0f, 120f);
            scrollApplied = true;
        }

        // Frames 4/5 are the two captures; afterwards, alternate every ~2s for eyeballing.
        boolean drawOld = frame.getFrameNumber() == 4
                || (frame.getFrameNumber() != 5 && (frame.getElapsedTime() % 4.0) < 2.0);

        CgUiPaintContext context = CgUiPaintContext.getInstance();
        if (drawOld) {
            uiWindow.paintFrame();
        } else {
            document.update(w / SCALE, h / SCALE);
            context.beginFrame(w, h);
            context.getPoseStack().pushPose();
            context.getPoseStack().last().pose().mul(new Matrix4f().scale(SCALE, SCALE, 1f));
            document.paint(context);
            context.getPoseStack().popPose();
            context.endFrame();
        }

        if (frame.getFrameNumber() == 4) ctx.getArtifactService().captureNow("engine_old");
        if (frame.getFrameNumber() == 5) ctx.getArtifactService().captureNow("engine_new");

        if (frame.getFrameNumber() > 5) {
            String status = (drawOld ? "OLD engine (UIWindow.paintFrame)" : "NEW engine (UIDocument.paint over boxes)")
                    + " -- alternates every 2s; PNGs written on frames 4/5";
            context.text().draw().at(4, (float) h - 20f).text(status)
                    .font(context.getFont().atSize(14)).submit();
        }
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
        return false;   // interactive: runs until closed, alternating engines
    }

    @Override
    public void dispose() {
        uiWindow = null;
        document = null;
    }
}
