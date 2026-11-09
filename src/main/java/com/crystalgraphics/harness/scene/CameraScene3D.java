package com.crystalgraphics.harness.scene;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.config.ViewportState;
import com.crystalgraphics.harness.util.HarnessProjectionUtil;
import com.crystalgraphics.harness.util.ValidationCube;
import com.crystalgraphics.harness.validation.ValidationCaptureStep;
import com.crystalgraphics.harness.validation.ValidationChoreographer;

import java.util.logging.Logger;

/**
 * The camera's check: a {@link ValidationCube} on the floor, captured from four set angles -- {@code front-view},
 * {@code side-view}, {@code top-down-view} and {@code diagonal-view} -- after which the scene stops.
 */
public class CameraScene3D implements InteractiveSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(CameraScene3D.class.getName());

    private boolean running = true;
    private ValidationCube cube;

    @Override
    public void init(HarnessContext ctx) {
        cube = new ValidationCube();
        ValidationChoreographer choreographer = new ValidationChoreographer(
                ctx.getCamera3D(), ctx.getTaskScheduler(), ctx.getArtifactService(), ctx.getRuntimeServices());
        choreographer.addStep(ValidationCaptureStep.builder("front-view", 0.5)
                .cameraPosition(0.0f, 2.0f, 8.0f).cameraOrientation(0.0f, -10.0f).build());
        choreographer.addStep(ValidationCaptureStep.builder("side-view", 1.0)
                .cameraPosition(8.0f, 2.0f, 0.0f).cameraOrientation(90.0f, -10.0f).build());
        choreographer.addStep(ValidationCaptureStep.builder("top-down-view", 1.5)
                .cameraPosition(0.0f, 12.0f, 0.1f).cameraOrientation(0.0f, -89.0f).build());
        choreographer.addStep(ValidationCaptureStep.builder("diagonal-view", 2.0)
                .cameraPosition(5.0f, 3.5f, 5.0f).cameraOrientation(45.0f, -20.0f).build());
        choreographer.onShutdown(() -> {
            LOGGER.info("[Camera3DValidation] All screenshots captured. Stopping.");
            running = false;
        });
        choreographer.scheduleAll();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        ViewportState vp = ctx.getViewport();
        cube.render(ctx.getCamera3D().getViewMatrix(), HarnessProjectionUtil.perspective(vp.getWidth(), vp.getHeight()),
                vp.getWidth(), vp.getHeight());
    }

    @Override
    public void dispose() {
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean uses3DCamera() {
        return true;
    }
}
