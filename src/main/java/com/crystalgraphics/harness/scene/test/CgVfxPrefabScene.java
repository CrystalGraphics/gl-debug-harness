package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgProceduralMesh;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.prefab.CgVfxPrefab;
import com.crystalgraphics.vfx.prefab.CgVfxPrefabInstance;
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
 * {@link CgVfxPrefabInstance#evaluate()}, which must take a few microseconds.
 *
 * <p>Prints {@code [vfx-prefab] PASS} or {@code FAIL}; run it on {@code gl}, {@code mac41} and {@code vulkan}.</p>
 */
public class CgVfxPrefabScene implements HarnessSceneLifecycle {

    private static final int CHAINS = 19, LENGTH = 24, CUT = 7, CUT_AT = 10, DIM = 3;
    private static final float EVALUATE_BUDGET_US = 50f;
    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("harness:prefab");
    private static final CgMaterialDefinition BALL = CgMaterialDefinition.of("harness:materials/prefab-ball",
            "harness:shader/prefab_test.shader").value("_Color", 0.9f, 0.6f, 0.2f, 1f).build();
    /** Each node's ball: radius 4, the size the gate's pixels were placed for. */
    private static final CgMesh BALL_MESH = CgProceduralMesh.revolve(CgProceduralMesh.sphere(4f, -4f, 4f)).divisions(32, 16).build();

    private final CgVfxSystem vfx = new CgVfxSystem();
    private float dx, dy;

    /** One prefab drawn for ever. */
    private static final class Model extends CgVfxEffect<Model> {
        final CgVfxPrefabInstance instance;

        Model(CgVfxLook look, CgVfxPrefabInstance instance) {
            super(look, 0, 0, 0);
            this.instance = instance;
        }

        @Override
        protected void tick(float dt) {
        }

        @Override
        protected void submit(CgVfxFrame frame) {
            instance.submit(this, frame);
        }
    }

    @Override
    public void init(HarnessContext ctx) {
    }

    private CgVfxPrefab prefab() {
        CgVfxPrefab.Builder b = CgVfxPrefab.builder("harness:prefab_gate").node("root").at(2f * dx, 2f * dy, 0f)
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
        dx = w / (CHAINS + 4f);
        dy = h / (LENGTH + 4f);
        CgVfxPrefab prefab = prefab();
        CgVfxPrefabInstance instance = new CgVfxPrefabInstance(prefab);
        vfx.play(new Model(CgVfxLook.builder(SCHEMA).with(prefab.kit()).build(), instance));
        float micros = time(instance);

        HarnessFboHelper target = HarnessFboHelper.create(w, h, true);   // the world lights' pyramid reads its depth
        target.bind();
        try {
            target.clear(0f, 0f, 0f, 1f);
            for (int i = 1; i <= 4; i++) vfx.update(i / 60.0);
            vfx.submit(CgWorldRenderer.get());
            Matrix4f view = new Matrix4f(), projection = new Matrix4f().setOrtho(0, w, 0, h, -100, 100);
            for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
                stage.host().set(0f, w, h, target.getFboId()).view().set(0, 0, 0, view, projection);
                stage.fire();
            }
            target.captureToFile(ctx.getOutputDir(), "vfx-prefab.png");
        } finally {
            target.unbind();
            target.delete();
        }
        report(ctx, GlErrorChecker.checkAndLog("vfx-prefab"), prefab.nodes(), micros);
    }

    /** The median microseconds of one {@code evaluate()}, warm. */
    private static float time(CgVfxPrefabInstance instance) {
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
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
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
        if ("recording".equals(on)) {
            System.out.println("[vfx-prefab] PASS on recording: every command validated; nothing drawn to compare");
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
    }
}
