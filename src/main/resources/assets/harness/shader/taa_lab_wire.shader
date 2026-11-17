// upscale-lab's wires and blades: an unlit colour from custom0, blended. custom1.x 1 keeps them at least two render
// pixels wide (cg_min_pixel_width), blended by what they truly cover, so they hold still under the jitter; 0 draws them
// as they are, thinner than a pixel and flickering. They stand in the world, so they keep their history (ResponsiveAA
// Off) and write their velocity and depth where they cover a tenth of a pixel (OutputVelocity): temporal AA moves them
// with themselves, not with the floor behind, when the camera moves.
#domain surface
Material { BlendMode Translucent  ResponsiveAA Off  OutputVelocity On  MaskClip 0.1  ShadingModel Unlit  ColorSpace Linear  Fog Off }

#include "crystalgraphics:shaders/lib/pixel_coverage.glsl"

struct v2f { float coverage; };

void vertex(inout CgVertex v, out v2f o) {
    // The lab's boxes are the unit cube
    o.coverage = CG_OBJECT_CUSTOM1.x > 0.5 ? cg_min_pixel_width(v, CG_OBJECT_TO_WORLD, vec3(0.5), 2.0) : 1.0;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = CG_OBJECT_CUSTOM0.rgb * CG_OBJECT_CUSTOM0.a;
    s.opacity = i.coverage;
}
