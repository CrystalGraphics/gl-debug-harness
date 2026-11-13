package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.CgAnimation;
import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.mesh.geckolib.CgGeckoAnimation;
import com.crystalgraphics.api.mesh.geckolib.CgGeckoModel;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTextures;
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
import org.joml.Matrix4f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * A kit's skinned models standing in a row, each playing its clips through the engine's own path: posed by
 * {@link CgAnimation} in {@code POSE}, skinned on the GPU and drawn in Minecraft's entity look
 * ({@code materials/entity.shader}). A GeckoLib kit's {@code .geo.json} models play every clip of its
 * {@code .animation.json} files, converted for each model as GeckoLib plays them; any other kit's skinned glTF, GLB or
 * {@code .cgmesh} plays the clips it carries. Scene id {@code model-play}.
 *
 * <pre>{@code
 * // RPG-Core's GeckoLib models and clips, in the player texture
 * R=X:/projects/Dragon-Forge-NeoForge/RPG-Core-NeoForge/src/main/resources
 * ./gradlew :gl-debug-harness:runHarness -Pharness.assetRoots=$R --args="--mode=model-play" \
 *     -Dcrystalgraphics.harness.modelPlay.namespace=rpgcore -Dcrystalgraphics.harness.modelPlay.kit=$R/assets/rpgcore \
 *     -Dcrystalgraphics.harness.modelPlay.texture=textures/player/base.png
 * // one model, starting on one clip
 * ... -Dcrystalgraphics.harness.modelPlay.models=geo/model.geo.json -Dcrystalgraphics.harness.modelPlay.clip=walk
 * // another kit's skinned meshes, each fitted to 1.8 blocks
 * ... -Dcrystalgraphics.harness.modelPlay.models=models/body.glb,models/beast.cgmesh
 * // N / B: next / previous clip   M: next model (the HUD's)   `: play the clip again   P: pause or play
 * // , .: pause and step 1/60 s back / forward   U / I: rate down / up   K: loop every clip   Left / Right: turn them
 * }</pre>
 *
 * <ul>
 *   <li>{@code .models} are paths under the kit; without it, every {@code .geo.json} under the kit. A file that fails
 *       to load or holds no skinned mesh stops the scene, naming it.</li>
 *   <li>{@code .animations} (GeckoLib only) are folders or files under the kit, {@code animations} by default. Each
 *       model takes every animation in them; a bone it lacks is skipped, as GeckoLib skips it.</li>
 *   <li>N and B move every model to the same clip index, so models sharing their files play the same clip.</li>
 *   <li>A clip loops as its file says: {@code loop: true} loops, {@code hold_on_last_frame} holds, and a clip that
 *       plays once ends on the rest pose, as GeckoLib's controller leaves it. K loops all of them.</li>
 *   <li>A GeckoLib model stands at its own size in blocks; any other is scaled to {@code .height} (1.8).
 *       {@code .spacing} (1.5) is the gap between them, {@code .distance} (4) the camera's from the row.</li>
 *   <li>Under VFX Lab's sky and floor; F9 day and night.</li>
 * </ul>
 */
public final class CgModelPlayScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private static final String TAG = "[model-play] ";
    private static final String P = "crystalgraphics.harness.modelPlay.";
    private static final float[] RATES = {0.1f, 0.25f, 0.5f, 1f, 2f};
    private static final double STEP = 1.0 / 60;
    /** The span a held clip's last frame loops over, seconds. */
    private static final float HOLD = 0.001f;
    private static final float TURN = (float) Math.toRadians(15);

    /** One clip: its name in the HUD, its sequence, and whether its file loops it or holds its last frame. */
    private record Clip(String name, CgAnimSequence sequence, boolean loops, boolean holds) {
    }

    /** One model in the row: its instance, its clips, and its placement before the row's turn. */
    private static final class Model {
        final String name;
        final CgAnimInstance instance;
        final List<Clip> clips;
        final Matrix4f fit;
        final double x;
        int clip;

        Model(String name, CgSkeletalMesh mesh, List<Clip> clips, Matrix4f fit, double x) {
            this.name = name;
            this.instance = new CgAnimInstance(mesh);
            this.clips = clips;
            this.fit = fit;
            this.x = x;
        }
    }

    private final List<Model> models = new ArrayList<>();
    private final Matrix4f placed = new Matrix4f();
    private CgTickFunction submit;
    private CgMaterial material;
    private boolean running = true, loopAll;
    private int selected, rate = 3, clipIndex;
    private float yaw = (float) Math.PI;

    @Override
    public void init(HarnessContext ctx) {
        String namespace = require("namespace");
        Path kit = Paths.get(require("kit"));
        float height = Float.parseFloat(System.getProperty(P + "height", "1.8"));
        double spacing = Double.parseDouble(System.getProperty(P + "spacing", "1.5"));
        float distance = Float.parseFloat(System.getProperty(P + "distance", "4"));

        String texture = System.getProperty(P + "texture");
        CgMaterialDefinition.Builder body = CgMaterialDefinition.of("harness:model_play/body", "crystalgraphics:shaders/materials/entity.shader")
                .cull(CgCullState.NONE);   // GeckoLib's entities draw as entityCutoutNoCull
        if (texture != null) {
            body.texture("_MainTex", CgTextures.of(namespace + ":" + texture),
                    CgSampler.Filter.NEAREST, CgSampler.Address.CLAMP);
        }
        material = CgMaterial.of(body.build());

        List<String> paths = list(P + "models");
        if (paths.isEmpty()) paths = files(kit, kit, ".geo.json");
        if (paths.isEmpty()) throw new IllegalStateException(TAG + "no model in " + kit + ": name them with -D" + P + "models");
        List<String> animations = new ArrayList<>();
        for (String a : list(P + "animations").isEmpty() ? List.of("animations") : list(P + "animations")) {
            Path at = kit.resolve(a);
            if (Files.isDirectory(at)) animations.addAll(files(kit, at, ".animation.json"));
            else if (Files.isRegularFile(at)) animations.add(a.replace('\\', '/'));
            else if (!list(P + "animations").isEmpty()) throw new IllegalStateException(TAG + "no animations at " + at);
        }

        double x0 = -spacing * (paths.size() - 1) / 2;
        for (int i = 0; i < paths.size(); i++) {
            String id = namespace + ":" + paths.get(i);
            models.add(id.endsWith(".geo.json") ? gecko(id, namespace, animations, x0 + i * spacing)
                    : other(id, height, x0 + i * spacing));
        }

        String start = System.getProperty(P + "clip");
        CgAnimation animation = CgAnimation.client();
        for (Model m : models) {
            animation.add(m.instance);
            m.instance.material(0, material);
            for (int s = 1; s < m.instance.materialSlots(); s++) m.instance.material(s, material);
            if (start != null) {
                for (int c = 0; c < m.clips.size(); c++) {
                    String name = m.clips.get(c).name();
                    if (name.equals(start) || name.endsWith("/" + start)) m.clip = c;
                }
            }
            play(m);
            System.out.println(TAG + m.name + ": " + m.instance.mesh().skeleton().boneCount() + " bones, " + m.clips.size() + " clips");
        }
        place();

        ctx.getCamera3D().moveCamera(0f, 1.2f, distance);
        ctx.getCamera3D().setPitch(-5f);
        // In SUBMIT, after POSE wrote this frame's palettes
        submit = CgWorld.client().schedule().add(CgTickGroup.SUBMIT, "harness:model-play", dt -> {
            CgWorldRenderer world = CgWorldRenderer.get();
            for (Model m : models) if (m.instance.palette() >= 0) world.draw(m.instance);
        });
    }

    /** A GeckoLib model at its own size, with every animation in {@code files} converted for it. */
    private static Model gecko(String id, String namespace, List<String> files, double x) {
        CgGeckoModel model = CgGeckoModel.load(id);
        List<Clip> clips = new ArrayList<>();
        for (String file : files) {
            String stem = file.substring(file.lastIndexOf('/') + 1).replace(".animation.json", "");
            for (CgGeckoAnimation a : model.animations(namespace + ":" + file)) {
                clips.add(new Clip(stem + "/" + a.name(), a.sequence(), a.loop() == CgGeckoAnimation.Loop.LOOP,
                        a.loop() == CgGeckoAnimation.Loop.HOLD_ON_LAST_FRAME));
            }
        }
        return new Model(id, model.mesh(), clips, new Matrix4f(), x);
    }

    /** A skinned glTF, GLB or cooked mesh scaled to {@code height} blocks, its feet on the floor, with the clips it carries. */
    private static Model other(String id, float height, double x) {
        CgSkeletalMesh mesh = CgMeshLoader.skeletal(id);
        List<Clip> clips = new ArrayList<>();
        for (CgAnimSequence s : mesh.sequences()) clips.add(new Clip(s.name(), s, true, false));
        CgAABB box = mesh.mesh().bounds(new CgAABB());
        float scale = height / (float) Math.max(box.maxY() - box.minY(), 1e-4);
        Matrix4f fit = new Matrix4f().scaling(scale).translate((float) -(box.minX() + box.maxX()) / 2f, (float) -box.minY(),
                (float) -(box.minZ() + box.maxZ()) / 2f);
        return new Model(id, mesh, clips, fit, x);
    }

    /** {@code m}'s clip from now: looped or held as its file says, or by K; played once, it ends on the rest pose. */
    private void play(Model m) {
        CgAnimInstance instance = m.instance;
        instance.clear();
        if (m.clips.isEmpty()) return;
        Clip clip = m.clips.get(Math.floorMod(m.clip, m.clips.size()));
        double now = instance.time();
        if (clip.loops() || loopAll) {
            instance.play(CgAnimSection.of(clip.sequence()).at(now).until(Double.POSITIVE_INFINITY).loop().build());
            return;
        }
        instance.play(CgAnimSection.of(clip.sequence()).at(now).build());
        if (clip.holds()) {
            // Its last millisecond looped from the clip's end on: the last frame, held (a section must keep some span)
            float end = clip.sequence().duration();
            instance.play(CgAnimSection.of(clip.sequence()).at(now + end).until(Double.POSITIVE_INFINITY)
                    .startOffset(Math.max(0f, end - HOLD)).build());
        }
    }

    /** Each model at its place in the row, turned by the row's yaw about its own feet. */
    private void place() {
        for (Model m : models) m.instance.place(m.x, 0.0, 0.0, placed.rotationY(yaw).mul(m.fit));
    }

    private static String require(String key) {
        String v = System.getProperty(P + key);
        if (v == null) throw new IllegalStateException(TAG + "-D" + P + key + " is required");
        return v;
    }

    private static List<String> list(String property) {
        List<String> out = new ArrayList<>();
        for (String s : System.getProperty(property, "").split(",")) if (!s.isBlank()) out.add(s.trim().replace('\\', '/'));
        return out;
    }

    /** Every file under {@code dir} ending in {@code suffix}, as paths under {@code kit}, sorted. */
    private static List<String> files(Path kit, Path dir, String suffix) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(suffix))
                    .map(p -> kit.relativize(p).toString().replace('\\', '/')).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(TAG + "cannot list " + dir, e);
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        if (!event.pressed()) return true;
        int key = event.key();
        // Held, a step or a turn repeats as the OS repeats it; every other key acts on its press alone
        switch (key) {
            case CgKeyCodes.KEY_COMMA, CgKeyCodes.KEY_PERIOD -> {
                for (Model m : models) {
                    m.instance.playing(false);
                    m.instance.seek(Math.max(0, m.instance.time() + (key == CgKeyCodes.KEY_PERIOD ? STEP : -STEP)));
                }
                return true;
            }
            case CgKeyCodes.KEY_LEFT, CgKeyCodes.KEY_RIGHT -> {
                yaw += key == CgKeyCodes.KEY_RIGHT ? TURN : -TURN;
                place();
                return true;
            }
            default -> {
            }
        }
        if (event.repeat()) return true;
        switch (key) {
            case CgKeyCodes.KEY_N, CgKeyCodes.KEY_B -> {
                clipIndex += key == CgKeyCodes.KEY_N ? 1 : -1;
                for (Model m : models) {
                    m.clip = clipIndex;
                    play(m);
                }
            }
            case CgKeyCodes.KEY_GRAVE -> models.forEach(this::play);
            case CgKeyCodes.KEY_M -> selected = (selected + 1) % models.size();
            case CgKeyCodes.KEY_K -> {
                loopAll = !loopAll;
                models.forEach(this::play);
            }
            case CgKeyCodes.KEY_P -> {
                boolean playing = !models.get(0).instance.playing();
                for (Model m : models) m.instance.playing(playing);
            }
            case CgKeyCodes.KEY_U, CgKeyCodes.KEY_I -> {
                rate = Math.max(0, Math.min(RATES.length - 1, rate + (key == CgKeyCodes.KEY_I ? 1 : -1)));
                for (Model m : models) m.instance.rate(RATES[rate]);
            }
            default -> {
            }
        }
        return true;
    }

    /** The HUD: the picked model, its clip and clock, then the row's switches. */
    @Override
    public String hudLine() {
        Model m = models.get(selected);
        CgAnimInstance instance = m.instance;
        if (m.clips.isEmpty()) {
            return String.format(Locale.ROOT, "%nModel [M]: %s (%d / %d) | no clips", m.name, selected + 1, models.size());
        }
        Clip clip = m.clips.get(Math.floorMod(m.clip, m.clips.size()));
        List<CgAnimSection> sections = instance.sections();
        float at = sections.isEmpty() ? 0f : sections.get(0).animationTime(instance.time());
        return String.format(Locale.ROOT, "%nModel [M]: %s (%d / %d)%n"
                        + "Clip [N/B]: %s (%d / %d) | %s | Again [`]%n"
                        + "%.3f / %.3f s | %s [P] | Step [, .] | Rate [U/I]: x%s | Loop all [K]: %s | Turn [Left/Right]",
                m.name, selected + 1, models.size(), clip.name(), Math.floorMod(m.clip, m.clips.size()) + 1, m.clips.size(),
                clip.loops() ? "loops" : clip.holds() ? "holds" : "once", at, clip.sequence().duration(),
                instance.playing() ? "playing" : "paused", RATES[rate], loopAll ? "on" : "off");
    }

    @Override
    public void dispose() {
        if (submit != null) submit.close();
        for (Model m : models) CgAnimation.client().remove(m.instance);
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean editorEnvironment() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
