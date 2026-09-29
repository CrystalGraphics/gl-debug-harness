package com.crystalgraphics.harness.util;


import com.crystalgraphics.platform.gl.CgGL;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.logging.Logger;

public final class HarnessShaderUtil {

    private static final Logger LOGGER = Logger.getLogger(HarnessShaderUtil.class.getName());

    public static int compileProgram(String vertSource, String fragSource) {
        int vert = compileShader(CgGL.GL_VERTEX_SHADER, vertSource);
        int frag = compileShader(CgGL.GL_FRAGMENT_SHADER, fragSource);

        int program = CgGL.glCreateProgram();
        CgGL.glAttachShader(program, vert);
        CgGL.glAttachShader(program, frag);
        CgGL.glLinkProgram(program);

        if (CgGL.glGetProgrami(program, CgGL.GL_LINK_STATUS) == CgGL.GL_FALSE) {
            String log = CgGL.glGetProgramInfoLog(program, 4096);
            CgGL.glDeleteProgram(program);
            CgGL.glDeleteShader(vert);
            CgGL.glDeleteShader(frag);
            throw new RuntimeException("Shader link failed:\n" + log);
        }

        CgGL.glDeleteShader(vert);
        CgGL.glDeleteShader(frag);
        return program;
    }

    public static String loadResource(String path) {
        InputStream in = HarnessShaderUtil.class.getResourceAsStream(path);
        if (in == null) {
            throw new RuntimeException("Shader resource not found: " + path);
        }
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader: " + path, e);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    private static int compileShader(int type, String source) {
        int shader = CgGL.glCreateShader(type);
        CgGL.glShaderSource(shader, source);
        CgGL.glCompileShader(shader);

        if (CgGL.glGetShaderi(shader, CgGL.GL_COMPILE_STATUS) == CgGL.GL_FALSE) {
            String log = CgGL.glGetShaderInfoLog(shader, 4096);
            CgGL.glDeleteShader(shader);
            String typeName = (type == CgGL.GL_VERTEX_SHADER) ? "vertex" : "fragment";
            throw new RuntimeException(typeName + " shader compilation failed:\n" + log);
        }
        return shader;
    }

    private HarnessShaderUtil() { }
}
