package io.github.somehussar.crystalgraphics.harness.object;

import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.state.CgBlendState;
import io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory;
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

    QuadRenderer modelQuad = new QuadRenderer();

    private static final String DIR = "assets/harness/shader/";
    CgShader sunShader = CgShaderFactory.load(DIR + "quad.vert", DIR + "circle/sun.frag");
    
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
        modelQuad.init(ctx);
    }

    public void render(HarnessContext ctx, FrameInfo frame) {
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

        CgBlendState.ALPHA.apply();
        sunShader.applyBindings(b -> {
            b.mat4("u_model", pose.getModel());
            b.mat4("u_view", ctx.getCamera3D().getViewMatrix());
            b.mat4("u_projection", ctx.getProjection());
        }).bind();
        modelQuad.render(ctx);

        // TextContext text = ctx.getTextContext();
        // text.draw(String.format("Angle: %.2f", angle), 0, 100, 0xffffffff, frame);
        // text.draw(String.format("Time:  %.2f/%ss", cycleInSec, cycleDurationSec), 0, 120, 0xffffffff, frame);
    }
}
