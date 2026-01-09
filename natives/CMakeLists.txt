cmake_minimum_required(VERSION 3.22)
project(nativelib LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

find_package(JNI REQUIRED)

add_library(nativelib SHARED
    src/dev_sweety_NativeApi.h
    src/jni.cpp
    src/engine.h
    src/engine.cpp
)

target_include_directories(nativelib PRIVATE
    ${JNI_INCLUDE_DIRS}
)
