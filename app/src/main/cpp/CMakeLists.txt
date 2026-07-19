cmake_minimum_required(VERSION 3.22.1)

project(mangossh_pty LANGUAGES CXX)

add_library(mangossh_pty SHARED mosh_pty_bridge.cpp)

find_library(android_log_lib log)

target_compile_features(mangossh_pty PRIVATE cxx_std_20)
target_compile_options(mangossh_pty PRIVATE -Wall -Wextra -Werror -fstack-protector-strong)
target_link_libraries(mangossh_pty PRIVATE ${android_log_lib})
