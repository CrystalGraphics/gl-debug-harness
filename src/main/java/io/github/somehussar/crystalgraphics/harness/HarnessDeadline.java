package io.github.somehussar.crystalgraphics.harness;

import java.util.logging.Logger;

/**
 * A wall-clock cap on how long a scene runs, set with {@code --seconds=N}.
 *
 * <h3>Why this is global rather than a scene's own business</h3>
 *
 * <p>Interactive scenes run until the window is closed, which is exactly right for a human and useless for
 * anything automated: a script or an agent launching one has no way to get it back. Scenes grew their own
 * escapes for this — {@code --oneshot}, a profile frame count, a hand-rolled frame counter — each spelled
 * differently, each present in one scene and absent from the rest. So the caller had to know which scene
 * supported which flag before it could safely start one.</p>
 *
 * <p><b>One flag, honoured by every scene, is the point.</b> It belongs to the runner rather than the scene
 * for the same reason: a scene cannot opt out of it, and a newly written scene gets it without doing
 * anything.</p>
 *
 * <h3>Two mechanisms, because one of them cannot fail</h3>
 *
 * <p>The interactive loop tests {@link #expired()} alongside its other exit conditions, so a scene that
 * reaches its deadline shuts down through the ordinary path with GL teardown intact. That is the mechanism
 * that should fire.</p>
 *
 * <p>The <b>watchdog</b> is the one that cannot fail. A scene stuck in a driver call, a blocking read, or an
 * infinite loop inside {@code render()} never reaches the top of the loop again, and a cap that only works
 * when the scene is healthy is not a cap — it is exactly the case an automated caller needs protection
 * from. So a daemon thread halts the process a grace period past the deadline, whatever the scene is doing.
 * It logs loudly first, because a halt with no explanation reads as a crash.</p>
 *
 * <p>Halting rather than {@code System.exit} is deliberate: shutdown hooks run on the JVM's exit path and a
 * hung GL context is precisely where one of those will block too.</p>
 *
 * <h3>Exit code is zero</h3>
 *
 * <p>Reaching the deadline is the <em>requested</em> outcome, not a failure — {@code --seconds=10} means
 * "run for ten seconds", so exiting after ten seconds is success. A non-zero code here would fail the Gradle
 * task and turn every timed run into a red build.</p>
 */
public final class HarnessDeadline {

    private static final Logger LOGGER = Logger.getLogger(HarnessDeadline.class.getName());

    /**
     * How long past the deadline the watchdog waits before halting.
     *
     * <p>Long enough that an orderly shutdown — GL teardown, artifact writes, a profiler dump — finishes on
     * its own and the watchdog never fires. Short enough that a genuinely wedged run still returns to the
     * caller in a useful time. If the watchdog is firing on a healthy scene, its cleanup is what wants
     * looking at, not this number.</p>
     */
    private static final long GRACE_MILLIS = 10_000L;

    /** {@code 0} when no cap was asked for, which is the default and means "run until closed". */
    private static volatile long deadlineNanos;

    private HarnessDeadline() {
    }

    /**
     * Starts the clock. Called once, immediately before the scene runs.
     *
     * <p>Timed from here rather than from process start so the cap measures the <b>scene</b>, not the GL
     * context creation and shader compilation in front of it — those vary by machine and are not what a
     * caller is trying to bound.</p>
     *
     * @param seconds wall-clock seconds; {@code <= 0} disables the cap entirely
     */
    public static void arm(double seconds) {
        if (seconds <= 0d) {
            deadlineNanos = 0L;
            return;
        }
        deadlineNanos = System.nanoTime() + (long) (seconds * 1_000_000_000d);
        LOGGER.info("[HarnessDeadline] Scene capped at " + seconds + "s"
                + " (watchdog halts at +" + (GRACE_MILLIS / 1000L) + "s)");
        startWatchdog(seconds);
    }

    /** Whether the cap has been reached. Always false when no cap was set. */
    public static boolean expired() {
        long at = deadlineNanos;
        // Subtraction rather than a bare compare: nanoTime has an arbitrary origin and may be negative, so
        // `now > at` is wrong across the wrap that difference handles correctly.
        return at != 0L && System.nanoTime() - at >= 0L;
    }

    /** Whether a cap is in force, for a scene that wants to say so in its own logging. */
    public static boolean isArmed() {
        return deadlineNanos != 0L;
    }

    private static void startWatchdog(double seconds) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep((long) (seconds * 1000d) + GRACE_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            LOGGER.severe("[HarnessDeadline] Scene did not stop " + (GRACE_MILLIS / 1000L)
                    + "s past its --seconds=" + seconds + " cap -- halting. The scene is wedged somewhere"
                    + " that never returns to the frame loop; a thread dump is the next step.");
            Runtime.getRuntime().halt(0);
        }, "harness-deadline-watchdog");
        // Daemon, so a run that finishes early is not held open by a watchdog still counting down.
        watchdog.setDaemon(true);
        watchdog.start();
    }
}
