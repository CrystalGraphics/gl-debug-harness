package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.render.CgVfxSortGroup;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * G0.7's gate, a shared sort group ({@link CgVfxSortGroup}): two effects' layers keep their authored order whichever is
 * nearer the camera. Three columns, each a red ball (order hint 10) and a blue one (hint 20) one behind the other: in a
 * group with red nearer, in a group with blue nearer, and with red nearer in no group. Grouped, blue draws last both
 * times; the control, sorted by distance, ends red.
 *
 * <p>Prints {@code [vfx-sort-group] PASS} or {@code FAIL}; run it on {@code gl}, {@code mac41} and {@code vulkan}.</p>
 */
public class CgVfxSortGroupScene implements HarnessSceneLifecycle {

    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("harness:sort-group");
    private static final CgVfxSlot BALL = CgVfxSlot.mesh("harness:sort-group/ball");
    private static final float[] RED = {0.9f, 0.2f, 0.2f}, BLUE = {0.2f, 0.3f, 0.9f};
    private static final CgVfxLook FIRST = look("red", RED, 10), SECOND = look("blue", BLUE, 20);
    /** Each column's middle, in normalized device x. */
    private static final float[] COLUMNS = {-0.6f, 0f, 0.6f};
    private static final String[] NAMES = {"grouped, red nearer", "grouped, blue nearer", "ungrouped, red nearer"};
    private static final boolean[] BLUE_DUE = {true, true, false};

    private final CgVfxSystem vfx = new CgVfxSystem();

    private static CgVfxLook look(String name, float[] c, int hint) {
        return CgVfxLook.builder(SCHEMA).layer(CgVfxLayer.of("harness:shader/sort_group.shader").on(BALL).order(hint)
                .set("_Color", c[0], c[1], c[2], 1f).named(name).build()).build();
    }

    /** A ball of {@code radius} at its origin, drawn for ever. */
    private static final class Ball extends CgVfxEffect<Ball> {
        private final Matrix4f transform = new Matrix4f();
        private final float radius;

        Ball(CgVfxLook look, double x, double y, double z, float radius) {
            super(look, x, y, z);
            this.radius = radius;
        }

        @Override
        protected void tick(float dt) {
        }

        @Override
        protected void submit(CgVfxFrame frame) {
            frame.mesh(this, BALL, 0f, 0f, 0f, transform.scaling(radius), 0f, 0f, 0f, 0f);
        }
    }

    @Override
    public void init(HarnessContext ctx) {
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        float aspect = (float) w / h;
        for (int c = 0; c < COLUMNS.length; c++) {
            CgVfxSortGroup group = c < 2 ? new CgVfxSortGroup() : null;
            float redAt = c == 1 ? 8f : 4f, blueAt = c == 1 ? 4f : 8f, x = COLUMNS[c] * aspect;
            vfx.play(new Ball(FIRST, x * redAt, 0, -redAt, 0.2f * redAt).sortGroup(group));
            vfx.play(new Ball(SECOND, x * blueAt, 0, -blueAt, 0.2f * blueAt).sortGroup(group));
        }
        HarnessFboHelper target = HarnessFboHelper.create(w, h, false);
        target.bind();
        try {
            target.clear(0f, 0f, 0f, 1f);
            for (int i = 1; i <= 4; i++) vfx.update(i / 60.0);
            vfx.submit(CgWorldRenderer.get());
            Matrix4f view = new Matrix4f();
            Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(90), (float) w / h, 0.1f, 100f);
            for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
                stage.host().set(0f, w, h, target.getFboId()).view().set(0, 0, 0, view, projection);
                stage.fire();
            }
            target.captureToFile(ctx.getOutputDir(), "vfx-sort-group.png");
        } finally {
            target.unbind();
            target.delete();
        }
        report(ctx, GlErrorChecker.checkAndLog("vfx-sort-group"));
    }

    private void report(HarnessContext ctx, boolean glErrors) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        if (glErrors) {
            System.out.println("[vfx-sort-group] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        int validation = PlatformServiceHarness.validationErrors();
        if (validation > 0) {
            System.out.println("[vfx-sort-group] FAIL on " + on + ": " + validation
                    + " Vulkan validation errors, logged above");
            return;
        }
        if ("recording".equals(on)) {
            System.out.println("[vfx-sort-group] PASS on recording: every command validated; nothing drawn to compare");
            return;
        }
        BufferedImage picture;
        try {
            picture = ImageIO.read(new File(ctx.getOutputDir(), "vfx-sort-group.png"));
        } catch (IOException e) {
            System.out.println("[vfx-sort-group] FAIL: the capture could not be read: " + e);
            return;
        }
        int w = picture.getWidth(), h = picture.getHeight();
        for (int c = 0; c < COLUMNS.length; c++) {
            int rgb = picture.getRGB(Math.round((COLUMNS[c] * 0.5f + 0.5f) * w), h / 2);
            int r = rgb >> 16 & 0xFF, b = rgb & 0xFF;
            boolean drawn = r > 40 || b > 40, blue = b > r;
            if (!drawn || blue != BLUE_DUE[c]) {
                System.out.println("[vfx-sort-group] FAIL on " + on + ": " + NAMES[c] + " ends (" + r + ", "
                        + (rgb >> 8 & 0xFF) + ", " + b + "), where " + (BLUE_DUE[c] ? "blue" : "red") + " is due");
                return;
            }
        }
        System.out.println("[vfx-sort-group] PASS on " + on + ": in a group the blue layer draws last whichever ball is "
                + "nearer; out of one, the nearer draws last");
    }

    @Override
    public void dispose() {
        vfx.delete();
    }
}
