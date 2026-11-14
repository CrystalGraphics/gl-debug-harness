// gpu-cull's instances and wall (CgGpuCullTestScene): CG_OBJECT_CUSTOM0's colour, shaded by the normal so each level's
// facets show; unlit and unfogged, so the CPU's cull and the GPU's draw the same pixels.
#domain surface
Material { ShadingModel Unlit  Fog Off }

struct v2f {
    vec3 normal;
    vec4 color;
};

void vertex(inout CgVertex v, out v2f o) {
    o.normal = CG_NORMAL_MATRIX * cg_Normal;
    o.color = CG_OBJECT_CUSTOM0;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = i.color.rgb * (0.55 + 0.45 * normalize(i.normal).z);
}
