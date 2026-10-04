package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.source.CgElementField;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferInspector;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.render.graph.CgTextureDesc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The gate for {@code CgRecording.readback} (gpu-compute C8): each frame, words {@code CgGpuOps.iota} wrote into a
 * transient buffer, a region of an RGBA32F target, a whole R8 target whose rows are 3 bytes, and level 2 of an RGBA8
 * chain are read back, each written with values of that frame. Every delivery is checked against the frame that
 * recorded it, and every request must be done once the readbacks drain. The words are read through
 * {@code CgBufferInspector} too: armed after a frame, served after the next frame's iota, decoded as its {@code uint}s.
 *
 * <p>The iota runs as the tier's form, so a lowered tier's held output must land before the copy. Prints
 * {@code [readback] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgReadbackTestScene implements InteractiveSceneLifecycle {

    private static final int FRAMES = 40, DRAIN = 30, WORDS = 4096, FIRST_WORD = 100, READ_WORDS = 16;
    private static final CgFrameBufferFormat F32 = CgFrameBufferFormat.builder("readback-f32")
            .color(0, CgTextureType.RGBA32F).build();
    private static final CgFrameBufferFormat R8 = CgFrameBufferFormat.builder("readback-r8")
            .color(0, CgTextureType.R8).build();
    private static final CgFrameBufferFormat RGBA8 = CgFrameBufferFormat.builder("readback-rgba8")
            .color(0, CgTextureType.RGBA8).build();

    private final CgGraphBuffer words = CgGraphBuffer.transientBuffer("readback.words",
            CgBufferDesc.elements(WORDS, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
    private final CgGraphTexture floats = CgGraphTexture.transientTexture("readback.f32", new CgTextureDesc(8, 4, F32));
    private final CgGraphTexture bytes = CgGraphTexture.transientTexture("readback.r8", new CgTextureDesc(3, 5, R8));
    private final CgGraphTexture chain = CgGraphTexture.transientTexture("readback.chain",
            new CgTextureDesc(16, 8, RGBA8).withMips());
    private final List<CgRequest> requests = new ArrayList<>();
    private final int[] landed = new int[4];
    private int inspectsArmed, inspected, inspectsFailed;
    private int frame, minLatency = Integer.MAX_VALUE, maxLatency;
    private String failure;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        CgBufferInspector.watch(true);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame <= FRAMES) {
            record(frame);
            return;
        }
        boolean answered = inspected + inspectsFailed == inspectsArmed;
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (answered || frame > FRAMES + DRAIN) {
            report();
            running = false;
        }
    }

    private void record(int n) {
        CgRecording rec = new CgRecording();
        CgComputePass pass = rec.compute("readback.iota");
        CgGpuOps.iota(pass, words, n * 1000, 3, CgGpuCount.of(WORDS));
        pass.end();
        requests.add(rec.readback(words, FIRST_WORD * 4L, READ_WORDS * 4L, data -> {
            for (int i = 0; i < READ_WORDS; i++) {
                expect(data.getInt(i * 4), n * 1000 + 3 * (FIRST_WORD + i), "word " + (FIRST_WORD + i), n);
            }
            landed(0, n);
        }));

        clear(rec, floats, 0, CgLoad.clear(n, -0.5f * n, 0.25f, 1f));
        requests.add(rec.readback(floats, 0, 2, 1, 4, 2, data -> {
            for (int p = 0; p < 8; p++) {
                float[] want = {n, -0.5f * n, 0.25f, 1f};
                for (int c = 0; c < 4; c++) {
                    float got = data.getFloat((p * 4 + c) * 4);
                    if (got != want[c]) fail("RGBA32F pixel " + p + "." + c + " is " + got + ", not " + want[c], n);
                }
            }
            landed(1, n);
        }));

        int b = n % 256;
        clear(rec, bytes, 0, CgLoad.clear(b / 255f, 0f, 0f, 0f));
        requests.add(rec.readback(bytes, 0, 0, 0, 3, 5, data -> {
            if (data.remaining() < 15) fail("R8 read " + data.remaining() + " bytes, not 15", n);
            for (int i = 0; i < 15; i++) expect(data.get(i) & 0xFF, b, "R8 texel " + i, n);
            landed(2, n);
        }));

        clear(rec, chain, 0, CgLoad.clear(0f, 0f, 0f, 0f));
        clear(rec, chain, 2, CgLoad.clear(b / 255f, 51 / 255f, 1f, 1f));
        requests.add(rec.readback(chain, 2, 0, 0, 4, 2, data -> {
            int[] want = {b, 51, 255, 255};
            for (int i = 0; i < 32; i++) expect(data.get(i) & 0xFF, want[i % 4], "level 2 byte " + i, n);
            landed(3, n);
        }));
        CgImmediate.execute(rec);
        if (n < FRAMES) inspect(n + 1);
    }

    /** The words as the inspector shows them after frame {@code n}'s iota. */
    private void inspect(int n) {
        for (CgBufferInspector.Site site : CgBufferInspector.sites()) {
            if (!site.buffer().equals("readback.words") || !site.pass().equals("readback.iota")) continue;
            inspectsArmed++;
            CgBufferInspector.read(site, FIRST_WORD, READ_WORDS, new CgBufferInspector.Sink() {
                @Override
                public void accept(CgBufferInspector.Read read) {
                    CgElementField word = read.site().decl().fields().get(0);
                    if (!read.site().decl().element().equals("uint") || read.site().elements() != WORDS) {
                        fail("inspector site " + read.site(), n);
                    }
                    expect(read.first(), FIRST_WORD, "inspected first element", n);
                    expect(read.count(), READ_WORDS, "inspected element count", n);
                    for (int i = 0; i < read.count(); i++) {
                        expect(Integer.parseInt(read.value(i, word)), n * 1000 + 3 * (FIRST_WORD + i),
                                "inspected word " + (FIRST_WORD + i), n);
                    }
                    inspected++;
                }

                @Override
                public void failed(String reason) {
                    inspectsFailed++;
                    fail("inspector read failed: " + reason, n);
                }
            });
            return;
        }
        if (n > 2) fail("no inspector site for readback.words after readback.iota", n);
    }

    private static void clear(CgRecording rec, CgGraphTexture texture, int level, CgLoad load) {
        CgPassConstants constants = new CgPassConstants().resolution(Math.max(1, texture.getWidth() >> level),
                Math.max(1, texture.getHeight() >> level));
        rec.raster(texture, level, load, constants, null, CgOrder.LOOKBACK).end();
    }

    private void landed(int kind, int requestedAt) {
        landed[kind]++;
        int latency = frame - requestedAt;
        minLatency = Math.min(minLatency, latency);
        maxLatency = Math.max(maxLatency, latency);
    }

    private void expect(int got, int want, String what, int n) {
        if (got != want) fail(what + " is " + got + ", not " + want, n);
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
        if (GlErrorChecker.checkAndLog("readback")) {
            System.out.println("[readback] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[readback] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (unanswered != null) {
            System.out.println("[readback] FAIL on " + on + ": " + done + " of " + requests.size() + " answered; " + unanswered);
        } else if (inspected + inspectsFailed != inspectsArmed || inspectsArmed == 0) {
            System.out.println("[readback] FAIL on " + on + ": " + inspected + " of " + inspectsArmed + " inspector reads answered");
        } else if ("recording".equals(on)) {
            System.out.println("[readback] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (failure != null) {
            System.out.println("[readback] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[readback] PASS on " + on + ": " + landed[0] + " buffer, " + landed[1] + " RGBA32F, "
                    + landed[2] + " unaligned R8 and " + landed[3] + " mip-level readbacks landed as recorded, "
                    + minLatency + "-" + maxLatency + " frames later; " + inspected + " inspector reads decoded");
        }
    }

    @Override
    public void dispose() {
        CgBufferInspector.watch(false);
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
