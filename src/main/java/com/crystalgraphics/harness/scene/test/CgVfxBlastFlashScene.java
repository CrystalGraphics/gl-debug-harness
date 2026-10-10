package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.demo.CgVfxBlastFlash;
import com.crystalgraphics.demo.CgVfxShowcase;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;

/**
 * {@link CgVfxBlastFlash} on the showcase's floor: one wave at a time bursting on it, for judging the blast's screen
 * flash and impact frame. Scene id {@code vfx-blast-flash}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-blast-flash"
 * // F: the flash double (now), single (before) or off     I: the impact frame and its hitstop on or off
 * // B: the billows drawn or not                            , .: a shorter or longer wait before the next wave
 * // Y: time faster, up to 2x; Shift+Y slower, down to 0x (0.5, 0.25, 0.1, 0.01)
 *
 * // Each frame of the first blast's impact frame photographed, with the frame before and after it, then it exits:
 * //   harness-output/vfx-blast-flash/vfx-blast-flash-00-before.png, -NN-impact-frame-<k>.png, -NN-after.png
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-blast-flash" \
 *     -Dcrystalgraphics.harness.vfx.moments=true -Dcrystalgraphics.harness.fixedDelta=0.0166667
 * // A later wave's: -Dcrystalgraphics.harness.vfx.wave=6
 * }</pre>
 */
public final class CgVfxBlastFlashScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.VfxBlastFlash");

    /** Where the wave bursts, in front of the showcase's grid; the camera looks down -z, so it fires along +x. */
    private static final double TARGET_X = 3.0, Z = 12.0;

    private final CgVfxShowcase stage = new CgVfxShowcase();
    private final CgVfxBlastFlash blast = new CgVfxBlastFlash();
    private double seconds;
    /**
     * The impact frame's frame announced this tick, photographed this frame; the wave photographed; whether it has hit
     * and whether its impact frame has shown.
     */
    private String moment;
    private CgEnergyWave photographed;
    private boolean photographing, hit, shown;
    /** The wave photographed, from 1 (.vfx.wave); captures taken. */
    private final int photographWave = Integer.getInteger("crystalgraphics.harness.vfx.wave", 1);
    private int captures;
    private boolean running = true, shift;

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 6f, 34f);
        ctx.getCamera3D().setPitch(-10f);
        ctx.getCamera3D().setMoveSpeed(6f);
        LOG.info("[vfx-blast-flash] F flash, I impact frame, B billows, Y / Shift+Y time, , . wait before the next wave");
        photographing = Boolean.getBoolean("crystalgraphics.harness.vfx.moments");
        if (photographing) blast.onMoment((effect, name, x, y, z, radius) -> {
            if (effect != photographed) return;
            if (name.equals(CgEnergyWave.MOMENT_IMPACT)) hit = true;
            if (name.startsWith(CgEnergyWave.MOMENT_IMPACT_FRAME)) moment = name;
        });
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        seconds += frame.getDeltaTime();
        CgWorldRenderer world = CgWorldRenderer.get();
        blast.submit(world, TARGET_X, 0.0, Z, 0.0, -1.0, seconds);
        if (photographing) {
            if (photographed == null && blast.fired() == photographWave) photographed = blast.wave();
            if (photographed != null) photograph(ctx);
        }
        stage.submitStage(world, 0.0, 0.0, 0.0, ctx.getCamera3D().getPosX(), ctx.getCamera3D().getPosY(),
                ctx.getCamera3D().getPosZ());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    /**
     * The photographed wave's impact frame: the last frame before it (overwritten each frame from the hit), each of its
     * frames as announced, and the first frame after it; then the scene exits.
     */
    private void photograph(HarnessContext ctx) {
        String name = null;
        if (photographed.impactFrameShowing()) {
            shown = true;
            if (moment != null) name = String.format(Locale.ROOT, "%02d-%s", ++captures, moment);
        } else if (shown) {
            name = String.format(Locale.ROOT, "%02d-after", ++captures);
            running = false;
        } else if (hit) {
            name = "00-before";
        }
        moment = null;
        if (name != null) ctx.getArtifactService().requestCapture(name);
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        int key = event.key();
        if (key == CgKeyCodes.KEY_LSHIFT || key == CgKeyCodes.KEY_RSHIFT) shift = event.pressed();
        else if (event.pressed() && !event.repeat()) blast.press(key, shift);
        return true;
    }

    @Override
    public String hudLine() {
        return blast.hudLine();
    }

    @Override
    public void dispose() {
        blast.delete();
        stage.delete();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
