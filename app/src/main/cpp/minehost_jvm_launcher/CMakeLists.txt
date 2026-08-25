cmake_minimum_required(VERSION 3.22.1)
project("minehost_jvm_launcher")

# AGP packages only artifacts under the library output directory; placing the
# executable there (with its lib*.so name) lets it ship inside the APK.
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")

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
