package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.camera.Camera3D;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.object.Pose;
import com.crystalgraphics.harness.object.VertexBinding;
import com.crystalgraphics.harness.util.RawGlProgram;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33C.*;

/** Raw LWJGL GL, drawn inside {@code CgGlState.hostForeign} while {@code CgGL}'s shadow exists. GL device only. */
public class ReviewScene implements InteractiveSceneLifecycle {
    private static final String DIR = "assets/harness/shader/review/";
    private static final Pose ORIGIN = new Pose();
    private static final CgGlSlot[] TOUCHES = {CgGlSlot.VERTEX_INPUT, CgGlSlot.PROGRAM, CgGlSlot.TEXTURES, CgGlSlot.BLEND,
            CgGlSlot.LINE_WIDTH};

    private VertexBinding triangle;
    private RawGlProgram triangleShader;

    private VertexBinding quad;

    private VertexBinding cube;
    private RawGlProgram cubeShader;
    private Pose cubePos = new Pose();

    private VertexBinding axis;
    private static final float AXIS_LENGTH = 500f;

    private Pose sunPose = new Pose();
    private Pose sunOrigin = new Pose().setPosition(5, 0, 0);
    private RawGlProgram sunShader;

    private Matrix4f projection = new Matrix4f();

    private static final String DIR1 = "assets/harness/shader/outline/";
    private RawGlProgram outlineShader;

    private static  int UV_DISTORTION_MAP ;//= HarnessTextureUtil.loadTexture(DIR1 + "tex2.png");
    private static  CgTexture UV_DISTORTION ;//=  CgTexture2D.create(DIR1 + "tex2.png", CgTextureSpec.R8_LINEAR_REPEAT);
    private static  int NOISE_MAP ;//= HarnessTextureUtil.loadTexture(DIR1 + "tex1.png");
    private static  int ALPHA_MAP ;//= HarnessTextureUtil.loadTexture(DIR1 + "tex4.png");
    private static  int FLAME_NOISE;// = HarnessTextureUtil.loadTexture(DIR1 + "tex5.png");

    private HarnessContext ctx;
    private final Runnable setUp = this::setUp, draw = this::draw;

    public VertexBinding initTriangle(HarnessContext ctx) {
        float[] vertices = {
                -0.5f, -0.5f, 0, col(0xffff0000),
                0.5f, -0.5f, 0, col(0xff0000ff),
                0, 0.5f, 0, col(0xff00ff00)
        };

        FloatBuffer buff = BufferUtils.createFloatBuffer(vertices.length);
        buff.put(vertices).flip();

        int vao = glGenVertexArrays();
        glBindVertexArray(vao);

        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, buff, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0);
        glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, 16, 12);

        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);

        return new VertexBinding(vao, vbo, -1, 3);
    }

    public VertexBinding initQuad(HarnessContext ctx) {
        float[] vertices = {
                -0.5f, -0.5f, 0, 0, 0, col(0xffff0000),
                0.5f, -0.5f, 0, 1, 0, col(0xff0000ff),
                0.5f, 0.5f, 0, 1, 1, col(0xff00ffff),
                -0.5f, 0.5f, 0, 0, 1, col(0xff00ff00)
        };

        int[] indices = {0, 1, 2, 2, 3, 0};
        int indexCount = indices.length;

        FloatBuffer buff = BufferUtils.createFloatBuffer(vertices.length);
        buff.put(vertices).flip();

        IntBuffer iBuff = BufferUtils.createIntBuffer(indexCount);
        iBuff.put(indices).flip();

        int vao = glGenVertexArrays();
        glBindVertexArray(vao);

        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, buff, GL_STATIC_DRAW);

        int ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, iBuff, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 24, 12);
        glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true, 24, 20);

        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        glEnableVertexAttribArray(2);

        return new VertexBinding(vao, vbo, ebo, indexCount);
    }

    public VertexBinding initCube(HarnessContext ctx) {
        float[] vertices = {
                // FRONT
                -0.5f, -0.5f, 0.5f,    /*UV*/0, 0,   /*NORMAL*/0, 0, 1,    /*COL*/col(0xffff0000),
                0.5f, -0.5f, 0.5f,     /*UV*/0, 1,   /*NORMAL*/0, 0, 1,    /*COL*/col(0xff0000ff),
                0.5f, 0.5f, 0.5f,      /*UV*/1, 1,   /*NORMAL*/0, 0, 1,    /*COL*/col(0xff00ffff),
                -0.5f, 0.5f, 0.5f,     /*UV*/0, 1,   /*NORMAL*/0, 0, 1,    /*COL*/col(0xff00ff00),
                // RIGHT
                0.5f, -0.5f, 0.5f,     /*UV*/0, 0,   /*NORMAL*/1, 0, 0,    /*COL*/col(0xffff0000),
                0.5f, -0.5f, -0.5f,    /*UV*/0, 1,   /*NORMAL*/1, 0, 0,    /*COL*/col(0xff0000ff),
                0.5f, 0.5f, -0.5f,     /*UV*/1, 1,   /*NORMAL*/1, 0, 0,    /*COL*/col(0xff00ffff),
                0.5f, 0.5f, 0.5f,      /*UV*/0, 1,   /*NORMAL*/1, 0, 0,    /*COL*/col(0xff00ff00),
                // BACK
                0.5f, -0.5f, -0.5f,    /*UV*/0, 0,   /*NORMAL*/0, 0, -1,    /*COL*/col(0xffff0000),
                -0.5f, -0.5f, -0.5f,   /*UV*/0, 1,   /*NORMAL*/0, 0, -1,    /*COL*/col(0xff0000ff),
                -0.5f, 0.5f, -0.5f,    /*UV*/1, 1,   /*NORMAL*/0, 0, -1,    /*COL*/col(0xff00ffff),
                0.5f, 0.5f, -0.5f,     /*UV*/0, 1,   /*NORMAL*/0, 0, -1,    /*COL*/col(0xff00ff00),
                // LEFT
                -0.5f, -0.5f, -0.5f,   /*UV*/0, 0,   /*NORMAL*/-1, 0, 0,    /*COL*/col(0xffff0000),
                -0.5f, -0.5f, 0.5f,    /*UV*/0, 1,   /*NORMAL*/-1, 0, 0,    /*COL*/col(0xff0000ff),
                -0.5f, 0.5f, 0.5f,     /*UV*/1, 1,   /*NORMAL*/-1, 0, 0,    /*COL*/col(0xff00ffff),
                -0.5f, 0.5f, -0.5f,    /*UV*/0, 1,   /*NORMAL*/-1, 0, 0,    /*COL*/col(0xff00ff00),
                // TOP
                -0.5f, 0.5f, 0.5f,     /*UV*/0, 0,   /*NORMAL*/0, 1, 0,    /*COL*/col(0xffff0000),
                0.5f, 0.5f, 0.5f,      /*UV*/0, 1,   /*NORMAL*/0, 1, 0,    /*COL*/col(0xff0000ff),
                0.5f, 0.5f, -0.5f,     /*UV*/1, 1,   /*NORMAL*/0, 1, 0,    /*COL*/col(0xff00ffff),
                -0.5f, 0.5f, -0.5f,    /*UV*/0, 1,   /*NORMAL*/0, 1, 0,    /*COL*/col(0xff00ff00),
                // BOTTOM
                -0.5f, -0.5f, 0.5f,    /*UV*/0, 0,   /*NORMAL*/0, -1, 0,    /*COL*/col(0xffff0000),
                0.5f, -0.5f, 0.5f,     /*UV*/0, 1,   /*NORMAL*/0, -1, 0,    /*COL*/col(0xff0000ff),
                0.5f, -0.5f, -0.5f,    /*UV*/1, 1,   /*NORMAL*/0, -1, 0,    /*COL*/col(0xff00ffff),
                -0.5f, -0.5f, -0.5f,   /*UV*/0, 1,   /*NORMAL*/0, -1, 0,    /*COL*/col(0xff00ff00),
        };

        int[] indices = {
                /*FRONT*/0, 1, 2, 2, 3, 0,
                /*RIGHT*/4, 5, 6, 6, 7, 4,
                /*BACK*/8, 9, 10, 10, 11, 8,
                /*LEFT*/12, 13, 14, 14, 15, 12,
                /*TOP*/16, 17, 18, 18, 19, 16,
                /*BOTTOM*/20, 21, 22, 22, 23, 20,
        };
        int indexCount = indices.length;

        FloatBuffer buff = BufferUtils.createFloatBuffer(vertices.length);
        buff.put(vertices).flip();

        IntBuffer iBuff = BufferUtils.createIntBuffer(indexCount);
        iBuff.put(indices).flip();

        int vao = glGenVertexArrays();
        glBindVertexArray(vao);

        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, buff, GL_STATIC_DRAW);

        int ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, iBuff, GL_STATIC_DRAW);

        int stride = (3 + 2 + 3) * 4 + 4;
        // POS3_UV2_NORMAL3_COL4UB
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);           // a_pos
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 12);          // a_uv
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 20);          // a_normal
        glVertexAttribPointer(3, 4, GL_UNSIGNED_BYTE, true, stride, 32);   // a_col

        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        glEnableVertexAttribArray(2);
        glEnableVertexAttribArray(3);

        return new VertexBinding(vao, vbo, ebo, indexCount);
    }

    public VertexBinding initAxis(HarnessContext ctx) {
        float[] vertices = {
                // X
                -AXIS_LENGTH, 0, 0, col(0xffff0000),
                AXIS_LENGTH, 0, 0, col(0xffff0000),

                // Y
                0, -AXIS_LENGTH, 0, col(0xff00ff00),
                0, AXIS_LENGTH, 0, col(0xff00ff00),

                // Z
                0, 0, -AXIS_LENGTH, col(0xff0000ff),
                0, 0, AXIS_LENGTH, col(0xff0000ff)
        };

        FloatBuffer buff = BufferUtils.createFloatBuffer(vertices.length);
        buff.put(vertices).flip();

        int vao = glGenVertexArrays();
        glBindVertexArray(vao);

        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, buff, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0);
        glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, 16, 12);

        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);

        return new VertexBinding(vao, vbo, -1, 6);
    }

    @Override
    public void init(HarnessContext ctx) {
        this.ctx = ctx;
        CgGlState.hostForeign(setUp, TOUCHES);

        projection.perspective((float) Math.PI / 3, ctx.getViewport().getAspectRatio(), 0.001f, 1000f);
    }

    private void setUp() {
        triangleShader = RawGlProgram.load(DIR + "triangle.vert", DIR + "triangle.frag");
        cubeShader = RawGlProgram.load(DIR + "cube.vert", DIR + "sphere_sun.frag");
        sunShader = RawGlProgram.load(DIR + "sun.vert", DIR + "sun.frag");
        outlineShader = RawGlProgram.load(DIR1 + "outline.vert", DIR1 + "outline.frag");

        triangle = initTriangle(ctx);
        quad = initQuad(ctx);
        cube = initCube(ctx);
        axis = initAxis(ctx);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // Outside the raw drawing: the texture loads and binds through CrystalGraphics
        if (UV_DISTORTION == null)
            UV_DISTORTION = CgTexture2D.create(DIR1 + "tex2.png", CgTextureSpec.RGBA8_LINEAR_REPEAT);
        CgGlState.hostForeign(draw, TOUCHES);
    }

    private void draw() {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        triangleShader.use()
                      .mat4("u_model", ORIGIN.getModel())
                      .mat4("u_view", ctx.getCamera3D().getViewMatrix())
                      .mat4("u_projection", projection)
                      .vec4("u_cameraPos", ctx.getCamera3D().getPos());

        //Axis
        glBindVertexArray(axis.vaoId);
        glLineWidth(2f);
        glDrawArrays(GL_LINES, 0, axis.indexCount);
        glLineWidth(1f);

        // Triangle
        glBindVertexArray(triangle.vaoId);
        // glDrawArrays(GL_TRIANGLES, 0, triangle.indexCount);

        // Sun
        glBindVertexArray(quad.vaoId);
        //  rotateSun();
        //  sunShader.use()
        //  .mat4("u_model", sunPose.getModel())
        //  .mat4("u_view", ctx.getCamera3D().getViewMatrix())
        //  .mat4("u_projection", projection);
        //        glDrawElements(GL_TRIANGLES, quad.indexCount, GL_UNSIGNED_INT, 0);

        // Cube
        glBindVertexArray(cube.vaoId);
        cubeShader.use()
                .mat4("u_model", cubePos.getModel())
                .mat4("u_view", ctx.getCamera3D().getViewMatrix())
                .mat4("u_projection", projection);
        // glDrawElements(GL_TRIANGLES, cube.indexCount, GL_UNSIGNED_INT, 0);

        // Ray traced sphere
        Camera3D cam = ctx.getCamera3D();
        cubeShader.use()
                .mat4("u_model", cubePos.getModel())
                .mat4("u_view", cam.getViewMatrix())
                .mat4("u_projection", projection)
                .vec3("u_lightPos", sunPose.getPos())
                .vec4("u_cameraPos", cam.getPos())
                .vec4("u_localCameraPos", cam.getPos().mul(cubePos.getModel().invert(new Matrix4f()), TEMP4));
        glDrawElements(GL_TRIANGLES, cube.indexCount, GL_UNSIGNED_INT, 0);


        //        glActiveTexture(GL_TEXTURE0);
        //        glBindTexture(GL_TEXTURE_2D, UV_DISTORTION_MAP);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, UV_DISTORTION.getId());

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, NOISE_MAP);

        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, ALPHA_MAP);

        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, FLAME_NOISE);

        glBindVertexArray(quad.vaoId);
        outlineShader.use()
                .set1i("distortion_map", 0)
                .set1i("noise_map", 1)
                .set1i("alpha_map", 2)
                .set1i("flame_map", 3);
        glDrawElements(GL_TRIANGLES, quad.indexCount, GL_UNSIGNED_INT, 0);
        glActiveTexture(GL_TEXTURE0);
    }

    private static final Vector3f TEMP = new Vector3f();
    private static final Vector4f TEMP4 = new Vector4f();

    public void rotateSun() {
        float duration = 60f;

        long cycleTimeMillis = System.currentTimeMillis() % (long) (duration * 1000f);
        float cycleTime = cycleTimeMillis / 1000f;
        float progress = cycleTime / duration;

        float angle = (float) (progress * 2 * Math.PI);
        TEMP.set(sunOrigin.getPos());
        //        TEMP.set(0, 2, 0.01f);
        TEMP.rotateZ(angle);
        sunPose.setPosition(TEMP);
    }

    @Override
    public void onResize(int width, int height) {
        projection.setPerspective((float) Math.PI / 3, (float) width / (float) height, 0.01f, 1000f);
    }

    @Override
    public void dispose() {
        triangle.dispose();
        quad.dispose();
        cube.dispose();
        axis.dispose();

        triangleShader.delete();
        cubeShader.delete();
        sunShader.delete();
        outlineShader.delete();
        CgGlState.invalidateAllIfPresent();
    }

    public float col(int argb) {
        int r = argb >> 16 & 0xff;
        int g = argb >> 8 & 0xff;
        int b = argb & 0xff;
        int a = argb >> 24 & 0xff;

        int packed;
        boolean littleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

        if (littleEndian) packed = a << 24 | b << 16 | g << 8 | r;
        else packed = r << 24 | g << 16 | b << 8 | a;
        return Float.intBitsToFloat(packed);
    }

    @Override
    public boolean isRunning() {return true;}

    @Override
    public boolean uses3DCamera() {return true;}

    @Override
    public boolean shouldShutdownOnComplete() {return false;}
}
