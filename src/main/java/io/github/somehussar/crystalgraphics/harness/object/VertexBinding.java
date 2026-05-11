package io.github.somehussar.crystalgraphics.harness.object;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

public class VertexBinding {
    public int vaoId, vboId, eboId, indexCount;

    public VertexBinding(int vaoId, int vboId, int eboId, int indexCount) {
        this.vaoId = vaoId;
        this.vboId = vboId;
        this.eboId = eboId;
        this.indexCount = indexCount;
    }

    public void dispose() {
        if (vaoId != -1) GL30.glDeleteVertexArrays(vaoId);
        if (vboId != -1) GL15.glDeleteBuffers(vboId);
        if (eboId != -1) GL15.glDeleteBuffers(eboId);
    }
}
