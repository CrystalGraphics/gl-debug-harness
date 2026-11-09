package com.crystalgraphics.harness.util;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.draw.CgPassConstants;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * A reference cube standing on the floor, from (-1, 0, -1) to (1, 2, 1), each face its own colour so a capture shows
 * which way the camera looks: front (+Z) red, back cyan, left (-X) green, right magenta, top blue, bottom yellow.
 *
 * <pre>{@code
 * ValidationCube cube = new ValidationCube();
 * cube.render(view, projection, width, height);   // each frame, into the bound target
 * }</pre>
 */
public final class ValidationCube {

    private static final String SHADER = "assets/harness/shader/flat_colour.shader";

    private static final float[][] COLOURS = {
            {1f, .2f, .2f}, {.2f, 1f, 1f}, {.2f, 1f, .2f}, {1f, .2f, 1f}, {.2f, .2f, 1f}, {1f, 1f, .2f}};

    private static final float HALF_PI = (float) (Math.PI / 2);

    private final Matrix4f[] faces = {
            new Matrix4f().translation(0f, 1f, 1f).scale(2f),
            new Matrix4f().translation(0f, 1f, -1f).rotateY(2f * HALF_PI).scale(2f),
            new Matrix4f().translation(-1f, 1f, 0f).rotateY(-HALF_PI).scale(2f),
            new Matrix4f().translation(1f, 1f, 0f).rotateY(HALF_PI).scale(2f),
            new Matrix4f().translation(0f, 2f, 0f).rotateX(-HALF_PI).scale(2f),
            new Matrix4f().translation(0f, 0f, 0f).rotateX(HALF_PI).scale(2f)};

    private final CgPassConstants constants = new CgPassConstants();
    private final ObjectDraws.Records record = (i, data, at) ->
            ObjectDraws.object(data, at, faces[i], COLOURS[i][0], COLOURS[i][1], COLOURS[i][2], 1f);
    private final CgMaterial material = CgMaterial.load(SHADER);
    private final CgMesh quad = CgMeshShapes.quad();

    /** Draws it under {@code view} and {@code projection}, into the bound target of {@code width} x {@code height}. */
    public void render(Matrix4fc view, Matrix4fc projection, int width, int height) {
        constants.view.set(view);
        constants.projection.set(projection);
        constants.resolution(width, height).time(CgFrameClock.seconds()).hostDepth().cameraFromView();
        ObjectDraws.draw(constants, material, quad, faces.length, record);
    }
}
