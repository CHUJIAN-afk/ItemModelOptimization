#version 330 core
layout(location = 0) in vec4 Bounds;
layout(location = 1) in vec4 Shadow;
layout(location = 2) in vec2 Parameters;
uniform mat4 View;
uniform mat4 Projection;
uniform vec3 Camera;
uniform int FogShape;
out vec2 texCoord;
out float alpha;
out float vertexDistance;
out float horizontalDistance;
void main() {
    float x = (gl_VertexID & 2) == 0 ? Bounds.x : Bounds.w;
    float z = (gl_VertexID & 1) == 0 ? Bounds.z : Shadow.x;
    vec3 relative = vec3(x, Bounds.y, z) - Camera;
    vertexDistance = length(relative);
    horizontalDistance = max(length(relative.xz), abs(relative.y));
    vec4 position = View * vec4(relative, 1.0);
    position.xyz *= 0.99975586;
    gl_Position = Projection * position;
    texCoord = (Shadow.yz - vec2(x, z)) / (2.0 * Parameters.x) + 0.5;
    alpha = Shadow.w;
}
