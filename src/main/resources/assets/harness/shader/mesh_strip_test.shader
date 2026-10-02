#type none

// A triangle strip placed from CG_VERTEX_ID alone: a wavy band.

Queue = "Geometry"

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend ONE ZERO
        DepthTest LEQUAL
        DepthWrite ON
        Cull OFF
    }

    struct v2f {
        vec3 color;
    };

    void vertex(out v2f o) {
        float x = float(CG_VERTEX_ID / 2) * 0.1;
        float y = float(CG_VERTEX_ID % 2) * 0.3 + sin(x * 3.0) * 0.2;
        o.color = vec3(1.0, 0.6, 0.1) * (0.5 + 0.5 * float(CG_VERTEX_ID % 2));
        gl_Position = CG_MATRIX_MVP * vec4(x, y, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(i.color, 1.0);
    }
}
