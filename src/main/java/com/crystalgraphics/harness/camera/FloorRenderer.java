package com.crystalgraphics.harness.camera;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.harness.config.WorldSettings;
import com.crystalgraphics.harness.util.ObjectDraws;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.draw.CgPassConstants;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The ground under a 3D scene: a flat square at Y 0 of {@link WorldSettings}' colour and size, drawn into the bound
 * target as the host's world is, before the scene and the world stages.
 *
 * <pre>{@code
 * FloorRenderer floor = new FloorRenderer();
 * floor.init(settings);
 * floor.render(view, projection, width, height);   // each frame, into the frame's target
 * }</pre>
 */
public final class FloorRenderer {

    private static final String SHADER = "assets/harness/shader/flat_colour.shader";

    private final Matrix4f model = new Matrix4f();
    private final CgPassConstants constants = new CgPassConstants();
    private float r, g, b;
    private final ObjectDraws.Records record = (i, data, at) -> ObjectDraws.object(data, at, model, r, g, b, 1f);
    private CgMaterial material;
    private CgMesh mesh;

    /** Loads its material and shape: once, before {@link #render}. */
    public void init(WorldSettings settings) {
        if (material != null) return;
        material = CgMaterial.load(SHADER);
        mesh = CgMeshShapes.grid(1);
        float size = 2f * settings.getFloorHalfSize();
        model.scaling(size, 1f, size);
        r = settings.getFloorR();
        g = settings.getFloorG();
        b = settings.getFloorB();
    }

    /** Draws it under {@code view} and {@code projection}, into the bound target of {@code width} x {@code height}. */
    public void render(Matrix4fc view, Matrix4fc projection, int width, int height) {
        if (material == null) throw new IllegalStateException("FloorRenderer.init() must be called before render()");
        constants.view.set(view);
        constants.projection.set(projection);
        constants.resolution(width, height).time(CgFrameClock.seconds()).hostDepth().cameraFromView();
        ObjectDraws.draw(constants, material, mesh, 1, record);
    }
}
