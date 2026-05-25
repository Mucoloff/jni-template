cmake_minimum_required(VERSION 3.22)
project(native_cpp LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Optimize by default — otherwise benchmarks compare an unoptimized C++ build
# against an optimized Rust --release build.
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()
add_compile_options(-O3)

find_package(JNI REQUIRED)

add_library(native_cpp SHARED
    src/cabi.cpp
    src/jni_hashengine.cpp
)

target_include_directories(native_cpp PRIVATE
    include
    generated            # registrations.generated.h (from native-api.json)
    ${JNI_INCLUDE_DIRS}
)

# The Gradle `buildCpp` task copies the built library into build/natives.
