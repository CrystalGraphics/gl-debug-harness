#type none

// volumes' material (CgVolumeTestScene): a volume laid out flat, slice z as rows z * _Rows up, each texel fetched
// through a sampler3D.

Tags { "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Geometry"

Properties {
    _Volume ("Volume", sampler3D) = "black"
    _Rows   ("Rows a slice", float) = 1
}

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend ONE ZERO
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    struct v2f {
        vec2 unused;
    };

    void vertex(out v2f o) {
        o.unused = CG_VERTEX_CORNER;
        gl_Position = vec4(CG_VERTEX_CORNER * 2.0 - 1.0, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        ivec2 p = ivec2(gl_FragCoord.xy);
        int rows = int(_Rows);
        fragColor = vec4(texelFetch(_Volume, ivec3(p.x, p.y % rows, p.y / rows), 0).r, 0.0, 0.0, 1.0);
    }
}
