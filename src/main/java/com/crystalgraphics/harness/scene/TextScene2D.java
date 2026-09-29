package com.crystalgraphics.harness.scene;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.text.atlas.CgGlyphAtlasPage;
import com.crystalgraphics.text.render.context.CgTextRenderContext;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgraphics.text.msdf.CgMsdfAtlasConfig;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.config.TextSceneConfig;
import com.crystalgraphics.harness.util.HarnessFontUtil;
import com.crystalgraphics.harness.util.HarnessOutputDir;
import com.crystalgraphics.harness.util.ScreenshotUtil;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.api.text.CgTextLayout;


import java.io.File;
import java.util.List;
import java.util.logging.Logger;

public class TextScene2D implements HarnessSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(TextScene2D.class.getName());

    // Matches CrystalGraphicsFontDemo.DEMO_TEXT_2D_LABEL exactly
    private static final String TOP_LABEL_TEXT = "2D UI text: logical size stable, raster scales with pose";
    // Matches CrystalGraphicsFontDemo top-label color (green, 0xAAFFAAFF packed RGBA)
    private static final int TOP_LABEL_COLOR = 0xAAFFAAFF;

    @Override
    public void init(HarnessContext ctx) {
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // Typed config is resolved before execution in FontDebugHarnessMain
        // and set on the context — no scene-side CLI parsing needed.
        TextSceneConfig config = (TextSceneConfig) ctx.getSceneConfig();
        run(ctx, ctx.getOutputDir(), config);
    }

    @Override
    public void dispose() {
    }

    void run(HarnessContext ctx, String outputDir, TextSceneConfig config) {
        String fontPath = HarnessFontUtil.resolveFontPath(config.getFontPath());
        int fontSizePx = config.getFontSizePx();
        String text = config.getText();
        int fboWidth = config.getWidth();
        int guiScale = config.getGuiScale();
        List<Float> scales = config.getEffectiveScales();

        // Logical UI width mirrors Minecraft's ScaledResolution: the overlay
        // coordinate space is [0, scaledWidth] where scaledWidth = displayWidth / guiScale.
        int logicalWidth = fboWidth / guiScale;
        int logicalHeight = config.getHeight() / guiScale;

        String outputFilename = config.getOutputFilename() != null
                ? config.getOutputFilename()
                : "text-scene.png";

        LOGGER.info("[Harness] Text scene: font=" + fontPath);
        LOGGER.info("[Harness] Text scene: size=" + fontSizePx + "px, text=\"" + text + "\"");
        LOGGER.info("[Harness] Text scene: scales=" + scales + ", guiScale=" + guiScale
                + ", logicalWidth=" + logicalWidth + ", output=" + outputFilename);

        CgCapabilities caps = CgCapabilities.detect();

        CgFont font = CgFont.load(fontPath, CgFontStyle.REGULAR, fontSizePx);
        LOGGER.info("[Harness] Font loaded: " + font.getKey());
        LOGGER.info("[Harness] Font metrics: ascender=" + font.getMetrics().getAscender()
                + ", descender=" + font.getMetrics().getDescender()
                + ", lineHeight=" + font.getMetrics().getLineHeight());

        CgMsdfAtlasConfig msdfConfig = config.buildMsdfAtlasConfig();
        CgFontRegistry registry = new CgFontRegistry(config.getAtlasSize(), msdfConfig);
        CgTextRenderer renderer = CgTextRenderer.createManualSized();
        CgTextRenderer.diagnosticLogging = true;

        CgTextLayout topLabelLayout = CgTextLayout.of(TOP_LABEL_TEXT, font)
                .maxWidth((float) fboWidth)
                .build();
        int topLabelBandHeight = (int) Math.ceil(topLabelLayout.totalHeight()) + 20;
        LOGGER.info("[Harness] Top label: layout " + topLabelLayout.lines().size()
                + " lines, width=" + topLabelLayout.totalWidth()
                + ", height=" + topLabelLayout.totalHeight()
                + ", wrapWidth=" + fboWidth + " (full FBO, identity pose)");

        // Measurement pass: compute per-band pixel height based on actual
        // layout line count and pose scale, with padding for margin.
        int bandPadding = 20; // 10px top + 10px bottom margin
        String[] labels = new String[scales.size()];
        CgTextLayout[] layouts = new CgTextLayout[scales.size()];
        int[] bandHeights = new int[scales.size()];
        int[] bandYOffsets = new int[scales.size()];
        int totalBandHeight = topLabelBandHeight;
        for (int i = 0; i < scales.size(); i++) {
            float scale = scales.get(i);
            labels[i] = text + " [base " + fontSizePx + "px, pose "
                    + String.format("%.1f", scale) + "x]";
            float logicalMaxWidth = fboWidth / scale;
            layouts[i] = CgTextLayout.of(labels[i], font).maxWidth(logicalMaxWidth).build();
            // The layout reports logical height; the pose scale magnifies it on screen.
            float scaledHeight = layouts[i].totalHeight() * scale;
            bandHeights[i] = (int) Math.ceil(scaledHeight) + bandPadding;
            bandYOffsets[i] = totalBandHeight;
            totalBandHeight += bandHeights[i];
            LOGGER.info("[Harness] Scale " + scale + "x: layout " + layouts[i].lines().size()
                    + " lines, width=" + layouts[i].totalWidth()
                    + ", height=" + layouts[i].totalHeight()
                    + ", bandPixelH=" + bandHeights[i]);
        }

        int fboHeight = config.isMultiScaleMode()
                ? totalBandHeight
                : config.getHeight();

        int fbo = CgGL.glGenFramebuffers();
        int colorTex = CgGL.glGenTextures();

        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, colorTex);
        CgGL.glTexImage2D(CgGL.GL_TEXTURE_2D, 0, CgGL.GL_RGBA8,
                fboWidth, fboHeight, 0,
                CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        CgGL.glTexParameteri(CgGL.GL_TEXTURE_2D, CgGL.GL_TEXTURE_MIN_FILTER, CgGL.GL_LINEAR);
        CgGL.glTexParameteri(CgGL.GL_TEXTURE_2D, CgGL.GL_TEXTURE_MAG_FILTER, CgGL.GL_LINEAR);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, 0);

        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, fbo);
        CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER,
                CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_TEXTURE_2D, colorTex, 0);

        int status = CgGL.glCheckFramebufferStatus(CgGL.GL_FRAMEBUFFER);
        if (status != CgGL.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Text scene FBO incomplete: 0x" + Integer.toHexString(status));
        }

        CgGL.glViewport(0, 0, fboWidth, fboHeight);
        CgGL.glClearColor(0.15f, 0.15f, 0.2f, 1.0f);
        CgGL.glClear(CgGL.GL_COLOR_BUFFER_BIT);

        renderer.context(CgTextRenderContext.orthographic(fboWidth, fboHeight));
        long frame = 1;

        if (config.isMtsdf()) {
            frame = prewarmDistanceFieldGlyphs(renderer, registry,
                    font,
                    topLabelLayout, 20.0f, 20.0f, TOP_LABEL_COLOR,
                    labels, layouts, bandYOffsets, scales,
                    frame);
        }

        // Draw top label: exact replication of CrystalGraphicsFontDemo's
        // identity-pose green label (position 20,20 — color 0xAAFFAAFF)
        renderer.beginBatch();
        renderer.context().clearHistory();
        PoseStack topLabelPose = new PoseStack();
        renderer.draw().layout(topLabelLayout).font(font).at(20.0f, 20.0f)
                .color(TOP_LABEL_COLOR).pose(topLabelPose).submit();
        frame++;

        for (int bandIdx = 0; bandIdx < scales.size(); bandIdx++) {
            float scale = scales.get(bandIdx);
            CgTextLayout layout = layouts[bandIdx];

            if (bandIdx == 0) {
                logGlyphDiagnostics(layout, labels[bandIdx]);
            }

            PoseStack poseStack = new PoseStack();
            float xDraw = 20.0f;
            float yDraw = config.isMultiScaleMode()
                    ? bandYOffsets[bandIdx] + 10.0f
                    : 40.0f;

            if (scale != 1.0f) {
                poseStack.last().pose().scale(scale, scale, 1.0f);
                // Compensate draw coords: pose scale multiplies them, so divide
                // to keep the final screen position stable across scales
                xDraw /= scale;
                yDraw /= scale;
            }

            renderer.context().clearHistory();
            renderer.draw().layout(layout).font(font).at(xDraw, yDraw).color(0xFFFFFF).pose(poseStack).submit();
            frame++;
        }
        renderer.endBatch();

        ScreenshotUtil.captureFboColorTexture(fbo, colorTex,
                fboWidth, fboHeight, outputDir, outputFilename);

        if (config.isDumpBitmapAtlas()) {
            String atlasDir = outputDir + File.separator + "atlas";
            HarnessOutputDir.ensureExists(atlasDir);
            String filename = "atlas-dump-" + fontSizePx + "px.png";
            CgGlyphAtlasPage bitmapAtlas = registry.findPopulatedBitmapPage(font.getKey());
            if (bitmapAtlas != null) {
                LOGGER.info("[Harness] Bitmap atlas captured: texture=" + bitmapAtlas.getTextureId()
                        + ", layer=" + bitmapAtlas.getPageIndex()
                        + ", size=" + bitmapAtlas.getPageWidth() + "x" + bitmapAtlas.getPageHeight());
                ScreenshotUtil.captureArrayTextureLayer(bitmapAtlas.getTextureId(), bitmapAtlas.getPageIndex(),
                        bitmapAtlas.getPageWidth(), bitmapAtlas.getPageHeight(), atlasDir, filename);
            } else {
                LOGGER.warning("[Harness] Bitmap atlas not available after rendering");
            }
        }

        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, 0);
        renderer.delete();
        registry.releaseAll();
        font.dispose();
        CgGL.glDeleteFramebuffers(fbo);
        CgGL.glDeleteTextures(colorTex);

        LOGGER.info("[Harness] Text scene complete.");
    }

    private long prewarmDistanceFieldGlyphs(CgTextRenderer renderer,
                                            CgFontRegistry registry,
                                            CgFont font,
                                            CgTextLayout topLabelLayout,
                                            float topLabelX,
                                            float topLabelY,
                                            int topLabelColor,
                                            String[] labels,
                                            CgTextLayout[] layouts,
                                            int[] bandYOffsets,
                                            List<Float> scales,
                                            long frame) {
        int totalChars = TOP_LABEL_TEXT.length();
        for (String label : labels) {
            totalChars += label.length();
        }
        int warmupFrames = Math.max(8, (totalChars / 4) + 6);

        for (int i = 0; i < warmupFrames; i++) {
            long drawFrame = frame + i;
            registry.tickFrame(drawFrame);

            renderer.beginBatch();

            renderer.context().clearHistory();
            PoseStack topLabelPose = new PoseStack();
            renderer.draw().layout(topLabelLayout).font(font).at(topLabelX, topLabelY)
                    .color(topLabelColor).pose(topLabelPose).submit();

            for (int bandIdx = 0; bandIdx < scales.size(); bandIdx++) {
                float scale = scales.get(bandIdx);
                PoseStack poseStack = new PoseStack();
                float xDraw = 20.0f;
                float yDraw = bandYOffsets[bandIdx] + 10.0f;
                if (scale != 1.0f) {
                    poseStack.last().pose().scale(scale, scale, 1.0f);
                    xDraw /= scale;
                    yDraw /= scale;
                }
                renderer.context().clearHistory();
                renderer.draw().layout(layouts[bandIdx]).font(font).at(xDraw, yDraw)
                        .color(0xFFFFFF).pose(poseStack).submit();
            }

            renderer.endBatch();
        }

        CgGL.glClearColor(0.15f, 0.15f, 0.2f, 1.0f);
        CgGL.glClear(CgGL.GL_COLOR_BUFFER_BIT);
        return frame + warmupFrames;
    }

    private void logGlyphDiagnostics(CgTextLayout layout, String text) {
        int charIdx = 0;
        for (int lineIdx = 0; lineIdx < layout.lines().size(); lineIdx++) {
            List<CgShapedRun> line = layout.lines().get(lineIdx);
            float penX = 0;
            for (CgShapedRun run : line) {
                float[] advances = run.advancesX();
                float[] offsetsX = run.offsetsX();
                int[] glyphIds = run.glyphIds();
                for (int i = 0; i < glyphIds.length; i++) {
                    char ch = charIdx < text.length() ? text.charAt(charIdx) : '?';
                    LOGGER.info(String.format("[Diag] glyph[%d] '%c' glyphId=%d penX=%.3f advance=%.3f offsetX=%.3f",
                            charIdx, ch, glyphIds[i], penX, advances[i], offsetsX[i]));
                    penX += advances[i];
                    charIdx++;
                }
            }
        }
    }
}
