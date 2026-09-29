package io.github.somehussar.crystalgraphics.harness.object;

import io.github.somehussar.crystalgraphics.harness.util.HarnessBuffers;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static io.github.somehussar.crystalgraphics.harness.util.ColorUtil.col;

public class WorldAxisRenderer {

    private static final float LENGTH = 500;
    private VertexBinding binding;

    private Matrix4f model = new Matrix4f().identity();

    private static final String DIR = "assets/harness/shader/";
    private CgShader shader = CgShaderFactory.load(DIR + "pos3_col4ub.vert", DIR + "pos3_col4ub.frag");

    public VertexBinding init(HarnessContext ctx) {
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

        int vaoId = CgGL.glGenVertexArrays();
        CgGL.glBindVertexArray(vaoId);

        int vboId = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vboId);
        CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, HarnessBuffers.bytes(buff), CgGL.GL_STATIC_DRAW);

        int eboId = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, eboId);
        CgGL.glBufferData(CgGL.GL_ELEMENT_ARRAY_BUFFER, HarnessBuffers.bytes(iBuff), CgGL.GL_STATIC_DRAW);

        CgGL.glVertexAttribPointer(0, 3, CgGL.GL_FLOAT, false, 16, 0);
        CgGL.glVertexAttribPointer(1, 4, CgGL.GL_UNSIGNED_BYTE, true, 16, 12);

        CgGL.glEnableVertexAttribArray(0);
        CgGL.glEnableVertexAttribArray(1);

        return binding = new VertexBinding(vaoId, vboId, eboId, indexCount);
    }

    public void render(HarnessContext ctx) {
        CgGL.glBindVertexArray(binding.vaoId);
        shader.applyBindings(b -> {
            b.mat4("u_model", model);
            b.mat4("u_view", ctx.getCamera3D().getViewMatrix());
            b.mat4("u_projection", ctx.getProjection());
        }).bind();
        CgGL.glLineWidth(2);
        CgGL.glDrawElements(CgGL.GL_LINES, binding.indexCount, CgGL.GL_UNSIGNED_INT, 0);
        CgGL.glLineWidth(1);
    }

    public void dispose() {
        if (binding != null) binding.dispose();
    }
}
