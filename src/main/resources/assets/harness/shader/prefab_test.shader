// vfx-prefab's material (CgVfxPrefabScene): a prefab node's mesh in _Color times its presence, premultiplied, so a
// node at presence 0 leaves the target black and one below 1 dims. Its front faces alone, so one layer blends.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/draw.glsl"
Material { BlendMode AlphaComposite  ShadingModel Unlit  DepthTest Off  Fog Off  PerDrawValues On }

Properties {
    _Color ("Its colour", color) = (1, 1, 1, 1)
}

struct v2f { float unused; };

void vertex(inout CgVertex v, out v2f o) {
    o.unused = 0.0;
    v.world = ((CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0))).xyz;
}

void surface(in v2f i, inout CgSurface s) {
    s.baseColor = _Color.rgb * cg_Vfx.presence;
    s.opacity = cg_Vfx.presence;
}
