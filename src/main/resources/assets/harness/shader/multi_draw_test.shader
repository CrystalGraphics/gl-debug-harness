// multi-draw's strips (CgMultiDrawTestScene): a mesh in the unit square, placed at CG_OBJECT_CUSTOM0.xy and scaled by
// CG_OBJECT_CUSTOM0.z, coloured CG_OBJECT_CUSTOM1 with its first two vertices brighter, so a draw's base instance and
// its base vertex both show in the picture.
#domain surface
Material { ShadingModel Unlit  Faces Both  DepthTest Off  DepthWrite Off  Fog Off }

struct v2f {
    vec4 color;
};

void vertex(inout CgVertex v, out v2f o) {
    vec2 p = CG_OBJECT_CUSTOM0.xy + cg_Position.xy * CG_OBJECT_CUSTOM0.z;
    o.color = vec4(CG_OBJECT_CUSTOM1.rgb * (CG_VERTEX_ID < 2 ? 1.0 : 0.5), 1.0);
    v.world = (CG_OBJECT_TO_WORLD * vec4(p, 0.0, 1.0)).xyz;
}

void surface(in v2f i, inout CgSurface s) {
    vec4 c = i.color;
    s.baseColor = c.rgb;
}
