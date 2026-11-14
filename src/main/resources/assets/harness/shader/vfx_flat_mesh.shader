// vfx-mesh-sections' materials (CgVfxMeshSectionsScene): each mesh particle's section in _Color, solid.
#domain surface
Material { BlendMode Translucent  ShadingModel Unlit  Faces Both  DepthTest Off  Fog Off }

Keywords {
    FX_ON_MESH_PARTICLES ("FX_ON_MESH_PARTICLES")
}

Properties {
    _Color ("Its colour", color) = (1, 1, 1, 1)
}

struct v2f { float unused; };

void vertex(inout CgVertex v, out v2f o) {
    o.unused = 0.0;
    v.world = ((CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0))).xyz;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = _Color.rgb;
    s.opacity = 1.0;
}
