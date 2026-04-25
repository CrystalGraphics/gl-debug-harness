package io.github.somehussar.crystalgraphics.harness.config;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.PoseStack;
import io.github.somehussar.crystalgraphics.api.font.CgFont;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.font.CgTextLayoutBuilder;
import io.github.somehussar.crystalgraphics.gl.render.CgDynamicTextureRenderLayer;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.util.HarnessFontUtil;
import io.github.somehussar.crystalgraphics.text.cache.CgFontRegistry;
import io.github.somehussar.crystalgraphics.text.render.CgTextLayers;
import io.github.somehussar.crystalgraphics.text.render.CgTextRenderContext;
import io.github.somehussar.crystalgraphics.text.render.CgTextRenderer;

public class TextContext {

    public CgFontRegistry registry = new CgFontRegistry();
    public CgFont font = CgFont.load(HarnessFontUtil.resolveFontPath(null), CgFontStyle.REGULAR, 24);
    public CgTextRenderer renderer = CgTextRenderer.create(CgCapabilities.detect(), registry);

    public CgTextLayoutBuilder layoutBuilder = new CgTextLayoutBuilder();

    public CgDynamicTextureRenderLayer textLayer = CgTextLayers.msdf(CgTextRenderer.MSDF_SHADER);

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
        textLayer.begin(orthoContext.getProjection());
        renderer.draw(textLayer, text, font, x, y, rgba, frame.getFrameNumber(), orthoContext, pose);
        textLayer.end();
    }
    

    public void delete() {

        if (textLayer != null) {
            textLayer.delete();
            textLayer = null;
        }
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
