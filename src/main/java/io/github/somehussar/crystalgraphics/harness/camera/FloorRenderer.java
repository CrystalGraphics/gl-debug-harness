package io.github.somehussar.crystalgraphics.harness.camera;

import io.github.somehussar.crystalgraphics.harness.util.HarnessBuffers;
import com.crystalgraphics.platform.gl.CgGL;
import io.github.somehussar.crystalgraphics.harness.config.WorldSettings;
import io.github.somehussar.crystalgraphics.harness.util.HarnessShaderUtil;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.logging.Logger;

/**
 * Renders a gray grid-like floor plane at Y=0 using immediate-mode style
 * vertex data uploaded to a VAO/VBO.
 *
 * <p>The floor is a large quad centered at the origin, rendered as two triangles.
 * Floor color is sourced from the resolved {@link WorldSettings} passed to
 * {@link #init(WorldSettings)}, defaulting to gray (0.5, 0.5, 0.5).</p>
 *
 * <p>The floor plane is drawn with depth testing enabled. Scenes using this
 * renderer should ensure GL_DEPTH_TEST is active.</p>
 */
public class FloorRenderer {

    private static final Logger LOGGER = Logger.getLogger(FloorRenderer.class.getName());

    // Shader sources for floor rendering with uniform MVP matrix and per-vertex color
    private static final String FLOOR_VERT =
            "#version 330 core\n" +
            "uniform mat4 u_mvp;\n" +
            "layout(location = 0) in vec3 a_pos;\n" +
            "layout(location = 1) in vec3 a_color;\n" +
            "out vec3 v_color;\n" +
            "void main() {\n" +
            "    gl_Position = u_mvp * vec4(a_pos, 1.0);\n" +
            "    v_color = a_color;\n" +
            "}\n";

    private static final String FLOOR_FRAG =
            "#version 330 core\n" +
            "in vec3 v_color;\n" +
            "layout(location = 0) out vec4 fragColor;\n" +
            "void main() {\n" +
            "    fragColor = vec4(v_color, 1.0);\n" +
            "}\n";

    // Two triangles forming a quad on the XZ plane at Y=0
    // Format: x, y, z, r, g, b per vertex
    // Vertex data is built at init() time from resolved WorldSettings

    private int program;
    private int vao;
    private int vbo;
    private int mvpLocation;
    private boolean initialized = false;

    /**
     * Initializes GL resources (shader program, VAO, VBO).
     * Must be called once with a valid GL context before {@link #render}.
     *
     * <p>Floor color and extent are read from the resolved {@link WorldSettings}
     * passed as a parameter, not from a mutable global singleton.</p>
     *
     * @param settings the resolved world settings for this run (must not be null)
     */
    public void init(WorldSettings settings) {
        if (initialized) {
            return;
        }

        float halfSize = settings.getFloorHalfSize();
        float r = settings.getFloorR();
        float g = settings.getFloorG();
        float b = settings.getFloorB();

        float[] floorVertices = {
            -halfSize, 0.0f, -halfSize, r, g, b,
             halfSize, 0.0f, -halfSize, r, g, b,
             halfSize, 0.0f,  halfSize, r, g, b,

            -halfSize, 0.0f, -halfSize, r, g, b,
             halfSize, 0.0f,  halfSize, r, g, b,
            -halfSize, 0.0f,  halfSize, r, g, b
        };

        program = HarnessShaderUtil.compileProgram(FLOOR_VERT, FLOOR_FRAG);

        mvpLocation = CgGL.glGetUniformLocation(program, "u_mvp");

        vao = CgGL.glGenVertexArrays();
        vbo = CgGL.glGenBuffers();

        CgGL.glBindVertexArray(vao);
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vbo);

        FloatBuffer buf = BufferUtils.createFloatBuffer(floorVertices.length);
        buf.put(floorVertices).flip();
        CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, HarnessBuffers.bytes(buf), CgGL.GL_STATIC_DRAW);

        int stride = 6 * 4; // 6 floats * 4 bytes
        CgGL.glVertexAttribPointer(0, 3, CgGL.GL_FLOAT, false, stride, 0);
        CgGL.glEnableVertexAttribArray(0);
        CgGL.glVertexAttribPointer(1, 3, CgGL.GL_FLOAT, false, stride, 12);
        CgGL.glEnableVertexAttribArray(1);

        CgGL.glBindVertexArray(0);
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, 0);

        // Check for any GL errors during initialization
        int glError = CgGL.glGetError();
        if (glError != CgGL.GL_NO_ERROR) {
            LOGGER.warning("[FloorRenderer] GL error during init: 0x" + Integer.toHexString(glError));
        }

        initialized = true;
        LOGGER.info("[FloorRenderer] Initialized: program=" + program + " vao=" + vao
                + " vbo=" + vbo + " mvpLoc=" + mvpLocation);
    }

    /**
     * Renders the floor plane using the given model-view-projection matrix.
     *
     * <p>The MVP matrix should be projection * view (no model transform needed,
     * as the floor is at the world origin).</p>
     *
     * <p>GL state requirements (depth ON, blend OFF, depth writes ON) are set by
     * {@link io.github.somehussar.crystalgraphics.harness.util.RenderPassState#beginWorldPass()}
     * before this method is called by the runner. This renderer does not manage
     * its own GL state save/restore — the pipeline boundary helpers handle it.</p>
     *
     * @param mvpMatrix the combined model-view-projection matrix stored in a float[16] column-major
     */
    public void render(Matrix4f mvpMatrix) {
        if (!initialized) {
            throw new IllegalStateException("FloorRenderer.init() must be called before render()");
        }

        FloatBuffer mvpBuf = BufferUtils.createFloatBuffer(16);
        mvpMatrix.get(mvpBuf);
        // No need to flip
        // mvpBuf.flip();

        CgGL.glUseProgram(program);
        CgGL.glUniformMatrix4fv(mvpLocation, false, mvpBuf);

        CgGL.glBindVertexArray(vao);
        CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 6);
        CgGL.glBindVertexArray(0);

        CgGL.glUseProgram(0);
    }

    /**
     * Handles display resize events. The floor renderer uses the MVP matrix
     * passed to render(), so no cached state needs updating on resize.
     *
     * @param newWidth  new viewport width in pixels
     * @param newHeight new viewport height in pixels
     */
    public void onDisplayResize(int newWidth, int newHeight) {
        // No-op: floor uses MVP computed fresh each frame from current viewport aspect
    }

    /**
     * Releases all GL resources held by this renderer.
     */
    public void delete() {
        if (!initialized) {
            return;
        }
        CgGL.glBindVertexArray(0);
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, 0);
        CgGL.glDeleteBuffers(vbo);
        CgGL.glDeleteVertexArrays(vao);
        CgGL.glDeleteProgram(program);
        vbo = 0;
        vao = 0;
        program = 0;
        initialized = false;
        LOGGER.info("[FloorRenderer] Deleted.");
    }
}
