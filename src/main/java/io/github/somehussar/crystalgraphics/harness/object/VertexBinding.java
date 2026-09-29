package io.github.somehussar.crystalgraphics.harness.object;

import com.crystalgraphics.platform.gl.CgGL;


public class VertexBinding {
    public int vaoId, vboId, eboId, indexCount;

    public VertexBinding(int vaoId, int vboId, int eboId, int indexCount) {
        this.vaoId = vaoId;
        this.vboId = vboId;
        this.eboId = eboId;
        this.indexCount = indexCount;
    }

    public void dispose() {
        if (vaoId != -1) CgGL.glDeleteVertexArrays(vaoId);
        if (vboId != -1) CgGL.glDeleteBuffers(vboId);
        if (eboId != -1) CgGL.glDeleteBuffers(eboId);
    }
}
