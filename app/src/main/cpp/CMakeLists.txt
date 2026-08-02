cmake_minimum_required(VERSION 3.22.1)
project(mediatool_demucs LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
set(DEMUCS_ROOT "${REPO_ROOT}/.deps/demucs.cpp")
if(NOT EXISTS "${DEMUCS_ROOT}/src/model.hpp")
    message(FATAL_ERROR "Thiếu demucs.cpp đã ghim. Chạy scripts/prepare_demucs_cpp.sh trước Gradle.")
endif()
if(NOT EXISTS "${DEMUCS_ROOT}/vendor/eigen/Eigen/Core")
    message(FATAL_ERROR "Thiếu Eigen submodule của demucs.cpp.")
endif()

file(GLOB DEMUCS_SOURCES CONFIGURE_DEPENDS "${DEMUCS_ROOT}/src/*.cpp")

add_library(
    mediatool_demucs
    SHARED
    demucs_jni.cpp
    ${DEMUCS_SOURCES}
)

target_include_directories(
    mediatool_demucs
    PRIVATE
    "${DEMUCS_ROOT}/src"
    "${DEMUCS_ROOT}/vendor/eigen"
)

target_compile_options(
    mediatool_demucs
    PRIVATE
    -O3
    -Wall
    -Wextra
    -DNDEBUG
    -DEIGEN_NO_DEBUG
    -fno-unsafe-math-optimizations
    -fassociative-math
    -freciprocal-math
    -fno-signed-zeros
)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(mediatool_demucs PRIVATE -march=armv8-a)
endif()

find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
target_link_libraries(mediatool_demucs ${LOG_LIB} ${ANDROID_LIB})
