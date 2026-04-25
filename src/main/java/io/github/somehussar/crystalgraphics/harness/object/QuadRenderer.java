package io.github.somehussar.crystalgraphics.harness.object;

import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public class QuadRenderer {
    public VertexBinding binding;
    
    public QuadRenderer init(HarnessContext ctx) {
        float[] quad = {
                -1, -1, 0, 0,
                1, -1, 1, 0,
                1, 1, 1, 1,
                -1, 1, 0, 1
        };

        int[] indices = {0, 1, 2, 2, 3, 0};
        int indexCount = indices.length;

        FloatBuffer buff = BufferUtils.createFloatBuffer(quad.length);
        buff.put(quad).flip();

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

        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8);

        GL20.glEnableVertexAttribArray(0);
        GL20.glEnableVertexAttribArray(1);

        binding = new VertexBinding(vaoId, vboId, eboId, indexCount);
        return this;
    }

    public void render(HarnessContext ctx) {
        GL30.glBindVertexArray(binding.vaoId);
        GL11.glDrawElements(GL11.GL_TRIANGLES, binding.indexCount, GL11.GL_UNSIGNED_INT, 0);
    }
}
