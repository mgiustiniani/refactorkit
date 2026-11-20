cmake_minimum_required(VERSION 3.20)
project(golden LANGUAGES C)
add_library(component STATIC src/other/a.c src/other/sub/b.c)
