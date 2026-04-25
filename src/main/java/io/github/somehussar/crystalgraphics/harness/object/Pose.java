package io.github.somehussar.crystalgraphics.harness.object;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public class Pose {

    public final Vector3f pos = new Vector3f(), scale = new Vector3f(1), rotation = new Vector3f();
    private Matrix4f model = new Matrix4f();

    public boolean dirty;

    public Pose setPosition(float x, float y, float z) {
        pos.set(x, y, z);
        dirty = true;
        return this;
    }

    public Pose setPosition(Vector3f pos) {
        this.pos.set(pos);
        dirty = true;
        return this;
    }

    public Pose setRotation(float x, float y, float z) {
        rotation.set(x, y, z);
        dirty = true;
        return this;
    }

    public Pose setScale(float x, float y, float z) {
        scale.set(x, y, z);
        dirty = true;
        return this;
    }

    public Pose setScale(float scale) {
        return setScale(scale, scale, scale);
    }

    public Vector3f getPos() {
        return pos;
    }

    public Vector3f getScale() {
        return scale;
    }

    public Vector3f getRotation() {
        return rotation;
    }

    public Pose rotatePosition(float angle, float x, float y, float z) {
        pos.rotateAxis(angle, x, y, z);
        dirty = true;
        return this;
    }

    public Matrix4f getModel() {
        if (dirty) {
            recomputeModel();
            dirty = false;
        }
        return model;
    }

    public void recomputeModel() {
        model.identity();
        model.setTranslation(pos);
        model.setRotationXYZ(rotation.x, rotation.y, rotation.z);
        model.scale(scale);
    }
}
