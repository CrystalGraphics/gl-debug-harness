#type none

// Quads placed from CG_VERTEX_ID alone: a 32-wide grid, coloured by quad, so a range that continues numbering shows.

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
        int quad = CG_VERTEX_ID >> 2;
        int corner = CG_VERTEX_ID & 3;
        vec2 c = vec2((corner == 1 || corner == 2) ? 1.0 : 0.0, corner >= 2 ? 1.0 : 0.0);
        vec2 cell = vec2(float(quad % 32), float(quad / 32));
        vec3 p = vec3((cell + c * 0.8) * 0.1, 0.0);
        float t = float(quad) / 1024.0;
        o.color = vec3(fract(float(quad) / 32.0), t, 1.0 - t);
        gl_Position = CG_MATRIX_MVP * vec4(p, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(i.color, 1.0);
    }
}
