// The forward renderer's solid material (CgForwardRendererScene, MeshTestScene): each instance its custom0 colour,
// darkened, lit and fogged as the world's things are. The properties are one of each kind, for the property tests.
#domain surface
Material { Faces Both }

Properties {
    _MainTex    ("Main Texture",    sampler2D)     = "white"
    _NormalMap  ("Normal Map",      sampler2D)     = "normal"
    _TexArray   ("Texture Array",   sampler2DArray)
    _Volume     ("Volume Texture",  sampler3D)
    _Skybox     ("Cubemap",         samplerCube)
    _Color      ("Tint Color",      color)         = (1.0, 1.0, 1.0, 1.0)
    _BaseColor  ("Base Color",      vec4)          = (0.2, 0.4, 0.8, 1.0)
    _Offset     ("UV Offset",       vec2)          = (0.0, 0.0)
    _Roughness  ("Roughness",       float)         = 0.5
    _Metallic   ("Metallic",        float)         = 0.0
    _Count      ("Instance Count",  boolean)       = 0
    _Speed      ("Speed",           Range(0, 10))  = 1.0
}

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    s.baseColor = CG_OBJECT_CUSTOM0.rgb - 0.4;
}
