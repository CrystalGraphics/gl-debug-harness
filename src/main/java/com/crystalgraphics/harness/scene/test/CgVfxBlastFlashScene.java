package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.demo.CgVfxShowcase;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * The energy wave's blast over and over, for judging its screen flash and impact frame: a wave fires uncharged at the
 * floor every period and stops as it hits, so it bursts there. Scene id {@code vfx-blast-flash}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-blast-flash"
 * // F: the flash double (now), single (before) or off     I: the impact frame and its hitstop on or off
 * // T: time at 1x, 0.25x or 0.1x                          , .: a blast more or less often
 *
 * // Each frame of the first blast's impact frame photographed, with the frame before and after it, then it exits:
 * //   harness-output/vfx-blast-flash/vfx-blast-flash-00-before.png, -NN-impact-frame-<k>.png, -NN-after.png
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-blast-flash" \
 *     -Dcrystalgraphics.harness.vfx.moments=true -Dcrystalgraphics.harness.fixedDelta=0.0166667
 * // A later wave's, among the smoke of those before it: -Dcrystalgraphics.harness.vfx.wave=6
 * }</pre>
 *
 * Each switch applies from the next wave fired.
 */
public final class CgVfxBlastFlashScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.VfxBlastFlash");

    private enum Flash { DOUBLE, SINGLE, OFF }

    /** The flash before the double: one pulse, held, gone. */
    private static final CgKeyframes SINGLE = CgKeyframes.start(0f, 0f)
            .to(0.025f, 1f, CgEasings.OUT_QUAD)
            .to(0.08f, 0.85f, CgEasings.LINEAR)
            .to(0.5f, 0f, CgEasings.OUT_CUBIC)
            .build();
    private static final CgKeyframes NONE = CgKeyframes.start(0f, 0f).to(1f, 0f, CgEasings.LINEAR).build();
    private static final float[] SPEEDS = {1f, 0.25f, 0.1f};
    private static final float MIN_PERIOD = 0.5f, MAX_PERIOD = 5f;
    /** Where the wave fires from and where it bursts, in front of the showcase's grid. */
    private static final double MUZZLE_X = -9.0, MUZZLE_Y = 2.5, TARGET_X = 3.0, Z = 12.0;

    private final CgVfxShowcase stage = new CgVfxShowcase();
    private final CgVfxSystem vfx = new CgVfxSystem();
    /** Waves fired and not yet stopped: each stops as it hits. */
    private final List<CgEnergyWave> flying = new ArrayList<>();
    private Flash flash = Flash.DOUBLE;
    private boolean impact = true;
    private int speed;
    /** The scene's own clock, slowed by T, and when the next wave fires on it. */
    private float period = 1f, clock, nextFire;
    /**
     * The impact frame's frame announced this tick, photographed this frame; the first wave, the one photographed;
     * whether it has hit and whether its impact frame has shown; captures taken.
     */
    private String moment;
    private CgEnergyWave photographed;
    private boolean photographing, hit, shown;
    /** The wave photographed, from 1 (.vfx.wave), and waves fired; captures taken. */
    private final int photographWave = Integer.getInteger("crystalgraphics.harness.vfx.wave", 1);
    private int fired, captures;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 6f, 34f);
        ctx.getCamera3D().setPitch(-10f);
        ctx.getCamera3D().setMoveSpeed(6f);
        LOG.info("[vfx-blast-flash] a blast every {}s; F flash, I impact frame, T slow motion, , . period", period);
        photographing = Boolean.getBoolean("crystalgraphics.harness.vfx.moments");
        if (photographing) vfx.onMoment((effect, name, x, y, z, radius) -> {
            if (effect != photographed) return;
            if (name.equals(CgEnergyWave.MOMENT_IMPACT)) hit = true;
            if (name.startsWith(CgEnergyWave.MOMENT_IMPACT_FRAME)) moment = name;
        });
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        clock += frame.getDeltaTime() * SPEEDS[speed];
        if (clock >= nextFire) {
            fire();
            nextFire = clock + period;
        }
        for (Iterator<CgEnergyWave> it = flying.iterator(); it.hasNext(); ) {
            CgEnergyWave wave = it.next();
            if (!wave.impacting()) continue;
            wave.stop();
            it.remove();
        }
        CgWorldRenderer world = CgWorldRenderer.get();
        vfx.update(clock);
        if (photographing && photographed != null) photograph(ctx);
        vfx.submit(world);
        stage.submitStage(world, 0.0, 0.0, 0.0, ctx.getCamera3D().getPosX(), ctx.getCamera3D().getPosY(),
                ctx.getCamera3D().getPosZ());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    /**
     * The first wave's impact frame: the last frame before it (overwritten each frame from the hit), each of its
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

    private void fire() {
        CgEnergyWave wave = new CgEnergyWave(CgEnergyWave.kamehameha(), MUZZLE_X, MUZZLE_Y, Z);
        wave.aim(1f, -0.2f, 0f).target(TARGET_X, 0.0, Z).fire();
        wave.ground(0.0);
        if (flash != Flash.DOUBLE) wave.set(CgEnergyWave.BLAST_FLASH, flash == Flash.SINGLE ? SINGLE : NONE);
        wave.set(CgEnergyWave.BLAST_IMPACT, impact ? 1f : 0f);
        flying.add(vfx.play(wave));
        if (++fired == photographWave) photographed = wave;
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        if (!event.pressed() || event.repeat()) return true;
        switch (event.key()) {
            case CgKeyCodes.KEY_F -> flash = Flash.values()[(flash.ordinal() + 1) % Flash.values().length];
            case CgKeyCodes.KEY_I -> impact = !impact;
            case CgKeyCodes.KEY_T -> speed = (speed + 1) % SPEEDS.length;
            case CgKeyCodes.KEY_COMMA -> period = Math.max(MIN_PERIOD, period - 0.5f);
            case CgKeyCodes.KEY_PERIOD -> period = Math.min(MAX_PERIOD, period + 0.5f);
            default -> { }
        }
        return true;
    }

    @Override
    public String hudLine() {
        return String.format(Locale.ROOT, "Flash [F]: %s   Impact frame [I]: %s   Time [T]: %sx   Every [, .]: %.1f s",
                flash.name().toLowerCase(Locale.ROOT), impact ? "on" : "off", SPEEDS[speed], period);
    }

    @Override
    public void dispose() {
        vfx.delete();
        stage.delete();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
