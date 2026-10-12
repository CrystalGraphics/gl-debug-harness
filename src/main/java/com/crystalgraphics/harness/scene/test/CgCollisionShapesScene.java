package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.demo.collision.CgCollisionDemo;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;

/**
 * {@link CgCollisionDemo} on the harness's floor, with no world behind it: every collision shape, a cast, rays and a
 * hitbox drawn as {@code CgCollisionDebug} lines. Scene id {@code collision-shapes}; {@code CgRenderDemo}'s
 * {@code collision} is the same demo in Minecraft, against its blocks and entities.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=collision-shapes"
 * }</pre>
 */
public final class CgCollisionShapesScene implements InteractiveSceneLifecycle {

    private final CgCollisionDemo demo = new CgCollisionDemo();

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 9f, 30f);
        ctx.getCamera3D().setPitch(-18f);
        ctx.getCamera3D().setMoveSpeed(8f);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        demo.submit(null, 0, 0, 0, frame.getElapsedTime());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    @Override
    public void dispose() {
        demo.clear();
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
