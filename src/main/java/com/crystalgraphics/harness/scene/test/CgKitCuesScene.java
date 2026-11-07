package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.asset.kind.CgGameplayKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.registry.CgAssetEntry;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.cue.CgCueDefinition;
import com.crystalgraphics.cue.CgCueManager;
import com.crystalgraphics.cue.CgCueParameters;
import com.crystalgraphics.cue.CgCuePlaying;
import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.ui.HarnessUi;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.system.CgVfxSequencer;
import org.joml.Matrix4f;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A kit's cues fired at a dummy standing on the harness's ground, through the engine's own path: the client world's cue manager,
 * its sequencer with the engine's spawners (systems, post volumes, colour regions, shakes) and the kit's look. What a
 * mod's hit plays in Minecraft, without launching it. Scene id {@code kit-cues}.
 *
 * <pre>{@code
 * // Dragon Forge's s0 hit sparks, from its resources
 * R=X:/projects/Dragon-Forge-NeoForge/RPG-Core-NeoForge/src/main/resources
 * ./gradlew :gl-debug-harness:runHarness -Pharness.assetRoots=$R --args="--mode=kit-cues" \
 *     -Dcrystalgraphics.harness.kitCues.namespace=s0 -Dcrystalgraphics.harness.kitCues.kit=$R/assets/s0 \
 *     -Dcrystalgraphics.harness.kitCues.cues=hit_spark/melee/light,hit_spark/melee/medium,hit_spark/melee/heavy
 * // `: fire again   N / B: next / previous cue   Left Alt: desktop   Y / Shift+Y: time faster / slower   Tab: pause or play time
 * // X / Z: pause and step 1/60 s forward / back, held to repeat; 0 to the end less 1/60 s, and round again
 * // C: the kit's colour grade on or off (each draw under its kit's look, or the frame's)
 *
 * // Unattended: the medium hit at frame 1 of play, frames 1 to 30 photographed, then it exits
 * ... -Dcrystalgraphics.harness.kitCues.cues=hit_spark/melee/medium -Dcrystalgraphics.harness.kitCues.capture=30 \
 *     -Dcrystalgraphics.harness.fixedDelta=0.0166667
 * }</pre>
 *
 * <ul>
 *   <li>A cue is named by its path under the kit's {@code cues/}; it fires by its own {@code tag}. Without
 *       {@code .cues}, every cue the kit declares, all loaded behind the loading screen.</li>
 *   <li>Left Alt opens the harness's desktop, where the cue picker is in the launcher, a tree a namespace: a click on a cue plays it, on a
 *       folder folds it. its X closes it, Esc the desktop.</li>
 *   <li>Under VFX Lab's sky and floor; F9 day and night.</li>
 *   <li>The dummy is an opaque world draw and the ground under VFX Lab's sky writes depth, so a colour region, a soft
 *       particle or a depth fade sees depth where the game would.</li>
 *   <li>{@code .fireEvery=<seconds>} (2) fires the cue again on that clock while nothing is pressed; 0 fires once.
 *       {@code .distance=<blocks>} (3) is the camera's distance from the hit, at eye height.</li>
 *   <li>{@code .regions=false} plays the cues' sequences without their colour regions.</li>
 *   <li>The cues and the sequences their bursts play load behind the loading screen, a task of its plan, until they
 *       are {@code READY}: a burst still loading 0.2 s after it fires plays nothing, as in the game.</li>
 *   <li>The HUD's age is the hit's time over its duration, its longest sequence's length; it holds at the end.</li>
 *   <li>A step forward pauses and simulates one frame; a step back, and a step past the end, seek the hit, which
 *       keeps its seeds: only a fire (`, N, B) draws new ones.</li>
 *   <li>A pixel check of the frame is the caller's: {@code .capture=<n>} writes frames 1 to n of play as
 *       {@code kit-cues-f<k>.png}.</li>
 * </ul>
 */
public final class CgKitCuesScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private static final String TAG = "[kit-cues] ";
    /** The dummy's feet and the hit at its chest, the camera on +z looking down -z. */
    private static final double HIT_X = 0, HIT_Y = 1.2, HIT_Z = 0;
    private static final float[] SPEEDS = {0f, 0.01f, 0.05f, 0.1f, 0.25f, 0.5f, 1f};
    /** A paused step, in the hit's seconds. */
    private static final double STEP = 1.0 / 60.0;

    private final List<String> ids = new ArrayList<>();
    private final List<CgAssetRef<CgCueDefinition>> cues = new ArrayList<>();
    /** The sequences the cues' bursts play, loaded before the first fire: a burst whose sequence is not loaded plays nothing. */
    private final List<CgAssetRef<CgSequenceDefinition>> sequences = new ArrayList<>();
    private CgTickFunction submit;
    private boolean sequencesDeclared;
    private final CgCueParameters params = new CgCueParameters();
    /**
     * Steve's model as one box: arm to arm 16 px, feet to head 32, the head 8 deep (HumanoidModel), drawn at 0.9375
     * of a block per 16 px (PlayerRenderer.scale).
     */
    private static final float STEVE = 0.9375f / 16f;
    private final Matrix4f dummyShape = new Matrix4f().translate(0f, 16 * STEVE, 0f).scale(16 * STEVE, 32 * STEVE, 8 * STEVE);
    private final float fireEvery = Float.parseFloat(System.getProperty("crystalgraphics.harness.kitCues.fireEvery", "2"));
    private final int capture = Integer.getInteger("crystalgraphics.harness.kitCues.capture", 0);
    private HarnessKitSystems kit;
    private CgSequencer sequencer;
    /** The looping cue playing, stopped as the next fires. */
    private CgCuePlaying looping;
    private CgMaterial dummy;
    private CgMesh cube;
    private int current, speed = SPEEDS.length - 1, frames;
    private String namespace;
    private HarnessUi ui;
    /** The picker, made once the cues are known; the document's thread uses it. */
    private CueSelector selector;
    /** The cue a click picked, for the render thread to fire; -1 for none. */
    private volatile int picked = -1;
    private double sinceFire;
    private boolean declared, loaded, started, fireNow, shift, running = true;
    /** The sequences the last fire plays, and the longest one's length in seconds: the hit's duration. */
    private final List<CgSequencePlayer> hit = new ArrayList<>();
    private double duration;
    /** The players before the last fire: the cue plays in the world's frame, so its own are the ones after it. */
    private final List<CgSequencePlayer> before = new ArrayList<>();
    private boolean collecting;
    /** Steps forward asked for and not yet simulated: one a frame, so a held key's repeats each step once. */
    private int steps;

    @Override
    public void init(HarnessContext ctx) {
        namespace = require("namespace");
        String folder = require("kit");
        for (String id : System.getProperty("crystalgraphics.harness.kitCues.cues", "").split(",")) {
            if (!id.isBlank()) ids.add(namespace + ":" + id.trim());
        }
        ui = ctx.getUi();
        float distance = Float.parseFloat(System.getProperty("crystalgraphics.harness.kitCues.distance", "3"));
        ctx.getCamera3D().moveCamera((float) HIT_X, 1.62f, (float) HIT_Z + distance);
        ctx.getCamera3D().setPitch(-5f);
        kit = HarnessKitSystems.load(CgVfx.world(), namespace, Paths.get(folder), List.of());
        // The engine's sequencer, as a client's: a burst's sequences play through it
        sequencer = CgVfxSequencer.install(CgWorld.client(), CgVfx.world());
        // Its colour regions left out, to tell what they grade from what the rest of the hit does
        if (!Boolean.parseBoolean(System.getProperty("crystalgraphics.harness.kitCues.regions", "true"))) {
            sequencer.spawner("colour_region", (player, spawnable, at) -> new CgSequenceSpawnScene.Fixture());
        }
        dummy = CgMaterial.load("assets/harness/shader/bloom_wall.shader");
        cube = CgMeshShapes.cube();
        // Submitted in the world's frame, as a mod's draws are: before it, the world's frame drops them
        submit = CgWorld.client().schedule().add(CgTickGroup.SUBMIT, "harness:kit-cues", dt -> {
            CgWorldRenderer.get().draw(cube, dummy).at(HIT_X, 0, HIT_Z - 0.4).transform(dummyShape).submit();
        });
        ctx.getLoadPlan().task("harness:kit-cues").render(task -> {
            prepare();
            return loaded || !running || kit.failed();
        }).start();
    }

    /** Declares the cues once the kit is scanned, then the sequences their bursts play, until all are loaded. */
    private void prepare() {
        if (!declared && kit.declared()) {
            declared = true;
            if (ids.isEmpty()) {
                for (CgAssetEntry e : CgVfxAssets.instance().registry().entries(CgGameplayKinds.CUE)) {
                    if (e.namespace().equals(namespace)) ids.add(e.id());
                }
                if (ids.isEmpty()) throw new IllegalStateException(TAG + "the kit " + namespace + " declares no cue");
            }
            selector = new CueSelector(ids, index -> picked = index);
            ui.offer(selector.kind());
            for (String id : ids) {
                CgAssetRef<CgCueDefinition> ref = CgVfxAssets.instance().declare(CgGameplayKinds.CUE, id);
                ref.request(CgLoadPriority.MOVE);
                cues.add(ref);
            }
        }
        if (!loaded && declared && allLoaded()) loaded = true;
    }

    private static String require(String key) {
        String v = System.getProperty("crystalgraphics.harness.kitCues." + key);
        if (v == null) throw new IllegalStateException(TAG + "-Dcrystalgraphics.harness.kitCues." + key + " is required");
        return v;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // A step simulates exactly STEP in one frame: the frame's dilation is what moves the world on by it. A fire's
        // own frame moves nothing, so stepping from it starts at the cue's frame 0
        double dt = frame.getDeltaTime();
        boolean stepping = speed == 0 && dt > 0 && !fireNow && !collecting && steps > 0;
        if (stepping) steps--;
        double rate = stepping ? STEP / dt : SPEEDS[speed];
        CgWorld.client().timeDilation((float) rate);
        prepare();
        if (kit.failed()) {
            System.out.println(TAG + "FAIL: " + kit.failures());
            running = false;
        } else if (!started && loaded) {
            started = true;
            fireNow = true;
            System.out.println(TAG + cues.size() + " cues ready: " + ids);
        }
        int pick = picked;
        if (pick >= 0) {
            picked = -1;
            choose(pick);
        }
        if (started) {
            if (collecting) collect();
            sinceFire += stepping ? STEP : dt * SPEEDS[speed];
            if (fireNow || fireEvery > 0 && sinceFire >= fireEvery) fire();
            frames++;
            if (capture > 0 && frames <= capture) ctx.getArtifactService().requestCapture(String.format(Locale.ROOT, "f%02d", frames));
            if (capture > 0 && frames > capture) running = false;
        }
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    private boolean allLoaded() {
        for (CgAssetRef<CgCueDefinition> ref : cues) {
            if (ref.failure() != null) {
                System.out.println(TAG + "FAIL: " + ref.failure());
                running = false;
                return false;
            }
            if (!ref.ready()) return false;
        }
        if (!sequencesDeclared) {
            sequencesDeclared = true;
            for (CgAssetRef<CgCueDefinition> ref : cues) {
                for (CgCueDefinition.SequenceEntry e : ref.loaded().burst().sequences()) {
                    CgAssetRef<CgSequenceDefinition> seq = CgVfxAssets.instance().declare(CgSequenceKinds.SEQUENCE, e.sequence());
                    seq.request(CgLoadPriority.MOVE);
                    sequences.add(seq);
                }
            }
        }
        for (CgAssetRef<CgSequenceDefinition> seq : sequences) {
            if (seq.failure() != null) {
                System.out.println(TAG + "FAIL: " + seq.failure());
                running = false;
                return false;
            }
            if (!seq.ready()) return false;
        }
        return true;
    }

    private void fire() {
        fireNow = false;
        sinceFire = 0;
        CgCueDefinition cue = cues.get(current).loaded();
        if (cue.tag() == null) throw new IllegalStateException(TAG + cue.id() + " has no tag to fire it by");
        params.reset().at(HIT_X, HIT_Y, HIT_Z).normal(0f, 0f, 1f);
        // The last hit gone at once: a fire starts the hit again, with new seeds
        for (CgSequencePlayer p : hit) p.kill();
        CgVfx.world().clear();
        // A burst is executed once; a looping cue is added, and the last one stops as the next fires
        before.clear();
        before.addAll(sequencer.players());
        collecting = true;
        if (cue.kind() == CgCueDefinition.Kind.BURST) {
            CgCueManager.client().execute(cue.tag(), params);
        } else {
            if (looping != null) looping.remove();
            looping = CgCueManager.client().add(cue.tag(), params);
        }
    }

    /** The hit's players, the ones its fire added, and its duration. */
    private void collect() {
        collecting = false;
        hit.clear();
        duration = 0;
        for (CgSequencePlayer p : sequencer.players()) {
            if (before.contains(p) || p.stopped()) continue;
            hit.add(p);
            CgSequenceDefinition d = p.definition();
            duration = Math.max(duration, d.seconds(d.end() - d.start()));
        }
    }

    /** Where the hit stands once the steps forward still queued are simulated. */
    private double at() {
        return Math.min(sinceFire, duration) + steps * STEP;
    }

    /**
     * Pauses and steps forward a frame, simulated. The frames run from 0 to the end less a frame and round again: from
     * the last, it seeks back to 0; from a hit played out to its end, to its first frame. Seeks keep the hit's seeds.
     */
    private void stepForward() {
        if (!started || duration <= 0) return;
        speed = 0;
        double from = at();
        if (from >= duration - 1e-6) seek(STEP);
        else if (from >= duration - STEP - 1e-6) seek(0);
        else steps++;
    }

    /** Pauses and seeks a frame back; from 0, or the end, to the end less a frame. */
    private void stepBack() {
        if (!started || duration <= 0) return;
        speed = 0;
        double from = at();
        seek(from >= duration - 1e-6 || from <= 1e-6 ? duration - STEP : from - STEP);
    }

    /**
     * The hit at {@code seconds}, as a scrub lands: each of its sequences sought there, and each system they play
     * played again from its age with the seed it drew, so the frame is the one play showed.
     */
    private void seek(double seconds) {
        steps = 0;
        for (CgSequencePlayer p : hit) p.seek(seconds);
        sinceFire = seconds;
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        int key = event.key();
        if (key == CgKeyCodes.KEY_LSHIFT || key == CgKeyCodes.KEY_RSHIFT) shift = event.pressed();
        if (!event.pressed()) return true;
        // A held step key steps once a repeat, as the OS repeats it; every other key acts on its press alone
        if (key == CgKeyCodes.KEY_X || key == CgKeyCodes.KEY_Z) {
            if (key == CgKeyCodes.KEY_X) stepForward();
            else stepBack();
            return true;
        }
        if (event.repeat()) return true;
        switch (key) {
            case CgKeyCodes.KEY_GRAVE -> fireNow = started;
            case CgKeyCodes.KEY_N -> choose((current + 1) % Math.max(1, ids.size()));
            case CgKeyCodes.KEY_B -> choose((current + ids.size() - 1) % Math.max(1, ids.size()));
            case CgKeyCodes.KEY_Y -> speed = shift ? Math.max(0, speed - 1) : Math.min(SPEEDS.length - 1, speed + 1);
            case CgKeyCodes.KEY_TAB -> speed = speed == 0 ? SPEEDS.length - 1 : 0;
            case CgKeyCodes.KEY_C -> CgWorldRenderer.get().gradeDraws(!CgWorldRenderer.get().gradeDraws());
            default -> {
            }
        }
        return true;
    }

    /** Makes {@code index} the cue and fires it, lit in the picker if it was made. */
    private void choose(int index) {
        current = index;
        fireNow = started;
        CueSelector shown = selector;
        if (shown != null) HostSession.session().inDocument(() -> shown.current(index));
    }

    /** The HUD's lines in its own form, a name, its keys, then its state: what plays, its clock, what it is drawn under. */
    @Override
    public String hudLine() {
        String cue = ids.isEmpty() ? "-" : ids.get(current);
        return String.format(Locale.ROOT, "%nCue [N/B]: %s | Fire [`]%n"
                        + "Time [Tab]: %s | Speed [Y]: x%s | Step [Z X]%n"
                        + "Age: %.3f / %.3f s%n"
                        + "Kit colour grade [C]: %s",
                cue, speed == 0 ? "paused" : "playing", SPEEDS[speed], Math.min(sinceFire, duration), duration,
                CgWorldRenderer.get().gradeDraws() ? "on" : "off").replace("\r", "");
    }

    @Override
    public boolean hudShowsShakeAndGlow() {
        return false;
    }

    @Override
    public void dispose() {
        if (submit != null) submit.close();
        if (looping != null) looping.remove();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean editorEnvironment() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return capture > 0; }
}
