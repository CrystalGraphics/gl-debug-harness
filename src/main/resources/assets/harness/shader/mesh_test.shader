// mesh-test's material: an 8x8 checker of each vertex's UV, both faces.
#domain surface
Material { Faces Both }

struct v2f {
    vec2 uv;
};

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    vec2 grid = floor(i.uv * 8.0);
    vec4 color = mix(vec4(0.2, 0.2, 0.2, 1.0), vec4(0.9, 0.9, 0.9, 1.0), mod(grid.x + grid.y, 2.0));
    s.baseColor = color.rgb;
}
