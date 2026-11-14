// A flat, opaque colour from the record's custom0: the harness's floor, drawn as a host's world is.
#domain surface
Material { ShadingModel Unlit  Faces Both  Fog Off }

struct v2f {
    vec3 localPos;
};

void vertex(inout CgVertex v, out v2f o) {
    o.localPos = cg_Position;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = CG_OBJECT_CUSTOM0.rgb;
}
