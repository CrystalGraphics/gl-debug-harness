#type spatial
// HarnessEnvironment's ground: depth alone, opaque, under the grid's lines. What a colour region, a soft particle or a
// depth fade reads at the floor, as a game's ground gives it; the colour is the sky's and the grid's.

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Geometry"

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        ColorMask 0
        DepthTest LEQUAL
        DepthWrite ON
        Cull OFF
    }

    // editor_grid's transform, so the lines over it, drawn a centimetre above, always pass their depth test
    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
        o.uv = cg_TexCoord0;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(0.0);
    }
}
