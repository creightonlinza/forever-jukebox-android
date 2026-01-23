cmake_minimum_required(VERSION 3.22.1)

project(fj_oboe)

add_library(fj_oboe SHARED
    native-lib.cpp
)

find_package(oboe REQUIRED CONFIG)
if (NOT oboe_FOUND)
    include(FetchContent)
    FetchContent_Declare(
        oboe
        GIT_REPOSITORY https://github.com/google/oboe.git
        GIT_TAG 1.9.0
    )
    set(OBOE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(OBOE_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(oboe)
    if (NOT TARGET oboe::oboe)
        add_library(oboe::oboe ALIAS oboe)
    endif()
endif()

set(FJ_16K_LINK_OPTIONS
    "-Wl,-z,common-page-size=16384"
    "-Wl,-z,max-page-size=16384"
)

target_link_options(fj_oboe PRIVATE ${FJ_16K_LINK_OPTIONS})
if (TARGET oboe)
    target_link_options(oboe PRIVATE ${FJ_16K_LINK_OPTIONS})
endif()

target_link_libraries(fj_oboe
    oboe::oboe
    log
)
