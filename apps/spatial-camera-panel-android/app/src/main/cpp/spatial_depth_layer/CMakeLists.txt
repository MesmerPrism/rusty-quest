cmake_minimum_required(VERSION 3.22.1)
project(rusty_quest_spatial_sdk_depth_handoff LANGUAGES CXX)

add_library(
  XR_APILAYER_MESMERPRISM_spatial_sdk_depth_handoff
  SHARED
  spatial_depth_api_layer.cpp
  spatial_depth_ring_vulkan.cpp
  spatial_depth_queue_broker.cpp
)

target_compile_features(XR_APILAYER_MESMERPRISM_spatial_sdk_depth_handoff PRIVATE cxx_std_20)
target_compile_definitions(
  XR_APILAYER_MESMERPRISM_spatial_sdk_depth_handoff
  PRIVATE VK_USE_PLATFORM_ANDROID_KHR
)
target_compile_options(
  XR_APILAYER_MESMERPRISM_spatial_sdk_depth_handoff
  PRIVATE -Wall -Wextra -Werror
)
target_link_libraries(
  XR_APILAYER_MESMERPRISM_spatial_sdk_depth_handoff
  PRIVATE android log vulkan
)
