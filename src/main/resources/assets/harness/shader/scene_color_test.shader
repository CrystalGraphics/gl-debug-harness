#type spatial

// A lens over the world: what cg_SceneColor holds behind it, rippled and inverted.

Queue = "Transparent"

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend ONE ZERO
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    struct v2f {
        vec3 worldPos;
    };

    void vertex(out v2f o) {
        o.worldPos = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec2 uv = gl_FragCoord.xy / CG_RESOLUTION;
        uv.x += 0.02 * sin(uv.y * 60.0);
        fragColor = vec4(1.0 - CG_SCENE_COLOR(uv).rgb, 1.0);
    }
}
