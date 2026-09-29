package com.crystalgraphics.harness.camera;

import com.crystalgraphics.harness.util.HarnessBuffers;
import com.crystalgraphics.harness.util.RenderPassState;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessShaderUtil;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.logging.Logger;

/**
 * Renders a semi-transparent gray overlay at the bottom of the screen
 * when the game is paused. Acts as a visual indicator similar to
 * Minecraft's chat area overlay.
 *
 * <p>The overlay is a screen-aligned quad drawn with alpha blending
 * using an orthographic projection derived from pixel coordinates.
 * The quad covers the bottom ~10% of the screen with a dark
 * semi-transparent gray color.</p>
 */
public final class PauseScreenRenderer {

    private static final Logger LOGGER = Logger.getLogger(PauseScreenRenderer.class.getName());

    // Overlay height as a fraction of screen height
    private static final float OVERLAY_HEIGHT_FRACTION = 0.10f;

    // Overlay color: dark gray with 50% opacity (RGBA)
    private static final float OVERLAY_R = 0.15f;
    private static final float OVERLAY_G = 0.15f;
    private static final float OVERLAY_B = 0.15f;
    private static final float OVERLAY_A = 0.5f;

    /** Not among CgGL's constants. */
    private static final int GL_DYNAMIC_DRAW = 0x88E8;

    // Shader for colored quad with alpha — uses pixel-to-NDC conversion
    private static final String PAUSE_VERT =
            "#version 330 core\n" +
            "layout(location = 0) in vec2 a_pos;\n" +
            "uniform vec2 u_screenSize;\n" +
            "void main() {\n" +
            "    vec2 ndc = (a_pos / u_screenSize) * 2.0 - 1.0;\n" +
            "    ndc.y = -ndc.y;\n" +
            "    gl_Position = vec4(ndc, 0.0, 1.0);\n" +
            "}\n";

    private static final String PAUSE_FRAG =
            "#version 330 core\n" +
            "uniform vec4 u_color;\n" +
            "layout(location = 0) out vec4 fragColor;\n" +
            "void main() {\n" +
            "    fragColor = u_color;\n" +
            "}\n";

    private int program;
    private int vao;
    private int vbo;
    private int screenSizeLoc;
    private int colorLoc;
    private boolean initialized = false;

    public PauseScreenRenderer() {
    }

    /**
     * Initializes GL resources (shader program, VAO, VBO).
     * Must be called once with a valid GL context before {@link #render}.
     */
    public void init() {
        if (initialized) {
            return;
        }

        program = HarnessShaderUtil.compileProgram(PAUSE_VERT, PAUSE_FRAG);

        screenSizeLoc = CgGL.glGetUniformLocation(program, "u_screenSize");
        colorLoc = CgGL.glGetUniformLocation(program, "u_color");

        vao = CgGL.glGenVertexArrays();
        vbo = CgGL.glGenBuffers();

        CgGL.glBindVertexArray(vao);
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vbo);

        // Allocate buffer for 6 vertices * 2 floats (x, y)
        CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, 6 * 2 * 4, GL_DYNAMIC_DRAW);

        int stride = 2 * 4;
        CgGL.glVertexAttribPointer(0, 2, CgGL.GL_FLOAT, false, stride, 0);
        CgGL.glEnableVertexAttribArray(0);

        CgGL.glBindVertexArray(0);
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, 0);

        initialized = true;
        LOGGER.info("[PauseScreenRenderer] Initialized: program=" + program
                + " vao=" + vao + " vbo=" + vbo);
    }

    /**
     * Renders the pause overlay at the bottom of the screen.
     *
     * <p>Temporarily disables depth testing and enables alpha blending
     * to draw the semi-transparent overlay quad.</p>
     *
     * @param ctx the harness context (provides screen dimensions)
     */
    public void render(HarnessContext ctx) {
        render(ctx.getScreenWidth(), ctx.getScreenHeight());
    }

    /**
     * Renders the pause overlay at the bottom of the screen with explicit dimensions.
     *
     * <p>GL state requirements (depth OFF, blend ON with alpha) are set by
     * {@link RenderPassState#beginOverlayPass()}
     * before this method is called by the runner. This renderer does not manage
     * its own GL state save/restore.</p>
     *
     * @param screenWidth  current viewport width in pixels
     * @param screenHeight current viewport height in pixels
     */
    public void render(int screenWidth, int screenHeight) {
        if (!initialized) {
            throw new IllegalStateException("PauseScreenRenderer.init() must be called before render()");
        }

        
        float overlayHeight = screenHeight * 0.075f;
        float y0 = screenHeight - overlayHeight;
        float y1 = (float) screenHeight - 10;
        float x0 = 0.0f;
        float x1 = (float) screenWidth;

        // Build quad vertices: two triangles covering the bottom strip
        float[] verts = {
            x0, y0,
            x1, y0,
            x1, y1,
            x0, y0,
            x1, y1,
            x0, y1
        };

        FloatBuffer vertBuf = BufferUtils.createFloatBuffer(verts.length);
        vertBuf.put(verts).flip();

        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vbo);
        CgGL.glBufferSubData(CgGL.GL_ARRAY_BUFFER, 0, HarnessBuffers.bytes(vertBuf));
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, 0);

        CgGL.glUseProgram(program);
        CgGL.glUniform2f(screenSizeLoc, (float) screenWidth, (float) screenHeight);
        CgGL.glUniform4f(colorLoc, OVERLAY_R, OVERLAY_G, OVERLAY_B, OVERLAY_A);

        CgGL.glBindVertexArray(vao);
        CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 6);
        CgGL.glBindVertexArray(0);

        CgGL.glUseProgram(0);
    }

    /**
     * Handles display resize events. The pause overlay recomputes quad
     * vertices each frame from screen dimensions, so no cached state
     * needs invalidation.
     *
     * @param newWidth  new viewport width in pixels
     * @param newHeight new viewport height in pixels
     */
    public void onDisplayResize(int newWidth, int newHeight) {
        // No-op: quad vertices are recomputed from screen dimensions each render call
    }

    /**
     * Releases all GL resources held by this renderer.
     */
    public void delete() {
        if (!initialized) {
            return;
        }
        CgGL.glDeleteBuffers(vbo);
        CgGL.glDeleteVertexArrays(vao);
        CgGL.glDeleteProgram(program);
        vbo = 0;
        vao = 0;
        program = 0;
        initialized = false;
        LOGGER.info("[PauseScreenRenderer] Deleted.");
    }
}
