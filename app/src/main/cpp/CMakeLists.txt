cmake_minimum_required(VERSION 3.22.1)
project(bloom C CXX)

# LibRaw 0.22.3 (LGPL-2.1 / CDDL, see libraw/COPYRIGHT). *_ph.cpp placeholders were removed when vendoring.
file(GLOB_RECURSE LIBRAW_SRC CONFIGURE_DEPENDS libraw/src/*.cpp)
add_library(raw STATIC ${LIBRAW_SRC})
target_include_directories(raw PUBLIC libraw)
target_compile_definitions(raw PUBLIC USE_ZLIB LIBRAW_NODLL)
target_compile_options(raw PRIVATE -O2 -w -fexceptions)

# MNN 3.6.2 (Apache-2.0, git submodule) for on-device models: CPU, plus OpenCL and Vulkan loaded at runtime if the phone has them.
set(MNN_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(MNN_OPENCL ON CACHE BOOL "" FORCE)
set(MNN_VULKAN ON CACHE BOOL "" FORCE)
set(MNN_LOW_MEMORY ON CACHE BOOL "" FORCE) # models ship with 8-bit weights
add_subdirectory(mnn EXCLUDE_FROM_ALL)

add_library(bloom SHARED raw.cpp segment.cpp)
target_compile_options(bloom PRIVATE -O2 -fexceptions)
target_include_directories(bloom PRIVATE mnn/include)
# MNN registers its ops and backends in static constructors, so the whole archive must be kept.
target_link_libraries(bloom raw -Wl,--whole-archive MNN -Wl,--no-whole-archive jnigraphics z log)
