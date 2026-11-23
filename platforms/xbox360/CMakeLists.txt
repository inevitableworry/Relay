cmake_minimum_required(VERSION 3.25)
project(relay360 C)

# Standalone Relay native PowerPC developer title, not a wrapper for a
# Windows / Xbox Series binary or another PS Remote Play frontend.
add_executable(relay360 source/main.c)
set_target_properties(relay360 PROPERTIES C_STANDARD 17 C_EXTENSIONS ON)
target_include_directories(relay360 PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/generated")
target_compile_options(relay360 PRIVATE -Wall -Wextra -Werror)
xsdk_add_xex(relay360 TITLE_ID 0x58525259 NAME "Relay for Xbox 360" VERSION 0.1.0.0)
