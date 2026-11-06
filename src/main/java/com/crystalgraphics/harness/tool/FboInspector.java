package com.crystalgraphics.harness.tool;

import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.harness.util.ScreenshotUtil;

import java.util.logging.Logger;

/**
 * Agent-debug tool #3: Dump FBO color attachments to PNG files.
 */
public final class FboInspector {

    /** Not among CgGL's constants. */
    private static final int GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME = 0x8CD1;

    private static final Logger LOGGER = Logger.getLogger(FboInspector.class.getName());

    /**
     * Dump the color attachment of an FBO to a PNG.
     *
     * @param fboId     FBO id
     * @param width     attachment width
     * @param height    attachment height
     * @param outputDir output directory
     * @param filename  output filename
     */
    public static void dumpColorAttachment(int fboId, int width, int height,
                                           String outputDir, String filename) {
        LOGGER.info("[FboInspector] Dumping FBO " + fboId
                + " color attachment (" + width + "x" + height + ") -> " + filename);

        // We need the color texture attached to this FBO
        CgGlScope bound = CgGlState.save(CgGlSlot.FBO);
        CgGL.glBindFramebuffer(CgGL.GL_READ_FRAMEBUFFER, fboId);

        // Query the attached texture
        int attachedTex = CgGL.glGetFramebufferAttachmentParameteriv(
                CgGL.GL_READ_FRAMEBUFFER,
                CgGL.GL_COLOR_ATTACHMENT0,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);

        bound.close();

        if (attachedTex != 0) {
            ScreenshotUtil.captureFboColorTexture(fboId, attachedTex,
                    width, height, outputDir, filename);
        } else {
            LOGGER.warning("[FboInspector] FBO " + fboId + " has no color attachment");
        }
    }

    private FboInspector() {
    }
}
