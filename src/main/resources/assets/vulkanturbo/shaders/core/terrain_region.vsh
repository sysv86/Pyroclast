#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:terrainglobals.glsl>

layout(push_constant) uniform RegionPush {
    ivec3 RegionOrigin;
    float RegionVisibility;
};

#ifdef COMPACT_VERTEX_20B
layout(location = 0) in uvec4 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#else
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
#ifdef EXTENDED_VERTEX_40B
layout(location = 4) in vec4 Normal;
layout(location = 5) in vec4 Tangent;
layout(location = 6) in vec2 MidUV;
#endif
#endif

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler2;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out float chunkVisibility;

void main() {
    #ifdef COMPACT_VERTEX_20B
    vec3 localPos = vec3(Position.xyz) * (1.0 / 512.0) - vec3(16.0);
    ivec2 uv2 = ivec2(int(Position.w & 0xFFu), int((Position.w >> 8u) & 0xFFu));
    #else
    vec3 localPos = Position;
    ivec2 uv2 = UV2;
    #endif

    vec3 pos = localPos + (RegionOrigin - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    #ifndef OIT_ALPHA_ONLY
    vertexColor = Color * sample_lightmap(Sampler2, uv2);
    #else
    vertexColor = Color;
    #endif
    texCoord0 = UV0;

    if (RegionVisibility >= 1.0) {
        chunkVisibility = 1.0;
    } else {
        const float chunkFullyVisibleRange = 16.0;
        float dist = length(pos);
        chunkVisibility = mix(1.0, RegionVisibility, clamp((dist - chunkFullyVisibleRange) / chunkFullyVisibleRange, 0.0, 1.0));
    }
}
