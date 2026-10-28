package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.harness.camera.Camera3D;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.post.CgPostMaterial;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * OL5's gate in {@code skinned-play} ({@code -Dcrystalgraphics.harness.skinnedPlay.outlineCheck=true}, with
 * {@code -Dcrystalgraphics.harness.mainTarget=true}): Kakarot's battle outline on Goku held still, the picture read back
 * with the outline whole, without it, and with each of its three edge tests off in turn, and Goku's CustomStencil. Every
 * pixel the outline changes must be Goku's (stencil 1) and darker, and each test must draw some of it.
 */
final class SkinnedOutlineCheck {

    /** Frames each phase is held before its picture is read: the outline's properties land at the next record. */
    private static final int SETTLE = 3;
    /** Frames the viewport has kept its size: the phases start once it has for {@link #SETTLE}. */
    private int steady;
    private static final String[] PHASES = {"whole", "none", "no depth test", "no normal test", "no ID test", "keyed",
            "restored"};
    private static final int KEYED = 5, RESTORED = 6;
    /**
     * An {@code ATAnimNotifyState_OutlineColor} section with {@code SetColor} on and {@code Restore} on, in the kit's
     * form, keyed red on the caster for two seconds. No kit move keys one today: each of its 108 has {@code SetColor} off.
     */
    private static final String KEYED_RED = "_v: 1\ntick_resolution: 30\ndisplay_rate: 30\nrange: [0, 60]\n"
            + "roles:\n  - { id: r1, name: caster, kind: caster, seed: true }\ntracks:\n"
            + "  - { id: t1, kind: material, binding: { role: caster }, sections: [ { id: s1, range: [0, 60],"
            + " completion: restore_state, colours: { outline: { keys: [[0, [1.0, 0.0, 0.0, 1.0], constant]] } } } ] }\n";

    private final CgPostMaterial outline;
    private final CgMaterial pass;
    private final List<CgAnimInstance> held;
    private final CgRenderStage.Registration reader;
    private volatile byte[] stencil;
    /** The custom data's packed x per pixel: 0 where no writer's redraw landed. */
    private volatile float[] packed;
    private final byte[][] pictures = new byte[PHASES.length][];
    private boolean readStencil;
    private int phase = -1, wait, w, h;
    private boolean done;

    /** Where the camera stands: close, since the game's normal test fades out by 5 m (OUTLINE.md, F(d)). */
    private final float eyeX, eyeY, eyeZ;
    private float posedY;

    private final Function<CgSequenceDefinition, CgSequencePlayer> play;
    private CgSequencePlayer keyed;

    SkinnedOutlineCheck(CgPostMaterial outline, List<CgAnimInstance> held, double x, double z, float height,
                        Function<CgSequenceDefinition, CgSequencePlayer> play) {
        this.play = play;
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
            CgGraphTexture data = stage.resources().get(CgFrameKeys.CUSTOM_DATA);
            if (data != null) {
                int n = data.getWidth() * data.getHeight();
                stage.recording().readback(data, 0, 0, 0, data.getWidth(), data.getHeight(), d -> {
                    float[] got = new float[n];
                    for (int i = 0; i < n; i++) got[i] = d.getFloat(i * 8);
                    packed = got;
                });
            }
        });
    }

    boolean done() {
        return done;
    }

    /** After the frame drew: holds Goku, steps the phases, reads each picture, and reports once all came back. */
    void frame(HarnessContext ctx) {
        if (done) return;
        // A window still taking its size (Vulkan's swapchain, a maximise) would compare pictures of different frames.
        if (ctx.getScreenWidth() != w || ctx.getScreenHeight() != h) {
            if (phase >= 0) System.out.println("[skinned-play] outline check: the window resized, started again");
            w = ctx.getScreenWidth();
            h = ctx.getScreenHeight();
            steady = 0;
            phase = -1;
            Arrays.fill(pictures, null);
            stencil = null;
            return;
        }
        if (phase < 0) {
            if (++steady < SETTLE) return;
            for (CgAnimInstance instance : held) instance.playing(false);
            pose(ctx);
            enter(0);
            return;
        }
        // Held every frame: the camera takes mouse and keys each frame, so a stray delta would move what is compared.
        if (!posed(ctx)) wait = SETTLE;
        pose(ctx);
        if (phase == RESTORED && !keyed.finished()) return;
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

    private void pose(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(eyeX, eyeY, eyeZ);
        ctx.getCamera3D().setYaw(0f);
        ctx.getCamera3D().setPitch(-5f);
        posedY = ctx.getCamera3D().getPosY();   // the camera's own floor may lift it
    }

    /** Whether this frame was drawn from the check's eye. */
    private boolean posed(HarnessContext ctx) {
        Camera3D c = ctx.getCamera3D();
        return c.getPosX() == eyeX && c.getPosY() == posedY && c.getPosZ() == eyeZ && c.getYaw() == 0f && c.getPitch() == -5f;
    }

    private void enter(int next) {
        phase = next;
        wait = SETTLE;
        outline.weight(next == 1 ? 0f : 1f);
        tests(pass, next != 2, next != 3, next != 4);
        if (next == KEYED) keyed = play.apply(compile(KEYED_RED));
    }

    private static CgSequenceDefinition compile(String yaml) {
        return CgSequenceDefinition.COMPILER.compile(CgAssetData.of("harness:outline_keyed", CgSequenceKinds.SEQUENCE,
                (Map<?, ?>) CgAssetYaml.read(yaml), null), (kind, id) -> null);
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
        // A hole: Goku's stencil with no custom data, where the writers' redraw failed the depth its colour draw wrote
        int goku = 0, holes = 0;
        for (int i = 0; i < w * h && packed != null; i++) {
            if ((stencil[i] & 0xFF) != 1) continue;
            goku++;
            if (packed[i] == 0f) holes++;
        }
        System.out.printf("[skinned-play] outline: %d pixels whole; %d without the depth test, %d without the normal test,"
                + " %d without the ID test; %d off Goku's stencil, %d lighter; custom data missing on %s of Goku's %d%n",
                counts[0], counts[2], counts[3], counts[4], offStencil, lighter, packed == null ? "?" : holes, goku);
        if (fail == null && packed == null) fail = "no custom data read back";
        // Framed from 1.8 m, Goku covers about a tenth of the frame; much less and the edges compared are few.
        if (fail == null && goku < w * h / 20) fail = "Goku covers " + goku + " pixels of " + w * h + ": not framed";
        if (fail == null && holes > 0) fail = holes + " of Goku's pixels have no custom data";
        if (fail == null && counts[0] < 100) fail = "the outline drew " + counts[0] + " pixels";
        else if (fail == null && offStencil > 0) fail = offStencil + " outline pixels off Goku's stencil";
        else if (fail == null && lighter > counts[0] / 100) fail = lighter + " outline pixels lighter than the scene";
        // The normal test is not held to it: it fades out by 5 m, and at this camera depth and ID edges cover its own
        for (int p = 2; p < KEYED && fail == null; p++) {
            if (p != 3 && counts[p] >= counts[0]) fail = "turning off the " + PHASES[p].substring(3) + " changed nothing: it draws no edge";
        }
        // The keyed colour: the whole outline's pixels drawn red over the section; after it, the outline as it was
        int red = 0, changed = 0;
        for (int i = 0; i < w * h; i++) {
            if (differs(pictures[RESTORED], pictures[0], i)) changed++;
            if (!differs(pictures[0], none, i)) continue;
            int r = pictures[KEYED][i * 4] & 0xFF, g = pictures[KEYED][i * 4 + 1] & 0xFF, b = pictures[KEYED][i * 4 + 2] & 0xFF;
            if (r > 2 * g + 20 && r > 2 * b + 20) red++;
        }
        System.out.printf("[skinned-play] outline keyed red: %d of %d outline pixels red; %d pixels unlike the outline after"
                + " the section%n", red, counts[0], changed);
        if (fail == null && red < counts[0] / 2) fail = "a keyed outline colour drew " + red + " of " + counts[0] + " pixels red";
        else if (fail == null && changed > counts[0] / 100) fail = changed + " pixels unlike the outline after its section ended";
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
