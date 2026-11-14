set pagination off
set confirm off
set breakpoint pending on
set environment ASAN_OPTIONS detect_leaks=0:halt_on_error=1
set environment UBSAN_OPTIONS print_stacktrace=1:halt_on_error=1
break __ubsan_handle_out_of_bounds
run /data/work/gpt-rank-selection-20261113/host/engine/resources/vulkan
bt 10
info registers rdi rsi rdx rbx rbp r12 r13 r14 r15
frame 1
info locals
info args
disassemble /r $pc-100,$pc+40
quit
