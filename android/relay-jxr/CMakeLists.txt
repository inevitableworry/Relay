cmake_minimum_required(VERSION 3.13)
# Can also be configured independently for the bounded native decoder smoke test.
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    project(relay_jxr LANGUAGES C CXX)
endif()

set(JXR_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../third-party/jxrlib")
set(JXR_SOURCES
    image/sys/adapthuff.c image/sys/image.c image/sys/strcodec.c
    image/sys/strPredQuant.c image/sys/strTransform.c image/sys/perfTimerANSI.c
    image/decode/decode.c image/decode/postprocess.c image/decode/segdec.c
    image/decode/strdec.c image/decode/strInvTransform.c
    image/decode/strPredQuantDec.c image/decode/JXRTranscode.c
    image/encode/encode.c image/encode/segenc.c image/encode/strenc.c
    image/encode/strFwdTransform.c image/encode/strPredQuantEnc.c
    jxrgluelib/JXRGlue.c jxrgluelib/JXRMeta.c
    jxrgluelib/JXRGluePFC.c jxrgluelib/JXRGlueJxr.c)
list(TRANSFORM JXR_SOURCES PREPEND "${JXR_ROOT}/")
add_library(relay-jxrlib STATIC ${JXR_SOURCES})
set_target_properties(relay-jxrlib PROPERTIES POSITION_INDEPENDENT_CODE ON C_STANDARD 99)
target_include_directories(relay-jxrlib PUBLIC
    "${JXR_ROOT}/common/include" "${JXR_ROOT}/image/sys" "${JXR_ROOT}/jxrgluelib")
target_compile_definitions(relay-jxrlib PUBLIC __ANSI__ DISABLE_PERF_MEASUREMENT)
target_compile_definitions(relay-jxrlib PRIVATE NDEBUG)
target_compile_options(relay-jxrlib PRIVATE
    -include "${CMAKE_CURRENT_SOURCE_DIR}/allocation_guard.h"
    -fno-strict-aliasing -fwrapv -fvisibility=hidden
    -Wno-deprecated-declarations -Wno-shift-negative-value)

add_library(relay-jxr-core STATIC relay_jxr_decoder.cpp allocation_guard.cpp)
set_target_properties(relay-jxr-core PROPERTIES POSITION_INDEPENDENT_CODE ON CXX_STANDARD 14)
target_link_libraries(relay-jxr-core PUBLIC relay-jxrlib)
target_include_directories(relay-jxr-core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")

if(ANDROID)
    add_library(relay-jxr SHARED relay_jxr_jni.cpp)
    set_target_properties(relay-jxr PROPERTIES CXX_STANDARD 14)
    target_link_libraries(relay-jxr PRIVATE relay-jxr-core jnigraphics log m)
    target_link_options(relay-jxr PRIVATE
        "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
endif()

option(RELAY_JXR_BUILD_TEST "Build the native real-file and malformed-input smoke test" OFF)
if(RELAY_JXR_BUILD_TEST)
    add_executable(relay-jxr-test tests/decode_test.cpp)
    set_target_properties(relay-jxr-test PROPERTIES CXX_STANDARD 14)
    target_link_libraries(relay-jxr-test PRIVATE relay-jxr-core)
    if(UNIX OR ANDROID)
        target_link_libraries(relay-jxr-test PRIVATE m)
    endif()
endif()
