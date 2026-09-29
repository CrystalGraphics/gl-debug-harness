package io.github.somehussar.crystalgraphics.harness.util;

import com.crystalgraphics.platform.gl.CgGL;

/**
 * Shared GL state reset helper for the harness render pipeline.
 *
 * <p>After a scene's {@code renderFrame()} call, various GL objects may remain
 * bound (shader programs, VAOs, VBOs, textures) and state flags may be left in
 * non-default configurations (depth test off, blend on, cull face on). This
 * helper provides a single canonical reset sequence that restores GL state to
 * the baseline expected by subsequent pipeline passes (floor, pause overlay,
 * HUD).</p>
 *
 * <p>This replaces the duplicated reset logic that previously existed in both
 * {@code InteractiveSceneRunner.GL20ResetHelper} and
 * {@code InteractiveWorldTextScene.renderFrame()}.</p>
 */
public final class GlStateResetHelper {

    private GlStateResetHelper() { }

    /**
     * Resets GL state after a scene render pass to ensure subsequent overlay
     * passes (pause screen, HUD) render correctly.
     *
     * <p>Specifically:</p>
     * <ul>
     *   <li>Unbinds shader program (scenes may leave MSDF/bitmap shaders bound)</li>
     *   <li>Unbinds VAO (scene geometry VAOs must not leak into overlay draws)</li>
     *   <li>Unbinds VBO and IBO (CgGlyphVbo or cube VBOs may remain bound)</li>
     *   <li>Unbinds texture on unit 0 (atlas textures may remain bound)</li>
     *   <li>Enables depth test with LEQUAL func and depth writes on</li>
     *   <li>Disables blend (FloorRenderer expects blend OFF)</li>
     *   <li>Disables cull face (world-text draw() may leave GL_CULL_FACE enabled)</li>
     *   <li>Unbinds FBO to ensure we render to the default backbuffer</li>
     * </ul>
     */
    public static void resetAfterScene() {
        // Unbind shader program — scenes may leave MSDF/bitmap shaders bound
        CgGL.glUseProgram(0);

        // Unbind VAO — scene geometry VAOs must not leak into overlay draws
        CgGL.glBindVertexArray(0);

        // Unbind VBO and IBO — CgGlyphVbo or cube VBOs may remain bound
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, 0);
        CgGL.glBindBuffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, 0);

        // Unbind texture on unit 0 — atlas textures may remain bound
        CgGL.glActiveTexture(CgGL.GL_TEXTURE0);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, 0);

        // Restore depth state — FloorRenderer requires depth test ON + depth writes ON
        CgGL.glEnable(CgGL.GL_DEPTH_TEST);
        CgGL.glDepthMask(true);
        CgGL.glDepthFunc(CgGL.GL_LEQUAL);

        // Disable blend — FloorRenderer expects blend OFF; HUD/pause manage their own
        CgGL.glDisable(CgGL.GL_BLEND);

        // Disable cull face — world-text draw() enables GL_CULL_FACE for single-sided text
        CgGL.glDisable(CgGL.GL_CULL_FACE);

        // Ensure we're rendering to the default framebuffer (backbuffer)
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, 0);
    }
}
