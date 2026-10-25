#type none
#include "crystalgraphics:shaders/lib/post/post_material.glsl"

// post-transitions' multi-pass post material (CgPostTransitionsScene): the scene halved into a half-size target, that
// plus a quarter into a quarter-size target, and that over the scene. A pass reading its input at a size other than its
// tag's draws magenta.

Tags { "RenderType" = "Transparent" "SceneColorMargin" = "0.01" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Overlay"

Properties {
    _Half ("The scene halved, at half size", sampler2D) = "black"
    _Quarter ("That plus a quarter, at a quarter size", sampler2D) = "black"
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" "Name" = "Down" "Target" = "_Half" "Scale" = "0.5" }
    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        o.uv = post_fullscreen_uv(CG_VERTEX_ID);
        gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(CG_SCENE_COLOR(i.uv).rgb * 0.5, 1.0);
    }
}

Pass {
    Tags { "LightMode" = "Forward" "Name" = "Quarter" "Target" = "_Quarter" "Scale" = "0.25" }
    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        o.uv = post_fullscreen_uv(CG_VERTEX_ID);
        gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        bool sized = textureSize(_Half, 0).x == int(CG_RESOLUTION.x * 0.5);
        fragColor = sized ? vec4(texture(_Half, i.uv).rgb + 0.25, 1.0) : vec4(1.0, 0.0, 1.0, 1.0);
    }
}

Pass {
    Tags { "LightMode" = "Forward" "Name" = "Over" }
    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        o.uv = post_fullscreen_uv(CG_VERTEX_ID);
        gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        bool sized = textureSize(_Quarter, 0).x == int(CG_RESOLUTION.x * 0.25);
        fragColor = sized ? vec4(texture(_Quarter, i.uv).rgb, 1.0) : vec4(1.0, 0.0, 1.0, 1.0);
    }
}
