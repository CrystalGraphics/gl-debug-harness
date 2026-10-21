package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;

/**
 * The arena's frame-thread load: 8 moves playing at once, each a generated look of
 * emitters over shapes and layers, their bursts staggered over the move so a few
 * dozen emitters are alive at a time, with 30 idle palettes prepared. What a frame costs is the books,
 * the ticks and the submit of the effects; the GPU work is the pools of the shapes alive. Scene id {@code bench-arena}; {@code bench-duel} and {@code bench-brawl} are
 * presets of it: two 67-shape ultimates starting together, and the arena under 300 puffs a second.
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

    /** Seconds a move's particles live, and its pause before it replays. */
    private static final float LIFE = 2f;
    private static final int CAPACITY = Integer.getInteger(PREFIX + "capacity", 128);

    private static final String[] SHADERS = {"spark", "speck", "dust", "ray", "ring", "sprite", "sprite_glow"};
    private static final CgBlendMode[] BLENDS = {CgBlendMode.ADDITIVE, CgBlendMode.PREMULTIPLIED, CgBlendMode.ALPHA};
    /** Shader x blend x sidedness: 42 programs, a layer taking the next in turn. */
    private static final int PROGRAMS = SHADERS.length * BLENDS.length * 2;

    private final int moves, emitters, shapes, layers, palettesCount, bursts;
    private final float span, period;
    private final boolean stagger;
    private final CgVfxSystem vfx = new CgVfxSystem();
    private CgVfxLook puff;
    private float owed, lastClock;
    private int puffs;
    private final List<CgVfxLook> palettes = new ArrayList<>();
    private float clock;
    private int[] plays;

    /** The arena: 8 moves of 180 emitters over 106 shapes, staggered, 30 palettes prepared. */
    public CgVfxBenchArenaScene() {
        this(8, 180, 106, 163, 30, 15f, true, 0);
    }

    /**
     * @param stagger whether the moves start part-way through their run, or all together at its first beat
     * @param bursts  one-off puffs played a second on top of the moves
     */
    CgVfxBenchArenaScene(int moves, int emitters, int shapes, int layers, int palettes, float span, boolean stagger,
                         int bursts) {
        this.moves = Integer.getInteger(PREFIX + "moves", moves);
        this.emitters = Integer.getInteger(PREFIX + "emitters", emitters);
        this.shapes = Integer.getInteger(PREFIX + "shapes", shapes);
        this.layers = Integer.getInteger(PREFIX + "layers", layers);
        this.palettesCount = Integer.getInteger(PREFIX + "palettes", palettes);
        this.span = Float.parseFloat(System.getProperty(PREFIX + "span", Float.toString(span)));
        this.period = this.span + LIFE + 1f;
        this.stagger = stagger;
        this.bursts = Integer.getInteger(PREFIX + "bursts", bursts);
    }

    /** Two ultimates of the heaviest shape count (67 shapes alive at once), starting together. */
    public static CgVfxBenchArenaScene duel() {
        return new CgVfxBenchArenaScene(2, 67, 67, 67, 4, 0.1f, false, 0);
    }

    /** The arena with 300 one-off puffs a second on top. */
    public static CgVfxBenchArenaScene brawl() {
        return new CgVfxBenchArenaScene(8, 180, 106, 163, 30, 15f, true, 300);
    }

    /** One burst of 16 sparks: what a hit throws. */
    private static CgVfxLook puffLook() {
        CgVfxEmitter spark = CgVfxEmitter.builder("puff").capacity(32).state(CgVfxEmitterState.ONCE).burst(0f, 16)
                .shape(CgSphere.of(0.2)).launch(0.2f, 1f, 1f).speed(1f, 3f).life(0.6f, 0.9f).size(0.05f, 0.1f)
                .module(new CgVfxModule.Gravity(4f)).build();
        return CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).emitter(spark)
                .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/particle/spark.shader").on(spark)
                        .state(CgMaterialState.builder().blend(CgBlendMode.ADDITIVE).build()).build())
                .build();
    }

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

    private CgVfxLook look(int variant) {
        CgVfxLook.Builder look = CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA);
        for (int i = 0; i < emitters; i++) {
            int shape = 1 + (i + variant) % shapes;
            float at = span * i / emitters;
            CgVfxEmitter.Builder e = CgVfxEmitter.builder("e" + i).capacity(CAPACITY).state(CgVfxEmitterState.ONCE)
                    .burst(at, CAPACITY / 2).shape(CgSphere.of(0.4)).launch(0.2f, 1f, 1f).speed(0.5f, 1.5f)
                    .life(LIFE, LIFE).size(0.05f, 0.1f).spin(0.5f, 2f);
            modules(e, shape);
            CgVfxEmitter emitter = e.build();
            look.emitter(emitter);
            if (i < layers) {
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
        for (int p = 0; p < palettesCount; p++) palettes.add(look(p));
        vfx.prepare(palettes.toArray(new CgVfxLook[0]));
        if (bursts > 0) {
            puff = puffLook();
            vfx.prepare(puff);
        }
        plays = new int[moves];
        java.util.Arrays.fill(plays, -1);
        LOG.info("[bench-arena] {} moves, {} emitters over {} shapes, {} layers, capacity {}, {} palettes",
                moves, emitters, shapes, layers, CAPACITY, palettesCount);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        clock = (float) frame.getElapsedTime();
        for (int m = 0; m < moves; m++) {
            // Move m is m/moves of the way through its run when the scene opens, so all of them play from the first frame.
            float phase = stagger ? period * m / moves : 0f;
            int round = (int) Math.floor((clock + phase) / period);
            if (round == plays[m]) continue;
            boolean first = plays[m] < 0;
            plays[m] = round;
            double x = (m - (moves - 1) / 2.0) * 3.0;
            CgVfxSystemInstance<?> burst = new CgVfxSystemInstance<>(palettes.get((m + round) % palettes.size()), x, 1.0, 0.0);
            if (first && phase > 0f) burst.startAt(phase);
            vfx.play(burst).ground(0f);
        }
        if (bursts > 0) {
            owed += bursts * (clock - lastClock);
            lastClock = clock;
            for (; owed >= 1f; owed -= 1f) {
                double angle = puffs++ * 2.399963;   // golden angle: spread without a random source
                vfx.play(new CgVfxSystemInstance<>(puff, Math.cos(angle) * 10.0, 1.0, Math.sin(angle) * 4.0)).ground(0f);
            }
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
