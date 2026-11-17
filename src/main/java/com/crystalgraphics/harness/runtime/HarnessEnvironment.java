package com.crystalgraphics.harness.runtime;

import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.host.CgHostFrame;
import com.crystalgraphics.harness.scene.test.HarnessFlatWorld;
import com.crystalgraphics.harness.scene.test.HarnessWorld;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.harness.camera.Camera3D;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.scene.sort.CgSortLayer;
import com.crystalgraphics.render.precache.CgPipelinePrecache;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgWorldQuery;

/**
 * VFX Lab's sky (CrystalGUI's {@code VfxLab}) over a white ground as a harness scene's world: {@code materials/sky.shader}
 * on a sphere round the eye and the harness's {@code ground.shader} on a plane at y 0 under it, in place of the harness's
 * own floor and clear colour: an opaque ground under open sky, as a game's is, hiding what is under the floor and
 * showing what the world lights add to white, under an afternoon sun that casts the sun's shadows. F9 eases between
 * day and night, the sun setting with it; a scene with no world of its own gets a flat one under the floor, so the
 * sun's shadows have sky light to darken,
 * the world stages' daylight and lightmap with it, so what adapts to the light (world lights, a colour region's gain)
 * follows.
 *
 * <pre>{@code
 * // a scene opts in:
 * @Override public boolean editorEnvironment() { return true; }
 * // or any scene, for one run:
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-spheres" -Dcrystalgraphics.harness.environment=editor
 * }</pre>
 *
 * <ul>
 *   <li>Off by default: a gate's pixels are checked against the plain clear colour and floor.</li>
 *   <li>Drawn by {@code HarnessWorld.fire}, once a frame however often a scene fires.</li>
 *   <li>Its pipelines are requested when the runner turns it on, before the scene's loading screen, which holds
 *       until they are built; its draws wait for them too.</li>
 * </ul>
 */
public final class HarnessEnvironment {

    public static final HarnessEnvironment INSTANCE = new HarnessEnvironment();

    /** {@code editor} turns it on for any 3D scene. */
    public static final boolean FORCED = "editor".equals(System.getProperty("crystalgraphics.harness.environment"));

    private static final String SKY_SHADER = "crystalgraphics:shaders/materials/sky.shader";
    private static final String FLOOR_SHADER = "assets/harness/shader/ground.shader";
    private static final Matrix4fc SKY_SCALE = new Matrix4f().scale(600f);
    /** Metres across the floor's plane, centred under the eye: far enough that its edge never shows. */
    private static final float FLOOR_SIZE = 250f;
    /** Day to night in about 0.4 s, as VFX Lab eases it. */
    private static final float NIGHT_RATE = 2.5f;
    /**
     * The sun's place by day, as Minecraft counts it (0 noon, 0.25 setting): mid-afternoon, 36 degrees off overhead, so
     * the sun's shadows fall long enough to see. Night eases it down through sunset to midnight, where only the moon is.
     */
    private static final float DAY_ANGLE = 0.1f, NIGHT_ANGLE = 0.5f;

    private CgMesh sky, floor;
    private CgMaterialInstance skyMaterial, floorMaterial;
    private boolean enabled, night, submitted, providedWorld;
    private float nightMix;
    /** Whether the world stages hold this mix's daylight. */
    private boolean daylightSet;
    /** Minecraft's lightmap, shaped as the lights scenes shape it, its sky light darkened by night. */
    private CgTexture2D lightmap;
    private final ByteBuffer texels = ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.nativeOrder());

    private HarnessEnvironment() {
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Turns it on or off for the run; on, its meshes and materials are made and its pipelines requested, and where the
     * levels hold no world (the scene's, or none), a flat one under the floor, the scene's entities kept: stone below y 0, full sky light above, the light the
     * sun's shadow takes its share from (a shadow darkens a pixel by withholding the sun from its sky light). Render thread.
     */
    public void enabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled && skyMaterial == null) prepare();
        CgLevels.Host levels = CgPlatform.get(CgLevels.HOST);
        if (enabled && levels.clientWorld() == CgWorldQuery.NONE) {
            // The scene's entities kept, the blocks the harness's
            CgPlatform.provide(CgLevels.HOST, CgLevels.single(new HarnessFlatWorld(0, 15), levels.clientEntities()));
            providedWorld = true;
        } else if (!enabled && providedWorld) {
            CgPlatform.provide(CgLevels.HOST, null);
            providedWorld = false;
        }
    }

    private void prepare() {
        sky = CgMeshShapes.sphere(24, 32);
        floor = CgMesh.build(CgVertexFormat.SPATIAL, m -> CgMeshShapes.plane(m, 1, 1, FLOOR_SIZE, FLOOR_SIZE));
        skyMaterial = CgMaterial.load(SKY_SHADER).instance();
        floorMaterial = CgMaterial.load(FLOOR_SHADER).instance();
        programs = CgPipelinePrecache.request(pipelines -> {
            pipelines.add(skyMaterial, false);
            pipelines.add(floorMaterial, false);
        });
    }

    public boolean night() {
        return night;
    }

    public void toggleNight() {
        night = !night;
    }

    /** Once a frame, before the scene renders: eases toward day or night and allows this frame's draws. */
    public void tick(float seconds) {
        submitted = false;
        if (!enabled || skyMaterial == null) return;
        float to = night ? 1f : 0f;
        if (nightMix == to) {
            if (!daylightSet) daylight();
            return;
        }
        float step = seconds * NIGHT_RATE;
        nightMix = Math.abs(to - nightMix) <= step ? to : nightMix + Math.signum(to - nightMix) * step;
        float mix = nightMix;
        skyMaterial.set1f("_Night", mix);
        daylight();
    }

    /**
     * The sun at this mix's place, daylight and stars, and the lightmap at it: brighter with block and sky light, the sky light darkened by up to 11 levels at night as Minecraft's sky
     * darkening does.
     */
    private void daylight() {
        float darken = 11f * nightMix;
        texels.clear();
        for (int sky = 0; sky < 16; sky++) {
            for (int block = 0; block < 16; block++) {
                int v = Math.round(255f * (0.05f + 0.95f * Math.max(block, Math.max(0f, sky - darken)) / 15f));
                texels.put((byte) v).put((byte) v).put((byte) v).put((byte) 255);
            }
        }
        texels.flip();
        if (lightmap == null) lightmap = CgTextures.of("harness.harnessEnvironment", CgTextureDesc.of(16, 16, CgFormat.RGBA8_UNORM));
        lightmap.uploadRegion(0, 0, 0, 16, 16, texels);
        CgHostFrame host = CgStageHost.frame(CgStageHost.WORLD_OPAQUE);
        host.environment().sun(DAY_ANGLE + (NIGHT_ANGLE - DAY_ANGLE) * nightMix, 0, nightMix, 1f - nightMix);
        host.textures().importedLightmap(lightmap.rhiTexture(), 16, 16);
        daylightSet = true;
    }

    /** The sky round {@code camera} and the floor under it, the first time a frame asks. */
    public void submit(Camera3D camera) {
        if (!enabled || submitted) return;
        submitted = true;
        if (!programs.ready()) return;
        CgScene scene = CgScene.client();
        double x = camera.getPosX(), y = camera.getPosY(), z = camera.getPosZ();
        scene.once().mesh(sky, skyMaterial).at(x, y, z).transform(SKY_SCALE).layer(CgSortLayer.BACKGROUND).add();
        // Under the eye, so flying never reaches its edge; open ground's light, the sky's alone.
        scene.once().mesh(floor, floorMaterial).at(x, 0.0, z).light(0f, 15f).layer(CgSortLayer.BACKGROUND).add();
    }

    private CgPipelinePrecache.Request programs;
}
