#type spatial
// mesh-test's material: an 8x8 checker of each vertex's UV, both faces.

Queue = "Geometry"

struct v2f {
    vec2 uv;
};

Pass {
    RenderState {
        DepthTest LEQUAL
        Cull OFF
    }

    void vertex(out v2f o) {
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
        o.uv = cg_TexCoord0;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec2 grid = floor(i.uv * 8.0);
        fragColor = mix(vec4(0.2, 0.2, 0.2, 1.0), vec4(0.9, 0.9, 0.9, 1.0), mod(grid.x + grid.y, 2.0));
    }
}
