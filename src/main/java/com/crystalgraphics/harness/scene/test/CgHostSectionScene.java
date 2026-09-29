package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.render.CgFrameData;
import com.crystalgraphics.api.render.CgRenderCommand;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.gl.mesh.CgMeshBuilder;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * A fake host drawing into our frame, as Minecraft's item and entity renderers will: engine draws, then a quad
 * the host draws in raw GL inside {@link CgGlState#hostForeign(Runnable, CgGlSlot...)}, then engine draws again,
 * all into one scratch target that is composited to the window last.
 *
 * <p>What the picture proves, in painter's order:</p>
 * <ul>
 *   <li>red, ours: a slab at depth 0.6;</li>
 *   <li>green, the host's: a nearer quad, depth-tested against red, drawn with fixed function and leaving its
 *       own state behind — program 0, depth test {@code LESS}, blending {@code ONE, ZERO}, a 16 px viewport;</li>
 *   <li>blue, ours: a slab at red's depth that must cover red ({@code LEQUAL}) and stay behind green.</li>
 * </ul>
 * <p>The whole frame sits in one outer scope, so the pipeline's own scopes are nested and trust the shadow: if
 * {@code hostForeign} failed to restore, blue would lose to red or to the host's viewport, visibly. Recorded
 * ({@code -Dcrystalgraphics.harness.record}), the host's quad is recorded as a body and drawn on replay in
 * its place.</p>
 */
public final class CgHostSectionScene implements InteractiveSceneLifecycle {

    private static final CgGlSlot[] HOST_TOUCHES = {
            CgGlSlot.PROGRAM, CgGlSlot.DEPTH, CgGlSlot.BLEND, CgGlSlot.VIEWPORT, CgGlSlot.VERTEX_INPUT, CgGlSlot.FBO };
    private static final CgGlSlot[] FRAME = {
            CgGlSlot.FBO, CgGlSlot.VIEWPORT, CgGlSlot.PROGRAM, CgGlSlot.DEPTH, CgGlSlot.BLEND, CgGlSlot.VERTEX_INPUT };

    private CgFrameBuffer target;
    private CgMesh slab;
    private CgMaterial material;
    private CgRenderPipeline pipeline;
    private final Runnable host = CgHostSectionScene::hostDraws;

    @Override
    public void init(HarnessContext ctx) {
        slab = CgMeshBuilder.quad2D(CgVertexFormat.SPATIAL, -0.5f, -0.5f, 0.5f, 0.5f).upload();
        material = CgMaterial.load("assets/harness/shader/host_section.shader");
        pipeline = CgRenderPipeline.getInstance();
        target = CgFrameBuffer.create("host-section", ctx.getScreenWidth(), ctx.getScreenHeight(),
                CgFrameBufferFormat.builder("host-section")
                        .color(0, CgTextureType.RGBA8)
                        .depthRenderbuffer(CgTextureType.DEPTH24_STENCIL8)
                        .build());
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        if (target.getWidth() != w || target.getHeight() != h) target.resize(w, h);

        CgFrameData fd = pipeline.getFrameData();
        fd.viewMatrix.identity();
        fd.projMatrix.identity();
        fd.viewportW = w;
        fd.viewportH = h;
        fd.deriveFromViewMatrix();

        try (CgGlScope ignored = CgGlState.save(FRAME)) {
            target.bind();
            CgGL.glViewport(0, 0, w, h);
            target.clear(CgGL.GL_COLOR_BUFFER_BIT | CgGL.GL_DEPTH_BUFFER_BIT, 0.08f, 0.08f, 0.1f, 1f, 1.0, 0);

            drawSlab(-0.25f, 0f, 1.1f, 1.2f, 0.2f, 0.85f, 0.2f, 0.2f);   // red
            CgGlState.hostForeign(host, HOST_TOUCHES);                   // green, the host's
            drawSlab(0.2f, -0.3f, 0.6f, 1.0f, 0.2f, 0.2f, 0.35f, 0.9f);  // blue, at red's depth

            CgGL.glBindFramebuffer(CgGL.GL_READ_FRAMEBUFFER, target.getId());
            CgGL.glBindFramebuffer(CgGL.GL_DRAW_FRAMEBUFFER, 0);
            CgGL.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, CgGL.GL_COLOR_BUFFER_BIT, CgGL.GL_NEAREST);
        }
    }

    private void drawSlab(float cx, float cy, float sx, float sy, float z, float r, float g, float b) {
        CgRenderCommand cmd = pipeline.acquireCommand();
        cmd.mesh = slab;
        cmd.material = material;
        cmd.modelMatrix.identity().translate(cx, cy, z).scale(sx, sy, 1f);
        cmd.custom0.set(r, g, b, 1f);
        cmd.worldAabb[0] = cx - sx / 2; cmd.worldAabb[1] = cy - sy / 2; cmd.worldAabb[2] = z;
        cmd.worldAabb[3] = cx + sx / 2; cmd.worldAabb[4] = cy + sy / 2; cmd.worldAabb[5] = z;
        pipeline.submit(cmd);
        pipeline.executeOpaquePass(0f, target.getId());
        pipeline.executeTransparentPass();
        pipeline.endFrame();
    }

    /** The host's part: raw GL and fixed function, with its own state left behind as a host leaves it. */
    private static void hostDraws() {
        GL20.glUseProgram(0);
        GL30.glBindVertexArray(0);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LESS);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glColor4f(0.2f, 0.8f, 0.3f, 1f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(-0.1f, -0.45f, -0.4f);
        GL11.glVertex3f(0.75f, -0.45f, -0.4f);
        GL11.glVertex3f(0.75f, 0.35f, -0.4f);
        GL11.glVertex3f(-0.1f, 0.35f, -0.4f);
        GL11.glEnd();
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glViewport(0, 0, 16, 16);
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return false; }

    @Override
    public void dispose() {
        slab.delete();
    }
}
