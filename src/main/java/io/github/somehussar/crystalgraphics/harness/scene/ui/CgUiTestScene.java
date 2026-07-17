package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgui.UIElement;
import com.crystalgui.Ui;
import com.crystalgui.UiRuntime;
import com.crystalgui.texture.CgUiQuad;
import com.crystalgui.texture.CgUiSprite;
import dev.vfyjxf.taffy.style.*;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import org.lwjgl.input.Mouse;

/**
 * Interactive harness scene that builds and renders a CrystalGUI DOM tree
 * using the immediate-mode {@link UiRuntime} paint path.
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
public class CgUiTestScene implements InteractiveSceneLifecycle {

    private UiRuntime uiRuntime;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void init(HarnessContext ctx) {
        UIElement root =
//                createUISimple();
                createYogaExample();

        this.uiRuntime = new UiRuntime(Ui.of(root));
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

        UIElement root = new UIElement()
                .generalStyle(s -> s.background(backgroundMain))
                .layout(l -> l
                        .width(250)
                        .height(475)
                        .paddingAll(10)
                );


        UIElement container = new UIElement()
                .generalStyle(s -> s.background(inset))
                .layout(l -> l
                        .widthPercent(100).heightPercent(100)
                        .flex(1)
                        .gapAll(10)
                        .flexDirection(FlexDirection.COLUMN)
                );
        root.addChild(container);

        UIElement header = new UIElement()
                .generalStyle(s -> s.background(inset))
                .layout(l -> l.height(60));
        container.addChild(header);

        UIElement mainWrapper = new UIElement()
                .layout(l -> l
                        .flexGrow(0)              // don't consume leftover vertical space
                        .flexShrink(0)            // don't get compressed either, if you want a hard floor
                        .heightAuto()
                        .marginLeft(10).marginRight(10)
                        // no alignSelf(CENTER) — let it stretch, so it has a definite width
                        .flexDirection(FlexDirection.ROW)      // new
                        .justifyContent(AlignContent.CENTER)   // centers `main` when main < 100% wide
                );
        container.addChild(mainWrapper);

        UIElement main = new UIElement()
                .generalStyle(s -> s.background(inset))
                .layout(l -> l
                        .widthPercent(100) // this now resolves against mainWrapper's definite width
                        .flexDirection(FlexDirection.ROW)
                        .flexWrap(FlexWrap.WRAP)
                        .paddingAll(10)
                        .alignContent(AlignContent.CENTER)
                        .alignItems(AlignItems.CENTER)
                        .justifyContent(AlignContent.SPACE_AROUND)
                );
        mainWrapper.addChild(main);

        for (int i = 0; i < 3; i++) {
            UIElement button = new UIElement()
                    .generalStyle(s -> s.background(buttonSprite))
                    .layout(l -> l.width(40).height(40));
            main.addChild(button);
        }

        UIElement content = new UIElement()
                .generalStyle(s -> s
                        .background(inset)
                        .color(0xFFFF0000)
                )
                .layout(l -> l.flex(2).marginBottom(72)); // gap already provides the top spacing
        container.addChild(content);

        UIElement absolute = new UIElement()
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

        UIElement button1 = new UIElement()
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
            UIElement button = new UIElement()
                    .generalStyle(s -> s.background(buttonSprite))
                    .layout(l -> l.width(40).height(40));
            absolute.addChild(button);
        }
        return root;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        uiRuntime.resize(ctx.getScreenWidth(), ctx.getScreenHeight());
        long timeMillis = System.currentTimeMillis();

        float value = (float) Math.sin(2 * Math.PI * timeMillis / 5000.0);
        uiRuntime.ui.rootElement.layout(l -> {
//            l.height(475 + value*10);
                })
                .getChildren().getFirst().getChildren().get(1).getChildren().getFirst().layout(
                        l -> l.widthPercent(60 + 40 * value).minWidth(60)
                );
        uiRuntime.setMouse(Mouse.getX(), ctx.getScreenHeight() - Mouse.getY());
        uiRuntime.paintFrame();
    }

    @Override
    public void dispose() {
        uiRuntime = null;
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
}
