package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.util.HarnessBuffers;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.shader.CgShaderFactory;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.camera.Camera3D;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.object.Pose;
import com.crystalgraphics.harness.object.VertexBinding;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public class ReviewScene implements InteractiveSceneLifecycle {
    private static final String DIR = "assets/harness/shader/review/";
    private static final Pose ORIGIN = new Pose();

    private VertexBinding triangle;
    private CgShader triangleShader = CgShaderFactory.load(DIR + "triangle.vert", DIR + "triangle.frag");

    private VertexBinding quad;

    private VertexBinding cube;
    private CgShader cubeShader = CgShaderFactory.load(DIR + "cube.vert", DIR + "sphere_sun.frag");
    private Pose cubePos = new Pose();

    private VertexBinding axis;
    private static final float AXIS_LENGTH = 500f;

    private Pose sunPose = new Pose();
    private Pose sunOrigin = new Pose().setPosition(5, 0, 0);
    private CgShader sunShader = CgShaderFactory.load(DIR + "sun.vert", DIR + "sun.frag");

    private Matrix4f projection = new Matrix4f();

    private static final String DIR1 = "assets/harness/shader/outline/";
    private CgShader outlineShader = CgShaderFactory.load(DIR1 + "outline.vert", DIR1 + "outline.frag");

    private static  int UV_DISTORTION_MAP ;//= HarnessTextureUtil.loadTexture(DIR1 + "tex2.png");
    private static  CgTexture UV_DISTORTION ;//=  CgTexture2D.create(DIR1 + "tex2.png", CgTextureSpec.R8_LINEAR_REPEAT);
    private static  int NOISE_MAP ;//= HarnessTextureUtil.loadTexture(DIR1 + "tex1.png");
    private static  int ALPHA_MAP ;//= HarnessTextureUtil.loadTexture(DIR1 + "tex4.png");
    private static  int FLAME_NOISE;// = HarnessTextureUtil.loadTexture(DIR1 + "tex5.png");

    public VertexBinding initTriangle(HarnessContext ctx) {
        float[] vertices = {
                -0.5f, -0.5f, 0, col(0xffff0000),
                0.5f, -0.5f, 0, col(0xff0000ff),
                0, 0.5f, 0, col(0xff00ff00)
        };

        FloatBuffer buff = BufferUtils.createFloatBuffer(vertices.length);
        buff.put(vertices).flip();

        int vao = CgGL.glGenVertexArrays();
        CgGL.glBindVertexArray(vao);

        int vbo = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vbo);
        CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, HarnessBuffers.bytes(buff), CgGL.GL_STATIC_DRAW);

        CgGL.glVertexAttribPointer(0, 3, CgGL.GL_FLOAT, false, 16, 0);
        CgGL.glVertexAttribPointer(1, 4, CgGL.GL_UNSIGNED_BYTE, true, 16, 12);

        CgGL.glEnableVertexAttribArray(0);
        CgGL.glEnableVertexAttribArray(1);

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

        int vao = CgGL.glGenVertexArrays();
        CgGL.glBindVertexArray(vao);

        int vbo = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vbo);
        CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, HarnessBuffers.bytes(buff), CgGL.GL_STATIC_DRAW);

        int ebo = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, ebo);
        CgGL.glBufferData(CgGL.GL_ELEMENT_ARRAY_BUFFER, HarnessBuffers.bytes(iBuff), CgGL.GL_STATIC_DRAW);

        CgGL.glVertexAttribPointer(0, 3, CgGL.GL_FLOAT, false, 24, 0);
        CgGL.glVertexAttribPointer(1, 2, CgGL.GL_FLOAT, false, 24, 12);
        CgGL.glVertexAttribPointer(2, 4, CgGL.GL_UNSIGNED_BYTE, true, 24, 20);

        CgGL.glEnableVertexAttribArray(0);
        CgGL.glEnableVertexAttribArray(1);
        CgGL.glEnableVertexAttribArray(2);

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

        int vao = CgGL.glGenVertexArrays();
        CgGL.glBindVertexArray(vao);

        int vbo = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vbo);
        CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, HarnessBuffers.bytes(buff), CgGL.GL_STATIC_DRAW);

        int ebo = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, ebo);
        CgGL.glBufferData(CgGL.GL_ELEMENT_ARRAY_BUFFER, HarnessBuffers.bytes(iBuff), CgGL.GL_STATIC_DRAW);

        int stride = (3 + 2 + 3) * 4 + 4;
        // POS3_UV2_NORMAL3_COL4UB
        CgGL.glVertexAttribPointer(0, 3, CgGL.GL_FLOAT, false, stride, 0);           // a_pos
        CgGL.glVertexAttribPointer(1, 2, CgGL.GL_FLOAT, false, stride, 12);          // a_uv
        CgGL.glVertexAttribPointer(2, 3, CgGL.GL_FLOAT, false, stride, 20);          // a_normal
        CgGL.glVertexAttribPointer(3, 4, CgGL.GL_UNSIGNED_BYTE, true, stride, 32);   // a_col

        CgGL.glEnableVertexAttribArray(0);
        CgGL.glEnableVertexAttribArray(1);
        CgGL.glEnableVertexAttribArray(2);
        CgGL.glEnableVertexAttribArray(3);

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

        int vao = CgGL.glGenVertexArrays();
        CgGL.glBindVertexArray(vao);

        int vbo = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vbo);
        CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, HarnessBuffers.bytes(buff), CgGL.GL_STATIC_DRAW);

        CgGL.glVertexAttribPointer(0, 3, CgGL.GL_FLOAT, false, 16, 0);
        CgGL.glVertexAttribPointer(1, 4, CgGL.GL_UNSIGNED_BYTE, true, 16, 12);

        CgGL.glEnableVertexAttribArray(0);
        CgGL.glEnableVertexAttribArray(1);

        return new VertexBinding(vao, vbo, -1, 6);
    }

    @Override
    public void init(HarnessContext ctx) {
        triangle = initTriangle(ctx);
        quad = initQuad(ctx);
        cube = initCube(ctx);
        axis = initAxis(ctx);

        projection.perspective((float) Math.PI / 3, ctx.getViewport().getAspectRatio(), 0.001f, 1000f);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgGL.glEnable(CgGL.GL_BLEND);
        CgGL.glBlendFunc(CgGL.GL_SRC_ALPHA, CgGL.GL_ONE_MINUS_SRC_ALPHA);

        triangleShader.bindings()
                      .mat4("u_model", ORIGIN.getModel())
                      .mat4("u_view", ctx.getCamera3D().getViewMatrix())
                      .mat4("u_projection", projection)
                      .vec4("u_cameraPos", ctx.getCamera3D().getPos());
        triangleShader.bind();

        //Axis
        CgGL.glBindVertexArray(axis.vaoId);
        CgGL.glLineWidth(2f);
        CgGL.glDrawArrays(CgGL.GL_LINES, 0, axis.indexCount);
        CgGL.glLineWidth(1f);

        // Triangle
        CgGL.glBindVertexArray(triangle.vaoId);
        // CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, triangle.indexCount);

        // Sun
        CgGL.glBindVertexArray(quad.vaoId);
        //  rotateSun();
        //  sunShader.applyBindings(b -> {
        //  b.mat4("u_model", sunPose.getModel());
        //  b.mat4("u_view", ctx.getCamera3D().getViewMatrix());
        //  b.mat4("u_projection", projection);
        //  }).bind();
        //        CgGL.glDrawElements(CgGL.GL_TRIANGLES, quad.indexCount, CgGL.GL_UNSIGNED_INT, 0);

        // Cube
        CgGL.glBindVertexArray(cube.vaoId);
        cubeShader.applyBindings(b -> {
            b.mat4("u_model", cubePos.getModel());
            b.mat4("u_view", ctx.getCamera3D().getViewMatrix());
            b.mat4("u_projection", projection);
        }).bind();
        // CgGL.glDrawElements(CgGL.GL_TRIANGLES, cube.indexCount, CgGL.GL_UNSIGNED_INT, 0);

        // Ray traced sphere
        Camera3D cam = ctx.getCamera3D();
        cubeShader.applyBindings(b -> {
            b.mat4("u_model", cubePos.getModel());
            b.mat4("u_view", cam.getViewMatrix());
            b.mat4("u_projection", projection);
            b.vec3("u_lightPos", sunPose.getPos());
            b.vec4("u_cameraPos", cam.getPos());
            b.vec4("u_localCameraPos", cam.getPos().mul(cubePos.getModel().invert(new Matrix4f()), TEMP4));
        }).bind();
        CgGL.glDrawElements(CgGL.GL_TRIANGLES, cube.indexCount, CgGL.GL_UNSIGNED_INT, 0);


        //        CgGL.glActiveTexture(CgGL.GL_TEXTURE0);
        //        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, UV_DISTORTION_MAP);
//        if (UV_DISTORTION == null)
//            UV_DISTORTION =  CgTexture2D.create(DIR1 + "tex2.png", CgTextureSpec.RGBA8_LINEAR_REPEAT);
        UV_DISTORTION.bind(0);

        CgGL.glActiveTexture(CgGL.GL_TEXTURE1);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, NOISE_MAP);

        CgGL.glActiveTexture(CgGL.GL_TEXTURE2);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, ALPHA_MAP);

        CgGL.glActiveTexture(CgGL.GL_TEXTURE3);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, FLAME_NOISE);

        CgGL.glBindVertexArray(quad.vaoId);
        outlineShader.applyBindings(b -> {
            b.set1i("distortion_map", 0);
            b.set1i("noise_map", 1);
            b.set1i("alpha_map", 2);
            b.set1i("flame_map", 3);
        }).bind();
        CgGL.glDrawElements(CgGL.GL_TRIANGLES, quad.indexCount, CgGL.GL_UNSIGNED_INT, 0);
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
