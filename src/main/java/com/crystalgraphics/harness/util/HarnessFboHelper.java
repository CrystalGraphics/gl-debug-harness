package com.crystalgraphics.harness.util;


import com.crystalgraphics.platform.gl.CgGL;
import java.util.logging.Logger;

/**
 * Shared FBO creation, validation, and teardown for managed harness scenes.
 */
public final class HarnessFboHelper {

    private static final Logger LOGGER = Logger.getLogger(HarnessFboHelper.class.getName());

    private int fboId;
    private int colorTexId;
    private int depthTexId;
    private final int width;
    private final int height;
    private final boolean hasDepth;

    private HarnessFboHelper(int fboId, int colorTexId, int depthTexId,
                             int width, int height, boolean hasDepth) {
        this.fboId = fboId;
        this.colorTexId = colorTexId;
        this.depthTexId = depthTexId;
        this.width = width;
        this.height = height;
        this.hasDepth = hasDepth;
    }

    /**
     * Create an FBO with a color texture and an optional depth texture: textures both, as Minecraft's targets are, so a
     * device can import them.
     */
    public static HarnessFboHelper create(int width, int height, boolean withDepth) {
        int fbo = CgGL.glGenFramebuffers();
        int colorTex = CgGL.glGenTextures();
        int depthTex = 0;

        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, colorTex);
        CgGL.glTexImage2D(CgGL.GL_TEXTURE_2D, 0, CgGL.GL_RGBA8,
                width, height, 0,
                CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        CgGL.glTexParameteri(CgGL.GL_TEXTURE_2D, CgGL.GL_TEXTURE_MIN_FILTER, CgGL.GL_LINEAR);
        CgGL.glTexParameteri(CgGL.GL_TEXTURE_2D, CgGL.GL_TEXTURE_MAG_FILTER, CgGL.GL_LINEAR);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, 0);

        if (withDepth) {
            // 32-bit float: every Vulkan device takes it, where 24-bit depth is not on all.
            depthTex = CgGL.glGenTextures();
            CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, depthTex);
            CgGL.glTexImage2D(CgGL.GL_TEXTURE_2D, 0, CgGL.GL_DEPTH_COMPONENT32F, width, height, 0,
                    CgGL.GL_DEPTH_COMPONENT, CgGL.GL_FLOAT, (java.nio.ByteBuffer) null);
            CgGL.glTexParameteri(CgGL.GL_TEXTURE_2D, CgGL.GL_TEXTURE_MIN_FILTER, CgGL.GL_NEAREST);
            CgGL.glTexParameteri(CgGL.GL_TEXTURE_2D, CgGL.GL_TEXTURE_MAG_FILTER, CgGL.GL_NEAREST);
            CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, 0);
        }

        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, fbo);
        CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER,
                CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_TEXTURE_2D, colorTex, 0);
        if (withDepth) {
            CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER,
                    CgGL.GL_DEPTH_ATTACHMENT, CgGL.GL_TEXTURE_2D, depthTex, 0);
        }

        int status = CgGL.glCheckFramebufferStatus(CgGL.GL_FRAMEBUFFER);
        if (status != CgGL.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("FBO incomplete: status=0x" + Integer.toHexString(status));
        }

        LOGGER.info("[Harness] FBO created: " + width + "x" + height
                + " depth=" + withDepth + " id=" + fbo);

        return new HarnessFboHelper(fbo, colorTex, depthTex, width, height, withDepth);
    }

    /** Bind this FBO and set viewport. */
    public void bind() {
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, fboId);
        CgGL.glViewport(0, 0, width, height);
    }

    /** Unbind FBO (bind default framebuffer 0). */
    public void unbind() {
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, 0);
    }

    /** Clear color (and optionally depth) buffers. */
    public void clear(float r, float g, float b, float a) {
        CgGL.glClearColor(r, g, b, a);
        int bits = CgGL.GL_COLOR_BUFFER_BIT;
        if (hasDepth) {
            bits |= CgGL.GL_DEPTH_BUFFER_BIT;
        }
        CgGL.glClear(bits);
    }

    /** Capture the FBO's color attachment to a PNG file. */
    public void captureToFile(String outputDir, String filename) {
        ScreenshotUtil.captureFboColorTexture(fboId, colorTexId,
                width, height, outputDir, filename);
    }

    /** Delete all GL resources. */
    public void delete() {
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, 0);
        if (fboId != 0) {
            CgGL.glDeleteFramebuffers(fboId);
            fboId = 0;
        }
        if (colorTexId != 0) {
            CgGL.glDeleteTextures(colorTexId);
            colorTexId = 0;
        }
        if (depthTexId != 0) {
            CgGL.glDeleteTextures(depthTexId);
            depthTexId = 0;
        }
    }

    public int getFboId() { return fboId; }
    public int getColorTexId() { return colorTexId; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
