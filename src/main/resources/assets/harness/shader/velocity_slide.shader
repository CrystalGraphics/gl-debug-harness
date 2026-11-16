// velocity-conformance's vertex() material: its own vertex() moves the vertex (by custom1.y metres a second of CG_TIME
// past custom1.x; the gate sets that past every frame it draws, so it stands), which the previous-frame copy must reach.
#domain surface
Material { ShadingModel Unlit  ColorSpace Linear  Fog Off }

struct v2f {
    vec3 normal;
};

void vertex(inout CgVertex v, out v2f o) {
    o.normal = v.normal;
    v.world.x += max(CG_TIME - CG_OBJECT_CUSTOM1.x, 0.0) * CG_OBJECT_CUSTOM1.y;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = CG_OBJECT_CUSTOM0.rgb;
}
