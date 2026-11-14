// skinned-mesh's colour (CgSkinnedMeshTestScene): the normal as a colour, so the GPU's skinning and the CPU's twin
// differ wherever a position or a normal does. Unlit and unfogged: the two halves compare as drawn.
#domain surface
Material { ShadingModel Unlit  Faces Both  DepthTest Off  DepthWrite Off  Fog Off }

struct v2f {
    vec3 normal;
};

void vertex(inout CgVertex v, out v2f o) {
    o.normal = CG_NORMAL_MATRIX * cg_Normal;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = normalize(i.normal) * 0.5 + 0.5;
}
