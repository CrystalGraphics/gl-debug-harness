#type spatial

struct v2f {
    vec3 worldPos;
    vec3 normal;
    vec2 uv;
};

void vertex(out v2f o) {
    vec4 worldPos4 = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
    gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    o.worldPos = worldPos4.xyz;
    o.normal = normalize(CG_NORMAL_MATRIX * cg_Normal);
    o.uv = cg_TexCoord0;
}

void fragment(in v2f i, out vec4 fragColor) {
    // TerrainParams UBO fields are in direct scope — no prefix
    vec3 sun = sunDirAndAmbient.xyz;
    float ambient = sunDirAndAmbient.w;
    float diffuse = clamp(dot(normalize(i.normal), normalize(sun)), 0.0, 1.0);
    vec3 color = vec3(0.4, 0.65, 0.3) * (ambient + diffuse);
    // Apply fog
    float dist = length(i.worldPos);
    float fog = clamp(1.0 - exp(-fogDensity * dist), 0.0, 1.0);
//     fragColor = vec4(mix(color, fogColor.rgb, fract(fog*state)), 1.0);
    fragColor = vec4(fract(i.uv * float(mod(state,15000)/1000.)),0,1);
}
