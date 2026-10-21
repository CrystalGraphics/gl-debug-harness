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
import java.util.List;

/**
 * A3's gate: the Nova Cannon played from its files. Its kit is scanned, its three systems' bundle loaded to
 * {@code READY} (each system's textures uploaded and its look prepared, {@link CgVfxSystemPreparer}), then each played
 * from its {@link CgAssetRef}. Over the next {@value #FRAMES} frames nothing may compile, sync-load or load a texture.
 * Scene id {@code vfx-nova}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-nova --device=vulkan"
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
    private static final String[] SYSTEMS = {"novamod:nova_cannon/charge", "novamod:nova_cannon/beam",
            "novamod:nova_cannon/impact"};

    private final CgVfxSystem vfx = new CgVfxSystem();
    private CgAssetRef<CgVfxSystemDefinition>[] refs;
    private CgLoadHandle handle;
    private boolean running = true, playing;
    private int frames, loadFrames;
    private long waits, syncs, textures, mostDrawn;

    @Override
    @SuppressWarnings("unchecked")
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(-6f, 4f, 12f);
        ctx.getCamera3D().setYaw(-25f);
        ctx.getCamera3D().setPitch(-10f);
        String kit = System.getProperty("crystalgraphics.harness.nova.kit", KIT);
        if (!Files.isDirectory(Paths.get(kit))) {
            throw new IllegalStateException("no Nova Cannon kit at " + Paths.get(kit).toAbsolutePath()
                    + ": check out plan/, or pass -Dcrystalgraphics.harness.nova.kit=<root>/assets/novamod");
        }
        CgVfxSystemAssets.register();
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.registerEngine();
        Path root = Paths.get(kit);
        registry.scan("novamod", root, CgAssetSource.DEV);
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
                vfx.play(new CgVfxSystemInstance<>(refs[0].get(), 0, 1.5, 0)).ground(0f);
                vfx.play(new CgVfxSystemInstance<>(refs[1].get(), 0, 1.5, 0)).ground(0f);
                vfx.play(new CgVfxSystemInstance<>(refs[2].get(), 0, 0.5, -20)).ground(0f);
                playing = true;
            }
        }
        vfx.update(frame.getElapsedTime());
        vfx.submit(CgWorldRenderer.get());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
        if (playing) mostDrawn = Math.max(mostDrawn, CgVfxSystem.particlesDrawn());
        if (playing && ++frames == FRAMES) {
            report(CgMaterialShader.compileWaits() - waits, CgVfxAssets.instance().syncLoads() - syncs,
                    CgTextureAbstract.loads() - textures);
            running = false;
        }
    }

    private void report(long compiles, long syncLoads, long textureLoads) {
        int validation = PlatformServiceHarness.validationErrors();
        boolean pass = compiles == 0 && syncLoads == 0 && textureLoads == 0 && mostDrawn > 0 && validation == 0;
        System.out.println("[vfx-nova] " + (pass ? "PASS" : "FAIL") + " on " + device() + ": ready after " + loadFrames
                + " frames; in the first " + FRAMES + " frames of play " + compiles + " compile waits, " + syncLoads
                + " sync loads, " + textureLoads + " texture loads, " + mostDrawn + " particles drawn at most"
                + (validation > 0 ? ", " + validation + " Vulkan validation errors" : ""));
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
