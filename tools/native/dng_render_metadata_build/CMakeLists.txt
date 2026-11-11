# Co-created with GPT (OpenAI), 2026-11-09. See NOTICE.
cmake_minimum_required(VERSION 3.20)
project(dng_render_metadata_probe LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffp-contract=off")
include("${RAW_UPSTREAM}/engine/cmake/LibRaw.cmake")
target_compile_definitions(spk_libraw PUBLIC USE_ZLIB
    LIBRAW_MAX_PROFILE_SIZE_MB=16 LIBRAW_MAX_METADATA_ALLOC_SIZE_MB=16
    LIBRAW_MAX_TILE_STREAM_COUNT=131072)
target_link_libraries(spk_libraw PUBLIC z)
add_library(spk_image_io STATIC "${RAW_UPSTREAM}/engine/src/io/raw_decoder.cpp"
    "${RAW_UPSTREAM}/engine/src/io/raw_headroom.cpp")
target_include_directories(spk_image_io PUBLIC "${RAW_UPSTREAM}/engine/src")
target_link_libraries(spk_image_io PRIVATE spk_libraw)
add_executable(dng_render_metadata "${RAW_REPO}/tools/native/dng_render_metadata.cpp")
target_link_libraries(dng_render_metadata PRIVATE spk_image_io)
