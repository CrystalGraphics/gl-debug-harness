package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.render.texture.CgUiQuad;
import com.crystalgui.render.texture.CgUiSprite;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.*;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.platform.input.CgSystemInput;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import org.lwjgl.input.Keyboard;

/**
 * Interactive harness scene that builds and renders a CrystalGUI DOM tree
 * using the immediate-mode {@link UIDocument} paint path.
 *
 * <p>This scene exercises the full CrystalGUI stack — Taffy layout, DOM tree
 * traversal, {@code CgUiQuad} solid fills, {@code CgUiSprite} atlas sprites,
 * absolute positioning, and flex layout — in a standalone GL harness window
 * with no Minecraft dependency.</p>
 *
 * <p>The UI tree is a panel with a header, main content area, and a bottom
 * toolbar containing sprite buttons, demonstrating the core layout and
 * rendering primitives.</p>
 *
 * <p>Register in {@link io.github.somehussar.crystalgraphics.harness.SceneRegistry}
 * under scene id {@code "cgui-test"}.</p>
 */
public class CgUiTestScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** Logical-to-surface scale, as the harness\'s other new-engine scenes use. */
    private static final float SCALE = 2f;

    private UIDocument document;

    private UINode hoveredElement;

    CgUiSprite backgroundMain = new CgUiSprite()
            .setTexture("crystalgui:textures/gui/gdp_styles.png")
            .setTextureSizeReference(256, 256)
            .setSprite(29, 1, 13, 13)
            .setBorder(1, 1, 11, 11);

    CgUiSprite inset = backgroundMain.copy()
            .setSprite(154, 131, 16, 16)
            .setBorder(5, 6, 9, 10);

    CgUiSprite overlay = inset.copy()
            .setSprite(86, 239, 16, 16)
            .setBorder(4, 5, 10, 11);

    CgUiSprite buttonSprite = inset.copy()
            .setSprite(154, 165, 16, 16)
            .setBorder(5, 6, 9, 10);


    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void init(HarnessContext ctx) {
        UINode root =
//                createUISimple();
                createYogaExample();
        Keyboard.enableRepeatEvents(true);
        this.document = new UIDocument().markFrameThread();
        this.document.boxes().setUiScale(SCALE);
        UINode sceneRoot = root;
        // THE ROOT FILLS THE DOCUMENT. On the old engine the scene's root WAS the window's
        // root and took the window's size; here the DOCUMENT is the root and this is an
        // ordinary child, which sizes to its content -- so without this the scene lays out
        // at nothing and draws nothing. DEFAULT origin, so a scene sheet still wins.
        StyleGroup.defaultPipeline(sceneRoot.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f));
        this.document.append(sceneRoot);
    }

    private UINode createUISimple() {
        UINode root = new UINode()
                .generalStyle(s -> s.background(new CgUiQuad(0xFFFFFFFF)))
                .layout(l -> l.height(600).width(600).flexDirection(FlexDirection.COLUMN));

        root.append(
                new UINode().generalStyle(s -> s.background(new CgUiQuad(0xFFFF0000)))
                        .layout(l -> l.widthPercent(100).height(300))
        );

        return root;
    }

    private UINode createYogaExample() {

        UINode root = new UINode()
                .generalStyle(s -> s.background(backgroundMain))
                .layout(l -> l
                        .width(250)
                        .height(475)
                        .paddingAll(10)
                ).onMouseScroll.attachListener(
//                        (thiz, event) -> thiz.generalStyle(s -> s.color(s.color() == 0xFF00FF00 ? 0xFFFFFFFF : 0xFF00FF00)),
                        (thizElement, event) -> { },
                        true, false);

        UINode container = new UINode().setId("Container")
                .generalStyle(s -> s.background(inset))
                .layout(l -> l
                        .widthPercent(100).heightPercent(100)
                        .flex(1)
                        .gapAll(10)
                        .flexDirection(FlexDirection.COLUMN)
                );
        root.append(container);

        UINode header = new UINode().setId("Header")
                .generalStyle(s -> s.background(inset))
                .layout(l -> l.height(60));
        container.append(header);

        UINode mainWrapper = new UINode().setId("wrapper")
                .setHitTest(false)
                .layout(l -> l
//                        .positionType(TaffyPosition.ABSOLUTE)   // pulls it out of container's flex flow entirely
//                        .widthPercent(100).heightPercent(100)   // stretches to fill container, top to bottom
                        .flexDirection(FlexDirection.ROW)
                        .justifyContent(AlignContent.CENTER)
                        .alignItems(AlignItems.CENTER)           // center main vertically within the full stretch too
                ).setFocusPolicy(FocusPolicy.NONE);
        container.append(mainWrapper); // still added before `content` — controls paint order, not flow anymore

        UINode main = new UINode().setId("main")
                .generalStyle(s -> s.background(inset).color(0xFF00FF00))
                .layout(l -> l
                        .widthPercent(100)
                        .heightAuto()
                        .flexDirection(FlexDirection.ROW)
                        .flexWrap(FlexWrap.WRAP)
                        .paddingAll(10)
                        .alignContent(AlignContent.CENTER)
                        .alignItems(AlignItems.CENTER)
                        .justifyContent(AlignContent.SPACE_AROUND)
                );
        mainWrapper.append(main);

        for (int i = 0; i < 3; i++) {
            UINode button = new UINode().setId("buttonMain"+i)
                    .generalStyle(s -> s.background(buttonSprite))
                    .layout(l -> l.width(40).height(40));
            main.append(button);
        }

        UINode content = new UINode().setId("content")
                .generalStyle(s -> s.background(inset).color(0xFFFF0000))
                .layout(l -> l.flex(2).marginBottom(72).marginLeft(10).marginRight(10));
        container.append(content);

        UINode absolute = new UINode().setId("absolute")
                .generalStyle(s -> s.background(overlay))
                .layout(l -> l
                        .positionType(TaffyPosition.ABSOLUTE)
                        .widthPercent(100).height(64)
                        .flexDirection(FlexDirection.ROW)
                        .alignItems(AlignItems.CENTER)
                        .justifyContent(AlignContent.SPACE_AROUND)
                        .bottom(0)
                );
        container.append(absolute);

        UINode button1 = new UINode().setId("button0")
                .generalStyle(s -> s
                        .background(buttonSprite)
                        .overlay(new CgUiSprite()
                                .setTexture("crystalgui:textures/gui/Spritesheet_UI_Flat.png")
                                .setTextureSizeReference(736, 288)
                                .setSprite(296, 233, 14, 14)
                        )
                )
                .layout(l -> l.width(40).height(40));
        absolute.append(button1);

        for (int i = 0; i < 3; i++) {
            UINode button = new UINode().setId("Header"+(1+i))
                    .generalStyle(s -> s.background(buttonSprite))
                    .layout(l -> l.width(40).height(40));
            absolute.append(button);
        }
        return root;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        long timeMillis = System.currentTimeMillis();

        float value = (float) Math.sin(2 * Math.PI * timeMillis / 5000.0);
        document
                .children().getFirst().children().get(1).children().getFirst().layout(
                        l -> l.widthPercent(60 + 40 * value).minWidth(60).maxHeight(160)
                );
//        document.setMouse(Mouse.getX(), ctx.getScreenHeight() - Mouse.getY());
        document.frame(frame.getDeltaTime(), ctx.getScreenWidth() / SCALE, ctx.getScreenHeight() / SCALE);

        // AND THE PAINT. `paintFrame()` did both; `frame()` only advances, so a scene that
        // lost this half advanced perfectly and drew nothing.
        CgUiPaintContext paintContext = CgUiPaintContext.getInstance();
        paintContext.beginFrame(ctx.getScreenWidth(), ctx.getScreenHeight());
        document.paint(paintContext);
        paintContext.endFrame();
        UINode previousElement = this.hoveredElement;
//        this.hoveredElement = document.input().hoverTarget(), ctx.getScreenHeight() - Mouse.getY());
//        if (this.hoveredElement != previousElement) {
//            if (this.hoveredElement == null) {
//                Display.setTitle("No element selected :(");
//            } else {
//                Display.setTitle(hoveredElement.id() + "#");
//            }
//        }
//        Display.setTitle(String.format("%d, %d", Mouse.getX(), ctx.getScreenHeight() - Mouse.getY()));
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

//        if (event.pressed()) {
//            document.generalStyle(s -> {
//                   s.color(s.color() == 0xFF00FF00 ? 0xFFFFFFFF : 0xFF00FF00);
//            });
//
//            if(this.hoveredElement != null) {
//                this.hoveredElement.append(new UINode().setId("button0")
//                        .generalStyle(s -> s
//                                .background(buttonSprite)
//                                .overlay(new CgUiSprite()
//                                        .setTexture("crystalgui:textures/gui/Spritesheet_UI_Flat.png")
//                                        .setTextureSizeReference(736, 288)
//                                        .setSprite(296, 233, 14, 14)
//                                )
//                        )
//                        .layout(l -> l.width(40).height(40)));
//            }
//        }

        return document.input().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return document.input().consumeMouseEvent(event);
    }
}
