package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.render.texture.CgUiQuad;
import com.crystalgui.render.texture.CgUiSprite;
import dev.vfyjxf.taffy.style.*;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import com.crystalgui.core.input.SystemInput;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * Interactive harness scene that builds and renders a CrystalGUI DOM tree
 * using the immediate-mode {@link UIWindow} paint path.
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
public class CgUiTestScene implements InteractiveSceneLifecycle, SystemInput.Keyboard, SystemInput.Mouse {

    private UIWindow uiWindow;

    private UIElement hoveredElement;

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
        UIElement root =
//                createUISimple();
                createYogaExample();

        this.uiWindow = new UIWindow(Ui.of(root));
    }

    private UIElement createUISimple() {
        UIElement root = new UIElement()
                .generalStyle(s -> s.background(new CgUiQuad(0xFFFFFFFF)))
                .layout(l -> l.height(600).width(600).flexDirection(FlexDirection.COLUMN));

        root.addChild(
                new UIElement().generalStyle(s -> s.background(new CgUiQuad(0xFFFF0000)))
                        .layout(l -> l.widthPercent(100).height(300))
        );

        return root;
    }

    private UIElement createYogaExample() {

        UIElement root = new UIElement()
                .generalStyle(s -> s.background(backgroundMain))
                .layout(l -> l
                        .width(250)
                        .height(475)
                        .paddingAll(10)
                ).onMouseDown.attachListener(
                        (thiz, event) -> thiz.generalStyle(s -> s.color(s.color() == 0xFF00FF00 ? 0xFFFFFFFF : 0xFF00FF00)),
                        false, false);

        UIElement container = new UIElement().setId("Container")
                .generalStyle(s -> s.background(inset))
                .layout(l -> l
                        .widthPercent(100).heightPercent(100)
                        .flex(1)
                        .gapAll(10)
                        .flexDirection(FlexDirection.COLUMN)
                );
        root.addChild(container);

        UIElement header = new UIElement().setId("Header")
                .generalStyle(s -> s.background(inset))
                .layout(l -> l.height(60));
        container.addChild(header);

        UIElement mainWrapper = new UIElement().setId("wrapper")
                .setHitTest(false)
                .layout(l -> l
//                        .positionType(TaffyPosition.ABSOLUTE)   // pulls it out of container's flex flow entirely
//                        .widthPercent(100).heightPercent(100)   // stretches to fill container, top to bottom
                        .flexDirection(FlexDirection.ROW)
                        .justifyContent(AlignContent.CENTER)
                        .alignItems(AlignItems.CENTER)           // center main vertically within the full stretch too
                );
        container.addChild(mainWrapper); // still added before `content` — controls paint order, not flow anymore

        UIElement main = new UIElement().setId("main")
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
        mainWrapper.addChild(main);

        for (int i = 0; i < 3; i++) {
            UIElement button = new UIElement().setId("buttonMain"+i)
                    .generalStyle(s -> s.background(buttonSprite))
                    .layout(l -> l.width(40).height(40));
            main.addChild(button);
        }

        UIElement content = new UIElement().setId("content")
                .generalStyle(s -> s.background(inset).color(0xFFFF0000))
                .layout(l -> l.flex(2).marginBottom(72).marginLeft(10).marginRight(10));
        container.addChild(content);

        UIElement absolute = new UIElement().setId("absolute")
                .generalStyle(s -> s.background(overlay))
                .layout(l -> l
                        .positionType(TaffyPosition.ABSOLUTE)
                        .widthPercent(100).height(64)
                        .flexDirection(FlexDirection.ROW)
                        .alignItems(AlignItems.CENTER)
                        .justifyContent(AlignContent.SPACE_AROUND)
                        .bottom(0)
                );
        container.addChild(absolute);

        UIElement button1 = new UIElement().setId("button0")
                .generalStyle(s -> s
                        .background(buttonSprite)
                        .overlay(new CgUiSprite()
                                .setTexture("crystalgui:textures/gui/Spritesheet_UI_Flat.png")
                                .setTextureSizeReference(736, 288)
                                .setSprite(296, 233, 14, 14)
                        )
                )
                .layout(l -> l.width(40).height(40));
        absolute.addChild(button1);

        for (int i = 0; i < 3; i++) {
            UIElement button = new UIElement().setId("Header"+(1+i))
                    .generalStyle(s -> s.background(buttonSprite))
                    .layout(l -> l.width(40).height(40));
            absolute.addChild(button);
        }
        return root;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        uiWindow.init(ctx.getScreenWidth(), ctx.getScreenHeight());
        long timeMillis = System.currentTimeMillis();

        float value = (float) Math.sin(2 * Math.PI * timeMillis / 5000.0);
        uiWindow.ui.rootElement
                .getChildren().getFirst().getChildren().get(1).getChildren().getFirst().layout(
                        l -> l.widthPercent(60 + 40 * value).minWidth(60).maxHeight(160)
                );
//        uiWindow.setMouse(Mouse.getX(), ctx.getScreenHeight() - Mouse.getY());
        uiWindow.paintFrame();
        UIElement previousElement = this.hoveredElement;
//        this.hoveredElement = uiWindow.getHoveredElement(Mouse.getX(), ctx.getScreenHeight() - Mouse.getY());
//        if (this.hoveredElement != previousElement) {
//            if (this.hoveredElement == null) {
//                Display.setTitle("No element selected :(");
//            } else {
//                Display.setTitle(hoveredElement.getId() + "#");
//            }
//        }
//        Display.setTitle(String.format("%d, %d", Mouse.getX(), ctx.getScreenHeight() - Mouse.getY()));
    }

    @Override
    public void dispose() {
        uiWindow = null;
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
    public boolean consumeKeyboardEvent(SystemInput.Keyboard.Event event) {

//        if (event.pressed()) {
//            uiWindow.ui.rootElement.generalStyle(s -> {
//                   s.color(s.color() == 0xFF00FF00 ? 0xFFFFFFFF : 0xFF00FF00);
//            });
//
//            if(this.hoveredElement != null) {
//                this.hoveredElement.addChild(new UIElement().setId("button0")
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

        return uiWindow.getInputHandler().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(SystemInput.Mouse.Event event) {
        return uiWindow.getInputHandler().consumeMouseEvent(event);
    }
}
