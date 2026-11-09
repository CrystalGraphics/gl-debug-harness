package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTexture3D;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=upload-stress --rhi=vulkan" \
 *       -Dcrystalgraphics.harness.profile=600 -Dcrystalgraphics.trace.channels=crystalgraphics.gl,crystalgraphics.gl.detail,gpu
 * </pre>
 *
 * <ul>
 *   <li>Per frame, the profile has the zones {@code upload.texture}, {@code upload.texture-unpack} and
 *       {@code deferral.apply}, the workers' {@code upload.lease-copy}, the counters {@code upload.textures},
 *       {@code upload.texture-bytes}, {@code upload.lease-misses}, {@code tracked.texture-write-bytes} and
 *       {@code tracked.upload-breaks}, and the GPU zones {@code upload.deferred}, {@code upload-stress.render-thread} and
 *       {@code upload-stress.draw}, the last holding any wait for copies on another queue.</li>
 *   <li>Landed means applied to the device: a worker burst lands at the first frame executed after its last worker
 *       finished.</li>
 *   <li>On {@code vulkan}, each burst also prints how many of its copies ran on the transfer queue;
 *       {@code -Dcrystalgraphics.vulkan.transfer=false} keeps them all on the frame's queue, to compare.
 *       {@code .load=<n>} draws n full-window quads a frame first, which gives the frame's queue work for those copies
 *       to overlap: without it the GPU idles, and the frame's queue can only wait for them.</li>
 *   <li>{@code .rewrite=true} rewrites one drawn texture whole each frame from the render thread and reads every tenth
 *       back, printing PASS or FAIL. On {@code vulkan} such an upload goes into a new image on the transfer queue, and
 *       the run fails if none did.</li>
 * </ul>
 */
public class CgUploadStressScene implements InteractiveSceneLifecycle {

    private static final int RENDER_THREAD_GPU = CgGpuTrace.name("upload-stress.render-thread");
    /** The frame's drawing, and any wait on the frame's queue for copies elsewhere, which comes before its first pass. */
    private static final int DRAW_GPU = CgGpuTrace.name("upload-stress.draw");

    private final int textures = Integer.getInteger("crystalgraphics.harness.upload.textures", 16);
    private final int size = Integer.getInteger("crystalgraphics.harness.upload.size", 1024);
    private final int volumeSize = Integer.getInteger("crystalgraphics.harness.upload.volume", 128);
    private final int every = Integer.getInteger("crystalgraphics.harness.upload.every", 120);
    private final int bursts = Integer.getInteger("crystalgraphics.harness.upload.bursts", 6);
    /** Full-window quads drawn under the grid each frame, so the GPU has work a copy elsewhere can overlap. */
    private final int load = Integer.getInteger("crystalgraphics.harness.upload.load", 0);
    /** One drawn texture rewritten whole each frame: the path a whole upload into a texture in use takes. */
    private final boolean rewrite = Boolean.getBoolean("crystalgraphics.harness.upload.rewrite");

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
    private final CgCanvas canvas = new CgCanvas();
    private CgMaterial material;
    private int frame, burst, burstFrame, copiesAtStart;
    private long burstStart;
    private boolean landing, wasTracingGpu, running = true;
    /** Two patterns no burst uses, each rewrite the one its texture does not hold; what each texture last got. */
    private ByteBuffer[] rewritePixels;
    private final Map<CgTexture2D, ByteBuffer> rewritten = new IdentityHashMap<>();
    private int rewrites, renamed, checks, failed;

    @Override
    public void init(HarnessContext ctx) {
        material = CgMaterial.load("assets/harness/shader/quad_renderer_test.shader");
        material.applyProperties(b -> b.sampler("_MainTex", 0, CgFallbackTextures.WHITE_1x1));
        pixels = new ByteBuffer[textures];
        for (int i = 0; i < textures; i++) pixels[i] = pattern(size * size, i);
        volumePixels = pattern(volumeSize * volumeSize * volumeSize, textures);
        if (rewrite) {
            rewritePixels = new ByteBuffer[] {pattern(size * size, textures + 1), pattern(size * size, textures + 2)};
        }
        wasTracingGpu = CgTrace.isEnabled(CgGpuTrace.GPU);
        CgTrace.setEnabled(CgGpuTrace.GPU, true);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        // Every worker done: this frame's execution applies their work.
        boolean landsNow = landing && outstanding.get() == 0;
        if (landsNow) landed();
        if (frame >= every && frame % every == 0 && !landing) {
            if (burst == bursts) {
                running = false;
                return;
            }
            start(burst++ % 2 == 0);
        }
        if (rewrite && !shown.isEmpty()) rewriteOne();
        long start = System.nanoTime();
        boolean gpu = CgGpuTrace.isMeasuring();
        if (gpu) CgGpuTrace.begin(DRAW_GPU);
        draw(ctx.getViewport().getWidth(), ctx.getViewport().getHeight());
        if (gpu) CgGpuTrace.end();
        if (landsNow) {
            System.out.printf("[upload-stress] burst %d landing frame: %.2f ms drawing, its deferred work included; "
                    + "%d copies on the transfer queue%n", burst, (System.nanoTime() - start) / 1e6,
                    PlatformServiceHarness.transferCopies() - copiesAtStart);
        }
        frame++;
    }

    /**
     * Rewrites one drawn texture whole from the render thread, and reads every tenth back. The Vulkan device's rename
     * count says whether the upload went into a new image.
     */
    private void rewriteOne() {
        CgTexture2D t = shown.get(frame % shown.size());
        ByteBuffer source = rewritten.get(t) == rewritePixels[0] ? rewritePixels[1] : rewritePixels[0];
        rewritten.put(t, source);
        long before = PlatformServiceHarness.textureRenames();
        t.uploadRegion(0, 0, 0, size, size, source);
        renamed += (int) (PlatformServiceHarness.textureRenames() - before);
        if (++rewrites % 10 != 0) return;
        checks++;
        boolean same = CgFrameBuffer.readTexture(t, 0).equals(source.duplicate().rewind());
        if (!same) failed++;
        System.out.printf("[upload-stress] rewrite %d: %s%n", rewrites, same ? "PASS, read back as written" : "FAIL, read back differs");
    }

    /** Frees the last burst's textures and starts the next: from the workers, or here. */
    private void start(boolean fromWorkers) {
        for (CgTexture2D t : shown) t.delete();
        shown.clear();
        rewritten.clear();
        if (volume != null) volume.delete();
        volume = null;
        burstStart = System.nanoTime();
        burstFrame = frame;
        copiesAtStart = PlatformServiceHarness.transferCopies();
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
        System.out.printf("[upload-stress] burst %d from the render thread: %s, %.2f ms on the render thread; "
                + "%d copies on the transfer queue%n", burst, bytes(), ms, PlatformServiceHarness.transferCopies() - copiesAtStart);
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
        t.uploadRegion(0, 0, 0, size, size, source);
        return t;
    }

    private CgTexture3D makeVolume() {
        CgTexture3D v = CgTexture3D.createEmpty(volumeSize, volumeSize, volumeSize, CgTextureSpec.RGBA8_LINEAR);
        v.uploadRegion(0, 0, 0, 0, volumeSize, volumeSize, volumeSize, volumePixels);
        return v;
    }

    /** The shown textures as a grid, one flush each: each samples its own texture at unit 0. */
    private void draw(int w, int h) {
        CgPassConstants constants = CgImmediate.constants();
        constants.view.identity();
        constants.projection.identity().ortho(0, w, h, 0, -1, 1);
        constants.resolution(w, h).cameraFromView();
        if (load > 0) {
            for (int i = 0; i < load; i++) canvas.quad(material).texture(0, CgFallbackTextures.WHITE_1x1).at(0, 0).size(w, h).color(0x08FFFFFF).submit();
            canvas.flush();
        }
        int columns = 8;
        float cell = Math.min(w, h) / (float) columns;
        for (int i = 0; i < shown.size(); i++) {
            canvas.quad(material).texture(0, shown.get(i)).at((i % columns) * cell, (i / columns) * cell).size(cell - 2, cell - 2).color(0xFFFFFFFF).submit();
            canvas.flush();
        }
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
        if (rewrite) {
            // Where the transfer queue took the bursts, a rewrite of a texture the frame drew is renamed onto it.
            boolean renames = PlatformServiceHarness.transferCopies() == 0 || rewrites < 20 || renamed > 0;
            System.out.printf("[upload-stress] %d rewrites, %d into a new image; %d read back, %d differing: %s%n",
                    rewrites, renamed, checks, failed, failed > 0 ? "FAIL" : renames ? "PASS" : "FAIL, none renamed");
        }
        workers.shutdownNow();
        CgTrace.setEnabled(CgGpuTrace.GPU, wasTracingGpu);
        for (CgTexture2D t : shown) t.delete();
        if (volume != null) volume.delete();
        if (material != null) material.delete();
    }
}
