package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgLoadHandle;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.registry.CgAssetSource;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.texture.CgTextureAbstract;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.system.CgVfxSystemAssets;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;
import com.crystalgraphics.vfx.system.CgVfxSystemPreparer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The Nova Cannon played from its files. Its kit is scanned, its three systems' bundle loaded to {@code READY} (each
 * system's textures uploaded and its look prepared, {@link CgVfxSystemPreparer}), then each played from its
 * {@link CgAssetRef}. Scene id {@code vfx-nova}.
 *
 * <ul>
 *   <li><b>A3, played</b> (the default): over the next {@value #FRAMES} frames nothing may compile, sync-load or load a
 *       texture.</li>
 *   <li><b>A6, hot reload</b> ({@code -Dcrystalgraphics.harness.nova.reload=true}): the kit is played from a copy, and
 *       while the charge plays its file is saved twice, each save announced as {@code CgHotReload} announces one
 *       ({@link CgAssetReloader#changed}). A value (the gather's spawn rate) must reach the playing charge with its
 *       emitters kept; a structure (a drag module added) must restart it at its age, its emitters made anew.</li>
 * </ul>
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-nova --device=vulkan"
 * ... -Dcrystalgraphics.harness.nova.reload=true
 * // a kit elsewhere: its namespace folder, and the resource root holding it
 * ... -Dcrystalgraphics.harness.nova.kit=<root>/assets/novamod -Pharness.assetRoots=<root>
 * }</pre>
 *
 * <ul>
 *   <li>The kit is the private plan repository's, {@code plan/crystalgraphics/vfx-aaa/examples/novamod}, a resource root
 *       the harness's build adds when it is checked out, so its textures resolve as {@code novamod:textures/...}.</li>
 *   <li>Prints {@code [vfx-nova] PASS} or {@code FAIL}; run it on {@code gl} and {@code vulkan}.</li>
 * </ul>
 */
public final class CgVfxNovaScene implements InteractiveSceneLifecycle {

    private static final int FRAMES = 600;
    /** From the harness's working directory, {@code gl-debug-harness/}. */
    private static final String KIT = "../plan/crystalgraphics/vfx-aaa/examples/novamod/assets/novamod";
    private static final boolean RELOAD = Boolean.getBoolean("crystalgraphics.harness.nova.reload");
    private static final String[] SYSTEMS = {"novamod:nova_cannon/charge", "novamod:nova_cannon/beam",
            "novamod:nova_cannon/impact"};
    private static final String CHARGE_FILE = "novamod:vfx/systems/nova_cannon/charge.yaml";
    /** Frames of play before each save, and the most a save may take to reach the charge. */
    private static final int SETTLE = 120, REACH = 300;

    private final CgVfxSystem vfx = new CgVfxSystem();
    private CgAssetRef<CgVfxSystemDefinition>[] refs;
    private CgLoadHandle handle;
    private boolean running = true, playing;
    private int frames, loadFrames;
    private long waits, syncs, textures, mostDrawn;
    /** Frames the charge's books held particles: what plays, wherever the camera points. */
    private int framesAlive;
    /** The reload: the kit's copy, the charge, and each save's phase. */
    private Path copy;
    private CgVfxSystemInstance<?> charge;
    private Reload phase = Reload.WAIT_VALUE;
    private int savedAt, valueFrames;
    private CgVfxSystemDefinition before;
    private CgVfxEmitterInstance gatherBefore;
    private float ageBefore;

    private enum Reload { WAIT_VALUE, VALUE, WAIT_STRUCTURE, STRUCTURE }

    @Override
    @SuppressWarnings("unchecked")
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(-6f, 4f, 12f);
        ctx.getCamera3D().setYaw(-25f);
        ctx.getCamera3D().setPitch(-10f);
        Path kit = Paths.get(System.getProperty("crystalgraphics.harness.nova.kit", KIT));
        if (!Files.isDirectory(kit)) {
            throw new IllegalStateException("no Nova Cannon kit at " + kit.toAbsolutePath()
                    + ": check out plan/, or pass -Dcrystalgraphics.harness.nova.kit=<root>/assets/novamod");
        }
        if (RELOAD) kit = copy = copyOf(kit);   // saved into, never the kit itself
        CgVfxSystemAssets.register();
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.registerEngine();
        registry.scan("novamod", kit, CgAssetSource.DEV);
        CgVfxAssets assets = new CgVfxAssets(registry);
        assets.preparer(CgParticleKinds.SYSTEM, new CgVfxSystemPreparer(() -> vfx));
        CgVfxAssets.install(assets);   // the frame boundary drains and publishes the process's assets
        refs = new CgAssetRef[SYSTEMS.length];
        for (int i = 0; i < SYSTEMS.length; i++) refs[i] = assets.declare(CgParticleKinds.SYSTEM, SYSTEMS[i]);
        handle = assets.load(List.of(refs), CgLoadPriority.MOVE);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (!playing) {
            if (handle.failed()) {
                StringBuilder why = new StringBuilder();
                for (CgAssetRef<?> r : refs) if (r.failure() != null) why.append(' ').append(r.failure());
                System.out.println("[vfx-nova] FAIL on " + device() + ": the bundle failed:" + why);
                running = false;
                return;
            }
            loadFrames++;
            if (handle.ready()) {
                waits = CgMaterialShader.compileWaits();
                syncs = CgVfxAssets.instance().syncLoads();
                textures = CgTextureAbstract.loads();
                charge = vfx.play(new CgVfxSystemInstance<>(refs[0], 0, 1.5, 0)).ground(0f);
                if (!RELOAD) {
                    vfx.play(new CgVfxSystemInstance<>(refs[1], 0, 1.5, 0)).ground(0f);
                    vfx.play(new CgVfxSystemInstance<>(refs[2], 0, 0.5, -20)).ground(0f);
                }
                playing = true;
            }
        }
        vfx.update(frame.getElapsedTime());
        vfx.submit(CgWorldRenderer.get());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
        if (!playing) return;
        mostDrawn = Math.max(mostDrawn, CgVfxSystem.particlesDrawn());
        for (int i = 0; i < charge.emitters(); i++) {
            if (charge.emitter(i).mayHaveParticles()) {
                framesAlive++;
                break;
            }
        }
        frames++;
        if (RELOAD) {
            reload();
        } else if (frames == FRAMES) {
            report(CgMaterialShader.compileWaits() - waits, CgVfxAssets.instance().syncLoads() - syncs,
                    CgTextureAbstract.loads() - textures);
            running = false;
        }
    }

    /** Saves the value, then the structure, each once the charge has taken the last; fails one that never arrives. */
    private void reload() {
        switch (phase) {
            case WAIT_VALUE -> {
                if (frames < SETTLE) return;
                save("{ rate: 80 }", "{ rate: 160 }");
                phase = Reload.VALUE;
            }
            case VALUE -> {
                if (charge.definition() == before) {
                    timeout("the spawn rate's save");
                    return;
                }
                valueFrames = frames - savedAt;
                if (charge.emitter("gather") != gatherBefore) {
                    fail("a value change restarted the charge: its emitters were made anew");
                } else if (charge.emitter("gather").emitter() != charge.definition().emitters().get(1)) {
                    fail("the charge kept its emitters but not the new definition's numbers");
                } else {
                    phase = Reload.WAIT_STRUCTURE;
                    savedAt = frames;
                }
            }
            case WAIT_STRUCTURE -> {
                if (frames - savedAt < SETTLE) return;
                save("      renderers:\n        - { id: h2r1", "      particle_update:\n"
                        + "        - { id: h2u1, module: crystalgraphics:drag, inputs: { drag: 2 } }\n"
                        + "      renderers:\n        - { id: h2r1");
                phase = Reload.STRUCTURE;
            }
            case STRUCTURE -> {
                if (charge.definition() == before) {
                    timeout("the drag module's save");
                    return;
                }
                if (charge.emitter("gather") == gatherBefore) {
                    fail("a structural change kept the charge's emitters: it was not restarted");
                } else if (charge.age() < ageBefore) {
                    fail("the restarted charge is at " + charge.age() + " s, before its age " + ageBefore + " s");
                } else {
                    int validation = PlatformServiceHarness.validationErrors();
                    System.out.println("[vfx-nova] reload " + (validation == 0 ? "PASS" : "FAIL") + " on " + device()
                            + ": the spawn rate reached the playing charge " + valueFrames + " frames after its save,"
                            + " its emitters kept; the drag module restarted it " + (frames - savedAt)
                            + " frames after its save, at " + charge.age() + " s (it was " + ageBefore + " s)"
                            + (validation > 0 ? ", " + validation + " Vulkan validation errors" : ""));
                    running = false;
                }
            }
        }
    }

    /** Replaces {@code from} with {@code to} in the copy's charge file and announces the save. */
    private void save(String from, String to) {
        Path file = copy.resolve("vfx/systems/nova_cannon/charge.yaml");
        try {
            String text = Files.readString(file).replace("\r\n", "\n");
            if (!text.contains(from)) throw new IllegalStateException("the charge file has no " + from);
            Files.writeString(file, text.replace(from, to), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        before = charge.definition();
        gatherBefore = charge.emitter("gather");
        ageBefore = charge.age();
        savedAt = frames;
        CgAssetReloader.changed(CHARGE_FILE);
    }

    private void timeout(String what) {
        if (frames - savedAt > REACH) fail(what + " never reached the playing charge in " + REACH + " frames");
    }

    private void fail(String why) {
        System.out.println("[vfx-nova] reload FAIL on " + device() + ": " + why);
        running = false;
    }

    private void report(long compiles, long syncLoads, long textureLoads) {
        int validation = PlatformServiceHarness.validationErrors();
        // The charge loops for ever: its books hold particles nearly every frame. What the camera kept to draw is a
        // readout only, since live input turns the camera.
        boolean pass = compiles == 0 && syncLoads == 0 && textureLoads == 0 && framesAlive > FRAMES / 2 && validation == 0;
        System.out.println("[vfx-nova] " + (pass ? "PASS" : "FAIL") + " on " + device() + ": ready after " + loadFrames
                + " frames; in the first " + FRAMES + " frames of play " + compiles + " compile waits, " + syncLoads
                + " sync loads, " + textureLoads + " texture loads; the charge held particles " + framesAlive + " frames, "
                + mostDrawn + " particles drawn at most"
                + (validation > 0 ? ", " + validation + " Vulkan validation errors" : ""));
    }

    /** {@code kit} copied into a temporary resource root, as {@code assets/<its name>}: what hot reload matches saves by. */
    private static Path copyOf(Path kit) {
        try {
            Path to = Files.createTempDirectory("vfx-nova").resolve("assets").resolve(kit.getFileName().toString());
            try (Stream<Path> files = Files.walk(kit)) {
                for (Path from : (Iterable<Path>) files::iterator) {
                    Path into = to.resolve(kit.relativize(from).toString());
                    if (Files.isDirectory(from)) Files.createDirectories(into);
                    else Files.copy(from, into);
                }
            }
            return to;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String device() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        return device == null ? "gl" : device.name();
    }

    @Override
    public void dispose() {
        vfx.delete();
        if (copy == null) return;
        try (Stream<Path> files = Files.walk(copy.getParent().getParent())) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // a temporary folder: the OS clears what is left
        }
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return true; }
}
