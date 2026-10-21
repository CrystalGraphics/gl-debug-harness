package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessJitWatch;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.graph.CgRehearsal;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxOwner;
import com.crystalgraphics.vfx.CgVfxRegistry;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p><b>Rehearsed</b> ({@code -Dcrystalgraphics.harness.prepare.casts=10}): the move is also rehearsed
 * ({@link CgVfxSystem#rehearse}) before it plays, then cast that many times, {@value #CAST_FRAMES} frames each and
 * cleared between. The first cast's frame-thread time must match the last's (within 10% or 0.25 ms a frame), and the
 * first cast must load no engine class (JFR, {@link HarnessJitWatch}); its compilations and deopts are printed beside
 * the last cast's, the JIT's floor in a warm cast.
 * {@code .prepare.rehearse=false} is the control, which should fail; {@code -Dcrystalgraphics.rehearsal.listGrowth=true}
 * lists the buffers the rehearsal grew instead of failing on the first.</p>
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-prepare" -Dcrystalgraphics.harness.prepare.casts=10 \
 *     -Dcrystalgraphics.harness.fps=0
 * }</pre>
 *
 * <p><b>Delayed</b> ({@code -Dcrystalgraphics.harness.prepare.delay=600}): the looks are prepared after that many idle
 * frames rather than at start, as a game loads palettes mid-session: profile that window for the warm-up's own cost,
 * not the JIT's at a cold start.</p>
 *
 * <p>Prints {@code [vfx-prepare] PASS} or {@code FAIL}; run it on {@code gl}, {@code mac41} and {@code vulkan}.</p>
 */
public final class CgVfxPrepareScene implements InteractiveSceneLifecycle {

    private static final String[] MOVE = {"crystalgraphics:kamehameha", "crystalgraphics:explosion",
            "crystalgraphics:heat_haze"};
    private static final int FRAMES = 1200;
    private static final boolean SKIP = Boolean.getBoolean("crystalgraphics.harness.prepare.skip");
    private static final int IDLE = 120;
    private static final int CASTS = Integer.getInteger("crystalgraphics.harness.prepare.casts", 0);
    private static final boolean REHEARSE = Boolean.parseBoolean(System.getProperty("crystalgraphics.harness.prepare.rehearse", "true"));
    private static final int CAST_FRAMES = 300;
    private static final int DELAY = Integer.getInteger("crystalgraphics.harness.prepare.delay", 0);
    /** Noise between two casts' mean frame-thread time: a share, and a floor in nanoseconds. */
    private static final double NOISE_SHARE = 0.10, NOISE_FLOOR = 250_000;

    private final CgVfxSystem vfx = new CgVfxSystem();
    private final CgVfxOwner owner = CgVfx.owner("harness", vfx);
    private boolean running = true, playing;
    private int frames, warmFrames;
    private long before, unreadyBefore, mostDrawn;
    private boolean hdrWas;
    private CgQuality qualityWas;
    private CgRehearsal rehearsal;
    private HarnessJitWatch jit;
    private int cast, castFrame;
    private final long[] castNanos = new long[Math.max(1, CASTS)];

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(-6f, 4f, 10f);
        ctx.getCamera3D().setYaw(-30f);
        ctx.getCamera3D().setPitch(-12f);
        if (SKIP) return;
        for (String id : MOVE) CgVfxRegistry.get(id).look();   // as a game has, long before a palette loads
        if (DELAY == 0) prepareLooks();
        if (CASTS > 0) {
            jit = HarnessJitWatch.start();
            if (REHEARSE) {
                rehearsal = vfx.rehearse(CgVfxPrepareScene::playMove);
                // Its length, for measuring what the first cast needs: the rehearsal's own defaults unless given.
                Integer least = Integer.getInteger("crystalgraphics.harness.prepare.minPasses");
                Integer window = Integer.getInteger("crystalgraphics.harness.prepare.window");
                if (least != null) rehearsal.minPasses(least).maxPasses(Math.max(least, 20_000));
                if (window != null) rehearsal.window(window);
            }
        }
    }

    private void prepareLooks() {
        CgVfxLook[] looks = new CgVfxLook[MOVE.length];
        for (int i = 0; i < MOVE.length; i++) looks[i] = CgVfxRegistry.get(MOVE[i]).look();
        owner.prepare(looks);
    }

    /** The move, into {@code system}: what the real cast plays, for the rehearsal's hidden system too. */
    private static void playMove(CgVfxSystem system) {
        play(system, MOVE[0], 0, 1.5, 0, 0, 0.5, -24);
        play(system, MOVE[1], 6, 0, -8, 6, 0, -8);
        play(system, MOVE[2], -4, 0.5, -4, -4, 0.5, -4);
    }

    private static void play(CgVfxSystem system, String id, double x, double y, double z, double tx, double ty, double tz) {
        CgVfxRegistry.Entry entry = CgVfxRegistry.get(id);
        system.play(entry.spawn(entry.look(), x, y, z, tx, ty, tz)).ground(0f);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (CASTS > 0) {
            renderCasts(ctx, frame);
            return;
        }
        // Unprepared, the move waits IDLE frames first, so the engine's own passes (text, post) are built before it.
        if (!SKIP && DELAY > 0 && warmFrames == DELAY) prepareLooks();
        if (!playing && (SKIP ? warmFrames >= IDLE : warmFrames >= DELAY && vfx.warmed())) {
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
            restoreSettings();
            running = false;
        }
    }

    /** Warm and rehearsed, then {@link #CASTS} casts, the first watched by the JIT watch. */
    private void renderCasts(HarnessContext ctx, FrameInfo frame) {
        if (!playing) {
            boolean warm;
            try {
                warm = vfx.warmed();
            } catch (IllegalStateException failed) {
                Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
                System.out.println("[vfx-prepare] FAIL on " + device() + ": the rehearsal failed: " + cause.getMessage());
                running = false;
                return;
            }
            if (warm) {
                playing = true;
                if (rehearsal != null) {
                    System.out.println("[vfx-prepare] rehearsed in " + rehearsal.passes() + " passes over " + warmFrames
                            + " frames" + (rehearsal.settledByMeasurement() ? "" : ", stopped at its most"));
                }
            } else {
                warmFrames++;
            }
        }
        if (playing && castFrame == 0) {
            if (cast == 0 || cast == CASTS - 1) jit.open();
            playMove(vfx);
        }
        long start = System.nanoTime();
        vfx.update(frame.getElapsedTime());
        vfx.submit(CgWorldRenderer.get());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
        if (!playing) return;
        castNanos[cast] += System.nanoTime() - start;
        if (++castFrame < CAST_FRAMES) return;
        if (cast == 0 || cast == CASTS - 1) jit.close();
        vfx.clear();
        castFrame = 0;
        if (++cast == CASTS) {
            List<HarnessJitWatch.Counts> windows = jit.stop();
            reportCasts(windows.get(0), windows.get(windows.size() - 1));
            running = false;
        }
    }

    private void reportCasts(HarnessJitWatch.Counts first, HarnessJitWatch.Counts last) {
        double firstMs = castNanos[0] / 1e6 / CAST_FRAMES, lastMs = castNanos[CASTS - 1] / 1e6 / CAST_FRAMES;
        StringBuilder each = new StringBuilder();
        for (int i = 0; i < CASTS; i++) each.append(i == 0 ? "" : " ").append(String.format("%.2f", castNanos[i] / 1e6 / CAST_FRAMES));
        double noise = Math.max(NOISE_SHARE * castNanos[CASTS - 1], NOISE_FLOOR * CAST_FRAMES);
        boolean even = Math.abs(castNanos[0] - castNanos[CASTS - 1]) <= noise;
        boolean pass = even && first.classLoads() == 0;
        System.out.println("[vfx-prepare] " + (pass ? "PASS" : "FAIL") + " on " + device() + (REHEARSE ? "" : " (control, unrehearsed)")
                + ": cast 1 " + String.format("%.2f", firstMs) + " ms a frame, cast " + CASTS + " "
                + String.format("%.2f", lastMs) + " (" + (even ? "within" : "beyond") + " noise); cast 1 had " + first);
        System.out.println("[vfx-prepare] ms a frame, each cast: " + each);
        System.out.println("[vfx-prepare] cast " + CASTS + " had " + last + ": the JIT's floor in a warm cast");
        // Cast 1's events by type and class, most first: what the rehearsal left cold.
        Map<String, Integer> byClass = new HashMap<>();
        for (String named : first.named()) {
            int dot = named.lastIndexOf('.');
            byClass.merge(named.substring(0, named.indexOf(' ') + 1) + (named.startsWith("ClassLoad") ? named.substring(named.indexOf(' ') + 1) : named.substring(named.indexOf(' ') + 1, dot)), 1, Integer::sum);
        }
        byClass.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(40)
                .forEach(e -> System.out.println("[vfx-prepare]   " + e.getValue() + " " + e.getKey()));
        List<String> grown = CgRehearsal.grown();
        if (!grown.isEmpty()) System.out.println("[vfx-prepare] the rehearsal grew: " + grown);
    }

    private static String device() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        return device == null ? "gl" : device.name();
    }

    private void report(long waits, long unready) {
        String on = device();
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
        restoreSettings();   // a run cut short (a profile window, --seconds) would otherwise save them toggled
        vfx.delete();
    }

    private void restoreSettings() {
        if (qualityWas == null) return;
        CgGraphicsSettings.HDR.set(hdrWas);
        CgGraphicsSettings.QUALITY.set(qualityWas);
        qualityWas = null;
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return true; }
}
