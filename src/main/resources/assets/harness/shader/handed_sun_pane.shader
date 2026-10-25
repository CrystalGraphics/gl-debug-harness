#type spatial
// A pane for lights-sun showing the sun's visibility it is handed at each fragment: white lit, black shadowed.

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" "Media" = "Off" }
Queue = "Transparent"

struct v2f {
    vec3 n;
};

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        o.n = cg_Normal;
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(vec3(CG_DIRECTIONAL_SHADOW(cg_FragmentPosition())), 1.0);
    }
}
