package com.crystalgraphics.harness.runtime;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL32C;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A second GL context sharing the window's, current on a thread of its own: textures uploaded there are drawn by the
 * window's context once a fence says the GPU has them (render-async-uploads U5). Host layer, so raw GL; a scene hands
 * it bytes and gets texture names and a fence back.
 *
 * <pre>{@code
 * SharedContextUploader up = SharedContextUploader.open();   // render thread, window's context current
 * up.execute(() -> {
 *     int name = up.texture2D(pixels, 1024, 1024);          // these on the uploader's thread
 *     fence = up.fence();                                   // flushed, so the window's context sees it signal
 * });
 * // render thread, each frame: CgGL.glClientWaitSync(fence, 0, 0) until signalled, then CgTexture2D.wrap(name, ...)
 * up.close();                                               // render thread
 * }</pre>
 *
 * <ul>
 *   <li>GL only: on a Vulkan device's window there is no context to share.</li>
 *   <li>Its names are the window's too: delete them there, through {@code CgGL}.</li>
 * </ul>
 */
public final class SharedContextUploader implements AutoCloseable {

    private final long window;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "harness shared-context uploads");
        t.setDaemon(true);
        return t;
    });

    private SharedContextUploader(long window) {
        this.window = window;
        thread.execute(() -> {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
        });
    }

    /** A hidden 1x1 window whose context shares the harness window's, at the same version. Render thread. */
    public static SharedContextUploader open() {
        long main = HarnessWindow.handle();
        if (main == 0L || GLFW.glfwGetCurrentContext() != main) {
            throw new IllegalStateException("a shared context needs the window's GL context current on this thread");
        }
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, GLFW.glfwGetWindowAttrib(main, GLFW.GLFW_CONTEXT_VERSION_MAJOR));
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, GLFW.glfwGetWindowAttrib(main, GLFW.GLFW_CONTEXT_VERSION_MINOR));
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        long window = GLFW.glfwCreateWindow(1, 1, "harness uploads", 0L, main);
        if (window == 0L) throw new IllegalStateException("GLFW could not create a context sharing the window's");
        return new SharedContextUploader(window);
    }

    /** Runs {@code work} on the uploader's thread, its context current. */
    public void execute(Runnable work) {
        thread.execute(work);
    }

    /** An RGBA8 texture of {@code width} x {@code height} filled from {@code rgba}, linear, one level. Uploader's thread. */
    public int texture2D(ByteBuffer rgba, int width, int height) {
        int t = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, t);
        parameters(GL11C.GL_TEXTURE_2D);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, width, height, 0, GL11C.GL_RGBA,
                GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, width, height, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, rgba);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        return t;
    }

    /** An RGBA8 volume of {@code size}³ filled from {@code rgba}, linear, one level. Uploader's thread. */
    public int texture3D(ByteBuffer rgba, int size) {
        int t = GL11C.glGenTextures();
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, t);
        parameters(GL12C.GL_TEXTURE_3D);
        GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL11C.GL_RGBA8, size, size, size, 0, GL11C.GL_RGBA,
                GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, 0, 0, 0, size, size, size, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE,
                rgba);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, 0);
        return t;
    }

    /** A fence after everything issued here, flushed so another context can wait on it. Uploader's thread. */
    public long fence() {
        long fence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL11C.glFlush();
        return fence;
    }

    /** Finishes what was queued, releases the context and destroys its window. Render thread. */
    @Override
    public void close() {
        thread.execute(() -> GLFW.glfwMakeContextCurrent(0L));
        thread.shutdown();
        try {
            thread.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        GLFW.glfwDestroyWindow(window);
    }

    private static void parameters(int target) {
        GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(target, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(target, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
    }
}
