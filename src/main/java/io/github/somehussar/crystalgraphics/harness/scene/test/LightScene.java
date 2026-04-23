package io.github.somehussar.crystalgraphics.harness.scene.test;

import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public class LightScene implements InteractiveSceneLifecycle {
    int vaoId, vboId, eboId, cubeIndexCount;

    Matrix4f model = new Matrix4f(), projection = new Matrix4f();

    private static final String DIR = "assets/harness/shader/";
    CgShader shader = CgShaderFactory.load(DIR + "pos3_uv2_normal3_col4ub.vert", DIR + "pos3_uv2_normal3_col4ub.frag");

    public void initCube() {
        float[] cube = {
                /*FRONT*/
                -0.5f, -0.5f, 0.5f,   /*UV*/0, 0,  /*NORMAL*/0, 0, 1,  /*COL*/col(0xff000000),
                0.5f, -0.5f, 0.5f,    /*UV*/1, 0,  /*NORMAL*/0, 0, 1,  /*COL*/col(0xffff0000),
                0.5f, 0.5f, 0.5f,     /*UV*/1, 1,  /*NORMAL*/0, 0, 1,  /*COL*/col(0xffffff00),
                -0.5f, 0.5f, 0.5f,    /*UV*/0, 1,  /*NORMAL*/0, 0, 1,  /*COL*/col(0xff00ff00),
                /*RIGHT*/
                0.5f, -0.5f, 0.5f,    /*UV*/0, 0,  /*NORMAL*/1, 0, 0,  /*COL*/col(0xff000000),
                0.5f, -0.5f, -0.5f,   /*UV*/1, 0,  /*NORMAL*/1, 0, 0,  /*COL*/col(0xffff0000),
                0.5f, 0.5f, -0.5f,    /*UV*/1, 1,  /*NORMAL*/1, 0, 0,  /*COL*/col(0xffffff00),
                0.5f, 0.5f, 0.5f,     /*UV*/0, 1,  /*NORMAL*/1, 0, 0,  /*COL*/col(0xff00ff00),
                /*BACK*/
                0.5f, -0.5f, -0.5f,   /*UV*/0, 0,  /*NORMAL*/0, 0, -1, /*COL*/col(0xff000000),
                -0.5f, -0.5f, -0.5f,  /*UV*/1, 0,  /*NORMAL*/0, 0, -1, /*COL*/col(0xffff0000),
                -0.5f, 0.5f, -0.5f,   /*UV*/1, 1,  /*NORMAL*/0, 0, -1, /*COL*/col(0xffffff00),
                0.5f, 0.5f, -0.5f,    /*UV*/0, 1,  /*NORMAL*/0, 0, -1, /*COL*/col(0xff00ff00),
                /*LEFT*/
                -0.5f, -0.5f, -0.5f,  /*UV*/0, 0,  /*NORMAL*/-1, 0, 0, /*COL*/col(0xff000000),
                -0.5f, -0.5f, 0.5f,   /*UV*/1, 0,  /*NORMAL*/-1, 0, 0, /*COL*/col(0xffff0000),
                -0.5f, 0.5f, 0.5f,    /*UV*/1, 1,  /*NORMAL*/-1, 0, 0, /*COL*/col(0xffffff00),
                -0.5f, 0.5f, -0.5f,   /*UV*/0, 1,  /*NORMAL*/-1, 0, 0, /*COL*/col(0xff00ff00),
                /*TOP*/
                -0.5f, 0.5f, 0.5f,    /*UV*/0, 0,  /*NORMAL*/0, 1, 0,  /*COL*/col(0xff000000),
                0.5f, 0.5f, 0.5f,     /*UV*/1, 0,  /*NORMAL*/0, 1, 0,  /*COL*/col(0xffff0000),
                0.5f, 0.5f, -0.5f,    /*UV*/1, 1,  /*NORMAL*/0, 1, 0,  /*COL*/col(0xffffff00),
                -0.5f, 0.5f, -0.5f,   /*UV*/0, 1,  /*NORMAL*/0, 1, 0,  /*COL*/col(0xff00ff00),
                /*BOTTOM*/
                -0.5f, -0.5f, 0.5f,   /*UV*/0, 0,  /*NORMAL*/0, -1, 0, /*COL*/col(0xff000000),
                0.5f, -0.5f, 0.5f,    /*UV*/1, 0,  /*NORMAL*/0, -1, 0, /*COL*/col(0xffff0000),
                0.5f, -0.5f, -0.5f,   /*UV*/1, 1,  /*NORMAL*/0, -1, 0, /*COL*/col(0xffffff00),
                -0.5f, -0.5f, -0.5f,  /*UV*/0, 1,  /*NORMAL*/0, -1, 0, /*COL*/col(0xff00ff00),
        };

        int[] indices = {
                /*FRONT*/ 0, 1, 2, 2, 3, 0,
                /*RIGHT*/ 4, 5, 6, 6, 7, 4,
                /*BACK*/ 8, 9, 10, 10, 11, 8,
                /*LEFT*/ 12, 13, 14, 14, 15, 12,
                /*TOP*/ 16, 17, 18, 18, 19, 16,
                /*BOTTOM*/ 20, 21, 22, 22, 23, 20
        };
        cubeIndexCount = indices.length;


        FloatBuffer buff = BufferUtils.createFloatBuffer(cube.length);
        buff.put(cube).flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buff, GL15.GL_STATIC_DRAW);


        IntBuffer iBuff = BufferUtils.createIntBuffer(cubeIndexCount);
        iBuff.put(indices).flip();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, iBuff, GL15.GL_STATIC_DRAW);
    }

    public float col(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = (argb) & 0xFF;
        int a = (argb >> 24) & 0xFF;

        int packed;
        boolean littleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

        if (littleEndian) packed = (a << 24) | (b << 16) | (g << 8) | r;
        else packed = (r << 24) | (g << 16) | (b << 8) | a;

        return Float.intBitsToFloat(packed);
    }

    @Override
    public void init(HarnessContext ctx) {
        projection.perspective((float) Math.toRadians(60f), ctx.getViewport().getAspectRatio(), 0.001f, 1000);

        vaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoId);

        vboId = GL15.glGenBuffers();
        eboId = GL15.glGenBuffers();

        initCube();

        //  POS3_UV2_NORMAL3_COL4UB
        int stride = (3 + 2 + 3) * 4 + 4;
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);          // a_pos
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 12);         // a_uv
        GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, stride, 20);         // a_normal
        GL20.glVertexAttribPointer(3, 4, GL11.GL_UNSIGNED_BYTE, true, stride, 32);  // a_color

        GL20.glEnableVertexAttribArray(0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glEnableVertexAttribArray(2);
        GL20.glEnableVertexAttribArray(3);
    }

    public void onResize(int width, int height) {
        projection.setPerspective((float) Math.toRadians(60f), (float) width / (float) height, 0.001f, 1000);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        model.setTranslation(0, 0.6f, -2.f);

        GL30.glBindVertexArray(vaoId);
        shader.applyBindings(b -> {
            b.mat4("u_model", model);
            b.mat4("u_view", ctx.getCamera3D().getViewMatrix());
            b.mat4("u_projection", projection);
        }).bind();
        GL11.glDrawElements(GL11.GL_TRIANGLES, cubeIndexCount, GL11.GL_UNSIGNED_INT, 0);
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public boolean uses3DCamera() {
        return true;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return false;
    }

    @Override
    public void dispose() {
        GL30.glDeleteVertexArrays(vaoId);
        GL15.glDeleteBuffers(vboId);
        GL15.glDeleteBuffers(eboId);
        shader.delete();
    }
}
