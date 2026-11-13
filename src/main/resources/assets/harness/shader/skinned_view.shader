// skinned-play's surface (CgSkinnedPlayScene): CG_OBJECT_CUSTOM0's colour, half-Lambert lit from a fixed sun so a
// pose reads by its shading, then the world's lightmap as any material.
#domain surface
Material { Faces Both }

struct v2f {
    vec3 normal;
};

void vertex(inout CgVertex v, out v2f o) {
    o.normal = CG_NORMAL_MATRIX * cg_Normal;
}

void surface(in v2f i, inout CgSurface s) {
    vec3 n = normalize(i.normal);
    float lambert = dot(n, normalize(vec3(0.4, 0.8, 0.45))) * 0.5 + 0.5;
    s.baseColor = CG_OBJECT_CUSTOM0.rgb * (0.25 + 0.75 * lambert * lambert);
}
