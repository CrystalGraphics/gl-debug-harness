#type none

// raster-levels' chain (CgRasterLevelsTestScene), drawn level by level into one texture. Without DOWN a level is a
// pattern from the pixel's position; with DOWN it is the mean of the 2x2 texels of the level above, read through a
// level view (_Source is that level alone, at its base), with a step per level a wrong read cannot match.

#pragma cg_feature DOWN

Queue = "Geometry"

Properties {
    _Source ("Level above", sampler2D) = "black"
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
#ifdef DOWN
        ivec2 p = ivec2(gl_FragCoord.xy) * 2, last = textureSize(_Source, 0) - 1;   // the level above's size
        ivec2 q = min(p + 1, last);
        vec4 sum = texelFetch(_Source, p, 0) + texelFetch(_Source, ivec2(q.x, p.y), 0)
                 + texelFetch(_Source, ivec2(p.x, q.y), 0) + texelFetch(_Source, q, 0);
        fragColor = sum * 0.25 + vec4(0.0, 0.0, 0.0625, 0.0);
#else
        fragColor = vec4(gl_FragCoord.x / 64.0, gl_FragCoord.y / 64.0, 0.25, 1.0);
#endif
    }
}
