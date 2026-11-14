// post-looks' graded ball: a translucent glow past white, premultiplied, in linear light, as a kit's effect draws. Its
// light is what a graded layer holds; what it covers, its alpha.
#domain surface
Material { BlendMode AlphaComposite  ShadingModel Unlit  Fog Off  ColorSpace Linear }

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = vec3(2.4, 0.9, 0.3) * 0.8;
    s.opacity = 0.8;
}
