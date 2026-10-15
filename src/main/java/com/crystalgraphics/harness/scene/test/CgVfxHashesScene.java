package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The gate for the spawn hash: {@code fx_rand} on the GPU must give the same bits as
 * {@code CgVfxEmitterInstance.rand}, since the Java books an emitter keeps (who spawns under a density share, when its
 * last particle dies) must agree with the particles the GPU spawned.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-hashes"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-hashes" -Dcrystalgraphics.compute.tier=G33
 * }</pre>
 *
 * <p>Prints {@code [vfx-hashes] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgVfxHashesScene implements InteractiveSceneLifecycle {

    private static final int RANDS = 4096, FRAMES = 3, DRAIN = 30;

    private final CgGraphBuffer randIn = CgGraphBuffer.transientBuffer("vfx-hashes.in", buffer(RANDS, 16));
    private final CgGraphBuffer randOut = CgGraphBuffer.transientBuffer("vfx-hashes.out", buffer(RANDS, 4));
    private final int[] seeds = new int[RANDS], spawns = new int[RANDS], draws = new int[RANDS];
    private final List<CgRequest> requests = new ArrayList<>();
    private int checked;
    private CgCompute kernels;
    private int frame;
    private String failure;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        kernels = CgCompute.load("harness:shaders/vfx_hashes.compute");
        Random random = new Random(1201);
        for (int i = 0; i < RANDS; i++) {
            seeds[i] = Float.floatToIntBits(random.nextFloat() * 1000f);
            spawns[i] = random.nextInt(1 << 20);
            draws[i] = i % 11;
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame <= FRAMES) {
            record(frame);
            return;
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
        rec.update(randIn, 0, randInputs());
        CgComputePass pass = rec.compute("vfx-hashes");
        pass.dispatch(kernels.kernel("Rand"), RANDS).bind("RAND_IN", randIn).bind("RAND_OUT", randOut);
        pass.end();
        requests.add(rec.readback(randOut, 0, RANDS * 4L, data -> {
            for (int i = 0; i < RANDS; i++) {
                float got = data.getFloat(i * 4), want = CgVfxEmitterInstance.rand(seeds[i], spawns[i], draws[i]);
                if (Float.floatToRawIntBits(got) != Float.floatToRawIntBits(want) && failure == null) {
                    failure = "frame " + n + "'s fx_rand(" + seeds[i] + ", " + spawns[i] + ", " + draws[i] + ") is "
                            + got + ", not " + want;
                }
                checked++;
            }
        }));
        CgImmediate.execute(rec);
    }

    private ByteBuffer randInputs() {
        ByteBuffer bytes = ByteBuffer.allocateDirect(RANDS * 16).order(ByteOrder.nativeOrder());
        for (int i = 0; i < RANDS; i++) bytes.putInt(seeds[i]).putInt(spawns[i]).putInt(draws[i]).putInt(0);
        return bytes.flip();
    }

    private static CgBufferDesc buffer(int count, int bytes) {
        return CgBufferDesc.elements(count, bytes, CgBufferUsage.STORAGE, CgBufferUsage.COPY);
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
        if (GlErrorChecker.checkAndLog("vfx-hashes")) {
            System.out.println("[vfx-hashes] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[vfx-hashes] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (unanswered != null) {
            System.out.println("[vfx-hashes] FAIL on " + on + ": " + done + " of " + requests.size() + " answered; " + unanswered);
        } else if (on.startsWith("recording")) {
            System.out.println("[vfx-hashes] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (failure != null) {
            System.out.println("[vfx-hashes] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[vfx-hashes] PASS on " + on + ": " + checked + " fx_rand draws bit for bit");
        }
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
