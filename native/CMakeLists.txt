cmake_minimum_required(VERSION 3.18)
project(whisper_jni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# whisper.cpp source tree, passed as -DWHISPER_SRC=<path>
if(NOT DEFINED WHISPER_SRC)
    message(FATAL_ERROR "Pass -DWHISPER_SRC=<whisper.cpp source dir>")
endif()

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LTO OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_SRC} ${CMAKE_BINARY_DIR}/whisper-build)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_link_libraries(whisper_jni PRIVATE whisper)
# 16KB page alignment: required by Android 15 devices with 16KB memory pages
# (still fully compatible with 4KB devices).
target_link_options(whisper_jni PRIVATE "-Wl,-z,max-page-size=16384")

# NOTE: whisper/ggml builds with OpenMP, so libwhisper_jni.so dynamically
# links libomp.so. Package the NDK runtime alongside it:
#   $NDK/toolchains/llvm/prebuilt/*/lib/clang/17/lib/linux/aarch64/libomp.so
#   -> app/src/main/jniLibs/arm64-v8a/libomp.so
