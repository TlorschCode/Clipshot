cmake_minimum_required(VERSION 3.22.1)
project(clip)

add_subdirectory(oboe)

add_library(clip SHARED
        clip.cpp
)

target_link_libraries(clip
        oboe
        log
)
