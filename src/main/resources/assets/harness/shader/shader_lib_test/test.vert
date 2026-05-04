#version 330 core

in vec3 a_pos;
in vec2 a_uv;
in vec4 a_col;

out vec2 v_uv;
out vec4 v_col;

void main() {
    v_uv  = a_uv;
    v_col = a_col;
    gl_Position = vec4(a_pos, 1.0);
}
