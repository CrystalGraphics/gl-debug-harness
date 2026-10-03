package com.crystalgraphics.harness.camera;

import com.crystalgraphics.platform.service.CgHostCamera;
import org.joml.Matrix4f;

/**
 * The harness's camera as a host camera: it keeps the offset {@code CgCameraShake} hands it and, while it is on,
 * applies it to the view and projection the world stages are fired with. Off at start, so captures stay still; C
 * turns it on and off in a scene with a 3D camera, and the HUD says which.
 *
 * <pre>{@code
 * CgPlatform.provide(CgHostCamera.SERVICE, HarnessCameraShake.INSTANCE);   // once, at start
 * HarnessCameraShake.INSTANCE.toggle();                                   // C
 * HarnessCameraShake.INSTANCE.apply(view, projection);                    // each world frame, both in place
 * }</pre>
 */
public final class HarnessCameraShake implements CgHostCamera {

    public static final HarnessCameraShake INSTANCE = new HarnessCameraShake();

    private float x, y, z, yaw, pitch, roll, fovScale = 1f;
    private boolean on;
    private int applied;

    private HarnessCameraShake() {
    }

    @Override
    public void offset(float x, float y, float z, float yaw, float pitch, float roll, float fovScale) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        this.fovScale = fovScale;
    }

    @Override
    public int capabilities() {
        return ROTATION | ROLL | FOV;
    }

    @Override
    public int applied() {
        return applied;
    }

    public boolean on() {
        return on;
    }

    public void toggle() {
        on = !on;
    }

    /**
     * Shakes {@code view} (world to eye) and widens {@code projection} (a perspective one) by the last offset, in
     * place; leaves both alone while off.
     */
    public void apply(Matrix4f view, Matrix4f projection) {
        if (!on) return;
        applied = capabilities();
        // Moved along the world's axes, then turned about the eye's own.
        view.translate(-x, -y, -z)
                .rotateLocalY((float) Math.toRadians(yaw))
                .rotateLocalX((float) Math.toRadians(pitch))
                .rotateLocalZ((float) Math.toRadians(roll));
        if (fovScale != 1f) {
            double half = Math.atan(1.0 / projection.m11());
            float k = (float) (projection.m11() * Math.tan(Math.min(half * fovScale, 1.5)));
            projection.m00(projection.m00() / k).m11(projection.m11() / k);
        }
    }
}
