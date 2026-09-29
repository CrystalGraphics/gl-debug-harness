package com.crystalgraphics.harness.object;

import com.crystalgraphics.harness.util.HarnessBuffers;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.harness.config.HarnessContext;
import org.lwjgl.BufferUtils;

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

        int vaoId = CgGL.glGenVertexArrays();
        CgGL.glBindVertexArray(vaoId);

        int vboId = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vboId);
        CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, HarnessBuffers.bytes(buff), CgGL.GL_STATIC_DRAW);

        int eboId = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, eboId);
        CgGL.glBufferData(CgGL.GL_ELEMENT_ARRAY_BUFFER, HarnessBuffers.bytes(iBuff), CgGL.GL_STATIC_DRAW);

        CgGL.glVertexAttribPointer(0, 2, CgGL.GL_FLOAT, false, 16, 0);
        CgGL.glVertexAttribPointer(1, 2, CgGL.GL_FLOAT, false, 16, 8);

        CgGL.glEnableVertexAttribArray(0);
        CgGL.glEnableVertexAttribArray(1);

        binding = new VertexBinding(vaoId, vboId, eboId, indexCount);
        return this;
    }

    public void render(HarnessContext ctx) {
        CgGL.glBindVertexArray(binding.vaoId);
        CgGL.glDrawElements(CgGL.GL_TRIANGLES, binding.indexCount, CgGL.GL_UNSIGNED_INT, 0);
    }
}
