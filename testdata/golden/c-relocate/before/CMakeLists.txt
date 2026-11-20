cmake_minimum_required(VERSION 3.20)
project(golden LANGUAGES C)
add_library(component STATIC src/component/a.c src/component/sub/b.c)
