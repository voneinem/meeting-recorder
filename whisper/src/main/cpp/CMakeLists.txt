# Native build of the speech engine (whisper.cpp, pinned by gradle/whisper.pin) and the JNI bridge.
# Research R-2: always optimized, no network access at configure time, no prebuilt binaries.

cmake_minimum_required(VERSION 3.22.1)

# The debug app variant must still link optimized native code (a -O0 engine is unusably slow).
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(whisper_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Reproducibility: no build id, no absolute source paths in the binaries.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffunction-sections -fdata-sections -ffile-prefix-map=${CMAKE_SOURCE_DIR}=. -ffile-prefix-map=${CMAKE_BINARY_DIR}=.")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffunction-sections -fdata-sections -ffile-prefix-map=${CMAKE_SOURCE_DIR}=. -ffile-prefix-map=${CMAKE_BINARY_DIR}=.")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--build-id=none")
set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--build-id=none")

# Reproducibility: no absolute build directory as RUNPATH in the libraries. The libraries sit side by side in the
# app's native library directory, where the loader finds them without it.
set(CMAKE_SKIP_BUILD_RPATH ON)
set(CMAKE_SKIP_INSTALL_RPATH ON)

# Switches of the engine (research R-2). Everything that would download sources, need a dynamic
# OpenMP runtime or pull in a GPU backend is off.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # ggml builds one CPU backend library per CPU generation and loads the best one at run time.
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
    set(WHISPER_ENGINE_DYNAMIC_BACKENDS 1)
else()
    # x86_64 (emulator): one statically linked baseline CPU backend.
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(WHISPER_ENGINE_DYNAMIC_BACKENDS 0)
endif()

# Only the engine itself: never examples, bindings, models, samples or tests (F-Droid rm: list).
add_subdirectory(whisper.cpp)

# The engine has no version function: read it from its CMakeLists.txt.
file(STRINGS whisper.cpp/CMakeLists.txt WHISPER_VERSION_LINES REGEX "^set\\(WHISPER_VERSION_(MAJOR|MINOR|PATCH) ")
foreach(line IN LISTS WHISPER_VERSION_LINES)
    if(line MATCHES "WHISPER_VERSION_(MAJOR|MINOR|PATCH) ([0-9]+)\\)")
        set(ENGINE_${CMAKE_MATCH_1} ${CMAKE_MATCH_2})
    endif()
endforeach()
if(NOT DEFINED ENGINE_MAJOR OR NOT DEFINED ENGINE_MINOR OR NOT DEFINED ENGINE_PATCH)
    message(FATAL_ERROR "Could not read the engine version from whisper.cpp/CMakeLists.txt")
endif()
set(WHISPER_ENGINE_VERSION_STRING "${ENGINE_MAJOR}.${ENGINE_MINOR}.${ENGINE_PATCH}")

add_library(whisper_jni SHARED whisper_jni.cpp)
target_compile_definitions(whisper_jni PRIVATE
    WHISPER_ENGINE_VERSION="${WHISPER_ENGINE_VERSION_STRING}"
    WHISPER_ENGINE_DYNAMIC_BACKENDS=${WHISPER_ENGINE_DYNAMIC_BACKENDS})
target_include_directories(whisper_jni PRIVATE
    whisper.cpp/include
    whisper.cpp/ggml/include)
target_link_libraries(whisper_jni PRIVATE whisper log android)
