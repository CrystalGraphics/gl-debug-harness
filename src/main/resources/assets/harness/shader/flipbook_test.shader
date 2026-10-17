#type none
#include "crystalgraphics:shaders/lib/vfx/fx_particle.glsl"

// flipbook's cells (CgFlipbookTestScene): a quad at CG_OBJECT_CUSTOM0.xy, CG_OBJECT_CUSTOM0.z across, showing the
// frames fx_subuv_frames picks for a particle of age CG_OBJECT_CUSTOM0.w, progress CG_OBJECT_CUSTOM1.x and seed
// CG_OBJECT_CUSTOM1.y, blended as it says: what sprite.shader draws.

Tags { "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Geometry"

Properties {
    _Sheet ("A 4x4 sheet of solid frames", sampler2D) = "white"
    _SubImage ("CgVfxFlipbook's", vec4) = (1, 1, 1, 0)
    _SubUV ("CgVfxFlipbook's", vec4) = (0, 0, -1, 0)
    _SubImages ("CgVfxFlipbook's rect table", sampler2D) = "white"
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
        vec4 uv;
        float blend;
    };

    void vertex(out v2f o) {
        vec2 corner = CG_VERTEX_CORNER * 2.0 - 1.0;
        float a, b;
        fx_subuv_frames(_SubUV, _SubImage, CG_OBJECT_CUSTOM0.w, CG_OBJECT_CUSTOM1.x, CG_OBJECT_CUSTOM1.y, a, b, o.blend);
        // A quarter in from each edge: a cell's middle, away from its neighbours whatever the filter.
        o.uv = vec4(fx_subimage_uv(_SubImages, _SubImage, a, corner * 0.5),
                fx_subimage_uv(_SubImages, _SubImage, b, corner * 0.5));
        vec2 p = CG_OBJECT_CUSTOM0.xy + CG_VERTEX_CORNER * CG_OBJECT_CUSTOM0.z;
        gl_Position = CG_MATRIX_MVP * vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(mix(texture(_Sheet, i.uv.xy).rgb, texture(_Sheet, i.uv.zw).rgb, i.blend), 1.0);
    }
}
