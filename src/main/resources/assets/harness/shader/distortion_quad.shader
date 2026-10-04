#type spatial
// distortion's quads (CgDistortionTestScene): transparent, drawing _Color, and a Distortion pass bending by _Offset:
// xy in pixels, z the chromatic split. The Distortion pass has no vertex(), so it takes the Forward pass's.

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Transparent"

Properties {
    _Color  ("Colour", vec4) = (0, 0, 0, 0)
    _Offset ("Offset in pixels, split", vec4) = (0, 0, 0, 0)
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull BACK
    }

    void vertex(out v2f o) {
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
        o.uv = cg_TexCoord0;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = _Color;
    }
}

Pass {
    Tags { "LightMode" = "Distortion" }

    void fragment(in v2f i, out vec4 offset) {
        offset = vec4(_Offset.xy / CG_RESOLUTION, _Offset.z, 0.0);
    }
}
