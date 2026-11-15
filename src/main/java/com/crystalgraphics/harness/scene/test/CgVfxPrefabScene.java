package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgProceduralMesh;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.DeviceErrors;
import com.crystalgraphics.harness.util.HarnessTarget;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.vfx.scalability.CgVfxScalability;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.render.scene.prefab.CgPrefab;
import com.crystalgraphics.render.scene.prefab.CgPrefabPose;
import com.crystalgraphics.vfx.prefab.CgVfxPrefabEffect;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * G0.9's gate, prefab node trees: a 458-node prefab (a root, a pivot and 19 chains of 24) drawn through one effect, a
 * ball a node. Chain {@value #CUT}'s node {@value #CUT_AT} is at an opacity of 0, so it draws nothing and every node
 * under it whole; chain {@value #DIM}'s head is at 0.2, so it alone dims: a node's opacity is its own (aaa-design C5,
 * B9). The rest draw whole. Also times
 * {@link CgPrefabPose#evaluate()}, which must take a few microseconds.
 *
 * <p>Framed until its look is warm (a play waits on its precache before it draws), then photographed. Prints
 * {@code [vfx-prefab] PASS} or {@code FAIL}; run it on {@code gl}, {@code mac41} and {@code vulkan}.</p>
 */
public class CgVfxPrefabScene implements InteractiveSceneLifecycle {

    private static final int CHAINS = 19, LENGTH = 24, CUT = 7, CUT_AT = 10, DIM = 3;
    private static final float EVALUATE_BUDGET_US = 50f;
    /** Frames drawn once its look is warm, before the photograph; frames before it gives up. */
    private static final int SETTLE = 2, GIVE_UP = 600;
    private static final CgMaterialDefinition BALL = CgMaterialDefinition.of("harness:materials/prefab-ball",
            "harness:shader/prefab_test.shader").value("_Color", 0.9f, 0.6f, 0.2f, 1f).build();
    /** Each node's ball: radius 4, the size the gate's pixels were placed for. */
    private static final CgMesh BALL_MESH = CgProceduralMesh.revolve(CgProceduralMesh.sphere(4f, -4f, 4f)).divisions(32, 16).build();

    private final CgVfxSystem vfx = new CgVfxSystem();
    private float dx, dy, micros;
    private CgPrefab prefab;
    private HarnessTarget target;
    private int frames, warmFor;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
    }

    private CgPrefab prefab() {
        CgPrefab.Builder b = CgPrefab.builder("harness:prefab_gate").node("root").at(2f * dx, 2f * dy, 0f)
                .node("pivot").parent("root");
        for (int c = 0; c < CHAINS; c++) {
            for (int k = 0; k < LENGTH; k++) {
                b.node(name(c, k)).parent(k == 0 ? "pivot" : name(c, k - 1)).at(k == 0 ? c * dx : 0f, k == 0 ? 0f : dy, 0f)
                        .part(BALL, BALL_MESH);
                if (c == CUT && k == CUT_AT) b.opacity(0f);
                if (c == DIM && k == 0) b.opacity(0.2f);
            }
        }
        return b.build();
    }

    private static String name(int c, int k) {
        return "c" + c + "n" + k;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        if (frames++ == 0) {
            dx = w / (CHAINS + 4f);
            dy = h / (LENGTH + 4f);
            prefab = prefab();
            CgVfxPrefabEffect model = new CgVfxPrefabEffect(prefab, 0, 0, 0);   // drawn for ever: never stopped
            vfx.play(model).scalability(CgVfxScalability.HERO);   // in pixels: past any scalability's distance
            micros = time(model.pose());
            target = HarnessTarget.create(w, h, true);   // the world lights' pyramid reads its depth
        }
        boolean photograph = vfx.ready() && ++warmFor > SETTLE || frames > GIVE_UP;
        target.bind();
        try {
            target.clear(0f, 0f, 0f, 1f);
            Matrix4f view = new Matrix4f(), projection = CgCamera.orthographicMatrix(new Matrix4f(), 0, w, 0, h, -100, 100);
            vfx.update(frames / 60.0);
            vfx.submit();
            CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, w, h, target.external()).camera().set(0, 0, 0, view, projection);
            HarnessWorld.fireCallbacks();
            if (photograph) target.captureToFile(ctx.getOutputDir(), "vfx-prefab.png");
        } finally {
            target.unbind();
        }
        if (!photograph) return;
        report(ctx, DeviceErrors.checkAndLog("vfx-prefab"), prefab.nodes(), micros);
        running = false;
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return true; }

    /** The median microseconds of one {@code evaluate()}, warm. */
    private static float time(CgPrefabPose instance) {
        for (int i = 0; i < 20_000; i++) instance.evaluate();
        long[] runs = new long[31];
        for (int r = 0; r < runs.length; r++) {
            long start = System.nanoTime();
            for (int i = 0; i < 200; i++) instance.evaluate();
            runs[r] = System.nanoTime() - start;
        }
        Arrays.sort(runs);
        return runs[runs.length / 2] / 200f / 1000f;
    }

    private void report(HarnessContext ctx, boolean glErrors, int nodes, float micros) {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        if (glErrors) {
            System.out.println("[vfx-prefab] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        int validation = PlatformServiceHarness.validationErrors();
        if (validation > 0) {
            System.out.println("[vfx-prefab] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
            return;
        }
        if (micros > EVALUATE_BUDGET_US) {
            System.out.println("[vfx-prefab] FAIL on " + on + ": evaluating " + nodes + " nodes took " + micros + " us");
            return;
        }
        BufferedImage picture;
        try {
            picture = ImageIO.read(new File(ctx.getOutputDir(), "vfx-prefab.png"));
        } catch (IOException e) {
            System.out.println("[vfx-prefab] FAIL: the capture could not be read: " + e);
            return;
        }
        int full = red(picture, 1, 12), dim = red(picture, DIM, 0), underDim = red(picture, DIM, 12);
        int before = red(picture, CUT, CUT_AT - 1), cut = red(picture, CUT, CUT_AT), after = red(picture, CUT, LENGTH - 1);
        String fault = full < 150 ? "a whole chain's node reads " + full
                : dim <= 20 || dim >= full - 20 ? "the dimmed head reads " + dim + " beside " + full
                : underDim < full - 5 ? "a node under the dimmed head reads " + underDim + ": it inherited"
                : before < full - 5 ? "the node above the cut reads " + before
                : cut > 5 ? "the cut node reads " + cut
                : after < full - 5 ? "a node under the cut reads " + after + ": it inherited" : null;
        if (fault != null) {
            System.out.println("[vfx-prefab] FAIL on " + on + ": " + fault);
            return;
        }
        System.out.println("[vfx-prefab] PASS on " + on + ": " + nodes + " nodes evaluated in " + micros
                + " us; a node's opacity is its own, its children drawn at theirs");
    }

    /** The red of chain {@code c}'s node {@code k}, at its centre. */
    private int red(BufferedImage picture, int c, int k) {
        int x = Math.round((2f + c) * dx), y = Math.round((2f + k) * dy);
        return picture.getRGB(x, picture.getHeight() - 1 - y) >> 16 & 0xFF;
    }

    @Override
    public void dispose() {
        vfx.delete();
        if (target != null) target.delete();
    }
}
