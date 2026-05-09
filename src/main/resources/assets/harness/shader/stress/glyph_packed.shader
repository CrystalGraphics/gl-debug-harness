#type spatial

struct v2f {
    vec2 uv;
    float advance;
};

void vertex(out v2f o) {
    GlyphMetrics g = GLYPH(CG_INSTANCE_ID);
    // Scale vertex by glyph bbox
    vec2 scaled = cg_Position.xy * (g.bbox.zw - g.bbox.xy) + g.bbox.xy;
    gl_Position = CG_MATRIX_MVP * vec4(scaled, cg_Position.z, 1.0);
    o.uv = mix(g.uv0, g.uv1, cg_TexCoord0);
    o.advance = g.advance;
}

void fragment(in v2f i, out vec4 fragColor) {
    // Visualise UV and advance
    fragColor = vec4(i.uv, i.advance * 4.0, 1.0);
}
