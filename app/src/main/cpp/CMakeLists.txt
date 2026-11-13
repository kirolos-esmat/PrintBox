cmake_minimum_required(VERSION 3.22.1)

project("zjsencoder")

add_library(
    zjsencoder
    SHARED
    foo2zjs_jni.c
    jbig.c
    jbig_ar.c
)

find_library(
    log-lib
    log
)

target_link_libraries(
    zjsencoder
    ${log-lib}
)
