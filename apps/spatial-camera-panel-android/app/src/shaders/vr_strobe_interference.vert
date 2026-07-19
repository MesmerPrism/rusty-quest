#version 430
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

#include <data/shaders/common.glsl>
#include <data/shaders/app2vertex.glsl>

#define PATTERN_FIELDS(P) \
  vec4 P##0##A; vec4 P##0##B; vec4 P##0##C; vec4 P##0##D; vec4 P##0##E; \
  vec4 P##1##A; vec4 P##1##B; vec4 P##1##C; vec4 P##1##D; vec4 P##1##E; \
  vec4 P##2##A; vec4 P##2##B; vec4 P##2##C; vec4 P##2##D; vec4 P##2##E; \
  vec4 P##3##A; vec4 P##3##B; vec4 P##3##C; vec4 P##3##D; vec4 P##3##E; \
  vec4 P##4##A; vec4 P##4##B; vec4 P##4##C; vec4 P##4##D; vec4 P##4##E; \
  vec4 P##5##A; vec4 P##5##B; vec4 P##5##C; vec4 P##5##D; vec4 P##5##E; \
  vec4 P##6##A; vec4 P##6##B; vec4 P##6##C; vec4 P##6##D; vec4 P##6##E; \
  vec4 P##7##A; vec4 P##7##B; vec4 P##7##C; vec4 P##7##D; vec4 P##7##E;

layout(std140, set = 3, binding = 0) uniform MaterialUniform {
  vec4 carrierShape;
  vec4 modeTime;
  vec4 color1;
  vec4 color2;
  vec4 color3;
  vec4 colorAnim;
  vec4 global0;
  vec4 global1;
  vec4 global2;
  vec4 post0;
  vec4 effects0;
  vec4 effects1;
  vec4 strobe0;
  vec4 strobe1;
  vec4 strobe2;
  vec4 fixationColor;
  vec4 patternCounts;
  PATTERN_FIELDS(stripe)
  PATTERN_FIELDS(ripple)
  PATTERN_FIELDS(ray)
  PATTERN_FIELDS(perlin)
} g_MaterialUniform;

layout(location = 0) out struct {
  vec3 objectPosition;
} vertexOut;

void main() {
  App2VertexUnpacked app = getApp2VertexUnpacked();
  vec3 carrierPosition = app.position;
  float polarAngle =
      g_MaterialUniform.carrierShape.z * clamp(g_MaterialUniform.carrierShape.y, 0.0, 1.0);
  float carrierRadius = max(g_MaterialUniform.carrierShape.w, 0.0001);
  float flatRadius = length(app.position.xy);
  if (
      g_MaterialUniform.carrierShape.x > 0.5 &&
      polarAngle > 0.0001 &&
      flatRadius > 0.0001) {
    float normalizedRadius = clamp(flatRadius / carrierRadius, 0.0, 1.0);
    float sphereRadius = carrierRadius / sin(polarAngle);
    float pointAngle = normalizedRadius * polarAngle;
    float mappedRadius = sphereRadius * sin(pointAngle);
    carrierPosition.xy = app.position.xy * (mappedRadius / flatRadius);
    // Positive local Z puts the rim toward the viewer on this carrier pose,
    // exposing the inside of the spherical bowl rather than its convex shell.
    carrierPosition.z += sphereRadius * (1.0 - cos(pointAngle));
  }
  vec4 worldPosition = g_PrimitiveUniform.worldFromObject * vec4(carrierPosition, 1.0);
  vertexOut.objectPosition = app.position;
  gl_Position = getClipFromWorld() * worldPosition;
  postprocessPosition(gl_Position);
}
