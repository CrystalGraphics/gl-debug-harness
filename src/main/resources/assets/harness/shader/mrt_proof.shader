#type none

// mrt-emission's draw (CgMrtEmissionScene): a screen rectangle written to colour (RT0) and emission (RT1) in one pass,
// both under the one alpha blend, so a draw's alpha dims the emission beneath it as it dims the colour.
// CUSTOM0 the rectangle (x0, y0, x1, y1 in 0..1), CUSTOM1.x its depth (0..1), CUSTOM2 its colour, CUSTOM3.rgb its glow.

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Geometry"

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite ON
        Cull OFF
    }

    struct v2f {
        vec2 unused;
    };

    struct Targets {
        vec4 color : RT0;
        vec4 glow  : RT1;
    };

    void vertex(out v2f o) {
        vec4 r = CG_OBJECT_CUSTOM0;
        vec2 p = mix(r.xy, r.zw, CG_VERTEX_CORNER);
        o.unused = p;
        gl_Position = vec4(p * 2.0 - 1.0, CG_OBJECT_CUSTOM1.x * 2.0 - 1.0, 1.0);
    }

    void fragment(in v2f i, out Targets o) {
        o.color = CG_OBJECT_CUSTOM2;
        o.glow = vec4(CG_OBJECT_CUSTOM3.rgb, CG_OBJECT_CUSTOM2.a);
    }
}
