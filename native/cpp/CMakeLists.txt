cmake_minimum_required(VERSION 3.22)
project(native_cpp LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

find_package(JNI REQUIRED)

add_library(native_cpp SHARED
    src/engine.cpp
    src/jni_scalar.cpp
    src/jni_buffer.cpp
    src/jni_engine.cpp
)

target_include_directories(native_cpp PRIVATE
    include
    ${JNI_INCLUDE_DIRS}
)

# Copy output to shared build/natives dir used by Gradle run tasks
set(NATIVES_OUT "${CMAKE_SOURCE_DIR}/../../build/natives")
add_custom_command(TARGET native_cpp POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${NATIVES_OUT}"
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:native_cpp> "${NATIVES_OUT}/"
)
