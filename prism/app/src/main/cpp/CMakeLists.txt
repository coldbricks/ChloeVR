cmake_minimum_required(VERSION 3.22.1)
project("openxr_input")

find_package(OpenXR REQUIRED CONFIG)

add_library(openxr_input SHARED
    openxr_input.cpp
    jni_bridge.cpp
    xr_renderer.cpp
    renderer_jni_bridge.cpp
)

target_link_libraries(openxr_input
    OpenXR::openxr_loader
    android
    log
    EGL
    GLESv3
)

target_compile_features(openxr_input PRIVATE cxx_std_17)
