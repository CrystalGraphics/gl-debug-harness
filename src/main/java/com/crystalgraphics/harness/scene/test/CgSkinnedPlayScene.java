package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.loading.CgLoadingProcess;
import com.crystalgraphics.loading.CgLoadingScreen;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgraphics.loading.task.CgLoadTask;
import com.crystalgraphics.animation.CgAnimLayer;
import com.crystalgraphics.animation.CgAnimation;
import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeletonDefinition;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.audio.CgAudioEngine;
import com.crystalgraphics.camera.CgCameraPose;
import com.crystalgraphics.camera.CgCameraSystem;
import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.camera.Camera3D;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.config.RuntimeServices;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.post.CgPostMaterial;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.stage.CgDirectionalLights;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.render.post.stage.CgStage;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.binding.CgRoleOutline;
import com.crystalgraphics.sequence.binding.CgRoleRim;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.animation.CgSequenceSkeletal;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxOwner;
import com.crystalgraphics.vfx.system.CgVfxSequencer;
import com.crystalgraphics.cue.CgCueManager;
import com.crystalgraphics.world.CgAttachment;
import org.joml.Quaternionf;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.reply.CgReply;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Skinned meshes playing their clips, to look at and play with: Kakarot's Goku and his move clips, and Kurama with
 * his. Each is a {@link CgAnimInstance} posed by {@link CgAnimation} in {@code POSE} and drawn skinned on the GPU.
 *
 * <ul>
 *   <li>Tab picks Goku or Kurama; N and B its next and previous clip, cross-fading over 0.25 s.</li>
 *   <li>P pauses and plays; {@code ,} and {@code .} step a frame back and on (a seek: the pose playing reaches);
 *       U and I play slower and faster; R restarts the clip.</li>
 *   <li>K on Goku plays Kakarot's Kamehameha: the kit's charge and shot montages played through the client world's
 *       {@link CgSequencer}, Goku their caster, their clips on his montage slot and their systems on his sockets, each on
 *       its tick. K charges (the loop held), K again fires (the shot's loop held), K again ends the shot.</li>
 *   <li>{@code crystalgraphics.harness.skinned.goku} (the kit's {@code Cpl001p1c01s1.glb}) and {@code .goku.clips}
 *       (its {@code ANM/cpl/C001A/BTL/SKL} folder); {@code .kurama} (NSUNS4's {@code 5nrvbod2.glb}). One that is
 *       missing is left out.</li>
 * </ul>
 */
public class CgSkinnedPlayScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private static final Logger LOGGER = LogManager.getLogger(CgSkinnedPlayScene.class);
    private static final CgVfxOwner VFX = CgVfx.owner("harness");
    private static final String KIT_ROOT = "plan/crystalgraphics/vfx-aaa/local/examples/kakarot_kit/";
    private static final String KIT = KIT_ROOT + "models/Art/";
    private static final String KIT_NAMESPACE = "kakarot_kit";
    /** Goku's sockets: the kit's skeleton asset, in its units.yaml's units. */
    private static final String GOKU_SKELETON = "skeletons/Art/MDL/Cpl/Cpl001/Model/Cpl001p1_Skeleton.yaml";
    private static final float FADE = 0.25f, FRAME = 1f / 30f;
    /** Kakarot's face layer (AB_C000): FacialSlot over facialbase. */
    private static final String FACE_SLOT = "FacialSlot", FACE_BRANCH = "facialbase";
    /** Seconds of timeline the blinks are laid over; the face loops alone after. */
    private static final double BLINKS_FOR = 600.0;
    private static final long BLINK_SEED = 0x6B616B61L;
    /** K's Kamehameha: the kit's charge and shot montages, sequences; the shot's loop ends at its {@code End} section. */
    private static final String CHARGE ="kakarot_kit:Blueprints/Character/C001A/BTL/SKL/AM_C001A_S_SKL001_01",
            SHOT = "kakarot_kit:Blueprints/Character/C001A/BTL/SKL/AM_C001A_S_SKL001_02", SHOT_END = "End";
    /** X's cut: the Kamehameha's cinematic montage, its face picked by Goku's form (SQ8). */
    private static final String CUT = "kakarot_kit:Blueprints/Character/C001A/BTL/SKL/AM_C001A_S_SKL001_Cut";
    /** The cut's camera animation: what its {@code camera_animation} section plays on the viewer's camera. */
    private static final String CUT_CAMERA = "kakarot_kit:Art/ANM/cpl/C001A/BTL/SKL/C001A_S_SKL001_Cam";
    /** The beams the shot and the cut fire: their AnimNotify_SpawnProjectileBeam's Projectile, each a cue. */
    private static final String BEAM = "kakarot_kit:Blueprints/Character/C001A/BTL/SKL/BP_Projectile_C001A_S_SKL001",
            BEAM_CUT = BEAM + "_Cut";
    /** E's stage: Kakarot's ocean arena, its post volume and height fog. */
    private static final String ARENA = "kakarot_kit:Area02";
    /** K's and X's darkening: the Kamehameha's action defaults (KakarotSkillDarken) and the material they play. */
    private static final String SKILL_ACTION = "roles/Blueprints/Character/C001A/BTL/SKL/BP_C001A_S_SKL001_Action.action.yaml",
            SKILL_DARKEN = "kakarot_kit:MasterMaterials/AT_Materials/PE_DarkenInTheGround";
    /** Goku's Blueprint: its mesh node names his mesh's materials, one a slot, in slot order. */
    private static final String GOKU_PREFAB = "prefabs/Blueprints/Character/C001A/AT_Character_cpl001_BP.yaml";
    /** The namespace the kit's materials name their shading models by ({@code kakarot:cell_tone}). */
    private static final String SHADING = "kakarot";
    /** The camera at Minecraft's walk, metres a second: the harness's 0.25 crawls between bodies metres tall. */
    private static final float WALK = 4.3f;
    private static final float[] RATES = {0.1f, 0.25f, 0.5f, 1f, 1.5f, 2f};

    /** One skinned mesh on the floor, its clips, and the one playing; a sequence's role, its clips by their id's last segment. */
    private static final class Actor implements CgSequenceRole, CgSequenceSkeletal {
        final String name;
        final CgSkeletalMesh mesh;
        final List<CgAnimSequence> clips;
        final CgAnimInstance instance;
        final double x, z;
        final Matrix4f transform = new Matrix4f();
        final float height, r, g, b;
        /** Whether it is the kit's Goku, the montages' caster; the montage playing on it, or null, and K's stage. */
        boolean kit;
        CgSequencePlayer move;
        Stage stage = Stage.IDLE;
        /** Its slots' kit materials by id, from its prefab, or empty; whether they are set on its instance yet. */
        List<String> kitMaterials = List.of();
        boolean dressed;
        /** The material contract's {@code outline} slot, once its kit materials and the outline are set; or null. */
        CgRoleOutline outline;
        /** Its cast entry's {@code outline} block: stencil, post, colour slots; or null where it has none. */
        @Nullable
        Map<?, ?> outlineBlock;
        /** The contract's rim, once its kit materials are set; its cast entry's {@code rim} block, or null for none. */
        @Nullable
        CgRoleRim rim;
        @Nullable
        Map<?, ?> rimBlock;
        /** How far its prefab's root stands above its mesh's, in host units: 0 for a body with no prefab. */
        double rootLift;
        /** Its mesh's turn back to its actor's: the inverse of the prefab's Mesh node rotation (an ACharacter's yaw -90). */
        final Quaternionf meshToActor = new Quaternionf();
        int clip;
        int rate = 3;

        Actor(String name, CgSkeletalMesh mesh, List<CgAnimSequence> clips, double x, double z, float height, int rgb) {
            this.name = name;
            this.mesh = mesh;
            this.clips = clips;
            this.instance = new CgAnimInstance(mesh);
            this.x = x;
            this.z = z;
            CgAABB box = mesh.mesh().bounds(new CgAABB());
            // 0: as authored, the caster whose effects are sized for him (Kakarot's mesh component carries no scale)
            if (height <= 0f) height = (float) (box.maxY() - box.minY());
            float scale = height / (float) Math.max(box.maxY() - box.minY(), 1e-4);
            transform.scaling(scale).translate((float) -(box.minX() + box.maxX()) / 2f, (float) -box.minY(),
                    (float) -(box.minZ() + box.maxZ()) / 2f);
            this.height = height;
            instance.place(x, 0.0, z, transform);
            this.r = (rgb >> 16 & 0xFF) / 255f;
            this.g = (rgb >> 8 & 0xFF) / 255f;
            this.b = (rgb & 0xFF) / 255f;
        }

        /** Plays clip {@code index} from its start, cross-fading from whatever played. */
        void play(int index) {
            clip = Math.floorMod(index, clips.size());
            double now = instance.time();
            List<CgAnimSection> old = instance.sections();
            instance.clear();
            for (CgAnimSection s : old) {
                if (s.contains(now) && s.end() > now + FADE) {
                    instance.play(CgAnimSection.of(s.sequence()).at(s.start()).until(now + FADE).easeOut(FADE)
                            .firstLoopOffset(0f).loop().build());
                }
            }
            instance.play(CgAnimSection.of(clips.get(clip)).at(now).until(Double.POSITIVE_INFINITY)
                    .easeIn(old.isEmpty() ? 0f : FADE).build());
        }

        /** Ends the montage playing: its clips leave the slot and its systems despawn at the sequencer's next evaluation. */
        void release() {
            if (move != null) move.stop();
            move = null;
            stage = Stage.IDLE;
        }

        String line() {
            String state = instance.playing() ? "playing" : "paused";
            if (move != null) {
                return String.format(Locale.ROOT, "%s: %s %s | %.2f s | %s at %.2fx", name, stage.name().toLowerCase(Locale.ROOT),
                        move.definition().id(), move.seconds(), state, RATES[rate]);
            }
            CgAnimSequence s = clips.get(clip);
            CgAnimSection playing = instance.sections().get(instance.sections().size() - 1);
            return String.format(Locale.ROOT, "%s: clip %d/%d %s | %.2f / %.2f s | %s at %.2fx", name, clip + 1,
                    clips.size(), s.name(), playing.animationTime(instance.time()), s.duration(), state, RATES[rate]);
        }

        @Override
        public CgAnimInstance instance() {
            return instance;
        }

        @Override
        public CgAnimSequence clip(String id) {
            String last = id.substring(id.lastIndexOf('/') + 1);
            for (CgAnimSequence c : clips) if (c.name().contains(last)) return c;
            return null;
        }

        @Override
        public CgAttachment attachment(String socket) {
            if (socket != null) return instance.attachment(socket);   // an unknown name answers where it stands
            // No socket: the actor's origin, its prefab's root, rootLift above the mesh's (an ACharacter's capsule centre),
            // turned as the actor rather than its mesh
            CgAttachment root = instance.attachment("");
            return (point, turn) -> {
                if (!root.place(point, turn)) return false;
                point[1] += rootLift;
                if (turn != null) turn.mul(meshToActor);
                return true;
            };
        }

        /** Its prefab's {@code Mesh} node: the skinned mesh's own origin and turn, below the capsule's centre. */
        @Override
        @Nullable
        public CgAttachment node(String name) {
            return name.equals("Mesh") ? instance.attachment("") : null;
        }

        @Override
        public int slot(String name) {
            // The outline's slot is 0, the rim's follow it
            int s = outline == null ? -1 : outline.slot(name);
            if (s >= 0) return s;
            s = rim == null ? -1 : rim.slot(name);
            return s < 0 ? -1 : 1 + s;
        }

        @Override
        public void set(int slot, float[] values, int mask) {
            if (slot == 0) outline.set(0, values, mask);
            else rim.set(slot - 1, values, mask);
        }

        @Override
        public void get(int slot, float[] out) {
            if (slot == 0) outline.get(0, out);
            else rim.get(slot - 1, out);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** K's stages: the charge held, the shot's loop held, the shot's end playing out. */
    private enum Stage { IDLE, CHARGING, FIRING, ENDING }

    private final List<Actor> actors = new ArrayList<>();
    /** Goku's materials and the charge montage; null without the kit. */
    private HarnessKitSystems kit;
    /** The scene shown now, across a re-entry that keeps the client world's sequencer and camera system. */
    @Nullable
    private static CgSkinnedPlayScene current;
    @Nullable
    private CgCameraSystem cameras;
    private HarnessContext ctx;
    /** V: watch as a viewer the cut does not involve, its camera and its post not taken. */
    private boolean bystander;
    private final Matrix4f view = new Matrix4f(), projection = new Matrix4f();
    private final Matrix3f basis = new Matrix3f();
    private final Vector3f forward = new Vector3f(), right = new Vector3f(), up = new Vector3f(), eye = new Vector3f();
    private CgSequencer sequencer;
    /** The moves' projectile beams: Kakarot's ATProjectileBeam, standing in for RPG-Core's. */
    private KakarotProjectileBeam beams;
    private int selected;
    private CgMaterial material;
    private CgTickFunction submit;
    /** The battle outline on the post stack, once Goku is dressed. */
    private CgRenderStage.Registration outline;
    /** Under {@code -Dcrystalgraphics.harness.skinnedPlay.cut=true}: frames since Goku's cut was played unattended, or -1. */
    private int cutFrames = Boolean.getBoolean("crystalgraphics.harness.skinnedPlay.cut") ? 0 : -1, cutRuns, cutStart;
    /** OL5's gate, under {@code -Dcrystalgraphics.harness.skinnedPlay.outlineCheck=true} ({@link SkinnedOutlineCheck}). */
    private SkinnedOutlineCheck outlineCheck;
    /** The outline's pass, and which of its edge tests O shows: all, then depth, normal and ID alone. */
    private CgMaterial outlinePass;
    private int outlineTests;
    private static final String[] OUTLINE_TESTS = {"all tests", "depth alone", "normal alone", "ID alone"};
    /** The outline's order among post materials: first, below any volume's priority. */
    private static final int OUTLINE_ORDER = -1_000_000;
    private boolean kitFailureLogged;
    /** E: the ocean arena, once the kit is ready; where the darkening's material sits in the kit's list. */
    @Nullable
    private CgStage stage;
    private int darkenMaterial = -1;
    /** Whether the bodies' files have been read and their actors made, or their reading failed. */
    private boolean bodiesLoaded;
    /**
     * Behind the loading screen: the bodies read, the kit loaded, then the arena up, so its post chain builds its
     * pipelines and loads its textures in the screen's warm frames rather than the scene's first.
     */
    private final CgLoadingProcess arena = new CgLoadingProcess() {
        @Override
        public String loading() {
            // The beam's cues once the kit's scan registered them: asked before, they fail as unregistered
            if (!beamsRequested && kit != null && kit.declared() && beams != null) {
                beamsRequested = true;
                beams.precache(BEAM).precache(BEAM_CUT);
            }
            if (!bodiesLoaded) return "loading Goku and Kurama";
            if (kit == null || kit.failed()) return null;
            if (!kit.ready()) return "loading the kit";
            if (stageAtStart) {
                stageAtStart = false;
                stage(true);
            }
            return null;
        }

        /** While the bodies load, their files read; after, the kit's own task measures it. */
        @Override
        public float progress() {
            CgLoadTask t = bodiesTask.get();
            return !bodiesLoaded && t != null ? t.reported() : Float.NaN;
        }
    };
    /** The bodies' read as the loading screen's task: set once its job has started. */
    private final AtomicReference<CgLoadTask> bodiesTask = new AtomicReference<>();
    /** On at start once the kit is ready, unless {@code -Dcrystalgraphics.harness.skinnedPlay.stage=false}. */
    private boolean stageAtStart = !"false".equals(System.getProperty("crystalgraphics.harness.skinnedPlay.stage"));
    /**
     * The ocean arena's directional light, the battle capture's (5440): what Kakarot's cel shading is lit by outside a
     * move's own light, which wins at priority 1. Unreal's (6.797, 5.941, 4.574) travelling along -(0.5567, -0.3214,
     * 0.766), in the host's axes; at 6.8 it outshines the host's sun at the same priority.
     */
    @Nullable
    private CgDirectionalLights.Light stageLight;
    /** The skill's darkening, once the kit is ready; null where the action has none. */
    @Nullable
    private KakarotSkillDarken darken;
    private boolean darkenTried;
    @Nullable
    private Path skillAction;

    @Override
    public void init(HarnessContext ctx) {
        services = ctx.getRuntimeServices();
        material = CgMaterial.load("assets/harness/shader/skinned_view.shader");
        String root = System.getProperty("user.dir").replace('\\', '/');
        root = root.endsWith("gl-debug-harness") ? root.substring(0, root.length() - "gl-debug-harness".length()) : root + "/";
        String goku = System.getProperty("crystalgraphics.harness.skinned.goku", root + KIT + "MDL/Cpl/Cpl001/Model/Cpl001p1c01s1.glb");
        String gokuClips = System.getProperty("crystalgraphics.harness.skinned.goku.clips", root + KIT + "ANM/cpl/C001A/BTL/SKL");
        String kurama = System.getProperty("crystalgraphics.harness.skinned.kurama", "X:/research/nsuns4/assets/5nrtspl1_extra/puppets/5nrvbod2.glb");

        // Every file read on the workers while the loading screen draws: the bodies, and the kit's plan, whose delivery
        // starts the kit's own load; the actors are made here once both have landed
        String kitRoot = root + KIT_ROOT, face = root + KIT + "ANM/cpl/C001A/FCE/";
        Path kitFolder = Paths.get(kitRoot);
        CgReply<Bodies> bodies = CgJob.run(() -> readBodies(goku, gokuClips, kurama, kitRoot, face, bodiesTask))
                .named("skinned-play bodies");
        CgReply<KitPlan> plan = Files.isDirectory(kitFolder)
                ? CgJob.run(() -> readKitPlan(kitFolder)).named("skinned-play kit").then(p -> loadKit(kitFolder, p))
                : CgReply.of(null);
        bodiesTask.set(CgLoadPlan.get().track("skinned-play:bodies", bodies));
        CgLoadPlan.get().track("skinned-play:kitPlan", plan);
        CgReply.both(bodies, plan).then(loaded -> {
            try {
                addActors(loaded.first(), loaded.second());
            } finally {
                bodiesLoaded = true;
            }
        }).onError(e -> {
            LOGGER.warn("[skinned-play] reading the bodies failed: {}", e.detail(), e.cause());
            bodiesLoaded = true;
        });
        CgLoadingScreen.add(arena);
        CgWorld client = CgWorld.client();
        // The cut's sound tracks play through it, each on its tick.
        if (client.subsystem(CgAudioEngine.class) == null) client.add(new CgAudioEngine());
        current = this;
        cameras = client.subsystem(CgCameraSystem.class);
        if (cameras == null) cameras = client.add(new CgCameraSystem());
        cameras.host(pose -> {
            if (current != null) current.hostPose(pose);
        });
        this.ctx = ctx;
        CgCueManager cues = CgCueManager.client();   // the engine's, wired as the client started
        // The engine's sequencer, as Minecraft's; its cameras answer the scene shown now's camera sequences
        sequencer = CgVfxSequencer.install(client, VFX.system(), cameras, id -> current == null ? null : current.cameraSequence(id));
        beams = installCut(sequencer, cues, player -> {
            for (Actor a : actors) if (a.move == player) return a;
            return null;
        });
        cameras.involved(owner -> current == null || !current.bystander);
        submit = client.schedule().add(CgTickGroup.SUBMIT, "harness:skinned-play", this::submit);
        stageLight = CgDirectionalLights.get().add(0).colour(1f, 5.941f / 6.797f, 4.574f / 6.797f, 6.797f)
                .travel(-0.5567f, -0.766f, 0.3214f);
        ctx.getCamera3D().moveCamera(2f, 3f, 6f);
        ctx.getCamera3D().setPitch(-10f);
        ctx.getCamera3D().setMoveSpeed(WALK);
    }

    /**
     * Goku's AnimBP face (AB_C000), from the FCE clips under {@code fce}: his face montage on FacialSlot over facialbase
     * (depth 1), its blink played in its place every {@link #blinkInterval} seconds with the face replayed after, as
     * OnMontageEnded does. A move's FacialSlot section plays over the face. No lip-sync layer: fbLowerFace's weight is
     * 0 in battle, where no voice has lip data.
     */
    private static void dressFace(Actor actor, Body body) {
        CgAnimSequence faceClip = body.face();
        if (faceClip == null) return;
        CgAnimLayer layer = actor.instance.layer(FACE_SLOT, FACE_BRANCH, 1);
        double from = 0;
        CgAnimSequence blinkClip = body.blink();
        if (blinkClip != null) {
            // Laid out on the timeline from a fixed seed rather than switched per frame, so a seek replays it exactly.
            // The timer restarts as a blink starts, so blinks are an interval apart start to start.
            double length = blinkClip.duration();
            Random rng = new Random(BLINK_SEED);
            for (double at = blinkInterval(rng); at + length <= BLINKS_FOR; at += Math.max(length, blinkInterval(rng))) {
                layer.play(CgAnimSection.of(faceClip).at(from).until(at).loop().build());
                layer.play(CgAnimSection.of(blinkClip).at(at).until(at + length).build());
                from = at + length;
            }
        }
        layer.play(CgAnimSection.of(faceClip).at(from).until(Double.POSITIVE_INFINITY).loop().build());
        LOGGER.info("[skinned-play] Goku's face: {} on {}{}", faceClip.name(), FACE_SLOT, blinkClip != null ? ", blinking" : "");
    }

    /** A skinned body read from its files, and its face's clips; null clips where it has none. */
    private record Body(CgSkeletalMesh mesh, List<CgAnimSequence> clips, @Nullable CgAnimSequence face,
                        @Nullable CgAnimSequence blink) {
    }

    /** Goku and Kurama, each null where its file is missing or plays nothing. */
    private record Bodies(@Nullable Body goku, @Nullable Body kurama) {
    }

    /**
     * Goku's kit blocks: his prefab's materials, one a slot, and its Mesh node (the root's lift above it, its turn
     * undone); his cast entry's outline (kept only with a post) and rim.
     */
    private record KitPlan(List<String> materials, double rootLift, Quaternionf meshToActor, @Nullable Map<?, ?> outline,
                           @Nullable Map<?, ?> rim) {
    }

    /**
     * Every body file read in one split across the cores, each its own item: Goku's mesh and sockets, each of his clip
     * files, his face's two clips, Kurama. A worker's.
     */
    private static Bodies readBodies(String goku, String gokuClips, String kurama, String kitRoot, String fce,
                                     AtomicReference<CgLoadTask> task) {
        String faceName = System.getProperty("crystalgraphics.harness.skinned.goku.face", "C001A_FCE_Battle");
        File face = new File(fce + faceName + ".glb"), blink = new File(fce + faceName + "_blink.glb");
        boolean hasGoku = new File(goku).isFile(), hasKurama = new File(kurama).isFile();
        if (!hasGoku) LOGGER.warn("[skinned-play] no Goku at {}", goku);
        if (!hasKurama) LOGGER.warn("[skinned-play] no Kurama at {}", kurama);
        File[] files = hasGoku ? new File(gokuClips).listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".glb")) : null;
        if (files == null) files = new File[0];
        Arrays.sort(files);
        File[] clipFiles = files;
        // 0 Goku, 1 Kurama, 2 the face, 3 the blink, then the clip files, kept in their order
        Object[] read = new Object[4 + clipFiles.length];
        AtomicInteger done = new AtomicInteger();
        CgJob.forEach(read.length, i -> {
            switch (i) {
                case 0 -> read[i] = hasGoku ? withKitSockets(CgMeshLoader.skeletal(goku), kitRoot) : null;
                case 1 -> read[i] = hasKurama ? CgMeshLoader.skeletal(kurama) : null;
                case 2 -> read[i] = hasGoku && face.isFile() ? CgMeshLoader.sequences(face.getPath()).get(0) : null;
                case 3 -> read[i] = hasGoku && blink.isFile() ? CgMeshLoader.sequences(blink.getPath()).get(0) : null;
                default -> read[i] = CgMeshLoader.sequences(clipFiles[i - 4].getAbsolutePath());
            }
            CgLoadTask t = task.get();   // the loading screen's record: files read of all of them
            if (t != null) t.progress(done.incrementAndGet() / (float) read.length);
        });
        Body gokuBody = null, kuramaBody = null;
        if (read[0] instanceof CgSkeletalMesh mesh) {
            List<CgAnimSequence> clips = new ArrayList<>(mesh.sequences());
            for (int i = 4; i < read.length; i++) {
                @SuppressWarnings("unchecked")
                List<CgAnimSequence> sequences = (List<CgAnimSequence>) read[i];
                for (CgAnimSequence clip : sequences) {
                    if (clip.trackCount() > 1) clips.add(clip);   // the _dmy files move one locator
                }
            }
            CgAnimSequence faceClip = (CgAnimSequence) read[2];
            if (faceClip == null || mesh.skeleton().indexOfIgnoringCase(FACE_BRANCH) < 0) {
                LOGGER.warn("[skinned-play] Goku's face clip {} is missing: his face is the body's", face);
                faceClip = null;
            }
            if (!clips.isEmpty()) gokuBody = new Body(mesh, clips, faceClip, faceClip == null ? null : (CgAnimSequence) read[3]);
        }
        if (read[1] instanceof CgSkeletalMesh mesh && !mesh.sequences().isEmpty()) {
            kuramaBody = new Body(mesh, new ArrayList<>(mesh.sequences()), null, null);
        }
        return new Bodies(gokuBody, kuramaBody);
    }

    /** Goku's kit blocks, from his prefab and cast entry under {@code kit}. A worker's. */
    private static KitPlan readKitPlan(Path kit) {
        Path prefab = kit.resolve(GOKU_PREFAB), cast = kit.resolve("cast.yaml");
        Quaternionf meshToActor = new Quaternionf();
        double rootLift = prefabMesh(prefab, kit.resolve("units.yaml"), meshToActor);
        Map<?, ?> outline = castBlock(cast, "Goku", "outline");
        if (outline != null && !(outline.get("post") instanceof String)) {
            LOGGER.warn("[skinned-play] Goku's cast entry has no outline with a post: not outlined");
            outline = null;
        }
        return new KitPlan(prefabMaterials(prefab), rootLift, meshToActor, outline, castBlock(cast, "Goku", "rim"));
    }

    /**
     * Starts the kit's load: Goku's materials and his outline's post (last: {@code kit.material(slots)}), then the
     * stage's and the darkening's.
     */
    private void loadKit(Path kitFolder, KitPlan plan) {
        List<String> materials = new ArrayList<>(plan.materials());
        if (plan.outline() != null) materials.add((String) plan.outline().get("post"));
        darkenMaterial = materials.size();
        materials.add(SKILL_DARKEN);
        skillAction = kitFolder.resolve(SKILL_ACTION);
        kit = HarnessKitSystems.load(VFX.system(), KIT_NAMESPACE, kitFolder, List.of(), materials, List.of(CHARGE, SHOT, CUT, CUT_CAMERA), List.of(ARENA), SHADING);
    }

    /** The bodies on the floor, playing their first clips, Goku in his kit's materials where it names one a slot. */
    private void addActors(Bodies bodies, @Nullable KitPlan plan) {
        Body goku = bodies.goku();
        if (goku != null) {
            Actor actor = new Actor("Goku", goku.mesh(), goku.clips(), -2.0, -6.0, 0f, 0xFFF0A040);
            actor.kit = true;
            if (plan != null) {
                int slots = actor.instance.materialSlots();
                if (plan.materials().size() == slots) {
                    actor.kitMaterials = plan.materials();
                    actor.outlineBlock = plan.outline();
                } else if (!plan.materials().isEmpty()) {
                    LOGGER.warn("[skinned-play] Goku's prefab names {} materials for {} slots: drawn plain", plan.materials().size(), slots);
                }
                actor.rimBlock = plan.rim();
                actor.rootLift = plan.rootLift();
                actor.meshToActor.set(plan.meshToActor());
            }
            actors.add(actor);
            dressFace(actor, goku);
        }
        Body kurama = bodies.kurama();
        if (kurama != null) actors.add(new Actor("Kurama", kurama.mesh(), kurama.clips(), 8.0, -16.0, 9f, 0xFFE06030));
        CgAnimation animation = CgAnimation.client();
        for (Actor actor : actors) {
            animation.add(actor.instance);
            actor.play(0);
            LOGGER.info("[skinned-play] {}: {} bones, {} clips{}", actor.name, actor.mesh.skeleton().boneCount(),
                    actor.clips.size(), actor.kit ? ", K charges the kit's Kamehameha" : "");
        }
    }

    /** Kakarot's next blink, in seconds: 15 times the mean of 20 uniform draws, 7.5 s on average (its facial tick). */
    private static double blinkInterval(Random rng) {
        double sum = 0;
        for (int i = 0; i < 20; i++) sum += rng.nextDouble();
        return 15.0 * sum / 20;
    }

    /** {@code mesh} with Goku's skeleton asset's sockets in the kit's units, where the kit at {@code kit} has them. */
    private static CgSkeletalMesh withKitSockets(CgSkeletalMesh mesh, String kit) {
        Path skeleton = Paths.get(kit, GOKU_SKELETON), units = Paths.get(kit, "units.yaml");
        if (!Files.isRegularFile(skeleton) || !Files.isRegularFile(units)) return mesh;
        try {
            CgUnits kitUnits = CgUnits.of(((Map<?, ?>) CgAssetYaml.read(Files.readString(units))).get("units"), CgUnits.HOST);
            Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read(Files.readString(skeleton));
            return mesh.withSockets(CgSkeletonDefinition.compile(GOKU_SKELETON, tree, kitUnits).socketsOn(mesh.skeleton()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The materials a prefab's first mesh names, one a slot in slot order: Unreal's component materials as the kit
     * exported them. Empty where it has none.
     */
    private static List<String> prefabMaterials(Path prefab) {
        if (!Files.isRegularFile(prefab)) return List.of();
        try {
            Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read(Files.readString(prefab));
            for (Object node : (List<?>) tree.get("nodes")) {
                if (!(((Map<?, ?>) node).get("meshes") instanceof List<?> meshes) || meshes.isEmpty()) continue;
                List<String> ids = new ArrayList<>();
                for (Object id : (List<?>) ((Map<?, ?>) meshes.get(0)).get("materials")) ids.add(String.valueOf(id));
                return ids;
            }
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The node drawing a prefab's mesh: how far the root stands above it, minus its height (Goku's Mesh at z -100 cm
     * under the Blueprint's capsule), answered, and its turn undone into {@code meshToActor}. 0 and unturned where the
     * prefab or its mesh node has no location.
     */
    private static double prefabMesh(Path prefab, Path units, Quaternionf meshToActor) {
        if (!Files.isRegularFile(prefab) || !Files.isRegularFile(units)) return 0.0;
        try {
            CgUnits kitUnits = CgUnits.of(((Map<?, ?>) CgAssetYaml.read(Files.readString(units))).get("units"), CgUnits.HOST);
            Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read(Files.readString(prefab));
            for (Object node : (List<?>) tree.get("nodes")) {
                Map<?, ?> n = (Map<?, ?>) node;
                if (!(n.get("meshes") instanceof List<?>) || !(n.get("location") instanceof List<?> at)) continue;
                List<?> host = (List<?>) kitUnits.toHost(at, CgQuantity.POSITION);
                kitUnits.rotationToHost(n.get("rotation"), meshToActor).invert();
                return -((Number) host.get(1)).doubleValue();
            }
            return 0.0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Cast member {@code name}'s {@code key} block (the rig's, `spec/bodies.md`), or null where it has none. */
    @Nullable
    private static Map<?, ?> castBlock(Path cast, String name, String key) {
        if (!Files.isRegularFile(cast)) return null;
        try {
            Object entry = ((Map<?, ?>) CgAssetYaml.read(Files.readString(cast))).get(name);
            return entry instanceof Map<?, ?> m && m.get(key) instanceof Map<?, ?> block ? block : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** In SUBMIT, after POSE wrote this frame's palettes. */
    private void submit(float dt) {
        CgWorldRenderer world = CgWorldRenderer.get();
        for (Actor actor : actors) {
            if (actor.stage == Stage.ENDING && actor.move.finished()) {
                actor.release();
                LOGGER.info("[skinned-play] {}: the shot played out", actor.name);
                if (actor.kit && cutsPlayed > 0) {
                    services.profileEnd();
                    if (cutsPlayed < CUTS) {
                        rearm = true;
                    } else if (!Double.isNaN(STEADY)) {
                        // Counted in frames: writing the cut's profile stalls for seconds, which the clock would skip.
                        steadyAfterIn = STEADY_SETTLE_FRAMES;
                    } else if (!Double.isNaN(EXIT_AFTER)) {
                        exitAt = elapsed + EXIT_AFTER;
                    }
                } else if (!Double.isNaN(EXIT_AFTER) && actor.kit) {
                    exitAt = elapsed + EXIT_AFTER;
                }
            }
            if (kit != null && kit.failed() && !kitFailureLogged) {
                kitFailureLogged = true;
                LOGGER.warn("[skinned-play] the kit failed to load: {}", kit.failures());
            }
            if (kit != null && materialsReady(actor)) {
                if (!actor.dressed && !actor.kitMaterials.isEmpty()) {
                    dress(actor);
                    LOGGER.info("[skinned-play] {}: drawn in its {} kit materials, at frame {}", actor.name, actor.kitMaterials.size(), frameNumber);
                    Map<?, ?> block = actor.outlineBlock;
                    if (outline == null && block != null) {
                        CgMaterial pass = CgMaterial.of(kit.material(actor.kitMaterials.size()).get());
                        if (Boolean.getBoolean("crystalgraphics.harness.skinnedPlay.outlineYellow")) {
                            // A debug view: every pixel the outline draws in full yellow, not the capture's 0.35 black
                            pass.applyProperties(b -> b.vec4("OutlineColor", 1f, 1f, 0f, 1f).set1f("Intensity", 1f)
                                    .set1f("UseOutlineColor", 1f));
                        }
                        outlinePass = pass;
                        // Before every post material, as the game's outline draws after lighting (cut capture 4376):
                        // the stage's colouring and a skill's grade then darken it with the rest
                        CgPostMaterial battle = CgPostMaterial.of(pass, CgPostPoint.BEFORE_TRANSLUCENCY, OUTLINE_ORDER);
                        outline = CgPostStack.get().add(battle);
                        LOGGER.info("[skinned-play] {}: outlined by {}", actor.name, block.get("post"));
                        // The contract's outline colour in his slot, 1: the battle's first member, as gameplay would give
                        if (block.get("slot_parameter") instanceof String parameter
                                && block.get("slot_colours") instanceof List<?> colours && !colours.isEmpty()) {
                            actor.outline = new CgRoleOutline(actor.instance, battle, 1, parameter, String.valueOf(colours.get(0)));
                        }
                        if (Boolean.getBoolean("crystalgraphics.harness.skinnedPlay.outlineCheck")) {
                            outlineCheck = new SkinnedOutlineCheck(battle, List.of(actor.instance), actor.x, actor.z, actor.height,
                                    keyed -> sequencer.play(keyed, Map.of("caster", actor)));
                        }
                    }
                }
            }
            if (actor.kit && kit != null && kit.ready()) darken(dt, actor.move != null);
            draw(world, actor);
        }
    }

    /**
     * Whether {@code actor}'s kit materials and its outline's post are READY: it is dressed then, as Unreal draws a mesh
     * once its own materials are, while the kit's systems may still be rehearsing.
     */
    private boolean materialsReady(Actor actor) {
        int last = actor.kitMaterials.size() + (actor.outlineBlock != null ? 1 : 0);
        if (!kit.declared()) return false;
        for (int s = 0; s < last; s++) if (!kit.material(s).ready()) return false;
        return true;
    }

    /**
     * {@code actor} in its kit materials, its own copies (Unreal's dynamic material instances: what its clips' curves and
     * its outline slot set), its cast entry's rim (what the contract's rim_strength, rim_power and rim write) and the
     * stencil its outline's post reads it by. The outline post itself is the scene's, set by the real Goku alone.
     */
    private void dress(Actor actor) {
        for (int s = 0; s < actor.kitMaterials.size(); s++) actor.instance.material(s, kit.material(s).get());
        actor.dressed = true;
        Map<?, ?> rim = actor.rimBlock;
        if (rim != null && rim.get("strength") instanceof String strength && rim.get("power") instanceof String power
                && rim.get("colour") instanceof String colour) {
            actor.rim = new CgRoleRim(actor.instance, strength, power, colour);
        }
        Map<?, ?> block = actor.outlineBlock;
        if (block != null && block.get("custom_depth_stencil_value") instanceof Number stencil) {
            actor.instance.customDepth(stencil.intValue(), 0xFF);
        }
    }

    /** {@code actor}'s draw once posed: in its kit materials once dressed, else the plain skinned view in its colour. */
    private void draw(CgWorldRenderer world, Actor actor) {
        if (actor.instance.palette() < 0) return;
        if (actor.dressed) {
            world.draw(actor.instance);
        } else {
            world.draw(actor.instance, material).pad(actor.height * 50f).custom(0, actor.r, actor.g, actor.b, 1f).submit();
        }
    }

    /** The cut's own kind on {@code sequencer}, beside the engine's: the beam its notify fires through {@code cues}. */
    private KakarotProjectileBeam installCut(CgSequencer sequencer, CgCueManager cues, Function<CgSequencePlayer, CgSequenceRole> casters) {
        return KakarotProjectileBeam.install(sequencer, cues, casters);
    }

    /** Whether the beam's cues were asked for: once the kit is declared, by {@link #arena}. */
    private boolean beamsRequested;

    /** The harness camera as the camera system's host: its eye, its look and the projection's horizontal FOV. */
    private void hostPose(CgCameraPose out) {
        Camera3D cam = ctx.getCamera3D();
        cam.getViewMatrix();   // its look, current
        Vector3f look = cam.getLookVector();
        forward.set(look).normalize();
        right.set(forward).cross(0, 1, 0).normalize();
        up.set(right).cross(forward).normalize();
        // The pose's turn takes +x to its forward and +y to its up; +z is then their cross, its right.
        basis.set(forward.x, forward.y, forward.z, up.x, up.y, up.z, right.x, right.y, right.z);
        out.location(cam.getPosX(), cam.getPosY(), cam.getPosZ()).fov((float) Math.toDegrees(2 * Math.atan(1 / ctx.getProjection().m00())));
        out.rotation().setFromNormalized(basis);
    }

    @Nullable
    private CgSequenceDefinition cameraSequence(String id) {
        return kit != null && kit.declared() && id.equals(CUT_CAMERA) && kit.sequence(3).ready() ? kit.sequence(3).get() : null;
    }

    /** The camera system's pose while it controls the view, so the runner's floor is drawn from it too. */
    @Override
    public boolean worldView(HarnessContext ctx, Matrix4f outView, Matrix4f outProjection) {
        cut = cameras != null && cameras.controls();
        if (!cut) return false;
        // The camera system's pose: its eye, its turn's forward and up, its horizontal FOV over the projection's aspect.
        CgCameraPose pose = cameras.pose();
        pose.rotation().transform(forward.set(1, 0, 0));
        pose.rotation().transform(up.set(0, 1, 0));
        eye.set((float) pose.location().x, (float) pose.location().y, (float) pose.location().z);
        view.setLookAt(eye.x, eye.y, eye.z, eye.x + forward.x, eye.y + forward.y, eye.z + forward.z, up.x, up.y, up.z);
        Matrix4f base = ctx.getProjection();
        float m00 = (float) (1 / Math.tan(Math.toRadians(pose.fov()) / 2));
        projection.set(base).m00(m00).m11(m00 * base.m11() / base.m00());
        outView.set(view);
        outProjection.set(projection);
        return true;
    }

    /** Whether this frame draws from the camera system's pose, as {@link #worldView} found it. */
    private boolean cut;

    /** The frame being drawn: when the kit came ready, in the log. */
    private long frameNumber;
    /** Seconds after Goku's kit is ready to press X, and after the shot plays out to end the run; NaN for never. */
    private static final double CUT_AFTER = seconds("crystalgraphics.harness.skinnedPlay.cutAfter"),
            EXIT_AFTER = seconds("crystalgraphics.harness.skinnedPlay.exitAfter");
    /** How many scripted cuts: each after the last plays out by {@link #CUT_AFTER}; the run ends after the last. */
    private static final int CUTS = Integer.getInteger("crystalgraphics.harness.skinnedPlay.cuts", 1);
    /**
     * Seconds of steady play profiled either side of the cuts, as {@code steady-before} (ending at the first cut) and
     * {@code steady-after} (from a second after the last plays out, then the run ends); NaN for neither. Under
     * {@code profile.ring}; {@link #CUT_AFTER} must be longer.
     */
    private static final double STEADY = seconds("crystalgraphics.harness.skinnedPlay.steady");
    /** Frames after the last cut plays out before {@code steady-after} opens: past its profile's write and the GPU's clock. */
    private static final int STEADY_SETTLE_FRAMES = 240;
    private int steadyAfterIn = -1;
    private boolean steadyOpen;
    /** The scene's clock, and when the scripted cut and the run's end fall on it; negative until known. */
    private double elapsed, cutAt = -1, exitAt = -1;
    /** A cut played out: the next is timed from the frame after, past the stall of writing its profile. */
    private boolean rearm;
    private int cutsPlayed;
    private boolean running = true;
    private RuntimeServices services;

    private static double seconds(String property) {
        String value = System.getProperty(property);
        return value == null ? Double.NaN : Double.parseDouble(value);
    }

    /**
     * The scripted cuts: X, {@link #CUT_AFTER} seconds after the kit is ready and again that long after each plays out,
     * {@link #CUTS} of them, each profiled as {@code cut-<n>} under {@code profile.ring}; the run's end
     * {@link #EXIT_AFTER} after the last plays out.
     */
    private void script() {
        Actor goku = null;
        for (Actor a : actors) if (a.kit) goku = a;
        if (goku == null || Double.isNaN(CUT_AFTER)) return;
        // From the kit's readiness, not Goku's dressing: he dresses before its systems are rehearsed, and X is refused
        // until they are.
        if (cutAt < 0 && goku.dressed && kit.ready() || rearm) cutAt = elapsed + CUT_AFTER;
        rearm = false;
        if (!Double.isNaN(STEADY) && cutsPlayed == 0 && cutAt >= 0 && cutAt != Double.MAX_VALUE && !steadyOpen
                && elapsed >= cutAt - STEADY) {
            services.profileBegin("steady-before");
            steadyOpen = true;
        }
        if (cutAt >= 0 && elapsed >= cutAt && cutAt != Double.MAX_VALUE) {
            cutAt = Double.MAX_VALUE;
            cutsPlayed++;
            if (steadyOpen) {
                services.profileEnd();
                steadyOpen = false;
            }
            LOGGER.info("[skinned-play] the scripted cut {}, at frame {}", cutsPlayed, frameNumber);
            services.profileBegin("cut-" + cutsPlayed);
            cut(goku);
        }
        if (steadyAfterIn > 0 && --steadyAfterIn == 0) {
            steadyAfterIn = -1;
            exitAt = elapsed + STEADY;
            // What outlives the cuts: the steady frame after them pays for it.
            LOGGER.info("[skinned-play] {} effects alive after the cuts", VFX.system().effects().size());
            for (CgVfxEffect<?> e : VFX.system().effects()) {
                LOGGER.info("[skinned-play] alive after the cuts: {} age {} state {}", e instanceof CgVfxSystemInstance<?> s
                        ? s.definition().name() : e.getClass().getSimpleName(), e.age(), e.state());
            }
            services.profileBegin("steady-after");
            steadyOpen = true;
        }
        if (exitAt >= 0 && elapsed >= exitAt) {
            if (steadyOpen) services.profileEnd();
            steadyOpen = false;
            running = false;
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        frameNumber = frame.getFrameNumber();
        elapsed = frame.getElapsedTime();
        script();
        if (cut) {
            HarnessWorld.fire(ctx, view, projection);
        } else {
            HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
        }
        if (outlineCheck != null) outlineCheck.frame(ctx);
        if (stageAtStart && kit != null && kit.ready()) {
            stageAtStart = false;
            stage(true);
        }
        if (cutFrames >= 0 && kit != null && kit.ready()) cutCheck();
        subtitleWatch();
    }

    /** The subtitle last logged. */
    private String subtitle;

    /** Logs each line the subtitles show as it changes: what, who, in which look, and when in the cut. */
    private void subtitleWatch() {
        CgAudioEngine audio = CgWorld.client().subsystem(CgAudioEngine.class);
        String text = audio == null ? null : audio.subtitles().text();
        if (Objects.equals(text, subtitle)) return;
        subtitle = text;
        Actor goku = null;
        for (Actor a : actors) if (a.kit) goku = a;
        String at = goku == null || goku.move == null ? "" : String.format(Locale.ROOT, " at %.2f s", goku.move.seconds());
        System.out.println(text == null ? "[skinned-play] subtitle cleared" + at + "; " + audio.subtitleFramesDrawn() + " frames drawn"
                : "[skinned-play] subtitle \"" + text + "\" by " + audio.subtitles().speaker() + ", in "
                + (audio.subtitles().style() != null ? "its game's style" : "the canvas look") + at);
    }

    /** The cut played twice, unattended, 300 frames after the kit is ready: the move's light and plays logged each second. */
    private void cutCheck() {
        Actor goku = null;
        for (Actor a : actors) if (a.kit) goku = a;
        if (goku == null) return;
        int frame = cutFrames++;
        // Warmed first: the frame after a cold start spans the pipelines' builds, and the whole cut with it.
        if (frame == 300 || frame > 300 && cutRuns == 1 && goku.move == null) {
            cutRuns++;
            cut(goku);
            cutStart = frame;
        }
        if (frame < 300 || (frame - cutStart) % 60 != 1 && goku.move != null) return;
        CgDirectionalLights.Light light = CgDirectionalLights.get().active(1f);
        System.out.println("[skinned-play] cut " + cutRuns + " at " + (goku.move == null ? "its end" : String.format("%.2f s", goku.move.seconds()))
                + ": " + sequencer.players().size() + " plays; directional light " + (light == null ? "the sun"
                : "priority " + light.priority() + ", travelling (" + light.dx() + ", " + light.dy() + ", " + light.dz() + "), colour ("
                + light.red() + ", " + light.green() + ", " + light.blue() + ")"));
        if (goku.move == null && cutRuns == 2) {
            System.out.println("[skinned-play] cut done");
            cutFrames = -1;
        }
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        if (!event.pressed() || actors.isEmpty()) return true;
        Actor actor = actors.get(selected);
        CgAnimInstance instance = actor.instance;
        switch (event.key()) {
            case CgKeyCodes.KEY_TAB -> selected = (selected + 1) % actors.size();
            case CgKeyCodes.KEY_N -> actor.play(actor.clip + 1);
            case CgKeyCodes.KEY_B -> actor.play(actor.clip - 1);
            case CgKeyCodes.KEY_R -> {
                instance.clear().seek(0);
                actor.play(actor.clip);
            }
            case CgKeyCodes.KEY_P -> {
                instance.playing(!instance.playing());
                rate(actor, actor.rate);
            }
            case CgKeyCodes.KEY_COMMA -> instance.playing(false).seek(instance.time() - FRAME);
            case CgKeyCodes.KEY_PERIOD -> instance.playing(false).seek(instance.time() + FRAME);
            case CgKeyCodes.KEY_U -> rate(actor, actor.rate - 1);
            case CgKeyCodes.KEY_I -> rate(actor, actor.rate + 1);
            case CgKeyCodes.KEY_K -> kamehameha(actor);
            case CgKeyCodes.KEY_X -> cut(actor);
            case CgKeyCodes.KEY_V -> bystander = !bystander;
            case CgKeyCodes.KEY_E -> stage(stage == null);
            case CgKeyCodes.KEY_O -> {
                if (outlinePass == null) break;
                outlineTests = (outlineTests + 1) % OUTLINE_TESTS.length;
                SkinnedOutlineCheck.tests(outlinePass, outlineTests < 2, outlineTests == 0 || outlineTests == 2,
                        outlineTests == 0 || outlineTests == 3);
            }
            default -> {
            }
        }
        return true;
    }

    /**
     * U/I: the actor's rate, on its clips, its move and the move's particles; P holds all three at 0. A move poses the
     * body from its own clock, so the clips' rate alone leaves a cut at full speed; its systems run on the world's.
     */
    private void rate(Actor actor, int index) {
        actor.rate = Math.max(0, Math.min(RATES.length - 1, index));
        float r = RATES[actor.rate];
        actor.instance.rate(r);
        float moving = actor.instance.playing() ? r : 0f;
        if (actor.move != null) actor.move.rate(moving);
        if (actor.kit) CgWorld.client().timeDilation(moving);
    }

    /** K: the charge, then the shot (its loop held), then the shot's end; the end plays out and lets go by itself. */
    private void kamehameha(Actor actor) {
        if (!actor.kit || kit == null || !kit.ready()) return;
        switch (actor.stage) {
            case IDLE -> {
                actor.move = sequencer.play(kit.sequence(0).get(), Map.of("caster", actor));
                actor.stage = Stage.CHARGING;
            }
            case CHARGING -> {
                actor.move.stop();
                actor.move = sequencer.play(kit.sequence(1).get(), Map.of("caster", actor));
                actor.stage = Stage.FIRING;
            }
            case FIRING -> {
                CgSequenceDefinition shot = actor.move.definition();
                actor.move.jumpTo(shot.seconds(shot.compositeSection(SHOT_END).from()));   // Montage_JumpToSection
                actor.stage = Stage.ENDING;
            }
            case ENDING -> actor.release();
        }
        rate(actor, actor.rate);
        LOGGER.info("[skinned-play] {}: {} ({})", actor.name, actor.stage, actor.move == null ? "let go" : actor.move.definition().id());
    }

    /** The skill's darkening, made at the kit's first ready frame, toward on while the actor's move plays. */
    private void darken(float dt, boolean running) {
        if (!darkenTried) {
            darkenTried = true;
            try {
                darken = KakarotSkillDarken.of(skillAction, kit.material(darkenMaterial).get());
            } catch (IOException e) {
                LOGGER.warn("[skinned-play] the skill's action file did not read: {}", e.toString());
            }
        }
        if (darken != null) darken.update(dt, running);
    }

    /**
     * E: the ocean arena on or off, the kit's stage placed with its anchor on the floor: the capture's ground under the
     * fight, so the cut camera stands at the capture's height in its fog.
     */
    private void stage(boolean on) {
        if (kit == null || !kit.ready() || on == (stage != null)) return;
        if (on) {
            stage = CgStage.place(kit.stage(0).get(), 0, 0, 0);
        } else {
            stage.close();
            stage = null;
        }
        LOGGER.info("[skinned-play] the ocean arena's post chain {}", on ? "on" : "off");
    }

    /**
     * X: the cut from the start, whatever was playing, which ends at once with its particles, so a repeated X starts
     * clean; the cut plays out and lets go by itself.
     */
    private void cut(Actor actor) {
        if (!actor.kit || kit == null || !kit.ready()) return;
        if (actor.move != null) actor.move.kill();
        actor.move = sequencer.play(kit.sequence(2).get(), Map.of("caster", actor));
        actor.stage = Stage.ENDING;
        rate(actor, actor.rate);
        LOGGER.info("[skinned-play] {}: the cut ({})", actor.name, actor.move.definition().id());
    }

    @Override
    public String hudLine() {
        if (actors.isEmpty()) return "skinned-play: no meshes found (see the scene's javadoc for the paths)";
        // The HUD's own form, a name, its keys, then its state: who plays what, the clip's keys, the move, the stage
        StringBuilder sb = new StringBuilder("\nActor [Tab]:\n");
        for (int i = 0; i < actors.size(); i++) sb.append(i == selected ? "> " : "  ").append(actors.get(i).line()).append('\n');
        sb.append("Clip [N/B] | Pause [P] | Frame [, .] | Rate [U/I] | Restart [R]\n");
        sb.append("Kamehameha [K]: charge, fire, end (Goku) | Cut [X] | View [V]: ")
                .append(bystander ? "yours, a bystander" : "the cut's camera").append('\n');
        sb.append("Stage [E]: ").append(stage != null ? "ocean arena" : "none").append(" | Night [F9]");
        if (outlinePass != null) sb.append(" | Outline [O]: ").append(OUTLINE_TESTS[outlineTests]);
        if (kit != null && kit.failed()) sb.append("\nThe kit failed to load: ").append(kit.failures());
        else if (kit != null && !kit.ready()) sb.append("\nLoading the kit...");
        return sb.toString();
    }

    @Override
    public void dispose() {
        CgLoadingScreen.remove(arena);
        if (submit != null) submit.close();
        if (beams != null) beams.close();
        if (outline != null) outline.close();
        if (stage != null) stage.close();
        if (darken != null) darken.close();
        if (stageLight != null) stageLight.close();
        CgWorld.client().timeDilation(1f);   // the client world outlives the scene
        CgAnimation animation = CgAnimation.client();
        for (Actor actor : actors) {
            actor.release();
            animation.remove(actor.instance);
        }
    }

    @Override public boolean isRunning()                { return running; }
    @Override public boolean uses3DCamera()             { return true; }
    @Override public boolean shouldShutdownOnComplete() { return !Double.isNaN(EXIT_AFTER); }
    @Override public boolean editorEnvironment()        { return true; }
}
