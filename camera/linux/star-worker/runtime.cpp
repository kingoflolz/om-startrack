#include "syscalls.h"

#include <stddef.h>
#include <stdint.h>

namespace {

int runtime_errno;

using SignedSize = __PTRDIFF_TYPE__;

}  // namespace

extern "C" void *memcpy(void *destination, const void *source,
                         size_t size) noexcept {
  auto *output = static_cast<uint8_t *>(destination);
  const auto *input = static_cast<const uint8_t *>(source);
  while (size-- != 0) *output++ = *input++;
  return destination;
}

extern "C" void *memmove(void *destination, const void *source,
                          size_t size) noexcept {
  auto *output = static_cast<uint8_t *>(destination);
  const auto *input = static_cast<const uint8_t *>(source);
  if (reinterpret_cast<uintptr_t>(output) <=
          reinterpret_cast<uintptr_t>(input) ||
      reinterpret_cast<uintptr_t>(output) >=
          reinterpret_cast<uintptr_t>(input) + size) {
    while (size-- != 0) *output++ = *input++;
  } else {
    output += size;
    input += size;
    while (size-- != 0) *--output = *--input;
  }
  return destination;
}

extern "C" void *memset(void *destination, int value, size_t size) noexcept {
  auto *output = static_cast<uint8_t *>(destination);
  const uint8_t byte = static_cast<uint8_t>(value);
  while (size-- != 0) *output++ = byte;
  return destination;
}

extern "C" size_t strlen(const char *text) noexcept {
  const char *cursor = text;
  while (*cursor != '\0') ++cursor;
  return static_cast<size_t>(cursor - text);
}

// GCC may emit the ARM EABI memory entry points even in a freestanding build.
// Keep them local so no libgcc or libc implementation is needed.
extern "C" void __aeabi_memcpy(void *destination, const void *source,
                                size_t size) noexcept {
  (void)memcpy(destination, source, size);
}

extern "C" void __aeabi_memcpy4(void *, const void *, size_t) noexcept
    __attribute__((alias("__aeabi_memcpy")));
extern "C" void __aeabi_memcpy8(void *, const void *, size_t) noexcept
    __attribute__((alias("__aeabi_memcpy")));

extern "C" void __aeabi_memmove(void *destination, const void *source,
                                 size_t size) noexcept {
  (void)memmove(destination, source, size);
}

extern "C" void __aeabi_memmove4(void *, const void *, size_t) noexcept
    __attribute__((alias("__aeabi_memmove")));
extern "C" void __aeabi_memmove8(void *, const void *, size_t) noexcept
    __attribute__((alias("__aeabi_memmove")));

extern "C" void __aeabi_memset(void *destination, size_t size,
                                int value) noexcept {
  (void)memset(destination, value, size);
}

extern "C" void __aeabi_memset4(void *, size_t, int) noexcept
    __attribute__((alias("__aeabi_memset")));
extern "C" void __aeabi_memset8(void *, size_t, int) noexcept
    __attribute__((alias("__aeabi_memset")));

extern "C" void __aeabi_memclr(void *destination, size_t size) noexcept {
  (void)memset(destination, 0, size);
}

extern "C" void __aeabi_memclr4(void *, size_t) noexcept
    __attribute__((alias("__aeabi_memclr")));
extern "C" void __aeabi_memclr8(void *, size_t) noexcept
    __attribute__((alias("__aeabi_memclr")));

// Compatibility for util.cpp, whose existing write_all uses the POSIX write
// declaration and errno.  All new code should prefer om1_stars::os directly.
extern "C" int *__errno_location() noexcept { return &runtime_errno; }

extern "C" SignedSize write(int fd, const void *data, size_t size) noexcept {
  const long result = om1_stars::os::write(fd, data, size);
  if (om1_stars::os::failed(result)) {
    runtime_errno = om1_stars::os::error_number(result);
    return -1;
  }
  return static_cast<SignedSize>(result);
}
