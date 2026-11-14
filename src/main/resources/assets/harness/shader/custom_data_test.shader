// custom-depth's writer (CgCustomDepthTestScene): flat grey, opaque, and Unreal's custom data and shading model, known
// values the probe reads back.
#domain surface
Material { ShadingModel Unlit  Faces Both  Fog Off }

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = vec3(0.3, 0.3, 0.32);
    s.customData = vec4(0.2, 0.4, 0.6, 0.5);
    s.shadingModelId = uvec2(13u, 2u);
}
