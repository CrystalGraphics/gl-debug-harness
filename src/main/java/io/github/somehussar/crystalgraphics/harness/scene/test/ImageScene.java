package io.github.somehussar.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.text.CgTextLayoutRequest;
import com.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.config.TextContext;
import io.github.somehussar.crystalgraphics.harness.util.HarnessTextureUtil;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public class ImageScene implements InteractiveSceneLifecycle {

    Matrix4f ortho;
    int squareControlVao, squareControlVbo, vaoId, vboId, eboId, textureId, colorVbo;
    int textureArrayId;

    CgShader shader = CgShaderFactory.load("assets/harness/shader/texture2d.vert",
            "assets/harness/shader/texture2d.frag");

    public static final int frames = 98;

    private void loadOrangePiccolo() {
        BufferedImage[] images1 = HarnessTextureUtil.processOrangePiccolo13(frames,
                "harness-output/orange-piccolo-52frames-2x-RIFE-RIFE4.0-100fps/");
        try {
            BufferedImage stitch = HarnessTextureUtil.stitchImages(false, images1);
            ImageIO.write(stitch, "PNG",
                    new File("harness-output/orange-piccolo-processed/stitched_" + frames + ".png"));
        } catch (Exception e) {

        }
    }

    @Override
    public void init(HarnessContext ctx) {


        //        BufferedImage[] images = HarnessTextureUtil.loadGif("harness-output/example.gif");
        //        try {
        //            textureArrayId = HarnessTextureUtil.createTextureArray(images);
        //        } catch (Exception e) {
        //            throw new RuntimeException(e);
        //        }

        loadOrangePiccolo();
        textureArrayId = HarnessTextureUtil.loadStitch(
                "src/main/resources/assets/harness/orange-piccolo-processed/stitched_" + frames + ".png",
                frames,
                false);

        // createTexture();


        float[] vertices = {
                -1, -1, 0, 0,
                0, 1, 0.5f, 1,
                1, -1, 1, 0
        };

        float[] quad_Control = {
                -1, -1, 0, 0,
                0, -1, 1, 0,
                0, 0, 1, 1,
                -1, 0, 0, 1
        };
        int width = ctx.getScreenWidth(), height = ctx.getScreenHeight();

        float[] quad = {
                width / 2, height, 0, 0,
                width, height, 1, 0,
                width, height / 2, 1, 1,
                width / 2, height / 2, 0, 1
        };


        int[] ebo = {0, 1, 2, 2, 3, 0};

        int[] colors = {
                0xff, 0xff00, 0, 255,
                0, 255, 0, 255,
                0, 0, 255, 255,
        };

        FloatBuffer controlBuff = BufferUtils.createFloatBuffer(quad_Control.length);
        controlBuff.put(quad_Control).flip();

        FloatBuffer fBuff = BufferUtils.createFloatBuffer(quad.length);
        fBuff.put(quad).flip();

        IntBuffer eboBuff = BufferUtils.createIntBuffer(ebo.length);
        eboBuff.put(ebo).flip();

        IntBuffer colBuffer = BufferUtils.createIntBuffer(colors.length);
        colBuffer.put(colors).flip();

        //        ByteBuffer colBuffer = BufferUtils.createByteBuffer(3 * 4); // 3 vertices * 4 bytes (RGBA)
        //colBuffer.put((byte)255).put((byte)0).put((byte)0).put((byte)255); // Red
        //colBuffer.put((byte)0).put((byte)255).put((byte)0).put((byte)255); // Green
        //colBuffer.put((byte)0).put((byte)0).put((byte)255).put((byte)255); // Blue
        //colBuffer.flip();
        squareControlVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(squareControlVao);

        eboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, eboBuff, GL15.GL_STATIC_DRAW);

        squareControlVbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, squareControlVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, controlBuff, GL15.GL_STATIC_DRAW);

        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8);


        vaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoId);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);

        vboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, fBuff, GL15.GL_STATIC_DRAW);

        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8);


        colorVbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, colorVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, colBuffer, GL15.GL_STATIC_DRAW);

        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_UNSIGNED_BYTE, true, 0, 0);

        ortho = new Matrix4f();
        onResize(width, height);
    }

    public void uploadQuad(int vbo, float x, float y, float width, float height, float u0, float v0, float u1,
                           float v1) {

        float[] quad = {
                x, y, u0, v1,
                x + width, y, u1, v1,
                x + width, y + height, u1, v0,
                x, y + height, u0, v0
        };

        FloatBuffer buff = BufferUtils.createFloatBuffer(quad.length);
        buff.put(quad).flip();

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, buff);
    }

    public void onResize(int width, int height) {
        ortho.setOrtho(0, width, height, 0, -1, 1);
        // The size of your square quads
        float quadSize = height / 2f;
        float halfSize = quadSize / 2f;
        float verticalCenter = height / 2f;

        // Left Quad: Center of the left half is (width / 4)
        float leftX = (width / 4f) - halfSize;
        float leftY = verticalCenter - halfSize;
        uploadQuad(squareControlVbo, leftX, leftY, quadSize, quadSize, 0, 0, 1, 1);

        // Right Quad: Center of the right half is (3 * width / 4)
        float rightX = (3f * width / 4f) - halfSize;
        float rightY = verticalCenter - halfSize;
        uploadQuad(vboId, rightX, rightY, quadSize, quadSize, 0, 0, 1, 1);
    }


    public static void renderText(HarnessContext ctx) {
        TextContext text = ctx.getTextContext();
        int TEXT_COLOR = 0xff0000ff;
        int width = ctx.getScreenWidth(), height = ctx.getScreenHeight();
        float quadSize = height / 2f;
        float halfSize = quadSize / 2f;
        float verticalCenter = height / 2f;

        float poseScale = 1f;
        PoseStack poseStack = new PoseStack();
        poseStack.scale(poseScale, poseScale, 1.0f);

        float leftX = (width / 4f) - halfSize / 2;
        float leftY = verticalCenter - halfSize;
        CgTextLayout layout = CgTextLayoutRequest.of("NO INTERPOLATION", text.font).build();

        float rightX = (3f * width / 4f) - halfSize;
        float rightY = verticalCenter - halfSize;
        CgTextLayout rightLayout = CgTextLayoutRequest.of("INBETWEEN FRAME INTERPOLATION", text.font).build();

        text.renderer.beginBatch();
        text.renderer.draw().layout(layout).font(text.font).at(leftX, leftY)
                .color(TEXT_COLOR).pose(poseStack).submit();
        text.renderer.draw().layout(rightLayout).font(text.font).at(rightX, rightY)
                .color(TEXT_COLOR).pose(poseStack).submit();
        text.renderer.endBatch();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        
        int totalFrames = frames;
        float frameDurSec = 0.032525f;  // each GIF frame is 120ms
        float totalDurSec = totalFrames * frameDurSec;  // 1.56s

        // How many frames have elapsed (e.g. 7.34)
        float elapsedSec = (System.currentTimeMillis() % (long) (totalDurSec * 1000)) / 1000f;
        float rawT = elapsedSec / frameDurSec;

        int frameIdx = (int) rawT % totalFrames;   // integer frame: 7
        float blendT = rawT - (int) rawT;

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        //GL11.glAlphaFunc(GL11.GL_GREATER, 0.001f);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textureArrayId);


        GL30.glBindVertexArray(vaoId);
        shader.applyBindings(b -> {
            b.mat4("u_projection", ortho);
            b.set1i("u_frames", 0);
            b.set1i("u_frameCount", totalFrames);
            b.set1i("u_frameIdx", frameIdx);
            b.set1f("u_normalized", blendT);
            b.set1f("u_rotationSpeed", 0.15f);
            b.set1f("u_totalTime", elapsedSec);
            b.set1i("u_lerp", 1);
        }).bind();
        GL11.glDrawElements(GL11.GL_TRIANGLES, 6, GL11.GL_UNSIGNED_INT, 0);

        GL30.glBindVertexArray(squareControlVao);
        shader.applyBindings(b -> {
            b.mat4("u_projection", ortho);
            b.set1i("u_frames", 0);
            b.set1i("u_frameCount", totalFrames);
            b.set1i("u_frameIdx", frameIdx);
            b.set1f("u_normalized", blendT);
            b.set1f("u_rotationSpeed", 5);
            b.set1f("u_totalTime", elapsedSec);
            b.set1i("u_lerp", 0);
        }).bind();
        GL11.glDrawElements(GL11.GL_TRIANGLES, 6, GL11.GL_UNSIGNED_INT, 0);

        // Overlay text
        renderText(ctx);
    }

    @Override
    public void dispose() {
        GL30.glDeleteVertexArrays(vaoId);
        GL30.glDeleteVertexArrays(squareControlVao);
        GL15.glDeleteBuffers(vboId);
        GL15.glDeleteBuffers(squareControlVbo);
        shader.delete();
        GL11.glDeleteTextures(textureId);
        GL11.glDeleteTextures(textureArrayId);
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return false;
    }
}
