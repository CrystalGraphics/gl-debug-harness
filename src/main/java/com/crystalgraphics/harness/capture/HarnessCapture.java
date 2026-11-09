package com.crystalgraphics.harness.capture;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Logger;

/**
 * Photographs to PNG, each a readback through CrystalGraphics: a target, the frame's own target, a texture or one
 * layer of an array. Every call waits for the GPU, which is what a capture is for.
 *
 * <pre>{@code
 * HarnessCapture.framebuffer(target, dir, "frame.png");     // a target, top row first
 * HarnessCapture.framebuffer(ctx.getTargetFramebuffer(), w, h, dir, "final.png");   // the frame's own target
 * HarnessCapture.texture(atlas, 3, dir, "page3.png");       // layer 3 of an array, its rows as uploaded
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, after what it photographs was drawn.</li>
 *   <li>A target's rows are flipped, its row 0 being the picture's bottom; a texture's are not, being as uploaded.</li>
 * </ul>
 */
public final class HarnessCapture {

    private static final Logger LOGGER = Logger.getLogger(HarnessCapture.class.getName());

    private HarnessCapture() {}

    /** {@code target}'s colour, top row first. */
    public static void framebuffer(CgFrameBuffer target, String outputDir, String filename) {
        write(image(target.readColor(0, 0, target.getWidth(), target.getHeight()), target.getWidth(), target.getHeight(),
                true), outputDir, filename);
    }

    /** {@code width} x {@code height} of the framebuffer named {@code framebuffer}: the frame's target, or the window. */
    public static void framebuffer(int framebuffer, int width, int height, String outputDir, String filename) {
        framebuffer(CgFrameBuffer.wrap("capture", framebuffer, width, height), outputDir, filename);
    }

    /** {@code texture} (an array's {@code layer}), its rows as uploaded. */
    public static void texture(CgTexture texture, int layer, String outputDir, String filename) {
        write(image(CgFrameBuffer.readTexture(texture, layer), texture.getWidth(), texture.getHeight(), false),
                outputDir, filename);
    }

    /** RGBA8 rows into an image, bottom row first when {@code flip}. */
    public static BufferedImage image(ByteBuffer rgba, int width, int height, boolean flip) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            int row = flip ? height - 1 - y : y;
            for (int x = 0; x < width; x++) {
                int i = (row * width + x) * 4;
                int r = rgba.get(i) & 0xFF, g = rgba.get(i + 1) & 0xFF, b = rgba.get(i + 2) & 0xFF;
                int a = rgba.get(i + 3) & 0xFF;
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    public static void write(BufferedImage image, String outputDir, String filename) {
        File file = new File(outputDir, filename);
        try {
            ImageIO.write(image, "PNG", file);
            LOGGER.info("[Harness] Wrote " + filename + " (" + file.length() + " bytes) to " + file.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write PNG: " + file.getAbsolutePath(), e);
        }
    }
}
