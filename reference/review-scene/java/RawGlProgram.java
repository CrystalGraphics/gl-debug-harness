package com.crystalgraphics.harness.util;

import com.crystalgraphics.util.io.CgIO;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.opengl.GL33C.*;

/**
 * A GLSL program from a {@code .vert} and a {@code .frag} on the classpath, through LWJGL's GL alone: what the
 * hand-written GL scenes ({@code image}, {@code light}, {@code light-source}) draw with. GL only: never on
 * {@code --device=vulkan} or {@code tracked}.
 *
 * <pre>{@code
 * RawGlProgram program = RawGlProgram.load("assets/harness/shader/quad.vert", "assets/harness/shader/quad.frag");
 * program.use().mat4("u_projection", projection).set1i("u_texture", 0);
 * glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
 * program.delete();
 * }</pre>
 *
 * <ul>
 *   <li>Uniform setters write the program in use: call {@link #use()} first.</li>
 *   <li>A vertex input with no {@code layout(location)} takes its declaration's index, so a shader written without
 *       them lines up with attribute arrays 0, 1, 2 in order.</li>
 *   <li>A uniform the program does not have is skipped, as GL skips location -1.</li>
 *   <li>The caller draws inside {@code CgGlState.hostForeign} while {@code CgGL}'s shadow exists: the shadow sees none
 *       of this.</li>
 * </ul>
 */
public final class RawGlProgram {

    private static final Pattern INPUT = Pattern.compile("(?m)^\\s*in\\s+\\w+\\s+(\\w+)\\s*;");

    private final int id;
    private final Map<String, Integer> locations = new HashMap<>();

    private RawGlProgram(int id) {
        this.id = id;
    }

    /** Compiles and links the two stages; throws naming the file whose compile or the link failed. */
    public static RawGlProgram load(String vertexPath, String fragmentPath) {
        String vertex = source(vertexPath), fragment = source(fragmentPath);
        int vs = compile(GL_VERTEX_SHADER, vertex, vertexPath), fs = compile(GL_FRAGMENT_SHADER, fragment, fragmentPath);
        int program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        Matcher inputs = INPUT.matcher(vertex);
        for (int index = 0; inputs.find(); index++) glBindAttribLocation(program, index, inputs.group(1));
        glLinkProgram(program);
        glDetachShader(program, vs);
        glDetachShader(program, fs);
        glDeleteShader(vs);
        glDeleteShader(fs);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException(vertexPath + " + " + fragmentPath + " did not link: " + log);
        }
        return new RawGlProgram(program);
    }

    private static String source(String path) {
        String source = CgIO.loadSource(path);
        if (source == null) throw new IllegalArgumentException("no shader at " + path);
        return source;
    }

    private static int compile(int stage, String source, String path) {
        int shader = glCreateShader(stage);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException(path + " did not compile: " + log);
        }
        return shader;
    }

    /** Makes it the program in use. */
    public RawGlProgram use() {
        glUseProgram(id);
        return this;
    }

    private int location(String name) {
        return locations.computeIfAbsent(name, n -> glGetUniformLocation(id, n));
    }

    public RawGlProgram mat4(String name, Matrix4f m) {
        int at = location(name);
        if (at < 0) return this;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(16);
            glUniformMatrix4fv(at, false, m.get(buffer));
        }
        return this;
    }

    public RawGlProgram vec3(String name, Vector3f v) {
        int at = location(name);
        if (at >= 0) glUniform3f(at, v.x, v.y, v.z);
        return this;
    }

    public RawGlProgram vec4(String name, Vector4f v) {
        return vec4(name, v.x, v.y, v.z, v.w);
    }

    public RawGlProgram vec4(String name, float x, float y, float z, float w) {
        int at = location(name);
        if (at >= 0) glUniform4f(at, x, y, z, w);
        return this;
    }

    public RawGlProgram set1i(String name, int value) {
        int at = location(name);
        if (at >= 0) glUniform1i(at, value);
        return this;
    }

    public RawGlProgram set1f(String name, float value) {
        int at = location(name);
        if (at >= 0) glUniform1f(at, value);
        return this;
    }

    public void delete() {
        glDeleteProgram(id);
    }
}
