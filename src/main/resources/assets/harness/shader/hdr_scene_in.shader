#type none
#include "crystalgraphics:shaders/lib/post/composite.glsl"

// hdr-scene's scene in (CgHdrSceneTestScene): the host's 8-bit colour decoded into the linear scene, texel for texel.

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Geometry"

Properties {
    _Source ("The host's colour", sampler2D) = "black"
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
        vec4 host = texelFetch(_Source, ivec2(gl_FragCoord.xy), 0);
        fragColor = vec4(post_decode_srgb(host.rgb), host.a);
    }
}
