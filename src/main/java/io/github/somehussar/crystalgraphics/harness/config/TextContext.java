package io.github.somehussar.crystalgraphics.harness.config;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.harness.util.HarnessFontUtil;
import com.crystalgraphics.text.render.CgTextRenderer;

public class TextContext {

    public CgFont font = CgFont.load(HarnessFontUtil.resolveFontPath(null), CgFontStyle.REGULAR, 24);
    public CgTextRenderer renderer = CgTextRenderer.createManualSized();

    public PoseStack poseStack = new PoseStack();

    public void update(HarnessContext ctx) {
        renderer.context().updateOrtho(ctx.getScreenWidth(), ctx.getScreenHeight());
    }

    public void draw(String text, int x, int y, int rgba) {
        draw(text, x, y, rgba, poseStack);
    }

    public void draw(String text, int x, int y, int rgba, PoseStack pose) {
        renderer.draw().text(text).font(font).at(x, y).color(rgba).pose(pose).submit();
    }

    public void delete() {

        if (renderer != null) {
            renderer.delete();
            renderer = null;
        }
        if (font != null) {
            font.dispose();
            font = null;
        }
    }
}
