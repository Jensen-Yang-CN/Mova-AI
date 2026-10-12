cmake_minimum_required(VERSION 3.22.1)
project(mova_edge LANGUAGES C CXX)

# Android 调试 APK 默认把 JNI 和整个 llama.cpp 以 -O0 编译。0.6B 模型的
# 首批提示预填充因此超过手机端 30 秒保护阈值。保留调试符号并启用 -O2。
if(ANDROID AND CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(CMAKE_BUILD_TYPE "RelWithDebInfo" CACHE STRING "Optimized on-device inference" FORCE)
endif()
message(STATUS "MOVA_NATIVE_BUILD_TYPE=${CMAKE_BUILD_TYPE}")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 上游源码由用户单独取得，版本须与本轮已验证的 GGUF 兼容。
set(LLAMA_SOURCE "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")
if(NOT EXISTS "${LLAMA_SOURCE}/include/llama.h")
    # 服务器上这次克隆的目录名是 llama.cpp-retry；WinSCP 可原名下载。
    set(LLAMA_SOURCE "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp-retry")
endif()
if(NOT EXISTS "${LLAMA_SOURCE}/include/llama.h")
    message(FATAL_ERROR "缺少 llama.cpp/include/llama.h；见 docs/13-Android端侧接入.md")
endif()

# 移动端原型先用 CPU，避免设备 GPU/驱动差异。关闭上游不需要的可执行程序。
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # 演示手机支持 ARM 点积指令。仅优化 ggml CPU 内核，JNI 入口仍保持
    # NDK 默认基线，并在加载前检查硬件特性，旧设备可安全回退云端。
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "Mobile CPU kernel ISA" FORCE)
endif()
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_SOURCE}" "${CMAKE_BINARY_DIR}/llama_cpp")
add_library(mova_edge SHARED edge_jni.cpp)
target_link_libraries(mova_edge PRIVATE llama log)
