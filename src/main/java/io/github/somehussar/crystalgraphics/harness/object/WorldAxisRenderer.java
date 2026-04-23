package io.github.somehussar.crystalgraphics.harness.object;

import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

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

        int vaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoId);

        int vboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buff, GL15.GL_STATIC_DRAW);

        int eboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, iBuff, GL15.GL_STATIC_DRAW);

        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 16, 0);
        GL20.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, 16, 12);

        GL20.glEnableVertexAttribArray(0);
        GL20.glEnableVertexAttribArray(1);

        return binding = new VertexBinding(vaoId, vboId, eboId, indexCount);
    }

    public void render(HarnessContext ctx) {
        GL30.glBindVertexArray(binding.vaoId);
        shader.applyBindings(b -> {
            b.mat4("u_model", model);
            b.mat4("u_view", ctx.getCamera3D().getViewMatrix());
            b.mat4("u_projection", ctx.getProjection());
        }).bind();
        GL11.glLineWidth(2);
        GL11.glDrawElements(GL11.GL_LINES, binding.indexCount, GL11.GL_UNSIGNED_INT, 0);
        GL11.glLineWidth(1);
    }

    public void dispose() {
        if (binding != null) binding.dispose();
    }
}
