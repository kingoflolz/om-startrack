#include "syscalls.h"

namespace om1_stars::os {

namespace {

constexpr long kSysRead = 3;
constexpr long kSysWrite = 4;
constexpr long kSysOpen = 5;
constexpr long kSysClose = 6;
constexpr long kSysLseek = 19;
constexpr long kSysIoctl = 54;
constexpr long kSysMunmap = 91;
constexpr long kSysNanosleep = 162;
constexpr long kSysPoll = 168;
constexpr long kSysMmap2 = 192;
constexpr long kSysFutex = 240;
constexpr long kSysExitGroup = 248;
constexpr long kSysClockGettime = 263;
constexpr long kSysSocket = 281;
constexpr long kSysConnect = 283;
constexpr long kSysSend = 289;
constexpr long kSysRecv = 291;
constexpr long kSysSetsockopt = 294;
constexpr long kSysGetsockopt = 295;

struct KernelTimespec32 {
  int32_t seconds;
  int32_t nanoseconds;
};

constexpr long kClockMonotonic = 1;

__attribute__((always_inline)) inline long syscall1(long number, long arg0) {
  register long r0 asm("r0") = arg0;
  register long r7 asm("r7") = number;
  asm volatile("svc 0" : "+r"(r0) : "r"(r7) : "memory", "cc");
  return r0;
}

__attribute__((always_inline)) inline long syscall2(long number, long arg0,
                                                     long arg1) {
  register long r0 asm("r0") = arg0;
  register long r1 asm("r1") = arg1;
  register long r7 asm("r7") = number;
  asm volatile("svc 0"
               : "+r"(r0), "+r"(r1)
               : "r"(r7)
               : "memory", "cc");
  return r0;
}

__attribute__((always_inline)) inline long syscall3(long number, long arg0,
                                                     long arg1, long arg2) {
  register long r0 asm("r0") = arg0;
  register long r1 asm("r1") = arg1;
  register long r2 asm("r2") = arg2;
  register long r7 asm("r7") = number;
  asm volatile("svc 0"
               : "+r"(r0), "+r"(r1), "+r"(r2)
               : "r"(r7)
               : "memory", "cc");
  return r0;
}

__attribute__((always_inline)) inline long syscall4(long number, long arg0,
                                                     long arg1, long arg2,
                                                     long arg3) {
  register long r0 asm("r0") = arg0;
  register long r1 asm("r1") = arg1;
  register long r2 asm("r2") = arg2;
  register long r3 asm("r3") = arg3;
  register long r7 asm("r7") = number;
  asm volatile("svc 0"
               : "+r"(r0), "+r"(r1), "+r"(r2), "+r"(r3)
               : "r"(r7)
               : "memory", "cc");
  return r0;
}

__attribute__((always_inline)) inline long syscall5(long number, long arg0,
                                                     long arg1, long arg2,
                                                     long arg3, long arg4) {
  register long r0 asm("r0") = arg0;
  register long r1 asm("r1") = arg1;
  register long r2 asm("r2") = arg2;
  register long r3 asm("r3") = arg3;
  register long r4 asm("r4") = arg4;
  register long r7 asm("r7") = number;
  asm volatile("svc 0"
               : "+r"(r0), "+r"(r1), "+r"(r2), "+r"(r3), "+r"(r4)
               : "r"(r7)
               : "memory", "cc");
  return r0;
}

__attribute__((always_inline)) inline long syscall6(long number, long arg0,
                                                     long arg1, long arg2,
                                                     long arg3, long arg4,
                                                     long arg5) {
  register long r0 asm("r0") = arg0;
  register long r1 asm("r1") = arg1;
  register long r2 asm("r2") = arg2;
  register long r3 asm("r3") = arg3;
  register long r4 asm("r4") = arg4;
  register long r5 asm("r5") = arg5;
  register long r7 asm("r7") = number;
  asm volatile("svc 0"
               : "+r"(r0), "+r"(r1), "+r"(r2), "+r"(r3), "+r"(r4),
                 "+r"(r5)
               : "r"(r7)
               : "memory", "cc");
  return r0;
}

inline long pointer_bits(const void *pointer) {
  return static_cast<long>(reinterpret_cast<uintptr_t>(pointer));
}

}  // namespace

long open(const char *path, int flags, unsigned mode) {
  return syscall3(kSysOpen, pointer_bits(path), flags, mode);
}

long read(int fd, void *data, size_t size) {
  return syscall3(kSysRead, fd, pointer_bits(data), static_cast<long>(size));
}

long write(int fd, const void *data, size_t size) {
  return syscall3(kSysWrite, fd, pointer_bits(data), static_cast<long>(size));
}

long close(int fd) { return syscall1(kSysClose, fd); }

long lseek(int fd, int32_t offset, unsigned whence) {
  return syscall3(kSysLseek, fd, offset, whence);
}

long ioctl(int fd, unsigned long request, void *argument) {
  return syscall3(kSysIoctl, fd, static_cast<long>(request),
                  pointer_bits(argument));
}

long nanosleep_ms(uint32_t milliseconds) {
  KernelTimespec32 request = {
      static_cast<int32_t>(milliseconds / 1000u),
      static_cast<int32_t>((milliseconds % 1000u) * 1000000u),
  };
  KernelTimespec32 remaining = {};
  for (;;) {
    const long result = syscall2(kSysNanosleep, pointer_bits(&request),
                                 pointer_bits(&remaining));
    if (result != -kErrorInterrupted) return result;
    request = remaining;
  }
}

long monotonic_milliseconds(uint64_t *milliseconds) {
  if (milliseconds == nullptr) return -14;  // EFAULT
  KernelTimespec32 value = {};
  const long result =
      syscall2(kSysClockGettime, kClockMonotonic, pointer_bits(&value));
  if (failed(result)) return result;
  if (value.seconds < 0 || value.nanoseconds < 0 ||
      value.nanoseconds >= 1000000000) {
    return -22;  // EINVAL: reject a malformed kernel result defensively.
  }
  *milliseconds = static_cast<uint64_t>(value.seconds) * UINT64_C(1000) +
                  static_cast<uint32_t>(value.nanoseconds) / 1000000u;
  return 0;
}

long socket(int domain, int type, int protocol) {
  return syscall3(kSysSocket, domain, type, protocol);
}

long connect(int fd, const void *address, uint32_t address_size) {
  return syscall3(kSysConnect, fd, pointer_bits(address), address_size);
}

long send(int fd, const void *data, size_t size, int flags) {
  return syscall4(kSysSend, fd, pointer_bits(data), static_cast<long>(size),
                  flags);
}

long recv(int fd, void *data, size_t size, int flags) {
  return syscall4(kSysRecv, fd, pointer_bits(data), static_cast<long>(size),
                  flags);
}

long setsockopt(int fd, int level, int option, const void *value,
                uint32_t value_size) {
  return syscall5(kSysSetsockopt, fd, level, option, pointer_bits(value),
                  value_size);
}

long getsockopt(int fd, int level, int option, void *value,
                uint32_t *value_size) {
  return syscall5(kSysGetsockopt, fd, level, option, pointer_bits(value),
                  pointer_bits(value_size));
}

long poll(PollFd *fds, uint32_t count, int timeout_ms) {
  return syscall3(kSysPoll, pointer_bits(fds), count, timeout_ms);
}

long futex(uint32_t *address, int operation, uint32_t value,
           const void *timeout) {
  return syscall4(kSysFutex, pointer_bits(address), operation, value,
                  pointer_bits(timeout));
}

long mmap2(void *address, size_t length, int protection, int flags, int fd,
           uint32_t page_offset) {
  return syscall6(kSysMmap2, pointer_bits(address), static_cast<long>(length),
                  protection, flags, fd, page_offset);
}

long munmap(void *address, size_t length) {
  return syscall2(kSysMunmap, pointer_bits(address),
                  static_cast<long>(length));
}

[[noreturn]] void exit_group(int status) {
  (void)syscall1(kSysExitGroup, status);
  for (;;) asm volatile("udf #0");
}

}  // namespace om1_stars::os
