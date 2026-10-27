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
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.system.CgVfxSystemAssets;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;
import com.crystalgraphics.vfx.system.CgVfxSystemPreparer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * A kit's systems played from its files by id: the kit scanned, the systems' bundle loaded to {@code READY} (textures
 * uploaded, looks prepared, {@link CgVfxSystemPreparer}), then each spawned once in a row along x. Scene id
 * {@code vfx-kit-play}; {@link CgVfxNovaScene} generalised to any kit.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-kit-play --device=vulkan" \
 *     -Dcrystalgraphics.harness.kit=<root>/assets/kakarot_kit -Dcrystalgraphics.harness.kit.namespace=kakarot_kit \
 *     -Pharness.assetRoots=<root>
 * // other systems than Kakarot's Kamehameha, the default: ids under the kit's vfx/systems, without .yaml
 * ... -Dcrystalgraphics.harness.kit.systems=Art/EFF/e/skill/a/Cpl001/Particles/PS_skill_cpl001_kameha_shot_01_00,...
 * // replay each system as it ends, until the window closes, to judge against the game
 * ... -Dcrystalgraphics.harness.kit.hold=true
 * }</pre>
 *
 * <ul>
 *   <li>Prints {@code [vfx-kit-play] PASS} or {@code FAIL} after {@value #FRAMES} frames of play: the bundle {@code READY},
 *       no compile wait, sync load or texture load after it, each system holding particles in some frame, no Vulkan
 *       validation error. A system that never holds one is named.</li>
 *   <li>The kit's textures resolve through a resource root holding {@code assets/<namespace>}: pass it as
 *       {@code -Pharness.assetRoots}.</li>
 *   <li>Each system plays with its user parameters at their defaults, at its own origin: a beam's far end where its
 *       system leaves it.</li>
 * </ul>
 */
public final class CgVfxKitPlayScene implements InteractiveSceneLifecycle {

    private static final int FRAMES = 600;
    private static final boolean HOLD = Boolean.getBoolean("crystalgraphics.harness.kit.hold");
    /** Kakarot's Kamehameha, its charge (AM_C001A_S_SKL001_01) and its shot (_02). */
    private static final String KAMEHAMEHA = String.join(",",
            "Art/EFF/e/skill/a/cmn/Particles/PS_skill_cmn_start_01_00",
            "Art/EFF/e/skill/a/Cpl001/Particles/PS_skill_Cpl001_kameha_s_charge_01_00",
            "Art/EFF/e/skill/a/Cpl001/Particles/PS_skill_Cpl001_kameha_s_charge_01_00_lp",
            "Art/EFF/e/skill/a/Cpl001/Particles/PS_skill_cpl001_kameha_shot_01_00",
            "Art/EFF/e/skill/a/Cpl001/Particles/PS_skill_cpl001_kameha_bullet_01_00_ST",
            "Art/EFF/e/skill/a/Cpl001/Particles/PS_skill_cpl001_kameha_bullet_01_00_LP",
            "Art/EFF/e/skill/a/Cpl001/Particles/PS_skill_cpl001_kameha_bullet_01_00_ED",
            "Art/EFF/e/cmn/Particles/PS_cmn_hit_beem_s_01_00");
    /** Blocks between systems along x. */
    private static final float SPACING = 6f;

    private final CgVfxSystem vfx = new CgVfxSystem();
    private final List<String> ids = new ArrayList<>();
    private CgAssetRef<CgVfxSystemDefinition>[] refs;
    private CgVfxSystemInstance<?>[] playing;
    private int[] framesAlive;
    private CgLoadHandle handle;
    private boolean running = true, started;
    private int frames, loadFrames;
    private long waits, syncs, textures;

    @Override
    @SuppressWarnings("unchecked")
    public void init(HarnessContext ctx) {
        String namespace = System.getProperty("crystalgraphics.harness.kit.namespace", "kakarot_kit");
        String folder = System.getProperty("crystalgraphics.harness.kit");
        if (folder == null) throw new IllegalStateException("no kit: pass -Dcrystalgraphics.harness.kit=<root>/assets/<namespace>");
        Path kit = Paths.get(folder);
        if (!Files.isDirectory(kit)) throw new IllegalStateException("no kit at " + kit.toAbsolutePath());
        for (String id : System.getProperty("crystalgraphics.harness.kit.systems", KAMEHAMEHA).split(",")) {
            if (!id.isBlank()) ids.add(id.trim());
        }
        ctx.getCamera3D().moveCamera(SPACING * (ids.size() - 1) / 2f, 6f, 28f);
        ctx.getCamera3D().setPitch(-10f);
        CgVfxSystemAssets.register();
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.registerEngine();
        registry.scan(namespace, kit, CgAssetSource.DEV);
        CgVfxAssets assets = new CgVfxAssets(registry);
        assets.preparer(CgParticleKinds.SYSTEM, new CgVfxSystemPreparer(() -> vfx));
        CgVfxAssets.install(assets);
        refs = new CgAssetRef[ids.size()];
        for (int i = 0; i < refs.length; i++) refs[i] = assets.declare(CgParticleKinds.SYSTEM, namespace + ":" + ids.get(i));
        playing = new CgVfxSystemInstance<?>[refs.length];
        framesAlive = new int[refs.length];
        handle = assets.load(List.of(refs), CgLoadPriority.MOVE);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (!started) {
            if (handle.failed()) {
                StringBuilder why = new StringBuilder();
                for (CgAssetRef<?> r : refs) if (r.failure() != null) why.append("\n  ").append(r.failure());
                System.out.println("[vfx-kit-play] FAIL on " + device() + ": the bundle failed:" + why);
                running = false;
                return;
            }
            loadFrames++;
            if (handle.ready()) {
                waits = CgMaterialShader.compileWaits();
                syncs = CgVfxAssets.instance().syncLoads();
                textures = CgTextureAbstract.loads();
                for (int i = 0; i < refs.length; i++) spawn(i);
                started = true;
            }
        }
        vfx.update(frame.getElapsedTime());
        vfx.submit(CgWorldRenderer.get());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
        if (!started) return;
        for (int i = 0; i < playing.length; i++) {
            CgVfxSystemInstance<?> s = playing[i];
            if (s == null) continue;
            for (int e = 0; e < s.emitters(); e++) {
                if (s.emitter(e).mayHaveParticles()) {
                    framesAlive[i]++;
                    break;
                }
            }
            if (HOLD && s.state() == CgVfxSystemInstance.State.DEAD) spawn(i);
        }
        if (++frames == FRAMES && !HOLD) {
            report();
            running = false;
        }
    }

    private void spawn(int i) {
        playing[i] = vfx.spawn(refs[i], i * SPACING, 1.5, 0).ground(0f);
    }

    private void report() {
        long compiles = CgMaterialShader.compileWaits() - waits, syncLoads = CgVfxAssets.instance().syncLoads() - syncs,
                textureLoads = CgTextureAbstract.loads() - textures;
        int validation = PlatformServiceHarness.validationErrors();
        List<String> silent = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) if (framesAlive[i] == 0) silent.add(ids.get(i));
        boolean pass = compiles == 0 && syncLoads == 0 && textureLoads == 0 && silent.isEmpty() && validation == 0;
        StringBuilder alive = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            alive.append("\n  ").append(framesAlive[i]).append(" frames with particles: ").append(ids.get(i));
        }
        System.out.println("[vfx-kit-play] " + (pass ? "PASS" : "FAIL") + " on " + device() + ": " + ids.size()
                + " systems ready after " + loadFrames + " frames; in the first " + FRAMES + " frames of play "
                + compiles + " compile waits, " + syncLoads + " sync loads, " + textureLoads + " texture loads"
                + (validation > 0 ? ", " + validation + " Vulkan validation errors" : "")
                + (silent.isEmpty() ? "" : "; never held a particle: " + silent) + alive);
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
