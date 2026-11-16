// taa-lab's wires and spinner: an unlit colour from custom0, bright against the dark, so a stair-stepped edge shows.
#domain surface
Material { ShadingModel Unlit  ColorSpace Linear  Fog Off }

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = CG_OBJECT_CUSTOM0.rgb * CG_OBJECT_CUSTOM0.a;
}
