package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.camera.Camera3D;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.object.LightSource;
import com.crystalgraphics.harness.object.WorldAxisRenderer;
import com.crystalgraphics.harness.util.RawGlProgram;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33C.*;

/** Raw LWJGL GL, drawn inside {@code CgGlState.hostForeign} while {@code CgGL}'s shadow exists. GL device only. */
public class LightScene implements InteractiveSceneLifecycle {
    private static final CgGlSlot[] TOUCHES = {CgGlSlot.VERTEX_INPUT, CgGlSlot.PROGRAM, CgGlSlot.TEXTURES, CgGlSlot.BLEND,
            CgGlSlot.LINE_WIDTH};

    int vaoId, vboId, eboId, cubeIndexCount;

    private static final String DIR = "assets/harness/shader/";
    RawGlProgram shader;
    RawGlProgram sphereShader;

    Matrix4f model = new Matrix4f(), projection = new Matrix4f();

    WorldAxisRenderer axis = new WorldAxisRenderer();
    LightSource lightSource = new LightSource();

    private HarnessContext ctx;
    private FrameInfo frame;
    private final Runnable setUp = this::setUp, draw = this::draw;

    public void initCube() {
        float[] cube = {
                /*FRONT*/
                -0.5f, -0.5f, 0.5f,   /*UV*/ 0, 0,   /*NORMAL*/ 0, 0, 1,   /*COL*/ col(0xffff0000),
                0.5f, -0.5f, 0.5f,    /*UV*/ 1, 0,   /*NORMAL*/ 0, 0, 1,   /*COL*/ col(0xff0000ff),
                0.5f, 0.5f, 0.5f,     /*UV*/ 1, 1,   /*NORMAL*/ 0, 0, 1,   /*COL*/ col(0xff00ffff),
                -0.5f, 0.5f, 0.5f,    /*UV*/ 0, 1,   /*NORMAL*/ 0, 0, 1,   /*COL*/ col(0xffff00ff),

                /*RIGHT*/
                0.5f, -0.5f, 0.5f,    /*UV*/ 0, 0,   /*NORMAL*/ 1, 0, 0,   /*COL*/ col(0xffff0000),
                0.5f, -0.5f, -0.5f,   /*UV*/ 1, 0,   /*NORMAL*/ 1, 0, 0,   /*COL*/ col(0xff0000ff),
                0.5f, 0.5f, -0.5f,    /*UV*/ 1, 1,   /*NORMAL*/ 1, 0, 0,   /*COL*/ col(0xff00ffff),
                0.5f, 0.5f, 0.5f,     /*UV*/ 0, 1,   /*NORMAL*/ 1, 0, 0,   /*COL*/ col(0xffff00ff),

                /*BACK*/
                0.5f, -0.5f, -0.5f,   /*UV*/ 0, 0,   /*NORMAL*/ 0, 0, -1,   /*COL*/ col(0xffff0000),
                -0.5f, -0.5f, -0.5f,  /*UV*/ 1, 0,   /*NORMAL*/ 0, 0, -1,   /*COL*/ col(0xff0000ff),
                -0.5f, 0.5f, -0.5f,   /*UV*/ 1, 1,   /*NORMAL*/ 0, 0, -1,   /*COL*/ col(0xff00ffff),
                0.5f, 0.5f, -0.5f,    /*UV*/ 0, 1,   /*NORMAL*/ 0, 0, -1,   /*COL*/ col(0xffff00ff),

                /*LEFT*/
                -0.5f, -0.5f, -0.5f,  /*UV*/ 0, 0,   /*NORMAL*/ -1, 0, 0,   /*COL*/ col(0xffff0000),
                -0.5f, -0.5f, 0.5f,   /*UV*/ 1, 0,   /*NORMAL*/ -1, 0, 0,   /*COL*/ col(0xff0000ff),
                -0.5f, 0.5f, 0.5f,    /*UV*/ 1, 1,   /*NORMAL*/ -1, 0, 0,   /*COL*/ col(0xff00ffff),
                -0.5f, 0.5f, -0.5f,   /*UV*/ 0, 1,   /*NORMAL*/ -1, 0, 0,   /*COL*/ col(0xffff00ff),

                /*TOP*/
                -0.5f, 0.5f, 0.5f,    /*UV*/ 0, 0,   /*NORMAL*/ 0, 1, 0,    /*COL*/ col(0xffff0000),
                0.5f, 0.5f, 0.5f,     /*UV*/ 1, 0,   /*NORMAL*/ 0, 1, 0,    /*COL*/ col(0xff0000ff),
                0.5f, 0.5f, -0.5f,    /*UV*/ 1, 1,   /*NORMAL*/ 0, 1, 0,    /*COL*/ col(0xff00ffff),
                -0.5f, 0.5f, -0.5f,   /*UV*/ 0, 1,   /*NORMAL*/ 0, 1, 0,    /*COL*/ col(0xffff00ff),

                /*BOTTOM*/
                -0.5f, -0.5f, 0.5f,   /*UV*/ 0, 0,   /*NORMAL*/ 0, -1, 0,   /*COL*/ col(0xffff0000),
                0.5f, -0.5f, 0.5f,    /*UV*/ 1, 0,   /*NORMAL*/ 0, -1, 0,   /*COL*/ col(0xff0000ff),
                0.5f, -0.5f, -0.5f,   /*UV*/ 1, 1,   /*NORMAL*/ 0, -1, 0,   /*COL*/ col(0xff00ffff),
                -0.5f, -0.5f, -0.5f,  /*UV*/ 0, 1,   /*NORMAL*/ 0, -1, 0,   /*COL*/ col(0xffff00ff),
        };

        int[] indices = {
                /*FRONT*/  0, 1, 2, 2, 3, 0,
                /*RIGHT*/  4, 5, 6, 6, 7, 4,
                /*BACK*/   8, 9, 10, 10, 11, 8,
                /*LEFT*/   12, 13, 14, 14, 15, 12,
                /*TOP*/    16, 17, 18, 18, 19, 16,
                /*BOTTOM*/ 20, 21, 22, 22, 23, 20
        };
        cubeIndexCount = indices.length;


        FloatBuffer buff = BufferUtils.createFloatBuffer(cube.length);
        buff.put(cube).flip();

        IntBuffer iBuff = BufferUtils.createIntBuffer(indices.length);
        iBuff.put(indices).flip();

        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glBufferData(GL_ARRAY_BUFFER, buff, GL_STATIC_DRAW);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, eboId);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, iBuff, GL_STATIC_DRAW);
    }

    @Override
    public void init(HarnessContext ctx) {
        this.ctx = ctx;
        axis.init(ctx); //3D XYZ axis

        // Sun
        lightSource.rotationStart(5, 0, 0).rotationAxis(0, 0, 1);
        lightSource.pose.setScale(5);

        projection.perspective((float) Math.toRadians(60), ctx.getViewport().getAspectRatio(), 0.001f, 1000f);

        CgGlState.hostForeign(setUp, TOUCHES);
    }

    private void setUp() {
        lightSource.init(ctx);
        shader = RawGlProgram.load(DIR + "pos3_uv2_normal3_col4ub.vert", DIR + "pos3_uv2_normal3_col4ub.frag");
        sphereShader = RawGlProgram.load(DIR + "circle/sphere.vert", DIR + "circle/sphere.frag");

        vaoId = glGenVertexArrays();
        glBindVertexArray(vaoId);

        vboId = glGenBuffers();
        eboId = glGenBuffers();

        initCube();

        //  POS3_UV2_NORMAL3_COL4UB
        int stride = (3 + 2 + 3) * 4 + 4;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);          // a_pos
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 12);         // a_uv
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 20);         // a_normal
        glVertexAttribPointer(3, 4, GL_UNSIGNED_BYTE, true, stride, 32);  // a_col

        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        glEnableVertexAttribArray(2);
        glEnableVertexAttribArray(3);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        this.frame = frame;
        CgGlState.hostForeign(draw, TOUCHES);
    }

    private void draw() {
        Camera3D cam = ctx.getCamera3D();
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        axis.render(ctx);
        // Core profile: no alpha test (was glDisable(GL_ALPHA_TEST))
       // glAlphaFunc(GL_GREATER,0.01f);
        glBindVertexArray(vaoId);
        sphereShader.use()
                .mat4("u_model", model)
                .mat4("u_view", cam.getViewMatrix())
                .mat4("u_projection", projection)
                .vec3("u_lightPos", lightSource.pose.getPos())
                .vec4("u_lightCol", 1f, 0.91f, 0.37f, 1f)
                .vec4("u_cameraPos", cam.getPos())
                .vec4("u_localCameraPos", cam.getPos().mul(model.invert(new Matrix4f())));
        glDrawElements(GL_TRIANGLES, cubeIndexCount, GL_UNSIGNED_INT, 0);

        lightSource.render(ctx, frame);
    }

    @Override
    public void onResize(int width, int height) {
        projection.setPerspective((float) Math.toRadians(60f), (float) width / (float) height, 0.001f, 1000f);
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

    public float col(int argb) {
        int r = ((argb >> 16) & 0xff);
        int g = ((argb >> 8) & 0xff);
        int b = (argb & 0xff);
        int a = ((argb >> 24) & 0xff);

        int packed;
        boolean littleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

        if (littleEndian) packed = a << 24 | b << 16 | g << 8 | r;
        else packed = r << 24 | g << 16 | b << 8 | a;

        return Float.intBitsToFloat(packed);
    }

    @Override
    public void dispose() {
        glDeleteVertexArrays(vaoId);
        glDeleteBuffers(vboId);
        glDeleteBuffers(eboId);
        shader.delete();
        sphereShader.delete();
        axis.dispose();
        lightSource.dispose();
        CgGlState.invalidateAllIfPresent();
    }
}
