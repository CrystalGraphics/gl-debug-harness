#type spatial
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"

// vfx-prefab's material (CgVfxPrefabScene): a prefab node's mesh in _Color times its presence, premultiplied, so a
// node at presence 0 leaves the target black and one below 1 dims. Its front faces alone, so one layer blends.

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Transparent"

Properties {
    _Color ("Its colour", color) = (1, 1, 1, 1)
}

struct v2f { float unused; };

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest ALWAYS
        DepthWrite OFF
        Cull BACK
    }

    void vertex(out v2f o) {
        o.unused = 0.0;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0));
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(_Color.rgb, 1.0) * FX_PRESENCE;
    }
}
