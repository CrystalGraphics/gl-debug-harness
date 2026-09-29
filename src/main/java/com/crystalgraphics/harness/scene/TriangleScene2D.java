package com.crystalgraphics.harness.scene;

import com.crystalgraphics.harness.util.HarnessBuffers;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.harness.util.HarnessShaderUtil;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.logging.Logger;

public class TriangleScene2D implements HarnessSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(TriangleScene2D.class.getName());

    static final String VERT_SOURCE =
            "#version 330 core\n" +
            "layout(location = 0) in vec2 a_pos;\n" +
            "layout(location = 1) in vec3 a_color;\n" +
            "out vec3 v_color;\n" +
            "void main() {\n" +
            "    gl_Position = vec4(a_pos, 0.0, 1.0);\n" +
            "    v_color = a_color;\n" +
            "}\n";

    static final String FRAG_SOURCE =
            "#version 330 core\n" +
            "in vec3 v_color;\n" +
            "layout(location = 0) out vec4 fragColor;\n" +
            "void main() {\n" +
            "    fragColor = vec4(v_color, 1.0);\n" +
            "}\n";

    static final float[] TRI_DATA = {
         0.0f,  0.5f,  1.0f, 0.0f, 0.0f,
        -0.5f, -0.5f,  0.0f, 1.0f, 0.0f,
         0.5f, -0.5f,  0.0f, 0.0f, 1.0f
    };
    
    @Override
    public void init(HarnessContext ctx) {
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        String outputDir = ctx.getOutputDir();
        String outputFile = ctx.getOutputSettings().buildBaseFilename("png");
        int fboWidth = ctx.getScreenWidth();
        int fboHeight = ctx.getScreenHeight();

        HarnessFboHelper fbo = HarnessFboHelper.create(fboWidth, fboHeight, true);
        fbo.bind();
        fbo.clear(0.1f, 0.1f, 0.1f, 1.0f);

        renderTriangle();

        fbo.captureToFile(outputDir, outputFile);

        LOGGER.info("[Harness] FBO dimensions: " + fboWidth + "x" + fboHeight);

        fbo.unbind();
        fbo.delete();

        LOGGER.info("[Harness] Triangle scene complete.");
    }

    @Override
    public void dispose() {
    }

    private void renderTriangle() {
        int program = HarnessShaderUtil.compileProgram(VERT_SOURCE, FRAG_SOURCE);
        int vao = CgGL.glGenVertexArrays();
        int vbo = CgGL.glGenBuffers();

        CgGL.glBindVertexArray(vao);
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, vbo);

        FloatBuffer buf = BufferUtils.createFloatBuffer(TRI_DATA.length);
        buf.put(TRI_DATA).flip();
        CgGL.glBufferData(CgGL.GL_ARRAY_BUFFER, HarnessBuffers.bytes(buf), CgGL.GL_STATIC_DRAW);

        int stride = 5 * 4;
        CgGL.glVertexAttribPointer(0, 2, CgGL.GL_FLOAT, false, stride, 0);
        CgGL.glEnableVertexAttribArray(0);
        CgGL.glVertexAttribPointer(1, 3, CgGL.GL_FLOAT, false, stride, 8);
        CgGL.glEnableVertexAttribArray(1);

        CgGL.glUseProgram(program);
        CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        CgGL.glUseProgram(0);

        CgGL.glBindVertexArray(0);
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, 0);
        CgGL.glDeleteBuffers(vbo);
        CgGL.glDeleteVertexArrays(vao);
        CgGL.glDeleteProgram(program);
        
    }
}
