package io.github.somehussar.crystalgraphics.harness.scene.test;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import io.github.somehussar.crystalgraphics.api.texture.CgTextureType;
import io.github.somehussar.crystalgraphics.api.material.CgFrameUniforms;
import io.github.somehussar.crystalgraphics.api.material.CgMaterial;
import io.github.somehussar.crystalgraphics.api.material.CgMaterialPipeline;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import io.github.somehussar.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMesh;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMeshBuilder;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.tool.GlErrorChecker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * CrystalShader MVP demo scene exercising the full Wave-1 material system:
 *
 * <ul>
 *   <li><b>Dual-path draw</b> — same material, same VAO: {@code drawDirect()} and
 *       {@code drawInstanced(N)} without variants or {@code #ifdef}.</li>
 *   <li><b>Material properties</b> — {@code applyBindings()} routes named writes to
 *       {@code CgMaterialProperty} setters, which populate the {@code CgMaterialBlock} UBO
 *       (non-samplers) and bind sampler uniforms. Adapter is cached per recompile, not
 *       allocated per call.</li>
 *   <li><b>Animated properties</b> — {@code _Color} cycles hue over time to confirm
 *       per-frame UBO uploads via {@code materialPropsDirty}.</li>
 *   <li><b>Render state</b> — {@code dual_path_test.shader} declares the full RenderState
 *       block (Blend, DepthTest, DepthWrite, Cull, AlphaTest, ColorMask, Stencil). GL state
 *       is saved via {@code CgGlScope} on {@code bind()} and restored on {@code unbind()} —
 *       NOT reset to defaults.</li>
 *   <li><b>drawChain</b> — the main material chains to an outline pass ({@code CULL FRONT},
 *       vertex extrusion along normal) using {@link CgMaterial#drawChain(Runnable)}.
 *       The draw command runs once per pass in the chain.</li>
 *   <li><b>Instanced drawChain</b> — same chain API, different draw command; confirms
 *       the chain iterates correctly over both passes for instanced draws.</li>
 *   <li><b>Keyword variants</b> — four {@code CgMaterial.newInstance()} objects loaded from the same
 *       {@code feature_keyword_test.shader}. Each has a different {@code enableKeyword()} combination
 *       ({@code TINT_ENABLED}, {@code EMISSION_ENABLED}, {@code GRID_OVERLAY}), producing four
 *       distinct {@code ProgramKey} entries in the shared {@link io.github.somehussar.crystalgraphics.gl.material.CgMaterialShader}
 *       program cache. The four cubes rendered above the main cubes at Y=3 confirm lazy compilation,
 *       cache sharing, and independent property values.</li>
 * </ul>
 *
 * <h3>What to look for when running</h3>
 * <ol>
 *   <li>Single cube at origin with animated tint (hue cycling) — confirms UBO upload works.</li>
 *   <li>Orange outline on the single cube — confirms {@code drawChain} second pass runs.</li>
 *   <li>11 118 instanced cubes with per-instance {@code custom0} color — confirms instanced path.</li>
 *   <li>Orange outline on all instanced cubes — confirms chain runs per instanced draw too.</li>
 *   <li>No GL errors logged — confirmed by {@link GlErrorChecker} after each draw group.</li>
 *   <li>Row of 4 cubes at Y=3 — from left: plain grey, blue tinted, dark with orange pulse,
 *       green+grid+purple-pulse. Confirms keyword program cache: each cube uses a distinct GL
 *       program compiled from the same shader source.</li>
 * </ol>
 */
public class CgMaterialDualPathScene implements InteractiveSceneLifecycle {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private boolean running = true;

    // ── Wave-1 dual-path resources ─────────────────────────────────────────────

    private CgMaterial material;
    private CgMaterial outlineMaterial;
    private CgMesh mesh;
    private CgMaterialPipeline pipeline;

    // ── MRT section resources ─────────────────────────────────────────────────

    private CgFrameBuffer mrtFbo;
    private CgMaterial mrtMaterial;
    /** Dedicated fullscreen NDC quad: quad2D(-1,-1,1,1), z=0, CCW winding. */
    private CgMesh mrtMesh;
    private boolean mrtReadbackDone = false;

    // ── Keyword variant demo resources ────────────────────────────────────────
    // Four independent material instances sharing one CgMaterialShader (same .shader path).
    // Each has a different keyword combination to prove per-instance program cache entries.

    /** No features active — plain base color. */
    private CgMaterial kwNone;
    /** TINT_ENABLED only. */
    private CgMaterial kwTint;
    /** EMISSION_ENABLED only. */
    private CgMaterial kwEmission;
    /** TINT_ENABLED + EMISSION_ENABLED + GRID_OVERLAY. */
    private CgMaterial kwAll;
    /** Shared unit cube for all four keyword demo draws. */
    private CgMesh kwMesh;
    private boolean kwLoggedOnce = false;

    private static final Matrix4f SCRATCH_4 = new Matrix4f();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void init(HarnessContext ctx) {
        pipeline = CgMaterialPipeline.getInstance();

        // Main material — loads dual_path_test.shader (full RenderState + Properties block)
        material = CgMaterial.load("assets/harness/shader/dual_path_test.shader");

        // Set initial property values via the cached CgMaterialBindingsAdapter.
        // Non-sampler props (_Color, _Roughness, _Speed) → CgMaterialBlock UBO.
        // Sampler props (_MainTex etc.) are not set here — CgFallbackTextures handle defaults.
        material.applyProperties(b -> {
            b.vec4("_Color",     1f, 1f, 1f, 1f);  // white tint — will animate in render()
            b.set1f("_Roughness", 0.4f);
            b.set1f("_Metallic",  0.0f);
            b.set1f("_Speed",     2.0f);
        });

        // Outline material — second pass: CULL FRONT, extrudes vertices along normal
        outlineMaterial = CgMaterial.load("assets/harness/shader/dual_path_outline.shader");
        outlineMaterial.applyProperties(b -> {
            b.vec4("_OutlineColor", 1f, 0.5f, 0f, 1f); // orange
            b.set1f("_OutlineWidth", 0.04f);
        });

        // Wire chain: material → outlineMaterial.
        // drawChain(cmd) will: bind material → cmd → unbind, then bind outline → cmd → unbind.
        material.setNextPass(outlineMaterial);

        mesh = CgMeshBuilder.unitCube(CgVertexFormat.SPATIAL).upload();

        // ── MRT section: 3-attachment FBO + sentinel material ─────────────────
        CgFrameBufferFormat mrtFormat = CgFrameBufferFormat.builder("mrt_test")
                .color(0, CgTextureType.RGBA8)
                .color(1, CgTextureType.RGBA8)
                .color(2, CgTextureType.RGBA8)
                .depth(CgTextureType.DEPTH24_STENCIL8)
                .build();
        
        mrtFbo = CgFrameBuffer.create("mrt_test", 256, 256, mrtFormat);
 
   
        mrtMaterial = CgMaterial.load("assets/harness/shader/mrt_sentinel.shader");
        mrtMesh = CgMeshBuilder.quad2D(CgVertexFormat.SPATIAL, -1f, -1f, 1f, 1f).upload();

        // ── Keyword variant demo ───────────────────────────────────────────────────
        // All four share the same underlying CgMaterialShader (same path, newInstance() API).
        // Each has an independent propStore + enabledKeywords → independent ProgramKey in the cache.
        kwMesh = CgMeshBuilder.unitCube(CgVertexFormat.SPATIAL).upload();

        kwNone = CgMaterial.newInstance("assets/harness/shader/feature_keyword_test.shader");
        kwNone.applyProperties(b -> b.vec4("_BaseTint", 0.7f, 0.7f, 0.7f, 1.0f));

        kwTint = CgMaterial.newInstance("assets/harness/shader/feature_keyword_test.shader");
        kwTint.enableKeyword("TINT_ENABLED");
        kwTint.applyProperties(b -> b.vec4("_BaseTint", 0.2f, 0.6f, 1.0f, 1.0f));

        kwEmission = CgMaterial.newInstance("assets/harness/shader/feature_keyword_test.shader");
        kwEmission.enableKeyword("EMISSION_ENABLED");
        kwEmission.applyProperties(b -> {
            b.vec4("_BaseTint", 0.15f, 0.15f, 0.15f, 1.0f);
            b.vec4("_EmissionColor", 1.0f, 0.4f, 0.0f,1f);
        });

        kwAll = CgMaterial.newInstance("assets/harness/shader/feature_keyword_test.shader");
        kwAll.enableKeyword("TINT_ENABLED");
        kwAll.enableKeyword("EMISSION_ENABLED");
        kwAll.enableKeyword("GRID_OVERLAY");
        kwAll.applyProperties(b -> {
            b.vec4("_BaseTint", 0.0f, 0.8f, 0.3f, 1.0f);
            b.vec4("_EmissionColor", 0.8f, 0.0f, 0.8f,1f);
            b.set1f("_GridScale", 6.0f);
        });
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        float t = (float) frame.getElapsedTime();
        Matrix4f view       = ctx.getCamera3D().getViewMatrix();
        Matrix4f projection = ctx.getProjection();

        CgFrameUniforms fu = pipeline.getFrameUniforms();
        fu.view(view).proj(projection).timeSecs(t)
          .viewportW(ctx.getScreenWidth()).viewportH(ctx.getScreenHeight());
        pipeline.beginFrame();

        // Animate _Color — hue cycles over 4 s. Confirms per-frame UBO re-upload via
        // materialPropsDirty (set by applyBindings()) and the cached bindingsAdapter.
        float hue = (t % 4f) / 4f;
        float[] rgb = hsvToRgb(hue, 0.8f, 1f);
        //material.applyProperties(b -> b.vec4("_Color", rgb[0], rgb[1], rgb[2], 0.9f));

        // ── drawChain: non-instanced (1 cube at origin) ───────────────────────
        // Writes one object record then lets drawChain run both passes.
        CgShaderBuffer objectBuffer = pipeline.objectBuffer();
        CgBufferWriter w = objectBuffer.beginWrite(1);
        w.beginRecord()
         .mat4("modelMatrix", SCRATCH_4.identity().translation(0f, 0f, 0f))
         .mat4("normalMatrix", SCRATCH_4.identity())
         .vec4("custom0", rgb[0], rgb[1], rgb[2], 1f);
        objectBuffer.endRecord();
        objectBuffer.endWrite();

        // drawChain: pass 1 (material, Blend+DepthTest+Stencil etc.) then
        //            pass 2 (outlineMaterial, Cull FRONT, orange extrusion)
        material.drawChain(mesh::drawDirect);
        GlErrorChecker.assertNoGlError("CgMaterialDualPathScene.drawChain.direct");

        // ── drawChain: instanced (11 118 cubes across X) ──────────────────────
        int N = 11_118;
        w = objectBuffer.beginWrite(N);
        for (int i = 0; i < N; i++) {
            float r = (i & 1) == 0 ? 1f : 0.25f;
            float g = (i & 2) == 0 ? 1f : 0.25f;
            float b = (i & 4) == 0 ? 1f : 0.25f;
            w.beginRecord()
             .mat4("modelMatrix", SCRATCH_4.identity().translation(i * 1.5f, 0f, -5f))
             .mat4("normalMatrix", SCRATCH_4.identity())
             .vec4("custom0", r, g, b, 1f);
            objectBuffer.endRecord();
        }
        objectBuffer.endWrite();

        material.drawChain(() -> mesh.drawInstanced(N));
        GlErrorChecker.assertNoGlError("CgMaterialDualPathScene.drawChain.instanced");

        // ── MRT section ───────────────────────────────────────────────────────
        if (mrtFbo != null && mrtMaterial != null && mrtMesh != null) {
            IntBuffer savedViewport = BufferUtils.createIntBuffer(16);
            GL11.glGetInteger(GL11.GL_VIEWPORT, savedViewport);

            mrtFbo.bind();
            mrtFbo.drawBuffers(0, 1, 2);
            GlErrorChecker.assertNoGlError("CgMrtSection.drawBuffers");

            GL11.glViewport(0, 0, 256, 256);
            GL11.glClearColor(0f, 0f, 0f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            CgShaderBuffer mrtBuf = pipeline.objectBuffer();
            CgBufferWriter mw = mrtBuf.beginWrite(1);
            mw.beginRecord()
              .mat4("modelMatrix", SCRATCH_4.identity())
              .mat4("normalMatrix", SCRATCH_4.identity());
            mrtBuf.endRecord();
            mrtBuf.endWrite();

            mrtMaterial.bind();
            mrtMesh.drawDirect();
            mrtMaterial.unbind();
            GlErrorChecker.assertNoGlError("CgMrtSection.draw");

            if (!mrtReadbackDone) {
                mrtReadbackDone = true;
                ByteBuffer pixel = BufferUtils.createByteBuffer(4);

                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                GL11.glReadPixels(128, 128, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
                float r0 = (pixel.get(0) & 0xFF) / 255.0f;
                LOGGER.info("[CgMrtSection] RT0 {} (r={})", r0 > 0.9f ? "PASS" : "FAIL", r0);
                pixel.clear();

                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT1);
                GL11.glReadPixels(128, 128, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
                float g1 = (pixel.get(1) & 0xFF) / 255.0f;
                LOGGER.info("[CgMrtSection] RT1 {} (g={})", g1 > 0.9f ? "PASS" : "FAIL", g1);
                pixel.clear();

                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT2);
                GL11.glReadPixels(128, 128, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
                float b2 = (pixel.get(2) & 0xFF) / 255.0f;
                LOGGER.info("[CgMrtSection] RT2 {} (b={})", b2 > 0.9f ? "PASS" : "FAIL", b2);
            }

            mrtFbo.unbind();
            GL11.glViewport(savedViewport.get(0), savedViewport.get(1),
                    savedViewport.get(2), savedViewport.get(3));
        }

        // ── Keyword variant demo ───────────────────────────────────────────────
        // Draw 4 cubes in a row at Y=3, spaced 2.5 units apart on X.
        // One beginWrite(1)/endWrite/drawInstanced(1) cycle per material so that each
        // draw reads its own record at gl_InstanceID=0 (the correct per-cube position).
        {
            CgShaderBuffer kwBuf = pipeline.objectBuffer();

            CgBufferWriter kw = kwBuf.beginWrite(1);
            kw.beginRecord()
              .mat4("modelMatrix",  SCRATCH_4.identity().translation(-3.75f, 3f, 0f))
              .mat4("normalMatrix", SCRATCH_4.identity())
              .vec4("custom0",      1f, 1f, 1f, 1f);
            kwBuf.endRecord();
            kwBuf.endWrite();
            kwNone.bind();
            kwMesh.drawInstanced(1);
            kwNone.unbind();

            kw = kwBuf.beginWrite(1);
            kw.beginRecord()
              .mat4("modelMatrix",  SCRATCH_4.identity().translation(-1.25f, 3f, 0f))
              .mat4("normalMatrix", SCRATCH_4.identity())
              .vec4("custom0",      1f, 1f, 1f, 1f);
            kwBuf.endRecord();
            kwBuf.endWrite();
            kwTint.bind();
            kwMesh.drawInstanced(1);
            kwTint.unbind();

            kw = kwBuf.beginWrite(1);
            kw.beginRecord()
              .mat4("modelMatrix",  SCRATCH_4.identity().translation(1.25f, 3f, 0f))
              .mat4("normalMatrix", SCRATCH_4.identity())
              .vec4("custom0",      1f, 1f, 1f, 1f);
            kwBuf.endRecord();
            kwBuf.endWrite();
            kwEmission.bind();
            kwMesh.drawInstanced(1);
            kwEmission.unbind();

            kw = kwBuf.beginWrite(1);
            kw.beginRecord()
              .mat4("modelMatrix",  SCRATCH_4.identity().translation(3.75f, 3f, 0f))
              .mat4("normalMatrix", SCRATCH_4.identity())
              .vec4("custom0",      1f, 1f, 1f, 1f);
            kwBuf.endRecord();
            kwBuf.endWrite();
            kwAll.bind();
            kwMesh.drawInstanced(1);
            kwAll.unbind();

            GlErrorChecker.assertNoGlError("CgMaterialDualPathScene.keywordDemo");
        }

        if (!kwLoggedOnce) {
            kwLoggedOnce = true;
            LOGGER.info("[KwDemo] kwNone     keywords=[] shader={}",
                    System.identityHashCode(kwNone.getShader()));
            LOGGER.info("[KwDemo] kwTint     keywords=[TINT_ENABLED] shader={}",
                    System.identityHashCode(kwTint.getShader()));
            LOGGER.info("[KwDemo] kwEmission keywords=[EMISSION_ENABLED] shader={}",
                    System.identityHashCode(kwEmission.getShader()));
            LOGGER.info("[KwDemo] kwAll      keywords=[TINT_ENABLED,EMISSION_ENABLED,GRID_OVERLAY] shader={}",
                    System.identityHashCode(kwAll.getShader()));
            boolean allDistinct =
                    kwNone.getShader()     != kwTint.getShader() &&
                    kwTint.getShader()     != kwEmission.getShader() &&
                    kwEmission.getShader() != kwAll.getShader();
            LOGGER.info("[KwDemo] All programs distinct: {}", allDistinct ? "YES ✓" : "NO — UNEXPECTED");
        }
    }

    // ── Dispose ───────────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        if (mesh != null) mesh.delete();
        if (material != null) { material.setNextPass(null); material.delete(); }
        if (outlineMaterial != null) outlineMaterial.delete();
        if (mrtMaterial != null) mrtMaterial.delete();
        if (mrtMesh != null) mrtMesh.delete();
        if (mrtFbo != null) mrtFbo.delete();
        if (kwNone     != null) kwNone.delete();
        if (kwTint     != null) kwTint.delete();
        if (kwEmission != null) kwEmission.delete();
        if (kwAll      != null) kwAll.delete();
        if (kwMesh     != null) kwMesh.delete();
    }

    @Override public boolean isRunning()              { return running; }
    @Override public boolean uses3DCamera()           { return true; }
    @Override public boolean shouldShutdownOnComplete() { return true; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static float[] hsvToRgb(float h, float s, float v) {
        int hi = (int)(h * 6f) % 6;
        float f  = h * 6f - (int)(h * 6f);
        float p  = v * (1f - s);
        float q  = v * (1f - f * s);
        float t  = v * (1f - (1f - f) * s);
        switch (hi) {
            case 0: return new float[]{v, t, p};
            case 1: return new float[]{q, v, p};
            case 2: return new float[]{p, v, t};
            case 3: return new float[]{p, q, v};
            case 4: return new float[]{t, p, v};
            default: return new float[]{v, p, q};
        }
    }
}
