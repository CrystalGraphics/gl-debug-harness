package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.collision.debug.CgCollisionDebug;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.collision.query.CgCollisionWorld;
import com.crystalgraphics.collision.query.CgShapeWorld;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgCapsule;
import com.crystalgraphics.shape.primitive.CgCompound;
import com.crystalgraphics.shape.primitive.CgCone;
import com.crystalgraphics.shape.primitive.CgConvex;
import com.crystalgraphics.shape.primitive.CgCylinder;
import com.crystalgraphics.shape.primitive.CgPlane;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgShell;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.shape.query.CgCastHit;
import com.crystalgraphics.shape.query.CgShapes;

/**
 * The collision engine's shapes drawn as {@code CgCollisionDebug} lines. Scene id {@code collision-shapes}.
 *
 * <ul>
 *   <li>The front row: every shape turning and bobbing through a box below it. A shape touching its box is green and
 *       the box red; apart, white and grey.</li>
 *   <li>Behind: a ball cast along a line through three boxes moving up and down, stopping red at the first it meets,
 *       with that surface's normal.</li>
 *   <li>In front: a ring hitbox's front growing through six boxes on the ground, each flashing red as the front
 *       reaches it, then starting again.</li>
 * </ul>
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=collision-shapes"
 * }</pre>
 */
public final class CgCollisionShapesScene implements InteractiveSceneLifecycle {

    private static final int WHITE = 0xFFFFFFFF, GREEN = 0xFF40FF60, GREY = 0xFF808080, RED = 0xFFFF4040,
            YELLOW = 0xFFFFD040, CYAN = 0xFF40D0FF;
    private static final double SPACING = 4;
    private static final CgBox TARGET = CgBox.of(0.6, 0.6, 0.6);

    private static final CgShape[] SHAPES = {
            CgSphere.of(1),
            CgBox.of(1, 0.5, 0.8),
            CgCapsule.of(0.8, 0.5),
            CgCylinder.of(0.8, 0.9),
            CgCone.of(1.6, Math.toRadians(35)),
            CgShell.of(0.6, 1.1),
            CgRing.of(0.6, 1.2, 0.4),
            CgCompound.builder().add(CgCapsule.of(0.7, 0.15), 0, 0, 0).add(CgBox.of(0.5, 0.3, 0.3), 0, 0, 1).build(),
            CgConvex.hull(new double[]{-1, 0, -0.6, 1, 0, -0.6, -1, 0, 0.8, 1, 0, 0.8, -0.6, 0.9, 0, 0.6, 0.9, 0}),
            CgPlane.of(0, 1, 0, 1.4),
    };

    // the sweep
    private static final CgSphere BALL = CgSphere.of(0.6);
    private static final double SWEEP_Z = -8, SWEEP_FROM = -15, SWEEP_LENGTH = 30;
    private static final double[] WALLS_X = {-6, 3, 10};
    private static final CgBox WALL = CgBox.of(0.5, 1.5, 1.5);

    // the hitbox
    private static final double RING_Z = 10, RING_REACH = 9, RING_TICKS = 40;
    private static final int TARGETS = 6;

    private final CgPose pose = new CgPose(), other = new CgPose();
    private final CgCastHit hit = new CgCastHit();
    private final CgShapeWorld arena = CgCollisionWorld.shapes();
    private final int[] flash = new int[TARGETS];
    private CgHitbox ring;

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 9f, 30f);
        ctx.getCamera3D().setPitch(-18f);
        ctx.getCamera3D().setMoveSpeed(8f);
        for (int i = 0; i < TARGETS; i++) {
            double angle = 2 * Math.PI * i / TARGETS, at = 2 + i * 1.2;
            arena.add(i, TARGET, new CgPose().at(Math.cos(angle) * at, 0.6, RING_Z + Math.sin(angle) * at));
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        double seconds = frame.getElapsedTime();
        shapes(seconds);
        sweep(seconds);
        ring(frame.getDeltaTime() * 20);
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    private void shapes(double seconds) {
        double left = -(SHAPES.length - 1) * SPACING / 2;
        for (int i = 0; i < SHAPES.length; i++) {
            double x = left + i * SPACING;
            pose.at(x, 2.6 + 1.4 * Math.sin(seconds * 0.9 + i), 0).facing(seconds * 0.5 + i, Math.sin(seconds * 0.4 + i));
            other.identity().at(x, 0.6, 0);
            boolean touching = CgShapes.overlaps(SHAPES[i], pose, TARGET, other);
            CgCollisionDebug.shape(SHAPES[i], pose, touching ? GREEN : WHITE);
            CgCollisionDebug.shape(TARGET, other, touching ? RED : GREY);
        }
    }

    private void sweep(double seconds) {
        double best = 1;
        boolean met = false;
        double nx = 0, ny = 0, nz = 0, px = 0, py = 0, pz = 0;
        pose.identity().at(SWEEP_FROM, 1.5, SWEEP_Z);
        for (int w = 0; w < WALLS_X.length; w++) {
            other.identity().at(WALLS_X[w], 1.5 + 3 * Math.sin(seconds * (0.7 + 0.3 * w) + w * 2), SWEEP_Z);
            CgCollisionDebug.shape(WALL, other, YELLOW);
            if (CgShapes.cast(BALL, pose, SWEEP_LENGTH, 0, 0, WALL, other, hit) && hit.fraction < best) {
                best = hit.fraction;
                met = true;
                nx = hit.normal.x;
                ny = hit.normal.y;
                nz = hit.normal.z;
                px = hit.point.x;
                py = hit.point.y;
                pz = hit.point.z;
            }
        }
        CgCollisionDebug.shape(BALL, pose, WHITE);
        double endX = SWEEP_FROM + SWEEP_LENGTH * best;
        CgCollisionDebug.line(SWEEP_FROM, 1.5, SWEEP_Z, endX, 1.5, SWEEP_Z, WHITE);
        CgCollisionDebug.shape(BALL, other.identity().at(endX, 1.5, SWEEP_Z), met ? RED : WHITE);
        if (met) CgCollisionDebug.line(px, py, pz, px + nx, py + ny, pz + nz, RED);
    }

    private void ring(double ticks) {
        if (ring == null || ring.stopped()) {
            ring = CgHitbox.of(CgRing.front(1.0, 0.8)).at(0, 0.8, RING_Z).radius(0, RING_REACH, RING_TICKS)
                    .activeFor(RING_TICKS).onHit(h -> flash[h.entity()] = 10);
        }
        ring.tick(arena, ticks);
        CgCollisionDebug.hitbox(ring, CYAN);
        for (int i = 0; i < TARGETS; i++) {
            double angle = 2 * Math.PI * i / TARGETS, at = 2 + i * 1.2;
            other.identity().at(Math.cos(angle) * at, 0.6, RING_Z + Math.sin(angle) * at);
            CgCollisionDebug.shape(TARGET, other, flash[i] > 0 ? RED : GREY);
            if (flash[i] > 0 && ticks > 0) flash[i]--;
        }
    }

    @Override
    public void dispose() {
        if (ring != null) ring.stop();
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
