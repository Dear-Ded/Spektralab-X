cmake_minimum_required(VERSION 3.22)
project(spk_float_dng_host LANGUAGES C CXX)
# Co-created with GPT (OpenAI). Decoder-only replay of the pinned, patched
# upstream; no GPU/resource build or Android package is needed by this probe.
if(NOT EXISTS "${SPK_UPSTREAM}/engine/cmake/LibRaw.cmake")
    message(FATAL_ERROR "SPK_UPSTREAM must name the patched upstream tree")
endif()
include("${SPK_UPSTREAM}/engine/cmake/LibRaw.cmake")
find_package(ZLIB REQUIRED)
target_compile_definitions(spk_libraw PUBLIC USE_ZLIB
    LIBRAW_MAX_PROFILE_SIZE_MB=16 LIBRAW_MAX_METADATA_ALLOC_SIZE_MB=16
    LIBRAW_MAX_TILE_STREAM_COUNT=131072)
target_link_libraries(spk_libraw PUBLIC ZLIB::ZLIB)
add_library(spk_image_io STATIC
    "${SPK_UPSTREAM}/engine/src/io/raw_decoder.cpp"
    "${SPK_UPSTREAM}/engine/src/io/raw_headroom.cpp"
    "${SPK_UPSTREAM}/engine/src/io/image_writer.cpp")
target_compile_features(spk_image_io PUBLIC cxx_std_20)
target_include_directories(spk_image_io PUBLIC "${SPK_UPSTREAM}/engine/src")
target_link_libraries(spk_image_io PUBLIC spk_libraw)
if(MINGW)
    target_compile_definitions(spk_image_io PRIVATE _WIN32_WINNT=0x0601 WINVER=0x0601)
endif()
set_target_properties(spk_libraw spk_image_io PROPERTIES
    ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/engine")

option(SPK_BUILD_RAW_COLOR_AUDIT "Build the production RAW colour inspection tool" OFF)
if(SPK_BUILD_RAW_COLOR_AUDIT)
    add_executable(raw_color_audit ../raw_color_audit.cpp)
    target_include_directories(raw_color_audit PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../../../app/src/main/cpp")
    target_link_libraries(raw_color_audit PRIVATE spk_image_io)
    if(MINGW)
        target_link_options(raw_color_audit PRIVATE -municode)
        if(SPEKTRALAB_STATIC_RUNTIME)
            target_link_options(raw_color_audit PRIVATE -static -static-libgcc -static-libstdc++)
        endif()
    endif()
endif()
