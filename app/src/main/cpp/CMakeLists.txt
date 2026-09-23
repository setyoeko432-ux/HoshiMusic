cmake_minimum_required(VERSION 3.22.1)
project("hoshi_dsp")

# [V21]: Senior NDK Refactor - Android 15 Alignment & SIMD Optimization
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -flto -fno-finite-math-only -fsigned-zeros")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -flto")

add_library(hoshi_dsp SHARED hoshi_dsp_core.cpp)
find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(hoshi_dsp ${log-lib} ${android-lib})
