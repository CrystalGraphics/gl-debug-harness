package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.graph.CgFrameGraph;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The HDR scene's gate, cost first (render-hdr-scene H0): a host-like RGBA8 target holding every code of every channel,
 * decoded into a linear scene (<i>scene in</i>) and encoded back into another RGBA8 target (the composite), with
 * nothing drawn between. Each pass is timed on the GPU at 1920x1080 and 3840x2160, with the scene RGBA16F and
 * R11G11B10F, over 60 frames after 10 of warm-up, beside a blit of the host's colour and depth (what a scene keeping
 * its own depth copies each frame); then the host is read back against the pattern. Prints its table and
 * {@code PASS} or {@code FAIL} and exits.
 *
 * <pre>
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=hdr-scene"
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=hdr-scene" -Pharness.downlevel=gl33
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=hdr-scene --device=vulkan"
 * </pre>
 *
 * <ul>
 *   <li>Then the engine's own scene in and composite: both world stages fired over the pattern with the HDR scene on
 *       and nothing drawn must leave it byte for byte, with {@code GL_FRAMEBUFFER_SRGB} off on GL.</li>
 *   <li>PASS needs the RGBA16F round trip byte-identical at every size; R11G11B10F's changed codes are reported, not
 *       failed (its blue channel has a 5-bit mantissa).</li>
 *   <li>The go/no-go bar is the pair under 0.5 ms at 1080p: the scene reports it, a person judges it per device.</li>
 *   <li>{@code -Dcrystalgraphics.harness.hdrScene.sizes=1920x1080,2560x1440} picks the sizes.</li>
 * </ul>
 */
public class CgHdrSceneTestScene implements InteractiveSceneLifecycle {

    private static final int WARMUP = 10, MEASURED = 60, DRAIN = 30;
    private static final double BAR_MS = 0.5;
    private static final CgTextureType[] SCENES = {CgTextureType.RGBA16F, CgTextureType.R11F_G11F_B10F};
    private static final CgFrameBufferFormat HOST = CgFrameBufferFormat.builder("hdr_scene_host")
            .color(0, CgTextureType.RGBA8).depth(CgTextureType.DEPTH24_STENCIL8).build();

    private record Config(String name, int width, int height, boolean exact, CgGraphTexture host, CgGraphTexture scene,
                          CgGraphTexture out, String in, String composite, CgFrameBuffer copy, String copyZone) {}

    private final List<Config> configs = new ArrayList<>();
    private final List<CgFrameBuffer> owned = new ArrayList<>();
    private final CgFrameBuilder builder = new CgFrameBuilder();
    private final CgFrameGraph graph = new CgFrameGraph();
    private final CgRecording recording = new CgRecording();
    private CgMaterial in, out;
    private int frame;
    private boolean wasTracingGpu, running = true;

    @Override
    public void init(HarnessContext ctx) {
        in = CgMaterial.newInstance("assets/harness/shader/hdr_scene_in.shader");
        out = CgMaterial.newInstance("assets/harness/shader/hdr_scene_out.shader");
        for (String size : System.getProperty("crystalgraphics.harness.hdrScene.sizes", "1920x1080,3840x2160").split(",")) {
            String[] wh = size.trim().split("x");
            int w = Integer.parseInt(wh[0]), h = Integer.parseInt(wh[1]);
            CgGraphTexture host = target("host " + size, w, h, HOST);
            upload(host.framebuffer(), pattern(w, h), w, h);
            CgGraphTexture composite = target("out " + size, w, h, HOST);
            CgFrameBuffer copy = target("copy " + size, w, h, HOST).framebuffer();
            for (CgTextureType type : SCENES) {
                CgFrameBufferFormat format = CgFrameBufferFormat.builder("hdr_scene_" + type.name().toLowerCase())
                        .color(0, type).build();
                String name = (type == CgTextureType.RGBA16F ? "rgba16f" : "r11g11b10f") + " " + w + "x" + h;
                configs.add(new Config(name, w, h, type == CgTextureType.RGBA16F, host,
                        target("scene " + name, w, h, format), composite, "in " + name, "out " + name,
                        copy, type == CgTextureType.RGBA16F ? "copy " + w + "x" + h : null));
            }
        }
        wasTracingGpu = CgTrace.isEnabled(CgGpuTrace.GPU);
        CgTrace.setEnabled(CgGpuTrace.GPU, true);
        CgGpuTrace.resetTotals();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        CgGpuTrace.collect();
        boolean measuring = frame >= WARMUP && frame < WARMUP + MEASURED;
        if (frame < WARMUP + MEASURED) {
            for (Config c : configs) run(c, measuring);
        } else if (frame >= WARMUP + MEASURED + DRAIN || everyQueryLanded()) {
            report();
            running = false;
        }
        frame++;
    }

    /** Scene in, then the composite, each a graph of its own inside its zone. The last config of a size leaves its out. */
    private void run(Config c, boolean measuring) {
        if (c.copyZone() != null) {
            // The host's colour and depth copied whole, as a scene keeping its own depth would take them each frame.
            int host = c.host().framebuffer().getId(), w = c.width(), h = c.height();
            execute(rec -> rec.callback("hdr-scene copy", null, () -> CgFrameBuffer.blitFrom(host, c.copy().getId(),
                    0, 0, w, h, 0, 0, w, h, CgGL.GL_COLOR_BUFFER_BIT | CgGL.GL_DEPTH_BUFFER_BIT, CgGL.GL_NEAREST)),
                    c.copyZone(), measuring);
            // The same callback copying nothing: what the copy zone holds besides the blit.
            execute(rec -> rec.callback("hdr-scene empty", null, () -> {}), c.copyZone() + " empty", measuring);
        }
        CgPassConstants constants = new CgPassConstants().resolution(c.width(), c.height()).time(frame / 60f);
        execute(rec -> fullscreen(rec, c.scene(), in, "_Source", c.host(), constants), c.in(), measuring);
        execute(rec -> fullscreen(rec, c.out(), out, "_Scene", c.scene(), constants), c.composite(), measuring);
    }

    private interface Record { void into(CgRecording rec); }

    private void execute(Record record, String zone, boolean measuring) {
        recording.reset();
        record.into(recording);
        CgFrame built = builder.build(graph.add(recording.seal()));
        graph.clear();
        if (measuring) CgGpuTrace.begin(zone);
        CgExecutor.execute(built, true);
        if (measuring) CgGpuTrace.end();
        builder.recycle(built);
    }

    private static void fullscreen(CgRecording rec, CgGraphTexture target, CgMaterial material, String sampler,
                                   CgGraphTexture source, CgPassConstants constants) {
        CgRasterPass pass = rec.raster(target, CgLoad.load(), constants, null, CgOrder.LOOKBACK);
        material.applyProperties(b -> b.sampler(sampler, 0, source));
        CgChunkBuilder c = rec.chunks().begin();
        c.draw(material.pipeline(CgInstanceKind.OBJECT), material.captureBindings(rec.bindings()), CgMesh.quads(1));
        c.instance();
        pass.add(c.end());
        pass.end();
    }

    private boolean everyQueryLanded() {
        Map<String, long[]> totals = CgGpuTrace.totals();
        for (Config c : configs) {
            for (String zone : new String[]{c.in(), c.composite(), c.copyZone()}) {
                if (zone == null) continue;
                long[] t = totals.get(zone);
                if (t == null || t[1] < MEASURED) return false;
            }
        }
        return true;
    }

    private void report() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? CgGL.glGetString(CgGL.GL_RENDERER) : device.name();
        System.out.println("[hdr-scene] " + on + ", GPU ms per frame over " + MEASURED + " frames");
        boolean timed = CgGpuTrace.support() != CgGpuTrace.Support.UNSUPPORTED;
        if (!timed) System.out.println("[hdr-scene]   this context has no timer queries: no cost measured");
        Map<String, long[]> totals = CgGpuTrace.totals();
        String failure = null;
        for (Config c : configs) {
            double a = mean(totals.get(c.in())), b = mean(totals.get(c.composite()));
            // Each out was last written by the R11G11B10F config of its size; redo this config's pair to read it.
            run(c, false);
            int changed = changed(c);
            String bar = c.height() == 1080 && timed ? (a + b < BAR_MS ? "  under " : "  OVER ") + BAR_MS : "";
            String copy = c.copyZone() == null ? "" : String.format("   colour+depth copy %7.3f (empty callback %7.3f)",
                    mean(totals.get(c.copyZone())), mean(totals.get(c.copyZone() + " empty")));
            System.out.printf("[hdr-scene]   %-22s in %7.3f   out %7.3f   pair %7.3f%s   round trip: %s%s%n", c.name(), a, b,
                    a + b, bar, changed == 0 ? "exact" : changed + " channel values changed", copy);
            if (c.exact() && changed != 0 && failure == null) {
                failure = c.name() + " changed " + changed + " channel values of the host";
            }
        }
        String engine = engineRoundTrip(device == null);
        System.out.println("[hdr-scene]   engine scene in and composite, both world stages fired: "
                + (engine == null ? "exact" : engine));
        if (engine != null && failure == null) failure = "the engine's round trip: " + engine;
        int validation = PlatformServiceHarness.validationErrors();
        if (GlErrorChecker.checkAndLog("hdr-scene")) {
            System.out.println("[hdr-scene] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[hdr-scene] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (failure != null) {
            System.out.println("[hdr-scene] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[hdr-scene] PASS on " + on + ": every code of every channel comes back through an RGBA16F scene unchanged");
        }
    }

    private static double mean(long[] t) {
        return t == null || t[1] == 0 ? Double.NaN : t[0] / 1e6 / t[1];
    }

    /**
     * The engine's own scene in and composite, as a host fires them: the pattern in a host target, both world stages
     * fired over it with the HDR scene on and nothing drawn. Null when every code comes back; else what went wrong. On
     * GL, the host's {@code GL_FRAMEBUFFER_SRGB} must be off too, or the scene's encode would be applied twice.
     */
    private String engineRoundTrip(boolean gl) {
        int w = 640, h = 480;
        CgFrameBuffer host = CgFrameBuffer.createOwned("hdr-scene engine host", w, h, HOST);
        owned.add(host);
        upload(host, pattern(w, h), w, h);
        CgWorldRenderer world = CgWorldRenderer.get();
        world.install();
        CgPostStack.get().install();
        boolean was = world.hdrScene();
        world.hdrScene(true);
        // A host draws its world with its target bound at its own size: the stages take both from GL.
        try (CgGlScope ignored = CgGlState.save(CgGlSlot.FBO, CgGlSlot.VIEWPORT)) {
            host.bind();
            CgGL.glViewport(0, 0, w, h);
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), (float) w / h, 0.05f, 100f);
            for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
                stage.host().set(0f, w, h, host.getId()).view().set(0, 0, 0, new Matrix4f(), projection);
                stage.fire();
            }
        } finally {
            world.hdrScene(was);
        }
        if (gl && CgGL.glGetBoolean(GL_FRAMEBUFFER_SRGB)) return "GL_FRAMEBUFFER_SRGB is on";
        int changed = changed(host.getColorTexture(0).getId(), w, h, true);
        return changed == 0 ? null : changed + " channel values changed";
    }

    private static final int GL_FRAMEBUFFER_SRGB = 0x8DB9;

    /** Channel values of {@code c}'s out unlike the pattern: RGB only where the scene has no alpha. */
    private static int changed(Config c) {
        return changed(c.out().framebuffer().getColorTexture(0).getId(), c.width(), c.height(), c.exact());
    }

    /** Channel values of {@code texture} unlike the pattern; without {@code alpha}, RGB only. */
    private static int changed(int texture, int w, int h, boolean alpha) {
        ByteBuffer made = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, texture);
        CgGL.glGetTexImage(CgGL.GL_TEXTURE_2D, 0, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, made);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, 0);
        ByteBuffer want = pattern(w, h);
        int changed = 0;
        for (int i = 0; i < w * h * 4; i++) {
            if (!alpha && i % 4 == 3) continue;
            if (made.get(i) != want.get(i)) changed++;
        }
        return changed;
    }

    /** Every code of every channel at once from 256 pixels a row and a column: r by x, g by y, b and a mixed. */
    private static ByteBuffer pattern(int w, int h) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels.put((byte) x).put((byte) y).put((byte) (x + 3 * y)).put((byte) (7 * x + y));
            }
        }
        return pixels.flip();
    }

    private static void upload(CgFrameBuffer fb, ByteBuffer pixels, int w, int h) {
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, fb.getColorTexture(0).getId());
        CgGL.glTexSubImage2D(CgGL.GL_TEXTURE_2D, 0, 0, 0, w, h, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, pixels);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, 0);
    }

    private CgGraphTexture target(String name, int w, int h, CgFrameBufferFormat format) {
        CgFrameBuffer fb = CgFrameBuffer.createOwned("hdr-scene " + name, w, h, format);
        owned.add(fb);
        return CgGraphTexture.imported(name, fb);
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return true; }

    @Override
    public void dispose() {
        CgTrace.setEnabled(CgGpuTrace.GPU, wasTracingGpu);
        for (CgFrameBuffer fb : owned) fb.delete();
    }
}
