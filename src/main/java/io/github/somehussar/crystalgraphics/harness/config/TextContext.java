package io.github.somehussar.crystalgraphics.harness.config;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgTextLayoutBuilder;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.util.HarnessFontUtil;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.text.render.CgTextRenderContext;
import com.crystalgraphics.text.render.CgTextRenderer;

public class TextContext {

    public CgFontRegistry registry = new CgFontRegistry();
    public CgFont font = CgFont.load(HarnessFontUtil.resolveFontPath(null), CgFontStyle.REGULAR, 24);
    public CgTextRenderer renderer = CgTextRenderer.create(CgCapabilities.detect(), registry);

    public CgTextLayoutBuilder layoutBuilder = new CgTextLayoutBuilder();

    public CgTextRenderContext orthoContext = CgTextRenderContext.orthographic(HarnessContext.DEFAULT_WIDTH,
            HarnessContext.DEFAULT_HEIGHT);

    public PoseStack poseStack = new PoseStack();

    public void update(HarnessContext ctx) {
        orthoContext.updateOrtho(ctx.getScreenWidth(), ctx.getScreenHeight());
    }

    public void draw(String text, int x, int y, int rgba, FrameInfo frame) {
        draw(text, x, y, rgba, poseStack, frame);
    }

    public void draw(String text, int x, int y, int rgba, PoseStack pose, FrameInfo frame) {
        renderer.draw(text, font, x, y, rgba, frame.getFrameNumber(), orthoContext, pose);
    }

    public void delete() {

        if (renderer != null) {
            renderer.delete();
            renderer = null;
        }
        if (registry != null) {
            registry.releaseAll();
            registry = null;
        }
        if (font != null) {
            font.dispose();
            font = null;
        }
    }
}
