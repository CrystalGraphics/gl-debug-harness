// kit-looks' smoke: alpha-blended and nearly black, so it counts by what it hides rather than by the light it adds.
#domain surface
Material { BlendMode Translucent  ShadingModel Unlit  Fog Off  ColorSpace Linear  Faces Both }

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = vec3(0.02);
    s.opacity = 0.75;
}
