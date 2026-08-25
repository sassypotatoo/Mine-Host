cmake_minimum_required(VERSION 3.22.1)
project("minehost_jvm_launcher")

add_executable(
    minehost_jvm_launcher
    main.cpp
)

set_target_properties(
    minehost_jvm_launcher
    PROPERTIES
        OUTPUT_NAME "minehost_jvm_launcher"
        PREFIX "lib"
        SUFFIX ".so"
)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(
    minehost_jvm_launcher
    PRIVATE
        ${log-lib}
        ${dl-lib}
)
