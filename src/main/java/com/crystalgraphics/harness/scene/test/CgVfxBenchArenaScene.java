package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.effect.CgVfxBurst;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The arena's frame-thread load: 8 moves playing at once, each a generated look of {@value #EMITTERS}
 * emitters over {@value #SHAPES} shapes and {@value #LAYERS} layers, their bursts staggered over the move so a few
 * dozen emitters are alive at a time, with 30 idle palettes prepared. What a frame costs is the books,
 * the ticks and the submit of the effects; the GPU work is the pools of the shapes alive. Scene id {@code bench-arena}.
 *
 * <pre>{@code
 * python gl-debug-harness/bench.py run bench-arena --cell gl --frames 600
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=bench-arena" -Dcrystalgraphics.harness.profile=600 \
 *     -Dcrystalgraphics.trace.channels=crystalgraphics,gpu
 * // fewer moves, a smaller capacity
 * ... -Dcrystalgraphics.harness.arena.moves=2 -Dcrystalgraphics.harness.arena.capacity=64
 * }</pre>
 *
 * <p>Shapes are module subsets of seven kinds; the first 106 subsets are used and the emitters past that
 * share a shape already used, so pools are shared as they are across effects. Every move replays when it has run out.</p>
 */
public final class CgVfxBenchArenaScene implements InteractiveSceneLifecycle {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.BenchArena");
    private static final String PREFIX = "crystalgraphics.harness.arena.";

    static final int MOVES = Integer.getInteger(PREFIX + "moves", 8);
    static final int EMITTERS = Integer.getInteger(PREFIX + "emitters", 180);
    static final int SHAPES = Integer.getInteger(PREFIX + "shapes", 106);
    static final int LAYERS = Integer.getInteger(PREFIX + "layers", 163);
    static final int PALETTES = Integer.getInteger(PREFIX + "palettes", 30);
    private static final int CAPACITY = Integer.getInteger(PREFIX + "capacity", 128);
    private static final float SPAN = Float.parseFloat(System.getProperty(PREFIX + "span", "15"));
    private static final float LIFE = 2f;
    private static final float PERIOD = SPAN + LIFE + 1f;

    private static final String[] SHADERS = {"spark", "speck", "dust", "ray", "ring", "sprite", "sprite_glow"};
    private static final CgBlendMode[] BLENDS = {CgBlendMode.ADDITIVE, CgBlendMode.PREMULTIPLIED, CgBlendMode.ALPHA};
    /** Shader x blend x sidedness: 42 programs, a layer taking the next in turn. */
    private static final int PROGRAMS = SHADERS.length * BLENDS.length * 2;

    private final CgVfxSystem vfx = new CgVfxSystem();
    private final List<CgVfxLook> palettes = new ArrayList<>();
    private float clock;
    private int[] plays;

    /** Seven module kinds; bit {@code k} of {@code subset} picks kind {@code k}: each subset a shape of its own. */
    private static void modules(CgVfxEmitter.Builder e, int subset) {
        if ((subset & 1) != 0) e.module(new CgVfxModule.Gravity(2f));
        if ((subset & 2) != 0) e.module(new CgVfxModule.Drag(0.4f, 0f));
        if ((subset & 4) != 0) e.module(new CgVfxModule.Turbulence(1f, 0.8f, 0.5f));
        if ((subset & 8) != 0) e.module(new CgVfxModule.Spin(0.5f));
        if ((subset & 16) != 0) e.module(new CgVfxModule.Damping(0.3f));
        if ((subset & 32) != 0) e.module(new CgVfxModule.LimitSpeed(3f));
        if ((subset & 64) != 0) e.module(new CgVfxModule.Wind(0.5f));
    }

    private static CgVfxLook look(int variant) {
        CgVfxLook.Builder look = CgVfxLook.builder(CgVfxBurst.SCHEMA);
        for (int i = 0; i < EMITTERS; i++) {
            int shape = 1 + (i + variant) % SHAPES;
            float at = SPAN * i / EMITTERS;
            CgVfxEmitter.Builder e = CgVfxEmitter.builder("e" + i).capacity(CAPACITY).state(CgVfxEmitterState.ONCE)
                    .burst(at, CAPACITY / 2).shape(CgSphere.of(0.4)).launch(0.2f, 1f, 1f).speed(0.5f, 1.5f)
                    .life(LIFE, LIFE).size(0.05f, 0.1f).spin(0.5f, 2f);
            modules(e, shape);
            CgVfxEmitter emitter = e.build();
            look.emitter(emitter);
            if (i < LAYERS) {
                int program = i % PROGRAMS;
                String shader = SHADERS[program % SHADERS.length];
                CgMaterialState.Builder state = CgMaterialState.builder().blend(BLENDS[program / SHADERS.length % BLENDS.length]);
                if (program >= PROGRAMS / 2) state.twoSided();
                look.layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/particle/" + shader + ".shader").on(emitter)
                        .state(state.build()).build());
            }
        }
        return look.build();
    }

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 6f, 24f);
        ctx.getCamera3D().setPitch(-12f);
        for (int p = 0; p < PALETTES; p++) palettes.add(look(p));
        vfx.prepare(palettes.toArray(new CgVfxLook[0]));
        plays = new int[MOVES];
        java.util.Arrays.fill(plays, -1);
        LOG.info("[bench-arena] {} moves, {} emitters over {} shapes, {} layers, capacity {}, {} palettes",
                MOVES, EMITTERS, SHAPES, LAYERS, CAPACITY, PALETTES);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        clock = (float) frame.getElapsedTime();
        for (int m = 0; m < MOVES; m++) {
            // Move m is m/MOVES of the way through its run when the scene opens, so all of them play from the first frame.
            float phase = PERIOD * m / MOVES;
            int round = (int) Math.floor((clock + phase) / PERIOD);
            if (round == plays[m]) continue;
            boolean first = plays[m] < 0;
            plays[m] = round;
            double x = (m - (MOVES - 1) / 2.0) * 3.0;
            CgVfxBurst burst = new CgVfxBurst(palettes.get((m + round) % palettes.size()), x, 1.0, 0.0);
            if (first && phase > 0f) burst.startAt(phase);
            vfx.play(burst).ground(0f);
        }
        vfx.update(frame.getElapsedTime());
        vfx.submit(CgWorldRenderer.get());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    @Override
    public void dispose() {
        vfx.delete();
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
