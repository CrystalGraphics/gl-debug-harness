package com.crystalgraphics.harness.scene.test;

import java.util.Locale;

import org.joml.Matrix4f;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.demo.CgDepthOfFieldDemo;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.camera.Camera3D;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.post.CgPost;
import com.crystalgraphics.render.scene.CgScene;

/**
 * The depth of field demo ({@link CgDepthOfFieldDemo}, {@code CgRenderDemo}'s {@code dof}) on the harness floor, its lens
 * zoomable too: the runner's projection replaced by the lens's on its sensor. Scene id {@code dof-lab}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=dof-lab --rhi=vulkan"
 * // Up / Down focus, F focus on the centre, Home pull, Left / Right f-stop, B / Shift+B blades, Insert widest,
 * // PgUp / PgDn format, End on or off (CgDepthOfFieldDemo); Z / X a shorter / longer lens, 18 to 200 mm
 * }</pre>
 */
public final class CgDepthOfFieldLabScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    /** Lenses Z and X step through, in millimetres. */
    private static final float[] LENSES = {18f, 24f, 35f, 50f, 85f, 135f, 200f};
    /** The sensor the lens's projection is measured on: the settings' default. */
    private static final float SENSOR = 24.576f;

    private final CgDepthOfFieldDemo demo = new CgDepthOfFieldDemo();
    private final Matrix4f projection = new Matrix4f();
    private final CgCamera eye = new CgCamera();
    private Camera3D camera;
    private int lens = 3;
    private double seconds;

    @Override
    public void init(HarnessContext ctx) {
        camera = ctx.getCamera3D();
        camera.moveCamera(0f, 1.6f, 4f);
        camera.setYaw(0f);
        camera.setPitch(0f);
        camera.setMoveSpeed(6f);
        demo.precache();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        seconds += frame.getDeltaTime();
        Matrix4f lens = lensProjection(ctx);
        eye.set(camera.getPosX(), camera.getPosY(), camera.getPosZ(), camera.getViewMatrix(), lens);
        if (demo.ready()) demo.submit(CgScene.client(), eye, 0, 0, 0, seconds);
        HarnessWorld.fire(ctx, camera.getViewMatrix(), lens);
    }

    /** The lens's projection: its horizontal FOV on the sensor, the runner's aspect, near and far. */
    private Matrix4f lensProjection(HarnessContext ctx) {
        Matrix4f base = ctx.getProjection();
        float m00 = 2f * LENSES[lens] / SENSOR;
        return projection.set(base).m00(m00).m11(m00 * base.m11() / base.m00());
    }

    /** The runner draws its floor through the lens too. */
    @Override
    public boolean worldView(HarnessContext ctx, Matrix4f outView, Matrix4f outProjection) {
        outView.set(camera.getViewMatrix());
        outProjection.set(lensProjection(ctx));
        return true;
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        boolean focusKey = event.key() == CgKeyCodes.KEY_UP || event.key() == CgKeyCodes.KEY_DOWN;
        if (!event.pressed() || event.repeat() && !focusKey) return true;
        switch (event.key()) {
            case CgKeyCodes.KEY_Z -> lens = Math.max(0, lens - 1);
            case CgKeyCodes.KEY_X -> lens = Math.min(LENSES.length - 1, lens + 1);
            default -> demo.press(event.key(), CgInput.isKeyDown(CgKeyCodes.KEY_LSHIFT) || CgInput.isKeyDown(CgKeyCodes.KEY_RSHIFT));
        }
        return true;
    }

    @Override
    public String hudLine() {
        return String.format(Locale.ROOT, "Lens [Z/X]: %d mm", (int) LENSES[lens]) + demo.hud();
    }

    @Override
    public void dispose() {
        demo.delete();
        CgPost.client().camera().reset();
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
