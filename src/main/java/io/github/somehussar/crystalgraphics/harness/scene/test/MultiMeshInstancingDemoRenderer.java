package io.github.somehussar.crystalgraphics.harness.scene.test;

import io.github.somehussar.crystalgraphics.api.mesh.CgMeshData;
import io.github.somehussar.crystalgraphics.api.mesh.CgMeshTopology;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.state.CgBlendState;
import io.github.somehussar.crystalgraphics.api.state.CgCullState;
import io.github.somehussar.crystalgraphics.api.state.CgDepthState;
import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
import io.github.somehussar.crystalgraphics.api.vertex.CgInstanceFormat;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgInstanceWriter;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMesh;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMeshBuilder;
import io.github.somehussar.crystalgraphics.gl.render.CgInstanceRenderer;
import io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

/**
 * Developer-ergonomics sample for drawing multiple instanced mesh shapes.
 *
 * <p>Demonstrates the intended frontend flow for static-mesh instancing:
 * build a {@link CgMesh} once at init, then draw it N times per frame
 * using {@link CgInstanceRenderer} with per-instance transform + color data.</p>
 *
 * <p>Three shapes are rendered: quads (row), triangles (row), diamonds (row).</p>
 */
public final class MultiMeshInstancingDemoRenderer {

    // Shader uses POS3_UV2_COL4UB layout:
    //   location 0: vec3 a_pos  (3 floats, z is always 0 for 2-D shapes)
    //   location 1: vec2 a_uv
    //   location 2: vec4 a_color (4 ubytes, normalized)
    //   locations 3-6: mat4 per-instance model matrix (4 × vec4, divisor=1)
    //   location 7: vec4 per-instance tint color (divisor=1)
    //   location 8: vec4 per-instance custom data (divisor=1)
    private static final String VERT =
        "#version 330 core\n"
        + "layout(location = 0) in vec3 a_pos;\n"
        + "layout(location = 1) in vec2 a_uv;\n"
        + "layout(location = 2) in vec4 a_color;\n"
        + "layout(location = 3) in vec4 a_instanceModel0;\n"
        + "layout(location = 4) in vec4 a_instanceModel1;\n"
        + "layout(location = 5) in vec4 a_instanceModel2;\n"
        + "layout(location = 6) in vec4 a_instanceModel3;\n"
        + "layout(location = 7) in vec4 a_instanceColor;\n"
        + "layout(location = 8) in vec4 a_instanceCustom;\n"
        + "uniform mat4 u_projection;\n"
        + "out vec4 v_color;\n"
        + "void main() {\n"
        + "    mat4 model = mat4(a_instanceModel0, a_instanceModel1, a_instanceModel2, a_instanceModel3);\n"
        + "    gl_Position = u_projection * model * vec4(a_pos, 1.0);\n"
        + "    v_color = a_color * a_instanceColor;\n"
        + "    v_color.rgb += a_instanceCustom.rgb * 0.08;\n"
        + "}\n";

    private static final String FRAG =
        "#version 330 core\n"
        + "in vec4 v_color;\n"
        + "out vec4 fragColor;\n"
        + "void main() { fragColor = v_color; }\n";

    private static final CgVertexFormat SHAPE_FORMAT = CgVertexFormat.POS3_UV2_COL4UB;

    private CgShader shader;
    private CgRenderState state;

    // Static base meshes — built once, drawn many times per frame via CgInstanceRenderer.
    private CgMesh quadMesh;
    private CgMesh triangleMesh;
    private CgMesh diamondMesh;

    // One CgInstanceRenderer per shape type.
    private CgInstanceRenderer quadRenderer;
    private CgInstanceRenderer triangleRenderer;
    private CgInstanceRenderer diamondRenderer;

    public void init() {
        shader = CgShaderFactory.fromSource(VERT, FRAG);
        // TODO: CgRenderState no longer carries a shader — shader must be bound separately
        //       via CgMaterial.bind() once CgRenderLayer is migrated to the material framework.
        state = CgRenderState.builder()
                .blend(CgBlendState.ALPHA)
                .depth(CgDepthState.NONE)
                .cull(CgCullState.NONE)
                .build();

        quadMesh     = CgMesh.upload(CgMeshBuilder.quad2D(SHAPE_FORMAT, -1f, -1f, 1f, 1f));
        triangleMesh = CgMesh.upload(buildUnitTriangle());
        diamondMesh  = CgMesh.upload(buildUnitDiamond());

        quadRenderer     = CgInstanceRenderer.create(quadMesh,     CgInstanceFormat.TRANSFORM_COLOR_CUSTOM, 32);
        triangleRenderer = CgInstanceRenderer.create(triangleMesh, CgInstanceFormat.TRANSFORM_COLOR_CUSTOM, 32);
        diamondRenderer  = CgInstanceRenderer.create(diamondMesh,  CgInstanceFormat.TRANSFORM_COLOR_CUSTOM, 32);
    }

    public boolean isCompiled() {
        return shader != null && shader.isCompiled();
    }

    public String getLastCompileError() {
        return shader != null ? shader.getLastCompileError() : "shader not initialized";
    }

    public void render(Matrix4f projection, int width, int height) {
        renderQuads(projection, width, height);
        renderTriangles(projection, width, height);
        renderDiamonds(projection, width, height);
    }

    public void delete() {
        if (quadRenderer     != null) quadRenderer.delete();
        if (triangleRenderer != null) triangleRenderer.delete();
        if (diamondRenderer  != null) diamondRenderer.delete();
        if (quadMesh         != null) quadMesh.delete();
        if (triangleMesh     != null) triangleMesh.delete();
        if (diamondMesh      != null) diamondMesh.delete();
        if (shader           != null) shader.delete();
    }

    // ── Per-shape render passes ───────────────────────────────────────────────

    private void renderQuads(Matrix4f projection, int width, int height) {
        quadRenderer.begin();
        for (int i = 0; i < 12; i++) {
            float x = 40.0f + i * 34.0f;
            float y = height - 80.0f;
            writeInstance(quadRenderer.instance(), x, y, 12.0f,
                    255, 120 + i * 8, 80, 220,
                    1.0f, 0.2f, 0.0f, 1.0f);
        }
        // TODO: projection binding must be restored via CgMaterial once migration is complete.
                state.apply();
        quadRenderer.flush();
        state.clear();
        quadRenderer.end();
    }

    private void renderTriangles(Matrix4f projection, int width, int height) {
        triangleRenderer.begin();
        for (int i = 0; i < 10; i++) {
            float x = width - 360.0f + i * 32.0f;
            float y = height - 135.0f;
            writeInstance(triangleRenderer.instance(), x, y, 15.0f,
                    80, 160, 255, 220,
                    0.0f, 0.6f, 1.0f, 1.0f);
        }
        // TODO: projection binding must be restored via CgMaterial once migration is complete.
                state.apply();
        triangleRenderer.flush();
        state.clear();
        triangleRenderer.end();
    }

    private void renderDiamonds(Matrix4f projection, int width, int height) {
        diamondRenderer.begin();
        for (int i = 0; i < 8; i++) {
            float x = width * 0.5f - 140.0f + i * 40.0f;
            float y = height - 35.0f;
            writeInstance(diamondRenderer.instance(), x, y, 14.0f,
                    160, 255, 110, 220,
                    0.3f, 1.0f, 0.2f, 1.0f);
        }
        // TODO: projection binding must be restored via CgMaterial once migration is complete.
                state.apply();
        diamondRenderer.flush();
        state.clear();
        diamondRenderer.end();
    }

    // ── Instance writer ───────────────────────────────────────────────────────

    private static void writeInstance(CgInstanceWriter w, float x, float y, float scale,
                                      int r, int g, int b, int a,
                                      float cx, float cy, float cz, float cw) {
        Matrix4f model = new Matrix4f().translation(x, y, 0.0f).scale(scale, scale, 1.0f);
        w.mat4(model)
         .color(r, g, b, a)
         .vec4(cx, cy, cz, cw)
         .endInstance();
    }

    // ── Static mesh builders for simple 2-D shapes ───────────────────────────

    /**
     * Unit triangle: apex at (0, 1, 0), base corners at (±1, -1, 0).
     * Non-indexed triangle soup (3 vertices, TRIANGLES topology).
     */
    private static CgMeshData buildUnitTriangle() {
        ByteBuffer vbo = BufferUtils.createByteBuffer(3 * SHAPE_FORMAT.getStride());
        CgVertexWriter w = CgVertexWriter.forBuffer(vbo, SHAPE_FORMAT);
        w.vertex( 0f,  1f, 0f).uv(0.5f, 0f  ).color(255, 255, 255, 255).endVertex();
        w.vertex( 1f, -1f, 0f).uv(1f,   1f  ).color(255, 255, 255, 255).endVertex();
        w.vertex(-1f, -1f, 0f).uv(0f,   1f  ).color(255, 255, 255, 255).endVertex();
        vbo.flip();
        return new CgMeshData(SHAPE_FORMAT, CgMeshTopology.TRIANGLES, vbo, null, 0);
    }

    /**
     * Unit diamond: two triangles forming a diamond shape from (0,±1,0) to (±1,0,0).
     * Non-indexed triangle soup (6 vertices, TRIANGLES topology).
     */
    private static CgMeshData buildUnitDiamond() {
        ByteBuffer vbo = BufferUtils.createByteBuffer(6 * SHAPE_FORMAT.getStride());
        CgVertexWriter w = CgVertexWriter.forBuffer(vbo, SHAPE_FORMAT);
        // Top half
        w.vertex( 0f,  1f, 0f).uv(0.5f, 0f  ).color(255, 255, 255, 255).endVertex();
        w.vertex( 1f,  0f, 0f).uv(1f,   0.5f).color(255, 255, 255, 255).endVertex();
        w.vertex(-1f,  0f, 0f).uv(0f,   0.5f).color(255, 255, 255, 255).endVertex();
        // Bottom half
        w.vertex( 0f, -1f, 0f).uv(0.5f, 1f  ).color(255, 255, 255, 255).endVertex();
        w.vertex(-1f,  0f, 0f).uv(0f,   0.5f).color(255, 255, 255, 255).endVertex();
        w.vertex( 1f,  0f, 0f).uv(1f,   0.5f).color(255, 255, 255, 255).endVertex();
        vbo.flip();
        return new CgMeshData(SHAPE_FORMAT, CgMeshTopology.TRIANGLES, vbo, null, 0);
    }
}
