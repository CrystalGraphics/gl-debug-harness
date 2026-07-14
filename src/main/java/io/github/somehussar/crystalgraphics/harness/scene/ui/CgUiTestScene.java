package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgui.UIElement;
import com.crystalgui.Ui;
import com.crystalgui.UiRuntime;
import com.crystalgui.texture.CgUiQuad;
import com.crystalgui.texture.CgUiSprite;
import dev.vfyjxf.taffy.geometry.TaffyRect;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import dev.vfyjxf.taffy.style.TaffyPosition;
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
        UIElement root = new UIElement();
        root.layout(l -> l
                .width(250).height(475)
                .padding(10)
        );
//        root.setBackground(new CgUiQuad(0xFF1C1E21));

        CgUiSprite backgroundMain = new CgUiSprite()
                .setTexture("crystalgui:textures/gui/Spritesheet_UI_Flat.png")
                .setTextureSizeReference(736, 288)
                .setSprite(128, 32, 96, 64)
                .setBorder(3, 5, 92, 58);

        CgUiSprite inset = backgroundMain.copy()
                .setSprite(160, 128, 32, 32)
                .setBorder(3, 4, 28, 27);

        root.setBackground(backgroundMain);

        UIElement container = new UIElement();
        container.setBackground(inset);
        container.layout(l -> l.widthPercent(1).heightPercent(1).flex(1).gap(10).flexDirection(FlexDirection.COLUMN));
        root.addChild(container);

        UIElement header = new UIElement();
        header
                .setColor(0xFFDDDDDD)
                .setBackground(inset)
                .layout(l -> l.height(60));
        container.addChild(header);

        UIElement main = new UIElement();
        main.setBackground(inset);
        main.layout(l -> l.flex(1).margin(10, 0, 10, 0));
        container.addChild(main);

        UIElement content = new UIElement();
        content.setBackground(inset);
        content.layout(l -> l.flex(2).margin(10, 0, 10, 0).margin(10,0,10,72));
        container.addChild(content);

        UIElement absolute = new UIElement();
        absolute.setBackground(new CgUiQuad(0x55606770));
        absolute.layout(l -> l
                .position(TaffyPosition.ABSOLUTE)
                .widthPercent(1).height(64)
                .flexDirection(FlexDirection.ROW)
                .alignItems(AlignItems.CENTER)
                .justifyContent(AlignContent.SPACE_AROUND)
                .raw().inset = new TaffyRect<>(
                        LengthPercentageAuto.AUTO,
                        LengthPercentageAuto.AUTO,
                        LengthPercentageAuto.AUTO,
                        LengthPercentageAuto.ZERO));
        container.addChild(absolute);

        UIElement button1 = new UIElement();
//        button1.setBackground(new CgUiSprite("crystalgui:textures/gui/Spritesheet_UI_Flat.png",
//                (236f) / 736f,
//                (233f) / 288f,
//                (236f + 14f) / 736f,
//                (233f + 14f) / 288f
//        ));
        button1.setOverlay(new CgUiSprite()
                .setTexture("crystalgui:textures/gui/Spritesheet_UI_Flat.png")
                .setSprite(296, 233, 14, 14)
                .setTextureSizeReference(736, 288)
        ).setBackground(inset);
        button1.layout(l -> l.width(40).height(40));
        absolute.addChild(button1);

        for (int i = 0; i < 3; i++) {
            UIElement button = new UIElement();
            button.setBackground(inset);
            button.layout(l -> l.width(40).height(40));
            absolute.addChild(button);
        }

        this.uiRuntime = new UiRuntime(Ui.of(root));
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getViewport().getWidth();
        int h = ctx.getViewport().getHeight();
        uiRuntime.resize(w/2, h/2);
        uiRuntime.setMouse(Mouse.getX() / 2f, ((float) h /2) - Mouse.getY() / 2f);
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
