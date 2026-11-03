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
# LibRaw's demosaic and decoders run on OpenMP. libomp is linked into this .so
# (-static-openmp below), so no libomp.so ships; thread count and spin policy
# are set per decode in openRaw.
set(SPEKTRALAB_LIBRAW_OPENMP ON CACHE BOOL "" FORCE)
# FindOpenMP (in upstream's LibRaw.cmake) would name the toolchain's libomp.so
# by full path; point it at libomp.a beside it so nothing links the shared one.
execute_process(COMMAND "${CMAKE_CXX_COMPILER}" --target=${CMAKE_CXX_COMPILER_TARGET} -print-file-name=libomp.a
    OUTPUT_VARIABLE spk_libomp OUTPUT_STRIP_TRAILING_WHITESPACE)
if(NOT IS_ABSOLUTE "${spk_libomp}" OR NOT EXISTS "${spk_libomp}")
    file(GLOB spk_libomp "${ANDROID_TOOLCHAIN_ROOT}/lib/clang/*/lib/linux/aarch64/libomp.a")
endif()
if(NOT spk_libomp OR NOT EXISTS "${spk_libomp}")
    message(FATAL_ERROR "LibRaw OpenMP needs the NDK's static libomp.a")
endif()
set(OpenMP_omp_LIBRARY "${spk_libomp}" CACHE FILEPATH "Static libomp from the NDK" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

set(spk_deps "${SPK_UPSTREAM}/build/deps")
set(SPEKTRALAB_DEPS_DIR "${spk_deps}" CACHE PATH "" FORCE)

# LibRaw is built from a copy of the pinned 0.22.2 tree with patches/libraw/*.patch
# applied, in name order. The stamp covers the patches, so editing one re-copies.
set(spk_libraw_pristine "${spk_deps}/LibRaw-0.22.2")
set(spk_libraw "${CMAKE_BINARY_DIR}/LibRaw-0.22.2-spk")
file(GLOB spk_libraw_patches "${CMAKE_CURRENT_LIST_DIR}/../../../../patches/libraw/*.patch")
list(SORT spk_libraw_patches)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${spk_libraw_patches})
set(spk_libraw_stamp "")
foreach(p IN LISTS spk_libraw_patches)
    file(SHA256 "${p}" h)
    string(APPEND spk_libraw_stamp "${h}\n")
endforeach()
if(EXISTS "${spk_libraw}/.spk-patches")
    file(READ "${spk_libraw}/.spk-patches" spk_libraw_have)
else()
    set(spk_libraw_have "-")
endif()
if(NOT spk_libraw_have STREQUAL spk_libraw_stamp)
    find_program(SPK_PATCH patch REQUIRED)
    file(REMOVE_RECURSE "${spk_libraw}")
    file(MAKE_DIRECTORY "${spk_libraw}")
    file(COPY "${spk_libraw_pristine}/src" "${spk_libraw_pristine}/libraw" "${spk_libraw_pristine}/internal"
         "${spk_libraw_pristine}/LICENSE.LGPL" "${spk_libraw_pristine}/COPYRIGHT" DESTINATION "${spk_libraw}")
    foreach(p IN LISTS spk_libraw_patches)
        execute_process(COMMAND "${SPK_PATCH}" -p1 --forward --batch -i "${p}"
            WORKING_DIRECTORY "${spk_libraw}" RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE out)
        if(NOT rc EQUAL 0)
            message(FATAL_ERROR "LibRaw patch ${p} does not apply:\n${out}")
        endif()
    endforeach()
    file(WRITE "${spk_libraw}/.spk-patches" "${spk_libraw_stamp}")
endif()
set(SPEKTRALAB_LIBRAW_SOURCE "${spk_libraw}" CACHE PATH "" FORCE)
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
# The NDK links libomp.so by default; this links libomp.a into the library.
target_link_options(spektralab_jni PRIVATE -fopenmp -static-openmp)
