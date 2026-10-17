#type none
#include "crystalgraphics:shaders/lib/post/post_material.glsl"

// post-transitions' post material (CgPostTransitionsScene): the picture POST_WEIGHT of the way to its inverse.

Tags { "RenderType" = "Transparent" "SceneColorMargin" = "0.001" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Overlay"

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
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
        vec3 scene = texelFetch(cg_SceneColor, ivec2(gl_FragCoord.xy), 0).rgb;
        fragColor = vec4(mix(scene, 1.0 - scene, POST_WEIGHT), 1.0);
    }
}
