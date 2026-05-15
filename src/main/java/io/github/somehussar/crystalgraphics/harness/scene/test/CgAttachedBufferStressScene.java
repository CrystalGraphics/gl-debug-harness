package io.github.somehussar.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.render.CgFrameData;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.gl.mesh.CgMeshBuilder;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.tool.GlErrorChecker;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import static com.crystalgraphics.api.buffer.CgBufferFormat.MemoryLayout.STD140;
import static com.crystalgraphics.api.buffer.CgBufferFormat.MemoryLayout.STD430;

/**
 * Stress-tests the full attached-buffer pipeline across 5 materials simultaneously.
 * Each material has a different buffer combination (SSBO-only, UBO-only, heavy SSBO,
 * packed SSBO, SSBO + UBO combined).
 */
public class CgAttachedBufferStressScene implements InteractiveSceneLifecycle {

    private boolean running = true;

    // 5 materials
    private CgMaterial matParticle;      // SSBO only — "PARTICLE" macro
    private CgMaterial matTerrain;       // UBO only  — flat scope
    private CgMaterial matSkinned;       // SSBO with mat4+mat3 heavy struct
    private CgMaterial matGlyph;         // SSBO with packed VEC2/FLOAT fields
    private CgMaterial matCombined;      // SSBO + UBO combined

    // Shared mesh — unit cube for all draws
    private CgMesh mesh;
    private CgRenderPipeline pipeline;

    // Attached buffers (user-owned, not engine pipeline buffers)
    private CgShaderBuffer particleBuf;   // "ParticleData", userIndex=0
    private CgShaderBuffer skinnedBuf;    // "SkinData",     userIndex=1
    private CgShaderBuffer glyphBuf;      // "GlyphMetrics", userIndex=2
    private CgShaderBuffer instanceProps; // "InstanceProps", userIndex=3
    private CgUniformBuffer terrainUbo;   // "TerrainParams", userIndex=0
    private CgUniformBuffer sceneUbo;     // "SceneParams",   userIndex=1

    private static final Matrix4f SCRATCH = new Matrix4f();
    private static final Matrix3f SCRATCH3 = new Matrix3f();

    // ParticleData — vec4 worldOffset, vec4 color, vec2 uv, float age, float _pad
    static final CgBufferFormat PARTICLE_FORMAT = CgBufferFormat.builder("ParticleData", STD430)
                                                                .vec4("worldOffset").vec4("color").vec2("uv")
                                                                .float_("age").float_("_pad").build();

    // SkinData — mat4 bindPose, mat3 normalMat, vec4 weights (stride=64+48+16=128 bytes)
    static final CgBufferFormat SKIN_FORMAT = CgBufferFormat.builder("SkinData", STD430)
                                                            .mat4("bindPose").mat3("normalMat").vec4("weights").build();

    // GlyphMetrics — vec4 bbox, vec2 uv0, vec2 uv1, float advance, float bearing, float descent, float _pad
    static final CgBufferFormat GLYPH_FORMAT = CgBufferFormat.builder("GlyphMetrics", STD430)
                                                             .vec4("bbox").vec2("uv0").vec2("uv1").float_("advance")
                                                             .float_("bearing").float_("descent").float_("_pad")
                                                             .build();

    // InstanceProps — vec4 albedo, vec4 emissive (stride=32)
    static final CgBufferFormat INSTANCE_PROPS_FORMAT = CgBufferFormat.builder("InstanceProps", STD430)
                                                                      .vec4("albedo").vec4("emissive").build();

    // TerrainParams UBO (STD140) — vec4 fogColor, vec4 sunDirAndAmbient, float fogDensity + padding
    static final CgBufferFormat TERRAIN_UBO_FORMAT = CgBufferFormat.builder("TerrainParams", STD140)
                                                                   .vec4("fogColor").vec4("sunDirAndAmbient")
                                                                   .float_("fogDensity").float_("_p0").float_("_p1")
                                                                   .float_("_p2").int64("state").build();

    // SceneParams UBO (STD140) — vec4 ambientColor, float exposure + padding
    static final CgBufferFormat SCENE_UBO_FORMAT = CgBufferFormat.builder("SceneParams", STD140)
                                                                 .vec4("ambientColor").float_("exposure").float_("_p0")
                                                                 .float_("_p1").float_("_p2").build();

    @Override
    public void init(HarnessContext ctx) {
        pipeline = CgRenderPipeline.getInstance();
        mesh = CgMeshBuilder.unitCube(CgVertexFormat.SPATIAL).upload();

        particleBuf = CgShaderBuffer.create("ParticleDataBuffer", PARTICLE_FORMAT, 0);
        skinnedBuf = CgShaderBuffer.create("SkinDataBuffer", SKIN_FORMAT, 1);
        glyphBuf = CgShaderBuffer.create("GlyphMetricsBuffer", GLYPH_FORMAT, 2);
        instanceProps = CgShaderBuffer.create("InstancePropsBuffer", INSTANCE_PROPS_FORMAT, 3);
        terrainUbo = CgUniformBuffer.create(TERRAIN_UBO_FORMAT, "TerrainParams", 0);
        sceneUbo = CgUniformBuffer.create(SCENE_UBO_FORMAT, "SceneParams", 1);

        matParticle = CgMaterial.load("assets/harness/shader/stress/particle_ssbo.shader");
        matParticle.attach(particleBuf, "PARTICLE");

        matTerrain = CgMaterial.load("assets/harness/shader/stress/terrain_ubo.shader");
        matTerrain.attach(terrainUbo);

        matSkinned = CgMaterial.load("assets/harness/shader/stress/skinned_ssbo.shader");
        matSkinned.attach(skinnedBuf, "SKIN_DATA");

        matGlyph = CgMaterial.load("assets/harness/shader/stress/glyph_packed.shader");
        matGlyph.attach(glyphBuf, "GLYPH");

        matCombined = CgMaterial.load("assets/harness/shader/stress/multi_combined.shader");
        matCombined.attach(instanceProps, "INST_PROPS");
        matCombined.attach(sceneUbo);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        Matrix4f view = ctx.getCamera3D().getViewMatrix();
        Matrix4f projection = ctx.getProjection();

        CgFrameData fd = pipeline.getFrameData();
        fd.viewMatrix.set(view);
        fd.projMatrix.set(projection);
        fd.timeSecs  = (float) frame.getElapsedTime();
        fd.viewportW = ctx.getScreenWidth();
        fd.viewportH = ctx.getScreenHeight();
        fd.deriveFromViewMatrix();
        pipeline.prepareFrame();

        float t = (float) frame.getElapsedTime();

        // ── Particle draw (N=500, spiral) ────────────────────────────────────
        int N_PARTICLE = 5000;
        CgBufferWriter w = particleBuf.beginWrite(N_PARTICLE);
        for (int i = 0; i < N_PARTICLE; i++) {
            float angle = (float) (i * 0.05 + t * 0.3);
            float radius = 0.5f + (i % 10) * 0.8f;
            float px = (float) (Math.cos(angle) * radius);
            float py = (float) (Math.sin(i * 0.17 + t * 0.5) * 3f);
            float pz = (float) (Math.sin(angle) * radius) - 15f;
            float r = (float) (0.5 + 0.5 * Math.sin(i * 0.3 + t));
            float g = (float) (0.5 + 0.5 * Math.cos(i * 0.2 + t * 1.3));
            float b = (float) (0.5 + 0.5 * Math.sin(i * 0.5 + t * 0.7));
            float age = (float) ((Math.sin(i * 0.1 + t) * 0.5 + 0.5));
            w.beginRecord()
             .vec4("worldOffset", px, py, pz, 0f)
             .vec4("color", r, g, b, 1f)
             .vec2("uv", (float) (i % 8) / 8f, (float) (i / 8) / 64f)
             .float_("age", age)
             .float_("_pad", 0f);
            particleBuf.endRecord();
        }
        particleBuf.endWrite();
        GL11.glEnable(GL11.GL_BLEND);
GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK,GL11.GL_FILL);
        GL11.glPointSize(15);
        GL11.glLineWidth(2);
        GL11.glEnable(GL11.GL_POINT_SMOOTH);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        CgShaderBuffer objBuf = pipeline.objectBuffer();
        CgBufferWriter ow = objBuf.beginWrite(N_PARTICLE);
        for (int i = 0; i < N_PARTICLE; i++) {
            ow.beginRecord()
              .mat4("modelMatrix", SCRATCH.identity())
              .mat4("normalMatrix", SCRATCH.identity());
            objBuf.endRecord();
        }
        objBuf.endWrite();
        matParticle.bind();
        particleBuf.bind();
       // mesh.drawInstanced(N_PARTICLE);
        matParticle.unbind();
        GlErrorChecker.assertNoGlError("particle");

        // ── Terrain draw (1 instance, large flat cube at Y=-2) ───────────────
        float fogR = (float) (0.3 + 0.1 * Math.sin(t * 0.1));
        terrainUbo.writer().reset().beginRecord()
                  .vec4("fogColor", fogR, fogR * 0.9f, fogR * 1.2f, 1f)
                  .vec4("sunDirAndAmbient", 0.3f, 5.6f, 0.5f, 0.15f)
                  .float_("fogDensity", 0.04f)
                  .float_("_p0", 0f).float_("_p1", 0f).float_("_p2", 0f).int64("state", System.currentTimeMillis());
        
        terrainUbo.endRecord();
        terrainUbo.upload();

        ow = objBuf.beginWrite(1);
        ow.beginRecord()
          .mat4("modelMatrix", SCRATCH.identity().translate(0f, -2f, -8f).scale(20f, 0.2f, 20f))
          .mat4("normalMatrix", SCRATCH.identity());
        objBuf.endRecord();
        objBuf.endWrite();
        matTerrain.bind();
        terrainUbo.bind();
        mesh.drawDirect();
        matTerrain.unbind();
        GlErrorChecker.assertNoGlError("terrain");

        // ── Skinned draw (N=8 in a row) ───────────────────────────────────────
        int N_SKIN = 8;
        w = skinnedBuf.beginWrite(N_SKIN);
        for (int i = 0; i < N_SKIN; i++) {
            float ang = t * 0.5f + i * 0.4f;
            w.beginRecord()
             .mat4("bindPose", SCRATCH.identity().rotateY(ang))
             .vec4("weights", 1f, 0f, 0f, 0f);
            skinnedBuf.endRecord();
        }
        skinnedBuf.endWrite();

        ow = objBuf.beginWrite(N_SKIN);
        for (int i = 0; i < N_SKIN; i++) {
            ow.beginRecord()
              .mat4("modelMatrix", SCRATCH.identity().translate(-14f + i * 4f, 0f, -10f))
              .mat4("normalMatrix", SCRATCH.identity());
            objBuf.endRecord();
        }
        objBuf.endWrite();
        matSkinned.bind();
        skinnedBuf.bind();
        mesh.drawInstanced(N_SKIN);
        matSkinned.unbind();
        GlErrorChecker.assertNoGlError("skinned");

        // ── Glyph draw (N=64, 8x8 grid) ──────────────────────────────────────
        int N_GLYPH = 64;
        w = glyphBuf.beginWrite(N_GLYPH);
        float timer = (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 1));

        for (int i = 0; i < N_GLYPH; i++) {
            float u = (i % 8) / 8f;
            float v = (i / 8) / 8f;
            w.beginRecord()
             .vec4("bbox", u, v, u + 0.125f + 2, v + 0.125f + 2)
             .vec2("uv0", u, v)
             .vec2("uv1", u + 0.125f, v + 0.125f)
             .float_("advance", 0.12f)
             .float_("bearing", 0.09f)
             .float_("descent", 0.02f)
             .float_("_pad", 2f);
            glyphBuf.endRecord();
        }
        glyphBuf.endWrite();

        ow = objBuf.beginWrite(N_GLYPH);
        for (int i = 0; i < N_GLYPH; i++) {
            float gx = -7f + (i % 8) * 2f;
            float gy = 4f - (i / 8) * 2f;
            ow.beginRecord()
              .mat4("modelMatrix", SCRATCH.identity().translate(gx, gy, -02f).scale(0.5f))
              .mat4("normalMatrix", SCRATCH.identity());
            objBuf.endRecord();
        }
        objBuf.endWrite();
        matGlyph.bind();
        glyphBuf.bind();
        mesh.drawInstanced(N_GLYPH);
        matGlyph.unbind();
        GlErrorChecker.assertNoGlError("glyph");

        // ── Combined draw (N=32, two rows) ────────────────────────────────────
        int N_COMB = 32;
        w = instanceProps.beginWrite(N_COMB);
        for (int i = 0; i < N_COMB; i++) {
            float h = (i / (float) N_COMB);
            float r = (float) (0.5 + 0.5 * Math.sin(h * Math.PI * 2 + 0));
            float g = (float) (0.5 + 0.5 * Math.cos(h * Math.PI * 2 + 0 * 1.3));
            float bl = (float) (0.5 + 0.5 * Math.sin(h * Math.PI * 4 + 0 * 0.7));
            w.beginRecord()
             .vec4("albedo", r, g, bl, 1f)
             .vec4("emissive", r * 0.2f, g * 0.2f, bl * 0.2f, 0f);
            instanceProps.endRecord();
        }
        instanceProps.endWrite();

        float exposure = (float) (0.8 + 0.4 * Math.sin(1 * 0.3));
        sceneUbo.writer().reset().beginRecord()
                .vec4("ambientColor", 0.1f, 0.12f, 0.15f, 1f)
                .float_("exposure", exposure)
                .float_("_p0", 0f).float_("_p1", 0f).float_("_p2", 0f);
        sceneUbo.endRecord();
        sceneUbo.upload();

        ow = objBuf.beginWrite(N_COMB);
        for (int i = 0; i < N_COMB; i++) {
            float cx = -15.5f + (i % 16) * 2f;
            float cy = 5f - (i / 16) * 2.5f;
            ow.beginRecord()
              .mat4("modelMatrix", SCRATCH.identity().translate(cx, cy, -20f))
              .mat4("normalMatrix", SCRATCH.identity());
            objBuf.endRecord();
        }
        objBuf.endWrite();
        matCombined.bind();
        instanceProps.bind();
        sceneUbo.bind();
        mesh.drawInstanced(N_COMB);
        matCombined.unbind();
        GlErrorChecker.assertNoGlError("combined");
    }

    @Override
    public void dispose() {
        if (mesh != null) mesh.delete();
        if (matParticle != null) matParticle.delete();
        if (matTerrain != null) matTerrain.delete();
        if (matSkinned != null) matSkinned.delete();
        if (matGlyph != null) matGlyph.delete();
        if (matCombined != null) matCombined.delete();
        // User-owned buffers are NOT deleted by the material — caller owns lifecycle
    }

    @Override
    public boolean isRunning() {return running;}

    @Override
    public boolean uses3DCamera() {return true;}

    @Override
    public boolean shouldShutdownOnComplete() {return true;}
}
