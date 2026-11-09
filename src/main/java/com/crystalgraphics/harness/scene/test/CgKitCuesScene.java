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
import com.crystalgraphics.cue.CgCueInspector;
import com.crystalgraphics.cue.CgCueManager;
import com.crystalgraphics.cue.CgCueParameters;
import com.crystalgraphics.cue.CgCuePlaying;
import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.harness.camera.Camera3D;
import com.crystalgraphics.harness.camera.HarnessCameraShake;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgui.app.cues.CueSelection;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.post.volume.CgColourRegion;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
 * // more kits in the Cues picker: -Dcrystalgraphics.harness.kitCues.extraKits=kk=$R/assets/kk,rpgcore=$R/assets/rpgcore
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
 *   <li>Left Alt opens the harness's desktop, where CrystalGUI's Cues application is in the launcher. It plays
 *       by the engine's cue manager, apart from this scene's stepped hit.</li>
 *   <li>Under VFX Lab's sky and floor; F9 day and night.</li>
 *   <li>The dummy is an opaque world draw and the ground under VFX Lab's sky writes depth, so a colour region, a soft
 *       particle or a depth fade sees depth where the game would.</li>
 *   <li>{@code .fireEvery=<seconds>} (2) fires the cue again on that clock while nothing is pressed; 0 fires once.
 *       {@code .distance=<blocks>} (3) is the camera's distance from the hit, at eye height.</li>
 *   <li>{@code .regions=false} plays the cues' sequences without their colour regions.</li>
 *   <li>The cues and the sequences their bursts play load behind the loading screen, a task of its plan, until they
 *       are {@code READY}: a burst still loading 0.2 s after it fires plays nothing, as in the game.</li>
 *   <li>The HUD's age is the hit's time over its duration: its longest sequence's, or when its last system completed
 *       ({@code ?} until it has); it holds at the end.</li>
 *   <li>A step forward pauses and simulates one frame; a step back, and a step past the end, seek the hit (its
 *       sequences and particle systems), which keeps its seeds: only a fire (`, N, B) draws new ones.</li>
 *   <li>A pixel check of the frame is the caller's: {@code .capture=<n>} writes frames 1 to n of play as
 *       {@code kit-cues-f<k>.png}.</li>
 * </ul>
 */
public final class CgKitCuesScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private static final String TAG = "[kit-cues] ";
    /** The dummy's feet and the hit at its chest, the camera on +z looking down -z. */
    private static final double HIT_X = 0, HIT_Y = 1.2, HIT_Z = 0;
    private static final float[] SPEEDS = {0f, 0.01f, 0.05f, 0.1f, 0.25f, 0.5f, 1f};

    private final List<String> ids = new ArrayList<>();
    private final List<CgAssetRef<CgCueDefinition>> cues = new ArrayList<>();
    /** The sequences the cues' bursts play, loaded before the first fire: a burst whose sequence is not loaded plays nothing. */
    private final List<CgAssetRef<CgSequenceDefinition>> sequences = new ArrayList<>();
    private CgTickFunction submit;
    private boolean sequencesDeclared;
    private final CueSelection selection = CueSelection.client();
    /**
     * Steve's model as one box: arm to arm 16 px, feet to head 32, the head 8 deep (HumanoidModel), drawn at 0.9375
     * of a block per 16 px (PlayerRenderer.scale).
     */
    private static final float STEVE = 0.9375f / 16f;
    private final Matrix4f dummyShape = new Matrix4f().translate(0f, 16 * STEVE, 0f).scale(16 * STEVE, 32 * STEVE, 8 * STEVE);
    private final float fireEvery = Float.parseFloat(System.getProperty("crystalgraphics.harness.kitCues.fireEvery", "2"));
    private final int capture = Integer.getInteger("crystalgraphics.harness.kitCues.capture", 0);
    private HarnessKitSystems kit;
    private boolean shakeWasOn;
    private final List<HarnessKitSystems> extraKits = new ArrayList<>();
    /** What the extra kits load: a failure there is named and skipped, as the main kit's is fatal. */
    private final Set<CgAssetRef<?>> extras = new HashSet<>(), warned = new HashSet<>();
    private CgSequencer sequencer;
    /** Takes the HUD's keys off the desktop's left keys at the end. */
    private Runnable keysLeft;
    private CgMaterial dummy;
    private CgMesh cube;
    private int speed = SPEEDS.length - 1, frames;
    /** The speed a pause left, which Tab plays at again. */
    private int resumeSpeed = SPEEDS.length - 1;
    private String namespace;
    private boolean extrasDeclared, declared, loaded, started, fireNow, shift, running = true;
    /** The engine's transport over the last burst: its sequences and particle systems, paused, stepped and sought. */
    private CgCueInspector inspector;

    @Override
    public void init(HarnessContext ctx) {
        namespace = require("namespace");
        String folder = require("kit");
        for (String id : System.getProperty("crystalgraphics.harness.kitCues.cues", "").split(",")) {
            if (!id.isBlank()) ids.add(namespace + ":" + id.trim());
        }
        float distance = Float.parseFloat(System.getProperty("crystalgraphics.harness.kitCues.distance", "3"));
        ctx.getCamera3D().moveCamera((float) HIT_X, 1.62f, (float) HIT_Z + distance);
        ctx.getCamera3D().setPitch(-5f);
        selection.at(HIT_X, HIT_Y, HIT_Z).normal(0f, 0f, 1f);
        // A cue's camera shake reaches the view only while the harness camera applies it, and C is this scene's grade key
        shakeWasOn = HarnessCameraShake.INSTANCE.on();
        if (!shakeWasOn) HarnessCameraShake.INSTANCE.toggle();
        kit = HarnessKitSystems.load(CgVfx.world(), namespace, Paths.get(folder), List.of());
        // Further kits, for the picker: registered beside the first, not stepped through or waited on
        for (String entry : System.getProperty("crystalgraphics.harness.kitCues.extraKits", "").split(",")) {
            int eq = entry.indexOf('=');
            if (eq > 0) extraKits.add(HarnessKitSystems.load(CgVfx.world(), entry.substring(0, eq).trim(), Paths.get(entry.substring(eq + 1).trim()), List.of()));
        }
        // The engine's sequencer, as a client's: a burst's sequences play through it
        sequencer = CgVfxSequencer.install(CgWorld.client(), CgVfx.world());
        // Made before any burst: it follows only what plays after it
        inspector = CgCueInspector.client();
        // The harness has no player or level, so an in-world shake would find no viewer and never play: the camera is it
        Camera3D camera = ctx.getCamera3D();
        CgCueManager.client().viewer(out -> {
            out[0] = camera.getPosX();
            out[1] = camera.getPosY();
            out[2] = camera.getPosZ();
            return true;
        });
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
            for (String id : ids) {
                CgAssetRef<CgCueDefinition> ref = CgVfxAssets.instance().declare(CgGameplayKinds.CUE, id);
                ref.request(CgLoadPriority.MOVE);
                cues.add(ref);
            }
        }
        // The other kits' cues load behind the loading screen too, so a pick in the picker plays at once
        if (declared && !extrasDeclared) {
            boolean scanned = true;
            for (HarnessKitSystems extra : extraKits) scanned &= extra.declared() || extra.failed();
            if (scanned) {
                extrasDeclared = true;
                for (CgAssetEntry e : CgVfxAssets.instance().registry().entries(CgGameplayKinds.CUE)) {
                    if (e.namespace().equals(namespace)) continue;
                    CgAssetRef<CgCueDefinition> ref = CgVfxAssets.instance().declare(CgGameplayKinds.CUE, e.id());
                    ref.request(CgLoadPriority.MOVE);
                    cues.add(ref);
                    extras.add(ref);
                }
            }
        }
        if (!loaded && declared && extrasDeclared && allLoaded()) loaded = true;
    }

    private static String require(String key) {
        String v = System.getProperty("crystalgraphics.harness.kitCues." + key);
        if (v == null) throw new IllegalStateException(TAG + "-Dcrystalgraphics.harness.kitCues." + key + " is required");
        return v;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // Paused, the inspector owns the clock (it releases one queued step a frame); playing, the speed is ours, set
        // after unpausing since the inspector puts back what it found
        if (speed == 0) {
            if (!inspector.paused()) inspector.setPaused(true);
        } else {
            if (inspector.paused()) inspector.setPaused(false);
            CgWorld.client().timeDilation(SPEEDS[speed]);
        }
        // The session may not be up in init: listen for the keys the desktop leaves from the first frame
        if (keysLeft == null && HostSession.isInstalled()) {
            HostSession session = HostSession.session();
            Runnable left = session.onKeyLeft(this::hudKey);
            // Tab moves the desktop's focus: the pause key is taken before it, never while a field is typed in
            Runnable hot = session.onHotKey(key -> key.key() == CgKeyCodes.KEY_TAB && hudKey(key));
            keysLeft = () -> {
                left.run();
                hot.run();
            };
        }
        prepare();
        if (kit.failed()) {
            System.out.println(TAG + "FAIL: " + kit.failures());
            running = false;
        } else if (!started && loaded) {
            started = true;
            fireNow = true;
            System.out.println(TAG + cues.size() + " cues ready: " + ids);
        }
        if (started) {
            if (fireNow || fireEvery > 0 && selection.age() >= fireEvery) fire();
            frames++;
            if (capture > 0 && frames <= capture) ctx.getArtifactService().requestCapture(String.format(Locale.ROOT, "f%02d", frames));
            if (capture > 0 && frames > capture) running = false;
        }
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    private boolean allLoaded() {
        for (CgAssetRef<CgCueDefinition> ref : cues) {
            if (ref.failure() != null) {
                if (!skipped(ref)) return false;
                continue;
            }
            if (!ref.ready()) return false;
        }
        if (!sequencesDeclared) {
            sequencesDeclared = true;
            for (CgAssetRef<CgCueDefinition> ref : cues) {
                if (ref.failure() != null) continue;
                for (CgCueDefinition.SequenceEntry e : ref.loaded().burst().sequences()) {
                    CgAssetRef<CgSequenceDefinition> seq = CgVfxAssets.instance().declare(CgSequenceKinds.SEQUENCE, e.sequence());
                    seq.request(CgLoadPriority.MOVE);
                    sequences.add(seq);
                    if (extras.contains(ref)) extras.add(seq);
                }
            }
        }
        for (CgAssetRef<CgSequenceDefinition> seq : sequences) {
            if (seq.failure() != null) {
                if (!skipped(seq)) return false;
                continue;
            }
            if (!seq.ready()) return false;
        }
        return true;
    }

    /** A failed extra-kit asset is named once and left out; the main kit's stops the scene. False when it stopped it. */
    private boolean skipped(CgAssetRef<?> ref) {
        if (extras.contains(ref)) {
            if (warned.add(ref)) System.out.println(TAG + "WARN, skipped: " + ref.failure());
            return true;
        }
        System.out.println(TAG + "FAIL: " + ref.failure());
        running = false;
        return false;
    }

    private void fire() {
        fireNow = false;
        if (selection.current().isEmpty()) selection.play(ids.get(0));
        else selection.fire();
    }

    /**
     * Pauses and steps the hit {@code frames} frames, through the engine's inspector: forward simulates one a frame, back
     * seeks, its sequences and particle systems both, keeping their seeds; round from either end.
     */
    private void pause() {
        if (speed > 0) resumeSpeed = speed;
        speed = 0;
    }

    private void step(int frames) {
        if (!started) return;
        pause();
        inspector.step(frames);
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        hudKey(event);
        return true;
    }

    /** How far a press of , or . slides every adapting colour region's night boost. */
    private static final float OFFSET_STEP = 0.05f;

    /** The HUD's keys, from the window and, with the desktop up, from the keys it left; true if one was this scene's. */
    private boolean hudKey(CgSystemInput.Keyboard.Event event) {
        int key = event.key();
        if (key == CgKeyCodes.KEY_LSHIFT || key == CgKeyCodes.KEY_RSHIFT) shift = event.pressed();
        if (!event.pressed()) return false;
        // A held step key steps once a repeat, as the OS repeats it; every other key acts on its press alone
        if (key == CgKeyCodes.KEY_X || key == CgKeyCodes.KEY_Z) {
            step(key == CgKeyCodes.KEY_X ? 1 : -1);
            return true;
        }
        // Held, the night gain slides a step a repeat, as Step does
        if (key == CgKeyCodes.KEY_COMMA || key == CgKeyCodes.KEY_PERIOD) {
            float step = key == CgKeyCodes.KEY_PERIOD ? OFFSET_STEP : -OFFSET_STEP;
            CgColourRegion.gainTuning(Math.round((CgColourRegion.gainTuning() + step) / OFFSET_STEP) * OFFSET_STEP);
            return true;
        }
        if (event.repeat()) return false;
        switch (key) {
            case CgKeyCodes.KEY_GRAVE -> fireNow = started;
            case CgKeyCodes.KEY_N -> choose((index() + 1) % Math.max(1, ids.size()));
            case CgKeyCodes.KEY_B -> choose((index() + ids.size() - 1) % Math.max(1, ids.size()));
            case CgKeyCodes.KEY_Y -> speed = shift ? Math.max(0, speed - 1) : Math.min(SPEEDS.length - 1, speed + 1);
            case CgKeyCodes.KEY_TAB -> {
                if (speed == 0) speed = resumeSpeed;
                else pause();
            }
            case CgKeyCodes.KEY_C -> CgWorldRenderer.get().gradeDraws(!CgWorldRenderer.get().gradeDraws());
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Makes {@code index} the cue and fires it. */
    private void choose(int index) {
        if (started) selection.play(ids.get(index));
    }

    /** The picked cue's place in {@code ids}; the first before one is picked. */
    private int index() {
        return Math.max(0, ids.indexOf(selection.current()));
    }

    /** The HUD's lines in its own form, a name, its keys, then its state: what plays, its clock, what it is drawn under. */
    @Override
    public String hudLine() {
        String cue = selection.current().isEmpty() ? "-" : selection.current();
        return String.format(Locale.ROOT, "%nCue [N/B]: %s | Fire [`]%n"
                        + "Time [Tab]: %s | Speed [Y]: x%s | Step [Z X]%n"
                        + "Age: %.3f / %s s | %s | fires %d | hit players %d%n"
                        + "Kit colour grade [C]: %s | Night gain [, .]: x%.2f",
                cue, speed == 0 ? "paused" : "playing", SPEEDS[speed],
                inspector.age(),
                Double.isNaN(inspector.duration()) ? "?" : String.format(Locale.ROOT, "%.3f", inspector.duration()),
                selection.state(), selection.fires(), selection.hit().size(),
                CgWorldRenderer.get().gradeDraws() ? "on" : "off", CgColourRegion.gainTuning()).replace("\r", "");
    }

    @Override
    public boolean hudShowsShakeAndGlow() {
        return false;
    }

    @Override
    public void dispose() {
        if (!shakeWasOn && HarnessCameraShake.INSTANCE.on()) HarnessCameraShake.INSTANCE.toggle();
        if (submit != null) submit.close();
        if (keysLeft != null) keysLeft.run();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean editorEnvironment() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return capture > 0; }
}
