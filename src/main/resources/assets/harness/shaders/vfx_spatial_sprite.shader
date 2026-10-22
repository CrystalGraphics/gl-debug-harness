// A mesh material drawn as sprites (vfx-renderers): #type spatial, its sprite program compiled with FX_ON_SPRITES and
// so reading no vertex data, drawn on CgMesh.quads as Unreal compiles one material per vertex factory it is used with.
#type spatial
#pragma cg_use particle
#pragma cg_use rows
#pragma cg_feature FX_ON_SPRITES
#pragma cg_feature FX_ON_MESH_PARTICLES

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Color ("Tint", color) = (0.4, 0.8, 1, 1)
}

struct v2f { vec2 uv; vec4 tint; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        CgMaterialVertex v = cg_material_vertex();
        CgMaterialParticle p = cg_material_particle();
        o.uv = v.texCoords[0];
        o.tint = p.color;
        gl_Position = cg_material_project(v);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float round_ = 1.0 - smoothstep(0.3, 0.5, length(i.uv - 0.5));
        fragColor = vec4(_Color.rgb * i.tint.rgb * round_, 0.0);
    }
}
