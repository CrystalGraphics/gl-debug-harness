// post-looks' opaque graded ball: a kit's masked or opaque effect draw, saturated and past white, in linear light. Its
// alpha is below 1 on purpose: an opaque draw covers what is behind it whatever alpha it writes.
#domain surface
Material { ShadingModel Unlit  Fog Off  ColorSpace Linear  PerDrawValues On }

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    vec4 color = vec4(1.8, 0.5, 0.15, 0.4);
    s.baseColor = color.rgb;
}
