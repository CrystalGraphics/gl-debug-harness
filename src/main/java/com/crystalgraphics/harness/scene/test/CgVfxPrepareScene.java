package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxOwner;
import com.crystalgraphics.vfx.CgVfxRegistry;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;

/**
 * G0.8's gate, programs prepared on load: a move's looks (the Kamehameha, the explosion, heat haze) prepared together,
 * played once {@link CgVfxSystem#warmed} says so, and their first {@value #FRAMES} frames must compile nothing
 * ({@link CgMaterialShader#compileWaits()} unchanged). Scene id {@code vfx-prepare}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-prepare"
 * // the control: played unprepared, the same frames must wait on compiles
 * ... -Dcrystalgraphics.harness.prepare.skip=true
 * }</pre>
 *
 * <p>Prints {@code [vfx-prepare] PASS} or {@code FAIL}; run it on {@code gl}, {@code mac41} and {@code vulkan}.</p>
 */
public final class CgVfxPrepareScene implements InteractiveSceneLifecycle {

    private static final String[] MOVE = {"crystalgraphics:kamehameha", "crystalgraphics:explosion",
            "crystalgraphics:heat_haze"};
    private static final int FRAMES = 1200;
    private static final boolean SKIP = Boolean.getBoolean("crystalgraphics.harness.prepare.skip");

    private final CgVfxSystem vfx = new CgVfxSystem();
    private final CgVfxOwner owner = CgVfx.owner("harness", vfx);
    private boolean running = true, playing;
    private int frames, warmFrames;
    private long before;

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(-6f, 4f, 10f);
        ctx.getCamera3D().setYaw(-30f);
        ctx.getCamera3D().setPitch(-12f);
        if (SKIP) return;
        CgVfxLook[] looks = new CgVfxLook[MOVE.length];
        for (int i = 0; i < MOVE.length; i++) looks[i] = CgVfxRegistry.get(MOVE[i]).look();
        owner.prepare(looks);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (!playing && (SKIP || vfx.warmed())) {
            before = CgMaterialShader.compileWaits();
            owner.play(MOVE[0], 0, 1.5, 0, 0, 0.5, -24).ground(0f);
            owner.play(MOVE[1], 6, 0, -8, 6, 0, -8).ground(0f);
            owner.play(MOVE[2], -4, 0.5, -4, -4, 0.5, -4).ground(0f);
            playing = true;
        }
        if (!playing) warmFrames++;
        vfx.update(frame.getElapsedTime());
        vfx.submit(CgWorldRenderer.get());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
        if (playing && ++frames == FRAMES) {
            report(CgMaterialShader.compileWaits() - before);
            running = false;
        }
    }

    private void report(long waits) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (validation > 0) {
            System.out.println("[vfx-prepare] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (SKIP) {
            System.out.println("[vfx-prepare] " + (waits > 0 ? "PASS" : "FAIL") + " on " + on + " (control, unprepared): "
                    + waits + " compile waits in the move's first " + FRAMES + " frames, where some are due");
        } else if (waits == 0) {
            System.out.println("[vfx-prepare] PASS on " + on + ": prepared over " + warmFrames + " frames, the move's first "
                    + FRAMES + " frames compiled nothing");
        } else {
            System.out.println("[vfx-prepare] FAIL on " + on + ": " + waits + " compile waits in the move's first " + FRAMES
                    + " frames after it was prepared");
        }
    }

    @Override
    public void dispose() {
        vfx.delete();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return true; }
}
