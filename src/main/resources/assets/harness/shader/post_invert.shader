// post-transitions' post material (CgPostTransitionsScene): the picture POST_WEIGHT of the way to its inverse, in the
// values the target stores.
#domain post
Material { Location AFTER_COMPOSITE  ColorSpace Target }

void surface(in CgScreen i, inout CgPostSurface s) {
    s.color = mix(s.color, 1.0 - s.color, POST_WEIGHT);
}
