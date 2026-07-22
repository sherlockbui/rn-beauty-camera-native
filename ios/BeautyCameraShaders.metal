#include <metal_stdlib>
using namespace metal;

struct RasterData {
  float4 position [[position]];
  float2 uv;
};

struct BeautyUniforms {
  float4 face;
  float4 leftEye;
  float4 rightEye;
  float4 leftBrow;
  float4 rightBrow;
  float4 nose;
  float4 mouth;
  float2 texelSize;
  float2 uvScale;
  float smoothing;
  float beautyEnabled;
  float highQuality;
  float mirrored;
  float slimFace;
  float enlargeEyes;
  float noseSlim;
};

vertex RasterData beautyVertex(uint vertexId [[vertex_id]]) {
  constexpr float2 positions[] = {
    float2(-1.0, -1.0), float2(1.0, -1.0), float2(-1.0, 1.0),
    float2(-1.0, 1.0), float2(1.0, -1.0), float2(1.0, 1.0)
  };
  constexpr float2 texCoords[] = {
    float2(0.0, 1.0), float2(1.0, 1.0), float2(0.0, 0.0),
    float2(0.0, 0.0), float2(1.0, 1.0), float2(1.0, 0.0)
  };
  RasterData out;
  out.position = float4(positions[vertexId], 0.0, 1.0);
  out.uv = texCoords[vertexId];
  return out;
}

static float ellipseMask(float2 uv, float4 rect, float feather) {
  if (rect.z <= 0.0 || rect.w <= 0.0) return 0.0;
  float2 center = rect.xy + rect.zw * 0.5;
  float2 radius = max(rect.zw * 0.5, float2(0.0001));
  float d = length((uv - center) / radius);
  return 1.0 - smoothstep(1.0 - feather, 1.0 + feather, d);
}

static float3 naturalTone(float3 color) {
  float3 source = color;
  float sourceLuma = dot(color, float3(0.299, 0.587, 0.114));
  float lift = (1.0 - smoothstep(0.18, 0.68, sourceLuma)) * 0.022;
  color = pow(max(color + lift, float3(0.0)), float3(0.955));
  color = (color - 0.5) * 1.018 + 0.5;
  float luma = dot(color, float3(0.299, 0.587, 0.114));
  color = mix(float3(luma), color, 1.045);
  color += float3(0.014, 0.006, -0.007);
  color = mix(color, source, smoothstep(0.82, 1.0, sourceLuma) * 0.22);
  return saturate(color);
}

static float skinConfidence(float3 color) {
  float y = dot(color, float3(0.299, 0.587, 0.114));
  float cb = -0.168736 * color.r - 0.331264 * color.g + 0.5 * color.b + 0.5;
  float cr = 0.5 * color.r - 0.418688 * color.g - 0.081312 * color.b + 0.5;
  float2 chroma = float2((cb - 0.455) / 0.25, (cr - 0.56) / 0.28);
  return saturate((1.0 - smoothstep(0.75, 1.45, length(chroma))) *
                  smoothstep(0.02, 0.08, y));
}

static float2 warpUv(float2 uv, constant BeautyUniforms& u) {
  if (u.face.z <= 0.0 || u.face.w <= 0.0) return uv;

  // 1. Eye Enlargement (Phóng to mắt)
  if (u.enlargeEyes > 0.01) {
    float2 leftCenter = u.leftEye.xy + u.leftEye.zw * 0.5;
    float2 rightCenter = u.rightEye.xy + u.rightEye.zw * 0.5;
    float radius = max(u.leftEye.z, u.leftEye.w) * 1.2;

    if (radius > 0.005) {
      float dLeft = length(uv - leftCenter);
      if (dLeft < radius) {
        float factor = dLeft / radius;
        uv = leftCenter + (uv - leftCenter) * mix(1.0, factor * factor, u.enlargeEyes * 0.35);
      }
      float dRight = length(uv - rightCenter);
      if (dRight < radius) {
        float factor = dRight / radius;
        uv = rightCenter + (uv - rightCenter) * mix(1.0, factor * factor, u.enlargeEyes * 0.35);
      }
    }
  }

  // 2. V-Line Face Slimming (Gọt cằm V-line)
  if (u.slimFace > 0.01) {
    float2 faceCenter = u.face.xy + u.face.zw * 0.5;
    float jawY = u.face.y + u.face.w * 0.65;
    if (uv.y > jawY && uv.y < u.face.y + u.face.w) {
      float distToAxis = abs(uv.x - faceCenter.x);
      float maxDist = u.face.z * 0.5;
      if (distToAxis < maxDist) {
        float pull = (1.0 - distToAxis / maxDist) * u.slimFace * 0.08;
        if (uv.x < faceCenter.x) {
          uv.x += pull;
        } else {
          uv.x -= pull;
        }
      }
    }
  }

  // 3. Nose Slimming (Thon gọn cánh mũi)
  if (u.noseSlim > 0.01 && u.nose.z > 0.0) {
    float2 noseCenter = u.nose.xy + u.nose.zw * 0.5;
    float distToNose = length(uv - noseCenter);
    float noseRadius = max(u.nose.z, u.nose.w) * 1.1;
    if (distToNose < noseRadius) {
      float pull = (1.0 - distToNose / noseRadius) * u.noseSlim * 0.05;
      if (uv.x < noseCenter.x) {
        uv.x += pull;
      } else {
        uv.x -= pull;
      }
    }
  }

  return uv;
}

fragment half4 beautyFragment(
  RasterData in [[stage_in]],
  texture2d<float> cameraTexture [[texture(0)]],
  constant BeautyUniforms& u [[buffer(0)]]) {
  constexpr sampler linearSampler(
    coord::normalized,
    address::clamp_to_edge,
    filter::linear
  );

  float2 uv = (in.uv - 0.5) * u.uvScale + 0.5;
  if (u.mirrored > 0.5) uv.x = 1.0 - uv.x;
  uv = warpUv(uv, u);
  float4 original = cameraTexture.sample(linearSampler, uv);
  if (u.beautyEnabled < 0.5) return half4(original);

  float3 toned = naturalTone(original.rgb);

  float face = (u.face.z <= 0.0 || u.face.w <= 0.0) ? 1.0 : ellipseMask(uv, u.face, 0.55);
  float protectedFeatures = (u.face.z <= 0.0 || u.face.w <= 0.0) ? 0.0 : max(
    max(ellipseMask(uv, u.leftEye, 0.45), ellipseMask(uv, u.rightEye, 0.45)),
    max(
      max(ellipseMask(uv, u.leftBrow, 0.45), ellipseMask(uv, u.rightBrow, 0.45)),
      max(ellipseMask(uv, u.nose, 0.38), ellipseMask(uv, u.mouth, 0.42))
    )
  );

  float skin = skinConfidence(original.rgb);
  float blendMask = face * (1.0 - protectedFeatures * 0.75) * smoothstep(0.10, 0.38, skin);

  if (blendMask < 0.015) {
    return half4(half3(toned), half(original.a));
  }

  // Dual-ring edge-preserving filter for porcelain skin smoothing
  float2 r1 = max(u.texelSize * 5.5, float2(0.0032));
  float2 r2 = max(u.texelSize * 11.5, float2(0.0068));
  float3 center = toned;

  // Inner ring (micro-smoothing)
  float3 s1 = naturalTone(cameraTexture.sample(linearSampler, uv + float2(r1.x, 0)).rgb);
  float3 s2 = naturalTone(cameraTexture.sample(linearSampler, uv - float2(r1.x, 0)).rgb);
  float3 s3 = naturalTone(cameraTexture.sample(linearSampler, uv + float2(0, r1.y)).rgb);
  float3 s4 = naturalTone(cameraTexture.sample(linearSampler, uv - float2(0, r1.y)).rgb);

  // Outer ring (macro-flattening)
  float3 s5 = naturalTone(cameraTexture.sample(linearSampler, uv + float2(r2.x, r2.y)).rgb);
  float3 s6 = naturalTone(cameraTexture.sample(linearSampler, uv + float2(-r2.x, r2.y)).rgb);
  float3 s7 = naturalTone(cameraTexture.sample(linearSampler, uv + float2(r2.x, -r2.y)).rgb);
  float3 s8 = naturalTone(cameraTexture.sample(linearSampler, uv - float2(-r2.x, -r2.y)).rgb);

  float w1 = exp(-dot(s1 - center, s1 - center) * 12.0);
  float w2 = exp(-dot(s2 - center, s2 - center) * 12.0);
  float w3 = exp(-dot(s3 - center, s3 - center) * 12.0);
  float w4 = exp(-dot(s4 - center, s4 - center) * 12.0);
  float w5 = exp(-dot(s5 - center, s5 - center) * 10.0) * 0.8;
  float w6 = exp(-dot(s6 - center, s6 - center) * 10.0) * 0.8;
  float w7 = exp(-dot(s7 - center, s7 - center) * 10.0) * 0.8;
  float w8 = exp(-dot(s8 - center, s8 - center) * 10.0) * 0.8;

  float total = 1.0 + w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8;
  float3 weighted = center * 1.0 + s1 * w1 + s2 * w2 + s3 * w3 + s4 * w4 + s5 * w5 + s6 * w6 + s7 * w7 + s8 * w8;

  float3 smoothed = weighted / max(total, 0.001);
  float blendAmount = clamp(u.smoothing * 2.8, 0.0, 0.99) * blendMask;

  float3 beauty = mix(center, smoothed, blendAmount);
  float3 brightTone = beauty * (float3(1.0) + (float3(1.0) - beauty) * 0.28 * u.smoothing);
  brightTone += float3(0.038, 0.035, 0.035) * u.smoothing;
  beauty = mix(beauty, brightTone, blendMask);

  return half4(half3(saturate(beauty)), half(original.a));
}
