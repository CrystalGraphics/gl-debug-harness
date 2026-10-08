#type none
#include "crystalgraphics:shaders/lib/post/composite.glsl"

// hdr-scene's composite (CgHdrSceneTestScene): the scene clamped and encoded into the 8-bit host. A value within 0.1 of
// a code is what scene in decoded (RGBA16F lands within 0.04) and is written back exact; anything else is dithered.

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Geometry"

Properties {
    _Scene ("The linear scene", sampler2D) = "black"
}

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    struct v2f {
        vec2 unused;
    };

    void vertex(out v2f o) {
        o.unused = CG_VERTEX_CORNER;
        gl_Position = vec4(CG_VERTEX_CORNER * 2.0 - 1.0, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec4 scene = texelFetch(_Scene, ivec2(gl_FragCoord.xy), 0);
        vec3 e = post_encode_srgb(min(scene.rgb, vec3(1.0))) * 255.0;
        vec3 code = floor(e + 0.5);
        vec3 c = all(lessThan(abs(e - code), vec3(0.1))) ? code * (1.0 / 255.0)
                : post_dither8(e * (1.0 / 255.0), gl_FragCoord.xy, floor(CG_TIME * 60.0));
        fragColor = vec4(c, scene.a);
    }
}
