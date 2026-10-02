package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshWriter;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.vfx.render.CgVfxTrail;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

/**
 * Trails ({@link CgVfxTrail}) swirling round the origin, every one rewritten every frame: the measure of a mesh drawn
 * once per frame it changes ({@code CgMesh.Usage.FRAME}). Scene id {@code vfx-trails}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-trails"
 * // how many trails, and points in each (the defaults):
 * ... -Dcrystalgraphics.harness.trails.count=64 -Dcrystalgraphics.harness.trails.points=64
 * // every trail in one mesh, which separates the bytes from the cost of each mesh:
 * ... -Dcrystalgraphics.harness.trails.merged=true
 * // a report: profile-harness-300f/report.txt
 * ... -Dcrystalgraphics.harness.profile=300 -Dcrystalgraphics.harness.profile.warmup=60 -Dcrystalgraphics.trace.channels=crystalgraphics,gpu
 * }</pre>
 *
 * <p>A trail pushes a point every frame, so its length on screen follows the frame rate; run it with
 * {@code -Dcrystalgraphics.harness.fixedDelta} for the same picture every time.</p>
 */
public final class CgVfxTrailsScene implements InteractiveSceneLifecycle {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.VfxTrails");

    private static final int COUNT = Integer.getInteger("crystalgraphics.harness.trails.count", 64);
    private static final int POINTS = Integer.getInteger("crystalgraphics.harness.trails.points", 64);
    private static final boolean MERGED = Boolean.getBoolean("crystalgraphics.harness.trails.merged");
    private static final float HALF_WIDTH = 0.06f, STRENGTH = 1.2f;
    /** Before profile mode's warmup ends, so the picture's PNG write is never a profiled frame. */
    private static final long SHOT_FRAME = 50;

    private final CgVfxTrail[] trails = new CgVfxTrail[COUNT];
    private final Matrix4f view = new Matrix4f().setLookAt(0f, 6f, 12f, 0f, 1.5f, 0f, 0f, 1f, 0f);
    private CgMaterial glow;
    private CgMesh merged;

    @Override
    public void init(HarnessContext ctx) {
        for (int k = 0; k < COUNT; k++) trails[k] = new CgVfxTrail(POINTS);
        glow = CgMaterial.load("crystalgraphics:shaders/vfx/particle/trail.shader");
        LOG.info("[vfx-trails] {} trails of {} points, {}", COUNT, POINTS, MERGED ? "one merged mesh" : "a mesh each");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        float seconds = (float) frame.getElapsedTime();
        CgWorldRenderer world = CgWorldRenderer.get();
        // The trails' own CPU cost: moving them, rewriting their meshes, submitting the draws.
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, "trails.submit")) {
            submit(world, seconds);
        }
        HarnessWorld.fire(ctx, view, ctx.getProjection());
        // One picture, once the trails have filled.
        if (frame.getFrameNumber() == SHOT_FRAME) ctx.getArtifactService().requestCapture(MERGED ? "merged" : "separate");
    }

    private void submit(CgWorldRenderer world, float seconds) {
        for (int k = 0; k < COUNT; k++) {
            float phase = k * 2.3999632f, spread = fraction(k * 0.618034f);
            float angle = seconds * (1.1f + 0.6f * fraction(k * 0.37f)) + phase, radius = 1.5f + 2.5f * spread;
            trails[k].push(Math.cos(angle) * radius, 1.5 + Math.sin(seconds * 1.7f + phase) * 1.2, Math.sin(angle) * radius,
                    HALF_WIDTH);
        }
        if (MERGED) {
            if (merged == null) merged = CgMesh.build(CgVertexFormat.SPATIAL, CgMesh.Usage.FRAME, m -> writeAll(m, trails));
            else merged.edit(trails, CgVfxTrailsScene::writeAll);
            merged.pad(HALF_WIDTH);
            world.draw(merged, glow).at(0.0, 0.0, 0.0).custom(0, 0.35f, 0.75f, 1f, STRENGTH).submit();
        } else {
            for (int k = 0; k < COUNT; k++) {
                float hue = (float) k / COUNT * 6.2831855f;
                trails[k].submit(world, glow, 0.55f + 0.45f * (float) Math.cos(hue), 0.55f + 0.45f * (float) Math.cos(hue + 2.1f),
                        0.55f + 0.45f * (float) Math.cos(hue + 4.2f), STRENGTH);
            }
        }
    }

    private static void writeAll(CgMeshWriter m, CgVfxTrail[] trails) {
        for (CgVfxTrail trail : trails) trail.writeTo(m, 0.0, 0.0, 0.0);
    }

    private static float fraction(float v) {
        return v - (float) Math.floor(v);
    }

    @Override
    public void dispose() {
        for (CgVfxTrail trail : trails) if (trail != null) trail.release();
        if (merged != null) merged.release();
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
