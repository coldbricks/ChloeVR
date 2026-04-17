cmake_minimum_required(VERSION 3.22.1)
project("openxr_input")

find_package(OpenXR REQUIRED CONFIG)

add_library(openxr_input SHARED
    openxr_input.cpp
    jni_bridge.cpp
    xr_renderer.cpp
    renderer_jni_bridge.cpp
    xr_scene_occlusion.cpp
)

target_link_libraries(openxr_input
    OpenXR::openxr_loader
    android
    log
    EGL
    GLESv3
)

target_compile_features(openxr_input PRIVATE cxx_std_17)

# Release optimizations
target_compile_options(openxr_input PRIVATE "$<$<CONFIG:Release>:-O2;-DNDEBUG>")

# Contract test — compile-only, validates struct layout via static_assert
add_library(contract_test STATIC ${CMAKE_SOURCE_DIR}/../../test/cpp/controller_state_contract_test.cpp)
target_include_directories(contract_test PRIVATE ${CMAKE_SOURCE_DIR})
