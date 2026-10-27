package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgLoadHandle;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.registry.CgAssetSource;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.texture.CgTextureAbstract;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.volume.CgPostVolumeSpawner;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.sequence.CgSequenceDefinition;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequenceRole;
import com.crystalgraphics.sequence.CgSequenceSpawned;
import com.crystalgraphics.sequence.CgSequenceSystem;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.prefab.CgVfxPrefabAssets;
import com.crystalgraphics.vfx.prefab.CgVfxPrefabSpawner;
import com.crystalgraphics.vfx.system.CgVfxSystemAssets;
import com.crystalgraphics.vfx.system.CgVfxSystemPreparer;
import com.crystalgraphics.vfx.system.CgVfxSystemSpawner;
import com.crystalgraphics.world.CgAttachment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A sequence played from its files through the sequencer, its spawnables made by the engine's spawners: systems,
 * prefabs and post volumes. The Nova Cannon by default; any kit's sequence by id. Scene id {@code sequence-spawn}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=sequence-spawn --device=vulkan"
 * // the Rasen Shuriken's demo, its worst boundary frame traced
 * ... -Dcrystalgraphics.harness.sequence.kit.namespace=nsuns4_kit \
 *     -Dcrystalgraphics.harness.sequence.id=nsuns4_kit:2efb_rsn/2efb_rsn_df_demo02 \
 *     -Dcrystalgraphics.trace.channels=crystalgraphics.world,crystalgraphics.vfx
 * }</pre>
 *
 * <ul>
 *   <li>The caster is a fixture standing at the origin, facing +x, every socket at chest height; it takes any slot a
 *       track writes. Cameras and lights spawn as fixtures too: their systems are not this scene's.</li>
 *   <li>Prints each system's activations and deactivations at their sequence ticks, the frame whose sequencer cost
 *       most (and how many objects it spawned), and the compile waits, sync loads and texture loads after
 *       {@code READY}. For the Nova Cannon it prints {@code PASS} or {@code FAIL}: the charge over [0, 42], the beam
 *       over [40, 90], no sync or texture load while playing, no Vulkan validation error. Compile waits are reported:
 *       a post volume's look keywords compile as it first applies.</li>
 * </ul>
 */
public final class CgSequenceSpawnScene implements InteractiveSceneLifecycle {

    private static final String TAG = "[sequence-spawn] ";
    /** From the harness's working directory, {@code gl-debug-harness/}. */
    private static final String NOVA_KIT = "../plan/crystalgraphics/vfx-aaa/examples/novamod/assets/novamod";
    private static final String KITS = "../plan/crystalgraphics/vfx-aaa/local/examples/";
    private static final String NOVA = "novamod:nova_cannon";
    /** Frames played past the sequence's end, for its systems to finish. */
    private static final int TAIL = 60;
    /** Frames to wait for its bundle, and to play it, before failing. */
    private static final int LOAD_LIMIT = 600, PLAY_LIMIT = 7200;

    private final CgVfxSystem vfx = new CgVfxSystem();
    private final List<String> timeline = new ArrayList<>();
    private String sequenceId;
    private CgAssetRef<CgSequenceDefinition> ref;
    private CgLoadHandle handle;
    private CgSequencer sequencer;
    private CgSequencePlayer player;
    private boolean running = true;
    private int frames, loadFrames, endFrames, spawnedThisFrame, worstSpawns;
    private long waits, syncs, textures, worstNanos;
    private float worstTick;
    /** The Nova Cannon's gate: each system's first activation and its first deactivation after, in ticks. */
    private final float[] on = {-1, -1}, off = {-1, -1};

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(-6f, 4f, 12f);
        ctx.getCamera3D().setYaw(-25f);
        ctx.getCamera3D().setPitch(-10f);
        sequenceId = System.getProperty("crystalgraphics.harness.sequence.id", NOVA);
        String namespace = System.getProperty("crystalgraphics.harness.sequence.kit.namespace", "novamod");
        Path kit = Paths.get(System.getProperty("crystalgraphics.harness.sequence.kit",
                namespace.equals("novamod") ? NOVA_KIT : KITS + namespace));
        if (!Files.isDirectory(kit)) throw new IllegalStateException("no kit at " + kit.toAbsolutePath() + ": check out plan/");
        CgVfxSystemAssets.register();
        CgVfxPrefabAssets.register();
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.registerEngine();
        registry.scan(namespace, kit, CgAssetSource.DEV);
        CgVfxAssets assets = new CgVfxAssets(registry);
        assets.preparer(CgParticleKinds.SYSTEM, new CgVfxSystemPreparer(() -> vfx));
        CgVfxAssets.install(assets);
        ref = assets.declare(CgSequenceKinds.SEQUENCE, sequenceId);
        handle = assets.load(List.of(ref), CgLoadPriority.MOVE);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        double now = frame.getElapsedTime();
        if (player == null) {
            if (handle.failed()) {
                System.out.println(TAG + "FAIL on " + device() + ": " + sequenceId + " failed to load: " + ref.failure());
                running = false;
                return;
            }
            loadFrames++;
            if (loadFrames % 240 == 0) System.out.println(TAG + "loading " + sequenceId + ": frame " + loadFrames + ", " + ref.state());
            if (loadFrames > LOAD_LIMIT) {
                System.out.println(TAG + "FAIL on " + device() + ": " + sequenceId + " not ready after " + LOAD_LIMIT + " frames: " + ref.state());
                for (CgAssetRef<?> a : handle.assets()) System.out.println(TAG + "  " + a.kind().id() + " " + a.id() + ": " + a.state());
                running = false;
                return;
            }
            if (!handle.ready()) {
                vfx.update(now);   // the world's frame prepares the bundle's GPU work
                vfx.submit(CgWorldRenderer.get());
                HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
                return;
            }
            start();
        }
        spawnedThisFrame = 0;
        long t0 = System.nanoTime();
        sequencer.evaluate(frame.getDeltaTime());
        long cost = System.nanoTime() - t0;
        if (cost > worstNanos && frames > 0) {
            worstNanos = cost;
            worstTick = tick();
            worstSpawns = spawnedThisFrame;
        }
        vfx.update(now);
        vfx.submit(CgWorldRenderer.get());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
        frames++;
        if (frames % 120 == 0) System.out.println(TAG + "frame " + frames + ": tick " + String.format("%.1f", tick()));
        if (frames > PLAY_LIMIT) {
            System.out.println(TAG + "FAIL on " + device() + ": " + sequenceId + " still playing after " + PLAY_LIMIT + " frames, at tick " + tick());
            report();
        } else if (player.finished() && ++endFrames > TAIL) {
            report();
        }
    }

    private void start() {
        waits = CgMaterialShader.compileWaits();
        syncs = CgVfxAssets.instance().syncLoads();
        textures = CgTextureAbstract.loads();
        sequencer = new CgWorld().add(new CgSequencer());
        CgVfxSystemSpawner systems = new CgVfxSystemSpawner(vfx);
        sequencer.spawner("system", (p, s, at) -> new Watched(s.name(), (CgSequenceSystem) systems.spawn(p, s, at)));
        CgVfxPrefabSpawner prefabs = new CgVfxPrefabSpawner(vfx, systems);
        sequencer.spawner("prefab", (p, s, at) -> counted(prefabs.spawn(p, s, at)));
        CgPostVolumeSpawner post = new CgPostVolumeSpawner(CgPostStack.get());
        sequencer.spawner("post", (p, s, at) -> counted(post.spawn(p, s, at)));
        sequencer.spawner("camera", (p, s, at) -> counted(new Fixture()));
        sequencer.spawner("light", (p, s, at) -> counted(new Fixture()));
        player = sequencer.play(ref.get(), Map.of("caster", new Caster()));
    }

    private CgSequenceSpawned counted(CgSequenceSpawned s) {
        spawnedThisFrame++;
        return s;
    }

    private float tick() {
        return (float) (player.seconds() / player.definition().seconds(1));
    }

    private void report() {
        long compiles = CgMaterialShader.compileWaits() - waits, syncLoads = CgVfxAssets.instance().syncLoads() - syncs,
                textureLoads = CgTextureAbstract.loads() - textures;
        int validation = PlatformServiceHarness.validationErrors();
        for (String line : timeline) System.out.println(TAG + line);
        String summary = "ready after " + loadFrames + " frames; " + frames + " frames played; " + compiles
                + " compile waits, " + syncLoads + " sync loads, " + textureLoads + " texture loads while playing; the sequencer's"
                + " worst frame " + String.format("%.3f", worstNanos / 1e6) + " ms at tick " + String.format("%.1f", worstTick)
                + ", spawning " + worstSpawns + (validation > 0 ? "; " + validation + " Vulkan validation errors" : "");
        if (!sequenceId.equals(NOVA)) {
            System.out.println(TAG + sequenceId + " on " + device() + ": " + summary);
        } else {
            boolean charge = on[0] >= 0 && on[0] <= 1 && off[0] >= 42 && off[0] <= 45;
            boolean beam = on[1] >= 40 && on[1] <= 41 && off[1] >= 90 && off[1] <= 91;
            // A post volume's look keywords are not warmed yet (the composite's variant compiles as it first applies):
            // compile waits are reported, not gated, until post volumes from spawnables prepare.
            boolean pass = charge && beam && syncLoads == 0 && textureLoads == 0 && validation == 0;
            System.out.println(TAG + (pass ? "PASS" : "FAIL") + " on " + device() + ": the charge active over [" + on[0] + ", "
                    + off[0] + "], the beam over [" + on[1] + ", " + off[1] + "]; " + summary);
        }
        running = false;
    }

    private static String device() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        return device == null ? "gl" : device.name();
    }

    /** A spawned system, its activations written to the timeline at their ticks. */
    private final class Watched implements CgSequenceSystem {
        private final String name;
        private final CgSequenceSystem system;

        Watched(String name, CgSequenceSystem system) {
            this.name = name;
            this.system = system;
            spawnedThisFrame++;
        }

        private void note(String what) {
            float t = tick();
            timeline.add(String.format("tick %6.1f  %-8s %s", t, name, what));
            int i = "charge".equals(name) ? 0 : "beam".equals(name) ? 1 : -1;
            if (i < 0) return;
            if (what.startsWith("activate") && on[i] < 0) on[i] = t;
            if (what.equals("deactivate") && on[i] >= 0 && off[i] < 0) off[i] = t;
        }

        @Override public void activate(boolean reset) {
            boolean was = system.active();
            system.activate(reset);
            if (!was || reset) note(reset ? "activate (reset)" : "activate");
        }

        @Override public void deactivate() {
            boolean was = system.active();
            system.deactivate();
            if (was) note("deactivate");
        }

        @Override public void kill() {
            system.kill();
            note("kill");
        }

        @Override public void detach() {
            system.detach();
        }

        @Override public boolean active() {
            return system.active();
        }

        @Override public void age(float seconds) {
            system.age(seconds);
        }

        @Override public void destroy(boolean graceful) {
            system.destroy(graceful);
            note(graceful ? "despawned" : "despawned at once");
        }
    }

    /** The caster: at the origin facing +x, every socket at chest height; any slot taken and kept. */
    private static final class Caster extends Fixture implements CgSequenceRole {
        @Override
        public CgAttachment attachment(String socket) {
            return (point, turn) -> {
                point[0] = 0;
                point[1] = 1.4;
                point[2] = 0;
                if (turn != null) turn.identity().rotateY((float) (Math.PI / 2));
                return true;
            };
        }
    }

    /** A target taking any slot: what this scene stands in for. */
    private static class Fixture implements CgSequenceSpawned {
        private final List<String> slots = new ArrayList<>();
        private final List<float[]> values = new ArrayList<>();

        @Override
        public int slot(String name) {
            int i = slots.indexOf(name);
            if (i >= 0) return i;
            slots.add(name);
            values.add(new float[4]);
            return slots.size() - 1;
        }

        @Override
        public void set(int slot, float[] v, int mask) {
            float[] to = values.get(slot);
            for (int i = 0; i < 4 && i < v.length; i++) if ((mask & 1 << i) != 0) to[i] = v[i];
        }

        @Override
        public void get(int slot, float[] out) {
            System.arraycopy(values.get(slot), 0, out, 0, Math.min(4, out.length));
        }

        @Override
        public void destroy(boolean graceful) {
        }
    }

    @Override
    public void dispose() {
        vfx.delete();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return true; }
}
