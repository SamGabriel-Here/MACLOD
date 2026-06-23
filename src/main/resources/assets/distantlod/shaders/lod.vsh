#version 150

// Reference shader for the FUTURE raw-GL / custom-shader path (Week 7-8).
// NOT used by the Week 1 test renderer, which uses Minecraft's built-in
// position_color core shader. Kept here so the GLSL is ready when you switch
// LodRenderer to your own VAO/VBO + shader program.
//
// macOS note: #version 150 == GLSL 1.50 == OpenGL 3.2 Core Profile, the floor
// Apple's GL supports. Stay at or below the GL version Minecraft creates.

in vec3 aPos;     // camera-relative position
in vec4 aColor;   // per-vertex colour

uniform mat4 uModelView;
uniform mat4 uProjection;

out vec4 vColor;
out float vViewDist;

void main() {
    vec4 viewPos = uModelView * vec4(aPos, 1.0);
    vViewDist = length(viewPos.xyz);
    vColor = aColor;
    gl_Position = uProjection * viewPos;
}
