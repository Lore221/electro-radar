#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    // Match the original GUI blit: discard only transparent atlas pixels, before fading.
    if (texel.a == 0.0) {
        discard;
    }
    fragColor = texel * vertexColor * ColorModulator;
}
