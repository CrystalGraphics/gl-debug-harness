package io.github.somehussar.crystalgraphics.harness.util;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * A byte view of a typed direct buffer, for {@code CgGL}'s uploads, which take bytes.
 *
 * <pre>{@code
 * FloatBuffer verts = BufferUtils.createFloatBuffer(data.length);
 * verts.put(data).flip();
 * CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, HarnessBuffers.bytes(verts), CgGL.GL_STATIC_DRAW);
 * }</pre>
 *
 * <p>No copy: the view spans the buffer's position to its limit, so flip before viewing. The buffer must
 * be direct.</p>
 */
public final class HarnessBuffers {

    private HarnessBuffers() {}

    public static ByteBuffer bytes(FloatBuffer buffer) {
        return MemoryUtil.memByteBuffer(buffer);
    }

    public static ByteBuffer bytes(IntBuffer buffer) {
        return MemoryUtil.memByteBuffer(buffer);
    }
}
