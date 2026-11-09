# ReviewScene — hand-written GL, kept as reference

The `light` scene and its shaders as they ran before the harness moved onto `CgRHI` (H4): raw GL 3.3 through
`RawGlProgram`, `VertexBinding` and `Pose`. Not compiled and not registered; the harness names no GL above its host
layer now.

- `java/` — `ReviewScene` and the three helpers it drew with.
- `shader/review/` — triangle, cube, sun, the sphere sun, `time-rotation-learning`.
- `shader/circle/` — the sphere and sun shaders, and `cube_sphere_cooler`.
- `shader/outline/` — the outline shader and its five textures.

To run it again, port its draws to the engine: the `.vert`/`.frag` pairs into a material's `.shader`, the meshes to
`CgMesh`, drawn through `ObjectDraws` into the scene's target.
