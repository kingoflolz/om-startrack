#ifndef OM1_STARS_SYSCALLS_H
#define OM1_STARS_SYSCALLS_H

#include <stddef.h>
#include <stdint.h>

namespace om1_stars::os {

// Linux ARM EABI values.  These are kept local instead of including target
// kernel or libc headers; the binary talks to the AArch64 kernel's AArch32
// compatibility ABI directly.
constexpr int kOpenReadOnly = 00000000;
constexpr int kOpenWriteOnly = 00000001;
constexpr int kOpenReadWrite = 00000002;
constexpr int kOpenCreate = 00000100;
constexpr int kOpenTruncate = 00001000;
constexpr int kOpenAppend = 00002000;
constexpr int kOpenNonBlock = 00004000;
constexpr int kOpenSync = 04010000;
constexpr int kOpenNoFollow = 00400000;
constexpr int kOpenCloseOnExec = 02000000;

constexpr unsigned kSeekSet = 0;
constexpr unsigned kSeekCurrent = 1;
constexpr unsigned kSeekEnd = 2;

constexpr int kAddressFamilyInet = 2;
constexpr int kSocketStream = 1;
constexpr int kSocketNonBlock = 00004000;
constexpr int kSocketCloseOnExec = 02000000;
constexpr int kSocketLevel = 1;
constexpr int kSocketError = 4;
constexpr int kIpProtocolTcp = 6;
constexpr int kTcpNoDelay = 1;

constexpr int kMessagePeek = 0x0002;
constexpr int kMessageWaitAll = 0x0100;
constexpr int kMessageNoSignal = 0x4000;

constexpr int16_t kPollIn = 0x0001;
constexpr int16_t kPollOut = 0x0004;
constexpr int16_t kPollError = 0x0008;
constexpr int16_t kPollHangup = 0x0010;
constexpr int16_t kPollInvalid = 0x0020;

constexpr int kProtectionNone = 0;
constexpr int kProtectionRead = 0x1;
constexpr int kProtectionWrite = 0x2;
constexpr int kMapShared = 0x01;
constexpr int kMapPrivate = 0x02;
constexpr int kMapFixed = 0x10;
constexpr int kMapAnonymous = 0x20;

constexpr int kErrorInterrupted = 4;
constexpr int kErrorAgain = 11;
constexpr int kErrorInProgress = 115;

struct PollFd {
  int32_t fd;
  int16_t events;
  int16_t revents;
};

struct SockAddrIn {
  uint16_t family;
  uint16_t port;
  uint32_t address;
  uint8_t zero[8];
};

static_assert(sizeof(PollFd) == 8, "ARM Linux pollfd ABI changed");
static_assert(sizeof(SockAddrIn) == 16, "ARM Linux sockaddr_in ABI changed");

constexpr uint16_t host_to_network16(uint16_t value) {
  return static_cast<uint16_t>((value << 8) | (value >> 8));
}

constexpr uint32_t host_to_network32(uint32_t value) {
  return ((value & UINT32_C(0x000000ff)) << 24) |
         ((value & UINT32_C(0x0000ff00)) << 8) |
         ((value & UINT32_C(0x00ff0000)) >> 8) |
         ((value & UINT32_C(0xff000000)) >> 24);
}

// Raw syscall results are nonnegative on success and -errno on failure.
// Testing the unsigned top page also handles pointer-valued mmap2 results.
inline bool failed(long result) {
  return static_cast<unsigned long>(result) >=
         static_cast<unsigned long>(-4095L);
}

inline int error_number(long result) {
  return failed(result) ? static_cast<int>(-result) : 0;
}

long open(const char *path, int flags, unsigned mode = 0);
long read(int fd, void *data, size_t size);
long write(int fd, const void *data, size_t size);
long close(int fd);
long lseek(int fd, int32_t offset, unsigned whence);
long ioctl(int fd, unsigned long request, void *argument);

long nanosleep_ms(uint32_t milliseconds);

// Read CLOCK_MONOTONIC and return whole milliseconds.  The clock is used for
// absolute protocol deadlines so a peer cannot extend a timeout indefinitely
// by sending a packet one byte at a time.
long monotonic_milliseconds(uint64_t *milliseconds);

long socket(int domain, int type, int protocol);
long connect(int fd, const void *address, uint32_t address_size);
long send(int fd, const void *data, size_t size, int flags = 0);
long recv(int fd, void *data, size_t size, int flags = 0);
long setsockopt(int fd, int level, int option, const void *value,
                uint32_t value_size);
long getsockopt(int fd, int level, int option, void *value,
                uint32_t *value_size);
long poll(PollFd *fds, uint32_t count, int timeout_ms);

// Raw futex operation used by the fixed-memory thread pipeline.  The current
// wait/wake operations need only the first four kernel arguments.
long futex(uint32_t *address, int operation, uint32_t value,
           const void *timeout);

// mmap2's offset is in 4096-byte pages, as required by the ARM EABI syscall.
long mmap2(void *address, size_t length, int protection, int flags, int fd,
           uint32_t page_offset);
long munmap(void *address, size_t length);

[[noreturn]] void exit_group(int status);

}  // namespace om1_stars::os

#endif
