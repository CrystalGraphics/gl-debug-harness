package io.github.somehussar.crystalgraphics.harness.object;

import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.config.TextContext;
import org.joml.Vector3f;

public class LightSource {
    public final Pose pose = new Pose();

    public boolean rotates = true;
    public float cycleDurationSec = 60;

    public Vector3f rotationStart = new Vector3f();
    public Vector3f rotationAxis = new Vector3f();
    private static final Vector3f TEMP = new Vector3f();

    public LightSource rotationStart(int x, int y, int z) {
        this.rotationStart.set(x, y, z);
        this.pose.setPosition(x, y, z);
        return this;
    }

    public LightSource rotationAxis(int x, int y, int z) {
        this.rotationAxis.set(x, y, z);
        return this;
    }

    public void render(HarnessContext ctx, FrameInfo frame, Runnable renderFunc) {
        float angle = 0;
        float cycleInSec = 0;
        if (rotates) {
            long cycleInMillis = System.currentTimeMillis() % (long) (cycleDurationSec * 1000L);
            cycleInSec = cycleInMillis / 1000f;
            float progress = cycleInSec / cycleDurationSec;
            angle = (float) (progress * 2 * Math.PI);

            TEMP.set(rotationStart);
            TEMP.rotateAxis(angle, rotationAxis.x, rotationAxis.y, rotationAxis.z);
            pose.setPosition(TEMP);
        }

        renderFunc.run();

        TextContext text = ctx.getTextContext();

        text.draw(String.format("Angle: %.2f", angle), 0, 100, 0xffffffff, frame);
        text.draw(String.format("Time:  %.2f/%ss", cycleInSec,cycleDurationSec), 0, 120, 0xffffffff, frame);
    }
}
