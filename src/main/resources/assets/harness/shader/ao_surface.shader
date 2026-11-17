// ambient-occlusion's floor, wall and box: Unreal's Default Lit, opaque, its albedo custom0's, no specular, so the sky's
// diffuse alone lights them.
#domain surface
Material { ShadingModel DefaultLit  Fog Off }

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    s.baseColor = CG_OBJECT_CUSTOM0.rgb;
    s.roughness = 1.0;
    s.specular = 0.0;
}
