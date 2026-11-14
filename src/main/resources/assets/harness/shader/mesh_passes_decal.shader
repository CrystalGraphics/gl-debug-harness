// mesh-passes' decal (CgMeshPassesTestScene): pure red, unlit, wherever its box meets the floor.
#domain decal
Material { DecalBlendMode Translucent  ShadingModel Unlit  Fog Off }

struct v2f {
    vec2 uv0;
};

void surface(in v2f i, inout CgSurface s) {
    s.emissive = vec3(1.0, 0.0, 0.0);
    s.opacity = 1.0;
}
