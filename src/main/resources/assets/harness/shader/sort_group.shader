// vfx-sort-group's layer (CgVfxSortGroupScene): the slot's sphere in _Color, solid, over whatever drew before it, so
// the colour left at its middle is the layer that drew last.
#domain surface
Material { BlendMode Translucent  ShadingModel Unlit  Faces Both  DepthTest Off  Fog Off }

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
