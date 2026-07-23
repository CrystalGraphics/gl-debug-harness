// Test material for CgQuadRenderer — a shared unit quad, drawn instanced, with all
// per-instance data (world-space origin/right/up basis vectors, UV rect, color) read
// from an attached SSBO/TBO instead of GL vertex attributes. See
// CrystalGraphics/docs_research/CGTEXTRENDERER_INSTANCING_FOUNDATIONS.md Decision 5.
//
// Pure screen-space 2D, same as crystalgui:shaders/gui_quad.shader: intentionally does
// NOT reference CG_OBJECT_TO_WORLD / CG_MATRIX_MVP, so no per-instance object-buffer
// record is required before drawing — gl_Position comes from cg_ProjMatrix applied to
// the per-instance world position reconstructed from origin + a_pos.x*right + a_pos.y*up.

#type pos2_uv2_col4ub

Tags { "RenderType" = "Transparent" }
Queue = "Overlay"

Properties {
    _MainTex ("Main Texture", sampler2D) = "white"
}

struct v2f {
    vec2 uv;
    vec4 color;
};

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        // CG_QUAD_WORLD_POS/CG_QUAD_UV/CG_QUAD_COLOR (cg_env.glsl, always auto-included,
        // no #include needed) already know the QUAD_DATA(CG_INSTANCE_ID) fetch and the
        // origin+right*u+up*v reconstruction — no QuadInstance/QUAD_DATA boilerplate here.
        gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
        o.uv = CG_QUAD_UV;
        o.color = CG_QUAD_COLOR;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = texture(_MainTex, i.uv) * i.color;
    }
}
