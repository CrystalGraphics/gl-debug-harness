package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.emit.CgKernelTarget;
import com.crystalgraphics.compute.program.CgComputeCheck;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgCapabilities.ComputeTier;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.util.io.CgIO;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * gpu-compute C10's gate for checked mode ({@code -Dcrystalgraphics.compute.checked=true}): {@code compute_check.compute}'s
 * kernels dispatched a few frames, three of them past their buffer or image, one inside. {@code CgComputeCheck} must
 * report each of the three once, at its line in the file, with the index and the count of one dispatch, and never the
 * fourth.
 *
 * <p>Checked mode is compute's: forced below it, the scene says so and passes. Prints {@code [compute-check] PASS} or
 * {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgComputeCheckTestScene implements InteractiveSceneLifecycle {

    private static final String FILE = "harness:shaders/compute_check.compute";
    private static final int FRAMES = 3, DRAIN = 30;
    private static final CgFrameBufferFormat R32F = CgFrameBufferFormat.builder("compute-check")
            .color(0, CgTextureType.R32F).build();

    private final CgGraphBuffer out = CgGraphBuffer.persistent("compute-check.out",
            CgBufferDesc.elements(64, 4, CgBufferUsage.STORAGE));
    private final CgGraphBuffer inside = CgGraphBuffer.persistent("compute-check.inside",
            CgBufferDesc.elements(64, 4, CgBufferUsage.STORAGE));
    private final CgGraphBuffer bins = CgGraphBuffer.persistent("compute-check.bins",
            CgBufferDesc.elements(64, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
    private final CgGraphTexture heat = CgGraphTexture.requested("compute-check.heat", new CgTextureDesc(32, 32, R32F));
    private int frame, before;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        before = CgComputeCheck.reported().size();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame <= FRAMES) {
            CgCompute file = CgCompute.load(FILE);
            CgRecording rec = new CgRecording();
            rec.fill(bins, 0);
            CgComputePass pass = rec.compute("compute-check");
            pass.dispatch(file.kernel("Overrun"), 70).bind("OUT", out);
            pass.dispatch(file.kernel("Bin"), 64).bind("BINS", bins);
            pass.dispatch(file.kernel("Paint"), 40, 32, 1).image("HEAT", heat);
            pass.dispatch(file.kernel("Inside"), 64).bind("OUT", inside);
            pass.end();
            CgImmediate.execute(rec);
            return;
        }
        if (reported().size() >= 3 || frame > FRAMES + DRAIN) {
            report();
            running = false;
        }
    }

    private List<String> reported() {
        List<String> all = CgComputeCheck.reported();
        return all.subList(before, all.size());
    }

    private void report() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        ComputeTier tier = CgCapabilities.detect().computeTier();
        if (GlErrorChecker.checkAndLog("compute-check")) {
            System.out.println("[compute-check] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        if (PlatformServiceHarness.validationErrors() > 0) {
            System.out.println("[compute-check] FAIL on " + on + ": " + PlatformServiceHarness.validationErrors()
                    + " Vulkan validation errors, logged above");
            return;
        }
        if (tier != ComputeTier.V && tier != ComputeTier.G43) {
            System.out.println("[compute-check] PASS on " + on + " at " + tier + ": checked mode checks kernels run as "
                    + "compute, and nothing here runs as compute");
            return;
        }
        if (!CgKernelTarget.CHECKED) {
            System.out.println("[compute-check] FAIL: run with -Dcrystalgraphics.compute.checked=true");
            return;
        }
        if ("recording".equals(on)) {
            System.out.println("[compute-check] PASS on recording: every checked dispatch validated; a recording device "
                    + "reads back no reports");
            return;
        }
        String text = CgIO.loadSource(FILE);
        String[] want = {
                "kernel Overrun, line " + line(text, "OUT_WRITE(uint") + ": OUT_WRITE at 6[4-9], past OUT's 64 elements \\(6 times in one dispatch\\)",
                "kernel Bin, line " + line(text, "BINS_ADD(") + ": BINS_ADD at 64, past BINS's 64 elements",
                "kernel Paint, line " + line(text, "HEAT_WRITE(") + ": HEAT_WRITE at \\(3[2-9], [0-9]+\\), outside HEAT's 32x32 \\(256 times in one dispatch\\)"};
        List<String> got = reported();
        for (String pattern : want) {
            boolean found = false;
            Matcher m = Pattern.compile(Pattern.quote(FILE) + ", " + pattern).matcher("");
            for (String r : got) found |= m.reset(r).matches();
            if (!found) {
                System.out.println("[compute-check] FAIL on " + on + ": no report matching '" + pattern + "' in " + got);
                return;
            }
        }
        for (String r : got) {
            if (r.contains("kernel Inside")) {
                System.out.println("[compute-check] FAIL on " + on + ": a kernel inside its buffer was reported: " + r);
                return;
            }
        }
        if (got.size() != 3) {
            System.out.println("[compute-check] FAIL on " + on + ": " + got.size() + " reports, not 3, over " + FRAMES
                    + " frames: " + got);
            return;
        }
        System.out.println("[compute-check] PASS on " + on + ": a write past a buffer, an add past it and a texel past an "
                + "image each named once at its line, and the kernel inside its buffer never");
    }

    /** The line of {@code text}'s first {@code needle}, from 1. */
    private static int line(String text, String needle) {
        int at = text.indexOf(needle), line = 1;
        for (int i = 0; i < at; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    @Override
    public void dispose() {
        CgRecording release = new CgRecording();
        release.release(out);
        release.release(inside);
        release.release(bins);
        CgImmediate.execute(release);
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
