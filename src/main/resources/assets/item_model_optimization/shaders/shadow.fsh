#version 330 core
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
in vec2 texCoord;
in float alpha;
in float vertexDistance;
out vec4 fragColor;
void main() {
    vec4 color = texture(Sampler0, clamp(texCoord, 0.0, 1.0)) * vec4(1.0, 1.0, 1.0, alpha) * ColorModulator;
    if (vertexDistance > FogStart) {
        float fog = vertexDistance < FogEnd ? smoothstep(FogStart, FogEnd, vertexDistance) : 1.0;
        color.rgb = mix(color.rgb, FogColor.rgb, fog * FogColor.a);
    }
    fragColor = color;
}
