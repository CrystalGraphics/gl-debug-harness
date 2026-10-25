#type spatial
// A "Surface"-lit pane for lights-volume: lit by the world's lights at the normal its draw's custom1 states.

Tags { "RenderType" = "Transparent" "Lighting" = "Surface" }
Queue = "Transparent"

struct v2f {
    vec3 n;
};

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull BACK
    }

    void vertex(out v2f o) {
        o.n = CG_OBJECT_CUSTOM1.xyz;
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        CG_SURFACE_NORMAL(normalize(i.n));
        fragColor = CG_OBJECT_CUSTOM0;
    }
}
