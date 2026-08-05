cmake_minimum_required(VERSION 3.22.1)
project(mediatool_spatial LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(phonon SHARED IMPORTED)
set_target_properties(
    phonon
    PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libphonon.so"
)

add_library(
    mediatool_spatial
    SHARED
    spatial_audio_jni.cpp
    room_aware_spatial_jni.cpp
)

target_include_directories(
    mediatool_spatial
    PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/steam_audio/include"
)

target_compile_options(
    mediatool_spatial
    PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
    -fno-exceptions
    -fno-rtti
)

target_link_libraries(
    mediatool_spatial
    phonon
    android
    log
)
