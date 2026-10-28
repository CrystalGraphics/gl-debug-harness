package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.CgAnimation;
import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeletonDefinition;
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
import com.crystalgraphics.render.post.CgPostMaterial;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.sequence.CgSequenceDefinition;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequenceRole;
import com.crystalgraphics.sequence.CgSequenceSkeletal;
import com.crystalgraphics.sequence.CgSequenceSpawned;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxOwner;
import com.crystalgraphics.vfx.system.CgVfxSystemSpawner;
import com.crystalgraphics.world.CgAttachment;
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
    /** Goku's sockets: the kit's skeleton asset, in its units.yaml's units. */
    private static final String GOKU_SKELETON = "skeletons/Art/MDL/Cpl/Cpl001/Model/Cpl001p1_Skeleton.yaml";
    private static final float FADE = 0.25f, FRAME = 1f / 30f;
    /** K's Kamehameha: the kit's charge and shot montages, sequences; the shot's loop ends at its {@code End} section. */
    private static final String CHARGE = "kakarot_kit:Blueprints/Character/C001A/BTL/SKL/AM_C001A_S_SKL001_01",
            SHOT = "kakarot_kit:Blueprints/Character/C001A/BTL/SKL/AM_C001A_S_SKL001_02", SHOT_END = "End";
    /** Kakarot's battle outline, its renderer's pass as a post material, and the stencil it outlines Goku by. */
    private static final String BATTLE_OUTLINE = "kakarot_kit:Renderer/BattleOutline";
    private static final int GOKU_STENCIL = 1;
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

        /** Ends the montage playing: its clips leave the slot and its systems despawn at the sequencer's next evaluation. */
        void release() {
            if (move != null) move.stop();
            move = null;
            stage = Stage.IDLE;
        }

        String line() {
            if (move != null) {
                return String.format(Locale.ROOT, "%s: %s, %s at %.2f s", name, stage.name().toLowerCase(Locale.ROOT),
                        move.definition().id(), move.seconds());
            }
            CgAnimSequence s = clips.get(clip);
            CgAnimSection playing = instance.sections().get(instance.sections().size() - 1);
            return String.format(Locale.ROOT, "%s: clip %d/%d %s, %.2f / %.2f s at %.2fx%s", name, clip + 1, clips.size(),
                    s.name(), playing.animationTime(instance.time()), s.duration(), RATES[rate],
                    instance.playing() ? "" : " (paused)");
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
            return instance.attachment(socket == null ? "" : socket);   // an unknown name answers where it stands
        }

        @Override
        public int slot(String name) {
            return -1;
        }

        @Override
        public void set(int slot, float[] values, int mask) {
        }

        @Override
        public void get(int slot, float[] out) {
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * A montage's skill camera, taking its rig's slots and moving nothing: cameras are SQ10b's, and this scene's camera
     * is the user's.
     */
    private static final class HeldCamera implements CgSequenceSpawned {
        private final List<String> slots = new ArrayList<>();
        private final List<float[]> values = new ArrayList<>();

        @Override
        public int slot(String name) {
            int i = slots.indexOf(name);
            if (i >= 0) return i;
            slots.add(name);
            values.add(new float[4]);
            return slots.size() - 1;
        }

        @Override
        public void set(int slot, float[] v, int mask) {
            float[] to = values.get(slot);
            for (int i = 0; i < 4 && i < v.length; i++) if ((mask & 1 << i) != 0) to[i] = v[i];
        }

        @Override
        public void get(int slot, float[] out) {
            System.arraycopy(values.get(slot), 0, out, 0, Math.min(4, out.length));
        }

        @Override
        public void destroy(boolean graceful) {
        }
    }

    /** K's stages: the charge held, the shot's loop held, the shot's end playing out. */
    private enum Stage { IDLE, CHARGING, FIRING, ENDING }

    private final List<Actor> actors = new ArrayList<>();
    /** Goku's materials and the charge montage; null without the kit. */
    private HarnessKitSystems kit;
    private CgSequencer sequencer;
    private int selected;
    private CgMaterial material;
    private CgTickFunction submit;
    /** The battle outline on the post stack, once Goku is dressed. */
    private CgRenderStage.Registration outline;
    /** OL5's gate, under {@code -Dcrystalgraphics.harness.skinnedPlay.outlineCheck=true} ({@link SkinnedOutlineCheck}). */
    private SkinnedOutlineCheck outlineCheck;
    /** The outline's pass, and which of its edge tests U shows: all, then depth, normal and ID alone. */
    private CgMaterial outlinePass;
    private int outlineTests;
    private static final String[] OUTLINE_TESTS = {"all tests", "depth alone", "normal alone", "ID alone"};
    private boolean kitFailureLogged;

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
            if (!clips.isEmpty()) {
                Actor actor = new Actor("Goku", mesh, clips, -2.0, -6.0, 1.9f, 0xFFF0A040);
                actor.kit = true;
                actors.add(actor);
            }
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
                    actor.clips.size(), actor.kit ? ", K charges the kit's Kamehameha" : "");
        }
        Path kitFolder = Paths.get(root + KIT_ROOT);
        if (Files.isDirectory(kitFolder)) {
            List<String> materials = List.of();
            for (Actor actor : actors) {
                if (!actor.name.equals("Goku")) continue;
                actor.kitMaterials = prefabMaterials(kitFolder.resolve(GOKU_PREFAB), actor.instance.materialSlots());
                materials = new ArrayList<>(actor.kitMaterials);
                materials.add(BATTLE_OUTLINE);   // last: kit.material(slots)
            }
            kit = HarnessKitSystems.load(VFX.system(), "kakarot_kit", kitFolder, List.of(), materials, List.of(CHARGE, SHOT), SHADING);
        }
        CgWorld client = CgWorld.client();
        sequencer = client.subsystem(CgSequencer.class);
        if (sequencer == null) sequencer = client.add(new CgSequencer());
        sequencer.spawner("system", new CgVfxSystemSpawner(VFX.system()));
        sequencer.spawner("camera", (p, s, at) -> new HeldCamera());
        submit = client.schedule().add(CgTickGroup.SUBMIT, "harness:skinned-play", this::submit);
        ctx.getCamera3D().moveCamera(2f, 3f, 6f);
        ctx.getCamera3D().setPitch(-10f);
        ctx.getCamera3D().setMoveSpeed(WALK);
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
            if (actor.stage == Stage.ENDING && actor.move.finished()) {
                actor.release();
                LOGGER.info("[skinned-play] {}: the shot played out", actor.name);
            }
            if (kit != null && kit.failed() && !kitFailureLogged) {
                kitFailureLogged = true;
                LOGGER.warn("[skinned-play] the kit failed to load: {}", kit.failures());
            }
            if (kit != null && kit.ready()) {
                if (!actor.dressed && !actor.kitMaterials.isEmpty()) {
                    for (int s = 0; s < actor.kitMaterials.size(); s++) actor.instance.material(s, CgMaterial.of(kit.material(s).get()));
                    actor.dressed = true;
                    LOGGER.info("[skinned-play] {}: drawn in its {} kit materials", actor.name, actor.kitMaterials.size());
                    // Kakarot's battle outline: its renderer's pass over the characters, Goku's stencil 1 (OL5).
                    actor.instance.customDepth(GOKU_STENCIL, 0xFF);
                    if (outline == null) {
                        CgMaterial pass = CgMaterial.of(kit.material(actor.kitMaterials.size()).get());
                        if (Boolean.parseBoolean(System.getProperty("crystalgraphics.harness.skinnedPlay.outlineYellow", "true"))) {
                            // A debug view: every pixel the outline draws in full yellow, not the capture's 0.35 black.
                            pass.applyProperties(b -> b.vec4("OutlineColor", 1f, 1f, 0f, 1f).set1f("Intensity", 1f)
                                    .set1f("UseOutlineColor", 1f));
                        }
                        outlinePass = pass;
                        CgPostMaterial battle = CgPostMaterial.of(pass, CgPostPoint.AFTER_WORLD, 0);
                        outline = CgPostStack.get().add(battle);
                        LOGGER.info("[skinned-play] {}: outlined by {}", actor.name, BATTLE_OUTLINE);
                        if (Boolean.getBoolean("crystalgraphics.harness.skinnedPlay.outlineCheck")) {
                            outlineCheck = new SkinnedOutlineCheck(battle, List.of(actor.instance), actor.x, actor.z, actor.height);
                        }
                    }
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
        if (outlineCheck != null) outlineCheck.frame(ctx);
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
            case CgKeyCodes.KEY_K -> kamehameha(actor);
            case CgKeyCodes.KEY_U -> {
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
        LOGGER.info("[skinned-play] {}: {} ({})", actor.name, actor.stage, actor.move == null ? "let go" : actor.move.definition().id());
    }

    @Override
    public String hudLine() {
        if (actors.isEmpty()) return "skinned-play: no meshes found (see the scene's javadoc for the paths)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < actors.size(); i++) sb.append(i == selected ? "> " : "  ").append(actors.get(i).line()).append('\n');
        sb.append("Tab actor  N/B clip  P pause  , . frame  I/O rate  R restart  K Kamehameha: charge, fire, end (Goku)  F9 night");
        if (outlinePass != null) sb.append("\nU outline: ").append(OUTLINE_TESTS[outlineTests]);
        if (kit != null && kit.failed()) sb.append("\nthe kit failed to load: ").append(kit.failures());
        else if (kit != null && !kit.ready()) sb.append("\nloading the kit...");
        return sb.toString();
    }

    @Override
    public void dispose() {
        if (submit != null) submit.close();
        if (outline != null) outline.close();
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
