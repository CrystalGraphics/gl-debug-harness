package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.render.post.CgPostMaterial;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.stage.CgRenderStage;

import javax.annotation.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A Kakarot skill's darkening while its action runs: what the game's post-effect controller does with the action's
 * DarkEffect defaults (UseDarkEffect, DarkEffectPower, DarkEffectFadein, DarkEffectFadeout), the background scaled
 * by PE_DarkenInTheGround. RPG-Core plays it in the game; skinned-play here, for K's and X's moves.
 *
 * <pre>{@code
 * KakarotSkillDarken darken = KakarotSkillDarken.of(actionFile, kit.material(darkenIndex).get());
 * darken.update(dt, actor.move != null);   // each frame
 * darken.close();
 * }</pre>
 *
 * <ul>
 *   <li>Drawn before translucency after the stage's colouring and before a skill's grade, as the cut capture's
 *       4415, 4447, 4460.</li>
 *   <li>Fades in over DarkEffectFadein from the action's start and out over DarkEffectFadeout from its end, linearly,
 *       as the controller's request lerps DarkPower between 1 and the target.</li>
 *   <li>The start and end times (0, 0) are read as the whole action, which every capture inside a skill shows.</li>
 * </ul>
 */
final class KakarotSkillDarken implements AutoCloseable {

    /** After the stage's CharaColor, before a skill volume's grade. */
    private static final int ORDER = -700_000;

    private final CgPostMaterial post;
    private final CgRenderStage.Registration on;
    private final float fadeIn, fadeOut;
    private float weight;
    /** The weight the fade-out started from, and its age; -1 while the action runs. */
    private float outFrom = -1f, outAge;

    private KakarotSkillDarken(CgMaterialDefinition darken, float power, float fadeIn, float fadeOut) {
        CgMaterial material = CgMaterial.of(darken);
        material.applyProperties(b -> b.set1f("DarkPower", power));
        post = CgPostMaterial.of(material, CgPostPoint.BEFORE_TRANSLUCENCY, ORDER);
        post.weight(0f);
        on = CgPostStack.get().add(post);
        this.fadeIn = fadeIn;
        this.fadeOut = fadeOut;
    }

    /** The action file's darkening, or null where UseDarkEffect is false or the file says nothing of it. */
    @Nullable
    static KakarotSkillDarken of(Path actionFile, CgMaterialDefinition darken) throws IOException {
        Object tree = CgAssetYaml.read(Files.readString(actionFile));
        Map<?, ?> defaults = null;
        if (tree instanceof Map<?, ?> m) {
            for (Object v : m.values()) {
                if (v instanceof Map<?, ?> block && block.containsKey("UseDarkEffect")) defaults = block;
            }
        }
        if (defaults == null || !Boolean.TRUE.equals(defaults.get("UseDarkEffect"))) return null;
        return new KakarotSkillDarken(darken, number(defaults, "DarkEffectPower", 0.5f),
                number(defaults, "DarkEffectFadein", 0.1f), number(defaults, "DarkEffectFadeout", 0.2f));
    }

    private static float number(Map<?, ?> m, String key, float otherwise) {
        return m.get(key) instanceof Number n ? n.floatValue() : otherwise;
    }

    /** Each frame: in from 0 while the action runs; out from wherever it was over the whole fade-out once it ends. */
    void update(float dt, boolean running) {
        if (running) {
            outFrom = -1f;
            weight = fadeIn <= 0f ? 1f : Math.min(1f, weight + dt / fadeIn);
        } else if (weight > 0f) {
            if (outFrom < 0f) {
                outFrom = weight;
                outAge = 0f;
            }
            outAge += dt;
            weight = fadeOut <= 0f ? 0f : Math.max(0f, outFrom * (1f - outAge / fadeOut));
        }
        post.weight(weight);
    }

    @Override
    public void close() {
        on.close();
    }
}
