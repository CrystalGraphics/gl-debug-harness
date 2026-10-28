package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.post.CgPostMaterial;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Consumer;

/**
 * OL5's gate in {@code skinned-play} ({@code -Dcrystalgraphics.harness.skinnedPlay.outlineCheck=true}, with
 * {@code -Dcrystalgraphics.harness.mainTarget=true}): Kakarot's battle outline on Goku held still, the picture read back
 * with the outline whole, without it, and with each of its three edge tests off in turn, and Goku's CustomStencil. Every
 * pixel the outline changes must be Goku's (stencil 1) and darker, and each test must draw some of it.
 */
final class SkinnedOutlineCheck {

    /** Frames each phase is held before its picture is read: the outline's properties land at the next record. */
    private static final int SETTLE = 3;
    private static final String[] PHASES = {"whole", "none", "no depth test", "no normal test", "no ID test"};

    private final CgPostMaterial outline;
    private final CgMaterial pass;
    private final List<CgAnimInstance> held;
    private final CgRenderStage.Registration reader;
    private volatile byte[] stencil;
    private final byte[][] pictures = new byte[PHASES.length][];
    private boolean readStencil;
    private int phase = -1, wait, w, h;
    private boolean done;

    /** Where the camera stands: close, since the game's normal test fades out by 5 m (OUTLINE.md, F(d)). */
    private final float eyeX, eyeY, eyeZ;

    SkinnedOutlineCheck(CgPostMaterial outline, List<CgAnimInstance> held, double x, double z, float height) {
        this.eyeX = (float) x;
        this.eyeY = height * 0.7f;
        this.eyeZ = (float) z + 1.8f;
        this.outline = outline;
        this.pass = outline.material();
        this.held = held;
        this.reader = CgRenderStage.WORLD_TRANSPARENT.register(CgWorldRenderer.ORDER + 1, stage -> {
            CgGraphTexture mask = stage.resources().get(CgFrameKeys.CUSTOM_DEPTH);
            if (!readStencil || mask == null) return;
            readStencil = false;
            stage.recording().readback(mask, 0, 0, 0, mask.getWidth(), mask.getHeight(), d -> stencil = copy(d, mask.getWidth() * mask.getHeight()));
        });
    }

    boolean done() {
        return done;
    }

    /** After the frame drew: holds Goku, steps the phases, reads each picture, and reports once all came back. */
    void frame(HarnessContext ctx) {
        if (done) return;
        if (phase < 0) {
            for (CgAnimInstance instance : held) instance.playing(false);
            ctx.getCamera3D().moveCamera(eyeX, eyeY, eyeZ);
            ctx.getCamera3D().setYaw(0f);
            ctx.getCamera3D().setPitch(-5f);
            w = ctx.getScreenWidth();
            h = ctx.getScreenHeight();
            enter(0);
            return;
        }
        if (--wait > 0) return;
        if (pictures[phase] == null && wait == 0) {
            int at = phase, fb = ctx.getTargetFramebuffer();
            CgReadback.pixels(fb, 0, 0, w, h, CgTextureType.RGBA8, d -> pictures[at] = copy(d, w * h * 4));
            if (phase == 0) readStencil = true;
            return;
        }
        if (pictures[phase] == null) return;
        if (phase + 1 < PHASES.length) enter(phase + 1);
        else if (stencil != null) report();
    }

    private void enter(int next) {
        phase = next;
        wait = SETTLE;
        outline.weight(next == 1 ? 0f : 1f);
        tests(pass, next != 2, next != 3, next != 4);
    }

    /**
     * Turns the outline's three edge tests on or off, each at the kit's values when on. Off is a threshold it cannot
     * reach: the decisions at 0 make 1 / D and 1 / N infinite, and the ID test's gain 0 with thresholds past any ID
     * leaves its laplacian below them.
     */
    static void tests(CgMaterial pass, boolean depth, boolean normal, boolean id) {
        set(pass, b -> b.vec4("D_Key0", depth ? 1f : 0f, 0f, 0f, 0f).vec4("D_Key1", depth ? 0.2f : 0f, 200f, 0f, 0f)
                .vec4("D_Key2", depth ? 0.005f : 0f, 4000f, 0f, 0f));
        set(pass, b -> b.vec4("N_Key0", normal ? 3f : 0f, 0f, 0f, 0f).vec4("N_Key1", normal ? 2f : 0f, 200f, 0f, 0f));
        set(pass, b -> b.set1f("Tex_Entering", id ? 5f : 0f)
                .vec4("Tex_Threshold", id ? 1f : 1e6f, id ? 2f : 1e6f, 0f, 1000f));
    }

    private static void set(CgMaterial pass, Consumer<CgShaderBindings> values) {
        pass.applyProperties(values::accept);
    }

    private void report() {
        done = true;
        reader.close();
        for (CgAnimInstance instance : held) instance.playing(true);
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name(), fail = null;
        if (GlErrorChecker.checkAndLog("outline-check")) fail = "GL errors, logged above";
        else if (PlatformServiceHarness.validationErrors() > 0) fail = "Vulkan validation errors, logged above";
        byte[] none = pictures[1];
        int[] counts = new int[PHASES.length];
        int offStencil = 0, lighter = 0;
        for (int p = 0; p < PHASES.length; p++) {
            if (p == 1) continue;
            for (int i = 0; i < w * h; i++) {
                if (!differs(pictures[p], none, i)) continue;
                counts[p]++;
                if (p != 0) continue;
                if ((stencil[i] & 0xFF) != 1) offStencil++;
                if (luma(pictures[0], i) > luma(none, i)) lighter++;
            }
        }
        System.out.printf("[skinned-play] outline: %d pixels whole; %d without the depth test, %d without the normal test,"
                + " %d without the ID test; %d off Goku's stencil, %d lighter%n", counts[0], counts[2], counts[3], counts[4],
                offStencil, lighter);
        if (fail == null && counts[0] < 100) fail = "the outline drew " + counts[0] + " pixels";
        else if (fail == null && offStencil > 0) fail = offStencil + " outline pixels off Goku's stencil";
        else if (fail == null && lighter > counts[0] / 100) fail = lighter + " outline pixels lighter than the scene";
        for (int p = 2; p < PHASES.length && fail == null; p++) {
            if (counts[p] >= counts[0]) fail = "turning off the " + PHASES[p].substring(3) + " changed nothing: it draws no edge";
        }
        System.out.println(fail == null ? "[skinned-play] outline PASS on " + on : "[skinned-play] outline FAIL on " + on + ": " + fail);
    }

    private static boolean differs(byte[] a, byte[] b, int i) {
        for (int c = 0; c < 3; c++) if (Math.abs((a[i * 4 + c] & 0xFF) - (b[i * 4 + c] & 0xFF)) > 4) return true;
        return false;
    }

    private static int luma(byte[] p, int i) {
        return (p[i * 4] & 0xFF) * 3 + (p[i * 4 + 1] & 0xFF) * 6 + (p[i * 4 + 2] & 0xFF);
    }

    private static byte[] copy(ByteBuffer data, int n) {
        byte[] out = new byte[n];
        data.get(0, out);
        return out;
    }
}
