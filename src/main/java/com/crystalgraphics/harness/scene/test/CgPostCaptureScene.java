package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgLoadHandle;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.registry.CgAssetSource;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.volume.CgPostVolumeSpawner;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A kit sequence's post spawnables against a game's capture: the captured frame before the game's post drawn as our
 * scene, the sequence's post volumes played over it at the captured tick, and the picture scored against the captured
 * frame after the post. Scene id {@code post-capture}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=post-capture --device=vulkan" \
 *     -Pharness.jvmArgs="-Dcrystalgraphics.harness.postCapture.scene=<before>.png \
 *     -Dcrystalgraphics.harness.postCapture.reference=<after>.png"
 * // another sequence and tick
 * ... -Dcrystalgraphics.harness.postCapture.sequence=<ns>:<path> -Dcrystalgraphics.harness.postCapture.tick=30
 * }</pre>
 *
 * <ul>
 *   <li>The pictures are display-encoded 8-bit, top row first; the target is their size. A pixel is compared in
 *       those codes, 0 to 1.</li>
 *   <li>Only the post spawnables play: every other kind spawns a fixture, every role is a caster at the origin, and
 *       its systems are not compiled.</li>
 *   <li>It first checks the scene comes back byte for byte with nothing playing; then plays, re-evaluating at the
 *       tick for {@link #SETTLE} frames so every post program is built, and scores the last.</li>
 *   <li>Prints {@code [post-capture] PASS} or {@code FAIL}: the frame's mean difference at most {@link #MEAN}, and
 *       each pick (a 3x3 mean) within {@link #PICK} of the reference's per channel; the same scores without the post
 *       for comparison, and every pick both ways.</li>
 * </ul>
 */
public final class CgPostCaptureScene implements InteractiveSceneLifecycle {

    private static final String TAG = "[post-capture] ";
    private static final String KITS = "../plan/crystalgraphics/vfx-aaa/local/examples/";
    private static final int SETTLE = 30, LOAD_LIMIT = 600;
    /** The bars: a frame's mean difference, and a pick's per channel, in codes 0 to 1. */
    private static final double MEAN = 0.01, PICK = 0.03;
    /** Where the picks are, in screen fractions from the top left: the centre, the subject, two corners, the top. */
    private static final double[][] PICKS = {{0.4, 0.5}, {0.5, 0.45}, {0.1, 0.1}, {0.9, 0.9}, {0.5, 0.05}};

    private BufferedImage scene, reference;
    private ByteBuffer pixels;
    private String sequenceId;
    private int tick;
    private CgAssetRef<CgSequenceDefinition> ref;
    private CgLoadHandle handle;
    private CgSequencer sequencer;
    private CgSequencePlayer player;
    private HarnessFboHelper target;
    private boolean running = true;
    private int loadFrames, played;
    private final Matrix4f view = new Matrix4f(), projection = new Matrix4f();

    @Override
    public void init(HarnessContext ctx) {
        scene = read("scene");
        reference = read("reference");
        if (scene.getWidth() != reference.getWidth() || scene.getHeight() != reference.getHeight()) {
            throw new IllegalStateException("the scene is " + scene.getWidth() + "x" + scene.getHeight() + ", the reference "
                    + reference.getWidth() + "x" + reference.getHeight());
        }
        int w = scene.getWidth(), h = scene.getHeight();
        pixels = ByteBuffer.allocateDirect(4 * w * h).order(ByteOrder.nativeOrder());
        for (int y = h - 1; y >= 0; y--) {   // GL's first row is the bottom
            for (int x = 0; x < w; x++) {
                int p = scene.getRGB(x, y);
                pixels.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) 0xFF);
            }
        }
        pixels.flip();
        sequenceId = System.getProperty("crystalgraphics.harness.postCapture.sequence", "nsuns4_kit:5nrtspl1/5nrtspl1_atk040");
        tick = Integer.getInteger("crystalgraphics.harness.postCapture.tick", 18);
        String namespace = sequenceId.substring(0, sequenceId.indexOf(':'));
        Path kit = Paths.get(System.getProperty("crystalgraphics.harness.postCapture.kit", KITS + namespace));
        if (!Files.isDirectory(kit)) throw new IllegalStateException("no kit at " + kit.toAbsolutePath() + ": check out plan/");
        // No system compiler: its systems stay unresolved, as a server's are, since only its post plays here.
        CgSequenceDefinition.register();
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.registerEngine();
        registry.scan(namespace, kit, CgAssetSource.DEV);
        CgVfxAssets assets = new CgVfxAssets(registry);
        CgVfxAssets.install(assets);
        ref = assets.declare(CgSequenceKinds.SEQUENCE, sequenceId);
        handle = assets.load(List.of(ref), CgLoadPriority.MOVE);
        target = HarnessFboHelper.create(w, h, true);
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

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
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
            int changed = changed(draw(ctx, "untouched"), scene);
            if (changed > 0) {
                finish("FAIL on " + device() + ": with nothing playing, " + changed + " pixels of the scene changed");
                return;
            }
            start();
        }
        sequencer.evaluate(0f);
        BufferedImage ours = draw(ctx, played + 1 == SETTLE ? "post" : null);
        if (++played < SETTLE) return;
        report(ours);
    }

    private void start() {
        sequencer = new CgWorld().add(new CgSequencer());
        CgSequenceDefinition definition = ref.get();
        CgPostVolumeSpawner post = new CgPostVolumeSpawner(CgPostStack.get());
        for (CgSequenceDefinition.Spawnable s : definition.spawnables()) {
            if (!s.kind().equals("post")) sequencer.spawner(s.kind(), (p, sp, at) -> new CgSequenceSpawnScene.Fixture());
        }
        sequencer.spawner("post", post);
        Map<String, CgSequenceRole> roles = new HashMap<>();
        for (CgSequenceDefinition.Role r : definition.roles()) roles.put(r.name(), new CgSequenceSpawnScene.Caster());
        player = sequencer.play(definition, roles).seek(definition.seconds(tick));
    }

    /** The scene into the target, then the world's stages over it; captured under {@code name}, or read back unsaved. */
    private BufferedImage draw(HarnessContext ctx, String name) {
        int w = scene.getWidth(), h = scene.getHeight();
        CgWorldRenderer.get().install();
        CgPostStack.get().install();
        target.bind();
        target.clear(0f, 0f, 0f, 1f);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, target.getColorTexId());
        CgGL.glTexSubImage2D(CgGL.GL_TEXTURE_2D, 0, 0, 0, w, h, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, pixels);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, 0);
        projection.setPerspective((float) Math.toRadians(60), (float) w / h, 0.05f, 100f);
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, w, h, target.getFboId()).view().set(0, 0, 0, view, projection);
            stage.fire();
        }
        CgFrameRing.endFrame();
        String file = name != null ? name + ".png" : "settling.png";
        target.captureToFile(ctx.getOutputDir(), file);
        target.unbind();
        try {
            return ImageIO.read(new File(ctx.getOutputDir(), file));
        } catch (IOException e) {
            throw new IllegalStateException("capture " + file + " could not be read", e);
        }
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
        finish((pass ? "PASS" : "FAIL") + " on " + device() + ": " + sequenceId + " at tick " + tick + " against the capture: mean "
                + String.format("%.4f, p99 %.4f, max %.3f; without the post mean %.4f, p99 %.4f", post[0], post[1], post[2], none[0], none[1])
                + (validation > 0 ? "; " + validation + " Vulkan validation errors" : "") + picks);
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
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        return device == null ? "gl" : device.name();
    }

    @Override
    public void dispose() {
        if (target != null) target.delete();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return true; }
}
