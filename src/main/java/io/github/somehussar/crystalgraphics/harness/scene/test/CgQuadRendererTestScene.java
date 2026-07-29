package io.github.somehussar.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.render.CgFrameData;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.tool.GlErrorChecker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

/**
 * Interactive visual test for {@link CgQuadRenderer} — the new SSBO/TBO-backed general
 * quad-instancing renderer (see
 * {@code CrystalGraphics/docs_research/CGTEXTRENDERER_INSTANCING_FOUNDATIONS.md} Decision 5).
 *
 * <p>Renders two groups of quads, submitted into the <strong>same</strong> begin/flush/end
 * window so both land in a single instanced draw call, both via the fluent
 * {@link CgQuadRenderer#quad()} builder:</p>
 * <ol>
 *   <li><b>Grid</b> — a large, animated, untransformed grid submitted with no
 *       {@code .pose(...)} call. Exercises high instance counts and the flat/no-transform
 *       case.</li>
 *   <li><b>Spinners</b> — a row of quads rotating around their own centers, submitted
 *       with {@code .pose(scratchPose)} — the fluent builder bakes the CPU-side 3-vector
 *       transform (origin/right/up via {@code transformPosition}/{@code transformDirection})
 *       internally. Exercises the "differently-transformed quads still merge into one draw
 *       call" property that motivated the whole instancing migration.</li>
 * </ol>
 *
 * <p>Both groups draw with one {@code renderer.flush()} per frame (material bind/unbind is
 * handled by {@code renderer.useMaterial(material)}, called once in {@link #init}) — visually,
 * and via the logged instance counts, this confirms the grid and spinners are one draw call,
 * not two.</p>
 */
public class CgQuadRendererTestScene implements InteractiveSceneLifecycle {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.CgQuadRendererTest");

    private static final int GRID_COLS = 16;
    private static final int GRID_ROWS = 10;
    private static final float GRID_QUAD_SIZE = 32f;
    private static final float GRID_SPACING = 44f;
    private static final float GRID_ORIGIN_X = 20f;
    private static final float GRID_ORIGIN_Y = 20f;

    private static final int SPINNER_COUNT = 6;
    private static final float SPINNER_SIZE = 60f;

    private CgMaterial material;
    private CgQuadRenderer renderer;

    /** Reused across every spinner/frame — mutated in place via translation()/rotateZ()/translate(), never reallocated. */
    private final Matrix4f scratchPose = new Matrix4f();

    private final boolean running = true;
    private boolean reportedOnce = false;

    @Override
    public void init(HarnessContext ctx) {
        renderer = CgQuadRenderer.create();

        material = CgMaterial.load("assets/harness/shader/quad_renderer_test.shader");
        // Attach BEFORE applyProperties(): CgMaterial.applyProperties() eagerly triggers
        // recompile() on its first call (CgMaterial.java: "if (propStore == null...) recompile()"),
        // and QUAD_DATA must already be attached by then or that first compile fails with an
        // undefined-symbol error (it then "self-heals" on the next real bind(), but there's no
        // reason to trigger the pointless failed compile at all — just attach first).
        // The shader's "Properties { _MainTex (...) = "white" }" default string is NOT
        // automatically resolved to a real bound texture (CgMaterialProperty.samplerTexture
        // stays null until explicitly set) — every real 2D consumer (e.g. CgUiPaintContext)
        // binds a real texture explicitly, so we do the same here rather than rely on a
        // shader-declared default that the property system doesn't currently apply.
        material.applyProperties(b -> b.sampler("_MainTex", 0, CgFallbackTextures.WHITE_1x1));
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getViewport().getWidth();
        int h = ctx.getViewport().getHeight();
        float t = (float) frame.getElapsedTime();

        // Pure 2D ortho projection via the frame UBO — no per-instance object-buffer
        // record needed since quad_renderer_test.shader never references
        // CG_OBJECT_TO_WORLD / CG_MATRIX_MVP (same pattern as crystalgui:gui_quad.shader).
        CgRenderPipeline pipeline = CgRenderPipeline.getInstance();
        CgFrameData fd = pipeline.getFrameData();
        fd.viewMatrix.identity();
        fd.projMatrix.identity().ortho(0, w, h, 0, -1, 1);
        fd.viewportW = w;
        fd.viewportH = h;
        pipeline.prepareFrame();

        // Must be called every frame, not just once at setup: it (re)binds material on every
        // call regardless of whether it's the same instance as last time, since other harness
        // rendering code (floor/HUD/pause overlay) rebinds its own shaders between our frames.
        renderer.begin();
        renderer.useMaterial(material);

        // ── Grid: untransformed quads via the fluent quad() builder, no .pose(...) ──
        int gridCount = 0;
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                float x = GRID_ORIGIN_X + col * GRID_SPACING;
                float y = GRID_ORIGIN_Y + row * GRID_SPACING;
                float phase = (col + row * GRID_COLS) * 0.05f + t * 0.3f;
                renderer.quad().at(x, y).size(GRID_QUAD_SIZE, GRID_QUAD_SIZE).color(hsvToArgb(phase)).submit();
                gridCount++;
            }
        }

        // ── Spinners: transformed quads via the fluent quad() builder's .pose(...) ──
        float spinnerCenterY = GRID_ORIGIN_Y + GRID_ROWS * GRID_SPACING + 60f;
        for (int i = 0; i < SPINNER_COUNT; i++) {
            float cx = GRID_ORIGIN_X + 60f + i * 120f;
            float angle = t * (0.6f + i * 0.15f) + i;

            // scratchPose is reused every iteration — translation()/rotateZ()/translate()
            // all mutate it in place and return it, no per-spinner/per-frame allocation.
            scratchPose.translation(cx, spinnerCenterY, 0f)
                    .rotateZ(angle)
                    .translate(-SPINNER_SIZE * 0.5f, -SPINNER_SIZE * 0.5f, 0f);

            int argb = hsvToArgb(i / (float) SPINNER_COUNT + t * 0.2f);
            renderer.quad().at(0f, 0f).size(SPINNER_SIZE, SPINNER_SIZE).pose(scratchPose).color(argb).submit();
        }

        renderer.flush();
        renderer.end();

        boolean hadErrors = GlErrorChecker.checkAndLog("CgQuadRenderer-test");
        if (!reportedOnce) {
            reportedOnce = true;
            LOG.info("CgQuadRenderer test scene: {} grid instances + {} spinner instances in one flush(), glError={}",
                    gridCount, SPINNER_COUNT, hadErrors);
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

    /** Deterministic hue-cycle → packed ARGB helper, purely for visually distinct quads. */
    private static int hsvToArgb(float huePhase) {
        float hue = (huePhase % 1f + 1f) % 1f;
        float r = clamp01(Math.abs(hue * 6f - 3f) - 1f);
        float g = clamp01(2f - Math.abs(hue * 6f - 2f));
        float b = clamp01(2f - Math.abs(hue * 6f - 4f));
        int ir = (int) (r * 255f);
        int ig = (int) (g * 255f);
        int ib = (int) (b * 255f);
        return (0xFF << 24) | (ir << 16) | (ig << 8) | ib;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
