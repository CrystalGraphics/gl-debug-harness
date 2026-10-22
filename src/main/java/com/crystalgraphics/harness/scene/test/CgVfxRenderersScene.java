package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.trace.TraceReport;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRenderers;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;

/**
 * An emitter's renderers drawing from material definitions: a sprite, an arc, trails, a ribbon and a mesh material drawn
 * as sprites on five emitters, played {@link #EFFECTS} times. Each renderer must stay one merged draw of every instance (a particle set), and draw
 * each instance once. Scene id {@code vfx-renderers}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-renderers"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-renderers" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-renderers --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [vfx-renderers] PASS} or {@code FAIL} after {@link #WARM} frames, then exits.</p>
 */
public final class CgVfxRenderersScene implements InteractiveSceneLifecycle {

    private static final int EFFECTS = 100, WARM = 90, RENDERERS = 5;
    private static final String SPRITE = "crystalgraphics:shaders/vfx/particle/sprite.shader";
    private static final String ARC = "crystalgraphics:shaders/vfx/particle/arc.shader";
    private static final String STREAKS = "crystalgraphics:shaders/vfx/particle/streaks.shader";
    private static final String RIBBON = "crystalgraphics:shaders/vfx/particle/ribbon.shader";
    /** {@code #type spatial} under a sprite renderer: its program reads no vertex data, drawn on {@code CgMesh.quads}. */
    private static final String SPATIAL_SPRITE = "harness:shaders/vfx_spatial_sprite.shader";
    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("harness:vfx_renderers");
    private static final CgVfxParam.Color EMBER = SCHEMA.color("ember", 0xFFFF8040, 2f);

    private final CgVfxSystem vfx = new CgVfxSystem();
    private int frames;
    private boolean running = true;

    private static CgMaterialDefinition material(String id, String shader, CgMaterialUsage usage) {
        return CgMaterialDefinition.of("harness:materials/" + id, shader).blend(CgBlendMode.ADDITIVE).usage(usage).build();
    }

    private static CgVfxEmitter.Builder emitter(String name) {
        return CgVfxEmitter.builder(name).capacity(64).rate(30f).shape(CgSphere.of(0.3)).hemisphere().speed(0.5f, 1.5f)
                .life(1.5f, 2f).size(0.05f, 0.1f);
    }

    private static CgVfxLook look() {
        CgVfxEmitter sprites = emitter("sprites").renderer(CgVfxRenderers.sprite("sprite")
                .material(material("sprite", SPRITE, CgMaterialUsage.SPRITES)).bind("_Color", "user.ember").build()).build();
        CgVfxEmitter arcs = emitter("arcs").renderer(CgVfxRenderers.arc("arc").material(material("arc", ARC, CgMaterialUsage.SPRITES)).build()).build();
        CgVfxEmitter trails = emitter("trails").trail(8).renderer(CgVfxRenderers.trails("trails")
                .material(material("streaks", STREAKS, CgMaterialUsage.RIBBONS)).field("points", 8).build()).build();
        CgVfxEmitter ribbons = emitter("ribbons").renderer(CgVfxRenderers.ribbon("ribbon")
                .material(material("ribbon", RIBBON, CgMaterialUsage.RIBBONS)).build()).build();
        CgVfxEmitter meshSprites = emitter("meshSprites").renderer(CgVfxRenderers.sprite("meshSprite")
                .material(material("spatial_sprite", SPATIAL_SPRITE, CgMaterialUsage.SPRITES)).build()).build();
        return CgVfxLook.builder(SCHEMA).emitter(sprites).emitter(arcs).emitter(trails).emitter(ribbons)
                .emitter(meshSprites).build();
    }

    @Override
    public void init(HarnessContext ctx) {
        CgAssetKinds.register();
        CgTrace.enable("crystalgraphics");
        ctx.getCamera3D().moveCamera(0f, 3f, 24f);
        ctx.getCamera3D().setPitch(-8f);
        CgVfxLook look = look();
        for (int i = 0; i < EFFECTS; i++) {
            vfx.play(new CgVfxSystemInstance<>(look, (i % 10 - 4.5) * 2.0, 1.0, (i / 10 - 4.5) * 2.0)).ground(0f);
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        vfx.update(frame.getElapsedTime());
        vfx.submit(CgWorldRenderer.get());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
        if (++frames < WARM || !running) return;
        TraceReport report = TraceReport.lastFrame();
        long sets = report.counter("vfx.draws.particle-sets"), records = report.counter("vfx.draws.particle-gpu");
        String verdict = sets == RENDERERS && records == (long) RENDERERS * EFFECTS ? "PASS" : "FAIL";
        System.out.printf("[vfx-renderers] %s: %d renderers of %d effects drew %d particle sets of %d records"
                + " (want %d and %d)%n", verdict, RENDERERS, EFFECTS, sets, records, RENDERERS, RENDERERS * EFFECTS);
        running = false;
    }

    @Override
    public void dispose() {
        vfx.delete();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return true; }
}
