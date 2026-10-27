#type none
#include "crystalgraphics:shaders/lib/post/post_material.glsl"

// custom-depth's probe (CgCustomDepthTestScene): every pixel the scene textures a post material is handed, as colour.
// r CustomStencil / 255, g the custom data's alpha, b the shading model / 31, a the type / 6 plus 0.5 where the world
// normal is +Z.

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
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
        vec2 uv = gl_FragCoord.xy / CG_RESOLUTION;
        int stencil = CG_CUSTOM_STENCIL(uv);
        vec4 data = CG_CUSTOM_DATA(uv);
        uvec2 model = CG_SHADING_MODEL_ID(uv);
        bool facing = CG_SCENE_WORLD_NORMAL(uv).z > 0.99;
        fragColor = vec4(float(stencil) / 255.0, data.a, float(model.x) / 31.0, float(model.y) / 6.0 + (facing ? 0.5 : 0.0));
    }
}
