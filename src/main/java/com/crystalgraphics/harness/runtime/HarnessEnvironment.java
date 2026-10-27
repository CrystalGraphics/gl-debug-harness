package com.crystalgraphics.harness.runtime;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.harness.camera.Camera3D;
import com.crystalgraphics.render.world.CgSortLayer;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * VFX Lab's sky and floor (CrystalGUI's {@code VfxLab}) as a harness scene's world: {@code editor_sky.shader} on a
 * sphere round the eye and {@code editor_grid.shader} on a plane at y 0 under it, in place of the harness's own floor
 * and clear colour. F9 eases between day and night.
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
 * </ul>
 */
public final class HarnessEnvironment {

    public static final HarnessEnvironment INSTANCE = new HarnessEnvironment();

    /** {@code editor} turns it on for any 3D scene. */
    public static final boolean FORCED = "editor".equals(System.getProperty("crystalgraphics.harness.environment"));

    private static final String SKY_SHADER = "crystalgraphics:shaders/demo/editor_sky.shader";
    private static final String FLOOR_SHADER = "crystalgraphics:shaders/demo/editor_grid.shader";
    private static final Matrix4fc SKY_SCALE = new Matrix4f().scale(600f);
    /** Metres the grid fades out by, from a quarter of it; the plane spans past it, so its edge never shows. */
    private static final float FLOOR_FADE = 500f;
    /** Day to night in about 0.4 s, as VFX Lab eases it. */
    private static final float NIGHT_RATE = 2.5f;

    private CgMesh sky, floor;
    private CgMaterial skyMaterial, floorMaterial;
    private boolean enabled, night, submitted;
    private float nightMix;

    private HarnessEnvironment() {
    }

    public boolean enabled() {
        return enabled;
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
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
        if (nightMix == to) return;
        float step = seconds * NIGHT_RATE;
        nightMix = Math.abs(to - nightMix) <= step ? to : nightMix + Math.signum(to - nightMix) * step;
        float mix = nightMix;
        skyMaterial.applyProperties(b -> b.set1f("_Night", mix));
        floorMaterial.applyProperties(b -> b.set1f("_Night", mix));
    }

    /** The sky round {@code camera} and the floor under it, the first time a frame asks. */
    public void submit(Camera3D camera) {
        if (!enabled || submitted) return;
        submitted = true;
        if (skyMaterial == null) {
            sky = CgMeshShapes.sphere(24, 32);
            floor = CgMesh.build(CgVertexFormat.SPATIAL, m -> CgMeshShapes.plane(m, 1, 1, FLOOR_FADE * 2.5f, FLOOR_FADE * 2.5f));
            skyMaterial = CgMaterial.newInstance(SKY_SHADER);
            floorMaterial = CgMaterial.newInstance(FLOOR_SHADER).applyProperties(b -> b.set1f("_Fade", FLOOR_FADE));
        }
        CgWorldRenderer world = CgWorldRenderer.get();
        double x = camera.getPosX(), y = camera.getPosY(), z = camera.getPosZ();
        // Under the eye, so flying never reaches its edge; the grid's lines are absolute and hold still.
        if (world.prepare(floorMaterial)) {
            world.draw(floor, floorMaterial).at(x, 0.0, z).layer(CgSortLayer.BACKGROUND).submit();
        }
        if (world.prepare(skyMaterial)) {
            world.draw(sky, skyMaterial).at(x, y, z).transform(SKY_SCALE).layer(CgSortLayer.BACKGROUND).submit();
        }
    }
}
