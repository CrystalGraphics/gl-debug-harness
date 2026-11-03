#type spatial

// hdr-scene's opaque draw (CgHdrSceneTestScene): an unlit linear colour past white, as an Unreal unlit emissive writes
// SceneColor. Through the opaque firing's scene its excess must reach the composite's roll.

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Geometry"

Properties {
    _Color ("Linear colour", vec4) = (4, 0, 0, 1)
}

struct v2f {
    float unused;
};

Pass {
    Tags { "LightMode" = "Forward" }

    void vertex(out v2f o) {
        o.unused = 0.0;
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = _Color;
    }
}
