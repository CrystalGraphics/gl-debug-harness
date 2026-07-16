package io.github.somehussar.crystalgraphics.harness.util;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class HarnessTextureUtil {

    public static BufferedImage[] loadGif(String path) {
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();

        try (ImageInputStream in = ImageIO.createImageInputStream(new File(path))) {
            reader.setInput(in);
            int frameCount = reader.getNumImages(true);

            // Read full GIF dimensions from root metadata
            IIOMetadata rootMeta = reader.getStreamMetadata();
            // Easier: just use the first frame's size as canvas size
            BufferedImage first = reader.read(0);
            int canvasW = first.getWidth();
            int canvasH = first.getHeight();

            // The persistent canvas — we paint each delta onto this
            BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();

            BufferedImage[] composed = new BufferedImage[frameCount];

            for (int i = 0; i < frameCount; i++) {
                // Get this frame's metadata to find its X/Y offset within the canvas
                IIOMetadata meta = reader.getImageMetadata(i);
                Node tree = meta.getAsTree("javax_imageio_gif_image_1.0");
                NamedNodeMap imgDesc = tree.getFirstChild().getAttributes(); // ImageDescriptor

                int offsetX = Integer.parseInt(imgDesc.getNamedItem("imageLeftPosition").getNodeValue());
                int offsetY = Integer.parseInt(imgDesc.getNamedItem("imageTopPosition").getNodeValue());

                // Paint delta frame onto canvas at its correct offset
                BufferedImage rawFrame = reader.read(i);
                g.drawImage(rawFrame, offsetX, offsetY, null);

                // Snapshot the current composed state
                BufferedImage snapshot = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
                snapshot.createGraphics().drawImage(canvas, 0, 0, null);
                composed[i] = snapshot;
            }

            g.dispose();
            return composed;
        } catch (Exception e) {
            throw new RuntimeException("failed loading gif: " + e.getMessage());
        }
    }

    public static int loadStitch(String path, int images, boolean vertical) {

        BufferedImage image;
        try (InputStream in = new FileInputStream(path)) {
            image = ImageIO.read(in);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load stitch:" + path);
        }

        int totalWidth = image.getWidth(), totalHeight = image.getHeight();
        int iWidth = !vertical ? totalWidth / images : totalWidth, iHeight = !vertical ? totalHeight : totalHeight / images;
        int[] pixels = new int[iWidth * iHeight];


        ByteBuffer buff = BufferUtils.createByteBuffer(iWidth * iHeight * 4 * images);

        for (int i = 0; i < images; i++) {
            if (!vertical)
                image.getRGB(iWidth * i, 0, iWidth, iHeight, pixels, 0, iWidth);
            else
                image.getRGB(0, i * iHeight, iWidth, iHeight, pixels, 0, iWidth);

            for (int y = 0; y < iHeight; y++) {
                int invertedY = iHeight - 1 - y;
                for (int x = 0; x < iWidth; x++) {
                    int pixel = pixels[invertedY * iWidth + x];
                    buff.put((byte) (pixel >> 16 & 0xff));
                    buff.put((byte) (pixel >> 8 & 0xff));
                    buff.put((byte) (pixel & 0xff));
                    buff.put((byte) (pixel >> 24 & 0xff));
                }
            }
        }


        buff.flip();
        int texId = GL11.glGenTextures();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texId);

        GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8, iWidth, iHeight, images, 0, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, buff);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);

        return texId;
    }

    public static BufferedImage[] processOrangePiccolo13(int frames, String dir) {
        String outputDir = "harness-output/orange-piccolo-processed/";
        File output = new File(outputDir);
        if (!output.exists()) output.mkdir();

        String name;
        BufferedImage[] images = new BufferedImage[frames];
        for (int i = 0; i < frames; i++) {
            name = String.format("%03d", i + 1) + ".png";
            try (InputStream in = new FileInputStream(dir + name)) {
                BufferedImage image = ImageIO.read(in);
                images[i] = image;
                //images[i] = image.getSubimage(502, 318, 150, 150);
//                images[i] = image.getSubimage(513, 330, 128, 128);
                

                //ImageIO.write(images[i], "PNG", new File(output, (i+1) + ".png"));
            } catch (Exception e) {
                throw new RuntimeException("Failed to load '" + name + "':" + e.getMessage());
            }
        }
        return images;
    }

    public static BufferedImage stitchImages(boolean vertical, BufferedImage... images) throws IOException {
        int width = images[0].getWidth(), height = images[0].getHeight();
        for (BufferedImage image : images) {
            int iWidth = image.getWidth(), iHeight = image.getHeight();
            if (iWidth != width || iHeight != height)
                throw new IOException(
                        String.format("All images must be of same dimensions. Expected: %sx%s, Provided: %sx%s ",
                                width, height, iWidth, iHeight));
        }

        int n = images.length;
        int[] pixels = new int[width * height];
        BufferedImage stitched;

        if (!vertical) {
            stitched = new BufferedImage(width * n, height, BufferedImage.TYPE_INT_ARGB);
            for (int i = 0; i < n; i++) {
                BufferedImage image = images[i];
                image.getRGB(0, 0, width, height, pixels, 0, width);
                stitched.setRGB(width * i, 0, width, height, pixels, 0, width);
            }
        } else {
            stitched = new BufferedImage(width, height * n, BufferedImage.TYPE_INT_ARGB);
            for (int i = 0; i < n; i++) {
                BufferedImage image = images[i];
                image.getRGB(0, 0, width, height, pixels, 0, width);
                stitched.setRGB(0, height * n, width, height, pixels, 0, width);
            }
        }


        return stitched;
    }

    public static void saveImage(String path, BufferedImage... images) {

        File dir = new File(path);
        if (!dir.exists())
            dir.mkdir();

        for (int i = 0; i < images.length; i++) {

            BufferedImage image = images[i];
            try {
                ImageIO.write(image, "png", new File(dir, i + ".png"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static int createTextureArray(BufferedImage... images) throws Exception {
        int width = images[0].getWidth(), height = images[0].getHeight();


        for (int i = 0; i < images.length; i++) {
            int iWidth = images[i].getWidth(), iHeight = images[i].getHeight();
            if (iWidth != width || iHeight != height)
                throw new Exception(
                        String.format("Image %s is of different dimension. Expected: %sx%s, Provided: %sx%s", i, width,
                                height, iWidth, iHeight));
        }


        int texId = GL11.glGenTextures();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texId);
        GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8, width, height, images.length, 0, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, toByteBuffer(images));


        //        for (int i = 0; i <= images.length; i++) {
        //            ByteBuffer image = toByteBuffer(images[i]);
        //            GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, width, height, i, GL11.GL_RGBA,
        //                    GL11.GL_UNSIGNED_BYTE, image);
        //        }

        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        return texId;
    }

    public static ByteBuffer toByteBuffer(BufferedImage... images) {
        int width = images[0].getWidth(), height = images[0].getHeight();
        ByteBuffer buff = BufferUtils.createByteBuffer(width * height * 4 * images.length);

        int[] pixels = new int[width * height];
        for (int i = 0; i < images.length; i++) {
            BufferedImage image = images[i];
            image.getRGB(0, 0, width, height, pixels, 0, width);

            for (int y = 0; y < height; y++) {
                int invertedY = height - 1 - y;
                for (int x = 0; x < width; x++) {
                    int pixel = pixels[invertedY * width + x];
                    buff.put((byte) (pixel >> 16 & 0xff));
                    buff.put((byte) (pixel >> 8 & 0xff));
                    buff.put((byte) (pixel & 0xff));
                    buff.put((byte) (pixel >> 24 & 0xff));
                }
            }
        }
        return (ByteBuffer) buff.flip();
    }
}
