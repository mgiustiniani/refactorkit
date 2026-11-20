package org.refactorkit.c

import java.nio.file.Path

/** First-party fixed before/after source and manifest images shared by library and transport acceptance. */
object CCMakeIncludeMoveSample {
    fun material(mode: String, visibility: String, renamed: Boolean, valueName: String = "api_value"): Map<Path, String> {
        val directory = if (renamed && mode == "component") "library" else "component"
        val api = if (renamed && mode == "source") "$directory/src/deep/api.c" else "$directory/src/api.c"
        val header = if (renamed && mode == "header") "$directory/include/public/api.h" else "$directory/include/api.h"
        val spelling = if (renamed && mode == "header") "public/api.h" else "api.h"
        return mapOf(Path.of(api) to "#include \"$spelling\"\nint $valueName(void) { return 7; }\n",
            Path.of("$directory/src/use.c") to "#include \"$spelling\"\nint api_use(void) { return $valueName(); }\n",
            Path.of(header) to "#pragma once\nint $valueName(void);\n",
            Path.of("CMakeLists.txt") to """cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
add_library(api STATIC $api $directory/src/use.c)
target_sources(api PRIVATE $header)
target_include_directories(api $visibility $directory/include)
""")
    }

}
