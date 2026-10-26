cmake_minimum_required(VERSION 3.22.1)
project(oneverse_screen LANGUAGES CXX)

if(NOT DEFINED NDI_ANDROID_SDK_ROOT)
    message(FATAL_ERROR "Set ndiAndroidSdk in local.properties or NDI_ANDROID_SDK to the installed Android SDK folder")
endif()

set(NDI_INCLUDE_DIR "${NDI_ANDROID_SDK_ROOT}/Include")
set(NDI_LIBRARY "${NDI_ANDROID_SDK_ROOT}/Lib/${ANDROID_ABI}/libndi.so")
if(NOT EXISTS "${NDI_LIBRARY}")
    message(FATAL_ERROR "NDI library not found for ${ANDROID_ABI}: ${NDI_LIBRARY}")
endif()

add_library(ndi SHARED IMPORTED)
set_target_properties(ndi PROPERTIES IMPORTED_LOCATION "${NDI_LIBRARY}")

add_library(oneverse_screen SHARED ndi_bridge.cpp)
target_include_directories(oneverse_screen PRIVATE "${NDI_INCLUDE_DIR}")
target_compile_features(oneverse_screen PRIVATE cxx_std_17)
find_library(android_log log)
target_link_libraries(oneverse_screen PRIVATE ndi ${android_log})

