// A mesh material drawn as sprites (vfx-renderers): on the MeshParticles factory, its sprite program compiled with
// FX_ON_SPRITES and so reading no vertex data, drawn on CgMesh.quads as Unreal compiles one material per vertex factory
// it is used with.
#domain surface
Material { BlendMode Additive  ShadingModel Unlit  Faces Both  VertexFactory MeshParticles  PerDrawValues On }

Properties {
    _Color ("Tint", color) = (0.4, 0.8, 1, 1)
}

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = v.uv0;
}

void surface(in v2f i, inout CgSurface s) {
    float round_ = 1.0 - smoothstep(0.3, 0.5, length(i.uv - 0.5));
    s.baseColor = _Color.rgb * cg_Particle.color.rgb * round_;
}
