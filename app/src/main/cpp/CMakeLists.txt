cmake_minimum_required(VERSION 3.22.1)
project("whisper_jni" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------
# whisper.cpp (git submodule)
#   cd app/src/main/cpp && git submodule add https://github.com/ggerganov/whisper.cpp.git
# ---------------------------------------------------------------------------
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

if(NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "whisper.cpp not found at ${WHISPER_DIR}.
         Run: git submodule add https://github.com/ggerganov/whisper.cpp.git app/src/main/cpp/whisper.cpp
         then: git submodule update --init --recursive")
endif()

# whisper.cpp のオプション（Android NDK で安定させるため多くを OFF に）
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

# arm64-v8a は NEON + fp16 を有効に
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-march=armv8.2-a+fp16+dotprod)
endif()

add_subdirectory(${WHISPER_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper_build EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# JNI bridge
# ---------------------------------------------------------------------------
add_library(whisper_jni SHARED whisper_jni.cpp)

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(whisper_jni
    PRIVATE
        whisper
        ${log-lib}
        ${android-lib}
)
