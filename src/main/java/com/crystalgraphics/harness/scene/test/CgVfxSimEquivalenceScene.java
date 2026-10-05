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
import com.crystalgraphics.vfx.particle.CgVfxAir;
import com.crystalgraphics.vfx.particle.CgVfxCurlNoise;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.CgVfxParticleSet;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;

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

    private static final int RANDS = 4096, CURLS = 4096, MODULE_PARTICLES = 1024, FRAMES = 3, DRAIN = 30;
    /** CgVfxCurlNoise's second octave: x * 2.03 + offset. */
    private static final float OCTAVE = 2.03f;
    private static final float[] OCTAVE_OFFSET = {5.2f, 1.3f, 7.9f};
    /** Far more than rounding (an ulp or two of fused multiply-adds), far less than a wrong corner or gradient. */
    private static final float CURL_TOLERANCE = 1e-4f;

    private final CgGraphBuffer randIn = CgGraphBuffer.transientBuffer("vfx-sim.rand.in", buffer(RANDS, 16));
    private final CgGraphBuffer randOut = CgGraphBuffer.transientBuffer("vfx-sim.rand.out", buffer(RANDS, 4));
    private final CgGraphBuffer curlIn = CgGraphBuffer.transientBuffer("vfx-sim.curl.in", buffer(CURLS, 64));
    private final CgGraphBuffer curlOut = CgGraphBuffer.transientBuffer("vfx-sim.curl.out", buffer(CURLS, 16));
    private final ModuleCheck[] modules = {
            new ModuleCheck(new CgVfxModule.Gravity(9.8f)), new ModuleCheck(new CgVfxModule.Drag(4f, 0.3f)),
            new ModuleCheck(new CgVfxModule.Wind(1.2f)), new ModuleCheck(new CgVfxModule.Turbulence(8f, 0.12f, 0.6f)),
            new ModuleCheck(new CgVfxModule.Buoyancy(16f, 0.9f)), new ModuleCheck(new CgVfxModule.Updraft(30f, 3f, 8f, 2.5f)),
            new ModuleCheck(new CgVfxModule.Ground(0.3f, 0.5f, 0.6f)), new ModuleCheck(new CgVfxModule.Spin(0.8f))};
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
        for (int k = 0; k < modules.length; k++) modules[k].prepare(k, random);
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
        for (int k = 0; k < modules.length; k++) modules[k].record(rec, pass, k, n);
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

    /**
     * One module kind alone: particles seeded in an emitter instance that has ticked for a second at a world origin,
     * the kind's {@code apply} run over them on the CPU, and its {@code fx_<kind>} over the same particles on the GPU
     * with the numbers and lanes its own writers produce. Positions, velocities, heat, spin rate, resting and the forces
     * it added are compared.
     */
    private final class ModuleCheck {
        /** Floats compared a particle: position, velocity, heat, spin rate, resting, acceleration, drag, quadratic drag. */
        private static final int COMPARED = 13;

        final CgVfxModule module;
        private final int[] words = new int[6 * 4];
        private float[] input, expected;
        private int[] ids;
        private float dt, windX, windY, windZ;
        private CgGraphBuffer particles, wordBuffer, moved;
        /** The largest difference seen, as a share of its tolerance. */
        float worst;

        ModuleCheck(CgVfxModule module) {
            this.module = module;
        }

        void prepare(int k, Random random) {
            String kind = module.gpuKind();
            particles = CgGraphBuffer.transientBuffer("vfx-sim.module." + kind, buffer(MODULE_PARTICLES, 80));
            wordBuffer = CgGraphBuffer.transientBuffer("vfx-sim.module." + kind + ".words", buffer(6, 16));
            moved = CgGraphBuffer.transientBuffer("vfx-sim.module." + kind + ".moved", buffer(MODULE_PARTICLES, 112));

            CgVfxEmitter definition = CgVfxEmitter.builder("check-" + kind).capacity(MODULE_PARTICLES).module(module).build();
            CgVfxEmitterInstance instance = new CgVfxEmitterInstance(definition, 0.37f);
            instance.start(0.5f, 0.2f, -0.3f);
            instance.ground(-1f);
            CgVfxAir air = new CgVfxAir().wind(1.5f, 0.2f, 0.5f).gusts(0.6f, 0.25f);
            air.tick(0.7f);
            dt = 1f / 60f;
            for (int t = 0; t < 60; t++) instance.tick(dt, air, 100.25, 64.5, -37.75);   // nothing spawns: time is 1 s
            windX = air.windX();
            windY = air.windY();
            windZ = air.windZ();

            CgVfxParticleSet p = instance.particles();
            ids = new int[MODULE_PARTICLES];
            for (int i = 0; i < MODULE_PARTICLES; i++) {
                int at = p.add();
                p.x[at] = spread(random, 4f);
                p.y[at] = spread(random, 3f) + 1.5f;   // some under the ground at -1
                p.z[at] = spread(random, 4f);
                p.vx[at] = spread(random, 6f);
                p.vy[at] = spread(random, 6f);
                p.vz[at] = spread(random, 6f);
                p.px[at] = p.x[at] - p.vx[at] * dt;
                p.py[at] = p.y[at] - p.vy[at] * dt;
                p.pz[at] = p.z[at] - p.vz[at] * dt;
                p.age[at] = random.nextFloat() * 2f;
                p.life[at] = 2f + random.nextFloat() * 3f;
                p.size[at] = 0.1f + random.nextFloat();
                p.seed[at] = random.nextFloat();
                p.spin[at] = random.nextFloat() * 6.28f;
                p.spinRate[at] = spread(random, 5f);
                p.heat[at] = random.nextFloat();
                p.resting[at] = random.nextInt(10) == 0 ? 1f : 0f;
                p.id[at] = ids[i] = i * 7 + k;
            }
            input = new float[MODULE_PARTICLES * 20];
            for (int i = 0; i < MODULE_PARTICLES; i++) {
                float[] r = {p.x[i], p.y[i], p.z[i], p.age[i], p.px[i], p.py[i], p.pz[i], p.life[i],
                        p.vx[i], p.vy[i], p.vz[i], p.size[i], p.seed[i], p.spin[i], p.spinRate[i], p.heat[i]};
                System.arraycopy(r, 0, input, i * 20, 16);
                input[i * 20 + 16] = p.resting[i];
            }

            CgVfxWords out = CgVfxWords.into(words, 0, module.paramVectors(), kind);
            module.writeParams(out);
            out.finish("its numbers");
            if (module.instanceLanes().length > 0) {
                out = CgVfxWords.into(words, 4, module.instanceLanes().length, kind);
                module.writeInstance(instance, out);
                out.finish("its lanes");
            }
            words[20] = Float.floatToRawIntBits(instance.groundY());

            module.apply(instance, dt);
            expected = new float[MODULE_PARTICLES * COMPARED];
            for (int i = 0; i < MODULE_PARTICLES; i++) {
                float[] r = {p.x[i], p.y[i], p.z[i], p.vx[i], p.vy[i], p.vz[i], p.heat[i], p.spinRate[i], p.resting[i],
                        p.ax[i], p.ay[i], p.az[i], p.drag[i] + 1000f * p.dragQuad[i]};
                System.arraycopy(r, 0, expected, i * COMPARED, COMPARED);
            }
        }

        void record(CgRecording rec, CgComputePass pass, int k, int n) {
            ByteBuffer in = ByteBuffer.allocateDirect(MODULE_PARTICLES * 80).order(ByteOrder.nativeOrder());
            for (int i = 0; i < MODULE_PARTICLES; i++) {
                for (int w = 0; w < 16; w++) in.putFloat(input[i * 20 + w]);
                in.putInt(input[i * 20 + 16] != 0f ? 1 : 0).putInt(ids[i]).putInt(0).putInt(0);
            }
            ByteBuffer w = ByteBuffer.allocateDirect(words.length * 4).order(ByteOrder.nativeOrder());
            for (int word : words) w.putInt(word);
            rec.update(particles, 0, in.flip());
            rec.update(wordBuffer, 0, w.flip());
            pass.dispatch(kernels.kernel("Module"), MODULE_PARTICLES).bind("PARTICLES", particles)
                    .bind("WORDS", wordBuffer).bind("MOVED", moved).set("_Kind", k).set("_Step", dt, windX, windY, windZ);
            requests.add(rec.readback(moved, 0, MODULE_PARTICLES * 112L, data -> compare(data, n)));
        }

        private void compare(ByteBuffer data, int n) {
            float tolerance = module instanceof CgVfxModule.Turbulence ? 1e-3f : 2e-5f;
            float[] got = new float[COMPARED];
            for (int i = 0; i < MODULE_PARTICLES; i++) {
                int at = i * 112;
                got[0] = data.getFloat(at);
                got[1] = data.getFloat(at + 4);
                got[2] = data.getFloat(at + 8);
                got[3] = data.getFloat(at + 32);
                got[4] = data.getFloat(at + 36);
                got[5] = data.getFloat(at + 40);
                got[6] = data.getFloat(at + 60);
                got[7] = data.getFloat(at + 56);
                got[8] = data.getInt(at + 64);
                got[9] = data.getFloat(at + 80);
                got[10] = data.getFloat(at + 84);
                got[11] = data.getFloat(at + 88);
                got[12] = data.getFloat(at + 92) + 1000f * data.getFloat(at + 96);
                if (data.getInt(at + 68) != ids[i]) fail(module.gpuKind() + " lost particle " + i + "'s id", n);
                for (int c = 0; c < COMPARED; c++) {
                    float want = expected[i * COMPARED + c];
                    float off = Math.abs(got[c] - want) / (tolerance * (1f + Math.abs(want)));
                    worst = Math.max(worst, off);
                    if (!(off <= 1f)) {
                        fail(module.gpuKind() + " particle " + i + " value " + c + " is " + got[c] + ", not " + want, n);
                    }
                }
            }
        }
    }

    private static float spread(Random random, float half) {
        return (random.nextFloat() * 2f - 1f) * half;
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
            StringBuilder kinds = new StringBuilder();
            for (ModuleCheck m : modules) {
                kinds.append(kinds.length() == 0 ? "" : ", ").append(m.module.gpuKind()).append(' ').append(m.worst);
            }
            System.out.println("[vfx-sim] PASS on " + on + ": " + randsChecked + " fx_rand draws bit for bit, "
                    + curlsChecked + " fx_curl samples within " + CURL_TOLERANCE + " (worst " + worstCurl + "), "
                    + MODULE_PARTICLES + " particles through each module kind as its apply moves them (worst share of the "
                    + "tolerance: " + kinds + ")");
        }
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
