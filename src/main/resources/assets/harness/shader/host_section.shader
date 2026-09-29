#type spatial

// The host-section scene's slabs: a flat colour from the per-instance custom0, depth-tested LEQUAL so a slab
// drawn at the same depth after another covers it -- which is exactly what a depth func left behind by a host
// (LESS) would break.

Queue = "Geometry"

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        DepthTest LEQUAL
        Cull OFF
        ColorMask RGBA
    }

    struct v2f {
        vec3 localPos;
    };

    void vertex(out v2f o) {
        o.localPos = cg_Position;
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = CG_OBJECT_CUSTOM0;
    }
}
