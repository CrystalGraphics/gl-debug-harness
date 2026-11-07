package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.config.TextContext;
import com.crystalgraphics.harness.util.HarnessTextureUtil;
import com.crystalgraphics.harness.util.RawGlProgram;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33C.*;

/** Raw LWJGL GL, drawn inside {@code CgGlState.hostForeign} while {@code CgGL}'s shadow exists. GL device only. */
public class ImageScene implements InteractiveSceneLifecycle {

    private static final CgGlSlot[] TOUCHES = {CgGlSlot.VERTEX_INPUT, CgGlSlot.PROGRAM, CgGlSlot.TEXTURES, CgGlSlot.BLEND};

    Matrix4f ortho;
    int squareControlVao, squareControlVbo, vaoId, vboId, eboId, textureId, colorVbo;
    int textureArrayId;

    RawGlProgram shader;

    public static final int frames = 98;

    private HarnessContext ctx;
    private int width, height;
    private int frameIdx, totalFrames;
    private float blendT, elapsedSec;
    private final Runnable setUp = this::setUp, draw = this::draw, upload = this::uploadQuads;

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
        this.ctx = ctx;

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

        width = ctx.getScreenWidth();
        height = ctx.getScreenHeight();
        CgGlState.hostForeign(setUp, TOUCHES);
        ortho = new Matrix4f();
        onResize(width, height);
    }

    private void setUp() {
        shader = RawGlProgram.load("assets/harness/shader/texture2d.vert", "assets/harness/shader/texture2d.frag");

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
        squareControlVao = glGenVertexArrays();
        glBindVertexArray(squareControlVao);

        eboId = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, eboId);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, eboBuff, GL_STATIC_DRAW);

        squareControlVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, squareControlVbo);
        glBufferData(GL_ARRAY_BUFFER, controlBuff, GL_STATIC_DRAW);

        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8);


        vaoId = glGenVertexArrays();
        glBindVertexArray(vaoId);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, eboId);

        vboId = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glBufferData(GL_ARRAY_BUFFER, fBuff, GL_STATIC_DRAW);

        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8);


        colorVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, colorVbo);
        glBufferData(GL_ARRAY_BUFFER, colBuffer, GL_STATIC_DRAW);

        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true, 0, 0);
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

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, buff);
    }

    public void onResize(int width, int height) {
        this.width = width;
        this.height = height;
        ortho.setOrtho(0, width, height, 0, -1, 1);
        CgGlState.hostForeign(upload, CgGlSlot.VERTEX_INPUT);
    }

    private void uploadQuads() {
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
        CgTextLayout layout = CgTextLayout.of("NO INTERPOLATION", text.font).build();

        float rightX = (3f * width / 4f) - halfSize;
        float rightY = verticalCenter - halfSize;
        CgTextLayout rightLayout = CgTextLayout.of("INBETWEEN FRAME INTERPOLATION", text.font).build();

        text.renderer.beginBatch();
        text.renderer.draw().layout(layout).font(text.font).at(leftX, leftY)
                .color(TEXT_COLOR).pose(poseStack).submit();
        text.renderer.draw().layout(rightLayout).font(text.font).at(rightX, rightY)
                .color(TEXT_COLOR).pose(poseStack).submit();
        text.renderer.endBatch();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {

        totalFrames = frames;
        float frameDurSec = 0.032525f;  // each GIF frame is 120ms
        float totalDurSec = totalFrames * frameDurSec;  // 1.56s

        // How many frames have elapsed (e.g. 7.34)
        // The frame clock, not the wall's: a capture at frame N is then the same picture on every run.
        elapsedSec = (float) (frame.getElapsedTime() % totalDurSec);
        float rawT = elapsedSec / frameDurSec;

        frameIdx = (int) rawT % totalFrames;   // integer frame: 7
        blendT = rawT - (int) rawT;

        CgGlState.hostForeign(draw, TOUCHES);

        // Overlay text
        renderText(ctx);
    }

    private void draw() {
        glEnable(GL_BLEND);
        // Core profile: no alpha test (was glEnable(GL_ALPHA_TEST))
        //glAlphaFunc(GL_GREATER, 0.001f);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);


        glBindVertexArray(vaoId);
        shader.use()
                .mat4("u_projection", ortho)
                .set1i("u_frames", 0)
                .set1i("u_frameCount", totalFrames)
                .set1i("u_frameIdx", frameIdx)
                .set1f("u_normalized", blendT)
                .set1f("u_rotationSpeed", 0.15f)
                .set1f("u_totalTime", elapsedSec)
                .set1i("u_lerp", 1);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);

        glBindVertexArray(squareControlVao);
        shader.use()
                .mat4("u_projection", ortho)
                .set1i("u_frames", 0)
                .set1i("u_frameCount", totalFrames)
                .set1i("u_frameIdx", frameIdx)
                .set1f("u_normalized", blendT)
                .set1f("u_rotationSpeed", 5)
                .set1f("u_totalTime", elapsedSec)
                .set1i("u_lerp", 0);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
    }

    @Override
    public void dispose() {
        glDeleteVertexArrays(vaoId);
        glDeleteVertexArrays(squareControlVao);
        glDeleteBuffers(vboId);
        glDeleteBuffers(squareControlVbo);
        glDeleteBuffers(eboId);
        glDeleteBuffers(colorVbo);
        shader.delete();
        glDeleteTextures(textureId);
        glDeleteTextures(textureArrayId);
        CgGlState.invalidateAllIfPresent();
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
