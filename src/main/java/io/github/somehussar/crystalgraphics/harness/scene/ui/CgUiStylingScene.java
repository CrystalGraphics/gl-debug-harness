package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgui.render.texture.CgUiSprite;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.core.input.SystemInput;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import org.lwjgl.input.Keyboard;

/**
 * Interactive harness scene that exercises CrystalGUI's stylesheet + transition system:
 * class/id/pseudo-class selectors, combinators, {@code !important}, and CSS-{@code transition}
 * state animations (both a paint-only property and a layout property, to prove interpolated
 * values actually reach Taffy, not just {@code getComputed()}).
 *
 * <p>Built on the same real {@link UIWindow}/input plumbing as {@link CgUiTestScene}, so
 * {@code :hover}/{@code :active} are driven by genuine mouse input in the harness window — move
 * the cursor over a button, click and hold it, tab between them (focus policy is default CLICK).</p>
 *
 * <p>Register in {@link io.github.somehussar.crystalgraphics.harness.SceneRegistry}
 * under scene id {@code "cgui-styling"}.</p>
 */
public class CgUiStylingScene implements InteractiveSceneLifecycle, SystemInput.Keyboard, SystemInput.Mouse {

    private UIWindow uiWindow;

    private final CgUiSprite panelSprite = new CgUiSprite()
            .setTexture("crystalgui:textures/gui/gdp_styles.png")
            .setTextureSizeReference(256, 256)
            .setSprite(29, 1, 13, 13)
            .setBorder(1, 1, 11, 11);

    private final CgUiSprite buttonSprite = panelSprite.copy()
            .setSprite(154, 165, 16, 16)
            .setBorder(5, 6, 9, 10);

    /**
     * Deliberately mirrors the documented CSS subset end to end: universal, class, compound
     * ({@code .button.primary}), id, pseudo-classes, the {@code >} child combinator,
     * {@code !important}, and two {@code transition:} entries — one on a paint-only property
     * ({@code color}), one on layout properties ({@code width}/{@code height}) so the demo also
     * proves the transition tick reaches TaffyBridge, not just the computed-style cache.
     */
    private static final String STYLE_SHEET = """
            * { z-index: 0; }

            .button {
                color: #AAAAAA;
                width: 32;
                height: 32;
                transition: color 1000ms ease-in-out, width 200ms ease-out, height 200ms ease-out;
            }

            .button:hover {
                color: #FFFFFF;
                width: 60;
                height: 60;
            }

            .button:active {
                width: 40;
                height: 40;
                color: #FFFF00;
            }

            .button.primary {
                color: #55AAFF !important;
            }

            #submit:disabled {
                color: #444444;
            }

            .panel > .button {
                margin-top: 2;
            }
            """;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void init(HarnessContext ctx) {
        Keyboard.enableRepeatEvents(true);
        UIElement root = createStylingDemo();
        this.uiWindow = new UIWindow(Ui.of(root));
        this.uiWindow.getStyleEngine().addStylesheet(StyleSheet.parse(STYLE_SHEET));
    }

    private UIElement createStylingDemo() {
        UIElement root = new UIElement()
                .generalStyle(s -> s.background(panelSprite))
                .layout(l -> l
                        .width(300)
                        .height(150)
                        .paddingAll(16)
                        .flexDirection(FlexDirection.COLUMN)
                        .gapAll(12)
                );
        root.addClass("panel");

        UIElement row = new UIElement()
                .layout(l -> l
                        .flexDirection(FlexDirection.ROW)
                        .gapAll(10)
                        .alignItems(AlignItems.CENTER)
                );
        root.addChild(row);

        // button 0: "#submit", starts disabled -> demonstrates the #id:pseudo-class combo
        // button 1: "primary" class -> demonstrates compound selector + !important
        // button 2: plain "button" -> baseline hover/active transition
        //
        // NOTE: base width/height come from the ".button" stylesheet rule, not from a Java
        // .layout(l -> l.width(...)) call here. Setting a property via .layout()/.generalStyle()
        // creates an INLINE-origin candidate, and INLINE always outranks STYLESHEET regardless of
        // selector specificity (same as a real `style=""` attribute beating any non-!important CSS
        // rule) — so a ".button:hover { width: ... }" rule could never win against an inline base
        // width, and the property would never appear to update or transition at all.
        for (int i = 0; i < 3; i++) {
            UIElement button = new UIElement()
                    .generalStyle(s -> s.background(buttonSprite));
            button.addClass("button");

            if (i == 0) {
                button.setId("submit");
                button.setEnabled(false);
            } else if (i == 1) {
                button.addClass("primary");
            }

            row.addChild(button);
        }

        return root;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        uiWindow.init(ctx.getScreenWidth(), ctx.getScreenHeight());
        uiWindow.paintFrame();
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
        return uiWindow.getInputHandler().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(SystemInput.Mouse.Event event) {
        return uiWindow.getInputHandler().consumeMouseEvent(event);
    }
}
