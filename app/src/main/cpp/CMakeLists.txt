cmake_minimum_required(VERSION 3.22.1)
project(defter_whisper LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Neural-network kernels need optimization even when the surrounding Android app is debug.
# Retain debug symbols, but avoid AGP's unoptimized native defaults for long recordings.
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O2")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O2")

# The source is vendored at an immutable upstream release. No FetchContent or downloads.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# x86 Android does not guarantee AVX/FMA. ARM uses the ABI's baseline instructions.
foreach(feature SSE42 BMI2 AVX AVX2 AVX512 AVX512_VBMI AVX512_VNNI FMA F16C)
    set(GGML_${feature} OFF CACHE BOOL "" FORCE)
endforeach()
add_subdirectory(../../../../third_party/whisper.cpp whisper EXCLUDE_FROM_ALL)
add_library(defter_whisper SHARED whisper_jni.cpp)
target_link_libraries(defter_whisper PRIVATE whisper android log)
target_compile_options(defter_whisper PRIVATE -Wall -Wextra -Werror)
# NDK 28 uses 16 KiB compatible ELF alignment by default; keep it explicit for packaging.
target_link_options(defter_whisper PRIVATE "-Wl,-z,max-page-size=16384")
