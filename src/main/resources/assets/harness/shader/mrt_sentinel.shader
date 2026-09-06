#type spatial

// Sentinel material for MRT readback verification.
// Uses a quad built with CgMeshBuilder.quad2D(-1,-1,1,1) -- vertices already in NDC space,
// so no MVP transform is applied. RenderState disables culling so winding never matters.

Pass {
    Tags { "LightMode" = "Forward" "Name" = "MrtSentinel" }

    RenderState {
        Cull Off
        DepthWrite Off
        
    }

    struct v2f {
        vec2 uv;
    };

    struct GBuffer {
        vec4 albedo   : RT0;
        vec4 normal   : RT1;
        vec4 emission : RT2;
    };

    void vertex(out v2f o) {
        // Quad vertices are already in NDC (-1..1). Pass through directly.
        o.uv = cg_TexCoord0;
        gl_Position = vec4(cg_Position.xy, 0.0, 1.0);
    }

    void fragment(in v2f i, out GBuffer o) {
        // Sentinel: each RT gets a distinct primary color so readback can validate each channel.
        o.albedo   = vec4(1.0, 0.0, 0.0, 1.0);  // RT0 -> red
        o.normal   = vec4(0.0, 1.0, 0.0, 1.0);  // RT1 -> green
        o.emission = vec4(0.0, 0.0, 1.0, 1.0);  // RT2 -> blue
    }
}
