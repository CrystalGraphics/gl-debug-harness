package com.crystalgraphics.harness.scene.test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;
import javax.imageio.ImageIO;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.capture.HarnessCapture;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.DeviceErrors;
import com.crystalgraphics.harness.util.HarnessTarget;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.CgWarmBudget;
import com.crystalgraphics.render.host.CgHostFrame;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.post.CgPostRenderer;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.precache.CgPipelinePrecache;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.scene.renderer.feature.ao.CgAmbientOcclusion;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;

/**
 * R13a's gate: screen-space ambient occlusion by both of Unreal's methods. A floor, a tall wall and a box, Default Lit
 * under a grey sky and no sun, so the sky's diffuse alone lights them and the occlusion scales it. Drawn with it off at
 * nine albedos (a pixel's value against the light it took, through the tonemapper), then by SSAO and by GTAO at full
 * intensity, each point's occlusion read back off that curve: the open floor stays lit, the near of the wall and the
 * box's side darken, and GTAO 40 cm from the wall stays within 0.15 of a ray-cast, pixel by pixel (the cosine-weighted
 * hemisphere of each floor point against the wall, each direction's wall counted by GTAO's falloff over its 2 m reach:
 * 1 - 2 d^2 / R^2, nothing past R over root 2). Then a close-up: a sphere filling the screen, whose face toward the eye has nothing near
 * it, so both methods must leave it open though their reach runs far off the screen. Then the Low tier, which runs none.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=ambient-occlusion --rhi=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [ambient-occlusion] PASS} or {@code FAIL}; on Vulkan a validation error fails it. Its captures land in
 * its output directory.</p>
 */
public final class CgAmbientOcclusionTestScene implements HarnessSceneLifecycle {

    private static final int W = 480, H = 270, FRAMES = 6, TIMING_W = 2560, TIMING_H = 1440, WARMUP = 30, TIMED = 60;
    private static final long WARM_NANOS = 30_000_000_000L;
    /** ambient_occlusion_method's names, by value. */
    private static final String[] METHODS = {"SSAO", "GTAO", "CACAO"};
    private static final float FLOOR = 64f, ALBEDO = 0.8f, WALL_FACE = -4f, REACH = 2f;
    private static final float[] EYE = {0f, 67f, 5f}, AT = {0f, 64f, -2f};
    private static final float[] OPEN = {-1.8f, FLOOR, 1f}, NEAR_WALL = {-1.5f, FLOOR, -3.6f}, BOX_SIDE = {1.45f, FLOOR, -0.5f};
    private static final float[] SHARES = {0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f};
    /** The close-up: a sphere of radius 1 on the line of sight, its near face 1.2 m away, so it fills the screen. */
    private static final float CLOSE_CENTRE = 2.2f, CLOSE_FACE = 1.2f;

    private final Matrix4f projection = CgCamera.perspectiveMatrix(new Matrix4f(), 50f, (float) W / H, 0.05f, 200f);
    private final Matrix4f look = new Matrix4f().lookAt(0f, 0f, 0f, AT[0] - EYE[0], AT[1] - EYE[1], AT[2] - EYE[2], 0f, 1f, 0f);
    private CgMaterial material;
    private CgTexture2D lightmap;

    @Override
    public void init(HarnessContext ctx) {
        material = CgMaterial.load("assets/harness/shader/ao_surface.shader");
        ByteBuffer grey = ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 16 * 16; i++) grey.put((byte) 128).put((byte) 128).put((byte) 128).put((byte) 255);
        lightmap = CgTextures.of("harness.ambientOcclusion", CgTextureDesc.of(16, 16, CgFormat.RGBA8_UNORM));
        lightmap.uploadRegion(0, 0, 0, 16, 16, grey.flip());
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgPostRenderer.install();
        CgPipelinePrecache.Request warm = CgPipelinePrecache.request(p -> p.add(material, false));
        long until = System.nanoTime() + WARM_NANOS;
        while (!(warm.ready() && CgAmbientOcclusion.ready()) && System.nanoTime() < until) {
            LockSupport.parkNanos(1_000_000L);
            CgWarmBudget.nextFrame();
        }
        CgView main = CgView.main();
        CgPostSettings settings = main.post().settings();
        float bloom = settings.bloomIntensity();
        settings.bloomIntensity(0f).ambientOcclusion(1f, CgPostSettings.DEFAULT_AO_RADIUS);
        boolean playerAo = CgGraphicsSettings.AMBIENT_OCCLUSION.get();
        float method = CgGraphicsSettings.AMBIENT_OCCLUSION_METHOD.get();
        CgQuality quality = CgGraphicsSettings.QUALITY.get();
        CgGraphicsSettings.AMBIENT_OCCLUSION.set(true);
        CgGraphicsSettings.QUALITY.set(CgQuality.HIGH);
        List<String> failures = new ArrayList<>();
        try {
            float[][] curve = new float[3][SHARES.length];
            BufferedImage[] offs = new BufferedImage[SHARES.length];
            for (int i = 0; i < SHARES.length; i++) {
                BufferedImage image = offs[i] = run(false, 0, SHARES[i]);
                if (SHARES[i] == 1f) save(ctx, "off", image);
                curve[0][i] = patch(image, OPEN);
                curve[1][i] = patch(image, NEAR_WALL);
                curve[2][i] = patch(image, BOX_SIDE);
            }
            BufferedImage[] methods = {run(true, 0, 1f), run(true, 1, 1f), run(true, 2, 1f)};
            BufferedImage gtao = methods[1];
            for (int m = 0; m < METHODS.length; m++) save(ctx, METHODS[m].toLowerCase(Locale.ROOT), methods[m]);
            CgGraphicsSettings.QUALITY.set(CgQuality.LOW);
            BufferedImage low = run(true, 1, 1f);
            save(ctx, "low", low);

            StringBuilder readings = new StringBuilder();
            for (int m = 0; m < METHODS.length; m++) {
                BufferedImage image = methods[m];
                String name = METHODS[m];
                float open = occlusion(curve[0], patch(image, OPEN)), near = occlusion(curve[1], patch(image, NEAR_WALL));
                float side = occlusion(curve[2], patch(image, BOX_SIDE));
                readings.append(String.format(Locale.ROOT, "%s open %.2f, near the wall %.2f, the box's side %.2f; ", name, open, near, side));
                if (open < 0.9f) failures.add(name + " darkens the open floor: " + open);
                if (near > 0.8f) failures.add(name + " leaves the floor near the wall lit: " + near);
                if (side > 0.85f) failures.add(name + " leaves the box's side lit: " + side);
            }
            float[] cast = castNearWall(offs, gtao);
            readings.append(String.format(Locale.ROOT, "GTAO near the wall %.3f, a ray-cast %.3f; ", cast[0], cast[1]));
            if (Math.abs(cast[0] - cast[1]) > 0.15f) failures.add("GTAO near the wall is " + cast[0] + ", a ray-cast " + cast[1]);
            // The close-up at High: Low, drawn above, runs none
            CgGraphicsSettings.QUALITY.set(CgQuality.HIGH);
            float[] face = closeFace(), closeCurve = new float[SHARES.length];
            for (int i = 0; i < SHARES.length; i++) closeCurve[i] = patch(render(false, 0, SHARES[i], W, H, FRAMES, -1, true), face);
            for (int m = 0; m < METHODS.length; m++) {
                String name = METHODS[m];
                BufferedImage image = render(true, m, 1f, W, H, FRAMES, -1, true);
                save(ctx, "close-" + name.toLowerCase(Locale.ROOT), image);
                float open = occlusion(closeCurve, patch(image, face));
                readings.append(String.format(Locale.ROOT, "%s close-up face %.2f; ", name, open));
                if (open < 0.9f) failures.add(name + " darkens a close-up's open face: " + open);
            }
            for (int m = 0; m < METHODS.length; m++) {
                for (int level = 2; level <= 4; level++) time(settings, m, level, false);
            }
            for (int level = 2; level <= 4; level++) time(settings, 2, level, true);
            settings.ambientOcclusionQuality(CgPostSettings.DEFAULT_AO_QUALITY);
            float lowNear = patch(low, NEAR_WALL), offNear = curve[1][SHARES.length - 1];
            readings.append(String.format(Locale.ROOT, "Low near the wall %.1f against off's %.1f", lowNear, offNear));
            if (Math.abs(lowNear - offNear) > 1.5f) failures.add("the Low tier occludes: " + lowNear + " against " + offNear);
            System.out.println("[ambient-occlusion] " + readings);
        } catch (IOException e) {
            failures.add("a capture could not be written: " + e);
        } finally {
            settings.bloomIntensity(bloom).ambientOcclusion(CgPostSettings.DEFAULT_AO_INTENSITY, CgPostSettings.DEFAULT_AO_RADIUS);
            main.showFlags().ambientOcclusion(true);
            CgGraphicsSettings.AMBIENT_OCCLUSION.set(playerAo);
            CgGraphicsSettings.AMBIENT_OCCLUSION_METHOD.set(method);
            CgGraphicsSettings.QUALITY.set(quality);
        }
        report(failures, DeviceErrors.checkAndLog("ambient-occlusion"));
    }

    /**
     * The pass's GPU time at a 1440p target, over TIMED frames: {@code method} at Unreal's shader {@code level}, CACAO's
     * downsampled presets where {@code downsampled}.
     */
    private void time(CgPostSettings settings, int method, int level, boolean downsampled) {
        boolean tracing = CgTrace.isEnabled(CgGpuTrace.GPU), wasDownsampled = CgGraphicsSettings.AMBIENT_OCCLUSION_DOWNSAMPLE.get();
        CgTrace.setEnabled(CgGpuTrace.GPU, true);
        try {
            if (!CgGpuTrace.isMeasuring()) return;
            CgGraphicsSettings.QUALITY.set(CgQuality.HIGH);
            CgGraphicsSettings.AMBIENT_OCCLUSION_DOWNSAMPLE.set(downsampled);
            settings.ambientOcclusionQuality(CgAmbientOcclusion.LEVEL_QUALITIES[level]);
            render(true, method, 1f, TIMING_W, TIMING_H, WARMUP + TIMED, WARMUP, false);
            long[] total = CgGpuTrace.totals().get("world.ambientOcclusion");
            double ms = total == null ? 0 : total[0] / 1e6 / TIMED;
            // Depth pyramids: GTAO builds its own, in a pass beside its kernels'
            long[] pyramids = CgGpuTrace.totals().get("ops.depthPyramid");
            double pyramidMs = pyramids == null ? 0 : pyramids[0] / 1e6 / TIMED;
            System.out.printf(Locale.ROOT, "[ambient-occlusion] %s %s%s, %dx%d: %.3f ms a frame, depth pyramids %.3f ms%n",
                    METHODS[method], CgAmbientOcclusion.LEVEL_NAMES[level], downsampled ? " downsampled" : "", TIMING_W,
                    TIMING_H, ms, pyramidMs);
        } finally {
            CgGraphicsSettings.AMBIENT_OCCLUSION_DOWNSAMPLE.set(wasDownsampled);
            CgTrace.setEnabled(CgGpuTrace.GPU, tracing);
        }
    }

    /** FRAMES frames of the floor, wall and box at {@code share} of the albedo; occluded by {@code method} or not. */
    private BufferedImage run(boolean occluded, int method, float share) {
        return render(occluded, method, share, W, H, FRAMES, -1, false);
    }

    /** The close-up sphere's face nearest the eye. */
    private static float[] closeFace() {
        return along(CLOSE_FACE);
    }

    /** The point {@code distance} from the eye toward where it looks. */
    private static float[] along(float distance) {
        Vector3f d = new Vector3f(AT[0] - EYE[0], AT[1] - EYE[1], AT[2] - EYE[2]).normalize(distance);
        return new float[] {EYE[0] + d.x, EYE[1] + d.y, EYE[2] + d.z};
    }

    /**
     * {@code frames} frames at {@code width} by {@code height}, the GPU's totals reset at {@code resetAt}; the last one.
     * The floor, wall and box, or the close-up's sphere alone.
     */
    private BufferedImage render(boolean occluded, int method, float share, int width, int height, int frames, int resetAt,
                                 boolean closeUp) {
        CgView main = CgView.main();
        main.showFlags().ambientOcclusion(occluded);
        CgGraphicsSettings.AMBIENT_OCCLUSION_METHOD.set((float) method);
        CgScene scene = CgScene.client();
        HarnessTarget target = HarnessTarget.create(width, height, true);
        CgMesh cube = CgMeshShapes.cube();
        float a = ALBEDO * share;
        List<CgMeshComponent> spawned = new ArrayList<>();
        if (closeUp) {
            float[] c = along(CLOSE_CENTRE);
            spawned.add(scene.mesh(CgMeshShapes.sphere(48, 96), material.instance()).at(c[0], c[1], c[2]).customData(0, a, a, a, 1f).spawn());
        } else {
            spawned.add(scene.mesh(cube, material.instance()).at(0, FLOOR - 0.1f, 0).scale(60f, 0.2f, 60f).customData(0, a, a, a, 1f).spawn());
            spawned.add(scene.mesh(cube, material.instance()).at(0, FLOOR + 3f, -4.2f).scale(60f, 6f, 0.4f).customData(0, a, a, a, 1f).spawn());
            spawned.add(scene.mesh(cube, material.instance()).at(2f, FLOOR + 0.5f, -0.5f).customData(0, a, a, a, 1f).spawn());
        }
        BufferedImage last = null;
        for (int f = 0; f < frames; f++) {
            if (f == resetAt) CgGpuTrace.resetTotals();
            target.bind();
            target.clear(0.02f, 0.02f, 0.03f, 1f);
            scene.update();
            CgHostFrame host = CgStageHost.frame(CgStageHost.WORLD_OPAQUE);
            host.set(0f, width, height, target.external()).camera().set(EYE[0], EYE[1], EYE[2], look, projection);
            host.environment().sun(Float.NaN, 0, 0f, 1f);
            host.textures().importedLightmap(lightmap.rhiTexture(), 16, 16);
            HarnessWorld.fireCallbacks();
            CgFrameRing.endFrame();
            if (resetAt >= 0) CgGpuTrace.collect();
            else if (f == frames - 1) last = HarnessCapture.image(target.external());
            target.unbind();
        }
        for (CgMeshComponent c : spawned) c.remove();
        target.delete();
        return last;
    }

    /**
     * Near the wall, each pixel's occlusion by GTAO (read off its own pixel's curve) and by a ray-cast of the
     * floor point it shows: their means.
     */
    private float[] castNearWall(BufferedImage[] offs, BufferedImage gtao) {
        Matrix4f inverse = new Matrix4f(projection).mul(look).invert();
        int[] c = pixel(NEAR_WALL);
        float measured = 0f, cast = 0f;
        int n = 0;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = c[0] + dx, y = c[1] + dy;
                float[] curve = new float[SHARES.length];
                for (int i = 0; i < SHARES.length; i++) curve[i] = value(offs[i], x, y);
                Vector3f ray = inverse.transformProject(new Vector3f((x + 0.5f) / W * 2f - 1f, 1f - (y + 0.5f) / H * 2f, 0.5f));
                float t = (FLOOR - EYE[1]) / ray.y;
                float fromWall = EYE[2] + ray.z * t - WALL_FACE;
                if (fromWall <= 0f) continue;
                measured += occlusion(curve, value(gtao, x, y));
                cast += cast(fromWall);
                n++;
            }
        }
        return new float[] {measured / Math.max(1, n), cast / Math.max(1, n)};
    }

    /**
     * The cosine-weighted share of the sky a floor point {@code d} from the wall sees (the wall taller than the reach):
     * a direction toward it is blocked by as much as GTAO's falloff counts the wall where it meets it, 1 - 2 d^2 / R^2.
     */
    private static float cast(float d) {
        int steps = 256;
        double open = 0, total = 0;
        for (int i = 0; i < steps; i++) {
            double theta = (i + 0.5) / steps * Math.PI * 0.5;
            for (int j = 0; j < steps; j++) {
                double phi = (j + 0.5) / steps * Math.PI * 2;
                double w = Math.cos(theta) * Math.sin(theta);
                double toward = Math.sin(theta) * Math.cos(phi);
                double blocked = 0;
                if (toward > 1.0e-6) {
                    double hit = d / toward;
                    blocked = Math.max(0, 1 - 2 * hit * hit / (REACH * REACH));
                }
                open += w * (1 - blocked);
                total += w;
            }
        }
        return (float) (open / total);
    }

    private static float value(BufferedImage image, int x, int y) {
        int rgb = image.getRGB(Math.max(0, Math.min(W - 1, x)), Math.max(0, Math.min(H - 1, y)));
        return ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3f;
    }

    /** Where world point {@code p} lands, in pixels from the top left. */
    private int[] pixel(float[] p) {
        Vector4f clip = new Vector4f(p[0] - EYE[0], p[1] - EYE[1], p[2] - EYE[2], 1f);
        look.transform(clip);
        projection.transform(clip);
        return new int[] {(int) ((clip.x / clip.w * 0.5f + 0.5f) * W), H - 1 - (int) ((clip.y / clip.w * 0.5f + 0.5f) * H)};
    }

    /** The share of the albedo an off curve needs to reach {@code value}: the occlusion at that point, 0.2 floored. */
    private static float occlusion(float[] curve, float value) {
        if (value <= curve[0]) return SHARES[0];
        for (int i = 1; i < SHARES.length; i++) {
            if (value <= curve[i]) {
                float t = (value - curve[i - 1]) / Math.max(1.0e-3f, curve[i] - curve[i - 1]);
                return SHARES[i - 1] + t * (SHARES[i] - SHARES[i - 1]);
            }
        }
        return 1f;
    }

    /** The mean of a 7x7 patch's channels round where world point {@code p} lands. */
    private float patch(BufferedImage image, float[] p) {
        int[] c = pixel(p);
        float sum = 0f;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) sum += value(image, c[0] + dx, c[1] + dy);
        }
        return sum / 49f;
    }

    private static void save(HarnessContext ctx, String name, BufferedImage image) throws IOException {
        ImageIO.write(image, "png", new File(ctx.getOutputDir(), name + ".png"));
    }

    private static void report(List<String> failures, boolean deviceErrors) {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (deviceErrors) {
            System.out.println("[ambient-occlusion] FAIL on " + on + ": device errors, logged above");
        } else if (validation > 0) {
            System.out.println("[ambient-occlusion] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (!failures.isEmpty()) {
            for (String failure : failures) System.out.println("[ambient-occlusion] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[ambient-occlusion] PASS on " + on + ": SSAO and GTAO darken the floor near a wall and a box's"
                    + " side and leave open floor and a close-up's face lit; GTAO near the wall stays within 0.15 of a ray-cast"
                    + " through its falloff; Low runs none");
        }
    }

    @Override
    public void dispose() {
        if (lightmap != null) lightmap.delete();
    }
}
