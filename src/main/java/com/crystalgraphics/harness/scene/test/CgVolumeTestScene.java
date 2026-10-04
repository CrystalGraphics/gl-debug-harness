package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.cpu.CgCpuBuffer;
import com.crystalgraphics.compute.cpu.CgCpuImage;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.render.graph.CgTextureDesc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * The gate for volumes in the frame graph (gpu-compute C11, E3). Each frame a 12x10x9 R16F volume is filled by a
 * {@code 3d} image kernel, a second, R32F, is written from the first's neighbours across slices, and the first is
 * sampled through a {@code sampler3D} property, each texel fetched and halfway between slices filtered. A material
 * lays the second out flat through its own {@code sampler3D}. A third, 7x5x6, kept across frames, is written whole in
 * frame 1 and a box of it at a new z each frame after. Everything is read back and checked against Java's answer for
 * the frame that recorded it: the buffers, the first volume whole, a box of the second, the flat picture and the kept
 * volume whole.
 *
 * <p>Runs as the tier's form: compute, lowered as a draw per slice, or the kernels' Java bodies. Prints
 * {@code [volumes] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgVolumeTestScene implements InteractiveSceneLifecycle {

    private static final int W = 12, H = 10, D = 9, N = W * H * D, FRAMES = 8, DRAIN = 30;
    private static final int PW = 7, PH = 5, PD = 6, BOX_W = 3, BOX_H = 2, BOX_D = 2;
    private static final int[] SPREAD_BOX = {1, 2, 3, 5, 4, 4};
    private static final CgFrameBufferFormat R16F = CgFrameBufferFormat.builder("volumes-r16f")
            .color(0, CgTextureType.R16F).build();
    private static final CgFrameBufferFormat R32F = CgFrameBufferFormat.builder("volumes-r32f")
            .color(0, CgTextureType.R32F).build();
    private static final CgBufferDesc FLOATS = CgBufferDesc.elements(N, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY);

    private final CgGraphTexture field = CgGraphTexture.transientTexture("volumes.field", CgTextureDesc.volume(W, H, D, R16F));
    private final CgGraphTexture spread = CgGraphTexture.transientTexture("volumes.spread", CgTextureDesc.volume(W, H, D, R32F));
    private final CgGraphTexture flat = CgGraphTexture.transientTexture("volumes.flat", new CgTextureDesc(W, H * D, R32F));
    private final CgGraphTexture kept = CgGraphTexture.requested("volumes.kept", CgTextureDesc.volume(PW, PH, PD, R32F));
    private final CgGraphBuffer texels = CgGraphBuffer.transientBuffer("volumes.texels", FLOATS);
    private final CgGraphBuffer between = CgGraphBuffer.transientBuffer("volumes.between", FLOATS);
    private final float[] keptModel = new float[PW * PH * PD];
    private final List<CgRequest> requests = new ArrayList<>();
    private final int[] landed = new int[6];
    private CgCompute kernels;
    private CgMaterial flatten;
    private int frame;
    private String failure;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        kernels = CgCompute.load("harness:shaders/volumes.compute");
        giveBodies();
        flatten = CgMaterial.newInstance("assets/harness/shader/volumes_test.shader");
        flatten.applyProperties(b -> b.set1f("_Rows", H));
    }

    /** The kernels' Java bodies: what the CPU tier runs. */
    private void giveBodies() {
        kernels.kernel("Fill").cpu(d -> {
            CgCpuImage out = d.image("FIELD");
            for (int e = d.first(); e < d.end(); e++) out.store(d.x(e), d.y(e), d.z(e), code(d.x(e), d.y(e), d.z(e)), 0f, 0f, 0f);
        });
        kernels.kernel("Spread").cpu(d -> {
            CgCpuImage source = d.image("SOURCE"), out = d.image("SPREAD");
            for (int e = d.first(); e < d.end(); e++) {
                int x = d.x(e), y = d.y(e), z = d.z(e);
                float sum = source.loadFloat(x, y, z, 0)
                        + source.loadFloat(Math.max(x - 1, 0), y, z, 0) + source.loadFloat(Math.min(x + 1, W - 1), y, z, 0)
                        + source.loadFloat(x, Math.max(y - 1, 0), z, 0) + source.loadFloat(x, Math.min(y + 1, H - 1), z, 0)
                        + 2f * source.loadFloat(x, y, Math.max(z - 1, 0), 0) + 4f * source.loadFloat(x, y, Math.min(z + 1, D - 1), 0);
                out.store(x, y, z, sum, 0f, 0f, 0f);
            }
        });
        kernels.kernel("Sample").cpu(d -> {
            CgCpuImage field = d.texture("_Field", 0);
            CgCpuBuffer fetched = d.buffer("TEXELS"), halfway = d.buffer("BETWEEN");
            for (int e = d.first(); e < d.end(); e++) {
                int x = e % W, y = (e / W) % H, z = e / (W * H);
                float here = field.loadFloat(x, y, z, 0);
                fetched.setFloat(e, here);
                halfway.setFloat(e, (here + field.loadFloat(x, y, Math.min(z + 1, D - 1), 0)) * 0.5f);
            }
        });
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame <= FRAMES) {
            record(frame);
            return;
        }
        if (frame == FRAMES + 1) {
            CgRecording release = new CgRecording();
            release.release(kept);
            CgImmediate.execute(release);
        }
        boolean answered = true;
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (answered || frame > FRAMES + DRAIN) {
            report();
            running = false;
        }
    }

    private void record(int n) {
        CgRecording rec = new CgRecording();
        CgComputePass pass = rec.compute("volumes");
        pass.dispatch(kernels.kernel("Fill"), W, H, D).image("FIELD", field);
        pass.dispatch(kernels.kernel("Spread"), W, H, D).image("SOURCE", field).image("SPREAD", spread);
        pass.dispatch(kernels.kernel("Sample"), N).texture("_Field", field).set("_Size", W, H, D, 0f)
                .bind("TEXELS", texels).bind("BETWEEN", between);
        pass.end();

        CgPassConstants constants = new CgPassConstants().resolution(W, H * D);
        CgRasterPass draw = rec.raster(flat, CgLoad.clear(0f, 0f, 0f, 0f), constants, null, CgOrder.LOOKBACK);
        int bindings = rec.bindings().withTexture(flatten.captureBindings(rec.bindings()), 0, spread);
        CgChunkBuilder chunk = rec.chunks().begin();
        chunk.draw(flatten.pipeline(CgInstanceKind.OBJECT), bindings, CgMesh.quads(1));
        chunk.instance();
        draw.add(chunk.end());
        draw.end();

        requests.add(rec.readback(texels, 0, N * 4L, data -> {
            for (int e = 0; e < N; e++) {
                expect(data.getFloat(e * 4), code(e % W, (e / W) % H, e / (W * H)), "fetched texel " + e, n);
            }
            landed[0]++;
        }));
        requests.add(rec.readback(between, 0, N * 4L, data -> {
            for (int e = 0; e < N; e++) {
                int x = e % W, y = (e / W) % H, z = e / (W * H);
                float a = code(x, y, z), b = code(x, y, Math.min(z + 1, D - 1)), got = data.getFloat(e * 4);
                // filtering weighs by a fixed-point fraction: a 256th of the step at most
                if (Math.abs(got - (a + b) * 0.5f) > Math.abs(a - b) / 256f + 1e-3f) {
                    fail("filtered texel " + e + " is " + got + ", not " + (a + b) * 0.5f, n);
                }
            }
            landed[1]++;
        }));
        requests.add(rec.readback(field, 0, 0, 0, 0, W, H, D, data -> {
            if (data.remaining() != N * 2) fail("the R16F volume read " + data.remaining() + " bytes, not " + N * 2, n);
            for (int e = 0; e < N && e * 2 + 1 < data.remaining(); e++) {
                expect(Float.float16ToFloat(data.getShort(e * 2)), code(e % W, (e / W) % H, e / (W * H)), "volume texel " + e, n);
            }
            landed[2]++;
        }));
        int[] s = SPREAD_BOX;
        requests.add(rec.readback(spread, 0, s[0], s[1], s[2], s[3], s[4], s[5], data -> {
            for (int i = 0; i < s[3] * s[4] * s[5]; i++) {
                int x = s[0] + i % s[3], y = s[1] + (i / s[3]) % s[4], z = s[2] + i / (s[3] * s[4]);
                expect(data.getFloat(i * 4), spread(x, y, z), "spread box texel " + i, n);
            }
            landed[3]++;
        }));
        requests.add(rec.readback(flat, 0, 0, 0, W, H * D, data -> {
            for (int i = 0; i < W * H * D; i++) {
                int x = i % W, row = i / W;
                expect(data.getFloat(i * 4), spread(x, row % H, row / H), "flattened pixel " + i, n);
            }
            landed[4]++;
        }));
        patchKept(rec, n);
        CgImmediate.execute(rec);
    }

    /** Frame 1 writes the kept volume whole; every frame then writes a box at a new z, and reads it whole. */
    private void patchKept(CgRecording rec, int n) {
        if (n == 1) {
            for (int i = 0; i < keptModel.length; i++) keptModel[i] = i % PW + 10 * ((i / PW) % PH) + 100 * (i / (PW * PH));
            requests.add(rec.update(kept, 0, 0, 0, 0, PW, PH, PD, floats(keptModel)));
        }
        int bz = n % (PD - BOX_D + 1);
        float[] box = new float[BOX_W * BOX_H * BOX_D];
        for (int i = 0; i < box.length; i++) {
            box[i] = n * 1000 + i;
            keptModel[2 + i % BOX_W + PW * (1 + (i / BOX_W) % BOX_H + PH * (bz + i / (BOX_W * BOX_H)))] = box[i];
        }
        requests.add(rec.update(kept, 0, 2, 1, bz, BOX_W, BOX_H, BOX_D, floats(box)));
        float[] want = keptModel.clone();
        requests.add(rec.readback(kept, 0, 0, 0, 0, PW, PH, PD, data -> {
            for (int i = 0; i < want.length; i++) expect(data.getFloat(i * 4), want[i], "kept volume texel " + i, n);
            landed[5]++;
        }));
    }

    private static float code(int x, int y, int z) {
        return (x * 7 + y * 13 + z * 29) % 1021;
    }

    private static float spread(int x, int y, int z) {
        return code(x, y, z) + code(Math.max(x - 1, 0), y, z) + code(Math.min(x + 1, W - 1), y, z)
                + code(x, Math.max(y - 1, 0), z) + code(x, Math.min(y + 1, H - 1), z)
                + 2f * code(x, y, Math.max(z - 1, 0)) + 4f * code(x, y, Math.min(z + 1, D - 1));
    }

    private static ByteBuffer floats(float[] values) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
        for (float v : values) bytes.putFloat(v);
        return bytes.flip();
    }

    private void expect(float got, float want, String what, int n) {
        if (got != want) fail(what + " is " + got + ", not " + want, n);
    }

    private void fail(String what, int n) {
        if (failure == null) failure = "frame " + n + "'s " + what;
    }

    private void report() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = (device == null ? "gl" : device.name()) + " at " + CgCapabilities.detect().computeTier();
        int validation = PlatformServiceHarness.validationErrors();
        int done = 0;
        String unanswered = null;
        for (CgRequest r : requests) {
            if (r.done()) done++;
            else if (unanswered == null) unanswered = r + (r.failure() != null ? ": " + r.failure() : "");
        }
        if (GlErrorChecker.checkAndLog("volumes")) {
            System.out.println("[volumes] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[volumes] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (unanswered != null) {
            System.out.println("[volumes] FAIL on " + on + ": " + done + " of " + requests.size() + " answered; " + unanswered);
        } else if (on.startsWith("recording")) {
            System.out.println("[volumes] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (failure != null) {
            System.out.println("[volumes] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[volumes] PASS on " + on + ": " + FRAMES + " frames of a filled, spread and sampled "
                    + W + "x" + H + "x" + D + " volume (" + landed[0] + " fetched, " + landed[1] + " filtered, " + landed[2]
                    + " whole, " + landed[3] + " box and " + landed[4] + " flattened readbacks), and " + landed[5]
                    + " readbacks of a kept volume updated by boxes, every texel as worked out");
        }
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
