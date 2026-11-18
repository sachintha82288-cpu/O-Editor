package com.oeditor.core.gl

/**
 * GLSL ES 3.00 shader library for the O Editor render graph.
 *
 * All effect shaders share one uniform contract so the renderer can drive any of them
 * without per-shader name mapping:
 *   u_p[16]     generic float parameters (mapped from EffectInstance.params)
 *   u_dir       direction vector (separable blurs)
 *   u_intensity effect strength 0..1
 *   u_time      timeline time in seconds (animated noise/glitch)
 *   u_second    secondary sampler (glow source, transition partner, LUT atlas)
 */
object ShaderLibrary {

    const val VERTEX = """
        #version 300 es
        in vec2 a_pos;
        in vec2 a_tex;
        uniform mat4 u_matrix;
        uniform mat4 u_texMatrix;
        out vec2 v_tex;
        out vec2 v_pos;
        void main() {
            v_tex = (u_texMatrix * vec4(a_tex, 0.0, 1.0)).xy;
            v_pos = a_pos;
            gl_Position = u_matrix * vec4(a_pos, 0.0, 1.0);
        }
    """

    private const val HEADER = """
        #version 300 es
        precision highp float;
        in vec2 v_tex;
        in vec2 v_pos;
        uniform sampler2D u_texture;
        uniform sampler2D u_second;
        uniform vec2 u_resolution;
        uniform vec2 u_dir;
        uniform float u_intensity;
        uniform float u_time;
        uniform float u_p[16];
        out vec4 fragColor;

        float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

        float hash(vec2 p) {
            return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
        }

        vec3 rgb2hsv(vec3 c) {
            vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
            vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
            vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
            float d = q.x - min(q.w, q.y);
            float e = 1.0e-10;
            return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
        }

        vec3 hsv2rgb(vec3 c) {
            vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
            vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
            return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
        }
    """

    private fun shader(body: String): String = HEADER + body

    val PASSTHROUGH = shader("""
        void main() {
            vec4 c = texture(u_texture, v_tex);
            fragColor = vec4(c.rgb, c.a * u_intensity);
        }
    """)

    /** Full colour pipeline: exposure, tonal ranges, white balance, HSL, lift/gamma/gain. */
    val COLOR_GRADE = shader("""
        vec3 applyGrade(vec3 c) {
            float exposure = u_p[0];
            float contrast = u_p[1];
            float highlights = u_p[2];
            float shadows = u_p[3];
            float whites = u_p[4];
            float blacks = u_p[5];
            float temperature = u_p[6];
            float tint = u_p[7];
            float saturation = u_p[8];
            float vibrance = u_p[9];
            float hue = u_p[10];
            float lift = u_p[11];
            float gamma = max(u_p[12], 0.05);
            float gain = u_p[13];

            c *= pow(2.0, exposure);

            float l = luma(c);
            c += shadows * 0.35 * (1.0 - smoothstep(0.0, 0.5, l)) * (1.0 - c);
            c += highlights * 0.35 * smoothstep(0.5, 1.0, l) * (1.0 - c);
            c += whites * 0.25 * smoothstep(0.6, 1.0, l);
            c -= blacks * 0.25 * (1.0 - smoothstep(0.0, 0.4, l));

            c = (c - 0.5) * (1.0 + contrast) + 0.5;

            c.r *= 1.0 + temperature * 0.25;
            c.b *= 1.0 - temperature * 0.25;
            c.g *= 1.0 + tint * 0.18;
            c.r *= 1.0 - tint * 0.09;
            c.b *= 1.0 - tint * 0.09;

            vec3 hsv = rgb2hsv(clamp(c, 0.0, 4.0));
            hsv.x = fract(hsv.x + hue / 360.0);
            float sat = clamp(hsv.y * (1.0 + saturation), 0.0, 1.0);
            float vib = 1.0 + vibrance * (1.0 - hsv.y) * 0.9;
            hsv.y = clamp(sat * vib, 0.0, 1.0);
            c = hsv2rgb(hsv);

            c = c * gain + lift * 0.2;
            c = pow(max(c, 0.0), vec3(1.0 / gamma));
            return c;
        }

        void main() {
            vec4 src = texture(u_texture, v_tex);
            vec3 graded = applyGrade(src.rgb);
            fragColor = vec4(mix(src.rgb, graded, clamp(u_intensity, 0.0, 1.0)), src.a);
        }
    """)

    /** Separable gaussian: run twice with u_dir = (1,0) then (0,1). */
    val BLUR_GAUSSIAN = shader("""
        void main() {
            float radius = max(u_p[0] * u_intensity, 0.0);
            if (radius <= 0.0001) { fragColor = texture(u_texture, v_tex); return; }
            vec2 texel = u_dir / u_resolution * radius * 12.0;
            vec4 sum = texture(u_texture, v_tex) * 0.227027;
            sum += texture(u_texture, v_tex + texel * 1.3846) * 0.316216;
            sum += texture(u_texture, v_tex - texel * 1.3846) * 0.316216;
            sum += texture(u_texture, v_tex + texel * 3.2308) * 0.070270;
            sum += texture(u_texture, v_tex - texel * 3.2308) * 0.070270;
            fragColor = sum;
        }
    """)

    val BLUR_DIRECTIONAL = shader("""
        void main() {
            float angle = radians(u_p[0]);
            float length = u_p[1] * u_intensity;
            vec2 dir = vec2(cos(angle), sin(angle)) * length * 0.08;
            vec4 sum = vec4(0.0);
            const int TAPS = 12;
            for (int i = 0; i < TAPS; i++) {
                float t = (float(i) / float(TAPS - 1)) - 0.5;
                sum += texture(u_texture, v_tex + dir * t);
            }
            fragColor = sum / float(TAPS);
        }
    """)

    val BLUR_RADIAL = shader("""
        void main() {
            vec2 center = vec2(u_p[0], u_p[1]);
            float amount = u_p[2] * u_intensity * 0.12;
            vec2 delta = (v_tex - center) * amount;
            vec4 sum = vec4(0.0);
            const int TAPS = 10;
            for (int i = 0; i < TAPS; i++) {
                float t = float(i) / float(TAPS - 1);
                sum += texture(u_texture, v_tex - delta * t);
            }
            fragColor = sum / float(TAPS);
        }
    """)

    /** Bright-pass plus additive blur (u_second holds the blurred copy). */
    val GLOW = shader("""
        void main() {
            vec4 base = texture(u_texture, v_tex);
            vec4 glow = texture(u_second, v_tex);
            float threshold = u_p[1];
            float strength = u_intensity * (1.0 - threshold) * 1.6;
            fragColor = vec4(base.rgb + glow.rgb * strength, base.a);
        }
    """)

    val BLOOM = shader("""
        void main() {
            vec4 base = texture(u_texture, v_tex);
            vec4 glow = texture(u_second, v_tex);
            vec3 bloom = max(glow.rgb - u_p[1], 0.0);
            fragColor = vec4(base.rgb + bloom * u_intensity * 1.8, base.a);
        }
    """)

    val FILM_GRAIN = shader("""
        void main() {
            vec4 c = texture(u_texture, v_tex);
            float size = max(u_p[0], 0.05);
            vec2 cell = floor(v_tex * u_resolution / (size * 4.0));
            float n = hash(cell + floor(u_time * 24.0));
            float grain = (n - 0.5) * 0.35 * u_intensity;
            fragColor = vec4(c.rgb + grain, c.a);
        }
    """)

    val NOISE = shader("""
        void main() {
            vec4 c = texture(u_texture, v_tex);
            float n = hash(v_tex * u_resolution + floor(u_time * 30.0));
            fragColor = vec4(c.rgb + (n - 0.5) * 0.5 * u_intensity, c.a);
        }
    """)

    val VIGNETTE = shader("""
        void main() {
            vec4 c = texture(u_texture, v_tex);
            float softness = max(u_p[1], 0.01);
            float d = distance(v_tex, vec2(0.5)) / 0.7071;
            float v = smoothstep(0.5 - softness * 0.5, 0.5 + softness, d);
            float amount = u_p[0] * u_intensity;
            fragColor = vec4(c.rgb * (1.0 - v * amount), c.a);
        }
    """)

    val SHARPEN = shader("""
        void main() {
            vec2 t = 1.0 / u_resolution;
            vec4 c = texture(u_texture, v_tex);
            vec3 blur = (
                texture(u_texture, v_tex + vec2(t.x, 0.0)).rgb +
                texture(u_texture, v_tex - vec2(t.x, 0.0)).rgb +
                texture(u_texture, v_tex + vec2(0.0, t.y)).rgb +
                texture(u_texture, v_tex - vec2(0.0, t.y)).rgb
            ) * 0.25;
            fragColor = vec4(c.rgb + (c.rgb - blur) * u_p[0] * u_intensity * 2.0, c.a);
        }
    """)

    val PIXELATE = shader("""
        void main() {
            float size = max(u_p[0], 0.005);
            vec2 blocks = max(u_resolution * size * 0.12, vec2(2.0));
            vec2 uv = (floor(v_tex * blocks) + 0.5) / blocks;
            fragColor = texture(u_texture, uv);
        }
    """)

    val GLITCH = shader("""
        void main() {
            float amount = u_p[0] * u_intensity;
            float speed = u_p[1];
            float t = floor(u_time * (4.0 + speed * 20.0));
            float band = floor(v_tex.y * 24.0);
            float r = hash(vec2(band, t));
            float shift = (r - 0.5) * amount * 0.12 * step(0.72, hash(vec2(band * 1.7, t)));
            vec2 uv = v_tex + vec2(shift, 0.0);
            vec4 c = texture(u_texture, uv);
            float split = amount * 0.006;
            c.r = texture(u_texture, uv + vec2(split, 0.0)).r;
            c.b = texture(u_texture, uv - vec2(split, 0.0)).b;
            fragColor = c;
        }
    """)

    val RGB_SPLIT = shader("""
        void main() {
            float angle = radians(u_p[1]);
            vec2 offset = vec2(cos(angle), sin(angle)) * u_p[0] * u_intensity * 0.02;
            vec4 c;
            c.r = texture(u_texture, v_tex + offset).r;
            c.g = texture(u_texture, v_tex).g;
            c.b = texture(u_texture, v_tex - offset).b;
            c.a = texture(u_texture, v_tex).a;
            fragColor = c;
        }
    """)

    val CHROMATIC = shader("""
        void main() {
            vec2 center = vec2(0.5);
            vec2 dir = v_tex - center;
            float amount = u_p[0] * u_intensity * 0.02;
            vec4 c;
            c.r = texture(u_texture, v_tex + dir * amount).r;
            c.g = texture(u_texture, v_tex).g;
            c.b = texture(u_texture, v_tex - dir * amount).b;
            c.a = texture(u_texture, v_tex).a;
            fragColor = c;
        }
    """)

    val VHS = shader("""
        void main() {
            float amount = u_p[0] * u_intensity;
            float scan = u_p[1];
            float wobble = sin(v_tex.y * 120.0 + u_time * 6.0) * 0.0015 * amount;
            vec2 uv = v_tex + vec2(wobble, 0.0);
            vec4 c;
            c.r = texture(u_texture, uv + vec2(0.002 * amount, 0.0)).r;
            c.g = texture(u_texture, uv).g;
            c.b = texture(u_texture, uv - vec2(0.002 * amount, 0.0)).b;
            c.a = texture(u_texture, uv).a;
            float lines = 1.0 - scan * 0.25 * step(0.5, fract(v_tex.y * u_resolution.y * 0.5));
            c.rgb *= lines;
            c.rgb += (hash(v_tex * u_resolution + u_time) - 0.5) * 0.06 * amount;
            fragColor = c;
        }
    """)

    val LIGHT_LEAK = shader("""
        void main() {
            vec4 c = texture(u_texture, v_tex);
            float pos = u_p[0];
            float warmth = u_p[1];
            float d = distance(v_tex, vec2(pos, 0.1));
            float leak = smoothstep(0.75, 0.0, d) * u_intensity;
            vec3 tint = mix(vec3(1.0, 0.45, 0.15), vec3(1.0, 0.85, 0.55), warmth);
            fragColor = vec4(c.rgb + tint * leak * 0.8, c.a);
        }
    """)

    val LENS_DISTORTION = shader("""
        void main() {
            vec2 uv = v_tex - 0.5;
            float k = u_p[0] * u_intensity;
            float r2 = dot(uv, uv);
            uv *= 1.0 + k * r2;
            uv += 0.5;
            if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                fragColor = vec4(0.0);
            } else {
                fragColor = texture(u_texture, uv);
            }
        }
    """)

    val WAVE_DISTORTION = shader("""
        void main() {
            float amp = u_p[0] * u_intensity * 0.05;
            float freq = u_p[1];
            float speed = u_p[2];
            vec2 uv = v_tex;
            uv.x += sin(v_tex.y * freq + u_time * speed * 6.28) * amp;
            uv.y += cos(v_tex.x * freq + u_time * speed * 6.28) * amp * 0.5;
            fragColor = texture(u_texture, clamp(uv, 0.0, 1.0));
        }
    """)

    val HSL = shader("""
        void main() {
            vec4 src = texture(u_texture, v_tex);
            vec3 hsv = rgb2hsv(src.rgb);
            hsv.x = fract(hsv.x + u_p[0] / 360.0 * u_intensity);
            hsv.y = clamp(hsv.y * (1.0 + u_p[1] * u_intensity), 0.0, 1.0);
            hsv.z = clamp(hsv.z * (1.0 + u_p[2] * u_intensity), 0.0, 1.0);
            fragColor = vec4(hsv2rgb(hsv), src.a);
        }
    """)

    /** LUT atlas: u_second is a tile-encoded 3D LUT, u_p[0] = size, u_p[1] = intensity. */
    val LUT3D = shader("""
        vec3 sampleLut(vec3 color, float size) {
            float sliceSize = 1.0 / size;
            float slicePixelSize = sliceSize / size;
            float sliceInnerSize = slicePixelSize * (size - 1.0);
            float zSlice0 = floor(color.b * (size - 1.0));
            float zSlice1 = min(zSlice0 + 1.0, size - 1.0);
            float xOffset = slicePixelSize * 0.5 + color.r * sliceInnerSize;
            float yRange = (size - 1.0) / size;
            float yOffset = (1.0 - sliceSize) + color.g * yRange;
            vec4 slice0Color = texture(u_second, vec2(zSlice0 * sliceSize + xOffset, yOffset));
            vec4 slice1Color = texture(u_second, vec2(zSlice1 * sliceSize + xOffset, yOffset));
            float zOffset = fract(color.b * (size - 1.0));
            return mix(slice0Color.rgb, slice1Color.rgb, zOffset);
        }

        void main() {
            vec4 src = texture(u_texture, v_tex);
            vec3 graded = sampleLut(clamp(src.rgb, 0.0, 1.0), max(u_p[0], 2.0));
            fragColor = vec4(mix(src.rgb, graded, clamp(u_p[1] * u_intensity, 0.0, 1.0)), src.a);
        }
    """)

    val CHROMA_KEY = shader("""
        void main() {
            vec4 c = texture(u_texture, v_tex);
            vec3 key = vec3(u_p[0], u_p[1], u_p[2]);
            float threshold = u_p[3];
            float smoothness = max(u_p[4], 0.001);
            float spill = u_p[5];
            float diff = distance(c.rgb, key);
            float alpha = smoothstep(threshold, threshold + smoothness, diff);
            alpha = mix(1.0, alpha, clamp(u_intensity, 0.0, 1.0));
            float spillAmount = clamp(c.g - max(c.r, c.b), 0.0, 1.0) * spill * (1.0 - alpha);
            c.g -= spillAmount;
            fragColor = vec4(c.rgb, c.a * alpha);
        }
    """)

    /** Alpha mask: shape, geometry and feather from u_p. */
    val MASK = shader("""
        void main() {
            vec4 c = texture(u_texture, v_tex);
            int shape = int(u_p[0]);
            vec2 center = vec2(u_p[1], u_p[2]);
            vec2 halfSize = vec2(u_p[3], u_p[4]) * 0.5;
            float feather = max(u_p[5], 0.001);
            float inverted = u_p[6];

            float mask;
            if (shape == 2) {
                vec2 d = abs(v_tex - center) - halfSize;
                float dist = length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
                mask = 1.0 - smoothstep(-feather, feather, dist);
            } else {
                float d = length((v_tex - center) / max(halfSize, vec2(0.001)));
                mask = 1.0 - smoothstep(1.0 - feather * 2.0, 1.0, d);
            }
            mask = mix(mask, 1.0 - mask, step(0.5, inverted));
            fragColor = vec4(c.rgb, c.a * mask);
        }
    """)

    /** Blend modes: u_second is the backdrop, u_p[0] the mode index, u_intensity the alpha. */
    val COMPOSITE = shader("""
        vec3 blend(vec3 base, vec3 top, int mode) {
            if (mode == 1) return base + top;
            if (mode == 2) return 1.0 - (1.0 - base) * (1.0 - top);
            if (mode == 3) return base * top;
            if (mode == 4) return mix(2.0 * base * top, 1.0 - 2.0 * (1.0 - base) * (1.0 - top), step(0.5, base));
            if (mode == 5) return mix(2.0 * base * top + base * base * (1.0 - 2.0 * top),
                                       sqrt(base) * (2.0 * top - 1.0) + 2.0 * base * (1.0 - top), step(0.5, top));
            if (mode == 6) return min(base, top);
            if (mode == 7) return max(base, top);
            if (mode == 8) return abs(base - top);
            return top;
        }

        void main() {
            vec4 top = texture(u_texture, v_tex);
            vec4 base = texture(u_second, v_tex);
            float alpha = top.a * clamp(u_intensity, 0.0, 1.0);
            vec3 mixed = blend(base.rgb, top.rgb, int(u_p[0]));
            vec3 outRgb = mix(base.rgb, mixed, alpha);
            float outA = base.a + alpha * (1.0 - base.a);
            fragColor = vec4(outRgb, outA);
        }
    """)

    /** Transitions: u_second is the outgoing frame, u_texture the incoming one. */
    val TRANSITION = shader("""
        float ease(float t) { return t * t * (3.0 - 2.0 * t); }

        void main() {
            int mode = int(u_p[0]);
            float progress = clamp(u_p[1], 0.0, 1.0);
            float direction = u_p[2];
            float softness = max(u_p[3], 0.01);
            float p = ease(progress);

            vec4 from = texture(u_second, v_tex);
            vec4 to = texture(u_texture, v_tex);

            if (mode == 1 || mode == 0) {
                fragColor = mix(from, to, p);
                return;
            }
            if (mode == 2) {
                float firstHalf = step(0.5, progress);
                vec3 black = vec3(0.0);
                vec3 a = mix(from.rgb, black, p * 2.0);
                vec3 b = mix(black, to.rgb, (p - 0.5) * 2.0);
                fragColor = vec4(firstHalf < 0.5 ? a : b, mix(from.a, to.a, progress));
                return;
            }
            if (mode == 3) {
                float angle = radians(direction);
                vec2 dir = vec2(cos(angle), sin(angle));
                float coord = dot(v_tex - 0.5, dir) + 0.5;
                float edge = mix(-softness, 1.0 + softness, p);
                float w = smoothstep(edge - softness, edge + softness, coord);
                fragColor = mix(to, from, w);
                return;
            }
            if (mode == 4) {
                float angle = radians(direction);
                vec2 dir = vec2(cos(angle), sin(angle));
                vec2 offset = dir * (1.0 - p);
                vec4 a = texture(u_second, v_tex + offset);
                vec4 b = texture(u_texture, v_tex - dir * p + dir);
                fragColor = mix(a, b, step(0.5, p));
                return;
            }
            if (mode == 5) {
                vec2 dir = vec2(cos(radians(direction)), sin(radians(direction)));
                vec4 a = texture(u_second, v_tex - dir * p);
                vec4 b = texture(u_texture, v_tex + dir * (1.0 - p));
                fragColor = mix(b, a, step(0.5, p));
                return;
            }
            if (mode == 6) {
                float scaleFrom = 1.0 + p * 0.6;
                float scaleTo = 1.6 - p * 0.6;
                vec4 a = texture(u_second, (v_tex - 0.5) * scaleFrom + 0.5);
                vec4 b = texture(u_texture, (v_tex - 0.5) * scaleTo + 0.5);
                fragColor = mix(a, b, p);
                return;
            }
            if (mode == 7) {
                float angle = p * 3.14159;
                vec2 uvA = (v_tex - 0.5) * (1.0 + p) + 0.5;
                vec4 a = texture(u_second, uvA);
                float ca = cos(angle), sa = sin(angle);
                vec2 uvB = mat2(ca, -sa, sa, ca) * (v_tex - 0.5) + 0.5;
                vec4 b = texture(u_texture, uvB);
                fragColor = mix(a, b, p);
                return;
            }
            if (mode == 8) {
                vec4 a = texture(u_second, v_tex);
                vec4 b = texture(u_texture, v_tex);
                vec4 acc = vec4(0.0);
                for (int i = 0; i < 6; i++) {
                    float f = float(i) / 5.0 - 0.5;
                    acc += mix(a, b, p + f * 0.15);
                }
                fragColor = mix(acc / 6.0, mix(a, b, p), 0.6);
                return;
            }
            if (mode == 9) {
                float flash = 1.0 - abs(p - 0.5) * 2.0;
                vec3 c = mix(from.rgb, to.rgb, p);
                fragColor = vec4(mix(c, vec3(1.0), flash * 0.9), mix(from.a, to.a, p));
                return;
            }
            if (mode == 10) {
                float band = floor(v_tex.y * 40.0);
                float n = hash(vec2(band, floor(u_time * 20.0)));
                float shift = (n - 0.5) * 0.08 * (1.0 - abs(p - 0.5) * 2.0);
                vec4 a = texture(u_second, v_tex + vec2(shift, 0.0));
                vec4 b = texture(u_texture, v_tex - vec2(shift, 0.0));
                fragColor = mix(a, b, step(0.5, n));
                return;
            }
            if (mode == 11) {
                float split = 0.02 * (1.0 - abs(p - 0.5) * 2.0);
                vec4 a = mix(texture(u_second, v_tex + vec2(split, 0.0)), texture(u_second, v_tex), p);
                vec4 b = mix(texture(u_texture, v_tex), texture(u_texture, v_tex - vec2(split, 0.0)), p);
                fragColor = mix(a, b, p);
                return;
            }
            float d = length(v_tex - 0.5);
            float w = smoothstep(0.75, 0.75 - softness, d);
            fragColor = mix(from, to, clamp(p * 2.0 + (1.0 - w) * p, 0.0, 1.0));
        }
    """)

    private val registry: Map<String, String> = mapOf(
        "passthrough" to PASSTHROUGH,
        "color_grade" to COLOR_GRADE,
        "blur_gaussian" to BLUR_GAUSSIAN,
        "blur_directional" to BLUR_DIRECTIONAL,
        "blur_radial" to BLUR_RADIAL,
        "glow" to GLOW,
        "bloom" to BLOOM,
        "film_grain" to FILM_GRAIN,
        "noise" to NOISE,
        "vignette" to VIGNETTE,
        "sharpen" to SHARPEN,
        "pixelate" to PIXELATE,
        "glitch" to GLITCH,
        "rgb_split" to RGB_SPLIT,
        "chromatic" to CHROMATIC,
        "vhs" to VHS,
        "light_leak" to LIGHT_LEAK,
        "lens_distortion" to LENS_DISTORTION,
        "wave_distortion" to WAVE_DISTORTION,
        "hsl" to HSL,
        "lut3d" to LUT3D,
        "chroma_key" to CHROMA_KEY,
        "mask" to MASK,
        "composite" to COMPOSITE,
        "transition" to TRANSITION,
    )

    fun fragment(shaderId: String): String = registry[shaderId] ?: PASSTHROUGH
}
