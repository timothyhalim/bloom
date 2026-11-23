cmake_minimum_required(VERSION 3.22.1)
project(bloomraw CXX)

# LibRaw 0.22.3 (LGPL-2.1 / CDDL, see libraw/COPYRIGHT). *_ph.cpp placeholders were removed when vendoring.
file(GLOB_RECURSE LIBRAW_SRC CONFIGURE_DEPENDS libraw/src/*.cpp)
add_library(raw STATIC ${LIBRAW_SRC})
target_include_directories(raw PUBLIC libraw)
target_compile_definitions(raw PUBLIC USE_ZLIB LIBRAW_NODLL)
target_compile_options(raw PRIVATE -O2 -w -fexceptions)

add_library(bloomraw SHARED raw.cpp)
target_compile_options(bloomraw PRIVATE -O2 -fexceptions)
target_link_libraries(bloomraw raw jnigraphics z)
