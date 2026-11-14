// hdr-scene's opaque draw (CgHdrSceneTestScene): an unlit linear colour past white, as an Unreal unlit emissive writes
// SceneColor. Through the opaque firing's scene its excess must reach the composite's roll.
#domain surface
Material { ShadingModel Unlit  Fog Off  ColorSpace Linear }

Properties {
    _Color ("Linear colour", vec4) = (4, 0, 0, 1)
}

struct v2f {
    float unused;
};

void vertex(inout CgVertex v, out v2f o) {
    o.unused = 0.0;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = _Color.rgb;
}
