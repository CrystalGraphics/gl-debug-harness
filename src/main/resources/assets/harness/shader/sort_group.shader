#type spatial

// vfx-sort-group's layer (CgVfxSortGroupScene): the slot's sphere in _Color, solid, over whatever drew before it, so
// the colour left at its middle is the layer that drew last.

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Transparent"

Properties {
    _Color ("Its colour", color) = (1, 1, 1, 1)
}

struct v2f { float unused; };

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        o.unused = 0.0;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0));
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(_Color.rgb, 1.0);
    }
}
