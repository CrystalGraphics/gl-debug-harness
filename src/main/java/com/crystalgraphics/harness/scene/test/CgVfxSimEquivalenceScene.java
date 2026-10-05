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
import com.crystalgraphics.vfx.particle.CgVfxCurlNoise;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The gate for the GPU particle simulation against the CPU path (plan vfx-gpu §13.9). Today its first part: the
 * simulation's GLSL libraries run over chosen inputs and checked against the Java the CPU path runs. {@code fx_rand},
 * the spawn hash, must give the same bits as {@code CgVfxEmitterInstance.rand}; {@code fx_curl}, fed each point split per
 * octave into a lattice cell and a fraction as the CPU will split an instance's origin, must match
 * {@code CgVfxCurlNoise.sample} within float rounding.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-sim-equivalence"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-sim-equivalence" -Dcrystalgraphics.compute.tier=G33
 * }</pre>
 *
 * <p>Prints {@code [vfx-sim] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgVfxSimEquivalenceScene implements InteractiveSceneLifecycle {

    private static final int RANDS = 4096, CURLS = 4096, FRAMES = 3, DRAIN = 30;
    /** CgVfxCurlNoise's second octave: x * 2.03 + offset. */
    private static final float OCTAVE = 2.03f;
    private static final float[] OCTAVE_OFFSET = {5.2f, 1.3f, 7.9f};
    /** Far more than rounding (an ulp or two of fused multiply-adds), far less than a wrong corner or gradient. */
    private static final float CURL_TOLERANCE = 1e-4f;

    private final CgGraphBuffer randIn = CgGraphBuffer.transientBuffer("vfx-sim.rand.in", buffer(RANDS, 16));
    private final CgGraphBuffer randOut = CgGraphBuffer.transientBuffer("vfx-sim.rand.out", buffer(RANDS, 4));
    private final CgGraphBuffer curlIn = CgGraphBuffer.transientBuffer("vfx-sim.curl.in", buffer(CURLS, 64));
    private final CgGraphBuffer curlOut = CgGraphBuffer.transientBuffer("vfx-sim.curl.out", buffer(CURLS, 16));
    private final int[] seeds = new int[RANDS], spawns = new int[RANDS], draws = new int[RANDS];
    private final float[][] points = new float[CURLS][3];
    private final List<CgRequest> requests = new ArrayList<>();
    private int randsChecked, curlsChecked;
    private float worstCurl;
    private CgCompute kernels;
    private int frame;
    private String failure;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        kernels = CgCompute.load("harness:shaders/vfx_sim.compute");
        Random random = new Random(1201);
        for (int i = 0; i < RANDS; i++) {
            seeds[i] = Float.floatToIntBits(random.nextFloat() * 1000f);
            spawns[i] = random.nextInt(1 << 20);
            draws[i] = i % 11;
        }
        for (float[] p : points) {
            for (int a = 0; a < 3; a++) p[a] = (random.nextFloat() - 0.5f) * 100f;
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
        rec.update(curlIn, 0, curlInputs());
        CgComputePass pass = rec.compute("vfx-sim.libraries");
        pass.dispatch(kernels.kernel("Rand"), RANDS).bind("RAND_IN", randIn).bind("RAND_OUT", randOut);
        pass.dispatch(kernels.kernel("Curl"), CURLS).bind("CURL_IN", curlIn).bind("CURL_OUT", curlOut);
        pass.end();
        requests.add(rec.readback(randOut, 0, RANDS * 4L, data -> {
            for (int i = 0; i < RANDS; i++) {
                float got = data.getFloat(i * 4), want = CgVfxEmitterInstance.rand(seeds[i], spawns[i], draws[i]);
                if (Float.floatToRawIntBits(got) != Float.floatToRawIntBits(want)) {
                    fail("fx_rand(" + seeds[i] + ", " + spawns[i] + ", " + draws[i] + ") is " + got + ", not " + want, n);
                }
                randsChecked++;
            }
        }));
        requests.add(rec.readback(curlOut, 0, CURLS * 16L, data -> {
            float[] want = new float[3];
            for (int i = 0; i < CURLS; i++) {
                CgVfxCurlNoise.sample(points[i][0], points[i][1], points[i][2], want);
                for (int a = 0; a < 3; a++) {
                    float off = Math.abs(data.getFloat(i * 16 + a * 4) - want[a]);
                    worstCurl = Math.max(worstCurl, off);
                    if (!(off <= CURL_TOLERANCE)) {
                        fail("fx_curl at (" + points[i][0] + ", " + points[i][1] + ", " + points[i][2] + ") axis " + a
                                + " is " + data.getFloat(i * 16 + a * 4) + ", not " + want[a], n);
                    }
                }
                curlsChecked++;
            }
        }));
        CgImmediate.execute(rec);
    }

    private ByteBuffer randInputs() {
        ByteBuffer bytes = ByteBuffer.allocateDirect(RANDS * 16).order(ByteOrder.nativeOrder());
        for (int i = 0; i < RANDS; i++) bytes.putInt(seeds[i]).putInt(spawns[i]).putInt(draws[i]).putInt(0);
        return bytes.flip();
    }

    /**
     * Each point split as the CPU will split an instance's origin: per octave, the floor and the fraction of where that
     * octave samples, worked out in float exactly as {@code CgVfxCurlNoise} works them out, so the two see one point.
     */
    private ByteBuffer curlInputs() {
        ByteBuffer bytes = ByteBuffer.allocateDirect(CURLS * 64).order(ByteOrder.nativeOrder());
        float[] second = new float[3];
        for (float[] p : points) {
            for (int a = 0; a < 3; a++) second[a] = p[a] * OCTAVE + OCTAVE_OFFSET[a];
            putSplit(bytes, p);
            putSplit(bytes, second);
        }
        return bytes.flip();
    }

    /** An ivec4 cell then a vec4 fraction. */
    private static void putSplit(ByteBuffer bytes, float[] at) {
        for (int a = 0; a < 3; a++) bytes.putInt((int) Math.floor(at[a]));
        bytes.putInt(0);
        for (int a = 0; a < 3; a++) bytes.putFloat(at[a] - (float) Math.floor(at[a]));
        bytes.putFloat(0f);
    }

    private static CgBufferDesc buffer(int count, int bytes) {
        return CgBufferDesc.elements(count, bytes, CgBufferUsage.STORAGE, CgBufferUsage.COPY);
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
        if (GlErrorChecker.checkAndLog("vfx-sim")) {
            System.out.println("[vfx-sim] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[vfx-sim] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (unanswered != null) {
            System.out.println("[vfx-sim] FAIL on " + on + ": " + done + " of " + requests.size() + " answered; " + unanswered);
        } else if (on.startsWith("recording")) {
            System.out.println("[vfx-sim] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (failure != null) {
            System.out.println("[vfx-sim] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[vfx-sim] PASS on " + on + ": " + randsChecked + " fx_rand draws bit for bit, "
                    + curlsChecked + " fx_curl samples within " + CURL_TOLERANCE + " (worst " + worstCurl + ")");
        }
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
