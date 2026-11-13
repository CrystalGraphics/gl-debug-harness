package com.crystalgraphics.harness.runtime;

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
import com.crystalgraphics.render.world.CgPipelinePrecache;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.crystalgraphics.platform.rhi.format.CgFormat;

/**
 * VFX Lab's sky and floor (CrystalGUI's {@code VfxLab}) as a harness scene's world: {@code materials/sky.shader} on a
 * sphere round the eye and {@code materials/grid.shader} on a plane at y 0 under it, in place of the harness's own floor
 * and clear colour: an opaque ground, as a game's is, hiding what is under the floor. F9 eases between day and night,
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
    private static final String FLOOR_SHADER = "crystalgraphics:shaders/materials/grid.shader";
    private static final Matrix4fc SKY_SCALE = new Matrix4f().scale(600f);
    /** Metres the grid fades out by, from a quarter of it; the plane spans past it, so its edge never shows. */
    private static final float FLOOR_FADE = 100f;
    /** Day to night in about 0.4 s, as VFX Lab eases it. */
    private static final float NIGHT_RATE = 2.5f;

    private CgMesh sky, floor;
    private CgMaterialInstance skyMaterial, floorMaterial;
    private boolean enabled, night, submitted;
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

    /** Turns it on or off for the run; on, its meshes and materials are made and its pipelines requested. Render thread. */
    public void enabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled && skyMaterial == null) prepare();
    }

    private void prepare() {
        sky = CgMeshShapes.sphere(24, 32);
        floor = CgMesh.build(CgVertexFormat.SPATIAL, m -> CgMeshShapes.plane(m, 1, 1, FLOOR_FADE * 2.5f, FLOOR_FADE * 2.5f));
        skyMaterial = CgMaterial.load(SKY_SHADER).instance();
        floorMaterial = CgMaterial.load(FLOOR_SHADER).instance().set1f("_Fade", FLOOR_FADE);
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
        floorMaterial.set1f("_Night", mix);
        daylight();
    }

    /**
     * The default sun at this mix's daylight and stars, as a host with no sun angle reports them, and the lightmap at
     * it: brighter with block and sky light, the sky light darkened by up to 11 levels at night as Minecraft's sky
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
        for (CgRenderStage stage : WORLD) {
            stage.host().environment().sun(Float.NaN, 0, nightMix, 1f - nightMix);
            stage.host().textures().importedLightmap(lightmap.rhiTexture(), 16, 16);
        }
        daylightSet = true;
    }

    private static final CgRenderStage[] WORLD = {CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT};

    /** The sky round {@code camera} and the floor under it, the first time a frame asks. */
    public void submit(Camera3D camera) {
        if (!enabled || submitted) return;
        submitted = true;
        if (!programs.ready()) return;
        CgWorldRenderer world = CgWorldRenderer.get();
        double x = camera.getPosX(), y = camera.getPosY(), z = camera.getPosZ();
        world.draw(sky, skyMaterial).at(x, y, z).transform(SKY_SCALE).layer(CgSortLayer.BACKGROUND).submit();
        // Under the eye, so flying never reaches its edge; the grid's lines are absolute and hold still.
        world.draw(floor, floorMaterial).at(x, 0.0, z).layer(CgSortLayer.BACKGROUND).submit();
    }

    private CgPipelinePrecache.Request programs;
}
