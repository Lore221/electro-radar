#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    // Как в исходном GUI blit: отсекаем только прозрачные тексели атласа до затухания.
    // Проверка итоговой alpha < 0.1, как в position_tex_color, обрывала бы конец затухания.
    if (texel.a == 0.0) {
        discard;
    }
    fragColor = texel * vertexColor * ColorModulator;
}
