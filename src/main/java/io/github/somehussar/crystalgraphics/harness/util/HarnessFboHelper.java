package io.github.somehussar.crystalgraphics.harness.util;


import com.crystalgraphics.platform.gl.CgGL;
import java.util.logging.Logger;

/**
 * Shared FBO creation, validation, and teardown for managed harness scenes.
 */
public final class HarnessFboHelper {

    private static final Logger LOGGER = Logger.getLogger(HarnessFboHelper.class.getName());

    private int fboId;
    private int colorTexId;
    private int depthRbId;
    private final int width;
    private final int height;
    private final boolean hasDepth;

    private HarnessFboHelper(int fboId, int colorTexId, int depthRbId,
                             int width, int height, boolean hasDepth) {
        this.fboId = fboId;
        this.colorTexId = colorTexId;
        this.depthRbId = depthRbId;
        this.width = width;
        this.height = height;
        this.hasDepth = hasDepth;
    }

    /**
     * Create an FBO with a color texture and optional depth renderbuffer.
     */
    public static HarnessFboHelper create(int width, int height, boolean withDepth) {
        int fbo = CgGL.glGenFramebuffers();
        int colorTex = CgGL.glGenTextures();
        int depthRb = 0;

        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, colorTex);
        CgGL.glTexImage2D(CgGL.GL_TEXTURE_2D, 0, CgGL.GL_RGBA8,
                width, height, 0,
                CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        CgGL.glTexParameteri(CgGL.GL_TEXTURE_2D, CgGL.GL_TEXTURE_MIN_FILTER, CgGL.GL_LINEAR);
        CgGL.glTexParameteri(CgGL.GL_TEXTURE_2D, CgGL.GL_TEXTURE_MAG_FILTER, CgGL.GL_LINEAR);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, 0);

        if (withDepth) {
            depthRb = CgGL.glGenRenderbuffers();
            CgGL.glBindRenderbuffer(CgGL.GL_RENDERBUFFER, depthRb);
            CgGL.glRenderbufferStorage(CgGL.GL_RENDERBUFFER, CgGL.GL_DEPTH_COMPONENT,
                    width, height);
            CgGL.glBindRenderbuffer(CgGL.GL_RENDERBUFFER, 0);
        }

        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, fbo);
        CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER,
                CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_TEXTURE_2D, colorTex, 0);
        if (withDepth) {
            CgGL.glFramebufferRenderbuffer(CgGL.GL_FRAMEBUFFER,
                    CgGL.GL_DEPTH_ATTACHMENT, CgGL.GL_RENDERBUFFER, depthRb);
        }

        int status = CgGL.glCheckFramebufferStatus(CgGL.GL_FRAMEBUFFER);
        if (status != CgGL.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("FBO incomplete: status=0x" + Integer.toHexString(status));
        }

        LOGGER.info("[Harness] FBO created: " + width + "x" + height
                + " depth=" + withDepth + " id=" + fbo);

        return new HarnessFboHelper(fbo, colorTex, depthRb, width, height, withDepth);
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
        if (depthRbId != 0) {
            CgGL.glDeleteRenderbuffers(depthRbId);
            depthRbId = 0;
        }
    }

    public int getFboId() { return fboId; }
    public int getColorTexId() { return colorTexId; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
