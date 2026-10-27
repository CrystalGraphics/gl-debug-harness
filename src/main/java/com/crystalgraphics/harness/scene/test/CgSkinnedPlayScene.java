package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.CgAnimation;
import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxOwner;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Skinned meshes playing their clips, to look at and play with: Kakarot's Goku and his move clips, and Kurama with
 * his. Each is a {@link CgAnimInstance} posed by {@link CgAnimation} in {@code POSE} and drawn skinned on the GPU.
 *
 * <ul>
 *   <li>Tab picks Goku or Kurama; N and B its next and previous clip, cross-fading over 0.25 s.</li>
 *   <li>P pauses and plays; {@code ,} and {@code .} step a frame back and on (a seek: the pose playing reaches);
 *       I and O play slower and faster; R restarts the clip.</li>
 *   <li>{@code crystalgraphics.harness.skinned.goku} (the kit's {@code Cpl001p1c01s1.glb}) and {@code .goku.clips}
 *       (its {@code ANM/cpl/C001A/BTL/SKL} folder); {@code .kurama} (NSUNS4's {@code 5nrvbod2.glb}). One that is
 *       missing is left out.</li>
 * </ul>
 */
public class CgSkinnedPlayScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private static final Logger LOGGER = LogManager.getLogger(CgSkinnedPlayScene.class);
    private static final CgVfxOwner VFX = CgVfx.owner("harness");
    private static final String KIT = "plan/crystalgraphics/vfx-aaa/local/examples/kakarot_kit/models/Art/";
    private static final float FADE = 0.25f, FRAME = 1f / 30f;
    /** K's look at a 1.9-block character: the stock wave is sized for a player's beam, its ball wider than Goku. */
    private static final float WAVE_SCALE = 0.15f, HUMAN = 1.9f;
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
        /** The right hand's bone name, what K's charge rides. */
        final String hand;
        /** The Kamehameha scaled to this actor's height. */
        final CgVfxLook wave;
        CgVfxSystemInstance<CgEnergyWave> charge;
        int clip;
        int rate = 3;

        /** The bone a right hand is on: the first whose name says hand and right, else the root. */
        private static String rightHand(CgSkeletalMesh mesh) {
            CgSkeleton s = mesh.skeleton();
            for (int b = 0; b < s.boneCount(); b++) {
                String n = s.name(b).toLowerCase(Locale.ROOT);
                if (n.contains("hand") && (n.endsWith("_r") || n.startsWith("r_") || n.contains("right") || n.contains("_r_"))) {
                    return s.name(b);
                }
            }
            return s.name(0);
        }

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
            this.hand = rightHand(mesh);
            this.wave = CgEnergyWave.kamehameha().derive().scaled(WAVE_SCALE * height / HUMAN).build();
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

        String line() {
            CgAnimSequence s = clips.get(clip);
            CgAnimSection playing = instance.sections().get(instance.sections().size() - 1);
            return String.format(Locale.ROOT, "%s: clip %d/%d %s, %.2f / %.2f s at %.2fx%s", name, clip + 1, clips.size(),
                    s.name(), playing.animationTime(instance.time()), s.duration(), RATES[rate],
                    instance.playing() ? "" : " (paused)");
        }
    }

    private final List<Actor> actors = new ArrayList<>();
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
            CgSkeletalMesh mesh = CgMeshLoader.skeletal(goku);
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
            LOGGER.info("[skinned-play] {}: {} bones, {} clips, K's charge on {}", actor.name, actor.mesh.skeleton().boneCount(),
                    actor.clips.size(), actor.hand);
        }
        submit = CgWorld.client().schedule().add(CgTickGroup.SUBMIT, "harness:skinned-play", this::submit);
        ctx.getCamera3D().moveCamera(2f, 3f, 6f);
        ctx.getCamera3D().setPitch(-10f);
        ctx.getCamera3D().setMoveSpeed(WALK);
    }

    /** In SUBMIT, after POSE wrote this frame's palettes. */
    private void submit(float dt) {
        CgWorldRenderer world = CgWorldRenderer.get();
        for (Actor actor : actors) {
            if (actor.instance.palette() < 0) continue;
            world.draw(actor.instance, material).pad(actor.height * 50f).custom(0, actor.r, actor.g, actor.b, 1f).submit();
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
                if (actor.charge != null) {
                    actor.charge.kill();
                    actor.charge = null;
                } else {
                    // A Kamehameha's charge riding the hand as posed each frame: a socket by its bone's name
                    actor.charge = VFX.play(CgEnergyWave.instance(actor.wave, actor.x, 1.0, actor.z)
                            .follow(instance, actor.hand).set(CgEnergyWave.RELEASE, Float.POSITIVE_INFINITY));
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
        return sb.append("Tab actor  N/B clip  P pause  , . frame  I/O rate  R restart  K charge on its right hand").toString();
    }

    @Override
    public void dispose() {
        if (submit != null) submit.close();
        CgAnimation animation = CgAnimation.client();
        for (Actor actor : actors) {
            if (actor.charge != null) actor.charge.kill();
            animation.remove(actor.instance);
        }
    }

    @Override public boolean isRunning()                { return true; }
    @Override public boolean uses3DCamera()             { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
    @Override public boolean editorEnvironment()        { return true; }
}
