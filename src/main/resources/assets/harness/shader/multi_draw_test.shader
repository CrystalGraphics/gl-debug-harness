#type spatial

// multi-draw's strips (CgMultiDrawTestScene): a mesh in the unit square, placed at CG_OBJECT_CUSTOM0.xy and scaled by
// CG_OBJECT_CUSTOM0.z, coloured CG_OBJECT_CUSTOM1 with its first two vertices brighter, so a draw's base instance and
// its base vertex both show in the picture.

Tags { "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Geometry"

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend ONE ZERO
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    struct v2f {
        vec4 color;
    };

    void vertex(out v2f o) {
        vec2 p = CG_OBJECT_CUSTOM0.xy + cg_Position.xy * CG_OBJECT_CUSTOM0.z;
        o.color = vec4(CG_OBJECT_CUSTOM1.rgb * (CG_VERTEX_ID < 2 ? 1.0 : 0.5), 1.0);
        gl_Position = CG_MATRIX_MVP * vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = i.color;
    }
}
