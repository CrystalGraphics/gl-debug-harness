// mesh-passes' consumer pass (CgMeshPassesTestScene, "harness:thermal"): 1 wherever a primitive flagged as a heat
// source draws, into an R16F of its own.
#domain surface
Material { ShadingModel Unlit  Fog Off }

struct v2f {
    vec3 normal;
};

void surface(in v2f i, inout CgSurface s) {
    s.emissive = vec3(1.0);
}
