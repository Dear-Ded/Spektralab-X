# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11.
cmake_minimum_required(VERSION 3.20)
project(libraw_deflate_probe LANGUAGES CXX)
if(NOT EXISTS "${LIBRAW_SOURCE}/libraw/libraw.h")
    message(FATAL_ERROR "LIBRAW_SOURCE must name the project's patched LibRaw 0.22.2 source")
endif()
option(WITH_ZLIB "Enable LibRaw's Deflate DNG implementation" OFF)
file(GLOB_RECURSE raw_sources CONFIGURE_DEPENDS "${LIBRAW_SOURCE}/src/*.cpp")
list(FILTER raw_sources EXCLUDE REGEX "/[^/]*_ph\\.cpp$")
add_library(raw_probe_library STATIC ${raw_sources})
target_include_directories(raw_probe_library PUBLIC "${LIBRAW_SOURCE}")
target_compile_definitions(raw_probe_library PUBLIC LIBRAW_NODLL SPEKTRALAB_RAW_OPENMP=1
    LIBRAW_MAX_PROFILE_SIZE_MB=16 LIBRAW_MAX_METADATA_ALLOC_SIZE_MB=16 LIBRAW_MAX_TILE_STREAM_COUNT=131072)
target_compile_features(raw_probe_library PUBLIC cxx_std_11)
find_package(OpenMP REQUIRED COMPONENTS CXX)
target_link_libraries(raw_probe_library PUBLIC OpenMP::OpenMP_CXX m)
if(WITH_ZLIB)
    find_package(ZLIB REQUIRED)
    target_compile_definitions(raw_probe_library PUBLIC USE_ZLIB)
    target_link_libraries(raw_probe_library PUBLIC ZLIB::ZLIB)
endif()
add_executable(libraw_deflate ../libraw_deflate.cpp)
target_compile_features(libraw_deflate PRIVATE cxx_std_20)
target_link_libraries(libraw_deflate PRIVATE raw_probe_library)
