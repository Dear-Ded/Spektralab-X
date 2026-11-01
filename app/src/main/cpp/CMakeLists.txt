# The Android native library: the upstream engine (portable pipeline + Vulkan
# backend + LibRaw), built by upstream's own engine/CMakeLists.txt, plus a thin
# JNI layer. SPK_UPSTREAM is a SpektraLab checkout plus patches/*.patch at the commit
# scripts/fetch-upstream.sh pins, with its build/deps already fetched.
cmake_minimum_required(VERSION 3.22.1)
project(spektralab_android LANGUAGES C CXX)

set(SPK_UPSTREAM "" CACHE PATH "SpektraLab source tree (the Windows_Linux branch)")
if(NOT EXISTS "${SPK_UPSTREAM}/engine/CMakeLists.txt")
    message(FATAL_ERROR "SPK_UPSTREAM='${SPK_UPSTREAM}' is not a SpektraLab checkout; run scripts/fetch-upstream.sh")
endif()

set(SPEKTRALAB_BUILD_NATIVE_RAW ON CACHE BOOL "" FORCE)
set(SPEKTRALAB_BUILD_HOST OFF CACHE BOOL "" FORCE)
set(SPEKTRALAB_BUILD_DESKTOP OFF CACHE BOOL "" FORCE)
set(SPEKTRALAB_BUILD_TEST_DLL OFF CACHE BOOL "" FORCE)
set(SPEKTRALAB_REQUIRE_VULKAN ON CACHE BOOL "" FORCE)
# OpenMP on Android would mean shipping libomp.so; LibRaw runs serially instead.
set(SPEKTRALAB_LIBRAW_OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

set(spk_deps "${SPK_UPSTREAM}/build/deps")
set(SPEKTRALAB_DEPS_DIR "${spk_deps}" CACHE PATH "" FORCE)
set(SPEKTRALAB_LIBRAW_SOURCE "${spk_deps}/LibRaw-0.22.2" CACHE PATH "" FORCE)
set(SPEKTRALAB_VULKAN_HEADERS "${spk_deps}/Vulkan-Headers/include" CACHE PATH "" FORCE)

add_subdirectory("${SPK_UPSTREAM}/engine" engine)

# white_balance.cpp is upstream's desktop-host Temperature/Tint (Robertson
# isotherms, LibRaw's camera matrix), compiled here unmodified.
add_library(spektralab_jni SHARED spektralab_jni.cpp tiff16.cpp resize.cpp
    "${SPK_UPSTREAM}/engine/host/white_balance.cpp")
target_compile_features(spektralab_jni PRIVATE cxx_std_20)
target_include_directories(spektralab_jni PRIVATE
    "${SPK_UPSTREAM}/engine/include"
    "${SPK_UPSTREAM}/engine/src"
    "${SPK_UPSTREAM}/engine/host")
target_link_libraries(spektralab_jni PRIVATE
    spektralab_engine spk_image_io spk_libraw spk_volk android log jnigraphics)
# 16 KB page alignment, required by newer Android releases for native code.
target_link_options(spektralab_jni PRIVATE "-Wl,-z,max-page-size=16384")
