package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.SceneRegistry;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.gl.mesh.CgMeshBuilder;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * GL harness scene exercising {@link CgWorldRenderer} through both world stages, the harness standing in for the host.
 * Three behaviours in one run:
 *
 * <ol>
 *   <li><b>GROUP A — instancing</b>: 50 unit cubes in a 10×5 grid (y=0), one material and mesh: one instanced
 *       draw.</li>
 *   <li><b>GROUP B — material break</b>: 20 cubes at y=1.5, submitted interleaved (A-B-A-B…), sorted by material
 *       into two instanced draws of 10.</li>
 *   <li><b>GROUP C — transparent depth order</b>: 8 cubes at y=2 along z=−1…−8, submitted front to back and drawn
 *       back to front.</li>
 * </ol>
 *
 * <h3>Visual confirmation — what to look for</h3>
 * <ol>
 *   <li>Grid of 50 cubes at y=0, each with a distinct HSV colour derived from its index.</li>
 *   <li>10 red cubes (tintMaterialA) + 10 blue cubes (tintMaterialB) arranged in a row
 *       at y=1.5, alternating in X.</li>
 *   <li>8 semi-transparent cubes at y=2 stacked along Z (z=−1…−8), visible with correct
 *       alpha blending — back cubes darker, front cube on top, no z-fighting or reversed draw order.</li>
 *   <li>No GL errors logged during any frame.</li>
 *   <li>Camera can orbit freely (uses3DCamera = true).</li>
 * </ol>
 *
 * <p>Register in {@link SceneRegistry}
 * under scene id {@code "forward-renderer"}.</p>
 */
public class CgForwardRendererScene implements InteractiveSceneLifecycle {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.ForwardRendererScene");

    // ── Scene geometry counts ─────────────────────────────────────────────────

    /** GROUP A: 10 columns × 5 rows = 50 opaque cubes, all same material+mesh. */
    private static final int GROUP_A_COUNT = 50;
    /** GROUP B: 10 with tintMaterialA + 10 with tintMaterialB, submitted interleaved. */
    private static final int GROUP_B_EACH  = 10;
    /** GROUP C: 8 transparent cubes along Z, z = -1 … -8. */
    private static final int GROUP_C_COUNT = 8;

    // ── Resources ─────────────────────────────────────────────────────────────

    /** Shared unit cube mesh for all three groups. */
    private CgMesh unitCubeMesh;

    /**
     * GROUP A base material — {@code dual_path_test.shader}, default white {@code _Color}.
     * All 50 GROUP A cubes share this exact material reference so the forward renderer
     * can merge them into one {@code drawInstanced(50)}.
     */
    private CgMaterial solidMaterial;

    /**
     * GROUP B material A — red tint ({@code _Color = (1, 0.3, 0.3, 1)}).
     * Independent instance created via {@code newInstance()} so it has its own
     * property store without contaminating the cached {@code dual_path_test.shader}.
     */
    private CgMaterial tintMaterialA;

    /**
     * GROUP B material B — blue tint ({@code _Color = (0.3, 0.3, 1, 1)}).
     */
    private CgMaterial tintMaterialB;

    /**
     * GROUP C transparent material — {@code forward_transparent_test.shader},
     * {@code Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA}, {@code DepthWrite OFF}.
     */
    private CgMaterial transparentMaterial;

    // ── State ─────────────────────────────────────────────────────────────────

    private boolean running         = true;
    private boolean loggedFirstFrame = false;


    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void init(HarnessContext ctx) {
        // Shared unit cube — SPATIAL format matches cg_env.glsl attribute layout
        unitCubeMesh = CgMeshBuilder.unitCube(CgVertexFormat.SPATIAL).upload();

        // GROUP A — shared opaque material; all 50 commands reference the same instance
        // so the sort leaves all 50 adjacent and they instance into one draw
        solidMaterial = CgMaterial.load("assets/harness/shader/dual_path_test.shader");

        // GROUP B — two independent instances from the same .shader asset so they each
        // have a distinct Java identity; auto-instancing will NOT merge A with B
        tintMaterialA = CgMaterial.newInstance("assets/harness/shader/dual_path_test.shader");
        tintMaterialA.applyProperties(b -> b.vec4("_Color", 1f, 0.3f, 0.3f, 1f));

        tintMaterialB = CgMaterial.newInstance("assets/harness/shader/dual_path_test.shader");
        tintMaterialB.applyProperties(b -> b.vec4("_Color", 0.3f, 0.3f, 1f, 1f));

        // GROUP C — transparent material with alpha blending and ZWrite OFF
        transparentMaterial = CgMaterial.load(
                "assets/harness/shader/forward_transparent_test.shader");

    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();

        // ── GROUP A — auto-instancing stress ─────────────────────────────────
        // 50 cubes in a 10-column × 5-row grid at y=0, all solidMaterial + unitCubeMesh: one instanced draw.
        for (int idx = 0; idx < GROUP_A_COUNT; idx++) {
            float[] rgb = hsvToRgb(idx / (float) GROUP_A_COUNT, 1f, 1f);
            world.draw(unitCubeMesh, solidMaterial)
                    .at(idx % 10 - 4.5f, 0f, idx / 10 - 2.0f + idx * 0.1f)
                    .custom(0, rgb[0], rgb[1], rgb[2], 1f)
                    .queue(CgRenderQueue.GEOMETRY)
                    .submit();
        }

        // ── GROUP B — material break test ────────────────────────────────────
        // 20 cubes at y=1.5, submitted interleaved A-B-A-B; the sort groups them by material: two draws of 10.
        for (int i = 0; i < GROUP_B_EACH; i++) {
            float x = (i - 4.5f) * 1.1f;
            world.draw(unitCubeMesh, tintMaterialA).at(x, 1.5f, -0.5f).custom(0, 1f, 1f, 1f, 1f)
                    .queue(CgRenderQueue.GEOMETRY).submit();
            world.draw(unitCubeMesh, tintMaterialB).at(x, 1.5f, 0.5f).custom(0, 1f, 1f, 1f, 1f)
                    .queue(CgRenderQueue.GEOMETRY).submit();
        }

        // ── GROUP C — transparent depth order ────────────────────────────────
        // 8 cubes at y=2, z = -1 … -8, submitted front to back; drawn back to front. Bluer is further.
        for (int i = 0; i < GROUP_C_COUNT; i++) {
            float depth = (i + 1f) / GROUP_C_COUNT;
            world.draw(unitCubeMesh, transparentMaterial).at(0f, 2f, -(i + 1f))
                    .custom(0, 1f - depth, 0.5f, depth, 0.5f)
                    .queue(CgRenderQueue.TRANSPARENT)
                    .submit();
        }

        HarnessWorld.fire(ctx.getScreenWidth(), ctx.getScreenHeight(), ctx.getCamera3D().getViewMatrix(),
                ctx.getProjection());

        // ── 6. First-frame diagnostics ────────────────────────────────────────
        if (!loggedFirstFrame) {
            loggedFirstFrame = true;
            LOG.info("[ForwardRendererScene] GROUP A: submitted {} draws with solidMaterial " +
                    "— expect one instanced draw of 50", GROUP_A_COUNT);
            LOG.info("[ForwardRendererScene] GROUP B: submitted {} interleaved A/B commands " +
                    "— expect 2 × drawInstanced(10) after sort", GROUP_B_EACH * 2);
            LOG.info("[ForwardRendererScene] GROUP C: submitted {} transparent cubes front-to-back " +
                    "— expect sort to reverse to back-to-front (z=-8 first)", GROUP_C_COUNT);
        }
    }

    // ── Dispose ───────────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        if (unitCubeMesh    != null) unitCubeMesh.delete();
        // solidMaterial is owned by CgMaterialRegistry (loaded via load()) — do NOT delete directly.
        // tintMaterialA/B and transparentMaterial: load() variants are registry-owned too.
        // Per-instance materials created via newInstance() can technically be deleted here,
        // but the registry cleanup in CgGraphicsLifecycle handles it safely either way.
        tintMaterialA    = null;
        tintMaterialB    = null;
        transparentMaterial = null;
        solidMaterial    = null;
        unitCubeMesh     = null;
    }

    @Override public boolean isRunning()              { return running; }
    @Override public boolean uses3DCamera()           { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Converts HSV (hue, saturation, value) to an RGB float triple.
     *
     * @param h hue in [0, 1)
     * @param s saturation in [0, 1]
     * @param v value in [0, 1]
     * @return float[3] with r, g, b in [0, 1]
     */
    private static float[] hsvToRgb(float h, float s, float v) {
        int   hi = (int)(h * 6f) % 6;
        float f  = h * 6f - (int)(h * 6f);
        float p  = v * (1f - s);
        float q  = v * (1f - f * s);
        float t  = v * (1f - (1f - f) * s);
        switch (hi) {
            case 0:  return new float[]{ v, t, p };
            case 1:  return new float[]{ q, v, p };
            case 2:  return new float[]{ p, v, t };
            case 3:  return new float[]{ p, q, v };
            case 4:  return new float[]{ t, p, v };
            default: return new float[]{ v, p, q };
        }
    }
}
