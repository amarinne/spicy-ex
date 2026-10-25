package com.eza.spicyex.ambient;

/**
 * Paper Shaders Mesh Gradient as an AGSL fragment shader for the existing ambient layer.
 *
 * <p>Adapted from the Apache-2.0 Android port
 * {@code AndreFrelicot/paper-shaders-android} at {@code fc2d75d4dad54b63bbb07df16d8a2d9a2cc467f3}
 * (tag {@code 0.0.3}), file
 * {@code paper-shaders-compose/src/main/kotlin/dev/andrefrelicot/papershaders/shaders/MeshGradientAgsl.kt},
 * with the maths from {@code paper-design/shaders} at
 * {@code 71be01576e8402c62ac76386bb4a7c59092e24c7} (v0.0.82), file
 * {@code packages/shaders/src/shaders/mesh-gradient.ts}. Both projects are Apache-2.0; the license
 * and notices travel in {@code app/src/main/resources/paper-shaders/}.
 *
 * <p>Deliberate differences from the port:
 * <ul>
 *   <li>The port computes the grain mixer and overlay unconditionally; the upstream
 *       {@code > 0} guards are restored here.</li>
 *   <li>{@code AgslCommon.ps_objectUV} is specialised for this layer's fixed contain fit, unit
 *       scale, zero rotation, centred origin and zero world size, so no sizing uniforms exist that
 *       the layer never varies.</li>
 *   <li>The mesh is premultiplied at output, then the existing ambient final grade (saturation,
 *       brightness, darkening and dither) runs on the straight colour so readability and the
 *       Darken setting match the Kawarp effect.</li>
 * </ul>
 */
public final class MeshGradientShader {
    /** Paper's mesh-gradient colour limit. */
    public static final int MAX_COLORS = 10;

    public static final String SOURCE =
            "uniform float2 resolution;\n"
            + "uniform float time;\n"
            + "uniform float dark;\n"
            + "uniform float brightness;\n"
            + "uniform float4 u_colors[10];\n"
            + "uniform float u_colorsCount;\n"
            + "uniform float u_distortion;\n"
            + "uniform float u_swirl;\n"
            + "uniform float u_phase;\n"
            + "uniform float u_motion;\n"
            + "uniform float u_grainMixer;\n"
            + "uniform float u_grainOverlay;\n"
            + "\n"
            + "// AgslCommon.ps_objectUV with the ambient layer's fixed sizing: contain fit, unit scale,\n"
            + "// no rotation, centred origin, zero offset and zero world size. Y stays down as AGSL's\n"
            + "// fragCoord requires; the port documents the same adaption for every object-space shader.\n"
            + "float2 mg_objectUV(float2 pos) {\n"
            + "  float2 uv = float2(pos.x / resolution.x - 0.5, 0.5 - pos.y / resolution.y);\n"
            + "  float side = min(resolution.x, resolution.y);\n"
            + "  return uv * (resolution / side);\n"
            + "}\n"
            + "\n"
            + "float4 mg_colorAt(int index) {\n"
            + "  if (index <= 0) return u_colors[0];\n"
            + "  if (index == 1) return u_colors[1];\n"
            + "  if (index == 2) return u_colors[2];\n"
            + "  if (index == 3) return u_colors[3];\n"
            + "  if (index == 4) return u_colors[4];\n"
            + "  if (index == 5) return u_colors[5];\n"
            + "  if (index == 6) return u_colors[6];\n"
            + "  if (index == 7) return u_colors[7];\n"
            + "  if (index == 8) return u_colors[8];\n"
            + "  return u_colors[9];\n"
            + "}\n"
            + "\n"
            + "float mg_hash21(float2 p) {\n"
            + "  p = fract(p * float2(0.3183099, 0.3678794)) + 0.1;\n"
            + "  p += dot(p, p + 19.19);\n"
            + "  return fract(p.x * p.y);\n"
            + "}\n"
            + "\n"
            + "float mg_valueNoise(float2 st) {\n"
            + "  float2 i = floor(st);\n"
            + "  float2 f = fract(st);\n"
            + "  float a = mg_hash21(i);\n"
            + "  float b = mg_hash21(i + float2(1.0, 0.0));\n"
            + "  float c = mg_hash21(i + float2(0.0, 1.0));\n"
            + "  float d = mg_hash21(i + float2(1.0, 1.0));\n"
            + "  float2 u = f * f * (3.0 - 2.0 * f);\n"
            + "  float x1 = mix(a, b, u.x);\n"
            + "  float x2 = mix(c, d, u.x);\n"
            + "  return mix(x1, x2, u.y);\n"
            + "}\n"
            + "\n"
            + "float2 mg_rotate(float2 uv, float th) {\n"
            + "  return mat2(cos(th), sin(th), -sin(th), cos(th)) * uv;\n"
            + "}\n"
            + "\n"
            + "float2 mg_getPosition(int i, float t) {\n"
            + "  float a = float(i) * 0.37 + u_phase * (0.55 + fract(float(i) / 4.0));\n"
            + "  float b = 0.6 + fract(float(i) / 3.0) * 0.9;\n"
            + "  float c = 0.8 + fract(float(i + 1) / 4.0);\n"
            + "\n"
            + "  float x = sin(t * b + a);\n"
            + "  float y = cos(t * c + a * 1.5);\n"
            + "\n"
            + "  return 0.5 + 0.5 * float2(x, y);\n"
            + "}\n"
            + "\n"
            + "// The ambient layer's dither stage, kept identical to the Kawarp effect.\n"
            + "float mg_ditherHash(float3 p) {\n"
            + "  p = fract(p * 0.1031);\n"
            + "  p += dot(p, p.zyx + 31.32);\n"
            + "  return fract((p.x + p.y) * p.z);\n"
            + "}\n"
            + "\n"
            + "float4 main(float2 fragCoord) {\n"
            + "  float2 uv = mg_objectUV(fragCoord);\n"
            + "  uv += 0.5;\n"
            + "  float2 grainUV = uv * 1000.0;\n"
            + "\n"
            + "  float mixerGrain = 0.0;\n"
            + "  if (u_grainMixer > 0.0) {\n"
            + "    mixerGrain = 0.4 * u_grainMixer * (mg_valueNoise(grainUV) - 0.5);\n"
            + "  }\n"
            + "\n"
            + "  const float firstFrameOffset = 41.5;\n"
            + "  float t = 0.5 * (time + firstFrameOffset) * u_motion;\n"
            + "\n"
            + "  float radius = smoothstep(0.0, 1.0, length(uv - 0.5));\n"
            + "  float center = 1.0 - radius;\n"
            + "  for (float i = 1.0; i <= 2.0; i++) {\n"
            + "    uv.x += u_distortion * center / i * sin(t + i * 0.4 * smoothstep(0.0, 1.0, uv.y)) * cos(0.2 * t + i * 2.4 * smoothstep(0.0, 1.0, uv.y));\n"
            + "    uv.y += u_distortion * center / i * cos(t + i * 2.0 * smoothstep(0.0, 1.0, uv.x));\n"
            + "  }\n"
            + "\n"
            + "  float2 uvRotated = uv;\n"
            + "  uvRotated -= float2(0.5);\n"
            + "  float angle = 3.0 * u_swirl * radius;\n"
            + "  uvRotated = mg_rotate(uvRotated, -angle);\n"
            + "  uvRotated += float2(0.5);\n"
            + "\n"
            + "  float3 color = float3(0.0);\n"
            + "  float opacity = 0.0;\n"
            + "  float totalWeight = 0.0;\n"
            + "\n"
            + "  for (int i = 0; i < 10; i++) {\n"
            + "    if (i >= int(u_colorsCount)) break;\n"
            + "\n"
            + "    float2 pos = mg_getPosition(i, t) + mixerGrain;\n"
            + "    float4 sourceColor = mg_colorAt(i);\n"
            + "    // Ambient palette packing forces active entries opaque; keep Paper's straight-alpha\n"
            + "    // accumulation and premultiply only once at the final output boundary.\n"
            + "    float3 colorFraction = sourceColor.rgb * sourceColor.a;\n"
            + "    float opacityFraction = sourceColor.a;\n"
            + "\n"
            + "    float dist = length(uvRotated - pos);\n"
            + "    dist = pow(dist, 3.5);\n"
            + "    float weight = 1.0 / (dist + 1e-3);\n"
            + "    color += colorFraction * weight;\n"
            + "    opacity += opacityFraction * weight;\n"
            + "    totalWeight += weight;\n"
            + "  }\n"
            + "\n"
            + "  color /= max(1e-4, totalWeight);\n"
            + "  opacity /= max(1e-4, totalWeight);\n"
            + "\n"
            + "  if (u_grainOverlay > 0.0) {\n"
            + "    float grainOverlay = mg_valueNoise(mg_rotate(grainUV, 1.0) + float2(3.0));\n"
            + "    grainOverlay = mix(grainOverlay, mg_valueNoise(mg_rotate(grainUV, 2.0) + float2(-1.0)), 0.5);\n"
            + "    grainOverlay = pow(grainOverlay, 1.3);\n"
            + "\n"
            + "    float grainOverlayV = grainOverlay * 2.0 - 1.0;\n"
            + "    float3 grainOverlayColor = float3(step(0.0, grainOverlayV));\n"
            + "    float grainOverlayStrength = u_grainOverlay * abs(grainOverlayV);\n"
            + "    grainOverlayStrength = pow(grainOverlayStrength, 0.8);\n"
            + "    color = mix(color, grainOverlayColor, 0.35 * grainOverlayStrength);\n"
            + "\n"
            + "    opacity += 0.5 * grainOverlayStrength;\n"
            + "  }\n"
            + "  opacity = clamp(opacity, 0.0, 1.0);\n"
            + "\n"
            + "  // Desktop fullscreen CSS grade shared with the Kawarp effect: normal saturate(2.5)\n"
            + "  // brightness(.65), dark brightness(.38) saturate(.9) contrast(.95), then the dither\n"
            + "  // that retains output precision. Zero brightness stays exactly black.\n"
            + "  float cssGray = dot(color, float3(0.213, 0.715, 0.072));\n"
            + "  float3 normal = clamp(mix(float3(cssGray), color, 2.5), 0.0, 1.0) * 0.65;\n"
            + "  float3 dim = (mix(float3(cssGray), color, 0.9) * 0.38 - 0.5) * 0.95 + 0.5;\n"
            + "  color = mix(normal, dim, dark) * brightness;\n"
            + "  float noise = mg_ditherHash(float3(floor(fragCoord), 0.0));\n"
            + "  color += (noise - 0.5) * 0.008 * min(1.0, brightness * 255.0);\n"
            + "  color = clamp(color, 0.0, 1.0);\n"
            + "\n"
            + "  // AGSL expects premultiplied output; Paper carries straight alpha until this point.\n"
            + "  return float4(color * opacity, opacity);\n"
            + "}\n";

    private MeshGradientShader() {
    }

    /**
     * Packs opaque ambient palette colours into Paper's {@code vec4 u_colors[10]} layout.
     * Alpha is intentionally forced to one because this background contract supplies opaque
     * palette fields; transparent input colors must not be double-premultiplied by the shader.
     */
    public static float[] packColors(int[] colors) {
        float[] packed = new float[MAX_COLORS * 4];
        if (colors == null) return packed;
        int count = Math.min(MAX_COLORS, colors.length);
        for (int i = 0; i < count; i++) {
            int color = colors[i];
            packed[i * 4] = ((color >> 16) & 0xff) / 255f;
            packed[i * 4 + 1] = ((color >> 8) & 0xff) / 255f;
            packed[i * 4 + 2] = (color & 0xff) / 255f;
            packed[i * 4 + 3] = 1f;
        }
        return packed;
    }
}
