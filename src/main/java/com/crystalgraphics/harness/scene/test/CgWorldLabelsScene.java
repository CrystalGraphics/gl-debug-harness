package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessFontUtil;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.text.style.CgTextLayer;
import com.crystalgraphics.text.style.CgTextStyle;

/**
 * World labels under load: a square grid of {@code -Dcrystalgraphics.harness.labels=<n>} (400) labels, each outlined
 * and shadowed, near ones large and far ones small, so a frame mixes raster tiers and shadow cells. Profile it with the
 * {@code crystalgraphics.text} and {@code crystalgraphics.gl} channels for flushes and transitions per frame.
 *
 * <p>{@code .labels.changing=true} makes some change, for the retained labels' rewrites: every 16th counts up every 4
 * frames, every 32nd changes colour every 30, and the last eighth are gone for frames 90 to 179 of every 180.</p>
 */
public final class CgWorldLabelsScene implements InteractiveSceneLifecycle {

    private static final int COUNT = Integer.getInteger("crystalgraphics.harness.labels", 400);
    private static final boolean CHANGING = Boolean.getBoolean("crystalgraphics.harness.labels.changing");
    private static final float SPACING = 3f;
    /**
     * Outlined 0.08 em in black over a soft drop shadow, at the font's 48 px: lengths in pixels at that size, so the
     * shadow sits 3 px down and right with a sigma of 2.
     */
    private static final CgTextStyle LABEL = CgTextStyle.builder("harness:world_label").size(48f * 72f / 96f)
            .layer(CgTextLayer.shadow("drop").offset(3f, 3f).blur(2f).paint(0xC0000000))
            .layer(CgTextLayer.outline("outline").width(0.08f * 48f).paint(0xFF000000))
            .layer(CgTextLayer.fill("fill")).build();

    private final String[] texts = new String[COUNT];
    private CgFont font;

    @Override
    public void init(HarnessContext ctx) {
        int side = (int) Math.ceil(Math.sqrt(COUNT));
        ctx.getCamera3D().moveCamera(0f, 6f, side * SPACING * 0.5f + 8f);
        ctx.getCamera3D().setPitch(-12f);
        ctx.getCamera3D().setMoveSpeed(12f);
        font = CgFont.load(HarnessFontUtil.LATIN_FONT, CgFontStyle.REGULAR, 48);
        for (int i = 0; i < COUNT; i++) texts[i] = "label " + i;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();
        int side = (int) Math.ceil(Math.sqrt(COUNT));
        float half = (side - 1) * SPACING * 0.5f;
        long n = frame.getFrameNumber();
        int shown = CHANGING && (n / 90) % 2 == 1 ? COUNT - COUNT / 8 : COUNT;
        for (int i = 0; i < shown; i++) {
            int col = i % side, row = i / side;
            String text = CHANGING && i % 16 == 7 ? texts[i] + ": " + n / 4 : texts[i];
            int color = CHANGING && i % 32 == 3 && (n / 30) % 2 == 1 ? 0xFFFFD040 : 0xFFFFFFFF;
            world.text(text).at(col * SPACING - half, 1.5, row * SPACING - half).height((i & 3) == 0 ? 0.9f : 0.4f)
                    .font(font).color(color).style(LABEL).submit();
        }
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    @Override
    public void dispose() {
        if (font != null) font.dispose();
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
