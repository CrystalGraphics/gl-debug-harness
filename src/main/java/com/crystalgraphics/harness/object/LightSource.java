package com.crystalgraphics.harness.object;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.RawGlProgram;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL33C.*;

/** Raw LWJGL GL: drawn inside its scene's {@code CgGlState.hostForeign}. GL device only. */
public class LightSource {
    public final Pose pose = new Pose();

    public boolean rotates = true;
    public float cycleDurationSec = 60;

    public Vector3f rotationStart = new Vector3f();
    public Vector3f rotationAxis = new Vector3f();
    private static final Vector3f TEMP = new Vector3f();

    QuadRenderer modelQuad = new QuadRenderer();

    private static final String DIR = "assets/harness/shader/";
    RawGlProgram sunShader;

    public LightSource rotationStart(int x, int y, int z) {
        this.rotationStart.set(x, y, z);
        this.pose.setPosition(x, y, z);
        return this;
    }

    public LightSource rotationAxis(int x, int y, int z) {
        this.rotationAxis.set(x, y, z);
        return this;
    }

    public void init(HarnessContext ctx) {
        sunShader = RawGlProgram.load(DIR + "quad.vert", DIR + "circle/sun.frag");
        modelQuad.init(ctx);
    }

    public void render(HarnessContext ctx, FrameInfo frame) {
        float angle = 0;
        float cycleInSec = 0;
        if (rotates) {
            cycleInSec = (float) (frame.getElapsedTime() % cycleDurationSec);
            float progress = cycleInSec / cycleDurationSec;
            angle = (float) (progress * 2 * Math.PI);

            TEMP.set(rotationStart);
            TEMP.rotateAxis(angle, rotationAxis.x, rotationAxis.y, rotationAxis.z);
            pose.setPosition(TEMP);
        }

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        sunShader.use()
                .mat4("u_model", pose.getModel())
                .mat4("u_view", ctx.getCamera3D().getViewMatrix())
                .mat4("u_projection", ctx.getProjection());
        modelQuad.render(ctx);

        // TextContext text = ctx.getTextContext();
        // text.draw(String.format("Angle: %.2f", angle), 0, 100, 0xffffffff, frame);
        // text.draw(String.format("Time:  %.2f/%ss", cycleInSec, cycleDurationSec), 0, 120, 0xffffffff, frame);
    }

    public void dispose() {
        if (sunShader != null) sunShader.delete();
    }
}
