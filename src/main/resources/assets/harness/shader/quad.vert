#version 330 core

layout (location = 0) in vec2 a_pos;
layout (location = 1) in vec2 a_uv;

uniform mat4 u_model;
uniform mat4 u_view;
uniform mat4 u_projection;

out vec2 v_uv;

void main(){
    gl_Position = u_projection * u_view * u_model * vec4(a_pos, 0, 1.);
    v_uv = a_uv;
}