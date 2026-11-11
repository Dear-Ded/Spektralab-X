# Source-only Linux Vulkan/JNI validation, co-created with GPT (OpenAI).
cmake_minimum_required(VERSION 3.22)
project(spk_android_host_validation LANGUAGES C CXX)

set(SPK_UPSTREAM "" CACHE PATH "Patched upstream checkout")
if(NOT EXISTS "${SPK_UPSTREAM}/engine/CMakeLists.txt")
    message(FATAL_ERROR "Set SPK_UPSTREAM to the fully patched engine checkout")
endif()
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(SPEKTRALAB_BUILD_NATIVE_RAW ON CACHE BOOL "" FORCE)
set(SPEKTRALAB_BUILD_HOST OFF CACHE BOOL "" FORCE)
set(SPEKTRALAB_BUILD_DESKTOP OFF CACHE BOOL "" FORCE)
set(SPEKTRALAB_BUILD_TEST_DLL OFF CACHE BOOL "" FORCE)
set(SPEKTRALAB_REQUIRE_VULKAN ON CACHE BOOL "" FORCE)
set(SPEKTRALAB_LIBRAW_OPENMP ON CACHE BOOL "" FORCE)
set(SPEKTRALAB_DEPS_DIR "${SPK_UPSTREAM}/build/deps" CACHE PATH "" FORCE)
add_subdirectory("${SPK_UPSTREAM}/engine" engine)
target_compile_definitions(spk_libraw PUBLIC
    USE_ZLIB
    LIBRAW_MAX_PROFILE_SIZE_MB=16
    LIBRAW_MAX_METADATA_ALLOC_SIZE_MB=16
    LIBRAW_MAX_TILE_STREAM_COUNT=131072)
target_link_libraries(spk_libraw PUBLIC z)
