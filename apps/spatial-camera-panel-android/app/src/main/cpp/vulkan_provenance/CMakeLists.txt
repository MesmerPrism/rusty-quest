cmake_minimum_required(VERSION 3.22.1)

if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
  message(FATAL_ERROR "Vulkan provenance must not be built for a release variant")
endif()
if(NOT RQ_VULKAN_PROVENANCE_APP_ID MATCHES "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
  message(FATAL_ERROR "Vulkan provenance requires the exact selected application ID")
endif()

add_library(VkLayer_rusty_quest_provenance SHARED
  vulkan_provenance_layer.cpp
  provenance_recorder.cpp
)
target_compile_features(VkLayer_rusty_quest_provenance PRIVATE cxx_std_20)
target_compile_definitions(VkLayer_rusty_quest_provenance PRIVATE
  VK_USE_PLATFORM_ANDROID_KHR
  RQ_VULKAN_PROVENANCE_APP_ID="${RQ_VULKAN_PROVENANCE_APP_ID}"
)
target_compile_options(VkLayer_rusty_quest_provenance PRIVATE -Wall -Wextra -Werror)
target_link_libraries(VkLayer_rusty_quest_provenance PRIVATE android log dl)
target_link_options(VkLayer_rusty_quest_provenance PRIVATE
  "-Wl,-Bsymbolic-functions"
  # Keep this debug diagnostic module/admission state until process exit.
  # Vulkan instances/devices and their ordinary destruction remain downstream-owned.
  "-Wl,-z,nodelete"
  "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384"
)
