package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.texture.CgTextureAbstract;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxOwner;
import com.crystalgraphics.vfx.CgVfxRegistry;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRenderers;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;

/**
 * The gate for effects a mod only declares: a look registered in {@code CgVfxRegistry} and another handed to its owner's
 * {@code precache} from a worker thread, as a mod's init would, each sampling a texture; then a title screen's frames
 * with nothing asking {@code ready()}, and both played. Their first {@value #FRAMES} frames of play must add no
 * compile wait and no texture load. Scene id {@code vfx-declared}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-declared --rhi=vulkan"
 * ... -Dcrystalgraphics.harness.declared.optOut=true   // the control: both left to their first play, which must wait
 * }</pre>
 */
public final class CgVfxDeclaredScene implements InteractiveSceneLifecycle {

    private static final int WAIT = 300, FRAMES = 600;
    private static final boolean OPT_OUT = Boolean.getBoolean("crystalgraphics.harness.declared.optOut");
    private static final CgVfxOwner VFX = CgVfx.owner("harness");

    private CgVfxLook registered, owned;
    private CgVfxSystemInstance<?> a, b;
    private boolean running = true;
    private int frames;
    private long waits, textures;
    private int aliveA, aliveB;

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 3f, 12f);
        ctx.getCamera3D().setPitch(-10f);
        registered = look("registered", "harness:orange-piccolo-processed/1.png");
        owned = look("owned", "harness:orange-piccolo-processed/2.png");
        CgVfxRegistry.register("harness:declared", "Declared", "Gates", registered,
                (look, x, y, z, tx, ty, tz) -> new CgVfxSystemInstance<>(look, x, y, z)).precache(!OPT_OUT);
        if (!OPT_OUT) {
            Thread init = new Thread(() -> VFX.precache(owned), "mod-init");
            init.start();
            try {
                init.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A look of one sprite emitter whose material samples {@code texture}: a pipeline and a texture to warm. */
    private static CgVfxLook look(String name, String texture) {
        CgMaterialDefinition sprite = CgMaterialDefinition.of("harness:materials/declared_" + name,
                "crystalgraphics:shaders/vfx/particle/sprite.shader").usage(CgMaterialUsage.SPRITES)
                .texture("_Sheet", texture).build();
        CgVfxEmitter emitter = CgVfxEmitter.builder(name).capacity(64).rate(30f).speed(0.5f, 1f).life(1f, 1.5f)
                .size(0.3f, 0.5f).renderer(CgVfxRenderers.sprite(name).material(sprite).build()).build();
        return CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).emitter(emitter).build();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        frames++;
        if (frames == WAIT) {
            waits = CgMaterialShader.compileWaits();
            textures = CgTextureAbstract.loads();
            a = VFX.play(new CgVfxSystemInstance<>(registered, -2, 1.5, 0).ground(0f));
            b = VFX.play(new CgVfxSystemInstance<>(owned, 2, 1.5, 0).ground(0f));
        }
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
        if (frames < WAIT) return;
        if (a.emitter(0).mayHaveParticles()) aliveA++;
        if (b.emitter(0).mayHaveParticles()) aliveB++;
        if (frames == WAIT + FRAMES) {
            report();
            running = false;
        }
    }

    private void report() {
        long compiles = CgMaterialShader.compileWaits() - waits, loads = CgTextureAbstract.loads() - textures;
        int validation = PlatformServiceHarness.validationErrors();
        boolean pass = compiles == 0 && loads == 0 && aliveA > 0 && aliveB > 0 && validation == 0;
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        System.out.println("[vfx-declared] " + (pass ? "PASS" : "FAIL") + " on " + (device == null ? "gl" : device.name())
                + (OPT_OUT ? " (control: opted out)" : "") + ": in the first " + FRAMES + " frames of play " + compiles
                + " compile waits, " + loads + " texture loads; particles in " + aliveA + " and " + aliveB + " frames"
                + (validation > 0 ? ", " + validation + " Vulkan validation errors" : ""));
    }

    @Override
    public void dispose() {
        VFX.clear();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return true; }
}
