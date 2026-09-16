cmake_minimum_required(VERSION 3.22.1)
project(lipmotion_rknn)

add_library(rknnrt SHARED IMPORTED)
set_target_properties(
        rknnrt
        PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/librknnrt.so)

add_library(lipmotion_rknn SHARED rknn_bridge.cpp)
target_include_directories(lipmotion_rknn PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(lipmotion_rknn rknnrt log)
