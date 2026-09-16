cmake_minimum_required(VERSION 3.22.1)
project(agegender_rknn)
add_library(rknnrt SHARED IMPORTED)
set_target_properties(rknnrt PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/${ANDROID_ABI}/librknnrt.so)
add_library(agegender_rknn SHARED rknn_bridge.cpp)
target_include_directories(agegender_rknn PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(agegender_rknn rknnrt log)
