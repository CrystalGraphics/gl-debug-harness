package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.CgAnimation;
import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeletonDefinition;
import com.crystalgraphics.api.animation.CgSocket;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxOwner;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Skinned meshes playing their clips, to look at and play with: Kakarot's Goku and his move clips, and Kurama with
 * his. Each is a {@link CgAnimInstance} posed by {@link CgAnimation} in {@code POSE} and drawn skinned on the GPU.
 *
 * <ul>
 *   <li>Tab picks Goku or Kurama; N and B its next and previous clip, cross-fading over 0.25 s.</li>
 *   <li>P pauses and plays; {@code ,} and {@code .} step a frame back and on (a seek: the pose playing reaches);
 *       I and O play slower and faster; R restarts the clip.</li>
 *   <li>K on Goku charges Kakarot's Kamehameha: the charge montage's clips, and the kit's start flash, charge and charge
 *       loop, each on the bone and frame the game's notifies place it; K again lets go.</li>
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
    /** Goku's sockets: the kit's skeleton asset, in its units.yaml's units. */
    private static final String GOKU_SKELETON = "skeletons/Art/MDL/Cpl/Cpl001/Model/Cpl001p1_Skeleton.yaml";
    private static final float FADE = 0.25f, FRAME = 1f / 30f;
    /**
     * K's charge: the kit's systems, each on the bone and offset its notify attaches it at in the charge montage, at
     * its frame (30 a second; {@code CgVfxKamehamehaScene}'s stations). The offsets are Unreal's, in the kit's units.
     */
    private record ChargeBeat(String system, float frame, Map<String, Object> socket, boolean loops) {
    }

    private static final String SKILL = "Art/EFF/e/skill/a/";
    private static final List<ChargeBeat> CHARGE = List.of(
            new ChargeBeat(SKILL + "cmn/Particles/PS_skill_cmn_start_01_00", 0f, Map.of("name", "kame_start", "from", "bone",
                    "bone", "pelvis", "rotation", Map.of("pitch", -90.0, "yaw", 0.0, "roll", 0.0)), false),
            new ChargeBeat(SKILL + "Cpl001/Particles/PS_skill_Cpl001_kameha_s_charge_01_00", 8f, Map.of("name", "kame_charge",
                    "from", "bone", "bone", "hand_r", "location", List.of(10.0, 0.0, -10.0)), false),
            new ChargeBeat(SKILL + "Cpl001/Particles/PS_skill_Cpl001_kameha_s_charge_01_00_lp", 17f, Map.of("name", "kame_loop",
                    "from", "bone", "bone", "hand_r"), true));
    /** Goku's Blueprint: its mesh node names his mesh's materials, one a slot, in slot order. */
    private static final String GOKU_PREFAB = "prefabs/Blueprints/Character/C001A/AT_Character_cpl001_BP.yaml";
    /** The namespace the kit's materials name their shading models by ({@code kakarot:cell_tone}). */
    private static final String SHADING = "kakarot";
    /** The charge montage's clips: its start, then its loop for as long as it is held. */
    private static final String CHARGE_START = "SKL001_Bgn_01ST", CHARGE_LOOP = "SKL001_Bgn_02LP";
    /** The camera at Minecraft's walk, metres a second: the harness's 0.25 crawls between bodies metres tall. */
    private static final float WALK = 4.3f;
    private static final float[] RATES = {0.1f, 0.25f, 0.5f, 1f, 1.5f, 2f};

    /** One skinned mesh on the floor, its clips, and the one playing. */
    private static final class Actor {
        final String name;
        final CgSkeletalMesh mesh;
        final List<CgAnimSequence> clips;
        final CgAnimInstance instance;
        final double x, z;
        final Matrix4f transform = new Matrix4f();
        final float height, r, g, b;
        /** K's charge playing: one system a beat, null before its frame; NaN timeline seconds when not charging. */
        final CgVfxSystemInstance<?>[] charge = new CgVfxSystemInstance<?>[CHARGE.size()];
        double chargedAt = Double.NaN;
        /** Its slots' kit materials by id, from its prefab, or empty; whether they are set on its instance yet. */
        List<String> kitMaterials = List.of();
        boolean dressed;
        /** The clip it played before K charged, played again on release. */
        int before;
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

        /** The clip whose name holds {@code part}, or -1. */
        int clip(String part) {
            for (int i = 0; i < clips.size(); i++) if (clips.get(i).name().contains(part)) return i;
            return -1;
        }

        /** Whether it carries the kit's charge: its clips and the sockets its beats ride. */
        boolean charges() {
            if (clip(CHARGE_START) < 0 || clip(CHARGE_LOOP) < 0) return false;
            for (ChargeBeat beat : CHARGE) if (mesh.socket((String) beat.socket().get("name")) == null) return false;
            return true;
        }

        /** The charge montage from now: its start clip, then its loop held. */
        void charge() {
            before = clip;
            CgAnimSequence start = clips.get(clip(CHARGE_START)), loop = clips.get(clip = clip(CHARGE_LOOP));
            double now = instance.time();
            instance.clear();
            instance.play(CgAnimSection.of(start).at(now).until(now + start.duration()).build());
            instance.play(CgAnimSection.of(loop).at(now + start.duration()).until(Double.POSITIVE_INFINITY).build());
            chargedAt = now;
        }

        /** Each beat's system once its frame is reached, a loop's again as it ends; on the timeline, as a notify. */
        void tickCharge(HarnessKitSystems systems) {
            if (Double.isNaN(chargedAt)) return;
            double into = instance.time() - chargedAt;
            for (int i = 0; i < CHARGE.size(); i++) {
                ChargeBeat beat = CHARGE.get(i);
                boolean due = charge[i] == null ? into >= beat.frame() * FRAME
                        : beat.loops() && charge[i].state() == CgVfxSystemInstance.State.DEAD;
                if (!due) continue;
                boolean first = charge[i] == null;
                charge[i] = VFX.system().spawn(systems.ref(i), x, 1.0, z)
                        .follow(instance.attachment((String) beat.socket().get("name")));
                if (first) LOGGER.info("[skinned-play] {}: {} at frame {}, on {}", name, systems.id(i), beat.frame(), beat.socket().get("bone"));
            }
        }

        /** Ends the charge: every system it played, and back to the clip it left. */
        void release() {
            for (int i = 0; i < charge.length; i++) {
                if (charge[i] != null) charge[i].kill();
                charge[i] = null;
            }
            chargedAt = Double.NaN;
        }

        String line() {
            CgAnimSequence s = clips.get(clip);
            CgAnimSection playing = instance.sections().get(instance.sections().size() - 1);
            return String.format(Locale.ROOT, "%s: clip %d/%d %s, %.2f / %.2f s at %.2fx%s", name, clip + 1, clips.size(),
                    s.name(), playing.animationTime(instance.time()), s.duration(), RATES[rate],
                    instance.playing() ? "" : " (paused)");
        }
    }

    private final List<Actor> actors = new ArrayList<>();
    /** The kit's charge systems, one a {@link #CHARGE} beat; null without the kit. */
    private HarnessKitSystems kit;
    private int selected;
    private CgMaterial material;
    private CgTickFunction submit;

    @Override
    public void init(HarnessContext ctx) {
        material = CgMaterial.load("assets/harness/shader/skinned_view.shader");
        String root = System.getProperty("user.dir").replace('\\', '/');
        root = root.endsWith("gl-debug-harness") ? root.substring(0, root.length() - "gl-debug-harness".length()) : root + "/";
        String goku = System.getProperty("crystalgraphics.harness.skinned.goku", root + KIT + "MDL/Cpl/Cpl001/Model/Cpl001p1c01s1.glb");
        String gokuClips = System.getProperty("crystalgraphics.harness.skinned.goku.clips", root + KIT + "ANM/cpl/C001A/BTL/SKL");
        String kurama = System.getProperty("crystalgraphics.harness.skinned.kurama", "X:/research/nsuns4/assets/5nrtspl1_extra/puppets/5nrvbod2.glb");

        if (new File(goku).isFile()) {
            CgSkeletalMesh mesh = withKitSockets(CgMeshLoader.skeletal(goku), root + KIT_ROOT);
            List<CgAnimSequence> clips = new ArrayList<>(mesh.sequences());
            File[] files = new File(gokuClips).listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".glb"));
            if (files != null) {
                Arrays.sort(files);
                for (File file : files) {
                    for (CgAnimSequence clip : CgMeshLoader.sequences(file.getAbsolutePath())) {
                        if (clip.trackCount() > 1) clips.add(clip);   // the _dmy files move one locator
                    }
                }
            }
            if (!clips.isEmpty()) actors.add(new Actor("Goku", mesh, clips, -2.0, -6.0, 1.9f, 0xFFF0A040));
        } else {
            LOGGER.warn("[skinned-play] no Goku at {}", goku);
        }
        if (new File(kurama).isFile()) {
            CgSkeletalMesh mesh = CgMeshLoader.skeletal(kurama);
            if (!mesh.sequences().isEmpty()) {
                actors.add(new Actor("Kurama", mesh, new ArrayList<>(mesh.sequences()), 8.0, -16.0, 9f, 0xFFE06030));
            }
        } else {
            LOGGER.warn("[skinned-play] no Kurama at {}", kurama);
        }
        CgAnimation animation = CgAnimation.client();
        for (Actor actor : actors) {
            animation.add(actor.instance);
            actor.play(0);
            LOGGER.info("[skinned-play] {}: {} bones, {} clips{}", actor.name, actor.mesh.skeleton().boneCount(),
                    actor.clips.size(), actor.charges() ? ", K charges the kit's Kamehameha" : "");
        }
        List<String> systems = new ArrayList<>();
        for (ChargeBeat beat : CHARGE) systems.add(beat.system());
        Path kitFolder = Paths.get(root + KIT_ROOT);
        if (Files.isDirectory(kitFolder)) {
            List<String> materials = List.of();
            for (Actor actor : actors) {
                if (!actor.name.equals("Goku")) continue;
                actor.kitMaterials = prefabMaterials(kitFolder.resolve(GOKU_PREFAB), actor.instance.materialSlots());
                materials = actor.kitMaterials;
            }
            kit = HarnessKitSystems.load(VFX.system(), "kakarot_kit", kitFolder, systems, materials, SHADING);
        }
        submit = CgWorld.client().schedule().add(CgTickGroup.SUBMIT, "harness:skinned-play", this::submit);
        ctx.getCamera3D().moveCamera(2f, 3f, 6f);
        ctx.getCamera3D().setPitch(-10f);
        ctx.getCamera3D().setMoveSpeed(WALK);
    }

    /**
     * {@code mesh} with Goku's skeleton asset's sockets and the charge montage's attach points, both in the kit's units,
     * where the kit at {@code kit} has them.
     */
    private static CgSkeletalMesh withKitSockets(CgSkeletalMesh mesh, String kit) {
        Path skeleton = Paths.get(kit, GOKU_SKELETON), units = Paths.get(kit, "units.yaml");
        if (!Files.isRegularFile(skeleton) || !Files.isRegularFile(units)) return mesh;
        try {
            CgUnits kitUnits = CgUnits.of(((Map<?, ?>) CgAssetYaml.read(Files.readString(units))).get("units"), CgUnits.HOST);
            Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read(Files.readString(skeleton));
            List<CgSocket> sockets = new ArrayList<>(CgSkeletonDefinition.compile(GOKU_SKELETON, tree, kitUnits).socketsOn(mesh.skeleton()));
            List<Map<String, Object>> beats = new ArrayList<>();
            for (ChargeBeat beat : CHARGE) beats.add(beat.socket());
            sockets.addAll(CgSkeletonDefinition.compile("charge montage", Map.of("sockets", beats), kitUnits).socketsOn(mesh.skeleton()));
            return mesh.withSockets(sockets);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The materials a prefab's first mesh names, one a slot in slot order: Unreal's component materials as the kit
     * exported them. Empty where it has none or not one for each of {@code slots}.
     */
    private static List<String> prefabMaterials(Path prefab, int slots) {
        if (!Files.isRegularFile(prefab)) return List.of();
        try {
            Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read(Files.readString(prefab));
            for (Object node : (List<?>) tree.get("nodes")) {
                if (!(((Map<?, ?>) node).get("meshes") instanceof List<?> meshes) || meshes.isEmpty()) continue;
                List<String> ids = new ArrayList<>();
                for (Object id : (List<?>) ((Map<?, ?>) meshes.get(0)).get("materials")) ids.add(String.valueOf(id));
                if (ids.size() == slots) return ids;
                LOGGER.warn("[skinned-play] {} names {} materials for {} slots: drawn plain", prefab, ids.size(), slots);
                return List.of();
            }
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** In SUBMIT, after POSE wrote this frame's palettes. */
    private void submit(float dt) {
        CgWorldRenderer world = CgWorldRenderer.get();
        for (Actor actor : actors) {
            if (kit != null && kit.ready()) {
                actor.tickCharge(kit);
                if (!actor.dressed && !actor.kitMaterials.isEmpty()) {
                    for (int s = 0; s < actor.kitMaterials.size(); s++) actor.instance.material(s, CgMaterial.of(kit.material(s).get()));
                    actor.dressed = true;
                    LOGGER.info("[skinned-play] {}: drawn in its {} kit materials", actor.name, actor.kitMaterials.size());
                }
            }
            if (actor.instance.palette() < 0) continue;
            if (actor.dressed) {
                world.draw(actor.instance);
            } else {
                world.draw(actor.instance, material).pad(actor.height * 50f).custom(0, actor.r, actor.g, actor.b, 1f).submit();
            }
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
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
            case CgKeyCodes.KEY_P -> instance.playing(!instance.playing());
            case CgKeyCodes.KEY_COMMA -> instance.playing(false).seek(instance.time() - FRAME);
            case CgKeyCodes.KEY_PERIOD -> instance.playing(false).seek(instance.time() + FRAME);
            case CgKeyCodes.KEY_I -> instance.rate(RATES[actor.rate = Math.max(0, actor.rate - 1)]);
            case CgKeyCodes.KEY_O -> instance.rate(RATES[actor.rate = Math.min(RATES.length - 1, actor.rate + 1)]);
            case CgKeyCodes.KEY_K -> {
                if (!Double.isNaN(actor.chargedAt)) {
                    actor.release();
                    actor.play(actor.before);
                } else if (actor.charges() && kit != null && kit.ready()) {
                    actor.charge();
                }
            }
            default -> {
            }
        }
        return true;
    }

    @Override
    public String hudLine() {
        if (actors.isEmpty()) return "skinned-play: no meshes found (see the scene's javadoc for the paths)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < actors.size(); i++) sb.append(i == selected ? "> " : "  ").append(actors.get(i).line()).append('\n');
        sb.append("Tab actor  N/B clip  P pause  , . frame  I/O rate  R restart  K charge (Goku: the kit's Kamehameha)  F9 night");
        if (kit != null && kit.failed()) sb.append("\nthe kit's charge failed to load: see the log");
        else if (kit != null && !kit.ready()) sb.append("\nloading the kit's charge...");
        return sb.toString();
    }

    @Override
    public void dispose() {
        if (submit != null) submit.close();
        CgAnimation animation = CgAnimation.client();
        for (Actor actor : actors) {
            actor.release();
            animation.remove(actor.instance);
        }
    }

    @Override public boolean isRunning()                { return true; }
    @Override public boolean uses3DCamera()             { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
    @Override public boolean editorEnvironment()        { return true; }
}
