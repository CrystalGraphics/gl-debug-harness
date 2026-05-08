#type spatial

Properties {
    _Color : vec4 = (1.0, 1.0, 1.0, 1.0)
};

struct v2f {
    vec2 uv;
    vec3 worldPos;
    vec3 normalWs;
    vec4 tint;
};

void vertex(out v2f o) {
    vec4 worldPos4 = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
    gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    o.worldPos = worldPos4.xyz;
    o.normalWs = normalize(CG_NORMAL_MATRIX * cg_Normal);
    o.uv = cg_TexCoord0;
    o.tint = CG_OBJECT_CUSTOM0;
}

void fragment(in v2f i, out vec4 fragColor) {
    fragColor = vec4(1) - 0.04 * CG_INSTANCE_ID;
}


