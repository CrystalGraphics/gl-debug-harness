// velocity-conformance's plain solid: an unlit colour from custom0, no vertex() of its own (the factory's fill alone).
#domain surface
Material { ShadingModel Unlit  ColorSpace Linear  Fog Off }

struct v2f {
    vec3 normal;
};

void surface(in v2f i, inout CgSurface s) {
    s.emissive = CG_OBJECT_CUSTOM0.rgb;
}
