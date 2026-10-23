package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterAssets;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxField;
import com.crystalgraphics.vfx.particle.CgVfxFieldTurn;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.form.CgVfxFormContext;
import com.crystalgraphics.vfx.particle.form.CgVfxFormInput;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnForm;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnInput;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The gate for inputs in forms (aaa-milestones P6.3, P6.4). Module inputs: an emitter compiled from its file a form,
 * each giving {@code acceleration}'s value as a range per channel, a range rolled once, a locked range, a pick, curves by
 * age, by the particle's age, by its speed, by a random draw and by its id, and a binding to its age. Particles of known
 * ids, ages, lives and velocities are seeded and stepped once; with nothing else pulling, each one's change of velocity
 * over the step is its acceleration, which must be {@code CgVfxFormInput}'s Java evaluation for that particle, every
 * lane. Spawn values: an emitter a spawn form ({@code initialize_particle}'s lifetime, sprite size and rotation,
 * {@code add_velocity}'s speed and linear velocity, {@code shape_location}'s radius and offset by the loop), 512 spawned
 * at a known loop time and stepped once, each one's life, size, rotation, velocity or place the form's Java evaluation
 * for its key, every component. Skeleton points ({@code socket_location}, in turn, following them): three named, the view
 * answering two, so each spawn at the third is absent and the rest are at their point plus its offset, however fast they
 * were launched. A mesh orientation
 * ({@code initial_mesh_orientation} per channel in turns, {@code mesh_rotation_rate}): each particle's quaternion, as Look
 * writes it for its draws, Unreal's rotator of its rolled start plus its rate times its age. A vector field placed,
 * pivoted and turned ({@code CgVfxFieldTurn}) with tightness: each seeded particle's velocity after the step, drawn
 * toward the turned field's and pushed by it, against Java. A point attractor that lasts and one that does not (Cascade's
 * {@code bAffectBaseVelocity}): both move a particle alike over the step, the first keeping the velocity it built and
 * the second leaving it as seeded. Cascade's triangle: every particle inside it, and its apex
 * weighing more than half in about a sixth of them, as three uniforms over their sum give (a quarter by area).
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-forms"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-forms" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-forms --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [vfx-forms] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgVfxFormsScene implements InteractiveSceneLifecycle {

    private static final int PARTICLES = 512, DRAIN = 60;
    private static final float DT = 0.5f, TOLERANCE = 2e-4f;
    private static final int SEED_BITS = 0x0F0E;
    private static final double[] ORIGIN = {12.5, 64.0, -30.25};

    /** Each form by name: its value as an emitter file writes it, an acceleration's three lanes. */
    private static final Map<String, Object> FORMS = new LinkedHashMap<>();
    /** Each spawn form by name: its emitter file's {@code particle_spawn}. */
    private static final Map<String, List<Object>> SPAWNS = new LinkedHashMap<>();
    private static final Map<String, Map<String, Object>> ORBITS = new LinkedHashMap<>();
    /** Each spawn form's target, by name. */
    private static final Map<String, CgVfxSpawnInput> TARGETS = new LinkedHashMap<>();
    /** A light station's light_attributes inputs. */
    private static final Map<String, Map<String, Object>> LIGHTS = new LinkedHashMap<>();
    /** The skeleton points the view answers, from the origin; the third it leaves unanswered. */
    private static final float[][] SOCKETS = {{1.5f, 0.25f, -2f}, {-0.75f, 3f, 0.5f}};
    private static final float[][] SOCKET_OFFSETS = {{0.5f, 0f, 0f}, {0f, 0.25f, 0f}, {0f, 0f, 0f}};
    /** The orientation station's start, two ends in turns, and its rate in turns a second: Unreal's rotators. */
    private static final double[] TURN_FROM = {0.0, 0.0, 0.0}, TURN_TO = {0.25, 0.5, 1.0}, TURN_RATE = {0.0, 0.125, 0.5};
    /** The orientation station's rate added after its multiplier, radians a second, on roll; its size's range over life. */
    private static final double TURN_ADDED = 1.3;
    private static final float[] ROLL_FROM = {0.5f, 0.5f, 0.5f}, ROLL_TO = {1.5f, 2f, 2.5f};
    /** Its user parameter {@code Length} as the instance gives it, which its mesh scale binds through a remap of 0..10 to 0..5. */
    private static final float[] LENGTH = {3f, 4f, 6f};
    /** The instance's {@code Push} and {@code Aim}, which a module input and a spawn offset bind through remaps. */
    private static final float[] PUSH = {2f, -5f, 30f}, AIM = {0.25f, 0.5f, 0.75f};
    /** The field station's grid, its placement, pivot and turn, its strength and tightness. */
    private static final CgVfxField FIELD = CgVfxField.of(4, 4, 4, false, (x, y, z, out) -> {
        out[0] = 0.5f + x - 0.3f * z;
        out[1] = 1f - 0.4f * y + 0.2f * x;
        out[2] = -0.8f + 0.6f * z - 0.1f * y;
    });
    private static final float[] FIELD_AT = {0.5f, -0.3f, 0.2f}, FIELD_HALF = {2f, 2f, 2f};
    private static final float FIELD_STRENGTH = 1.7f, FIELD_TIGHTNESS = 0.35f;
    private static final CgVfxFieldTurn FIELD_TURN = new CgVfxFieldTurn(new float[]{0.3f, 0.1f, -0.2f},
            CgUnits.rotatorToHost(0.4, 0.9, -0.6), new float[]{0.2f, -0.5f, 1.1f}, new float[]{0.2f, -0.5f, 1.1f}, new float[3]);
    /** The loop's seconds the spawn stations spawn at, and the instance's as the module stations step. */
    private static final float LOOP = 0.7f, STEP_LOOP = 1.3f;
    /** The triangle station's height, apex angle (radians) and thickness. */
    /** The attractor stations' centre from the source (the seeded particles sit there), radius and strength. */
    private static final double[] ATTRACT_AT = {2.0, 0.0, 0.0};
    private static final double ATTRACT_RADIUS = 10.0, ATTRACT_STRENGTH = 3.0;
    /** The inverse-square station's radius: inside the seeded particles' distance, so they feel the square's fall. */
    private static final double GRAVITY_RADIUS = 1.0;
    /** The source-movement station's scale, and how far its source moves over the step. */
    private static final float[] CARRY_SCALE = {1f, 0.5f, 2f}, CARRIED = {0.4f, -0.3f, 0.25f};
    private static final double TRI_HEIGHT = 2.0, TRI_APEX = Math.toRadians(70), TRI_THICKNESS = 0.5;

    static {
        Map<String, Object> cubic = Map.of("keys", List.of(List.of(0.0, -2.0, "cubic", "break", 0.0, 6.0),
                List.of(0.4, 1.5, "cubic", "break", -1.0, 2.0), List.of(1.0, 0.25, "cubic", "break", 0.0, 0.0)));
        Map<String, Object> linear = Map.of("keys", List.of(List.of(0.0, 1.0, "linear"), List.of(0.3, 4.0, "linear"),
                List.of(1.0, -1.0, "linear")));
        Map<String, Object> step = Map.of("keys", List.of(List.of(0.0, 0.5, "constant"), List.of(0.6, 3.0, "constant")));
        Map<String, Object> seconds = Map.of("keys", List.of(List.of(0.0, 0.0), List.of(2.0, 5.0, "linear"), List.of(4.0, 1.0)));
        FORMS.put("range", Map.of("range", List.of(List.of(-1.0, 2.0, 0.5), List.of(3.0, -2.0, 4.0)), "per_channel", true));
        FORMS.put("range-once", Map.of("range", List.of(List.of(0.0, 0.0, 0.0), List.of(2.0, 4.0, 6.0))));
        FORMS.put("range-locked", Map.of("range", List.of(List.of(0.0, 0.0, 0.0), List.of(1.0, 2.0, 3.0)), "per_channel", true,
                "locked", "xz"));
        FORMS.put("pick", Map.of("pick", List.of(List.of(1.0, 2.0, 3.0), List.of(-1.0, -2.0, -3.0))));
        FORMS.put("curve-age", Map.of("curves", List.of(cubic, linear, step), "by", "age"));
        FORMS.put("curve-particles-age", Map.of("curve", seconds, "by", "particles.age"));
        FORMS.put("curve-speed", Map.of("curve", seconds, "by", "speed"));
        FORMS.put("curve-random", Map.of("curve", cubic, "by", "random"));
        FORMS.put("curve-id", Map.of("curve", Map.of("keys", List.of(List.of(0.0, 0.0), List.of(511.0, 3.0, "linear"))), "by", "id"));
        FORMS.put("age-binding", Map.of("bind", "particles.age", "scale", 2.0));
        FORMS.put("array-age", Map.of("array", List.of(List.of(0.0, 0.0, 0.0), List.of(1.0, 2.0, 3.0), List.of(-1.0, 0.0, 2.0)), "by", "age"));
        FORMS.put("array-id-wrap", Map.of("array", List.of(List.of(1.0, 0.0, 0.0), List.of(0.0, 2.0, 0.0), List.of(0.0, 0.0, 3.0)),
                "by", "id", "address", "wrap"));
        FORMS.put("array-random", Map.of("array", List.of(List.of(4.0, 0.0, 0.0), List.of(0.0, -4.0, 0.0), List.of(0.0, 0.0, 4.0),
                List.of(1.0, 1.0, 1.0)), "by", "random"));
        FORMS.put("fn-random-range", Map.of("fn", "crystalgraphics:random_range", "inputs", Map.of(
                "min", Map.of("range", List.of(List.of(0.0, 0.0, 0.0), List.of(1.0, 1.0, 1.0)), "per_channel", true),
                "max", List.of(2.0, 3.0, 4.0))));
        FORMS.put("fn-add", Map.of("fn", "crystalgraphics:add", "inputs", Map.of("a", Map.of("curve", cubic, "by", "age"),
                "b", List.of(0.5, -0.5, 1.0))));
        FORMS.put("curve-loop", Map.of("curve", seconds, "by", "loop"));
        FORMS.put("user-binding", Map.of("bind", "user.Push", "default", List.of(1.0, 1.0, 1.0), "remap", Map.of(
                "in", List.of(List.of(0.0, -10.0, 0.0), List.of(10.0, 10.0, 10.0)), "out", List.of(List.of(0.0, 0.0, 0.0),
                        List.of(5.0, 4.0, 3.0)))));
        SPAWNS.put("spawn-life-pick", List.of(init(Map.of("lifetime", Map.of("pick", List.of(1.5, 3.0))))));
        SPAWNS.put("spawn-life-id", List.of(init(Map.of("lifetime",
                Map.of("curve", Map.of("keys", List.of(List.of(0.0, 1.0), List.of(511.0, 4.0, "linear"))), "by", "id")))));
        SPAWNS.put("spawn-size-random", List.of(init(Map.of("lifetime", 3.0, "sprite_size", CgVfxSpawnForm.skewed(0.1f, 0.5f, 2f)))));
        SPAWNS.put("spawn-speed-random", List.of(init(Map.of("lifetime", 3.0)), Map.of("id", "velocity",
                "module", "crystalgraphics:add_velocity", "inputs", Map.of("mode", "outward", "speed", Map.of("curve", cubic, "by", "random")))));
        SPAWNS.put("spawn-radius-loop", List.of(init(Map.of("lifetime", 3.0)), Map.of("id", "shape",
                "module", "crystalgraphics:shape_location", "inputs", Map.of("shape", "sphere", "surface_only", true,
                        "sphere_radius", CgVfxSpawnForm.swept(2f, 3f, 1.6f)))));
        SPAWNS.put("spawn-velocity-range", List.of(init(Map.of("lifetime", 3.0)), linear(Map.of("range",
                List.of(List.of(-1.0, 2.0, -1.0), List.of(1.0, 4.0, 1.5)), "per_channel", true))));
        SPAWNS.put("spawn-velocity-loop", List.of(init(Map.of("lifetime", 3.0)), linear(Map.of("curves", List.of(seconds, cubic, linear),
                "by", "loop"))));
        SPAWNS.put("spawn-offset-loop", List.of(init(Map.of("lifetime", 3.0)), linear(List.of(0.0, 0.0, 0.0)), Map.of("id", "shape",
                "module", "crystalgraphics:shape_location", "inputs", Map.of("shape", "point",
                        "offset", Map.of("curves", List.of(linear, seconds, cubic), "by", "loop")))));
        SPAWNS.put("spawn-rotation-turns", List.of(init(Map.of("lifetime", 3.0, "sprite_rotation",
                Map.of("range", List.of(0.0, 1.0), "unit", "turns")))));
        // Following its points: the velocity moves it, and each step puts it back.
        SPAWNS.put("spawn-sockets", List.of(init(Map.of("lifetime", 3.0)), linear(List.of(3.0, 0.0, 0.0)), Map.of("id", "socket",
                "module", "crystalgraphics:socket_location", "inputs", Map.of("selection", "sequential", "update_each_frame", true,
                        "points", List.of(socket("a", 0), socket("b", 1), socket("c", 2))))));
        SPAWNS.put("spawn-orientation", List.of(init(Map.of("lifetime", 3.0)), Map.of("id", "orientation",
                "module", "crystalgraphics:initial_mesh_orientation", "inputs", Map.of("rotation", Map.of("range",
                        List.of(List.of(TURN_FROM[0], TURN_FROM[1], TURN_FROM[2]), List.of(TURN_TO[0], TURN_TO[1], TURN_TO[2])),
                        "per_channel", true, "unit", "turns")))));
        // Starts stored at birth: a colour by the loop, a mesh scale by a function.
        SPAWNS.put("spawn-color-loop", List.of(init(Map.of("lifetime", 3.0, "color", Map.of("curves", List.of(seconds, cubic, linear),
                "by", "loop")))));
        SPAWNS.put("spawn-scale-fn", List.of(init(Map.of("lifetime", 3.0, "mesh_scale", Map.of("fn", "crystalgraphics:random_range",
                "inputs", Map.of("min", List.of(0.5, 0.25, 1.0), "max", List.of(2.0, 3.0, 4.0)))))));
        SPAWNS.put("spawn-offset-bind", List.of(init(Map.of("lifetime", 3.0)), Map.of("id", "shape",
                "module", "crystalgraphics:shape_location", "inputs", Map.of("shape", "point", "offset", Map.of("bind", "user.Aim",
                        "default", List.of(0.0, 0.0, 0.0), "remap", Map.of("in", List.of(List.of(0.0, 0.0, 0.0), List.of(1.0, 1.0, 1.0)),
                                "out", List.of(List.of(-2.0, 0.0, 1.0), List.of(2.0, 4.0, 5.0))))))));
        TARGETS.put("spawn-offset-bind", CgVfxSpawnInput.OFFSET);
        SPAWNS.put("spawn-triangle", List.of(init(Map.of("lifetime", 3.0)), Map.of("id", "shape",
                "module", "crystalgraphics:shape_location", "inputs", Map.of("shape", "triangle", "triangle_height", TRI_HEIGHT,
                        "triangle_angle", TRI_APEX, "triangle_thickness", TRI_THICKNESS))));
        // On a cylinder's edge, its half height out from its middle: a height by the loop.
        SPAWNS.put("spawn-height-loop", List.of(init(Map.of("lifetime", 3.0)), Map.of("id", "shape",
                "module", "crystalgraphics:shape_location", "inputs", Map.of("shape", "cylinder", "cylinder_radius", 1.0,
                        "edge_only", true, "cylinder_height", Map.of("curve", seconds, "by", "loop")))));
        TARGETS.put("spawn-height-loop", CgVfxSpawnInput.CYLINDER_HEIGHT);
        // A mesh rotator and a rotation rate stored at birth: by the loop, and a pick.
        SPAWNS.put("spawn-turn-loop", List.of(init(Map.of("lifetime", 3.0)), Map.of("id", "orientation",
                "module", "crystalgraphics:initial_mesh_orientation", "inputs", Map.of("rotation",
                        Map.of("curves", List.of(seconds, cubic, linear), "by", "loop")))));
        SPAWNS.put("spawn-turn-rate-pick", List.of(init(Map.of("lifetime", 3.0))));
        // A light's radius scale by the loop, Cascade's RadiusScale at the emitter's time, and an exponent rolled at birth.
        SPAWNS.put("spawn-light-loop", List.of(init(Map.of("lifetime", 3.0))));
        SPAWNS.put("spawn-light-range", List.of(init(Map.of("lifetime", 3.0))));
        LIGHTS.put("spawn-light-loop", Map.of("radius", Map.of("curve", seconds, "by", "loop")));
        LIGHTS.put("spawn-light-range", Map.of("exponent", Map.of("range", List.of(2.0, 9.0))));
        // Cascade's orbit, stored at birth: a rate by the loop, and an offset picked.
        SPAWNS.put("spawn-orbit-rate-loop", List.of(init(Map.of("lifetime", 3.0))));
        SPAWNS.put("spawn-orbit-offset-pick", List.of(init(Map.of("lifetime", 3.0))));
        ORBITS.put("spawn-orbit-rate-loop", Map.of("offset", List.of(0.0, 1.0, 0.0), "rotation", List.of(0.0, 0.0, 0.0),
                "rotation_rate", Map.of("curves", List.of(seconds, cubic, linear), "by", "loop")));
        ORBITS.put("spawn-orbit-offset-pick", Map.of("offset", Map.of("pick", List.of(List.of(1.0, 2.0, 3.0), List.of(-1.0, 0.5, 2.0))),
                "rotation", List.of(0.0, 0.0, 0.0), "rotation_rate", List.of(0.0, 0.0, 0.0)));
        TARGETS.put("spawn-orbit-rate-loop", CgVfxSpawnInput.ORBIT_RATE);
        TARGETS.put("spawn-orbit-offset-pick", CgVfxSpawnInput.ORBIT_OFFSET);
        TARGETS.put("spawn-light-loop", CgVfxSpawnInput.LIGHT_RADIUS);
        TARGETS.put("spawn-light-range", CgVfxSpawnInput.LIGHT_EXPONENT);
        TARGETS.put("spawn-turn-loop", CgVfxSpawnInput.MESH_ORIENTATION);
        TARGETS.put("spawn-turn-rate-pick", CgVfxSpawnInput.MESH_ROTATION_RATE);
        TARGETS.put("spawn-color-loop", CgVfxSpawnInput.COLOR);
        TARGETS.put("spawn-scale-fn", CgVfxSpawnInput.MESH_SCALE);
        TARGETS.put("spawn-life-pick", CgVfxSpawnInput.LIFETIME);
        TARGETS.put("spawn-life-id", CgVfxSpawnInput.LIFETIME);
        TARGETS.put("spawn-size-random", CgVfxSpawnInput.SPRITE_SIZE);
        TARGETS.put("spawn-speed-random", CgVfxSpawnInput.SPEED);
        TARGETS.put("spawn-radius-loop", CgVfxSpawnInput.RADIUS);
        TARGETS.put("spawn-velocity-range", CgVfxSpawnInput.VELOCITY);
        TARGETS.put("spawn-velocity-loop", CgVfxSpawnInput.VELOCITY);
        TARGETS.put("spawn-offset-loop", CgVfxSpawnInput.OFFSET);
        TARGETS.put("spawn-rotation-turns", CgVfxSpawnInput.SPRITE_ROTATION);
    }

    private static Map<String, Object> socket(String name, int i) {
        float[] o = SOCKET_OFFSETS[i];
        return Map.of("name", name, "offset", List.of((double) o[0], (double) o[1], (double) o[2]));
    }

    private static Map<String, Object> linear(Object velocity) {
        return Map.of("id", "velocity", "module", "crystalgraphics:add_velocity", "inputs", Map.of("mode", "linear", "linear", velocity));
    }

    private static Map<String, Object> init(Map<String, Object> inputs) {
        return Map.of("id", "init", "module", "crystalgraphics:initialize_particle", "inputs", inputs);
    }

    private final List<CgVfxEmitter> emitters = new ArrayList<>();
    /** Each spawn station's form under test. */
    private final List<CgVfxSpawnForm> spawnForms = new ArrayList<>();
    private final List<CgVfxParticlePool> pools = new ArrayList<>();
    private final List<Integer> slots = new ArrayList<>();
    private final List<ByteBuffer> read = new ArrayList<>();
    /** Each pool's drawn parts, read back where its particles carry drawn slots. */
    private final Map<Integer, ByteBuffer> drawnRead = new HashMap<>();
    private int drawnWanted;
    private final List<CgRequest> requests = new ArrayList<>();
    /** Each particle's seeded age, life and velocity, by its id. */
    private final float[][] seeded = new float[PARTICLES][];
    /** The stations seeded with particles: the module forms, the field and the attractors; spawn stations after. */
    private int seededStations;
    /** The momentary attractor's pools' owner: its station shares the lasting one's shape, and each station steps its own pool. */
    private final Object momentaryOwner = new Object(), gravityOwner = new Object();
    private int frame, checked;
    private float worst;
    private String failure;
    private boolean compared, running = true;

    @Override
    public void init(HarnessContext ctx) {
        for (Map.Entry<String, Object> form : FORMS.entrySet()) {
            Map<String, Object> tree = new LinkedHashMap<>();
            tree.put("_v", 1);
            tree.put("properties", Map.of("capacity", PARTICLES));
            tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                    "inputs", Map.of("loop", "once", "duration", 1.0))));
            tree.put("particle_update", List.of(Map.of("id", "push", "module", "crystalgraphics:acceleration",
                    "inputs", Map.of("acceleration", form.getValue()))));
            CgVfxEmitter emitter = CgVfxEmitterAssets.compile("forms-" + form.getKey(), tree, null);
            if (emitter.moduleForms().size() != 1) fail(form.getKey() + " compiled " + emitter.moduleForms().size() + " forms");
            emitters.add(emitter);
            CgVfxParticlePool.prepare(emitter);
            read.add(null);
        }
        Map<String, Object> still = new LinkedHashMap<>();
        still.put("_v", 1);
        still.put("properties", Map.of("capacity", PARTICLES));
        still.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                "inputs", Map.of("loop", "once", "duration", 1.0))));
        CgVfxEmitter fielded = CgVfxEmitterAssets.compile("forms-field", still, null).toBuilder().module(new CgVfxModule.VectorField(FIELD,
                CgBox.of(FIELD_HALF[0], FIELD_HALF[1], FIELD_HALF[2]), FIELD_AT[0], FIELD_AT[1], FIELD_AT[2], FIELD_STRENGTH, 1f,
                FIELD_TIGHTNESS, FIELD_TURN)).build();
        emitters.add(fielded);
        CgVfxParticlePool.prepare(fielded);
        read.add(null);
        for (String law : List.of("lasting", "momentary", "gravity")) {
            Map<String, Object> tree = new LinkedHashMap<>(still);
            Map<String, Object> pull = new LinkedHashMap<>(Map.of("position", List.of(ATTRACT_AT[0], ATTRACT_AT[1], ATTRACT_AT[2]),
                    "radius", law.equals("gravity") ? GRAVITY_RADIUS : ATTRACT_RADIUS, "strength", ATTRACT_STRENGTH, "falloff", 0.0,
                    "affect_base_velocity", !law.equals("momentary")));
            if (law.equals("gravity")) pull.put("law", "inverse_square");
            tree.put("particle_update", List.of(Map.of("id", "pull", "module", "crystalgraphics:point_attractor", "inputs", pull)));
            CgVfxEmitter pulled = CgVfxEmitterAssets.compile("forms-attract-" + law, tree, null);
            emitters.add(pulled);
            CgVfxParticlePool.prepare(pulled);
            read.add(null);
        }
        Map<String, Object> carried = new LinkedHashMap<>(still);
        carried.put("particle_update", List.of(Map.of("id", "carry", "module", "crystalgraphics:source_movement", "inputs",
                Map.of("scale", List.of((double) CARRY_SCALE[0], (double) CARRY_SCALE[1], (double) CARRY_SCALE[2])))));
        CgVfxEmitter carrier = CgVfxEmitterAssets.compile("forms-source-movement", carried, null);
        emitters.add(carrier);
        CgVfxParticlePool.prepare(carrier);
        read.add(null);
        seededStations = emitters.size();
        for (Map.Entry<String, List<Object>> spawn : SPAWNS.entrySet()) {
            Map<String, Object> tree = new LinkedHashMap<>();
            tree.put("_v", 1);
            tree.put("properties", Map.of("capacity", PARTICLES));
            tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                    "inputs", Map.of("loop", "once", "duration", 1.6))));
            tree.put("particle_spawn", spawn.getValue());
            if (ORBITS.containsKey(spawn.getKey())) {
                tree.put("particle_update", List.of(Map.of("id", "orbit", "module", "crystalgraphics:orbit",
                        "inputs", ORBITS.get(spawn.getKey()))));
            }
            if (LIGHTS.containsKey(spawn.getKey())) {
                tree.put("particle_update", List.of(Map.of("id", "light", "module", "crystalgraphics:light_attributes",
                        "inputs", LIGHTS.get(spawn.getKey()))));
            }
            if (spawn.getKey().equals("spawn-turn-rate-pick")) {
                tree.put("particle_update", List.of(Map.of("id", "rotation_rate", "module", "crystalgraphics:mesh_rotation_rate",
                        "inputs", Map.of("rate", Map.of("pick", List.of(List.of(1.0, -2.0, 0.5), List.of(-3.0, 0.25, 4.0)))))));
            }
            if (spawn.getKey().equals("spawn-orientation")) {
                // Its rate slowing linearly to nothing over life, then a rate the slowing misses; a size flickering each step.
                Map<String, Object> falling = Map.of("samples", Map.of("range", List.of(0.0, 1.0), "values", List.of(1.0, 0.0)));
                Map<String, Object> flat = Map.of("samples", Map.of("range", List.of(0.0, 1.0), "values", List.of(TURN_ADDED, TURN_ADDED)));
                Map<String, Object> none = Map.of("samples", Map.of("range", List.of(0.0, 1.0), "values", List.of(0.0, 0.0)));
                tree.put("particle_update", List.of(Map.of("id", "rotation_rate", "module", "crystalgraphics:mesh_rotation_rate",
                        "inputs", Map.of("rate", Map.of("value", List.of(TURN_RATE[0], TURN_RATE[1], TURN_RATE[2]), "unit", "turns"))),
                        Map.of("id", "slowing", "module", "crystalgraphics:mesh_rotation_rate", "inputs", Map.of("mode", "scale",
                                "rate_scale", Map.of("curves", List.of(falling, falling, falling), "by", "age"))),
                        Map.of("id", "added", "module", "crystalgraphics:mesh_rotation_rate", "inputs", Map.of("rate",
                                Map.of("curves", List.of(flat, none, none), "by", "age"))),
                        Map.of("id", "flicker", "module", "crystalgraphics:scale_mesh_size", "inputs", Map.of("scale", Map.of("range",
                                List.of(List.of((double) ROLL_FROM[0], (double) ROLL_FROM[1], (double) ROLL_FROM[2]),
                                        List.of((double) ROLL_TO[0], (double) ROLL_TO[1], (double) ROLL_TO[2])),
                                "per_channel", true, "locked", "yz"))),
                        // Its alpha rolled each step between two curves by life: its second roll.
                        Map.of("id", "fade", "module", "crystalgraphics:scale_color", "inputs", Map.of("alpha", Map.of(
                                "fn", "crystalgraphics:random_range", "inputs", Map.of(
                                        "min", Map.of("curve", Map.of("samples", Map.of("range", List.of(0.0, 1.0), "values", List.of(1.0, 0.5))), "by", "age"),
                                        "max", Map.of("curve", Map.of("samples", Map.of("range", List.of(0.0, 1.0), "values", List.of(2.0, 3.0))), "by", "age"))))),
                        Map.of("id", "length", "module", "crystalgraphics:scale_mesh_size", "inputs", Map.of("scale", Map.of(
                                "bind", "user.Length", "default", List.of(1.0, 1.0, 1.0), "remap", Map.of(
                                        "in", List.of(List.of(0.0, 0.0, 0.0), List.of(10.0, 10.0, 10.0)),
                                        "out", List.of(List.of(0.0, 0.0, 0.0), List.of(5.0, 5.0, 5.0))))))));
            }
            CgVfxEmitter emitter = CgVfxEmitterAssets.compile("forms-" + spawn.getKey(), tree, null);
            CgVfxSpawnInput target = TARGETS.get(spawn.getKey());
            CgVfxSpawnForm form = emitter.spawnForms().stream().filter(x -> x.target() == target).findFirst().orElse(null);
            if (target != null && form == null) fail(spawn.getKey() + " compiled no " + target + " spawn form");
            if (target == null && emitter.sockets() == null && emitter.meshRotation() == null && !spawn.getKey().equals("spawn-triangle")) {
                fail(spawn.getKey() + " compiled no skeleton points and no mesh orientation");
            }
            spawnForms.add(form);
            emitters.add(emitter);
            CgVfxParticlePool.prepare(emitter);
            read.add(null);
        }
        Random random = new Random(1201);
        for (int id = 0; id < PARTICLES; id++) {
            // Every one outlives the step: a death would compact it away.
            float life = 1f + random.nextFloat() * 4f, age = random.nextFloat() * (life - DT - 0.1f);
            seeded[id] = new float[]{age, life, (random.nextFloat() * 2f - 1f) * 3f, (random.nextFloat() * 2f - 1f) * 3f,
                    (random.nextFloat() * 2f - 1f) * 3f};
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame <= 2) {
            CgRecording rec = new CgRecording();
            if (frame == 1) {
                seed(rec);
            } else {
                for (int e = 0; e < pools.size(); e++) {
                    CgVfxParticlePool pool = pools.get(e);
                    pool.beginStep(DT, 0f, 0f, 0f);
                    pool.instance(slots.get(e), SEED_BITS, 1f, Float.NaN,
                            emitters.get(e).name().equals("forms-source-movement") ? moved() : origin());
                    // A spawn station's particles: one burst at the step's start, at the loop's LOOP seconds.
                    if (e >= seededStations) pool.spawn(slots.get(e), 0, PARTICLES, PARTICLES, 0f, DT, 0f, 0f, 0f, 0f, 0f, 0f, LOOP, LOOP);
                    pool.endStep();
                }
                CgVfxParticlePool.record(rec, pools);
                for (int e = 0; e < pools.size(); e++) {
                    int at = e;
                    CgVfxParticlePool pool = pools.get(e);
                    requests.add(rec.readback(pool.records(), 0, (long) pool.storage() * pool.record().bytes(),
                            data -> read.set(at, copy(data))));
                    if (pool.shape().drawnStride() == 2) {
                        drawnWanted++;
                        requests.add(rec.readback(pool.drawn(), 0, (long) pool.storage() * 2 * CgVfxRecord.DRAWN_BYTES,
                                data -> drawnRead.put(at, copy(data))));
                    }
                }
            }
            CgImmediate.execute(rec);
            return;
        }
        boolean answered = true;
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (!compared && answered && !read.contains(null) && drawnRead.size() == drawnWanted && frame > 10) {
            for (int e = 0; e < emitters.size(); e++) {
                if (e < FORMS.size()) compare(e);
                else if (e == FORMS.size()) compareField(e);
                else if (emitters.get(e).name().equals("forms-source-movement")) compareCarried(e);
                else if (e < seededStations) compareAttract(e);
                else compareSpawn(e);
            }
            compared = true;
        }
        if (compared || frame > DRAIN) {
            report();
            CgVfxParticlePool.release(this);
            CgVfxParticlePool.release(momentaryOwner);
            CgVfxParticlePool.release(gravityOwner);
            running = false;
        }
    }

    private void seed(CgRecording rec) {
        for (CgVfxEmitter emitter : emitters) {
            CgVfxParticlePool pool = CgVfxParticlePool.of(emitter.name().endsWith("momentary") ? momentaryOwner
                    : emitter.name().endsWith("gravity") ? gravityOwner : this, emitter);
            if (pools.contains(pool)) fail(emitter.name() + " shares a pool with another form");
            int slot = pool.open(emitter, PARTICLES);
            if (pools.size() >= seededStations) {
                pools.add(pool);
                slots.add(slot);
                continue;
            }
            CgVfxRecord layout = pool.record();
            ByteBuffer packed = ByteBuffer.allocateDirect(PARTICLES * layout.bytes()).order(ByteOrder.nativeOrder());
            for (int id = 0; id < PARTICLES; id++) {
                float[] s = seeded[id];
                layout.put(packed, id, 0f, 0f, 0f, s[2], s[3], s[4], s[0], s[1], 0.1f, 0f, id, slot);
            }
            pool.seed(rec, packed, PARTICLES);
            pools.add(pool);
            slots.add(slot);
        }
    }

    /** Every particle of form {@code e}: its change of velocity over the step, against the form's Java evaluation. */
    private void compare(int e) {
        CgVfxEmitter emitter = emitters.get(e);
        CgVfxFormInput form = emitter.moduleForms().get(0).compiled();
        ByteBuffer records = read.get(e);
        CgVfxRecord layout = pools.get(e).record();
        CgVfxFormContext ctx = new CgVfxFormContext().seed(SEED_BITS).loop(STEP_LOOP, 0).parameter("user.Push", PUSH);
        float[] want = new float[form.components()];
        boolean[] seen = new boolean[PARTICLES];
        for (int r = 0; r < PARTICLES; r++) {
            int id = layout.i(records, r, CgVfxRecord.ID);
            if (layout.f(records, r, CgVfxRecord.LIFE) == 0f || id < 0 || id >= PARTICLES || seen[id]) continue;
            seen[id] = true;
            float[] s = seeded[id];
            float speed = (float) Math.sqrt(s[2] * s[2] + s[3] * s[3] + s[4] * s[4]);
            form.eval(ctx.particle(id).age(s[0] / s[1]).particleAge(s[0]).speed(speed), want);
            for (int c = 0; c < 3; c++) {
                float got = (layout.f(records, r, CgVfxRecord.VELOCITY + c) - s[2 + c]) / DT;
                float off = Math.abs(got - want[Math.min(c, want.length - 1)]) / (TOLERANCE * (1f + Math.abs(want[Math.min(c, want.length - 1)])));
                worst = Math.max(worst, off);
                if (!(off <= 1f)) {
                    fail(emitter.name() + ": particle " + id + " lane " + c + " is " + got + ", Java gives " + want[Math.min(c, want.length - 1)]);
                    return;
                }
            }
            checked++;
        }
        for (int id = 0; id < PARTICLES; id++) {
            if (!seen[id]) {
                fail(emitter.name() + ": particle " + id + " was not read back");
                return;
            }
        }
    }

    /**
     * Every particle of the field station: seeded at the origin, so each sits {@code -FIELD_AT} from the field's centre;
     * its velocity drawn toward the turned field's by tightness, then pushed by it for the step.
     */
    private void compareField(int e) {
        ByteBuffer records = read.get(e);
        CgVfxRecord layout = pools.get(e).record();
        float[] q = new float[4], conj = new float[4], local = new float[3], sample = new float[3], v = new float[3];
        FIELD_TURN.at(0f, 0f, q);
        conj[0] = -q[0];
        conj[1] = -q[1];
        conj[2] = -q[2];
        conj[3] = q[3];
        turn(conj, new float[]{-FIELD_AT[0], -FIELD_AT[1], -FIELD_AT[2]}, local);
        for (int a = 0; a < 3; a++) local[a] = ((local[a] - FIELD_TURN.pivot()[a]) / FIELD_HALF[a] + 1f) * 0.5f;
        FIELD.sample(local[0], local[1], local[2], sample);
        turn(q, sample, v);
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        float pull = FIELD_TIGHTNESS / (FIELD_TIGHTNESS + 0.001f);
        boolean[] seen = new boolean[PARTICLES];
        for (int r = 0; r < PARTICLES; r++) {
            int id = layout.i(records, r, CgVfxRecord.ID);
            if (layout.f(records, r, CgVfxRecord.LIFE) == 0f || id < 0 || id >= PARTICLES || seen[id]) continue;
            seen[id] = true;
            float[] s = seeded[id];
            for (int c = 0; c < 3; c++) {
                float drawn = s[2 + c] + (v[c] * FIELD_STRENGTH * pull - s[2 + c]) * FIELD_TIGHTNESS;
                float want = drawn + v[c] / l * (l * FIELD_STRENGTH) * DT;
                float got = layout.f(records, r, CgVfxRecord.VELOCITY + c);
                float off = Math.abs(got - want) / (TOLERANCE * (1f + Math.abs(want)));
                worst = Math.max(worst, off);
                if (!(off <= 1f)) {
                    fail("forms-field: particle " + id + " lane " + c + " is " + got + ", Java gives " + want);
                    return;
                }
            }
            checked++;
        }
    }

    /**
     * Every particle of an attractor station, seeded at the source with the pull exactly (strength, 0, 0) there: its
     * place after the step its seeded velocity plus the pull's step, times the step, at both; its velocity that too where
     * the pull lasts, and as seeded where it does not. The inverse-square pull is the strength times (r / d)^2 there.
     */
    private void compareAttract(int e) {
        boolean lasting = !emitters.get(e).name().endsWith("momentary");
        boolean gravity = emitters.get(e).name().endsWith("gravity");
        ByteBuffer records = read.get(e);
        CgVfxRecord layout = pools.get(e).record();
        double d = Math.sqrt(ATTRACT_AT[0] * ATTRACT_AT[0] + ATTRACT_AT[1] * ATTRACT_AT[1] + ATTRACT_AT[2] * ATTRACT_AT[2]);
        float pull = (float) (ATTRACT_STRENGTH * (gravity ? Math.min(1.0, GRAVITY_RADIUS * GRAVITY_RADIUS / (d * d)) : 1.0)) * DT;
        for (int r = 0; r < PARTICLES; r++) {
            int id = layout.i(records, r, CgVfxRecord.ID);
            if (layout.f(records, r, CgVfxRecord.LIFE) == 0f || id < 0 || id >= PARTICLES) continue;
            float[] s = seeded[id];
            for (int c = 0; c < 3; c++) {
                float moved = s[2 + c] + (c == 0 ? pull : 0f);
                float[] want = {moved * DT, lasting ? moved : s[2 + c]};
                float[] got = {layout.f(records, r, CgVfxRecord.POSITION + c), layout.f(records, r, CgVfxRecord.VELOCITY + c)};
                for (int k = 0; k < 2; k++) {
                    float off = Math.abs(got[k] - want[k]) / (TOLERANCE * (1f + Math.abs(want[k])));
                    worst = Math.max(worst, off);
                    if (!(off <= 1f)) {
                        fail(emitters.get(e).name() + ": particle " + id + "'s " + (k == 0 ? "place" : "velocity") + " lane " + c
                                + " is " + got[k] + ", Java gives " + want[k]);
                        return;
                    }
                }
            }
            checked++;
        }
    }

    /** {@code q}'s turn of {@code v} into {@code out}: {@code fx_field_turn}. */
    private static void turn(float[] q, float[] v, float[] out) {
        float cx = q[1] * v[2] - q[2] * v[1] + q[3] * v[0], cy = q[2] * v[0] - q[0] * v[2] + q[3] * v[1];
        float cz = q[0] * v[1] - q[1] * v[0] + q[3] * v[2];
        out[0] = v[0] + 2f * (q[1] * cz - q[2] * cy);
        out[1] = v[1] + 2f * (q[2] * cx - q[0] * cz);
        out[2] = v[2] + 2f * (q[0] * cy - q[1] * cx);
    }

    /** Every particle of spawn station {@code e}: its life, size, rotation, velocity or place, against the form's Java evaluation. */
    private void compareSpawn(int e) {
        CgVfxEmitter emitter = emitters.get(e);
        CgVfxSpawnForm form = spawnForms.get(e - seededStations);
        if (form == null) {
            if (emitter.name().equals("forms-spawn-triangle")) compareTriangle(e);
            else if (emitter.meshRotation() != null) compareOrientation(e);
            else compareSockets(e);
            return;
        }
        ByteBuffer records = read.get(e);
        CgVfxRecord layout = pools.get(e).record();
        CgVfxFormContext ctx = new CgVfxFormContext().seed(SEED_BITS).loop(LOOP, 0).parameter("user.Aim", AIM);
        boolean[] seen = new boolean[PARTICLES];
        for (int r = 0; r < PARTICLES; r++) {
            int id = layout.i(records, r, CgVfxRecord.ID);
            if (layout.f(records, r, CgVfxRecord.LIFE) == 0f || id < 0 || id >= PARTICLES || seen[id]) continue;
            seen[id] = true;
            int at = switch (form.target()) {
                case LIFETIME -> CgVfxRecord.LIFE;
                case SPRITE_SIZE -> CgVfxRecord.SIZE;
                case SPRITE_ROTATION -> CgVfxRecord.SPIN;
                case SPEED, VELOCITY -> CgVfxRecord.VELOCITY;
                case RADIUS, OFFSET, CYLINDER_HEIGHT -> CgVfxRecord.POSITION;
                case CONE_ANGLE -> throw new IllegalStateException("a cone's angle is not read back");
                case COLOR -> layout.word(CgVfxRecord.Tail.COLOR);
                case MESH_SCALE -> layout.word(CgVfxRecord.Tail.SCALE);
                case MESH_ORIENTATION -> layout.word(CgVfxRecord.Tail.TURN);
                case MESH_ROTATION_RATE -> layout.word(CgVfxRecord.Tail.TURN_RATE);
                case LIGHT_RADIUS -> layout.word(CgVfxRecord.Tail.LIGHT);
                case LIGHT_EXPONENT -> layout.word(CgVfxRecord.Tail.LIGHT) + 1;
                case ORBIT_OFFSET -> layout.word(CgVfxRecord.Tail.ORBIT);
                case ORBIT_ROTATION -> layout.word(CgVfxRecord.Tail.ORBIT) + 3;
                case ORBIT_RATE -> layout.word(CgVfxRecord.Tail.ORBIT) + 6;
            };
            CgVfxFormInput c = form.compiled();
            // An rgb colour's alpha is the row's: it stores none.
            for (int k = 0; k < Math.min(form.target().components(), Math.max(c.components(), 3)); k++) {
                float got = switch (form.target()) {
                    case SPEED, RADIUS -> length(layout, records, r, at);
                    case CYLINDER_HEIGHT -> 2f * Math.abs(layout.f(records, r, at + 1));
                    default -> layout.f(records, r, at + k);
                };
                float want = c.channel(ctx.particle(id), Math.min(k, c.components() - 1));
                // A negative speed launches the other way: its velocity's length is the value's size.
                if (form.target() == CgVfxSpawnInput.SPEED) want = Math.abs(want);
                float off = Math.abs(got - want) / (TOLERANCE * (1f + Math.abs(want)));
                worst = Math.max(worst, off);
                if (!(off <= 1f)) {
                    fail(emitter.name() + ": particle " + id + "'s " + form.target() + " lane " + k + " is " + got + ", Java gives " + want);
                    return;
                }
            }
            checked++;
        }
        for (int id = 0; id < PARTICLES; id++) {
            if (!seen[id]) {
                fail(emitter.name() + ": particle " + id + " was not read back");
                return;
            }
        }
    }

    /**
     * Every particle of the orientation station: the quaternion Look wrote into its drawn slots' scale, against Unreal's
     * rotator of its start, rolled per channel from its seed, plus its rate integrated over life through the falling
     * multiplier, {@code life * (t - t^2 / 2)}, plus the added rate times its age (a quaternion and its negation the same
     * turn); and its drawn scale, the size range rolled at its age, y and z locked, times its instance's bound length.
     */
    private void compareOrientation(int e) {
        ByteBuffer records = read.get(e), drawn = drawnRead.get(e);
        CgVfxRecord layout = pools.get(e).record();
        double turn = 2 * Math.PI;
        for (int r = 0; r < PARTICLES; r++) {
            int id = layout.i(records, r, CgVfxRecord.ID);
            if (layout.f(records, r, CgVfxRecord.LIFE) == 0f || id < 0 || id >= PARTICLES) continue;
            int bits = Float.floatToRawIntBits(layout.f(records, r, CgVfxRecord.SEED));
            float age = layout.f(records, r, CgVfxRecord.AGE), life = layout.f(records, r, CgVfxRecord.LIFE);
            double t = Math.min(age / life, 1.0);
            double[] rotator = new double[3];
            for (int c = 0; c < 3; c++) {
                double roll = CgVfxEmitterInstance.rand(bits, 0, 30 + c);
                rotator[c] = turn * (TURN_FROM[c] + (TURN_TO[c] - TURN_FROM[c]) * roll + TURN_RATE[c] * life * (t - t * t / 2));
            }
            rotator[0] += TURN_ADDED * age;
            int key = bits ^ Float.floatToRawIntBits(age);
            int[] lock = {0, 1, 1};
            int scaleAt = 2 * r * CgVfxRecord.DRAWN_BYTES + 5 * 16;
            for (int c = 0; c < 3; c++) {
                float want = (ROLL_FROM[c] + (ROLL_TO[c] - ROLL_FROM[c]) * (float) CgVfxEmitterInstance.rand(key, 0, 50 + lock[c]))
                        * LENGTH[c] * 0.5f;
                float got = drawn.getFloat(scaleAt + 4 * c), off = Math.abs(got - want) / (TOLERANCE * (1f + Math.abs(want)));
                worst = Math.max(worst, off);
                if (!(off <= 1f)) {
                    fail("spawn-orientation: particle " + id + "'s drawn scale " + c + " is " + got + ", its life roll gives " + want);
                    return;
                }
            }
            float lo = 1f - 0.5f * (float) t, hi = 2f + (float) t;
            float alpha = lo + (hi - lo) * (float) CgVfxEmitterInstance.rand(key, 1, 50);
            float gotAlpha = drawn.getFloat(2 * r * CgVfxRecord.DRAWN_BYTES + 4 * 16 + 12);
            float offAlpha = Math.abs(gotAlpha - alpha) / (TOLERANCE * (1f + alpha));
            worst = Math.max(worst, offAlpha);
            if (!(offAlpha <= 1f)) {
                fail("spawn-orientation: particle " + id + "'s drawn alpha is " + gotAlpha + ", its curved roll gives " + alpha);
                return;
            }
            float[] want = CgUnits.rotatorToHost(rotator[0], rotator[1], rotator[2]);
            int at = (2 * r + 1) * CgVfxRecord.DRAWN_BYTES + 5 * 16;
            double dot = 0;
            for (int c = 0; c < 4; c++) dot += want[c] * drawn.getFloat(at + 4 * c);
            float off = (float) ((1 - Math.abs(dot)) / 1e-5);
            worst = Math.max(worst, off);
            if (!(off <= 1f)) {
                fail("spawn-orientation: particle " + id + "'s quaternion is " + drawn.getFloat(at) + ", " + drawn.getFloat(at + 4) + ", "
                        + drawn.getFloat(at + 8) + ", " + drawn.getFloat(at + 12) + "; Java gives " + Arrays.toString(want));
                return;
            }
            checked++;
        }
    }

    /**
     * Every particle of the triangle station: inside it (its corner weights, read back from its place, none below 0; its
     * thickness along y), and its apex's weight above a half in about a sixth of them, Cascade's sum of three uniforms.
     */
    private void compareTriangle(int e) {
        ByteBuffer records = read.get(e);
        CgVfxRecord layout = pools.get(e).record();
        double hx = TRI_HEIGHT / 2, base = TRI_HEIGHT * Math.tan(TRI_APEX / 2), slack = 1e-4;
        int heavy = 0, n = 0;
        for (int r = 0; r < PARTICLES; r++) {
            int id = layout.i(records, r, CgVfxRecord.ID);
            if (layout.f(records, r, CgVfxRecord.LIFE) == 0f || id < 0 || id >= PARTICLES) continue;
            double x = layout.f(records, r, CgVfxRecord.POSITION), y = layout.f(records, r, CgVfxRecord.POSITION + 1);
            double z = layout.f(records, r, CgVfxRecord.POSITION + 2);
            double apex = (x / hx + 1) / 2, left = (1 - apex + z / base) / 2, right = (1 - apex - z / base) / 2;
            if (apex < -slack || left < -slack || right < -slack || Math.abs(y) > TRI_THICKNESS / 2 + slack) {
                fail("spawn-triangle: particle " + id + " at " + x + ", " + y + ", " + z + " is outside its triangle");
                return;
            }
            if (apex > 0.5) heavy++;
            n++;
            checked++;
        }
        double share = n == 0 ? 0 : (double) heavy / n;
        // 1/6 against an area's 1/4: binomial spread at 512 is about 0.017.
        if (n < PARTICLES || Math.abs(share - 1.0 / 6) > 0.05) {
            fail("spawn-triangle: " + n + " read back, the apex over half in " + share + " of them, Cascade's sum gives 1/6");
        }
    }

    /** Every particle of the skeleton station: absent at the unanswered point, else at its point plus its offset. */
    private void compareSockets(int e) {
        ByteBuffer records = read.get(e);
        CgVfxRecord layout = pools.get(e).record();
        boolean[] seen = new boolean[PARTICLES];
        for (int r = 0; r < PARTICLES; r++) {
            int id = layout.i(records, r, CgVfxRecord.ID);
            if (layout.f(records, r, CgVfxRecord.LIFE) == 0f || id < 0 || id >= PARTICLES || seen[id]) continue;
            seen[id] = true;
            int s = id % 3;
            if (s == 2) {
                fail("spawn-sockets: particle " + id + " spawned at the unanswered point");
                return;
            }
            for (int c = 0; c < 3; c++) {
                float got = layout.f(records, r, CgVfxRecord.POSITION + c), want = SOCKETS[s][c] + SOCKET_OFFSETS[s][c];
                float off = Math.abs(got - want) / (TOLERANCE * (1f + Math.abs(want)));
                worst = Math.max(worst, off);
                if (!(off <= 1f)) {
                    fail("spawn-sockets: particle " + id + " lane " + c + " is at " + got + ", its point puts it at " + want);
                    return;
                }
            }
            checked++;
        }
        for (int id = 0; id < PARTICLES; id++) {
            if (!seen[id] && id % 3 != 2) {
                fail("spawn-sockets: particle " + id + " was not read back");
                return;
            }
        }
    }

    private static float length(CgVfxRecord layout, ByteBuffer records, int r, int at) {
        float x = layout.f(records, r, at), y = layout.f(records, r, at + 1), z = layout.f(records, r, at + 2);
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    /** {@link #origin}, its source moved {@link #CARRIED} over the step. */
    private static CgVfxInstanceView moved() {
        CgVfxInstanceView at = origin();
        return new CgVfxInstanceView() {
            public double originX() { return at.originX(); }
            public double originY() { return at.originY(); }
            public double originZ() { return at.originZ(); }
            public float time() { return 0f; }
            public float sourceX() { return 0f; }
            public float sourceY() { return 0f; }
            public float sourceZ() { return 0f; }
            public float fromX() { return -CARRIED[0]; }
            public float fromY() { return -CARRIED[1]; }
            public float fromZ() { return -CARRIED[2]; }
        };
    }

    /**
     * Every particle of the source-movement station: its place its seeded velocity times the step, plus the source's
     * movement times the scale where it has lived past two steps (Cascade's). Those within a step of that age are left out.
     */
    private void compareCarried(int e) {
        ByteBuffer records = read.get(e);
        CgVfxRecord layout = pools.get(e).record();
        for (int r = 0; r < PARTICLES; r++) {
            int id = layout.i(records, r, CgVfxRecord.ID);
            if (layout.f(records, r, CgVfxRecord.LIFE) == 0f || id < 0 || id >= PARTICLES) continue;
            float[] s = seeded[id];
            if (Math.abs(s[0] - 2f * DT) < 2f * DT) continue;
            boolean carries = s[0] > 2f * DT;
            for (int c = 0; c < 3; c++) {
                float want = s[2 + c] * DT + (carries ? CARRY_SCALE[c] * CARRIED[c] : 0f);
                float got = layout.f(records, r, CgVfxRecord.POSITION + c);
                float off = Math.abs(got - want) / (TOLERANCE * (1f + Math.abs(want)));
                worst = Math.max(worst, off);
                if (!(off <= 1f)) {
                    fail(emitters.get(e).name() + ": particle " + id + "'s place lane " + c + " is " + got + ", Java gives " + want);
                    return;
                }
            }
            checked++;
        }
    }

    private static CgVfxInstanceView origin() {
        return new CgVfxInstanceView() {
            public double originX() { return ORIGIN[0]; }
            public double originY() { return ORIGIN[1]; }
            public double originZ() { return ORIGIN[2]; }
            public float time() { return 0f; }
            public float sourceX() { return 0f; }
            public float sourceY() { return 0f; }
            public float sourceZ() { return 0f; }
            public float loopSeconds() { return STEP_LOOP; }
            public float user(String name, int component, float fallback) {
                return name.equals("Length") ? LENGTH[component] : name.equals("Push") ? PUSH[component]
                        : name.equals("Aim") ? AIM[component] : fallback;
            }
            public boolean socket(int i, float[] out) {
                if (i >= SOCKETS.length) return false;
                System.arraycopy(SOCKETS[i], 0, out, 0, 3);
                return true;
            }
        };
    }

    private static ByteBuffer copy(ByteBuffer data) {
        ByteBuffer out = ByteBuffer.allocate(data.remaining()).order(ByteOrder.nativeOrder());
        out.put(data.duplicate()).flip();
        return out;
    }

    private void fail(String what) {
        if (failure == null) failure = what;
    }

    private void report() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = (device == null ? "gl" : device.name()) + " at " + CgCapabilities.detect().computeTier();
        int validation = PlatformServiceHarness.validationErrors();
        if (GlErrorChecker.checkAndLog("vfx-forms")) {
            System.out.println("[vfx-forms] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[vfx-forms] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (on.startsWith("recording")) {
            System.out.println("[vfx-forms] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (!compared) {
            System.out.println("[vfx-forms] FAIL on " + on + ": the records never arrived");
        } else if (failure != null) {
            System.out.println("[vfx-forms] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[vfx-forms] PASS on " + on + ": " + FORMS.size() + " module forms, a turned field, lasting, momentary and inverse-square attractors, " + SPAWNS.size() + " spawn forms, " + checked
                    + " particles, every lane the form's Java evaluation (worst share of the tolerance " + worst + ")");
        }
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
