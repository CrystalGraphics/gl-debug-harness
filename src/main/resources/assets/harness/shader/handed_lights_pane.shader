#type spatial
// A pane for lights-volume showing the light nearest its draw as handed to a program carrying its own lighting:
// red 1 where one is found, green its distance over 16 blocks, blue its radius over 32.

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" "Media" = "Off" }
Queue = "Transparent"

struct v2f {
    vec3 shown;
};

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        DepthTest LEQUAL
        DepthWrite OFF
        Cull BACK
    }

    void vertex(out v2f o) {
        vec3 to = CG_DRAW_POINT_LIGHT_POSITION(0) - CG_OBJECT_TO_WORLD[3].xyz;
        vec4 params = CG_DRAW_POINT_LIGHT_PARAMS(0);
        o.shown = vec3(params.x, params.x * clamp(length(to) / 16.0, 0.0, 1.0), params.z / 32.0);
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(i.shown, 1.0);
    }
}
