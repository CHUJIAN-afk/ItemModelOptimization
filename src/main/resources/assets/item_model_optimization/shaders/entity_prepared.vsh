#version 330 core
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;
layout(location = 6) in vec3 Translation;
layout(location = 7) in vec2 Rotation;
layout(location = 9) in ivec2 Light;
uniform mat4 View;
uniform mat4 Projection;
uniform vec3 Camera;
uniform int FogShape;
uniform vec3 Light0;
uniform vec3 Light1;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord;
void main() {
    float s = Rotation.x, c = Rotation.y;
    mat3 rotation = mat3(c, 0.0, -s, 0.0, 1.0, 0.0, s, 0.0, c);
    vec3 position = rotation * Position + Translation - Camera;
    vec3 normal = normalize(rotation * Normal);
    gl_Position = Projection * View * vec4(position, 1.0);
    vertexDistance = FogShape == 0 ? length(position) : max(length(position.xz), abs(position.y));
    float light = min(1.0, (max(0.0, dot(Light0, normal)) + max(0.0, dot(Light1, normal))) * 0.6 + 0.4);
    vertexColor = vec4(Color.rgb * light, Color.a);
    lightMapColor = texelFetch(Sampler2, clamp(max(UV2, Light) / 16, ivec2(0), ivec2(15)), 0);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord = UV0;
}
