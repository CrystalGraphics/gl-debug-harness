package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.host.CgHostFrame;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.harness.util.ObjectDraws;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.demo.CgLightsDemo;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessTarget;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.variable.CgVars;
import com.crystalgraphics.vfx.CgVfx;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import com.crystalgraphics.platform.rhi.format.CgFormat;

/**
 * The lights demo ({@code CgLightsDemo}) at midnight on a flat world under a night's lightmap, seen from in front of its
 * arena, captured with each of the world lights' later passes left out in turn: everything, the lit air off, every
 * shadow off, the transparent volume off, all three off. Prints each capture's mean brightness, its share of saturated
 * pixels and the mean round the mirror ball, to tell which pass brightens the show.
 *
 * <p>Steady ({@code lights-demo-steady}), it draws every pass on and the show held at one moment until closed: a run to
 * profile, the particles stepped by {@code fixedDelta}, more lights added by the demo's sweep.
 * {@code -Dcrystalgraphics.harness.lightsDemo.capture=<frame>} writes the picture that many frames after the show
 * starts, {@code steady-<frame>.png}, to compare a change against. Steady, the show starts at frame
 * {@link #STEADY_START} (or once warm, if later), so its particles and the frame index the shadows' noise is keyed by
 * are the same in every run.</p>
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-demo-bisect --width=960 --height=540"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-demo-steady --width=2560 --height=1440" \
 *     -Dcrystalgraphics.harness.fixedDelta=0.0166667 -Dcrystalgraphics.harness.profile=600 \
 *     -Dcrystalgraphics.harness.profile.warmup=900 -Dcrystalgraphics.trace.channels=crystalgraphics,gpu \
 *     -Dcrystalgraphics.demo.lightsSweep=100 -Dcrystalgraphics.demo.lightsSweepKind=scatter
 * }</pre>
 */
public class CgLightsDemoBisectScene implements InteractiveSceneLifecycle {

    private static final int PHASE = 180, GROUND = 64;
    private static final double SHOW_AT = 4.0;
    /**
     * {@code -Dcrystalgraphics.harness.lightsDemo.look=down}: standing at a player's eye height looking steeply down at
     * the lit floor, inside the carousel unless {@code .downAt=<x>,<z>} says where. Every pixel is lit ground, which is
     * what the show costs most.
     */
    private static final boolean DOWN = "down".equals(System.getProperty("crystalgraphics.harness.lightsDemo.look"));
    private static final String[] DOWN_AT = System.getProperty("crystalgraphics.harness.lightsDemo.downAt", "0,5").split(",");
    private static final double X = 0, Z = 0, EYE_X = DOWN ? Double.parseDouble(DOWN_AT[0]) : 0, EYE_Y = GROUND + (DOWN ? 1.62 : 4),
            EYE_Z = DOWN ? Double.parseDouble(DOWN_AT[1]) : 24;
    /** The mirror ball: CgLightsDemo's, ten blocks over the arena's middle and two behind it. */
    private static final double[] BALL = {X, GROUND + 10, Z - 2};
    /**
     * {@code .yawPitch=<yaw>,<pitch>} with {@code look=down}: the camera turned as Minecraft's F3 reads it (yaw 0 toward
     * +z, -90 toward +x; pitch down from level), for a view a player stood in; absent, straight down.
     */
    private static final String YAW_PITCH = System.getProperty("crystalgraphics.harness.lightsDemo.yawPitch", "");
    private static final String[] VARS = {"crystalgraphics.world.lightsAir", "crystalgraphics.world.lightsShadows",
            "crystalgraphics.world.lightsVolume"};
    /** Per phase, which of VARS are on. */
    private static final boolean[][] PHASES = {{true, true, true}, {false, true, true}, {true, false, true},
            {true, true, false}, {false, false, false}};
    private static final String[] NAMES = {"all", "no-air", "no-shadows", "no-volume", "none"};
    private static final int CAPTURE = Integer.getInteger("crystalgraphics.harness.lightsDemo.capture", -1);
    /** {@code .sway=<degrees>}: the view turning that far either way about a second, as a player's hand on the mouse. */
    private static final float SWAY = (float) Math.toRadians(Double.parseDouble(
            System.getProperty("crystalgraphics.harness.lightsDemo.sway", "0")));
    private static final int STEADY_START = 600;

    private final CgLightsDemo demo = new CgLightsDemo(CgVfx.owner("harness"));
    private final boolean steady;
    private final Matrix4f view = DOWN && !YAW_PITCH.isEmpty() ? turned(YAW_PITCH)
            : DOWN ? new Matrix4f().lookAt(0f, 0f, 0f, 0f, (float) (GROUND - EYE_Y), -0.5f, 0f, 1f, 0f)
            : new Matrix4f().lookAt(0f, 0f, 0f, (float) (X - EYE_X), (float) (GROUND + 4 - EYE_Y), (float) (Z - 2 - EYE_Z),
                    0f, 1f, 0f);
    private final Matrix4f projection = new Matrix4f(), ground = new Matrix4f(), swayed = new Matrix4f();
    private HarnessTarget target;
    private CgMaterial floor;
    private CgTexture2D lightmap;
    private int width, height, frame, warmAt = -1;
    private boolean running = true;

    public CgLightsDemoBisectScene() {
        this(false);
    }

    public CgLightsDemoBisectScene(boolean steady) {
        this.steady = steady;
    }

    /** The view along Minecraft's yaw and pitch, in degrees: its Entity.calculateViewVector. */
    private static Matrix4f turned(String yawPitch) {
        String[] parts = yawPitch.split(",");
        double yaw = Math.toRadians(Double.parseDouble(parts[0])), pitch = Math.toRadians(Double.parseDouble(parts[1]));
        float x = (float) (-Math.sin(yaw) * Math.cos(pitch)), y = (float) -Math.sin(pitch),
                z = (float) (Math.cos(yaw) * Math.cos(pitch));
        return new Matrix4f().lookAt(0f, 0f, 0f, x, y, z, 0f, 1f, 0f);
    }

    @Override
    public void init(HarnessContext ctx) {
        HarnessFlatWorld.provide(GROUND, 15);
        floor = CgMaterial.load("assets/harness/shader/bloom_wall.shader");
        demo.precache();
        width = ctx.getScreenWidth();
        height = ctx.getScreenHeight();
        target = HarnessTarget.create(width, height, true);
        // A night's lightmap: block light along u as Minecraft's, the sky's moonlight a faint blue.
        ByteBuffer texels = ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.nativeOrder());
        for (int sky = 0; sky < 16; sky++) {
            for (int block = 0; block < 16; block++) {
                float b = 0.03f + 0.97f * block / 15f, s = 0.05f * sky / 15f;
                texels.put((byte) Math.round(255 * Math.min(1f, b + s))).put((byte) Math.round(255 * Math.min(1f, b + s)))
                        .put((byte) Math.round(255 * Math.min(1f, b + s * 1.6f))).put((byte) 255);
            }
        }
        texels.flip();
        lightmap = CgTextures.of("harness.lightsDemoBisect", CgTextureDesc.of(16, 16, CgFormat.RGBA8_UNORM));
        lightmap.uploadRegion(0, 0, 0, 16, 16, texels);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (warmAt < 0 && demo.ready() && (!steady || frame >= STEADY_START)) warmAt = frame;
        int phase = warmAt < 0 || steady ? -1 : (frame - warmAt) / PHASE;
        if (phase >= 0 && phase < PHASES.length) {
            for (int v = 0; v < VARS.length; v++) ((CgVar.Bool) CgVars.get(VARS[v])).set(PHASES[phase][v]);
        }
        CgCamera.perspectiveMatrix(projection, 70f, (float) width / height, 0.05f, 400f);
        target.bind();
        target.clear(0.01f, 0.01f, 0.03f, 1f);
        Matrix4f view = SWAY == 0f ? this.view
                : swayed.rotationY(SWAY * (float) Math.sin(frame * 2.0 * Math.PI / 60.0)).mul(this.view);
        CgCamera host = new CgCamera();
        host.view().set(view);
        host.projection().set(projection);
        host.resolution(width, height).time(CgFrameClock.seconds());
        ground.translation((float) (X - EYE_X), (float) (GROUND - 0.5 - EYE_Y), (float) (Z - EYE_Z)).scale(400f, 1f, 400f);
        CgMesh cube = CgMeshShapes.cube();
        ObjectDraws.draw(host, floor, cube, 1, (i, data, at) -> ObjectDraws.object(data, at, ground, 0.35f, 0.4f, 0.3f, 1f));
        // One moment of the show for every capture, between its lightning's flashes: only the passes differ.
        if (warmAt >= 0) demo.submit(CgScene.client(), X, GROUND, Z, SHOW_AT);
        CgHostFrame hostFrame = CgStageHost.frame(CgStageHost.WORLD_OPAQUE);
        hostFrame.set(0f, width, height, target.external()).camera().set(EYE_X, EYE_Y, EYE_Z, view, projection);
        hostFrame.environment().sun(0.5f, 0, 0f, 1f);
        hostFrame.textures().importedLightmap(lightmap.rhiTexture(), 16, 16);
        HarnessWorld.fireCallbacks();
        CgFrameRing.endFrame();
        if (phase >= 0 && phase < PHASES.length && (frame - warmAt) % PHASE == PHASE - 1) {
            target.captureToFile(ctx.getOutputDir(), NAMES[phase] + ".png");
        }
        if (steady && warmAt >= 0 && frame - warmAt == CAPTURE) target.captureToFile(ctx.getOutputDir(), "steady-" + CAPTURE + ".png");
        target.unbind();
        if (phase == PHASES.length) {
            for (String v : VARS) ((CgVar.Bool) CgVars.get(v)).set(true);
            report(ctx);
            running = false;
        }
    }

    private void report(HarnessContext ctx) {
        for (String name : NAMES) {
            try {
                BufferedImage image = ImageIO.read(new File(ctx.getOutputDir(), name + ".png"));
                double sum = 0, ball = 0;
                int saturated = 0, ballN = 0;
                int[] at = project(BALL);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int rgb = image.getRGB(x, y);
                        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
                        float l = (r + g + b) / 3f;
                        sum += l;
                        if (Math.max(r, Math.max(g, b)) >= 250) saturated++;
                        if (Math.abs(x - at[0]) <= 60 && Math.abs(y - at[1]) <= 60) {
                            ball += l;
                            ballN++;
                        }
                    }
                }
                System.out.printf("[lights-demo-bisect] %-10s mean %.1f, saturated %.1f%%, round the ball %.1f%n", name,
                        sum / (width * height), 100.0 * saturated / (width * height), ballN > 0 ? ball / ballN : -1.0);
            } catch (IOException e) {
                System.out.println("[lights-demo-bisect] " + name + " did not read back: " + e.getMessage());
            }
        }
    }

    private int[] project(double[] p) {
        Vector4f clip = new Vector4f((float) (p[0] - EYE_X), (float) (p[1] - EYE_Y), (float) (p[2] - EYE_Z), 1f);
        view.transform(clip);
        projection.transform(clip);
        return new int[]{(int) ((clip.x / clip.w * 0.5f + 0.5f) * width), height - 1 - (int) ((clip.y / clip.w * 0.5f + 0.5f) * height)};
    }

    @Override
    public void dispose() {
        demo.delete();
        if (target != null) target.delete();
        CgPlatform.provide(CgLevels.HOST, null);
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
