package com.crystalgraphics.harness.scene.test;

import java.util.Locale;
import java.util.Map;

import org.joml.Matrix4f;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessTarget;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.post.CgPostRenderer;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.post.settings.CgPostSettings.Control;
import com.crystalgraphics.render.post.settings.CgPostSettings.Region;
import com.crystalgraphics.render.post.aa.CgTemporalAA;
import com.crystalgraphics.render.post.upscale.CgAntiAliasing;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;

/**
 * Temporal AA against TSR, each of its qualities, at a 1440p output: the GPU time a frame of the anti-aliasing's own
 * passes and of the whole frame, printed. Temporal AA at 100%, then TSR at Low, Medium, High and Ultra at 100% and at the
 * screen percentage Unreal picks for a 1440p display (62.5%), each under a slowly panning camera over the posts and bars
 * {@code temporal-upscale} draws, after enough frames for the GPU's clocks to rise. A measurement, not a gate.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=temporal-upscale-timing --rhi=vulkan --seconds=60"
 * }</pre>
 */
public final class CgTemporalUpscaleTimingScene implements HarnessSceneLifecycle {

    private static final int W = 2560, H = 1440;
    /** Each run's frames before the timing starts (the GPU's clocks idle low), and timed. */
    private static final int WARMUP = 120, TIMED = 240;

    private CgMaterialInstance line;
    private final Matrix4f projection = new Matrix4f(), view = new Matrix4f();
    /** Two kits' looks on the bars and posts, for the look weights' cost (R14g); null draws them ungraded. */
    private CgPostSettings lookA, lookB;

    @Override
    public void init(HarnessContext ctx) {
        line = CgMaterial.load("assets/harness/shader/taa_lab_line.shader").instance();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgPostRenderer.install();
        CgCamera.perspectiveMatrix(projection, 40f, (float) W / H, 0.05f, 100f);
        CgView main = CgView.main();
        CgQuality quality = CgGraphicsSettings.ANTI_ALIASING.get();
        boolean tracing = CgTrace.isEnabled(CgGpuTrace.GPU);
        CgTrace.setEnabled(CgGpuTrace.GPU, true);
        CgTemporalAA.enable(main, true);
        try {
            if (!CgGpuTrace.isMeasuring()) {
                System.out.println("[temporal-upscale-timing] GPU timing is not measured on this device");
                return;
            }
            System.out.printf(Locale.ROOT, "[temporal-upscale-timing] %dx%d output, GPU ms a frame over %d frames%n", W, H, TIMED);
            time("TAA 100%", CgAntiAliasing.TAA, quality, false, "post.taa.");
            lookA = new CgPostSettings().tonemap().grade(Region.GLOBAL, Control.SATURATION, 1f, 1f, 1f, 0f);
            lookB = new CgPostSettings().tonemap().grade(Region.GLOBAL, Control.GAIN, 1.4f, 0.9f, 0.5f, 1f);
            time("TAA 2 looks", CgAntiAliasing.TAA, quality, false, "post.taa.");
            time("TSR LOW 2 looks", CgAntiAliasing.TSR, CgQuality.LOW, false, "post.tsr.");
            lookA = lookB = null;
            for (CgQuality q : new CgQuality[]{CgQuality.LOW, CgQuality.MEDIUM, CgQuality.HIGH, CgQuality.ULTRA}) {
                time("TSR " + q + " 100%", CgAntiAliasing.TSR, q, false, "post.tsr.");
                time("TSR " + q + " auto", CgAntiAliasing.TSR, q, true, "post.tsr.");
            }
        } finally {
            CgTemporalAA.enable(main, false);
            main.screenPercentageAuto(false).screenPercentage(100f).antiAliasing(CgAntiAliasing.TSR);
            CgGraphicsSettings.ANTI_ALIASING.set(quality);
            CgTrace.setEnabled(CgGpuTrace.GPU, tracing);
        }
    }

    /** One run: {@code method} at {@code quality}, at 100% or the auto percentage; its passes are the zones under {@code passes}. */
    private void time(String label, CgAntiAliasing method, CgQuality quality, boolean auto, String passes) {
        CgGraphicsSettings.ANTI_ALIASING.set(quality);
        CgView main = CgView.main().screenPercentageAuto(auto).antiAliasing(method);
        if (!auto) main.screenPercentage(100f);
        HarnessTarget target = HarnessTarget.create(W, H, true);
        CgScene scene = CgScene.client();
        CgMesh cube = CgMeshShapes.cube();
        try {
            for (int f = 0; f < WARMUP + TIMED; f++) {
                if (f == WARMUP) CgGpuTrace.resetTotals();
                target.bind();
                target.clear(0.02f, 0.02f, 0.03f, 1f);
                draw(scene, cube);
                view.identity().translate((float) Math.sin(f * 0.02) * 0.3f, -1.2f, 0f);
                CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, W, H, target.external()).camera().set(0, 0, 0, view, projection);
                HarnessWorld.fireCallbacks();
                CgFrameRing.endFrame();
                CgGpuTrace.collect();
                target.unbind();
            }
        } finally {
            target.delete();
        }
        double frameMs = 0, passMs = 0;
        StringBuilder each = new StringBuilder();
        for (Map.Entry<String, long[]> e : CgGpuTrace.totals().entrySet()) {
            double ms = e.getValue()[0] / 1e6 / TIMED;
            frameMs += ms;
            if (!e.getKey().startsWith(passes)) continue;
            passMs += ms;
            each.append(String.format(Locale.ROOT, " %s %.3f", e.getKey().substring(passes.length()), ms));
        }
        double looksMs = 0;
        for (Map.Entry<String, long[]> e : CgGpuTrace.totals().entrySet()) {
            if (e.getKey().startsWith("post.look.")) looksMs += e.getValue()[0] / 1e6 / TIMED;
        }
        System.out.printf(Locale.ROOT, "[temporal-upscale-timing] %-16s drawn %4dx%-4d  AA %.3f  looks %.3f  frame %.3f  |%s%n", label,
                main.width(), main.height(), passMs, looksMs, frameMs, each);
    }

    private void draw(CgScene scene, CgMesh cube) {
        scene.once().mesh(cube, line).at(0.6, 0.4, -5).transform(new Matrix4f().rotateZ((float) Math.toRadians(4)).scale(3f, 0.15f, 0.02f))
                .customData(0, 1f, 0.9f, 0.6f, 1f).grade(lookA).add();
        for (int i = 0; i < 12; i++) {
            scene.once().mesh(cube, line).at(-3 + i * 0.5, 1.2, -9).scale(0.04f, 1.6f, 0.04f).customData(0, 0.6f, 0.85f, 1f, 2f)
                    .grade(i % 2 == 0 ? lookA : lookB).add();
        }
    }

    @Override
    public void dispose() {
    }
}
