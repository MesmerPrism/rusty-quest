#version 430
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

#include <data/shaders/common.glsl>
#include <data/shaders/app2vertex.glsl>

layout(location = 0) out struct {
  vec3 objectPosition;
  vec3 worldPosition;
  vec3 worldNormal;
  vec2 uv;
} vertexOut;

layout(std140, set = 3, binding = 0) uniform MaterialUniform {
  vec4 stereoParams;
  vec4 probeParams;
  vec4 colorParams;
} g_MaterialUniform;

void main() {
  App2VertexUnpacked app = getApp2VertexUnpacked();
  vec4 worldPosition = g_PrimitiveUniform.worldFromObject * vec4(app.position, 1.0);
  vertexOut.objectPosition = app.position;
  vertexOut.worldPosition = worldPosition.xyz;
  vertexOut.worldNormal = normalize(
      (transpose(g_PrimitiveUniform.objectFromWorld) * vec4(app.normal, 0.0)).xyz);
  vertexOut.uv = app.uv;
  gl_Position = getClipFromWorld() * worldPosition;
  postprocessPosition(gl_Position);
}
