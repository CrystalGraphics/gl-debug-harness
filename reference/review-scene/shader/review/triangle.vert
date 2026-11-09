#version 330 core
layout(location=0) in vec3 a_pos;
layout(location=1) in vec4 a_col;

uniform mat4 u_model;
uniform mat4 u_view;
uniform mat4 u_projection;


out vec3 v_pos;
out vec4 v_col;

void main(){
    
    gl_Position = u_projection * u_view * u_model *vec4(a_pos *1.9, 1.);
    v_col = a_col;
    v_pos = a_pos;
}