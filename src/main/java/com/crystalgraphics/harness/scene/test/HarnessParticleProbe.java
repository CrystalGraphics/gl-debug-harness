package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.gpu.draw.CgVfxRange;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One emitter's particles read back every frame, right after Range culls, sorts and caps them, and logged as what
 * changed since the frame before: which particles live in its slot, which Range drew, and how far the live ones moved
 * and aged. For a particle that flickers: it tells a particle that dies and respawns from one drawn one frame and
 * skipped the next.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-kamehameha --device=vulkan --seconds=25" \
 *     -Dcrystalgraphics.harness.probe=CylinderPtc
 * // held at 0x partway: Shift, then Y five times, then probed
 * ... -Dcrystalgraphics.harness.keyAt=500:LSHIFT,501:Y,502:Y,503:Y,504:Y,505:Y -Dcrystalgraphics.harness.probe.skip=150
 * // [probe] beam end f=12 slot 3: live 1251 (born 0, died 0), drawn 500 (kept 212, new 288, dropped 288), moved 0.0000 ...
 * }</pre>
 *
 * <ul>
 *   <li>Off unless {@code crystalgraphics.harness.probe} names an emitter; it logs {@code .probe.frames} frames (120)
 *       after passing over {@code .probe.skip} (0) once something is watched, then stops. Held at 0x from the start
 *       ({@code crystalgraphics.demo.time=0}) nothing ever spawns.</li>
 *   <li>A frame's line arrives two or three frames after it: readbacks land late.</li>
 * </ul>
 */
final class HarnessParticleProbe implements AutoCloseable {

    /** The probe's name for every emitter of the watched systems: whether each is submitted and kept, logged on change. */
    static final String EVERY = "*";

    private final CgVfxSystem vfx;
    private final String emitter;
    private final int frames;
    private final List<String> labels = new ArrayList<>();
    private final List<CgVfxSystemInstance<?>> systems = new ArrayList<>();
    /** Per label, the last frame's drawn spawn ids and every live particle's id to position and age. */
    private final Map<String, Set<Integer>> lastDrawn = new HashMap<>();
    private final Map<String, Map<Integer, float[]>> lastLive = new HashMap<>();
    private final CgRenderStage.Registration registration;
    private int recorded, logged;
    /** Frames left to pass over once something is watched. */
    private int skip;

    private HarnessParticleProbe(CgVfxSystem vfx, String emitter, int frames, int skip) {
        this.vfx = vfx;
        this.emitter = emitter;
        this.frames = frames;
        this.skip = skip;
        registration = CgRenderStage.WORLD_OPAQUE.register(CgVfxRange.ORDER + 1, this::record);
    }

    /** The probe {@code crystalgraphics.harness.probe} asks for, or null. */
    static HarnessParticleProbe fromProperties(CgVfxSystem vfx) {
        String name = System.getProperty("crystalgraphics.harness.probe");
        if (name == null || name.isBlank()) return null;
        int frames = Integer.getInteger("crystalgraphics.harness.probe.frames", 120);
        int skip = Integer.getInteger("crystalgraphics.harness.probe.skip", 0);
        System.out.println("[probe] watching emitter " + name + " for " + frames + " frames after " + skip);
        return new HarnessParticleProbe(vfx, name.trim(), frames, skip);
    }

    /** Watches {@code system}'s emitter of the probe's name under {@code label}, replacing what that label watched. */
    void watch(String label, CgVfxSystemInstance<?> system) {
        if (!EVERY.equals(emitter) && system.emitter(emitter) == null) return;
        int at = labels.indexOf(label);
        if (at >= 0) systems.set(at, system);
        else {
            labels.add(label);
            systems.add(system);
        }
    }

    private void record(CgStageFrame frame) {
        if (skip > 0 && systems.size() > 0) {
            skip--;
            return;
        }
        if (recorded >= frames) return;
        if (EVERY.equals(emitter)) {
            recordEvery(frame);
            return;
        }
        boolean any = false;
        for (int i = 0; i < systems.size(); i++) {
            CgVfxEmitterInstance instance = systems.get(i).emitter(emitter);
            CgVfxParticlePool pool = instance == null ? null : vfx.pool(instance);
            if (pool == null || pool.records() == null) continue;
            int slot = vfx.slot(instance);
            CgVfxRange range = CgVfxRange.of(pool);
            Snapshot s = new Snapshot(labels.get(i), recorded, slot, pool.record());
            int base = range.base(slot), capacity = pool.capacity(slot);
            frame.recording().readback(pool.liveBuffer(), pool.liveOffset(), 4, d -> s.live = d.getInt(0));
            frame.recording().readback(pool.records(), 0, (long) pool.storage() * pool.record().bytes(), d -> s.records = copy(d));
            frame.recording().readback(range.visible(), range.visibleWord(slot) * 4L, 4, d -> s.visible = d.getInt(0));
            frame.recording().readback(range.order(), base * 4L, capacity * 4L, d -> {
                s.order = copy(d);
                report(s);
            });
            any = true;
        }
        if (any) recorded++;
    }

    /**
     * Under {@link #EVERY}: each emitter of every watched system, whether this frame submits it (its books say it may
     * hold particles) and how many particles Range kept for it, logged when either changes.
     */
    private void recordEvery(CgStageFrame frame) {
        int at = recorded++;
        for (int i = 0; i < systems.size(); i++) {
            CgVfxSystemInstance<?> system = systems.get(i);
            for (int e = 0; e < system.emitters(); e++) {
                CgVfxEmitterInstance instance = system.emitter(e);
                String key = labels.get(i) + "/" + e + " " + instance.emitter().name();
                boolean submitted = instance.mayHaveParticles();
                CgVfxParticlePool pool = vfx.pool(instance);
                if (pool == null || pool.records() == null) {
                    every(key, at, submitted, -1);
                    continue;
                }
                CgVfxRange range = CgVfxRange.of(pool);
                frame.recording().readback(range.visible(), range.visibleWord(vfx.slot(instance)) * 4L, 4,
                        d -> every(key, at, submitted, d.getInt(0)));
            }
        }
        if (recorded == frames) System.out.println("[probe] done");
    }

    private final Map<String, String> lastEvery = new HashMap<>();

    private void every(String key, int frame, boolean submitted, int visible) {
        String state = (submitted ? "submitted" : "skipped") + ", " + (visible < 0 ? "no slot" : visible + " kept");
        String was = lastEvery.put(key, state);
        if (!state.equals(was)) System.out.println("[probe] f=" + frame + " " + key + ": " + state + (was == null ? "" : " (was " + was + ")"));
    }

    private void report(Snapshot s) {
        CgVfxRecord layout = s.layout;
        Map<Integer, float[]> live = new HashMap<>();
        Map<Integer, Integer> idOfRecord = new HashMap<>();
        for (int r = 0; r < s.live; r++) {
            if (layout.i(s.records, r, CgVfxRecord.SLOT) != s.slot) continue;
            int id = layout.i(s.records, r, CgVfxRecord.ID);
            idOfRecord.put(r, id);
            live.put(id, new float[]{layout.f(s.records, r, CgVfxRecord.POSITION), layout.f(s.records, r, CgVfxRecord.POSITION + 1),
                    layout.f(s.records, r, CgVfxRecord.POSITION + 2), layout.f(s.records, r, CgVfxRecord.AGE),
                    layout.f(s.records, r, CgVfxRecord.LIFE), layout.f(s.records, r, CgVfxRecord.SIZE)});
        }
        Set<Integer> drawn = new HashSet<>();
        int unknown = 0;
        // A drawn entry outside the slot, by the slot its record belongs to: -1 past the live ones.
        Map<Integer, Integer> foreign = new HashMap<>();
        for (int k = 0; k < s.visible; k++) {
            Integer id = idOfRecord.get((int) s.order.getFloat(k * 4));
            if (id != null) {
                drawn.add(id);
                continue;
            }
            unknown++;
            int r = (int) s.order.getFloat(k * 4);
            foreign.merge(r >= 0 && r < s.live ? layout.i(s.records, r, CgVfxRecord.SLOT) : -1, 1, Integer::sum);
        }
        Set<Integer> before = lastDrawn.get(s.label);
        Map<Integer, float[]> beforeLive = lastLive.get(s.label);
        int kept = 0;
        if (before != null) for (int id : drawn) if (before.contains(id)) kept++;
        float moved = 0f, aged = 0f;
        int born = 0, died = 0;
        if (beforeLive != null) {
            for (Map.Entry<Integer, float[]> e : live.entrySet()) {
                float[] was = beforeLive.get(e.getKey());
                if (was == null) {
                    born++;
                    continue;
                }
                float[] now = e.getValue();
                moved = Math.max(moved, (float) Math.sqrt(sq(now[0] - was[0]) + sq(now[1] - was[1]) + sq(now[2] - was[2])));
                aged = Math.max(aged, now[3] - was[3]);
            }
            for (int id : beforeLive.keySet()) if (!live.containsKey(id)) died++;
        }
        float minSize = Float.MAX_VALUE, maxSize = 0f, minLeft = Float.MAX_VALUE;
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (float[] p : live.values()) {
            minSize = Math.min(minSize, p[5]);
            maxSize = Math.max(maxSize, p[5]);
            minLeft = Math.min(minLeft, p[4] - p[3]);
            for (int a = 0; a < 3; a++) {
                lo[a] = Math.min(lo[a], p[a]);
                hi[a] = Math.max(hi[a], p[a]);
            }
        }
        String box = live.isEmpty() ? "" : String.format(", from origin (%.2f, %.2f, %.2f)..(%.2f, %.2f, %.2f)",
                lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
        System.out.printf("[probe] %s f=%d slot %d: live %d (born %d, died %d), drawn %d (kept %d, new %d, dropped %d%s),"
                        + " moved %.4f, aged %.4f, size %.3f..%.3f, least life left %.3f%s%n",
                s.label, s.frame, s.slot, live.size(), born, died, drawn.size(), kept, before == null ? 0 : drawn.size() - kept,
                before == null ? 0 : before.size() - kept, unknown > 0 ? ", " + unknown + " not in the slot, by slot " + foreign : "",
                moved, aged, live.isEmpty() ? 0f : minSize, maxSize, live.isEmpty() ? 0f : minLeft, box);
        lastDrawn.put(s.label, drawn);
        lastLive.put(s.label, live);
        if (++logged == frames * labels.size()) System.out.println("[probe] done");
    }

    private static float sq(float v) {
        return v * v;
    }

    private static ByteBuffer copy(ByteBuffer data) {
        ByteBuffer out = ByteBuffer.allocate(data.remaining()).order(ByteOrder.nativeOrder());
        out.put(data.duplicate().order(ByteOrder.nativeOrder())).flip();
        return out;
    }

    @Override
    public void close() {
        registration.close();
    }

    /** One frame of one watched slot, filled by its readbacks as they land, in the order recorded. */
    private static final class Snapshot {
        final String label;
        final int frame, slot;
        final CgVfxRecord layout;
        int live, visible;
        ByteBuffer records, order;

        Snapshot(String label, int frame, int slot, CgVfxRecord layout) {
            this.label = label;
            this.frame = frame;
            this.slot = slot;
            this.layout = layout;
        }
    }
}
