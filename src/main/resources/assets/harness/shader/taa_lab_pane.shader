// taa-lab's pane: a Masked, dithered surface (DitherOpacityMask) whose mask is custom0's alpha, so temporal
// anti-aliasing settles its noise to that much coverage; unlit in custom0's colour.
#domain surface
Material { BlendMode Masked  DitherOpacityMask On  ShadingModel Unlit  Faces Both  Fog Off }

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = CG_OBJECT_CUSTOM0.rgb;
    s.opacityMask = CG_OBJECT_CUSTOM0.a;
}
