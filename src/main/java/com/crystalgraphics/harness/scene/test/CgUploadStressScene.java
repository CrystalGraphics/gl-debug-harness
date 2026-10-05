package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTexture3D;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * render-async-uploads U0: what uploading costs the render thread and the GPU. Every {@code .every} frames, a burst of
 * {@code .textures} RGBA8 textures of {@code .size}² and an RGBA8 volume of {@code .volume}³ is made and filled at once,
 * alternately from worker threads (each upload deferred to the render thread) and from the render thread itself. The
 * textures are drawn as a grid from the frame after they exist, as a consumer would. Each burst prints its bytes, the
 * frames and milliseconds from asking to landed, and the render thread's own time for a render-thread burst.
 *
 * <pre>
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=upload-stress --device=vulkan" \
 *       -Dcrystalgraphics.harness.profile=600 -Dcrystalgraphics.trace.channels=crystalgraphics.gl,crystalgraphics.gl.detail,gpu
 * </pre>
 *
 * <ul>
 *   <li>Per frame, the profile has the zones {@code upload.texture} and {@code deferral.apply}, the counters
 *       {@code upload.textures}, {@code upload.texture-bytes}, {@code deferral.copies}, {@code tracked.texture-write-bytes}
 *       and {@code tracked.upload-breaks}, and the GPU zones {@code upload.deferred} and {@code upload-stress.render-thread}.</li>
 *   <li>Landed means applied to the device: a worker burst lands at the first frame executed after its last worker
 *       finished.</li>
 * </ul>
 */
public class CgUploadStressScene implements InteractiveSceneLifecycle {

    private static final int GL_RGBA = 0x1908, GL_UNSIGNED_BYTE = 0x1401;
    private static final int RENDER_THREAD_GPU = CgGpuTrace.name("upload-stress.render-thread");

    private final int textures = Integer.getInteger("crystalgraphics.harness.upload.textures", 16);
    private final int size = Integer.getInteger("crystalgraphics.harness.upload.size", 1024);
    private final int volumeSize = Integer.getInteger("crystalgraphics.harness.upload.volume", 128);
    private final int every = Integer.getInteger("crystalgraphics.harness.upload.every", 120);
    private final int bursts = Integer.getInteger("crystalgraphics.harness.upload.bursts", 6);

    private final ExecutorService workers = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "upload-stress worker");
        t.setDaemon(true);
        return t;
    });
    private final List<CgTexture2D> shown = new ArrayList<>(), made = new ArrayList<>();
    private final AtomicInteger outstanding = new AtomicInteger();
    private ByteBuffer[] pixels;
    private ByteBuffer volumePixels;
    private CgTexture3D volume;
    private CgQuadRenderer renderer;
    private CgMaterial material;
    private int frame, burst, burstFrame;
    private long burstStart;
    private boolean landing, wasTracingGpu, running = true;

    @Override
    public void init(HarnessContext ctx) {
        renderer = CgQuadRenderer.create();
        material = CgMaterial.load("assets/harness/shader/quad_renderer_test.shader");
        material.applyProperties(b -> b.sampler("_MainTex", 0, CgFallbackTextures.WHITE_1x1));
        pixels = new ByteBuffer[textures];
        for (int i = 0; i < textures; i++) pixels[i] = pattern(size * size, i);
        volumePixels = pattern(volumeSize * volumeSize * volumeSize, textures);
        wasTracingGpu = CgTrace.isEnabled(CgGpuTrace.GPU);
        CgTrace.setEnabled(CgGpuTrace.GPU, true);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        if (landing && outstanding.get() == 0) {   // every worker done: this frame's execution applies what they asked
            landed();
        }
        if (frame >= every && frame % every == 0 && !landing) {
            if (burst == bursts) {
                running = false;
                return;
            }
            start(burst++ % 2 == 0);
        }
        draw(ctx.getViewport().getWidth(), ctx.getViewport().getHeight());
        frame++;
    }

    /** Frees the last burst's textures and starts the next: from the workers, or here. */
    private void start(boolean fromWorkers) {
        for (CgTexture2D t : shown) t.delete();
        shown.clear();
        if (volume != null) volume.delete();
        burstStart = System.nanoTime();
        burstFrame = frame;
        if (fromWorkers) {
            outstanding.set(textures + 1);
            synchronized (made) {
                made.clear();
            }
            for (int i = 0; i < textures; i++) {
                ByteBuffer source = pixels[i];
                workers.execute(() -> {
                    CgTexture2D t = make(source);
                    synchronized (made) {
                        made.add(t);
                    }
                    outstanding.decrementAndGet();
                });
            }
            workers.execute(() -> {
                volume = makeVolume();
                outstanding.decrementAndGet();
            });
            landing = true;
            return;
        }
        boolean gpu = CgGpuTrace.isMeasuring();
        if (gpu) CgGpuTrace.begin(RENDER_THREAD_GPU);
        for (int i = 0; i < textures; i++) shown.add(make(pixels[i]));
        volume = makeVolume();
        if (gpu) CgGpuTrace.end();
        double ms = (System.nanoTime() - burstStart) / 1e6;
        System.out.printf("[upload-stress] burst %d from the render thread: %s, %.2f ms on the render thread%n",
                burst, bytes(), ms);
    }

    private void landed() {
        landing = false;
        synchronized (made) {
            shown.addAll(made);
            made.clear();
        }
        double ms = (System.nanoTime() - burstStart) / 1e6;
        System.out.printf("[upload-stress] burst %d from workers: %s, landed %d frames and %.2f ms after asking%n",
                burst, bytes(), frame - burstFrame, ms);
    }

    private CgTexture2D make(ByteBuffer source) {
        CgTexture2D t = CgTexture2D.createEmpty(size, size, CgTextureSpec.RGBA8_LINEAR);
        t.uploadRegion(0, 0, 0, size, size, source, GL_RGBA, GL_UNSIGNED_BYTE);
        return t;
    }

    private CgTexture3D makeVolume() {
        CgTexture3D v = CgTexture3D.createEmpty(volumeSize, volumeSize, volumeSize, CgTextureSpec.RGBA8_LINEAR);
        v.uploadRegion(0, 0, 0, 0, volumeSize, volumeSize, volumeSize, volumePixels, GL_RGBA, GL_UNSIGNED_BYTE);
        return v;
    }

    /** The shown textures as a grid, one flush each: each samples its own texture at unit 0. */
    private void draw(int w, int h) {
        CgPassConstants constants = CgImmediate.constants();
        constants.view.identity();
        constants.projection.identity().ortho(0, w, h, 0, -1, 1);
        constants.resolution(w, h).cameraFromView();
        renderer.begin();
        renderer.useMaterial(material);
        int columns = 8;
        float cell = Math.min(w, h) / (float) columns;
        for (int i = 0; i < shown.size(); i++) {
            renderer.bindTexture(0, shown.get(i));
            renderer.quad().at((i % columns) * cell, (i / columns) * cell).size(cell - 2, cell - 2).color(0xFFFFFFFF).submit();
            renderer.flush();
        }
        renderer.end();
    }

    private String bytes() {
        long total = 4L * size * size * textures + 4L * volumeSize * volumeSize * volumeSize;
        return String.format("%d textures %d^2 and a %d^3 volume, %.1f MB", textures, size, volumeSize, total / 1048576.0);
    }

    /** {@code texels} RGBA8 texels, different per {@code seed}. */
    private static ByteBuffer pattern(int texels, int seed) {
        ByteBuffer b = ByteBuffer.allocateDirect(4 * texels).order(ByteOrder.nativeOrder());
        for (int i = 0; i < texels; i++) b.putInt(i * 0x9E3779B1 + seed * 0x85EBCA6B | 0xFF000000);
        b.flip();
        return b;
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return true; }

    @Override
    public void dispose() {
        workers.shutdownNow();
        CgTrace.setEnabled(CgGpuTrace.GPU, wasTracingGpu);
        for (CgTexture2D t : shown) t.delete();
        if (volume != null) volume.delete();
        if (renderer != null) renderer.delete();
        if (material != null) material.delete();
    }
}
