package io.github.somehussar.crystalgraphics.harness.scene.ui;

import com.crystalgui.render.texture.CgUiSprite;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.core.input.SystemInput;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import dev.vfyjxf.taffy.style.JustifyContent;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import org.lwjgl.input.Keyboard;

/**
 * Interactive harness scene that exercises CrystalGUI's stylesheet + transition system:
 * class/id/pseudo-class selectors, combinators, {@code !important}, and CSS-{@code transition}
 * state animations (both a paint-only property and a layout property, to prove interpolated
 * values actually reach Taffy, not just {@code getComputed()}) — plus {@code background}
 * cross-fades ({@code CgUiCrossFade}, via {@code TextureProperty}'s interpolator) between
 * color/texture/9-slice drawable pairs, triggered by hovering the {@code .fade-*} swatches.
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
                width: 32;
                height: 32;
                transition: background-color 2000ms ease-in-out, width 200ms ease-out, height 200ms ease-out;
            }
            
            .button:hover {
                background-color: #FFFFFF;
                width: 60;
                height: 60;
            }

            .button.primary {
                background-color: #55AAFF;
            }
            .button:focus {
                background-color: #FF0000;
            }
            
            .button:active {
                width: 40;
                height: 40;
                background-color: #FFFF00;
            }

            #submit:disabled {
                background-color: #444444;
            }

            .panel > .button {
                margin-top: 2;
            }

            .color-swatch {
                background: #33CC9955;
                background-color: rgba(255, 0, 0, 0.25);
            }

            .sliced-swatch {
                background: sprite("crystalgui:textures/gui/gdp_styles.png", 154 165 16 16, 5 6 9 10);
            }

            /* image(...)'s new optional crop-rect arg: a plain (non-9-slice) sub-region of the atlas. */
            .cropped-swatch {
                background: image("crystalgui:textures/gui/gdp_styles.png", "154 165 16 16");
            }

            /* Background cross-fade demo (hover each swatch) — one entry per CgUiDrawable type
             * pairing, proving CgUiCrossFade works uniformly regardless of the concrete drawable
             * types on either side of the transition. */
            .fade-color-color {
                background: #3355AA;
                transition: background 2000ms ease-in-out;
            }
            .fade-color-color:hover {
                background: #E8B23DCC;
            }

            .fade-color-texture {
                background: #33AA66;
                transition: background 2000ms ease-in-out;
            }
            .fade-color-texture:hover {
                background: image("crystalgui:textures/gui/gdp_styles.png");
            }

            /* 9-slice <-> 9-slice with DIFFERENT border thicknesses (1,1,11,11 vs 5,6,9,10) —
             * CgUiCrossFade's draw-A-then-draw-B-on-top compositing has no per-pixel correspondence
             * to blend against when the two sides don't even share the same quad geometry, so this
             * looks rough by construction, not by bug. A real fix needs a dedicated 2-sampler
             * pixel-blend shader restricted to matching-geometry drawables — deliberately deferred,
             * not built here. Kept as a visible reference case, not polished. */
            .fade-texture-texture {
                background: sprite("crystalgui:textures/gui/gdp_styles.png", 29 1 13 13, 1 1 11 11);
                transition: background 2000ms ease-in-out;
            }
            .fade-texture-texture:hover {
                background: sprite("crystalgui:textures/gui/gdp_styles.png", 154 165 16 16, 5 6 9 10);
            }

            /* border-radius/border-width/border-color are a universal wrapping layer, not a special
             * background value type — they apply on top of whatever `background:` resolves to
             * (real CSS semantics). Transitions animate the underlying scalar/color longhands
             * directly (no shape-morph special case needed at the drawable level anymore). */
            .fade-sdf-color {
                background: #3355AA;
                border-radius: 24px;
                border-width: 2px;
                border-color: #000000;
                transition: background 2000ms ease-in-out, border-radius 2000ms ease-in-out,
                            border-width 2000ms ease-in-out, border-color 2000ms ease-in-out;
            }
            .fade-sdf-color:hover {
                background: #E8B23D;
                border-radius: 40px;
                border-width: 5px;
                border-color: #224488;
            }

            .fade-sdf-texture {
                background: #3355AA;
                border-radius: 40px;
                border-width: 3px;
                border-color: #224488;
                transition: background 2000ms ease-in-out;
            }
            .fade-sdf-texture:hover {
                background: image("crystalgui:textures/gui/gdp_styles.png");
            }

            /* Per-corner radii, CSS border-radius order (TL TR BR BL): only the top edge is
             * rounded, bottom corners stay square. A 9-slice/sprite background can't be visually
             * clipped by this layer yet (documented gap) — this swatch uses a flat color fill so
             * the rounding/border are actually visible. */
            .rounded-corners-swatch {
                background: #EE8822;
                border-radius: 14px 14px 0px 0px;
                border-width: 2px;
                border-color: #224488;
            }

            /* Visual Layers: clip: mask (the "overflow" style enum's CSS property is named "clip",
             * not "overflow"). The child is deliberately bigger than the parent
             * (negative margin pokes it toward the top-left corner) so it visibly overflows the
             * rounded shape — with the mask active, that overflow should be clipped to the inner
             * rounded region; without it (compare .mask-off-swatch), it spills past the corner. */
            .mask-swatch, .mask-off-swatch {
                background: #33AA66;
                border-radius: 16px;
                border-width: 3px;
                border-color: #224488;
            }
            .mask-swatch {
                clip: mask;
            }
            .mask-child {
                background: #FF4444;
                width: 40;
                height: 40;
                margin-left: -12;
                margin-top: -12;
            }

            /* Visual Layers: fractional opacity isolation. Two overlapping translucent children —
             * with isolation, they blend against each other first, then the whole group fades as
             * one unit (no seam at the overlap); without it (.opacity-off-swatch's children, which
             * paint directly against the parent's own already-opaque backdrop with no group
             * isolation), the overlap region visibly double-blends. */
            .opacity-swatch {
                opacity: 0.5;
            }
            .opacity-off-swatch {
            }
            .opacity-child-a {
                background: rgba(255, 0, 0, 0.5);
                width: 30;
                height: 30;
            }
            .opacity-child-b {
                background: rgba(0, 128, 255, 0.6);
                width: 30;
                height: 30;
                margin-left: -15;
                margin-top: 15;
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
                        .width(420)
                        .height(320)
                        .paddingAll(16)
                        .flexDirection(FlexDirection.ROW)
                        .flexWrap(FlexWrap.WRAP)
                        .gapAll(12)
                        .justifyContent(AlignContent.CENTER)
                        .alignItems(AlignItems.CENTER)
                ).setFocusPolicy(FocusPolicy.NONE);
        root.addClass("panel");

//        UIElement row = new UIElement()
//                .layout(l -> l
//                        .flexDirection(FlexDirection.ROW)
//                        .gapAll(10)
//                        .alignItems(AlignItems.CENTER)
//                );
//        root.addChild(row);

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
//        for (int i = 0; i < 3; i++) {
//            UIElement button = new UIElement()
//                    .generalStyle(s -> s.background(buttonSprite));
//            button.addClass("button");
//
//            if (i == 0) {
//                button.setId("submit");
////                button.setEnabled(false);
//            } else if (i == 1) {
//                button.addClass("primary");
//            }
//
//            root.addChild(button);
//        }
//
//        // SDF rounded-rect smoke-test: border-radius/border-width/border-color as a universal
//        // wrapping layer over a flat-color background, exercised at runtime so
//        // gui_rounded_rect.shader actually compiles under real GL, not just javac. border-width
//        // (set via .borderAll below) now grows the layout box for real — it's the same
//        // border-width-* longhand Taffy resolves, not a bespoke SDF-only number.
//        UIElement roundedButton = new UIElement()
//                .generalStyle(s -> s.background(new com.crystalgui.render.texture.CgUiQuad(0xFFEE8822))
//                        .borderRadius(10f)
//                        .borderColor(0xFF224488))
//                .layout(l -> l.width(48).height(48).borderAll(3));
//        root.addChild(roundedButton);
//
//        // Phase 7 smoke-test: background/background-color parsers exercised through the real
//        // stylesheet pipeline (not constructed directly in Java), plus the new sprite(...) CSS
//        // function for 9-slice-from-CSS.
//        UIElement colorSwatch = new UIElement().layout(l -> l.width(24).height(48));
//        colorSwatch.addClass("color-swatch");
//        root.addChild(colorSwatch);
//
//        UIElement slicedSwatch = new UIElement().layout(l -> l.width(32).height(32));
//        slicedSwatch.addClass("sliced-swatch");
//        root.addChild(slicedSwatch);
//
//        UIElement croppedSwatch = new UIElement().layout(l -> l.width(32).height(32));
//        croppedSwatch.addClass("cropped-swatch");
//        root.addChild(croppedSwatch);
//
//        // Background cross-fade demo — move the mouse over each swatch to trigger the
//        // `background 700ms ease-in-out` transition. Each pairs a different CgUiDrawable
//        // combination on either side of the fade:
//        UIElement fadeColorColor = new UIElement().layout(l -> l.width(48).height(48)); // flat color -> flat color
//        fadeColorColor.addClass("fade-color-color");
//        root.addChild(fadeColorColor);
//
//        UIElement fadeColorTexture = new UIElement().layout(l -> l.width(48).height(48)); // flat color -> full texture
//        fadeColorTexture.addClass("fade-color-texture");
//        root.addChild(fadeColorTexture);
//
//        UIElement fadeTextureTexture = new UIElement().layout(l -> l.width(48).height(48)); // 9-slice -> 9-slice
//        fadeTextureTexture.addClass("fade-texture-texture");
//        root.addChild(fadeTextureTexture);
//
//        UIElement fadeSdfColor = new UIElement().layout(l -> l.width(48).height(48)); // SDF rounded rect -> SDF rounded rect (color fill)
//        fadeSdfColor.addClass("fade-sdf-color");
//        root.addChild(fadeSdfColor);
//
//        UIElement fadeSdfTexture = new UIElement().layout(l -> l.width(48).height(48)); // SDF rounded rect, color fill -> texture fill
//        fadeSdfTexture.addClass("fade-sdf-texture");
//        root.addChild(fadeSdfTexture);
//
//        UIElement roundedCorners = new UIElement().layout(l -> l.width(48).height(48)); // per-corner radii: top rounded, bottom square
//        roundedCorners.addClass("rounded-corners-swatch");
//        root.addChild(roundedCorners);
//
//        // Visual Layers smoke-test: clip: mask, compare against an identical but unmasked
//        // sibling to see the difference the mask makes to the overflowing child.
//        UIElement maskOn = new UIElement().layout(l -> l.width(48).height(48).paddingAll(4));
//        maskOn.addClass("mask-swatch");
//        UIElement maskOnChild = new UIElement();
//        maskOnChild.addClass("mask-child");
//        maskOn.addChild(maskOnChild);
//        root.addChild(maskOn);
//
//        UIElement maskOff = new UIElement().layout(l -> l.width(48).height(48).paddingAll(4));
//        maskOff.addClass("mask-off-swatch");
//        UIElement maskOffChild = new UIElement();
//        maskOffChild.addClass("mask-child");
//        maskOff.addChild(maskOffChild);
//        root.addChild(maskOff);
//
//        // Visual Layers smoke-test: fractional opacity isolation, compare against an unisolated
//        // sibling with the same overlapping translucent children — the overlap region should look
//        // different (no double-blend seam with isolation, a visible seam without it).
//        UIElement opacityOn = new UIElement().layout(l -> l.width(48).height(48));
//        opacityOn.addClass("opacity-swatch");
//        UIElement opacityOnA = new UIElement();
//        opacityOnA.addClass("opacity-child-a");
//        UIElement opacityOnB = new UIElement();
//        opacityOnB.addClass("opacity-child-b");
//        opacityOn.addChild(opacityOnA);
//        opacityOn.addChild(opacityOnB);
//        root.addChild(opacityOn);

        UIElement opacityOff = new UIElement().layout(l -> l.width(48).height(48));
        opacityOff.addClass("opacity-off-swatch");
        UIElement opacityOffA = new UIElement();
        opacityOffA.addClass("opacity-child-a");
        UIElement opacityOffB = new UIElement();
        opacityOffB.addClass("opacity-child-b");
        opacityOff.addChild(opacityOffA);
        opacityOff.addChild(opacityOffB);
        UIElement noParentB = new UIElement();
        noParentB.addClass("opacity-child-a");
        root.addChild(noParentB);
//        root.addChild(opacityOff);

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
