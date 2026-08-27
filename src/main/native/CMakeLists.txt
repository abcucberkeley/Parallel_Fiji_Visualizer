cmake_minimum_required(VERSION 3.18)

project(ParallelFijiVisualizerNatives LANGUAGES CXX)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Only the JNI headers are needed (the shims never link libjvm), so accept
# both a full JDK and a headers-only JAVA_HOME -- e.g. an include/ directory
# rsync'd onto the cluster where no JDK with headers is installed.
find_package(JNI)
if(JNI_FOUND)
  set(PFV_JNI_INCLUDE_DIRS ${JNI_INCLUDE_DIRS})
else()
  set(_java_home "$ENV{JAVA_HOME}")
  if(_java_home AND EXISTS "${_java_home}/include/jni.h")
    set(PFV_JNI_INCLUDE_DIRS "${_java_home}/include")
    foreach(_md linux darwin win32)
      if(EXISTS "${_java_home}/include/${_md}")
        list(APPEND PFV_JNI_INCLUDE_DIRS "${_java_home}/include/${_md}")
      endif()
    endforeach()
    message(STATUS "Using JNI headers from JAVA_HOME: ${_java_home}/include")
  else()
    message(FATAL_ERROR "JNI headers not found. Set JAVA_HOME to a JDK, or to "
      "any directory containing include/jni.h and include/<platform>/jni_md.h.")
  endif()
endif()
find_package(OpenMP REQUIRED)
include(ExternalProject)

# ---------------------------------------------------------------------------
# Configurable paths (the Maven -Pnative profile passes these; the defaults
# match the repository layout so a manual cmake invocation also works).
# ---------------------------------------------------------------------------
get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../.." ABSOLUTE)
set(JNI_HEADERS_DIR "${REPO_ROOT}/target/generated-jni-headers" CACHE PATH
    "Directory containing the javac -h generated JNI headers")
set(NATIVES_OUTPUT_DIR "${REPO_ROOT}/target/classes/natives" CACHE PATH
    "Base directory the libraries are installed into (a platform subdir is appended)")
set(NATIVES_DEV_DIR "${REPO_ROOT}/target/natives" CACHE PATH
    "Secondary staging tree for IDE runs (empty to disable). IDEs wipe
     target/classes on full builds; NativeLibraries falls back to this tree.")
set(EXTRA_RUNTIME_LIBS "" CACHE STRING
    "Optional ;-list of extra runtime library files to bundle (e.g. MinGW DLLs)")

# ---------------------------------------------------------------------------
# Platform id -- must match org.scijava.nativelib.NativeLibraryUtil.Architecture
# (lowercased), because NativeLibraries.java looks libraries up under
# natives/<platform>/ inside the plugin jar.
# ---------------------------------------------------------------------------
if(WIN32)
  set(NATIVES_PLATFORM "windows_64")
elseif(APPLE)
  if(CMAKE_SYSTEM_PROCESSOR MATCHES "arm64|aarch64")
    set(NATIVES_PLATFORM "osx_arm64")
  else()
    set(NATIVES_PLATFORM "osx_64")
  endif()
else()
  if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
    set(NATIVES_PLATFORM "linux_arm64")
  else()
    set(NATIVES_PLATFORM "linux_64")
  endif()
endif()
set(STAGE_DIRS "${NATIVES_OUTPUT_DIR}/${NATIVES_PLATFORM}")
if(NATIVES_DEV_DIR)
  list(APPEND STAGE_DIRS "${NATIVES_DEV_DIR}/${NATIVES_PLATFORM}")
endif()
message(STATUS "Native libraries will be staged into: ${STAGE_DIRS}")

# ---------------------------------------------------------------------------
# Upstream libraries: pinned release sources, each built in its own isolated
# CMake invocation (superbuild). Isolation is required, not cosmetic: cpp-tiff
# fetches stock zlib (targets zlib/zlibstatic) while cpp-zarr's c-blosc2
# vendors zlib-ng under the same target names, so the two projects cannot
# share one CMake tree. It also contains cpp-tiff's FORCE'd cache writes
# (BUILD_SHARED_LIBS, SKIP_INSTALL_ALL, ...) which would leak into this build
# under add_subdirectory/FetchContent.
# ---------------------------------------------------------------------------
set(CPP_TIFF_VERSION v1.6.0)
set(CPP_TIFF_SHA256 fc7206eef3fe55ea66f7697eb4257458640cb1dfc11c96131faae96bc4e6d542)
set(CPP_ZARR_VERSION v1.5.2)
set(CPP_ZARR_SHA256 c11bd910fc4f95b2a1519247fb9b88a03f24e69ffc2fa353c2cc80fbd93c542a)

set(DEPS_PREFIX "${CMAKE_CURRENT_BINARY_DIR}/deps")
set(TIFF_PREFIX "${DEPS_PREFIX}/cpp-tiff")
set(ZARR_PREFIX "${DEPS_PREFIX}/cpp-zarr")

# Forward the toolchain so the sub-builds compile the same way we do.
set(EP_TOOLCHAIN_ARGS -DCMAKE_CXX_COMPILER=${CMAKE_CXX_COMPILER})
foreach(v CMAKE_C_COMPILER CMAKE_OSX_ARCHITECTURES CMAKE_OSX_DEPLOYMENT_TARGET CMAKE_MAKE_PROGRAM)
  if(DEFINED ${v})
    list(APPEND EP_TOOLCHAIN_ARGS "-D${v}=${${v}}")
  endif()
endforeach()

# Upstream artifact locations within each install prefix. On MinGW the DLL is
# a RUNTIME artifact (installed to bin/) and linking uses the import library.
if(WIN32)
  set(CPPTIFF_SHARED "bin/libcppTiff.dll")
  set(CPPTIFF_LINK   "lib/libcppTiff.dll.a")
  set(CPPZARR_SHARED "bin/libcppZarr.dll")
  set(CPPZARR_LINK   "lib/libcppZarr.dll.a")
else()
  set(CPPTIFF_SHARED "lib/libcppTiff${CMAKE_SHARED_LIBRARY_SUFFIX}")
  set(CPPTIFF_LINK   "${CPPTIFF_SHARED}")
  set(CPPZARR_SHARED "lib/libcppZarr${CMAKE_SHARED_LIBRARY_SUFFIX}")
  set(CPPZARR_LINK   "${CPPZARR_SHARED}")
endif()

# Note: -DCMAKE_INSTALL_LIBDIR/-DCMAKE_INSTALL_INCLUDEDIR are load-bearing for
# cpp-tiff: its install() rules reference the variables without including
# GNUInstallDirs, so they must be defined from the outside.
ExternalProject_Add(cpp_tiff
  URL      https://github.com/abcucberkeley/cpp-tiff/archive/refs/tags/${CPP_TIFF_VERSION}.tar.gz
  URL_HASH SHA256=${CPP_TIFF_SHA256}
  PREFIX   ${CMAKE_CURRENT_BINARY_DIR}/cpp-tiff
  CMAKE_ARGS
    -DCMAKE_INSTALL_PREFIX=${TIFF_PREFIX}
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_INSTALL_LIBDIR=lib
    -DCMAKE_INSTALL_INCLUDEDIR=include
    ${EP_TOOLCHAIN_ARGS}
  BUILD_BYPRODUCTS
    ${TIFF_PREFIX}/${CPPTIFF_SHARED}
    ${TIFF_PREFIX}/${CPPTIFF_LINK}
)

ExternalProject_Add(cpp_zarr
  URL      https://github.com/abcucberkeley/cpp-zarr/archive/refs/tags/${CPP_ZARR_VERSION}.tar.gz
  URL_HASH SHA256=${CPP_ZARR_SHA256}
  PREFIX   ${CMAKE_CURRENT_BINARY_DIR}/cpp-zarr
  CMAKE_ARGS
    -DCMAKE_INSTALL_PREFIX=${ZARR_PREFIX}
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_INSTALL_LIBDIR=lib
    -DCMAKE_INSTALL_INCLUDEDIR=include
    ${EP_TOOLCHAIN_ARGS}
  BUILD_BYPRODUCTS
    ${ZARR_PREFIX}/${CPPZARR_SHARED}
    ${ZARR_PREFIX}/${CPPZARR_LINK}
)

# ---------------------------------------------------------------------------
# The two JNI libraries. Four shims -> two libraries:
#   libParallelReadC : reader_tiff.cpp + reader_zarr.cpp
#   libParallelWriteC: writer_tiff.cpp + writer_zarr.cpp
# ---------------------------------------------------------------------------
add_library(ParallelReadC SHARED reader_tiff.cpp reader_zarr.cpp)
add_library(ParallelWriteC SHARED writer_tiff.cpp writer_zarr.cpp)

# Each source file sees ONLY its own upstream's headers: cpp-tiff and cpp-zarr
# both install a helperfunctions.h with the identical include guard
# (HELPERFUNCTIONS_H), so their include dirs must never appear on the same
# compile line.
set_source_files_properties(reader_tiff.cpp writer_tiff.cpp PROPERTIES
    INCLUDE_DIRECTORIES "${TIFF_PREFIX}/include")
set_source_files_properties(reader_zarr.cpp writer_zarr.cpp PROPERTIES
    INCLUDE_DIRECTORIES "${ZARR_PREFIX}/include")

foreach(t ParallelReadC ParallelWriteC)
  target_include_directories(${t} PRIVATE
      ${PFV_JNI_INCLUDE_DIRS}
      ${JNI_HEADERS_DIR}
      ${CMAKE_CURRENT_SOURCE_DIR})
  target_link_libraries(${t} PRIVATE
      ${TIFF_PREFIX}/${CPPTIFF_LINK}
      ${ZARR_PREFIX}/${CPPZARR_LINK}
      OpenMP::OpenMP_CXX
      -static-libgcc
      -static-libstdc++)
  add_dependencies(${t} cpp_tiff cpp_zarr)
  if(MINGW)
    # libcppTiff/libcppZarr are built with -static-libgcc, and MinGW DLLs
    # auto-export every global symbol -- including the statically linked
    # unwinder (_Unwind_Resume etc.). Linking their import libs together with
    # our own static libgcc trips "multiple definition"; first-defined wins.
    # Same workaround cpp-zarr itself uses on MinGW.
    target_link_options(${t} PRIVATE -Wl,--allow-multiple-definition)
  endif()
  if(APPLE)
    # libcppTiff/libcppZarr carry @rpath install names; resolve them next to us.
    set_target_properties(${t} PROPERTIES
        BUILD_RPATH "@loader_path" INSTALL_RPATH "@loader_path")
  elseif(UNIX)
    set_target_properties(${t} PROPERTIES
        BUILD_RPATH "\$ORIGIN" INSTALL_RPATH "\$ORIGIN")
  endif()
endforeach()

# ---------------------------------------------------------------------------
# Staging: everything the plugin needs at runtime goes into one flat dir per
# destination -- target/classes/natives/<platform> (packaged into the jar) and
# target/natives/<platform> (survives IDE full builds; loader dev fallback).
# ---------------------------------------------------------------------------
foreach(_dest IN LISTS STAGE_DIRS)
  install(TARGETS ParallelReadC ParallelWriteC
          LIBRARY DESTINATION ${_dest}
          RUNTIME DESTINATION ${_dest})
  install(FILES
          ${TIFF_PREFIX}/${CPPTIFF_SHARED}
          ${ZARR_PREFIX}/${CPPZARR_SHARED}
          DESTINATION ${_dest})
endforeach()

# Bundle the OpenMP runtime when the compiler can point us at it, so systems
# without a GCC runtime still work (the upstream libs link it dynamically and
# find the bundled copy through their $ORIGIN run path / Windows preload).
if(WIN32)
  set(_runtime_names libgomp-1.dll libwinpthread-1.dll libgcc_s_seh-1.dll)
elseif(APPLE)
  # NOTE: on macOS gcc emits an absolute install name for libgomp; bundling
  # alone is not enough -- the CI mac stage must also run install_name_tool.
  set(_runtime_names libgomp.1.dylib)
else()
  set(_runtime_names libgomp.so.1)
endif()
foreach(_rt ${_runtime_names})
  execute_process(COMMAND ${CMAKE_CXX_COMPILER} -print-file-name=${_rt}
                  OUTPUT_VARIABLE _rt_path OUTPUT_STRIP_TRAILING_WHITESPACE
                  ERROR_QUIET)
  if(NOT (_rt_path AND IS_ABSOLUTE "${_rt_path}" AND EXISTS "${_rt_path}"))
    # MinGW keeps its runtime DLLs next to the compiler itself, where
    # -print-file-name does not look.
    get_filename_component(_cc_dir "${CMAKE_CXX_COMPILER}" DIRECTORY)
    if(EXISTS "${_cc_dir}/${_rt}")
      set(_rt_path "${_cc_dir}/${_rt}")
    endif()
  endif()
  if(_rt_path AND IS_ABSOLUTE "${_rt_path}" AND EXISTS "${_rt_path}")
    get_filename_component(_rt_real "${_rt_path}" REALPATH)
    message(STATUS "Bundling runtime library ${_rt} (${_rt_real})")
    foreach(_dest IN LISTS STAGE_DIRS)
      install(FILES ${_rt_real} DESTINATION ${_dest} RENAME ${_rt})
    endforeach()
  else()
    message(STATUS "Runtime library ${_rt} not found via compiler; not bundling")
  endif()
endforeach()
if(EXTRA_RUNTIME_LIBS)
  foreach(_dest IN LISTS STAGE_DIRS)
    install(FILES ${EXTRA_RUNTIME_LIBS} DESTINATION ${_dest})
  endforeach()
endif()
