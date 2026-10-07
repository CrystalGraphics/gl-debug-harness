package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.demo.CgVfxShowcase;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessFontUtil;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxField;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.CgVfxModule.Volume;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * X6's module catalogue, a station each on the showcase's floor, to fly round and compare: attraction with orbit,
 * a vortex, a vector field, conform to sphere; colliders (a sphere with collision events, a container, a slope, a block);
 * damping beside drag, a speed limit; flipbook sprites, and the five facing modes. Every station replays every
 * {@link #PERIOD} seconds, so V (the simulation on the CPU or the GPU) reaches it within one. Scene id
 * {@code vfx-modules}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-modules"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-modules --device=vulkan"
 * // V: CPU or GPU simulation; the stations and where they stand are logged at the start
 * }</pre>
 */
public final class CgVfxModulesScene implements InteractiveSceneLifecycle {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.VfxModules");
    private static final String PARTICLE = "crystalgraphics:shaders/vfx/particle/";
    private static final float PERIOD = 7f, FOREVER = 1.0e6f;
    /** The station grid: columns along x, rows along z, toward the camera. */
    private static final float[] COLUMNS = {-21f, -7f, 7f, 21f}, ROWS = {-14f, 0f, 14f};

    private static final CgVfxSchema SCHEMA = new CgVfxSchema();
    private static final CgVfxParam GOLD = SCHEMA.color("gold", 1f, 0.55f, 0.12f, 1f);
    private static final CgVfxParam WHITE = SCHEMA.color("white", 1f, 1f, 1f, 1f);
    private static final CgVfxParam CYAN = SCHEMA.color("cyan", 0.15f, 0.7f, 1.2f, 1f);
    private static final CgVfxParam VIOLET = SCHEMA.color("violet", 0.7f, 0.25f, 1.3f, 1f);
    private static final CgVfxParam SMOKE = SCHEMA.color("smoke", 0.55f, 0.55f, 0.6f, 0.8f);
    private static final CgVfxParam EMBER = SCHEMA.color("ember", 1.2f, 0.35f, 0.05f, 1f);
    private static final CgVfxParam FLAME = SCHEMA.color("flame", 1.4f, 1.1f, 0.5f, 1f);
    private static final CgVfxParam DEBRIS = SCHEMA.color("debris", 0.12f, 0.1f, 0.09f, 1f);
    private static final CgVfxParam ARROW = SCHEMA.color("arrow", 0.9f, 0.95f, 1f, 1f);

    private static final CgKeyframes FADE = CgKeyframes.start(0f, 0f).to(0.1f, 1f, CgEasings.OUT_QUAD)
            .to(0.7f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.IN_QUAD).build();

    /** One station: its emitters started at their sources, its look, and what it stands beside. */
    private record Station(String name, float x, float z, CgVfxLook look, List<CgVfxEmitter> emitters, float[][] sources,
                           Consumer<Props> props) {
    }

    /** What a station draws besides its particles: a collider's shape. */
    private interface Props {
        void sphere(float x, float y, float z, float radius);

        void box(float x, float y, float z, Matrix4f transform);
    }

    private final CgVfxShowcase stage = new CgVfxShowcase();
    private final CgVfxSystem vfx = new CgVfxSystem();
    private final List<Station> stations = new ArrayList<>();
    private final Matrix4f transform = new Matrix4f();
    private float nextPlay;
    private CgMesh sphere, cube;
    private CgMaterial mercury, copper;
    private CgWorldRenderer world;
    private CgFont font;

    /** A station playing: its emitters ticked until the period ends and they have all finished. */
    private static final class Play extends CgVfxEffect {
        private final List<CgVfxEmitterInstance> emitters = new ArrayList<>();

        Play(Station station) {
            super(station.look(), station.x(), 0.0, station.z());
            for (int i = 0; i < station.emitters().size(); i++) {
                CgVfxEmitterInstance emitter = new CgVfxEmitterInstance(station.emitters().get(i), seed + i * 0.137f);
                float[] s = station.sources()[i];
                emitter.start(s[0], s[1], s[2]);
                emitter.ground(0f);
                emitters.add(emitter);
            }
        }

        @Override
        protected void tick(float dt) {
            boolean done = true;
            for (int i = 0; i < emitters.size(); i++) {
                tick(emitters.get(i), dt);
                done &= emitters.get(i).finished();
            }
            if (done && age > 1f) die();
        }

        @Override
        protected void submit(CgVfxFrame frame) {
            for (int i = 0; i < emitters.size(); i++) frame.particles(this, emitters.get(i));
        }
    }

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 9f, 34f);
        ctx.getCamera3D().setPitch(-14f);
        ctx.getCamera3D().setMoveSpeed(8f);
        font = CgFont.load(HarnessFontUtil.LATIN_FONT, CgFontStyle.REGULAR, 48);
        sphere = CgMeshShapes.sphere(40, 80);
        cube = CgMeshShapes.cube();
        mercury = CgMaterial.load("crystalgraphics:shaders/demo/vfx_mercury.shader");
        copper = CgMaterial.load("crystalgraphics:shaders/demo/vfx_copper.shader");
        CgTexture smokeSheet = sheet(8, 8, 64, CgVfxModulesScene::smokeFrame);
        CgTexture fireSheet = sheet(4, 4, 64, CgVfxModulesScene::fireFrame);
        CgTexture dot = sheet(1, 1, 64, (f, u, v, out) -> dot(u, v, out));
        CgTexture arrow = sheet(1, 1, 64, (f, u, v, out) -> arrow(u, v, out));

        gather(COLUMNS[0], ROWS[0]);
        tornado(COLUMNS[1], ROWS[0], smokeSheet);
        field(COLUMNS[2], ROWS[0]);
        shield(COLUMNS[3], ROWS[0], dot);
        boulder(COLUMNS[0], ROWS[1]);
        cage(COLUMNS[1], ROWS[1]);
        slope(COLUMNS[2], ROWS[1]);
        block(COLUMNS[3], ROWS[1]);
        dampingAndDrag(COLUMNS[0], ROWS[2]);
        limit(COLUMNS[1], ROWS[2]);
        flipbooks(COLUMNS[2], ROWS[2], smokeSheet, fireSheet);
        facings(COLUMNS[3], ROWS[2], arrow);
        for (Station s : stations) LOG.info("[vfx-modules] {} at x {}, z {}", s.name(), s.x(), s.z());
        LOG.info("[vfx-modules] every station replays every {}s; V switches the simulation (CPU or GPU) from the next", PERIOD);
    }

    // ── stations ──────────────────────────────────────────────────────────────

    /** Godot's sphere attractor and orbit velocity, killed at the centre: energy gathering in a hand. */
    private void gather(float x, float z) {
        CgVfxEmitter motes = CgVfxEmitter.builder("gather").renderer(CgVfxEmitter.Renderer.QUADS).capacity(2000)
                .rate(320f, 0f, PERIOD - 3f).shape(6f).launch(-0.3f, 0.5f, 1f).speed(0.2f, 0.8f).life(3f, 4f)
                .size(0.05f, 0.14f, 1.5f).heat(1f)
                .module(new CgVfxModule.Attract(Volume.sphere(9f), 22f).attenuation(0.4f))
                .module(new CgVfxModule.Drag(1.4f, 0f))
                .module(new CgVfxModule.Orbit(0.45f, 0f, 1f, 0f, 0f, 0f, 0f))
                .module(new CgVfxModule.Kill(Volume.sphere(0.35f), true))
                .opacity(FADE).build();
        add("gather: Attract + Orbit + Kill", x, z, List.of(motes), new float[][]{{0f, 3f, 0f}},
                layer("spark", motes, GOLD, WHITE, 1f, null), null);
    }

    /** bevy_hanabi's tangent and radial accelerations round a centre over the ground: a funnel of smoke. */
    private void tornado(float x, float z, CgTexture smokeSheet) {
        CgVfxEmitter puffs = CgVfxEmitter.builder("tornado").renderer(CgVfxEmitter.Renderer.QUADS).capacity(1500)
                .rate(220f, 0f, PERIOD - 3f).shape(2.5f).launch(0f, 0.3f, 1f).speed(0.5f, 2f).life(2.5f, 3.5f)
                .size(0.5f, 1f, 1f).spin(0.3f, 1.2f)
                .module(new CgVfxModule.Vortex(0f, 1f, 0f, 16f, -4f, 0f, 4f, 0f))
                .module(new CgVfxModule.Force(0f, 2.5f, 0f))
                .module(new CgVfxModule.Drag(1.1f, 0f))
                .opacity(FADE).build();
        add("tornado: Vortex + Force", x, z, List.of(puffs), new float[][]{{0f, 0.3f, 0f}},
                layer("sprite", puffs, SMOKE, SMOKE, 1f,
                        b -> b.sampler("_Sheet", 0, smokeSheet).vec4("_Frames", 8f, 8f, 64f, 1f).vec4("_Flip", 0.3f, 0f, 1f, 0f)),
                null);
    }

    /** Godot's vector field attractor: a swirl in a box carries what rises into it. */
    private void field(float x, float z) {
        CgVfxField swirl = CgVfxField.of(16, 16, 16, false, (i, j, k, out) -> {
            out[0] = (float) Math.sin(j * 0.55 + k * 0.2);
            out[1] = 0.55f + 0.45f * (float) Math.cos(i * 0.6);
            out[2] = (float) Math.cos(i * 0.5 + j * 0.35);
        });
        CgVfxEmitter sparks = CgVfxEmitter.builder("field").renderer(CgVfxEmitter.Renderer.QUADS).capacity(2000)
                .rate(300f, 0f, PERIOD - 3f).shape(3.5f).launch(0f, 0.15f, 1f).speed(0.2f, 0.6f).life(3f, 4f)
                .size(0.06f, 0.12f, 1f).heat(0.8f)
                .module(new CgVfxModule.VectorField(swirl, Volume.box(4f, 4f, 4f).at(0f, 4f, 0f), 14f, 1f))
                .module(new CgVfxModule.Drag(1.6f, 0f))
                .opacity(FADE).build();
        add("field: VectorField", x, z, List.of(sparks), new float[][]{{0f, 0.3f, 0f}},
                layer("spark", sparks, CYAN, WHITE, 1f, null), null);
    }

    /** bevy_hanabi's conform to sphere, a vortex sweeping them round it: a shield taking shape. */
    private void shield(float x, float z, CgTexture dot) {
        CgVfxEmitter motes = CgVfxEmitter.builder("shield").renderer(CgVfxEmitter.Renderer.QUADS).capacity(2500)
                .rate(500f, 0f, PERIOD - 3f).shape(5f).launch(-0.4f, 0.6f, 1f).speed(0.5f, 2f).life(2.5f, 3.5f)
                .size(0.08f, 0.16f, 1f).heat(0.6f)
                .module(new CgVfxModule.Conform(Volume.sphere(2.6f), 4f, 40f, 6f))
                .module(new CgVfxModule.Vortex(0.2f, 1f, 0f, 9f, 0f, 0f, 0f, 0f))
                .module(new CgVfxModule.Drag(0.8f, 0f))
                .opacity(FADE).build();
        add("shield: Conform + Vortex", x, z, List.of(motes), new float[][]{{0f, 3.2f, 0f}},
                layer("sprite_glow", motes, VIOLET, WHITE, 1f,
                        b -> b.sampler("_Sheet", 0, dot).vec4("_Frames", 1f, 1f, 1f, 1f)), null);
    }

    /** Godot's sphere collider and rigid response; each bounce a collision event spawning a flash. */
    private void boulder(float x, float z) {
        CgVfxEmitter flash = CgVfxEmitter.builder("boulderFlash").renderer(CgVfxEmitter.Renderer.QUADS).capacity(64)
                .speed(0.5f, 1.5f).life(0.15f, 0.3f).size(0.15f, 0.3f, 1f).heat(1f).build();
        CgVfxEmitter sparks = CgVfxEmitter.builder("boulder").renderer(CgVfxEmitter.Renderer.QUADS).capacity(1200)
                .rate(120f, 0f, PERIOD - 3f).shape(1.2f).launch(-1f, -0.7f, 1f).speed(0.5f, 2f).life(2.5f, 3f)
                .size(0.08f, 0.14f, 1f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Collide(Volume.sphere(1.8f).at(0f, -5.2f, 0f), 0.6f, 0.05f, 0.3f))
                .module(new CgVfxModule.Collide(Volume.plane(0f, 1f, 0f).at(0f, -7f, 0f), 0.35f, 0.4f, 0.4f))
                .event(CgVfxEvent.onCollision().spawn(flash, 2))
                .opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(sparks).emitter(flash)
                .layer(sparkLayer(sparks, EMBER, FLAME)).layer(sparkLayer(flash, WHITE, WHITE)).build();
        stations.add(new Station("boulder: Collide sphere, onCollision spawns a flash", x, z, look, List.of(sparks),
                new float[][]{{0f, 7f, 0f}}, p -> p.sphere(0f, 1.8f, 0f, 1.75f)));
    }

    /** A container box: everything bounces inside it, nothing lost to friction. */
    private void cage(float x, float z) {
        CgVfxEmitter balls = CgVfxEmitter.builder("cage").renderer(CgVfxEmitter.Renderer.QUADS).capacity(400)
                .burst(0f, 300).shape(1f).launch(-1f, 1f, 1f).speed(4f, 9f).life(PERIOD - 1f, PERIOD - 0.5f)
                .size(0.1f, 0.2f, 1f).heat(0.9f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Collide(Volume.box(2.5f, 2.5f, 2.5f), 0.92f, 0f, 0f).container())
                .opacity(FADE).build();
        add("cage: Collide container box", x, z, List.of(balls), new float[][]{{0f, 2.6f, 0f}},
                layer("spark", balls, CYAN, WHITE, 1f, null), null);
    }

    /** A plane collider tilted into a slope: debris lands, slides off and is killed past a box. */
    private void slope(float x, float z) {
        float nx = 0.35f, ny = 1f, length = (float) Math.sqrt(nx * nx + ny * ny);
        CgVfxEmitter debris = CgVfxEmitter.builder("slope").renderer(CgVfxEmitter.Renderer.QUADS).capacity(1200)
                .rate(150f, 0f, PERIOD - 3f).shape(2.5f).launch(-1f, -0.8f, 1f).speed(0.5f, 1.5f).life(3f, 4f)
                .size(0.12f, 0.25f, 1.5f).spin(1f, 5f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Collide(Volume.plane(nx, ny, 0f).at(0f, -4.5f, 0f), 0.3f, 0.02f, 0f))
                .module(new CgVfxModule.Kill(Volume.box(4.5f, 8f, 4.5f), false))
                .opacity(FADE).build();
        add("slope: Collide plane + Kill outside a box", x, z, List.of(debris), new float[][]{{0f, 6.5f, 0f}},
                layer("speck", debris, DEBRIS, DEBRIS, 1f, null), p -> {
                    // the plane's surface through (0, 2, 0), drawn as a thin slab just under it
                    float angle = (float) Math.atan2(nx, ny);
                    transform.identity().rotateZ(angle).translate(0f, -0.05f, 0f).scale(8f, 0.1f, 8f);
                    p.box(0f, 2f, 0f, transform);
                });
    }

    /** Godot's box collider: debris rests on a block and slides off its edges onto the floor. */
    private void block(float x, float z) {
        CgVfxEmitter debris = CgVfxEmitter.builder("block").renderer(CgVfxEmitter.Renderer.QUADS).capacity(1500)
                .rate(180f, 0f, PERIOD - 3f).shape(2.2f).launch(-1f, -0.6f, 1f).speed(0.5f, 2f).life(3f, 4f)
                .size(0.12f, 0.25f, 1.5f).spin(1f, 5f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Collide(Volume.box(1.5f, 1f, 1.5f).at(0f, -5f, 0f), 0.4f, 0.15f, 0.5f))
                .module(new CgVfxModule.Collide(Volume.plane(0f, 1f, 0f).at(0f, -6f, 0f), 0.3f, 0.4f, 0.5f))
                .opacity(FADE).build();
        add("block: Collide box", x, z, List.of(debris), new float[][]{{0f, 6f, 0f}},
                layer("speck", debris, DEBRIS, DEBRIS, 1f, null), p -> {
                    transform.identity().scale(3f, 2f, 3f);
                    p.box(0f, 1f, 0f, transform);
                });
    }

    /** Godot's damping (left) beside drag (right): one stops dead, the other eases out, both near 3 blocks. */
    private void dampingAndDrag(float x, float z) {
        CgVfxEmitter.Builder ring = CgVfxEmitter.builder("damping").renderer(CgVfxEmitter.Renderer.QUADS).capacity(800)
                .burst(0f, 160).burst(1.75f, 160).burst(3.5f, 160).launch(0f, 0.02f, 1f).speed(8f, 8f).life(1.6f, 1.6f)
                .size(0.1f, 0.1f, 1f).heat(1f).opacity(FADE);
        CgVfxEmitter damped = ring.module(new CgVfxModule.Damping(10f)).build();
        CgVfxEmitter dragged = damped.toBuilder().name("drag").clearModules().module(new CgVfxModule.Drag(2.5f, 0f)).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(damped).emitter(dragged)
                .layer(sparkLayer(damped, GOLD, WHITE)).layer(sparkLayer(dragged, CYAN, WHITE)).build();
        stations.add(new Station("damping: Damping (gold) beside Drag (cyan)", x, z, look, List.of(damped, dragged),
                new float[][]{{-3.5f, 1f, 0f}, {3.5f, 1f, 0f}}, null));
    }

    /** Godot's velocity limit: the same fountain with (gold) and without (cyan) a 3 blocks a second ceiling. */
    private void limit(float x, float z) {
        CgVfxEmitter free = CgVfxEmitter.builder("free").renderer(CgVfxEmitter.Renderer.QUADS).capacity(1500)
                .rate(120f, 0f, PERIOD - 3f).launch(0.85f, 1f, 1f).speed(9f, 12f).life(3f, 3.5f)
                .size(0.08f, 0.12f, 1f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Collide(Volume.plane(0f, 1f, 0f).at(0f, -0.3f, 0f), 0.3f, 0.3f, 0.5f))
                .opacity(FADE).build();
        CgVfxEmitter limited = free.toBuilder().name("limited").module(new CgVfxModule.LimitSpeed(3f)).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(free).emitter(limited)
                .layer(sparkLayer(free, CYAN, WHITE)).layer(sparkLayer(limited, GOLD, WHITE)).build();
        stations.add(new Station("limit: LimitSpeed 3 (gold) beside none (cyan)", x, z, look, List.of(free, limited),
                new float[][]{{3f, 0.3f, 0f}, {-3f, 0.3f, 0f}}, null));
    }

    /** Flipbooks: a smoke sheet over each puff's life, frames blended, and fire upright, looping. */
    private void flipbooks(float x, float z, CgTexture smokeSheet, CgTexture fireSheet) {
        CgVfxEmitter puffs = CgVfxEmitter.builder("puffs").renderer(CgVfxEmitter.Renderer.QUADS).capacity(300)
                .rate(25f, 0f, PERIOD - 3f).shape(0.5f).launch(0.7f, 1f, 1f).speed(1f, 2f).life(2.5f, 3f)
                .size(0.8f, 1.2f, 1f).spin(0.1f, 0.5f)
                .module(new CgVfxModule.Drag(0.6f, 0f))
                .size(CgKeyframes.start(0f, 0.6f).to(1f, 1.8f, CgEasings.OUT_CUBIC).build())
                .opacity(FADE).build();
        CgVfxEmitter fire = CgVfxEmitter.builder("fire").renderer(CgVfxEmitter.Renderer.QUADS).capacity(200)
                .rate(30f, 0f, PERIOD - 3f).shape(0.3f).launch(0.9f, 1f, 1f).speed(0.3f, 0.8f).life(0.8f, 1.2f)
                .size(0.7f, 1f, 1f).heat(1f)
                .module(new CgVfxModule.Force(0f, 1.5f, 0f))
                .opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(puffs).emitter(fire)
                .layer(layerOf("sprite", puffs, SMOKE, SMOKE,
                        b -> b.sampler("_Sheet", 0, smokeSheet).vec4("_Frames", 8f, 8f, 64f, 1f).vec4("_Flip", 0f, 0f, 1f, 0f)))
                .layer(layerOf("sprite_glow", fire, EMBER, FLAME,
                        b -> b.sampler("_Sheet", 0, fireSheet).vec4("_Frames", 4f, 4f, 16f, 2f).vec4("_Flip", 1f, 1f, 1f, 0f)
                                .set1f("_Facing", 2f)))
                .build();
        stations.add(new Station("flipbooks: smoke over life (left), fire upright and looping (right)", x, z, look,
                List.of(puffs, fire), new float[][]{{-2.5f, 0.5f, 0f}, {2.5f, 0.2f, 0f}}, null));
    }

    /** The five facing modes, left to right: camera, camera position, upright, velocity, axis (flat on the floor). */
    private void facings(float x, float z, CgTexture arrow) {
        CgVfxLook.Builder look = CgVfxLook.builder(SCHEMA);
        List<CgVfxEmitter> emitters = new ArrayList<>();
        float[][] sources = new float[5][];
        String[] names = {"faceCamera", "facePosition", "faceUpright", "faceVelocity", "faceAxis"};
        for (int mode = 0; mode < 5; mode++) {
            boolean spray = mode == 3;
            CgVfxEmitter e = CgVfxEmitter.builder(names[mode]).renderer(CgVfxEmitter.Renderer.QUADS).capacity(120)
                    .rate(spray ? 30f : 6f, 0f, PERIOD - 3f).shape(spray ? 0f : 0.6f).launch(spray ? 0.6f : 0f, spray ? 1f : 0.2f, 1f)
                    .speed(spray ? 5f : 0.1f, spray ? 7f : 0.3f).life(2.5f, 3f).size(0.5f, 0.5f, 1f).spin(0.2f, 0.6f)
                    .module(new CgVfxModule.Gravity(spray ? 6f : 0f))
                    .opacity(FADE).build();
            float facing = mode;
            look.emitter(e).layer(layerOf("sprite", e, ARROW, ARROW, b -> b.sampler("_Sheet", 0, arrow)
                    .vec4("_Frames", 1f, 1f, 1f, 1f).set1f("_Facing", facing).vec4("_Axis", 0f, 1f, 0f, 0f)));
            emitters.add(e);
            sources[mode] = new float[]{-6f + mode * 3f, mode == 4 ? 0.1f : 2f, 0f};
        }
        stations.add(new Station("facing: camera, camera position, upright, velocity, axis", x, z, look.build(), emitters,
                sources, null));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void add(String name, float x, float z, List<CgVfxEmitter> emitters, float[][] sources, CgVfxLayer layer,
                     Consumer<Props> props) {
        CgVfxLook.Builder look = CgVfxLook.builder(SCHEMA);
        for (CgVfxEmitter e : emitters) look.emitter(e);
        stations.add(new Station(name, x, z, look.layer(layer).build(), emitters, sources, props));
    }

    private static CgVfxLayer layer(String shader, CgVfxEmitter e, CgVfxParam a, CgVfxParam b, float radius,
                                    Consumer<CgShaderBindings> properties) {
        CgVfxLayer.Builder l = CgVfxLayer.builder(PARTICLE + shader + ".shader").slot(e.layer()).colors(a, b).radius(radius);
        if (properties != null) l.properties(properties);
        return l.build();
    }

    private static CgVfxLayer layerOf(String shader, CgVfxEmitter e, CgVfxParam a, CgVfxParam b,
                                      Consumer<CgShaderBindings> properties) {
        return layer(shader, e, a, b, 1f, properties);
    }

    private static CgVfxLayer sparkLayer(CgVfxEmitter e, CgVfxParam a, CgVfxParam b) {
        return layer("spark", e, a, b, 1f, null);
    }

    /** Fills one texel of a flipbook frame: {@code (u, v)} 0..1 across the frame, v up; straight-alpha RGBA. */
    private interface Texel {
        void at(int frame, float u, float v, float[] out);
    }

    /** A {@code columns} x {@code rows} sheet of {@code cell}-texel frames, frame 0 at the top left. */
    private static CgTexture sheet(int columns, int rows, int cell, Texel texel) {
        int w = columns * cell, h = rows * cell;
        ByteBuffer pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        float[] out = new float[4];
        for (int y = 0; y < h; y++) {
            int row = rows - 1 - y / cell;
            for (int x = 0; x < w; x++) {
                int frame = row * columns + x / cell;
                texel.at(frame, (x % cell + 0.5f) / cell, (y % cell + 0.5f) / cell, out);
                for (int c = 0; c < 4; c++) pixels.put((byte) Math.round(Math.max(0f, Math.min(1f, out[c])) * 255f));
            }
        }
        pixels.flip();
        CgTexture2D texture = CgTexture2D.createEmpty(w, h, CgTextureSpec.RGBA8_LINEAR);
        texture.uploadRegion(0, 0, 0, w, h, pixels, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE);
        return texture;
    }

    /** A smoke puff over 64 frames: billowing out and thinning away, its edge eaten by noise. */
    private static void smokeFrame(int frame, float u, float v, float[] out) {
        float t = frame / 63f;
        float dx = u - 0.5f, dy = v - 0.5f, r = (float) Math.sqrt(dx * dx + dy * dy) * 2f;
        float n = noise(u * 5f + t * 1.5f, v * 5f - t) * 0.6f + noise(u * 11f, v * 11f + t * 3f) * 0.4f;
        float edge = 0.55f + 0.35f * t + (n - 0.5f) * 0.5f;
        float body = smooth(edge, edge - 0.25f, r) * (1f - smooth(0.4f, 1f, t)) * (0.7f + 0.3f * n);
        float shade = 0.75f + 0.25f * (v - 0.2f * r);
        out[0] = out[1] = out[2] = shade;
        out[3] = body;
    }

    /** A flame over 16 frames: a tapered tongue, flickering. */
    private static void fireFrame(int frame, float u, float v, float[] out) {
        float t = frame / 16f;
        float sway = (noise(v * 3f + t * 6f, t * 4f) - 0.5f) * 0.25f * v;
        float dx = (u - 0.5f - sway) / (0.38f * (1f - v * 0.85f) + 0.02f);
        float lick = noise(u * 6f, v * 4f - t * 8f);
        float body = smooth(1f, 0.6f, Math.abs(dx)) * smooth(1f, 0.55f + 0.3f * lick, v) * smooth(0f, 0.08f, v);
        float core = smooth(0.6f, 0.1f, Math.abs(dx)) * smooth(0.7f, 0.2f, v);
        out[0] = 1f;
        out[1] = 0.45f + 0.55f * core;
        out[2] = 0.1f + 0.7f * core;
        out[3] = body;
    }

    private static void dot(float u, float v, float[] out) {
        float dx = u - 0.5f, dy = v - 0.5f, r = (float) Math.sqrt(dx * dx + dy * dy) * 2f;
        out[0] = out[1] = out[2] = 1f;
        out[3] = (float) Math.exp(-r * r * 5f) * smooth(1f, 0.85f, r);
    }

    /** An arrow along +u inside a ring: which way a quad's length and face point. */
    private static void arrow(float u, float v, float[] out) {
        float dx = u - 0.5f, dy = v - 0.5f, r = (float) Math.sqrt(dx * dx + dy * dy) * 2f;
        boolean ring = r > 0.85f && r < 0.97f;
        boolean shaft = Math.abs(dy) < 0.06f && dx > -0.35f && dx < 0.15f;
        boolean head = dx >= 0.15f && dx < 0.4f && Math.abs(dy) < (0.4f - dx) * 0.9f;
        out[0] = out[1] = out[2] = 1f;
        out[3] = ring || shaft || head ? 1f : r < 0.85f ? 0.12f : 0f;
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    /** Smooth value noise, 0..1. */
    private static float noise(float x, float y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        fx = fx * fx * (3f - 2f * fx);
        fy = fy * fy * (3f - 2f * fy);
        float a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
    }

    private static float hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0xFFFFFF;
    }

    // ── frame ─────────────────────────────────────────────────────────────────

    private final Props props = new Props() {
        private final Matrix4f scaled = new Matrix4f();

        @Override
        public void sphere(float x, float y, float z, float radius) {
            scaled.identity().scale(radius);
            world.draw(sphere, mercury).at(currentX + x, y, currentZ + z).transform(scaled).submit();
        }

        @Override
        public void box(float x, float y, float z, Matrix4f transform) {
            world.draw(cube, copper).at(currentX + x, y, currentZ + z).transform(transform).submit();
        }
    };
    private float currentX, currentZ;

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        float seconds = (float) frame.getElapsedTime();
        world = CgWorldRenderer.get();
        if (seconds >= nextPlay) {
            for (Station s : stations) vfx.play(new Play(s));
            nextPlay = seconds + PERIOD;
        }
        vfx.update(seconds);
        vfx.submit(world);
        for (Station s : stations) {
            // Its name over it, and what it shows under that.
            int split = s.name().indexOf(": ");
            String title = split < 0 ? s.name() : s.name().substring(0, split);
            world.text(title).at(s.x(), 10.2, s.z()).height(1.1f).font(font).stroke(0.08f, 0xFF000000).submit();
            if (split >= 0) {
                world.text(s.name().substring(split + 2)).at(s.x(), 9.1, s.z()).height(0.6f).font(font)
                        .color(0xFFB8D8FF).stroke(0.08f, 0xFF000000).submit();
            }
            if (s.props() == null) continue;
            currentX = s.x();
            currentZ = s.z();
            s.props().accept(props);
        }
        stage.submitStage(world, 0.0, 0.0, 0.0, ctx.getCamera3D().getPosX(), ctx.getCamera3D().getPosY(),
                ctx.getCamera3D().getPosZ());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    @Override
    public void dispose() {
        vfx.delete();
        stage.delete();
        if (font != null) font.dispose();
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
