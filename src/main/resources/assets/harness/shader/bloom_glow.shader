// bloom-occlusion's glowing ball: an opaque surface that writes depth and glows. The glow must survive the depth its
// own surface wrote, and nothing of it may bloom from behind the wall.
#domain surface
Material { ShadingModel Unlit  Fog Off }

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    s.baseColor = vec3(1.0, 0.7, 0.3);
    s.emissive = vec3(6.0, 4.0, 1.5);
}
