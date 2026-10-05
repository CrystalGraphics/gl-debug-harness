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
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.render.graph.CgTextureDesc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The gate for raster passes into array layers. Each frame a 24x16 RGBA16F array of five layers, transient, has its
 * first 2 to 5 layers (a different count each frame) each cleared to its own blue and drawn into twice, adding, with a
 * pattern of its own; a material then sums those layers through a {@code sampler2DArray} into a second target. Every
 * layer drawn and the sum are read back and checked against Java's answer, rounded to half floats as stored.
 *
 * <p>A clear or draw that reached the wrong layer changes another's texels; a layer the sum should not read adds
 * itself. The array's storage must be the same object every frame (the pool keeps a fixed description), and a pass
 * sampling the array it draws into must be refused. Prints {@code [raster-layers] PASS} or {@code FAIL}; on Vulkan a
 * validation error fails it.</p>
 */
public class CgRasterLayersTestScene implements InteractiveSceneLifecycle {

    private static final int W = 24, H = 16, LAYERS = 5, FRAMES = 8, DRAIN = 30;
    private static final CgFrameBufferFormat HALF = CgFrameBufferFormat.builder("raster-layers")
            .color(0, CgTextureType.RGBA16F).build();

    private final CgGraphTexture fields = CgGraphTexture.transientTexture("raster-layers.fields",
            CgTextureDesc.array(W, H, LAYERS, HALF));
    private final CgGraphTexture sum = CgGraphTexture.transientTexture("raster-layers.sum", new CgTextureDesc(W, H, HALF));
    private final CgMaterial[] draw = new CgMaterial[LAYERS];
    private final CgMaterial[] summing = new CgMaterial[LAYERS + 1];
    private final List<CgRequest> requests = new ArrayList<>();
    private final Set<CgFrameBuffer> storage = new HashSet<>();
    private int frame, layersRead, sumsRead;
    private String failure;
    private boolean refused;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        for (int k = 0; k < LAYERS; k++) {
            int layer = k;
            draw[k] = CgMaterial.newInstance("assets/harness/shader/raster_layers_draw.shader");
            draw[k].applyProperties(b -> b.set1i("_Layer", layer));
        }
        for (int n = 1; n <= LAYERS; n++) {
            int count = n;
            summing[n] = CgMaterial.newInstance("assets/harness/shader/raster_layers_sum.shader");
            summing[n].applyProperties(b -> b.sampler("_Fields", 0, fields).set1i("_Count", count));
        }
        refused = refusesOwnArray();
    }

    /** Whether a pass drawing into the array and sampling it is refused when its chunk is added. */
    private boolean refusesOwnArray() {
        CgRecording rec = new CgRecording();
        CgRasterPass pass = rec.raster(fields, 0, 1, CgLoad.load(), new CgPassConstants().resolution(W, H), null,
                CgOrder.LOOKBACK);
        CgChunkBuilder chunk = rec.chunks().begin();
        chunk.draw(summing[1].pipeline(CgInstanceKind.OBJECT), summing[1].captureBindings(rec.bindings()), CgMesh.quads(1));
        chunk.instance();
        try {
            pass.add(chunk.end());
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame <= FRAMES) {
            record(frame, 2 + frame % (LAYERS - 1));
            return;
        }
        boolean answered = true;
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (answered || frame > FRAMES + DRAIN) {
            report();
            running = false;
        }
    }

    private void record(int n, int used) {
        CgRecording rec = new CgRecording();
        CgPassConstants constants = new CgPassConstants().resolution(W, H);
        for (int k = 0; k < used; k++) {
            CgRasterPass pass = rec.raster(fields, 0, k, CgLoad.clear(0f, 0f, 0.125f * k, 0f), constants, null, CgOrder.LOOKBACK);
            CgChunkBuilder chunk = rec.chunks().begin();
            for (int twice = 0; twice < 2; twice++) {
                chunk.draw(draw[k].pipeline(CgInstanceKind.OBJECT), draw[k].captureBindings(rec.bindings()), CgMesh.quads(1));
                chunk.instance();
            }
            pass.add(chunk.end());
            pass.end();
        }
        // The layers are read back before anything samples the array: on Mesa's llvmpipe a readback after the sum pass
        // may land while the last layer is still being drawn.
        for (int k = 0; k < used; k++) {
            int layer = k;
            requests.add(rec.readback(fields, 0, 0, 0, k, W, H, 1, data -> {
                check(data, layer, 1, "layer " + layer, n);
                layersRead++;
            }));
        }
        CgRasterPass add = rec.raster(sum, CgLoad.clear(0f, 0f, 0f, 0f), constants, null, CgOrder.LOOKBACK);
        CgChunkBuilder chunk = rec.chunks().begin();
        chunk.draw(summing[used].pipeline(CgInstanceKind.OBJECT), summing[used].captureBindings(rec.bindings()), CgMesh.quads(1));
        chunk.instance();
        add.add(chunk.end());
        add.end();
        requests.add(rec.readback(sum, 0, 0, 0, W, H, data -> {
            check(data, 0, used, "the sum of " + used + " layers", n);
            sumsRead++;
        }));
        requests.add(rec.callback("raster-layers.storage", null, () -> storage.add(fields.framebuffer()), fields));
        CgImmediate.execute(rec);
    }

    /** Checks {@code data} against layers {@code first} to {@code first + count - 1} summed. */
    private void check(ByteBuffer data, int first, int count, String what, int n) {
        if (data.remaining() != W * H * 8) {
            fail(what + " read " + data.remaining() + " bytes, not " + W * H * 8, n);
            return;
        }
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                for (int ch = 0; ch < 4; ch++) {
                    float want = 0f;
                    for (int k = first; k < first + count; k++) want += layer(k, x, y, ch);
                    want = half(want);
                    float got = Float.float16ToFloat(data.getShort(((y * W + x) * 4 + ch) * 2));
                    if (Math.abs(got - want) > 2e-3f * Math.max(1f, Math.abs(want))) {
                        int at = (y * W + x) * 4;
                        fail(what + " texel (" + x + ", " + y + ")." + ch + " is " + got + ", not " + want + "; whole texel "
                                + Float.float16ToFloat(data.getShort(at * 2)) + " " + Float.float16ToFloat(data.getShort(at * 2 + 2))
                                + " " + Float.float16ToFloat(data.getShort(at * 2 + 4)) + " " + Float.float16ToFloat(data.getShort(at * 2 + 6)), n);
                        return;
                    }
                }
            }
        }
    }

    /** Layer {@code k}'s texel as stored: its clear, then the pattern added twice, each step rounded to a half. */
    private static float layer(int k, int x, int y, int ch) {
        float clear = ch == 2 ? 0.125f * k : 0f;
        float add = switch (ch) {
            case 0 -> (x + 0.5f) / 128f * (k + 1);
            case 1 -> (y + 0.5f) / 128f;
            case 2 -> 0.0625f;
            default -> 0.125f;
        };
        return half(half(half(clear) + add) + add);
    }

    private static float half(float value) {
        return Float.float16ToFloat(Float.floatToFloat16(value));
    }

    private void fail(String what, int n) {
        if (failure == null) failure = "frame " + n + "'s " + what;
    }

    private void report() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        int done = 0;
        String unanswered = null;
        for (CgRequest r : requests) {
            if (r.done()) done++;
            else if (unanswered == null) unanswered = r + (r.failure() != null ? ": " + r.failure() : "");
        }
        if (GlErrorChecker.checkAndLog("raster-layers")) {
            System.out.println("[raster-layers] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[raster-layers] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (unanswered != null) {
            System.out.println("[raster-layers] FAIL on " + on + ": " + done + " of " + requests.size() + " answered; " + unanswered);
        } else if (!refused) {
            System.out.println("[raster-layers] FAIL on " + on + ": a pass sampling the array it draws into was not refused");
        } else if (storage.size() != 1) {
            System.out.println("[raster-layers] FAIL on " + on + ": the array took " + storage.size() + " storages over "
                    + FRAMES + " frames, not 1");
        } else if (on.startsWith("recording")) {
            System.out.println("[raster-layers] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (failure != null) {
            System.out.println("[raster-layers] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[raster-layers] PASS on " + on + ": " + FRAMES + " frames of 2 to " + LAYERS + " layers drawn "
                    + "into one array and summed through a sampler2DArray (" + layersRead + " layer and " + sumsRead
                    + " sum readbacks), every texel as worked out, one storage throughout");
        }
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
