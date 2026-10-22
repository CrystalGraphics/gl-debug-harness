package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.render.world.CgWorldLights;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDrawnChannel;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRenderers;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Particles as lights (clustered-lights-decals L4): an emitter of {@link #PARTICLES} still embers over the floor, drawn
 * as sparks and each a light by Cascade's Light module, half of them given one by the renderer's spawn fraction. After {@link #WARM}
 * frames the firing's light list is read back with the pool's records and drawn parts, and held to a Java gather of
 * the same particles (Cascade's {@code ParticleSystemRender.cpp:870-935}): which particles give light, where, how far
 * and how bright, the colour scale and brightness by age evaluated in Java. Scene id {@code vfx-lights}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-lights"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-lights" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-lights --device=vulkan"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-lights" -Dcrystalgraphics.harness.vfx.lights=10000  *         -Dcrystalgraphics.harness.profile=300 -Dcrystalgraphics.trace.channels=crystalgraphics,gpu   // the cost at 10,000
 * }</pre>
 *
 * <p>Prints {@code [vfx-lights] PASS} or {@code FAIL}, then exits.</p>
 */
public final class CgVfxLightsScene implements InteractiveSceneLifecycle {

    /** Embers: 200 unless {@code -Dcrystalgraphics.harness.vfx.lights} asks more, which also keeps it running to be profiled. */
    private static final int PARTICLES = Integer.getInteger("crystalgraphics.harness.vfx.lights", 200), WARM = 30, DRAIN = 90;
    private static final boolean PROFILED = Integer.getInteger("crystalgraphics.harness.vfx.lights") != null;
    private static final float SHARE = 0.5f, RADIUS_SCALE = 12f, EXPONENT = 8f, TOLERANCE = 1e-3f;
    private static final float[] COLOR_SCALE = {1f, 0.5f, 0.25f};
    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("harness:vfx_lights");
    /** Brightness by age: up from 4 to 40 over a life, so the light element is held to its curve. */
    private static final CgKeyframes BRIGHTNESS = CgKeyframes.start(0f, 4f).to(1f, 40f, CgEasings.LINEAR).build();

    private final CgVfxSystem vfx = new CgVfxSystem();
    private final List<CgRequest> requests = new ArrayList<>();
    private CgVfxEmitter emitter;
    private CgVfxParticlePool pool;
    private ByteBuffer records, drawn, live, list, count;
    private double eyeX, eyeY, eyeZ;
    private final double[][] origins = new double[64][];
    private int frames;
    private boolean running = true, asked, compared;

    private static CgVfxEmitter emitter() {
        return CgVfxEmitter.builder("embers").capacity(PARTICLES).state(CgVfxEmitterState.once(1f)).burst(0f, PARTICLES)
                .shape(CgBox.of(6, 0.05, 6)).speed(0f, 0f).life(30f, 30f).size(0.2f, 0.6f)
                .light(RADIUS_SCALE, EXPONENT, CgVfxDrawnChannel.over(CgKeyframes.constant(COLOR_SCALE[0])),
                        CgVfxDrawnChannel.over(CgKeyframes.constant(COLOR_SCALE[1])),
                        CgVfxDrawnChannel.over(CgKeyframes.constant(COLOR_SCALE[2])), CgVfxDrawnChannel.over(BRIGHTNESS))
                .renderer(CgVfxRenderers.sprite("ember").material(CgMaterialDefinition.of("harness:materials/vfx_lights_ember",
                        "crystalgraphics:shaders/vfx/particle/spark.shader").blend(CgBlendMode.ADDITIVE).usage(CgMaterialUsage.SPRITES)
                        .build()).build())
                .renderer(CgVfxRenderers.light("glow").field("spawn_fraction", (double) SHARE)
                        .field("inverse_squared_falloff", false).build())
                .build();
    }

    @Override
    public void init(HarnessContext ctx) {
        CgAssetKinds.register();
        ctx.getCamera3D().moveCamera(0f, 6f, 14f);
        ctx.getCamera3D().setPitch(-25f);
        emitter = emitter();
        vfx.play(new CgVfxSystemInstance<>(CgVfxLook.builder(SCHEMA).emitter(emitter).build(), 0.0, 0.3, 0.0)).ground(0f);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        vfx.update(frame.getElapsedTime());
        vfx.submit(CgWorldRenderer.get());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
        frames++;
        if (!running || compared) return;
        if (frames == WARM) ask();
        boolean answered = asked;
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (answered && records != null && drawn != null && live != null && list != null && count != null) {
            compare();
            compared = true;
            running = PROFILED;
        } else if (frames > DRAIN) {
            System.out.println("[vfx-lights] FAIL: the readbacks never answered");
            running = false;
        }
    }

    /** The firing's lights, the pool's records and drawn parts and its live count, with the eye and slot origins they were made at. */
    private void ask() {
        asked = true;
        for (CgVfxParticlePool p : CgVfxParticlePool.pools()) if (p.shape().lights()) pool = p;
        if (pool == null) {
            System.out.println("[vfx-lights] FAIL: no pool gives light");
            running = false;
            return;
        }
        CgWorldLights lights = CgWorldRenderer.get().lights();
        eyeX = lights.cameraX();
        eyeY = lights.cameraY();
        eyeZ = lights.cameraZ();
        for (int s = 0; s < Math.min(pool.slotCount(), origins.length); s++) {
            origins[s] = new double[] {pool.origin(s, 0), pool.origin(s, 1), pool.origin(s, 2)};
        }
        CgRecording rec = new CgRecording();
        requests.add(rec.readback(pool.records(), 0, (long) pool.storage() * pool.record().bytes(), d -> records = copy(d)));
        requests.add(rec.readback(pool.drawn(), 0, (long) pool.storage() * pool.shape().drawnStride() * CgVfxRecord.DRAWN_BYTES,
                d -> drawn = copy(d)));
        requests.add(rec.readback(pool.live(), 0, 4, d -> live = copy(d)));
        requests.add(rec.readback(lights.list(), 0, (long) Math.min(PARTICLES, CgWorldLights.CAPACITY) * CgWorldLights.FLOATS * 4, d -> list = copy(d)));
        requests.add(rec.readback(lights.count(), 0, 4, d -> count = copy(d)));
        CgImmediate.execute(rec);
    }

    /** Each live particle's light by Java, against the list as a set: every expected light found once, nothing more. */
    private void compare() {
        CgVfxRecord layout = pool.record();
        int stride = pool.shape().drawnStride(), tail = layout.word(CgVfxRecord.Tail.LIGHT);
        int n = live.getInt(0), listed = count.getInt(0);
        List<float[]> want = new ArrayList<>();
        for (int r = 0; r < n; r++) {
            float life = layout.f(records, r, CgVfxRecord.LIFE), age = layout.f(records, r, CgVfxRecord.AGE);
            int bits = Float.floatToRawIntBits(layout.f(records, r, CgVfxRecord.SEED));
            if (CgVfxEmitterInstance.rand(bits, 0, 60) >= SHARE) continue;
            int slot = layout.i(records, r, CgVfxRecord.SLOT), part = r * stride * CgVfxRecord.DRAWN_BYTES;
            int light = (r * stride + 2) * CgVfxRecord.DRAWN_BYTES;
            float t = Math.min(age / life, 1f);
            // The light element against its channels in Java, by age.
            float[] scale = {COLOR_SCALE[0], COLOR_SCALE[1], COLOR_SCALE[2], BRIGHTNESS.at(t)};
            for (int c = 0; c < 4; c++) {
                float got = drawn.getFloat(light + c * 4);
                if (Math.abs(got - scale[c]) > TOLERANCE * (1f + Math.abs(scale[c]))) {
                    fail("particle " + r + "'s light channel " + c + " is " + got + ", Java gives " + scale[c]);
                    return;
                }
            }
            float size = drawn.getFloat(part + 12), sx = drawn.getFloat(part + 80), sy = drawn.getFloat(part + 84);
            float radius = layout.f(records, r, tail) * size * (sx + sy) * 0.5f;
            float a = drawn.getFloat(part + 64 + 12), k = a * scale[3];
            double[] o = origins[slot];
            want.add(new float[] {
                    (float) (o[0] - eyeX + drawn.getFloat(part)), (float) (o[1] - eyeY + drawn.getFloat(part + 4)),
                    (float) (o[2] - eyeZ + drawn.getFloat(part + 8)), radius,
                    drawn.getFloat(part + 64) * k * scale[0], drawn.getFloat(part + 68) * k * scale[1],
                    drawn.getFloat(part + 72) * k * scale[2], layout.f(records, r, tail + 1)});
        }
        if (listed != want.size()) {
            fail("the list holds " + listed + " lights, Java gathers " + want.size() + " of " + n + " particles");
            return;
        }
        boolean[] taken = new boolean[listed];
        float worst = 0f;
        for (float[] w : want) {
            int found = -1;
            for (int i = 0; i < listed && found < 0; i++) {
                if (taken[i]) continue;
                boolean same = true;
                for (int c = 0; c < 8 && same; c++) same = Math.abs(list.getFloat((i * CgWorldLights.FLOATS + c) * 4) - w[c]) <= TOLERANCE * (1f + Math.abs(w[c]));
                if (same) found = i;
            }
            if (found < 0) {
                fail("no listed light matches Java's at (" + w[0] + ", " + w[1] + ", " + w[2] + ") radius " + w[3]);
                return;
            }
            taken[found] = true;
            for (int c = 0; c < 8; c++) {
                worst = Math.max(worst, Math.abs(list.getFloat((found * CgWorldLights.FLOATS + c) * 4) - w[c]) / (TOLERANCE * (1f + Math.abs(w[c]))));
            }
        }
        System.out.printf("[vfx-lights] PASS: %d of %d particles give light (share %.2f), each listed as Java gathers it"
                + " (worst share of the tolerance %.4f)%n", want.size(), n, SHARE, worst);
    }

    private void fail(String what) {
        System.out.println("[vfx-lights] FAIL: " + what);
    }

    private static ByteBuffer copy(ByteBuffer data) {
        ByteBuffer out = ByteBuffer.allocate(data.remaining()).order(ByteOrder.nativeOrder());
        out.put(data.duplicate()).flip();
        return out;
    }

    @Override
    public void dispose() {
        vfx.delete();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return true; }
}
