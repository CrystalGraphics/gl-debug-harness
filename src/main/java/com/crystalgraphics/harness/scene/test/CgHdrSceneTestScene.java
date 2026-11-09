package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.harness.runtime.HarnessWindow;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.DeviceErrors;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.rhi.CgRHI;
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
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=hdr-scene --rhi=vulkan"
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
    private CgMaterial in, out, opaqueMaterial, overdrawMaterial;
    private int frame;
    private boolean wasTracingGpu, running = true;

    @Override
    public void init(HarnessContext ctx) {
        in = CgMaterial.newInstance("assets/harness/shader/hdr_scene_in.shader");
        out = CgMaterial.newInstance("assets/harness/shader/hdr_scene_out.shader");
        opaqueMaterial = CgMaterial.newInstance("assets/harness/shader/hdr_scene_opaque.shader");
        overdrawMaterial = CgMaterial.newInstance("assets/harness/shader/hdr_scene_overdraw.shader");
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
            CgFrameBuffer host = c.host().framebuffer();
            int copy = c.copy().getId();
            execute(rec -> rec.callback("hdr-scene copy", null, () -> host.copyColorAndDepthTo(copy)), c.copyZone(), measuring);
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
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
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
        if (DeviceErrors.checkAndLog("hdr-scene")) {
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
        // A host draws its world with its target bound at its own size: the stages take both from GL.
        try (CgFrameBuffer.Drawing ignored = host.drawInto()) {
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), (float) w / h, 0.05f, 100f);
            for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
                stage.host().set(0f, w, h, host.getId()).view().set(0, 0, 0, new Matrix4f(), projection);
                stage.fire();
            }
        }
        if (gl && HarnessWindow.framebufferSrgb()) return "GL_FRAMEBUFFER_SRGB is on";
        int changed = changed(host, true);
        if (changed != 0) return changed + " channel values changed";
        // Bloom off for the exact checks: the cube's excess blooms past it, which the last check asks for
        float bloom = CgPostStack.get().bloom().intensity();
        CgPostStack.get().bloom().intensity(0f);
        try {
            String opaque = opaqueRoundTrip(host, w, h, false, false);
            if (opaque == null) opaque = opaqueRoundTrip(host, w, h, true, false);
            if (opaque != null) return opaque;
        } finally {
            CgPostStack.get().bloom().intensity(bloom);
        }
        CgPostStack.get().bloom().intensity(1f);
        try {
            return opaqueRoundTrip(host, w, h, false, true);
        } finally {
            CgPostStack.get().bloom().intensity(bloom);
        }
    }

    /** The middle of the screen the opaque cube covers, padded: outside it every pixel must come back. */
    private static final int CUBE_HALF = 100;

    /**
     * The opaque firing's scene: an unlit opaque cube of linear (4, 0, 0) drawn into it over the pattern, encoded back
     * after the opaque stage, then with {@code overdraw} the host drawing between the stages (opaque blue over the left
     * quarter, alpha 0 everywhere else). Every pixel outside the cube and the blue must come back byte for byte, the
     * blue as blue, and the cube must keep its excess: rolled toward white by the composite, its green near 188 where a
     * value clamped at the opaque stage would leave 0. Null when all hold; else what failed.
     */
    private String opaqueRoundTrip(CgFrameBuffer host, int w, int h, boolean overdraw, boolean blooming) {
        String which = blooming ? "opaque scene, blooming" : overdraw ? "opaque scene under the host's overdraw" : "opaque scene";
        CgWorldRenderer world = CgWorldRenderer.get();
        upload(host, pattern(w, h), w, h);
        boolean wasSkipping = world.skipUnready();
        world.skipUnready(false);
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), (float) w / h, 0.05f, 100f);
        try (CgFrameBuffer.Drawing ignored = host.drawInto()) {
            host.clearDepth();
            world.draw(CgMeshShapes.cube(), opaqueMaterial).at(0, 0, -3).submit();
            fire(CgRenderStage.WORLD_OPAQUE, host, w, h, projection);
            if (overdraw) {
                overdrawMaterial.applyProperties(b -> b.set1f("_Split", w / 4f));
                CgPassConstants camera = new CgPassConstants().resolution(w, h).time(0f);
                try (CgImmediate draw = CgImmediate.begin(camera)) {
                    draw.chunks().draw(overdrawMaterial.pipeline(CgInstanceKind.OBJECT),
                            overdrawMaterial.captureBindings(draw.bindings()), CgMesh.quads(1));
                    draw.chunks().instance();
                }
            }
            fire(CgRenderStage.WORLD_TRANSPARENT, host, w, h, projection);
        } finally {
            world.skipUnready(wasSkipping);
        }
        ByteBuffer made = host.readColor(0, 0, w, h);
        ByteBuffer want = pattern(w, h);
        int changed = 0, blue = 0, worst = 0;
        int[] perChannel = new int[4], box = {w, h, -1, -1};
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int at = (y * w + x) * 4;
                if (Math.abs(x - w / 2) < CUBE_HALF && Math.abs(y - h / 2) < CUBE_HALF) continue;
                if (overdraw && x < w / 4) {
                    if ((made.get(at) & 0xFF) != 0 || (made.get(at + 1) & 0xFF) != 0 || (made.get(at + 2) & 0xFF) != 255) blue++;
                    continue;
                }
                for (int c = 0; c < 4; c++) {
                    if (made.get(at + c) == want.get(at + c)) continue;
                    changed++;
                    perChannel[c]++;
                    box[0] = Math.min(box[0], x);
                    box[1] = Math.min(box[1], y);
                    box[2] = Math.max(box[2], x);
                    box[3] = Math.max(box[3], y);
                    int d = Math.abs((made.get(at + c) & 0xFF) - (want.get(at + c) & 0xFF));
                    worst = Math.max(worst, d);
                }
            }
        }
        if (changed != 0) {
            System.out.printf("[hdr-scene]     changed by channel r %d g %d b %d a %d, within x %d..%d y %d..%d, at most %d codes%n",
                    perChannel[0], perChannel[1], perChannel[2], perChannel[3], box[0], box[2], box[1], box[3], worst);
        }
        int middle = (h / 2 * w + w / 2) * 4;
        int r = made.get(middle) & 0xFF, g = made.get(middle + 1) & 0xFF;
        System.out.printf("[hdr-scene]   %s: outside the cube %d channel values changed%s, its middle (%d, %d, %d)%n",
                which, changed, overdraw ? ", " + blue + " blue pixels wrong" : "", r, g, made.get(middle + 2) & 0xFF);
        // A clamp at the opaque stage leaves (255, 0, 0); the composite rolls the kept 4x red toward white
        if (r < 250 || g < 64) return which + ": the cube's middle is (" + r + ", " + g + "), not white-rolled red: its excess was lost";
        if (blooming) {
            return perChannel[0] > 0 && perChannel[0] > perChannel[1] ? null
                    : which + ": no red glow past the cube: its excess never reached bloom";
        }
        if (changed != 0) return which + ": " + changed + " channel values changed outside the cube";
        if (blue != 0) return which + ": " + blue + " pixels of the host's blue lost";
        return null;
    }

    private static void fire(CgRenderStage stage, CgFrameBuffer host, int w, int h, Matrix4f projection) {
        stage.host().set(0f, w, h, host.getId()).view().set(0, 0, 0, new Matrix4f(), projection);
        stage.fire();
    }

    /** Channel values of {@code c}'s out unlike the pattern: RGB only where the scene has no alpha. */
    private static int changed(Config c) {
        return changed(c.out().framebuffer(), c.exact());
    }

    /** Channel values of {@code target}'s colour unlike the pattern; without {@code alpha}, RGB only. */
    private static int changed(CgFrameBuffer target, boolean alpha) {
        int w = target.getWidth(), h = target.getHeight();
        ByteBuffer made = target.readColor(0, 0, w, h);
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
        ((CgTexture2D) fb.getColorTexture(0)).uploadRegion(0, 0, 0, w, h, pixels);
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
