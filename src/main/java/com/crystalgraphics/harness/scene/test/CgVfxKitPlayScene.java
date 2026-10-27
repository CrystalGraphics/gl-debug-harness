package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.texture.CgTextureAbstract;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;
import com.crystalgraphics.vfx.system.CgVfxSystemPreparer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The gate for a kit's systems played from its files by id: the kit scanned, the systems' bundle loaded to
 * {@code READY} (textures uploaded, looks prepared, {@link CgVfxSystemPreparer}), then each spawned once in a row along
 * x. Kakarot's Kamehameha by default, which {@link CgVfxKamehamehaScene} is for watching. Scene id {@code vfx-kit-play}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-kit-play --device=vulkan"
 * // another kit: its folder and namespace, and ids under its vfx/systems without .yaml
 * ... -Dcrystalgraphics.harness.kit=<folder> -Dcrystalgraphics.harness.kit.namespace=spzero_kit \
 *     -Dcrystalgraphics.harness.kit.systems=<id>,<id>
 * // each renderer's material parameters as bound, for comparing against a capture (HarnessMaterialDump)
 * ... -Dcrystalgraphics.harness.kit.dump=<file.tsv>
 * }</pre>
 *
 * <ul>
 *   <li>Prints {@code [vfx-kit-play] PASS} or {@code FAIL} after {@value #FRAMES} frames of play: the bundle {@code READY},
 *       no compile wait, sync load or texture load after it, each system holding particles in some frame, no Vulkan
 *       validation error. A system that never holds one is named.</li>
 *   <li>A kit's textures resolve through a resource root holding {@code assets/<namespace>}: the plan's
 *       {@code local/kitroot}, which the harness's build adds, or {@code -Pharness.assetRoots}.</li>
 * </ul>
 */
public final class CgVfxKitPlayScene implements InteractiveSceneLifecycle {

    private static final int FRAMES = 600;
    /** From the harness's working directory, {@code gl-debug-harness/}: the plan's built kits. */
    private static final String KITS = "../plan/crystalgraphics/vfx-aaa/local/examples/";
    /** Blocks between systems along x. */
    private static final float SPACING = 6f;

    private final CgVfxSystem vfx = new CgVfxSystem();
    private HarnessKitSystems kit;
    private CgVfxSystemInstance<?>[] playing;
    private int[] framesAlive;
    private boolean running = true, started;
    private int frames, loadFrames;
    private long waits, syncs, textures;

    @Override
    public void init(HarnessContext ctx) {
        String namespace = System.getProperty("crystalgraphics.harness.kit.namespace", "kakarot_kit");
        String folder = System.getProperty("crystalgraphics.harness.kit", KITS + namespace);
        String systems = System.getProperty("crystalgraphics.harness.kit.systems");
        List<String> ids = new ArrayList<>();
        if (systems == null) ids.addAll(CgVfxKamehamehaScene.systems());
        else for (String id : systems.split(",")) if (!id.isBlank()) ids.add(id.trim());
        ctx.getCamera3D().moveCamera(SPACING * (ids.size() - 1) / 2f, 6f, 28f);
        ctx.getCamera3D().setPitch(-10f);
        kit = HarnessKitSystems.load(vfx, namespace, Paths.get(folder), ids);
        playing = new CgVfxSystemInstance<?>[ids.size()];
        framesAlive = new int[ids.size()];
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (!started) {
            if (kit.failed()) {
                System.out.println("[vfx-kit-play] FAIL on " + device() + ": the bundle failed:\n  " + kit.failures());
                running = false;
                return;
            }
            loadFrames++;
            if (kit.ready()) {
                dump();
                waits = CgMaterialShader.compileWaits();
                syncs = CgVfxAssets.instance().syncLoads();
                textures = CgTextureAbstract.loads();
                for (int i = 0; i < kit.size(); i++) playing[i] = vfx.spawn(kit.ref(i), i * SPACING, 1.5, 0).ground(0f);
                started = true;
            }
        }
        vfx.update(frame.getElapsedTime());
        vfx.submit(CgWorldRenderer.get());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
        if (!started) return;
        for (int i = 0; i < playing.length; i++) {
            CgVfxSystemInstance<?> s = playing[i];
            for (int e = 0; e < s.emitters(); e++) {
                if (s.emitter(e).mayHaveParticles()) {
                    framesAlive[i]++;
                    break;
                }
            }
        }
        if (++frames == FRAMES) {
            report();
            running = false;
        }
    }

    private void report() {
        long compiles = CgMaterialShader.compileWaits() - waits, syncLoads = CgVfxAssets.instance().syncLoads() - syncs,
                textureLoads = CgTextureAbstract.loads() - textures;
        int validation = PlatformServiceHarness.validationErrors();
        List<String> silent = new ArrayList<>();
        for (int i = 0; i < kit.size(); i++) if (framesAlive[i] == 0) silent.add(kit.id(i));
        boolean pass = compiles == 0 && syncLoads == 0 && textureLoads == 0 && silent.isEmpty() && validation == 0;
        StringBuilder alive = new StringBuilder();
        for (int i = 0; i < kit.size(); i++) alive.append("\n  ").append(framesAlive[i]).append(" frames with particles: ").append(kit.id(i));
        System.out.println("[vfx-kit-play] " + (pass ? "PASS" : "FAIL") + " on " + device() + ": " + kit.size()
                + " systems ready after " + loadFrames + " frames; in the first " + FRAMES + " frames of play "
                + compiles + " compile waits, " + syncLoads + " sync loads, " + textureLoads + " texture loads"
                + (validation > 0 ? ", " + validation + " Vulkan validation errors" : "")
                + (silent.isEmpty() ? "" : "; never held a particle: " + silent) + alive);
    }

    /** Under {@code crystalgraphics.harness.kit.dump}, every renderer's material parameters to that file. */
    private void dump() {
        String out = System.getProperty("crystalgraphics.harness.kit.dump");
        if (out == null) return;
        List<String> ids = new ArrayList<>();
        List<CgVfxSystemDefinition> systems = new ArrayList<>();
        for (int i = 0; i < kit.size(); i++) {
            ids.add(kit.id(i));
            systems.add(kit.ref(i).loaded());
        }
        try {
            HarnessMaterialDump.write(Paths.get(out), ids, systems);
            System.out.println("[vfx-kit-play] material parameters written to " + Paths.get(out).toAbsolutePath());
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
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return true; }
}
