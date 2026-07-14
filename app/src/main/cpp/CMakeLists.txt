cmake_minimum_required(VERSION 3.22.1)
project("maktabah_sqlite")

add_library(
    maktabah_sqlite
    SHARED
    native-lib.cpp
    sqlite3.c
)

target_link_libraries(
    maktabah_sqlite
    log
)

target_compile_definitions(
    maktabah_sqlite
    PRIVATE
    SQLITE_ENABLE_FTS5
)
