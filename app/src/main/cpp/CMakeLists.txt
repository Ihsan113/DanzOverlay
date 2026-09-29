cmake_minimum_required(VERSION 3.22.1)
project(danzrenderer CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(danzrenderer SHARED renderer.cpp)

find_library(log-lib log)
target_link_libraries(danzrenderer ${log-lib} EGL GLESv3 android)
