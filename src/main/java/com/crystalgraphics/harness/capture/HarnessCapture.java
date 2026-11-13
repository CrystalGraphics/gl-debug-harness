package com.crystalgraphics.harness.capture;

import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.gpu.resource.CgTextureAbstract;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Logger;

/**
 * Photographs to PNG, each a readback through CrystalGraphics that waits for the GPU ({@link CgReadback#now}): a target,
 * the frame's own target, a texture or one layer of an array.
 *
 * <pre>{@code
 * HarnessCapture.target(target.external(), dir, "frame.png");     // a target, top row first
 * HarnessCapture.target(ctx.getTarget(), dir, "final.png");       // the frame's own target
 * HarnessCapture.texture(atlas, 3, dir, "page3.png");             // layer 3 of an array, its rows as uploaded
 * BufferedImage drawn = HarnessCapture.image(target.external());  // read back, no file
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
    public static void target(CgExternalTarget target, String outputDir, String filename) {
        write(image(target), outputDir, filename);
    }

    /** {@code target}'s colour as a picture, top row first, written nowhere: for a scene that scores what it drew. */
    public static BufferedImage image(CgExternalTarget target) {
        int w = target.width(), h = target.height();
        return image(CgReadback.now(target.color(), 0, 0, w, h, false), w, h, true);
    }

    /** {@code texture} (an array's {@code layer}), its rows as uploaded. */
    public static void texture(CgTextureAbstract texture, int layer, String outputDir, String filename) {
        texture.getId();   // lands what is queued
        CgTextureView view = CgTextureView.attachment(texture.rhiTexture(), 0, layer);
        write(image(CgReadback.now(view, 0, 0, texture.getWidth(), texture.getHeight(), false), texture.getWidth(),
                texture.getHeight(), false), outputDir, filename);
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
