package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
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
 * // unprepared with unready draws skipped: no waits, and the skipped draws counted
 * ... -Dcrystalgraphics.harness.prepare.skip=true -Dcrystalgraphics.world.skipUnready=true
 * }</pre>
 *
 * <p>Prepared, halfway through the move it toggles HDR and the quality tier, which must re-warm before applying and so
 * add no wait either; both settings are put back after.</p>
 *
 * <p>Prints {@code [vfx-prepare] PASS} or {@code FAIL}; run it on {@code gl}, {@code mac41} and {@code vulkan}.</p>
 */
public final class CgVfxPrepareScene implements InteractiveSceneLifecycle {

    private static final String[] MOVE = {"crystalgraphics:kamehameha", "crystalgraphics:explosion",
            "crystalgraphics:heat_haze"};
    private static final int FRAMES = 1200;
    private static final boolean SKIP = Boolean.getBoolean("crystalgraphics.harness.prepare.skip");
    private static final int IDLE = 120;

    private final CgVfxSystem vfx = new CgVfxSystem();
    private final CgVfxOwner owner = CgVfx.owner("harness", vfx);
    private boolean running = true, playing;
    private int frames, warmFrames;
    private long before, unreadyBefore, mostDrawn;
    private boolean hdrWas;
    private CgQuality qualityWas;

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
        // Unprepared, the move waits IDLE frames first, so the engine's own passes (text, post) are built before it.
        if (!playing && (SKIP ? warmFrames >= IDLE : vfx.warmed())) {
            before = CgMaterialShader.compileWaits();
            unreadyBefore = CgWorldRenderer.get().unreadyDraws();
            owner.play(MOVE[0], 0, 1.5, 0, 0, 0.5, -24).ground(0f);
            owner.play(MOVE[1], 6, 0, -8, 6, 0, -8).ground(0f);
            owner.play(MOVE[2], -4, 0.5, -4, -4, 0.5, -4).ground(0f);
            playing = true;
        }
        if (!playing) warmFrames++;
        if (playing && !SKIP && frames == FRAMES / 2) {
            hdrWas = CgGraphicsSettings.HDR.get();
            qualityWas = CgGraphicsSettings.QUALITY.get();
            CgGraphicsSettings.HDR.set(!hdrWas);
            CgGraphicsSettings.QUALITY.set(qualityWas == CgQuality.LOW ? CgQuality.HIGH : CgQuality.LOW);
        }
        vfx.update(frame.getElapsedTime());
        vfx.submit(CgWorldRenderer.get());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
        if (playing) mostDrawn = Math.max(mostDrawn, CgVfxSystem.particlesDrawn());
        if (playing && ++frames == FRAMES) {
            report(CgMaterialShader.compileWaits() - before, CgWorldRenderer.get().unreadyDraws() - unreadyBefore);
            if (qualityWas != null) {
                CgGraphicsSettings.HDR.set(hdrWas);
                CgGraphicsSettings.QUALITY.set(qualityWas);
            }
            running = false;
        }
    }

    private void report(long waits, long unready) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (validation > 0) {
            System.out.println("[vfx-prepare] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (mostDrawn == 0) {
            // A move that draws nothing also compiles nothing: never a pass.
            System.out.println("[vfx-prepare] FAIL on " + on + ": the move drew no particle in its " + FRAMES + " frames");
        } else if (SKIP && CgWorldRenderer.get().skipUnready()) {
            System.out.println("[vfx-prepare] " + (waits == 0 && unready > 0 ? "PASS" : "FAIL") + " on " + on
                    + " (unprepared, unready draws skipped): " + waits + " compile waits, " + unready + " draws skipped and counted");
        } else if (SKIP) {
            System.out.println("[vfx-prepare] " + (waits > 0 ? "PASS" : "FAIL") + " on " + on + " (control, unprepared): "
                    + waits + " compile waits in the move's first " + FRAMES + " frames, where some are due");
        } else if (waits == 0) {
            System.out.println("[vfx-prepare] PASS on " + on + ": prepared over " + warmFrames + " frames, the move's first "
                    + FRAMES + " frames compiled nothing, HDR and the tier toggled halfway");
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
