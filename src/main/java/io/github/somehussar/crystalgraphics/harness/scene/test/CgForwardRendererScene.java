package io.github.somehussar.crystalgraphics.harness.scene.test;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.material.CgMaterial;
import io.github.somehussar.crystalgraphics.api.material.CgRenderQueue;
import io.github.somehussar.crystalgraphics.api.render.CgFrameData;
import io.github.somehussar.crystalgraphics.api.render.CgRenderCommand;
import io.github.somehussar.crystalgraphics.api.render.CgRenderPipeline;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMesh;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMeshBuilder;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.tool.GlErrorChecker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

/**
 * GL harness scene exercising the complete Phase 1 CrystalGraphics forward render pipeline.
 *
 * <p>All geometry is submitted through {@link CgRenderPipeline} — no direct
 * {@code material.bind()} / {@code material.drawChain()} calls. The scene validates
 * all three major pipeline behaviours in one run:</p>
 *
 * <ol>
 *   <li><b>GROUP A — auto-instancing stress</b>: 50 unit cubes in a 10×5 grid (y=0)
 *       all using the same material and mesh. The {@link io.github.somehussar.crystalgraphics.render.pipeline.CgForwardRenderer}
 *       must collapse these into a single {@code drawInstanced(50)} call.</li>
 *   <li><b>GROUP B — material break test</b>: 20 cubes at y=1.5, submitted interleaved
 *       (A-B-A-B…) but sorted by material identity. Expect 2 instanced draw calls
 *       of 10 each after sort groups them: 10×{@code tintMaterialA} then 10×{@code tintMaterialB}.</li>
 *   <li><b>GROUP C — transparent depth order</b>: 8 cubes at y=2 placed at z=−1…−8.
 *       Submitted in any order; sort must produce back-to-front (z=−8 first, z=−1 last).</li>
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
 * <p>Register in {@link io.github.somehussar.crystalgraphics.harness.SceneRegistry}
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

    /** Singleton orchestrator owning the sort, prepass, and all pass renderers. */
    private CgRenderPipeline renderFrame;

    // ── State ─────────────────────────────────────────────────────────────────

    private boolean running         = true;
    private boolean loggedFirstFrame = false;

    // Scratch matrix — reused every frame to avoid per-frame allocation
    private final Matrix4f scratchModel = new Matrix4f();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void init(HarnessContext ctx) {
        // Shared unit cube — SPATIAL format matches cg_env.glsl attribute layout
        unitCubeMesh = CgMeshBuilder.unitCube(CgVertexFormat.SPATIAL).upload();

        // GROUP A — shared opaque material; all 50 commands reference the same instance
        // so CgForwardRenderer.canMerge() returns true for all 50 (identity comparison)
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

        // Singleton pipeline orchestrator — created lazily on first access
        renderFrame = CgRenderPipeline.getInstance();
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {

        // ── 1. Populate CgFrameData from harness camera ─────────────────────
        // CgRenderPipeline.execute() reads frameData internally before uploading the frame UBO.
        CgFrameData fd = renderFrame.getFrameData();

        Matrix4f view = ctx.getCamera3D().getViewMatrix();
        Matrix4f proj = ctx.getProjection();

        fd.viewMatrix.set(view);
        fd.projMatrix.set(proj);
        fd.timeSecs  = (float) frame.getElapsedTime();
        fd.viewportW = ctx.getScreenWidth();
        fd.viewportH = ctx.getScreenHeight();
        // Derives cameraForward and cameraPos from the updated viewMatrix
        fd.deriveFromViewMatrix();

        // ── 2. GROUP A — auto-instancing stress ───────────────────────────────
        // 50 cubes in a 10-column × 5-row grid at y=0. All share solidMaterial +
        // unitCubeMesh. CgForwardRenderer must emit exactly 1 drawInstanced(50).
        for (int idx = 0; idx < 50; idx++) {
            int col = idx % 10;
            int row = idx / 10;
            // x: -4.5 … +4.5, z: -2 … +2 (step 1 in each axis)
            float x = (col - 4.5f)+ idx*0.0f;
            float z = (row - 2.0f) + idx*0.1f;
            float y = 0f;

            CgRenderCommand cmd = renderFrame.acquireCommand();
            cmd.mesh     = unitCubeMesh;
            cmd.material = solidMaterial;
            cmd.queueSlot = CgRenderQueue.TRANSPARENT;

            cmd.modelMatrix.identity().translation(x, y, z);

            // Per-cube HSV color encoded into custom0 — visible via CG_OBJECT_CUSTOM0 in the shader
            float hue = idx / (float) GROUP_A_COUNT;
            float[] rgb = hsvToRgb(hue, 1f, 1f);
            cmd.custom0.set(rgb[0], rgb[1], rgb[2], 1f);

            setUnitCubeAabb(cmd,x, y, z);
            renderFrame.submit(cmd);
        }

        // ── 3. GROUP B — material break test ─────────────────────────────────
        // 20 cubes at y=1.5, submitted interleaved (A-B-A-B…).
        // After sort, commands are grouped by material identity (sort key encodes
        // material hash bits), so expect 2 drawInstanced(10) calls.
        for (int i = 0; i < GROUP_B_EACH; i++) {
            float xA = (i - 4.5f) * 1.1f;  // tintMaterialA row
            float xB = (i - 4.5f) * 1.1f;  // tintMaterialB row (same X, different material/z)

            // Submit A (interleaved first)
            CgRenderCommand cmdA = renderFrame.acquireCommand();
            cmdA.mesh      = unitCubeMesh;
            cmdA.material  = tintMaterialA;
            cmdA.queueSlot = CgRenderQueue.GEOMETRY;
            cmdA.modelMatrix.identity().translation(xA, 1.5f, -0.5f);
            cmdA.custom0.set(1f, 1f, 1f, 1f);
            setUnitCubeAabb(cmdA, xA, 1.5f, -0.5f);
//            renderFrame.submit(cmdA);

            // Submit B (interleaved second — different identity → sort will group separately)
            CgRenderCommand cmdB = renderFrame.acquireCommand();
            cmdB.mesh      = unitCubeMesh;
            cmdB.material  = tintMaterialB;
            cmdB.queueSlot = CgRenderQueue.GEOMETRY;
            cmdB.modelMatrix.identity().translation(xB, 1.5f, 0.5f);
            cmdB.custom0.set(1f, 1f, 1f, 1f);
            setUnitCubeAabb(cmdB, xB, 1.5f, 0.5f);
//            renderFrame.submit(cmdB);
        }

        // ── 4. GROUP C — transparent depth order ─────────────────────────────
        // 8 transparent cubes at y=2, z = -1 … -8, submitted front-to-back.
        // CgRenderCommandQueue.sort() must reorder these back-to-front for correct blending.
        // custom0 encodes a depth-based tint (bluer = further) so ordering is visually verifiable.
        for (int i = 0; i < GROUP_C_COUNT; i++) {
            float z = -(i + 1f);   // z = -1, -2, -3, … -8 (submitted front-to-back)
            float depth = (i + 1f) / GROUP_C_COUNT;  // 0..1, higher = further

            CgRenderCommand cmd = renderFrame.acquireCommand();
            cmd.mesh      = unitCubeMesh;
            cmd.material  = transparentMaterial;
            cmd.queueSlot = CgRenderQueue.TRANSPARENT;

            cmd.modelMatrix.identity().translation(0f, 2f, z);
            // Tint: closer cubes are warmer (red), further cubes are cooler (blue)
            cmd.custom0.set(1f - depth, 0.5f, depth, 0.5f);  // alpha 0.5 for blending

            setUnitCubeAabb(cmd, 0f, 2f, z);
            renderFrame.submit(cmd);
        }

        // ── 5. Execute the full render frame ──────────────────────────────────
        // Internally: sort() → updateFrameUniforms() → beginFrame() → depth prepass
        // → CgForwardRenderer (opaque) → CgTransparentRenderer (transparent).
        // GL state is saved before and restored after the entire execute() call.
        renderFrame.execute(0.0f);

        GlErrorChecker.assertNoGlError("CgForwardRendererScene.frame");

        // ── 6. First-frame diagnostics ────────────────────────────────────────
        if (!loggedFirstFrame) {
            loggedFirstFrame = true;
            CgCapabilities caps = CgCapabilities.detect();
            LOG.info("[ForwardRendererScene] GL caps: drawInstanced={} vertexAttribDivisor={}",
                    caps.isDrawInstancedSupported(), caps.isVertexAttribDivisorSupported());
            LOG.info("[ForwardRendererScene] GROUP A: submitted {} commands with solidMaterial " +
                    "— expect CgForwardRenderer to emit drawInstanced(50)", GROUP_A_COUNT);
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
     * Sets the world-space AABB on {@code cmd} for a unit cube centred at (cx, cy, cz).
     * A unit cube built by {@link CgMeshBuilder#unitCube} has half-extents of 0.5 on all axes.
     *
     * @param cmd the command to populate
     * @param cx  cube centre X
     * @param cy  cube centre Y
     * @param cz  cube centre Z
     */
    private static void setUnitCubeAabb(CgRenderCommand cmd,
                                        float cx, float cy, float cz) {
        cmd.worldAabb[0] = cx - 0.5f;  // minX
        cmd.worldAabb[1] = cy - 0.5f;  // minY
        cmd.worldAabb[2] = cz - 0.5f;  // minZ
        cmd.worldAabb[3] = cx + 0.5f;  // maxX
        cmd.worldAabb[4] = cy + 0.5f;  // maxY
        cmd.worldAabb[5] = cz + 0.5f;  // maxZ
    }

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
