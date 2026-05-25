cmake_minimum_required(VERSION 3.22)
project(native_cpp LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

find_package(JNI REQUIRED)

add_library(native_cpp SHARED
    src/jni_checksum.cpp
    src/jni_buffer.cpp
    src/jni_hasher.cpp
)

target_include_directories(native_cpp PRIVATE
    include
    ${JNI_INCLUDE_DIRS}
)

# The Gradle `buildCpp` task copies the built library into build/natives.
