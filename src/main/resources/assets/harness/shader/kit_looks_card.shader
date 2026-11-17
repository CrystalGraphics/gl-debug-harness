// kit-looks' hair card: Masked, a 4 x 4 checker cut out of its quad, unlit in custom0's colour; what shows through
// its holes is the frame's, never its look's.
#domain surface
Material { BlendMode Masked  ShadingModel Unlit  Faces Both  Fog Off  ColorSpace Linear }

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = CG_OBJECT_CUSTOM0.rgb;
    ivec2 cell = ivec2(floor(i.uv * 4.0));
    s.opacityMask = float((cell.x + cell.y) & 1);
}
