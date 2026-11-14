// bloom-occlusion's wall: flat grey, opaque, writing depth.
#domain surface
Material { ShadingModel Unlit  Faces Both  Fog Off }

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    vec4 color = vec4(0.3, 0.3, 0.32, 1.0);
    s.emissive = color.rgb;
}
