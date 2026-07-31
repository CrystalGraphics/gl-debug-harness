package io.github.somehussar.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.render.CgFrameData;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.gl.render.CgCurveRenderer;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.tool.GlErrorChecker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

/**
 * Interactive visual test for {@link CgCurveRenderer} — the instanced quadratic-Bézier stroke
 * renderer, and the one true engine gap that the node-graph view (P6.2) is blocked on.
 *
 * <p>Every row below lands in the <strong>same</strong> begin/flush/end window, so the whole scene
 * is one instanced draw call. The rows are chosen to cover the things that can each be
 * independently wrong:</p>
 * <ol>
 *   <li><b>Straight lines</b> — the degenerate case. {@code line()} builds a quadratic whose control
 *       point is the midpoint, which makes {@code A - 2B + C} exactly zero and divides by zero in the
 *       unguarded Bézier SDF. If {@code sdf_bezier}'s segment fallback ever regresses, this row goes
 *       blank or garbage while every curved row still looks perfect.</li>
 *   <li><b>Curves</b> — plain quadratics at varying control offsets.</li>
 *   <li><b>Taper</b> — {@code width(start, end)}, thick to hairline.</li>
 *   <li><b>Gradient</b> — {@code colors(a, b)} along the curve, the node-graph wire idiom.</li>
 *   <li><b>Caps</b> — butt / round / square, drawn thick so the difference is actually visible.</li>
 *   <li><b>Cubics</b> — {@code cubic(...)}, split CPU-side into 1–4 quadratics by one call. Taper and
 *       gradient must stay continuous <em>across</em> those internal segment boundaries; banding here
 *       means the per-segment ramp slicing is wrong.</li>
 *   <li><b>Animated pose</b> — a rotating, scaling fan proving widths track the pose's scale rather
 *       than staying fixed while the geometry grows.</li>
 * </ol>
 */
public class CgCurveRendererTestScene implements InteractiveSceneLifecycle {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.CgCurveRendererTest");

    private static final int FAN_SPOKES = 12;

    private CgMaterial material;
    private CgCurveRenderer renderer;

    /** Reused every frame — mutated in place, never reallocated. */
    private final Matrix4f scratchPose = new Matrix4f();

    private final boolean running = true;
    private boolean reportedOnce = false;

    @Override
    public void init(HarnessContext ctx) {
        renderer = CgCurveRenderer.create();
        // The shipped reference material — no Properties to bind, since a stroke's colour, width and
        // softness are all per-instance data rather than material state.
        material = CgMaterial.load("crystalgraphics:shaders/curve.shader");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getViewport().getWidth();
        int h = ctx.getViewport().getHeight();
        float t = (float) frame.getElapsedTime();

        // Pure 2D ortho via the frame UBO — curve.shader reads cg_ProjMatrix and never touches
        // CG_OBJECT_TO_WORLD, so no per-instance object-buffer record is needed.
        CgRenderPipeline pipeline = CgRenderPipeline.getInstance();
        CgFrameData fd = pipeline.getFrameData();
        fd.viewMatrix.identity();
        fd.projMatrix.identity().ortho(0, w, h, 0, -1, 1);
        fd.viewportW = w;
        fd.viewportH = h;
        pipeline.prepareFrame();

        renderer.begin();
        renderer.useMaterial(material);

        int count = 0;

        // ── Row 1: straight lines — the divide-by-zero case ──────────────────
        for (int i = 0; i < 6; i++) {
            float x = 40f + i * 130f;
            renderer.curve().line(x, 40f, x + 100f, 90f)
                    .width(1f + i * 1.5f)
                    .color(0xFFE0E0E0)
                    .submit();
            count++;
        }

        // ── Row 2: quadratic curves ──────────────────────────────────────────
        for (int i = 0; i < 6; i++) {
            float x = 40f + i * 130f;
            float bow = 30f + i * 18f;
            renderer.curve().from(x, 170f).via(x + 50f, 170f - bow).to(x + 100f, 170f)
                    .width(3f)
                    .color(hsvToArgb(i / 6f))
                    .submit();
            count++;
        }

        // ── Row 3: taper ─────────────────────────────────────────────────────
        for (int i = 0; i < 4; i++) {
            float x = 40f + i * 200f;
            renderer.curve().from(x, 250f).via(x + 80f, 210f).to(x + 160f, 250f)
                    .width(9f, 0.75f)
                    .color(0xFF6CC4FF)
                    .submit();
            count++;
        }

        // ── Row 4: gradient — the node-graph wire idiom ──────────────────────
        for (int i = 0; i < 4; i++) {
            float x = 40f + i * 200f;
            renderer.curve().from(x, 330f).via(x + 80f, 290f).to(x + 160f, 330f)
                    .width(5f)
                    .colors(0xFFFF4D6D, 0xFF4DFFC3)
                    .submit();
            count++;
        }

        // ── Row 5: caps, thick enough that the difference reads ──────────────
        // Each bar gets hairline ticks at its NOMINAL endpoints. Without them this row is not
        // actually diagnostic: three bars that all look rounded and three bars that are all correct
        // are hard to tell apart by eye, and the first cap implementation shipped broken for exactly
        // that reason. Against the ticks it is unambiguous — butt stops ON the tick, round bulges a
        // half-disc past it, square overhangs it by the same amount with square corners.
        int[] caps = { CgCurveRenderer.CAP_BUTT, CgCurveRenderer.CAP_ROUND, CgCurveRenderer.CAP_SQUARE };
        for (int i = 0; i < caps.length; i++) {
            float x = 60f + i * 240f;
            renderer.curve().line(x, 410f, x + 160f, 410f)
                    .width(14f)
                    .cap(caps[i])
                    .color(0xFFFFC24D)
                    .submit();
            count++;

            for (int e = 0; e < 2; e++) {
                float tx = x + e * 160f;
                renderer.curve().line(tx, 386f, tx, 434f)
                        .width(0.5f)
                        .cap(CgCurveRenderer.CAP_BUTT)
                        .color(0xFF7A7A7A)
                        .submit();
                count++;
            }
        }

        // ── Row 5b: the same three caps at 45°, thick ────────────────────────
        // Axis-aligned strokes cannot detect a bounding-box that is too small for a square cap:
        // the cap's corner reaches halfWidth*sqrt(2) along one axis only when the stroke is
        // diagonal, and is exactly halfWidth when it is horizontal or vertical. A horizontal-only
        // caps row therefore passes with padding that clips every diagonal cap in the engine.
        // Spacing has to clear the FULL diagonal footprint, not just the stroke's length: at 45° a
        // half-width of 11 adds ~8px of X from the tangent extension and another ~8 from the normal,
        // so three of these at 45px apart merge into one zigzag and the individual caps — the entire
        // point of the group — become impossible to read.
        for (int i = 0; i < caps.length; i++) {
            float x = 750f + i * 68f;
            renderer.curve().line(x, 390f, x + 34f, 424f)
                    .width(11f)
                    .cap(caps[i])
                    .color(0xFFFFC24D)
                    .submit();
            count++;
        }

        // ── Row 6: cubics — one call, N instances, continuous across the split ──
        for (int i = 0; i < 3; i++) {
            float x = 50f + i * 300f;
            float wobble = 60f + 40f * (float) Math.sin(t * 0.7f + i);
            renderer.curve()
                    .cubic(x, 500f,
                            x + 70f, 500f - wobble,
                            x + 150f, 500f + wobble,
                            x + 220f, 500f)
                    .width(7f, 1.5f)
                    .colors(0xFFB36CFF, 0xFF6CFFB3)
                    .submit();
            count++;
        }

        // ── Row 7: animated pose — widths must scale with the geometry ───────
        float fanCx = w * 0.5f;
        float fanCy = 610f;
        float scale = 0.75f + 0.35f * (float) Math.sin(t * 0.8f);
        scratchPose.translation(fanCx, fanCy, 0f).rotateZ(t * 0.4f).scale(scale);
        for (int i = 0; i < FAN_SPOKES; i++) {
            double a = (i / (double) FAN_SPOKES) * Math.PI * 2.0;
            float ex = (float) Math.cos(a) * 90f;
            float ey = (float) Math.sin(a) * 90f;
            renderer.curve().from(0f, 0f).via(ex * 0.5f - ey * 0.25f, ey * 0.5f + ex * 0.25f).to(ex, ey)
                    .width(3f)
                    .colors(0xFFFFFFFF, hsvToArgb(i / (float) FAN_SPOKES + t * 0.1f))
                    .pose(scratchPose)
                    .submit();
            count++;
        }

        renderer.flush();
        renderer.end();

        boolean hadErrors = GlErrorChecker.checkAndLog("CgCurveRenderer-test");
        if (!reportedOnce) {
            reportedOnce = true;
            LOG.info("CgCurveRenderer test scene: {} submit() calls in one flush() "
                    + "(cubics expand to more instances than that), glError={}", count, hadErrors);
        }
    }

    @Override
    public void dispose() {
        if (material != null) material.delete();
        if (renderer != null) renderer.delete();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return false; }

    /** Deterministic hue cycle → packed ARGB, purely for visually distinct strokes. */
    private static int hsvToArgb(float huePhase) {
        float hue = (huePhase % 1f + 1f) % 1f;
        float r = clamp01(Math.abs(hue * 6f - 3f) - 1f);
        float g = clamp01(2f - Math.abs(hue * 6f - 2f));
        float b = clamp01(2f - Math.abs(hue * 6f - 4f));
        return (0xFF << 24) | ((int) (r * 255f) << 16) | ((int) (g * 255f) << 8) | (int) (b * 255f);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
