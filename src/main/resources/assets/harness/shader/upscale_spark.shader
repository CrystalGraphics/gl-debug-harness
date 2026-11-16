// A spark for the temporal-upscale gate: translucent, drawn apart after depth of field (separate translucency), which a
// temporal upscaler composes itself. custom0 its colour and, in a, twice its opacity (as forward_transparent_test reads it).
#domain surface
Material { BlendMode Translucent  ShadingModel Unlit  TranslucencyPass AfterDOF }

struct v2f { vec2 uv0; };
void surface(in v2f i, inout CgSurface s) {
    s.emissive = CG_OBJECT_CUSTOM0.rgb;
    s.opacity = clamp(CG_OBJECT_CUSTOM0.a * 0.5, 0.0, 1.0);
}
