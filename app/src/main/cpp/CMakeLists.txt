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
# Upstream's output transform arithmetic (engine/host/output.cpp: the transfer
# functions and the CAM16-UCS gamut compression), taken verbatim from the pinned
# tree at configure time so the phone converts exactly as the desktop does.
# Markers, not line numbers: a reshuffled upstream file fails here, loudly.
file(READ "${SPK_UPSTREAM}/engine/host/output.cpp" spk_output_src)
function(spk_slice out src from to)
    string(FIND "${${src}}" "${from}" a)
    string(FIND "${${src}}" "${to}" b)
    if(a EQUAL -1 OR b EQUAL -1 OR NOT b GREATER a)
        message(FATAL_ERROR "upstream ${src} changed: cannot find '${from}' .. '${to}'")
    endif()
    math(EXPR n "${b} - ${a}")
    string(SUBSTRING "${${src}}" ${a} ${n} s)
    set(${out} "${s}" PARENT_SCOPE)
endfunction()
spk_slice(spk_cctf spk_output_src "static inline float spow" "bool OutputTransform::fetch")
spk_slice(spk_cam16 spk_output_src "static inline void cam16ucs_into" "template <typename F>")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/generated/upstream_output_math.inc"
    "// Generated from ${SPK_UPSTREAM}/engine/host/output.cpp. Do not edit.
${spk_cctf}
${spk_cam16}
")
# The host's input side (engine/host/image_io.*): the colour spaces and curves
# a display-encoded photo is decoded with before the engine sees it.
file(READ "${SPK_UPSTREAM}/engine/host/image_io.hpp" spk_io_hpp)
file(READ "${SPK_UPSTREAM}/engine/host/image_io.cpp" spk_io_cpp)
spk_slice(spk_rgb_space spk_io_hpp "struct Trc {" "bool parse_icc(")
spk_slice(spk_spaces spk_io_cpp "double Trc::decode" "static uint32_t be32")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/generated/upstream_input_math.inc"
    "// Generated from ${SPK_UPSTREAM}/engine/host/image_io.{hpp,cpp}. Do not edit.
${spk_rgb_space}
${spk_spaces}
")

add_library(spektralab_jni SHARED spektralab_jni.cpp tiff16.cpp resize.cpp layer2.cpp output_transform.cpp input_transform.cpp
    "${SPK_UPSTREAM}/engine/host/white_balance.cpp")
target_compile_features(spektralab_jni PRIVATE cxx_std_20)
target_include_directories(spektralab_jni PRIVATE
    "${SPK_UPSTREAM}/engine/include"
    "${SPK_UPSTREAM}/engine/src"
    "${SPK_UPSTREAM}/engine/host"
    "${CMAKE_CURRENT_BINARY_DIR}/generated")
target_link_libraries(spektralab_jni PRIVATE
    spektralab_engine spk_image_io spk_libraw spk_volk android log jnigraphics z)
# 16 KB page alignment, required by newer Android releases for native code.
target_link_options(spektralab_jni PRIVATE "-Wl,-z,max-page-size=16384")
