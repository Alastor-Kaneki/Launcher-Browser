cmake_minimum_required(VERSION 3.22)

project(cosmos_double_doors VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Pocket Cosmos pins LiteLDev/preloader-android as a git submodule
# (app/src/main/cpp/preloader). Build against the same commit so the SDK ABI
# matches the libpreloader.so shipped inside the launcher.
set(CDD_PRELOADER_GIT_TAG "5d108da350ce3e7f13f51415f81e65344ecd5d14"
    CACHE STRING "preloader-android commit used by Pocket Cosmos")
set(PRELOADER_ANDROID_ROOT "" CACHE PATH
    "Optional local preloader-android checkout (skips the download)")

include(FetchContent)

if(PRELOADER_ANDROID_ROOT)
    set(CDD_PRELOADER_DIR "${PRELOADER_ANDROID_ROOT}")
else()
    FetchContent_Declare(
        preloader_android
        GIT_REPOSITORY https://github.com/LiteLDev/preloader-android.git
        GIT_TAG ${CDD_PRELOADER_GIT_TAG}
        SOURCE_SUBDIR __cdd_do_not_configure__)
    FetchContent_MakeAvailable(preloader_android)
    set(CDD_PRELOADER_DIR "${preloader_android_SOURCE_DIR}")
endif()
if(NOT EXISTS "${CDD_PRELOADER_DIR}/include/pl/Mod.hpp")
    message(FATAL_ERROR "preloader-android headers not found in ${CDD_PRELOADER_DIR}")
endif()

set(CDD_DEPS)
if(NOT TARGET nlohmann_json::nlohmann_json)
    FetchContent_Declare(nlohmann_json
        GIT_REPOSITORY https://github.com/nlohmann/json.git GIT_TAG v3.11.3)
    list(APPEND CDD_DEPS nlohmann_json)
endif()
if(NOT TARGET Boost::pfr)
    FetchContent_Declare(boost_pfr
        GIT_REPOSITORY https://github.com/boostorg/pfr.git GIT_TAG boost-1.88.0)
    list(APPEND CDD_DEPS boost_pfr)
endif()
if(NOT TARGET magic_enum::magic_enum)
    FetchContent_Declare(magic_enum
        GIT_REPOSITORY https://github.com/Neargye/magic_enum.git GIT_TAG v0.9.7)
    list(APPEND CDD_DEPS magic_enum)
endif()
if(NOT TARGET fmt::fmt)
    FetchContent_Declare(fmt
        GIT_REPOSITORY https://github.com/fmtlib/fmt.git GIT_TAG 11.2.0)
    set(FMT_DOC OFF CACHE BOOL "" FORCE)
    set(FMT_TEST OFF CACHE BOOL "" FORCE)
    set(FMT_INSTALL OFF CACHE BOOL "" FORCE)
    list(APPEND CDD_DEPS fmt)
endif()

set(CDD_LOGIC_SOURCES
    src/logic/DoorModel.cpp
    src/logic/PairFinder.cpp)

function(cdd_warnings target)
    target_compile_options(${target} PRIVATE
        $<$<COMPILE_LANG_AND_ID:CXX,Clang,GNU>:-Wall -Wextra -Wpedantic>)
endfunction()

enable_testing()

if(NOT ANDROID)
    # ---- Host: config generator + pure logic tests --------------------------
    FetchContent_MakeAvailable(${CDD_DEPS})

    add_executable(cdd_config_gen src/config/GenerateConfig.cpp)
    target_include_directories(cdd_config_gen PRIVATE src "${CDD_PRELOADER_DIR}/include")
    target_compile_definitions(cdd_config_gen PRIVATE PL_CONFIG_NO_RUNTIME)
    target_link_libraries(cdd_config_gen PRIVATE
        nlohmann_json::nlohmann_json Boost::pfr magic_enum::magic_enum)
    cdd_warnings(cdd_config_gen)

    add_executable(cdd_logic_tests tests/LogicTests.cpp ${CDD_LOGIC_SOURCES})
    target_include_directories(cdd_logic_tests PRIVATE src tests)
    cdd_warnings(cdd_logic_tests)
    add_test(NAME logic COMMAND cdd_logic_tests)
    return()
endif()

# ---- Android ---------------------------------------------------------------
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Cosmos Double Doors only supports arm64-v8a (got ${ANDROID_ABI})")
endif()

# Builds the SDK's libpreloader.so only to link against it; the launcher
# provides the real one at runtime, so it is not packaged.
add_subdirectory("${CDD_PRELOADER_DIR}" "${CMAKE_BINARY_DIR}/_preloader_android"
                 EXCLUDE_FROM_ALL)
FetchContent_MakeAvailable(${CDD_DEPS})

set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/out/${ANDROID_ABI}")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/out/${ANDROID_ABI}")

set(CDD_RUNTIME_SOURCES
    src/memory/SafeMemory.cpp
    src/memory/ModuleImage.cpp
    src/memory/Rtti.cpp
    src/game/BlockStateReader.cpp)

add_library(cosmos_double_doors SHARED
    src/CosmosDoubleDoors.cpp
    src/game/GameBindings.cpp
    src/sync/DoorSync.cpp
    ${CDD_RUNTIME_SOURCES}
    ${CDD_LOGIC_SOURCES})
target_include_directories(cosmos_double_doors PRIVATE src)
target_link_libraries(cosmos_double_doors PRIVATE
    preloader
    nlohmann_json::nlohmann_json Boost::pfr magic_enum::magic_enum fmt::fmt
    log)
target_compile_options(cosmos_double_doors PRIVATE
    -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(cosmos_double_doors PRIVATE
    -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,--gc-sections)
cdd_warnings(cosmos_double_doors)

# Device-side tests: the real hook/sync code against a fake game compiled
# with the same toolchain, run under qemu-aarch64 (scripts/build.sh, CI).
add_executable(cdd_device_tests EXCLUDE_FROM_ALL
    tests/DeviceTests.cpp
    tests/FakeGame.cpp
    tests/FakeSdk.cpp
    src/game/GameBindings.cpp
    src/sync/DoorSync.cpp
    ${CDD_RUNTIME_SOURCES}
    ${CDD_LOGIC_SOURCES})
target_include_directories(cdd_device_tests PRIVATE
    src tests "${CDD_PRELOADER_DIR}/include")
target_link_libraries(cdd_device_tests PRIVATE
    nlohmann_json::nlohmann_json Boost::pfr magic_enum::magic_enum fmt::fmt)
target_link_options(cdd_device_tests PRIVATE -static)
cdd_warnings(cdd_device_tests)
