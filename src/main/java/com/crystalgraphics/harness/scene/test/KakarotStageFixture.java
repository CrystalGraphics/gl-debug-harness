package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.render.post.CgPostMaterial;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.post.volume.CgPostVolume;
import com.crystalgraphics.render.stage.CgRenderStage;

import javax.annotation.Nullable;

/**
 * Kakarot's ocean arena (Area02) as a parity fixture: the post chain its battle frames draw around a character, in the
 * cut capture's order (kamehameha_cut_charge.rdc). Placed only to match a capture, never as a stage of the game's.
 *
 * <pre>{@code
 * KakarotStageFixture stage = new KakarotStageFixture(kit.material(charaIndex).get(), kit.material(postIndex).get());
 * stage.on(true);    // E in skinned-play
 * stage.on(false);
 * }</pre>
 *
 * <ul>
 *   <li>Before translucency, after the battle outline: the height fog (4394), then Area02_CharaColor (4415), then
 *       whatever a skill grades. After the world: Unreal 4.21's legacy tonemapper with Kakarot's film heal and its 0.5
 *       vignette (5151), then Area02_PostProcess (5185), before a skill's radial blur.</li>
 *   <li>The fog's values and the dummy sun are the capture's: the kit carries neither. The fog's heights put the
 *       harness's cut camera at the capture's, 7959 cm.</li>
 *   <li>The tonemapper reads the HDR scene: keep it on (G).</li>
 * </ul>
 */
final class KakarotStageFixture {

    /** Ahead of a skill's volumes, behind the battle outline: the order the arena's chain draws in. */
    private static final int FOG_ORDER = -900_000, CHARA_ORDER = -800_000, OVERLAY_ORDER = -800_000;
    /** The capture's camera height less the cut camera's in the harness (about 1.1 m), cm. */
    private static final float FOG_HEIGHT_OFFSET = 7959f - 110f;

    private final CgPostMaterial fog, chara, overlay;
    @Nullable
    private CgRenderStage.Registration fogOn, charaOn, overlayOn;
    @Nullable
    private CgPostVolume tonemap;

    KakarotStageFixture(CgMaterialDefinition charaColor, CgMaterialDefinition postProcess) {
        CgMaterial fogMaterial = CgMaterial.newInstance("kakarot_kit:shaders/post/height_fog.shader");
        // Its ExponentialHeightFog as 4394 reads it: cb1[0..2]
        fogMaterial.applyProperties(b -> b.set1f("FogDensity", 2e-6f).set1f("FogHeightFalloff", 1e-4f)
                .set1f("FogHeight", 15799f).vec4("FogInscatteringColor", 0.41f, 0.89f, 1f, 1f)
                .set1f("FogMaxOpacity", 0.65f).set1f("FogStartDistance", 30000f).set1f("FogCutoffDistance", 4e6f)
                .set1f("HeightOffset", FOG_HEIGHT_OFFSET));
        fog = CgPostMaterial.of(fogMaterial, CgPostPoint.BEFORE_TRANSLUCENCY, FOG_ORDER);
        chara = CgPostMaterial.of(CgMaterial.of(charaColor), CgPostPoint.BEFORE_TRANSLUCENCY, CHARA_ORDER);
        CgMaterial overlayMaterial = CgMaterial.of(postProcess);
        // MP_LightVector's dummy sun at the capture: cb2[3] and cb2[4] of 5185
        overlayMaterial.applyProperties(b -> b.vec4("DummySunVector", 0.814867f, -0.468214f, 0.341713f, 0f)
                .vec4("DummySunVectorR", 0.498203f, 0.86706f, 0f, 0f));
        overlay = CgPostMaterial.of(overlayMaterial, CgPostPoint.AFTER_COMPOSITE, OVERLAY_ORDER);
    }

    boolean on() {
        return tonemap != null;
    }

    void on(boolean on) {
        if (on == on()) return;
        if (on) {
            CgPostStack stack = CgPostStack.get();
            fogOn = stack.add(fog);
            charaOn = stack.add(chara);
            tonemap = stack.volume(0, new CgPostSettings().tonemap().filmHeal(0.18f).vignette(0.5f));
            overlayOn = stack.add(overlay);
        } else {
            fogOn.close();
            charaOn.close();
            tonemap.close();
            overlayOn.close();
            fogOn = charaOn = overlayOn = null;
            tonemap = null;
        }
    }
}
