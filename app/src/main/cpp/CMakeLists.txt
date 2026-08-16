cmake_minimum_required(VERSION 3.22.1)
project(mazevm C)

set(CMAKE_C_STANDARD 11)
add_compile_options(-Wall -Wextra -O2)

# The pseudo-terminal used by the container backend. Without it a shell has no tty,
# which costs line editing, job control and anything full-screen.
add_library(mazepty SHARED pty.c)
target_link_libraries(mazepty log)

# There is no exec shim here any more. Routing execve through the system linker was
# never needed: proot substitutes its own loader, which lives in nativeLibraryDir where
# execution is permitted, and that loader maps the guest binary itself.
