#type spatial

#pragma cg_feature TINT_ENABLED
#pragma cg_feature EMISSION_ENABLED
#pragma cg_feature GRID_OVERLAY

Queue = "Geometry"
RenderState {
    DepthTest LEQUAL
    DepthWrite ON
    Cull BACK
}

Properties {
    _BaseTint      ("Base Tint",    color)         = (0.9, 0.9, 0.9, 1.0)
    _EmissionColor ("Emission",     vec4)          = (0.0, 0.5, 1.0, 1.0)
    _GridScale     ("Grid Scale",   float)         = 8.0
}

struct v2f {
    vec2 uv;
    vec3 worldPos;
};

void vertex(out v2f o) {
    gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    o.worldPos  = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;
    o.uv        = cg_TexCoord0;
}

void fragment(in v2f i, out vec4 fragColor) {
    // Base: neutral grey, modulated by per-instance custom0 slot
    fragColor = _BaseTint * CG_OBJECT_CUSTOM0;

#ifdef TINT_ENABLED
    // Shift hue toward the material's _BaseTint more strongly
    fragColor.rgb *= _BaseTint.rgb * 0.5;
#endif

#ifdef EMISSION_ENABLED
    // Add an emissive contribution that pulses with cg_Time
    float pulse = 0.5 + 0.5 * sin(cg_Time.y * 2.0);
    fragColor.rgb += _EmissionColor.rrr * pulse * 0.6;
#endif

#ifdef GRID_OVERLAY
    // Overlay a UV grid: darken edges between cells
    vec2 grid = fract(i.uv * _GridScale);
    float line = step(0.9, max(grid.x, grid.y));
    fragColor.rgb = mix(fragColor.rgb, vec3(0.0), line * 0.5);
#endif
}
