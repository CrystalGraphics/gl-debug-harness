package com.crystalgraphics.harness.object;

import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.RawGlProgram;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static com.crystalgraphics.harness.util.ColorUtil.col;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Raw LWJGL GL. {@link #render} draws inside its caller's {@code CgGlState.hostForeign}; {@link #init} brackets
 * itself, since a scene on {@code CgGL} (MeshTestScene) calls it too. GL device only.
 */
public class WorldAxisRenderer {

    private static final float LENGTH = 500;
    private VertexBinding binding;

    private Matrix4f model = new Matrix4f().identity();

    private static final String DIR = "assets/harness/shader/";
    private RawGlProgram shader;

    public VertexBinding init(HarnessContext ctx) {
        CgGlState.hostForeign(this::setUp, CgGlSlot.VERTEX_INPUT);
        return binding;
    }

    private void setUp() {
        shader = RawGlProgram.load(DIR + "pos3_col4ub.vert", DIR + "pos3_col4ub.frag");
        float[] vertices = {
                //X
                -LENGTH, 0, 0, col(0xffff0000),
                LENGTH, 0, 0, col(0xffff0000),
                // Y
                0, -LENGTH, 0, col(0xff00ff00),
                0, LENGTH, 0, col(0xff00ff00),
                // Z
                0, 0, -LENGTH, col(0xff0000ff),
                0, 0, LENGTH, col(0xff0000ff)
        };

        int[] indices = {
                /*X*/ 0, 1,
                /*Y*/ 2, 3,
                /*Z*/ 4, 5,
        };
        int indexCount = indices.length;

        FloatBuffer buff = BufferUtils.createFloatBuffer(vertices.length);
        buff.put(vertices).flip();

        IntBuffer iBuff = BufferUtils.createIntBuffer(indexCount);
        iBuff.put(indices).flip();

        int vaoId = glGenVertexArrays();
        glBindVertexArray(vaoId);

        int vboId = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glBufferData(GL_ARRAY_BUFFER, buff, GL_STATIC_DRAW);

        int eboId = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, eboId);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, iBuff, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0);
        glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, 16, 12);

        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);

        binding = new VertexBinding(vaoId, vboId, eboId, indexCount);
    }

    public void render(HarnessContext ctx) {
        glBindVertexArray(binding.vaoId);
        shader.use()
                .mat4("u_model", model)
                .mat4("u_view", ctx.getCamera3D().getViewMatrix())
                .mat4("u_projection", ctx.getProjection());
        glLineWidth(2);
        glDrawElements(GL_LINES, binding.indexCount, GL_UNSIGNED_INT, 0);
        glLineWidth(1);
    }

    public void dispose() {
        if (binding != null) binding.dispose();
        if (shader != null) shader.delete();
    }
}
