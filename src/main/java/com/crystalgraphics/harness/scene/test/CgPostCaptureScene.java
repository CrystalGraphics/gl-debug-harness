package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgLoadHandle;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.capture.HarnessCapture;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessTarget;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.CgRenderer;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.volume.CgPostVolumePreparer;
import com.crystalgraphics.render.post.volume.CgPostVolumeSpawner;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.definition.CgSequenceBinding;
import com.crystalgraphics.sequence.spawn.CgSequenceSystem;
import com.crystalgui.screen.CgUiScreen;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A kit sequence's post spawnables against a game's capture: the captured frame before the game's post drawn as our
 * scene, the sequence's post volumes played over it at the captured tick, and the picture scored against the captured
 * frame after the post. Scene id {@code post-capture}.
 *
 * <pre>{@code
 * // the gate, on the default sequence's pair in the research repo: captures/post_capture_5nrtspl1_atk040/
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=post-capture --rhi=vulkan" \
 *     -Pharness.jvmArgs="-Dcrystalgraphics.harness.postCapture.scene=X:/research/captures/post_capture_5nrtspl1_atk040/before.png \
 *     -Dcrystalgraphics.harness.postCapture.reference=X:/research/captures/post_capture_5nrtspl1_atk040/after.png"
 * // the same, live in the window until it is closed: 1 ours, 2 the game's, 3 before the post; left and right step a
 * // tick (shift: ten), space plays and pauses, home goes back to the captured tick, S scores what plays now
 * ... -Dcrystalgraphics.harness.postCapture.live=true
 * // another sequence and tick
 * ... -Dcrystalgraphics.harness.postCapture.sequence=<ns>:<path> -Dcrystalgraphics.harness.postCapture.tick=30
 * }</pre>
 *
 * <ul>
 *   <li>The pictures are display-encoded 8-bit, top row first; the target is their size. A pixel is compared in
 *       those codes, 0 to 1. Live, the picture fits the window, its aspect kept.</li>
 *   <li>The sequence's kit is the one the harness mounts ({@code local/kitroot}): a kit not built there fails its load.</li>
 *   <li>Only the post spawnables play: every other kind spawns a fixture, every role is a caster at the origin, the environment a fixture. Its
 *       systems and emitters load uncompiled, as a kind with no compiler does, so a particle module not built yet
 *       does not stop a post gate; the run's process compiles no system after it.</li>
 *   <li>It first checks the scene comes back byte for byte with nothing playing; then plays, re-evaluating at the
 *       tick for {@link #SETTLE} frames so every post program is built, and scores the last, saved as {@code post.png}.</li>
 *   <li>Prints {@code [post-capture] PASS} or {@code FAIL}: the frame's mean difference at most {@link #MEAN}, and
 *       each pick (a 3x3 mean) within {@link #PICK} of the reference's per channel; the same scores without the post
 *       for comparison, and every pick both ways. Live prints it too, then plays on.</li>
 * </ul>
 */
public final class CgPostCaptureScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private static final String TAG = "[post-capture] ";
    private static final int SETTLE = 30, LOAD_LIMIT = 600;
    /** The bars: a frame's mean difference, and a pick's per channel, in codes 0 to 1. */
    private static final double MEAN = 0.01, PICK = 0.03;
    /** Where the picks are, in screen fractions from the top left: the centre, the subject, two corners, the top. */
    private static final double[][] PICKS = {{0.4, 0.5}, {0.5, 0.45}, {0.1, 0.1}, {0.9, 0.9}, {0.5, 0.05}};

    private enum Showing { OURS, GAME, BEFORE }

    private final boolean live = Boolean.getBoolean("crystalgraphics.harness.postCapture.live");
    private BufferedImage scene, reference;
    private ByteBuffer pixels;
    private String sequenceId;
    private int tick;
    private CgAssetRef<CgSequenceDefinition> ref;
    private CgLoadHandle handle;
    private CgSequencer sequencer;
    private CgSequencePlayer player;
    private HarnessTarget target;
    private boolean running = true;
    private int loadFrames, played;
    private final Matrix4f view = new Matrix4f(), projection = new Matrix4f();

    // Live only
    private CgTexture2D before, after;
    private CgMaterialInstance shown;
    private final CgRecording presenting = new CgRecording();
    private final CgCamera window = new CgCamera();
    private Showing showing = Showing.OURS;
    private boolean playing, shift, scoreNow;
    private Runnable keysLeft;

    @Override
    public void init(HarnessContext ctx) {
        scene = read("scene");
        reference = read("reference");
        if (scene.getWidth() != reference.getWidth() || scene.getHeight() != reference.getHeight()) {
            throw new IllegalStateException("the scene is " + scene.getWidth() + "x" + scene.getHeight() + ", the reference "
                    + reference.getWidth() + "x" + reference.getHeight());
        }
        int w = scene.getWidth(), h = scene.getHeight();
        pixels = upload(scene);
        sequenceId = System.getProperty("crystalgraphics.harness.postCapture.sequence", "nsuns4_kit:5nrtspl1/5nrtspl1_atk040");
        tick = Integer.getInteger("crystalgraphics.harness.postCapture.tick", 18);
        CgAssetCompiler.register(CgParticleKinds.SYSTEM, CgAssetCompiler.RESOLVED);
        CgAssetCompiler.register(CgParticleKinds.EMITTER, CgAssetCompiler.RESOLVED);
        // The kits the harness mounted, scanned once at startup. Assets of its own: the engine's system preparer takes
        // a compiled system, and this one's are not
        CgVfxAssets assets = new CgVfxAssets(CgVfxAssets.instance().registry());
        assets.preparer(CgSequenceKinds.SEQUENCE, new CgPostVolumePreparer());
        CgVfxAssets.install(assets);
        ref = assets.declare(CgSequenceKinds.SEQUENCE, sequenceId);
        handle = assets.load(List.of(ref), CgLoadPriority.MOVE);
        target = HarnessTarget.create(w, h, true);
        if (live) {
            before = texture("post_capture.before", w, h, pixels);
            after = texture("post_capture.after", w, h, upload(reference));
            shown = CgMaterial.load("assets/harness/shader/post_capture_view.shader").instance();
        }
    }

    private static BufferedImage read(String which) {
        String path = System.getProperty("crystalgraphics.harness.postCapture." + which);
        if (path == null) throw new IllegalStateException("-Dcrystalgraphics.harness.postCapture." + which + "=<png> names the " + which);
        try {
            BufferedImage image = ImageIO.read(new File(path));
            if (image == null) throw new IllegalStateException(path + " is not an image");
            return image;
        } catch (IOException e) {
            throw new IllegalStateException(path + " could not be read", e);
        }
    }

    /** {@code image} as RGBA8 rows, bottom row first, as a texture's first row is. */
    private static ByteBuffer upload(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        ByteBuffer rows = ByteBuffer.allocateDirect(4 * w * h).order(ByteOrder.nativeOrder());
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                int p = image.getRGB(x, y);
                rows.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) 0xFF);
            }
        }
        return rows.flip();
    }

    private static CgTexture2D texture(String name, int w, int h, ByteBuffer rows) {
        CgTexture2D t = CgTextures.of(name, CgTextureDesc.of(w, h, CgFormat.RGBA8_UNORM));
        t.uploadRegion(0, 0, 0, w, h, rows);
        return t;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        listenForKeysLeft();
        if (player == null) {
            if (handle.failed()) {
                finish("FAIL on " + device() + ": " + sequenceId + " failed to load: " + ref.failure());
                return;
            }
            if (++loadFrames > LOAD_LIMIT) {
                finish("FAIL on " + device() + ": " + sequenceId + " not ready after " + LOAD_LIMIT + " frames: " + ref.state());
                return;
            }
            if (!handle.ready()) return;
            draw();
            int changed = changed(HarnessCapture.image(target.external()), scene);
            if (changed > 0) {
                finish("FAIL on " + device() + ": with nothing playing, " + changed + " pixels of the scene changed");
                return;
            }
            start();
            if (live) System.out.println(TAG + "live: " + status());
        }
        sequencer.evaluate(playing ? (float) frame.getDeltaTime() : 0f);
        draw();
        if (played < SETTLE && ++played == SETTLE || scoreNow) {
            scoreNow = false;
            BufferedImage ours = HarnessCapture.image(target.external());
            if (!live) HarnessCapture.write(ours, ctx.getOutputDir(), "post.png");
            report(ours);
        }
        if (live) present(ctx);
    }

    private void start() {
        sequencer = new CgWorld().add(new CgSequencer());
        // The stage's brightness and fog are the captured frame's already.
        sequencer.environment(new CgSequenceSpawnScene.Fixture());
        CgSequenceDefinition definition = ref.get();
        CgPostVolumeSpawner post = new CgPostVolumeSpawner(CgPostStack.get());
        for (CgSequenceDefinition.Spawnable s : definition.spawnables()) {
            if (!s.kind().equals("post")) sequencer.spawner(s.kind(), (p, sp, at) -> new StandIn());
        }
        sequencer.spawner("post", post);
        Map<String, CgSequenceRole> roles = new HashMap<>();
        for (CgSequenceDefinition.Role r : definition.roles()) roles.put(r.name(), new CgSequenceSpawnScene.Caster());
        player = sequencer.play(definition, roles).seek(definition.seconds(tick));
    }

    /** The scene into the target, then the world's stages over it. */
    private void draw() {
        int w = scene.getWidth(), h = scene.getHeight();
        CgWorldRenderer.get().install();
        CgPostStack.get().install();
        target.bind();
        target.clear(0f, 0f, 0f, 1f);
        target.getColor().uploadRegion(0, 0, 0, w, h, pixels);
        CgCamera.perspectiveMatrix(projection, 60f, (float) w / h, 0.05f, 100f);
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, w, h, target.external()).camera().set(0, 0, 0, view, projection);
            stage.fire();
        }
        CgFrameRing.endFrame();
        target.unbind();
    }

    /** The picture shown into the window, fitted with its aspect kept. */
    private void present(HarnessContext ctx) {
        int sw = ctx.getScreenWidth(), sh = ctx.getScreenHeight(), w = scene.getWidth(), h = scene.getHeight();
        float fit = Math.min((float) sw / w, (float) sh / h), fw = w * fit / sw, fh = h * fit / sh;
        CgTexture2D image = switch (showing) {
            case OURS -> target.getColor();
            case GAME -> after;
            case BEFORE -> before;
        };
        shown.sampler("_Image", 0, image).vec4("_Rect", (1 - fw) / 2, (1 - fh) / 2, (1 + fw) / 2, (1 + fh) / 2);
        presenting.reset();
        CgRasterPass pass = presenting.raster("post-capture.view").color(CgGraphTexture.current(), CgLoad.load())
                .camera(window.resolution(sw, sh)).order(CgOrder.LOOKBACK);
        pass.draw(CgMesh.quads(1), shown);
        pass.end();
        CgRenderer.executeNow(presenting);
    }

    // The session may not be up in init: listen for the keys the desktop leaves from the first frame
    private void listenForKeysLeft() {
        if (!live || keysLeft != null || !CgUiScreen.isInstalled()) return;
        keysLeft = CgUiScreen.onKeyLeft(this::key);
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        return key(event);
    }

    private boolean key(CgSystemInput.Keyboard.Event event) {
        int key = event.key();
        if (key == CgKeyCodes.KEY_LSHIFT || key == CgKeyCodes.KEY_RSHIFT) shift = event.pressed();
        if (!live || player == null || !event.pressed()) return false;
        switch (key) {
            case CgKeyCodes.KEY_1 -> showing = Showing.OURS;
            case CgKeyCodes.KEY_2 -> showing = Showing.GAME;
            case CgKeyCodes.KEY_3 -> showing = Showing.BEFORE;
            case CgKeyCodes.KEY_LEFT, CgKeyCodes.KEY_RIGHT -> {
                int step = (key == CgKeyCodes.KEY_RIGHT ? 1 : -1) * (shift ? 10 : 1);
                playing = false;
                seek(Math.max(0, ticks() + step));
            }
            case CgKeyCodes.KEY_HOME -> {
                playing = false;
                seek(tick);
            }
            case CgKeyCodes.KEY_SPACE -> {
                if (event.repeat()) return true;
                playing = !playing;
                if (playing && player.finished()) seek(0);
            }
            case CgKeyCodes.KEY_S -> scoreNow = true;
            default -> {
                return false;
            }
        }
        System.out.println(TAG + "live: " + status());
        return true;
    }

    private void seek(int ticks) {
        player.seek(player.definition().seconds(ticks));
    }

    /** The tick the player stands on, rounded. */
    private int ticks() {
        return (int) Math.round(player.seconds() / player.definition().seconds(1));
    }

    private String status() {
        String what = switch (showing) {
            case OURS -> "ours";
            case GAME -> "the game's";
            case BEFORE -> "before the post";
        };
        return what + " at tick " + ticks() + (playing ? ", playing" : ", paused") + (ticks() == tick ? " (the captured tick)" : "");
    }

    private void report(BufferedImage ours) {
        double[] post = score(ours), none = score(scene);
        StringBuilder picks = new StringBuilder();
        boolean pass = post[0] <= MEAN;
        for (double[] p : PICKS) {
            double[] game = pick(reference, p), got = pick(ours, p), before = pick(scene, p);
            double off = 0;
            for (int c = 0; c < 3; c++) off = Math.max(off, Math.abs(got[c] - game[c]));
            if (off > PICK) pass = false;
            picks.append(String.format("%n%s  (%.2f, %.2f): ours %s, the game's %s, before %s, off %.3f", TAG, p[0], p[1],
                    rgb(got), rgb(game), rgb(before), off));
        }
        int validation = PlatformServiceHarness.validationErrors();
        if (validation > 0) pass = false;
        String line = (pass ? "PASS" : "FAIL") + " on " + device() + ": " + sequenceId + " at tick " + (live ? ticks() : tick)
                + " against the capture: mean "
                + String.format("%.4f, p99 %.4f, max %.3f; without the post mean %.4f, p99 %.4f", post[0], post[1], post[2], none[0], none[1])
                + (validation > 0 ? "; " + validation + " Vulkan validation errors" : "") + picks;
        if (live) System.out.println(TAG + line);
        else finish(line);
    }

    /** {@code image} against the reference, every channel of every pixel in codes: the mean, the 99th percentile, the most. */
    private double[] score(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int[] histogram = new int[256];
        long sum = 0;
        int most = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = image.getRGB(x, y), b = reference.getRGB(x, y);
                for (int s = 0; s <= 16; s += 8) {
                    int d = Math.abs((a >> s & 0xFF) - (b >> s & 0xFF));
                    histogram[d]++;
                    sum += d;
                    most = Math.max(most, d);
                }
            }
        }
        long n = 3L * w * h, at = (long) Math.ceil(0.99 * n), seen = 0;
        int p99 = 0;
        for (int d = 0; d < 256; d++) {
            seen += histogram[d];
            if (seen >= at) {
                p99 = d;
                break;
            }
        }
        return new double[] {sum / (255.0 * n), p99 / 255.0, most / 255.0};
    }

    /** The 3x3 mean at {@code at}, in screen fractions from the top left, in codes 0 to 1. */
    private static double[] pick(BufferedImage image, double[] at) {
        int cx = (int) (at[0] * image.getWidth()), cy = (int) (at[1] * image.getHeight());
        double[] out = new double[3];
        for (int y = cy - 1; y <= cy + 1; y++) {
            for (int x = cx - 1; x <= cx + 1; x++) {
                int p = image.getRGB(x, y);
                out[0] += (p >> 16 & 0xFF) / (9 * 255.0);
                out[1] += (p >> 8 & 0xFF) / (9 * 255.0);
                out[2] += (p & 0xFF) / (9 * 255.0);
            }
        }
        return out;
    }

    private static String rgb(double[] c) {
        return String.format("(%.3f, %.3f, %.3f)", c[0], c[1], c[2]);
    }

    private static int changed(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) if ((a.getRGB(x, y) & 0xFFFFFF) != (b.getRGB(x, y) & 0xFFFFFF)) n++;
        }
        return n;
    }

    private void finish(String line) {
        System.out.println(TAG + line);
        running = false;
    }

    private static String device() {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        return device == null ? "gl" : device.name();
    }

    @Override
    public void dispose() {
        if (keysLeft != null) keysLeft.run();
        if (target != null) target.delete();
        if (before != null) before.release();
        if (after != null) after.release();
    }

    /**
     * What a kind other than post spawns: any slot taken, any node or material a part of its own, and a system its
     * tracks drive that draws nothing.
     */
    private static final class StandIn extends CgSequenceSpawnScene.Fixture implements CgSequenceSystem {
        private final Map<String, StandIn> parts = new HashMap<>();
        private boolean active;

        @Override
        public CgSequenceTarget part(CgSequenceBinding.Part part, String name) {
            return parts.computeIfAbsent(part + ":" + name, k -> new StandIn());
        }

        @Override public void activate(boolean reset) { active = true; }
        @Override public void deactivate() { active = false; }
        @Override public void kill() { active = false; }
        @Override public void detach() { }
        @Override public boolean active() { return active; }
        @Override public void age(float seconds) { }
        @Override public CgSequenceSystem emitter(String handle) { return parts.computeIfAbsent("emitter:" + handle, k -> new StandIn()); }
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return true; }
}
