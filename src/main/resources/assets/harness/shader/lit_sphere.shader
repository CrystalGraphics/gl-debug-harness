// lit-spheres' ball: Unreal's Default Lit, translucent at full opacity so the world's deferred apply never adds over it.
#domain surface
Material { BlendMode Translucent  ShadingModel DefaultLit  Fog Off }

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    s.baseColor = vec3(0.8);
    s.roughness = 1.0;
    s.specular = 0.0;
    s.opacity = 1.0;
}
