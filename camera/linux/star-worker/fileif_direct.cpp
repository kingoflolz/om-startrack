#include "fileif_direct.h"

#include "syscalls.h"
#include "util.h"

namespace om1_stars {

namespace {

constexpr char kSemaphorePath[] = "/dev/shm/sem.sem_fileif";
constexpr char kIscPath[] = "/dev/isc";
constexpr char kMemoryPath[] = "/dev/mem";

constexpr uint32_t kSharedPhysicalPage = 0x002a3df4u;
constexpr uint32_t kRtosSharedBase = 0xa3df4000u;
constexpr uint32_t kPathOffset = 0x000u;
constexpr uint32_t kModeOffset = 0x200u;
constexpr uint32_t kPayloadOffset = 0x800u;
constexpr uint32_t kPathStorageBytes = 0x200u;
constexpr uint32_t kMaximumCanonicalPathBytes = kPathStorageBytes - 3u;
constexpr uint32_t kCacheLineBytes = 128u;

constexpr uint16_t kRequestType = 0;
constexpr uint16_t kReplyType = 1;
constexpr uint16_t kOperationClose = 0;
constexpr uint16_t kOperationFileSize = 1;
constexpr uint16_t kOperationOpen = 2;
constexpr uint16_t kOperationRead = 3;

constexpr uint32_t kIscDestination = 1;
constexpr uint32_t kIscRequestChannel = 5;
constexpr uint32_t kIscReplyChannel = 6;
constexpr uint32_t kIscMessageBytes = 32;

constexpr uint32_t kIoctlSendTimeout = 0xc0185c01u;
constexpr uint32_t kIoctlReceiveWait = 0xc0185c03u;
constexpr uint32_t kIoctlReceiveCancel = 0xc0185c04u;
constexpr uint32_t kIoctlCacheCleanInvalidate = 0xc0105c22u;
constexpr uint32_t kIoctlCacheClean = 0xc0105c23u;

constexpr long kSysIoctl = 54;
constexpr long kSysStatx = 397;
constexpr int kAtCurrentWorkingDirectory = -100;
constexpr int kAtSymlinkNoFollow = 0x100;
constexpr int kAtEmptyPath = 0x1000;
constexpr uint32_t kStatxType = 0x0001u;
constexpr uint32_t kStatxMode = 0x0002u;
constexpr uint32_t kStatxInode = 0x0100u;
constexpr uint32_t kStatxSize = 0x0200u;
constexpr uint32_t kStatxWanted =
    kStatxType | kStatxMode | kStatxInode | kStatxSize;
constexpr uint16_t kFileModeTypeMask = 0170000;
constexpr uint16_t kFileModeRegular = 0100000;

constexpr uint32_t kSemaphoreSharedFutex = 0x80u;
constexpr uint32_t kFutexWake = 1u;

// libisyscomm maps kernel ENOMEM to its public -4 result; that is the value
// stock isc_file retries around ISC_send_timeout.  Direct raw syscalls see the
// underlying -ENOMEM (-12).  The driver ioctl translates its internal receive
// interruption -6 to ENXIO (-6), and cancellation -7 to EINTR (-4).
constexpr long kDriverSendRetry = -12;
constexpr long kDriverReceiveInterrupted = -6;

struct alignas(8) IscTransfer {
  uint32_t endpoint;
  uint32_t channel_or_padding;
  uint64_t data;
  uint32_t size;
  uint32_t timeout_or_padding;
};

struct alignas(8) IscCacheRange {
  uint64_t address;
  uint32_t size;
  uint32_t padding;
};

struct FileMessage {
  uint16_t type;
  uint16_t operation;
  uint32_t cookie;
  uint32_t word[6];
};

struct StatxTimestamp {
  int64_t seconds;
  uint32_t nanoseconds;
  int32_t reserved;
};

struct alignas(8) Statx {
  uint32_t mask;
  uint32_t block_size;
  uint64_t attributes;
  uint32_t link_count;
  uint32_t user_id;
  uint32_t group_id;
  uint16_t mode;
  uint16_t spare0;
  uint64_t inode;
  uint64_t size;
  uint64_t blocks;
  uint64_t attributes_mask;
  StatxTimestamp access_time;
  StatxTimestamp birth_time;
  StatxTimestamp change_time;
  StatxTimestamp modification_time;
  uint32_t rdev_major;
  uint32_t rdev_minor;
  uint32_t dev_major;
  uint32_t dev_minor;
  uint64_t spare2[14];
};

static_assert(sizeof(IscTransfer) == 24,
              "32-bit ISC transfer ioctl ABI changed");
static_assert(offsetof(IscTransfer, endpoint) == 0 &&
                  offsetof(IscTransfer, channel_or_padding) == 4 &&
                  offsetof(IscTransfer, size) == 16 &&
                  offsetof(IscTransfer, timeout_or_padding) == 20,
              "32-bit ISC transfer field offsets changed");
static_assert(offsetof(IscTransfer, data) == 8,
              "32-bit ISC pointer alignment changed");
static_assert(sizeof(IscCacheRange) == 16,
              "32-bit ISC cache ioctl ABI changed");
static_assert(offsetof(IscCacheRange, address) == 0 &&
                  offsetof(IscCacheRange, size) == 8,
              "32-bit ISC cache field offsets changed");
static_assert(sizeof(FileMessage) == kIscMessageBytes,
              "RTOS file message ABI changed");
static_assert(offsetof(FileMessage, type) == 0 &&
                  offsetof(FileMessage, operation) == 2 &&
                  offsetof(FileMessage, cookie) == 4 &&
                  offsetof(FileMessage, word) == 8,
              "RTOS file message field offsets changed");
static_assert(sizeof(StatxTimestamp) == 16, "Linux statx timestamp ABI changed");
static_assert(sizeof(Statx) == 256, "Linux statx ABI changed");
static_assert(offsetof(Statx, inode) == 0x20,
              "Linux statx inode offset changed");
static_assert(offsetof(Statx, dev_major) == 0x88,
              "Linux statx device offset changed");
static_assert(sizeof(FileIfDirectInput) == 64,
              "direct file stream state must remain a fixed 64 bytes");

inline long pointer_bits(const void *pointer) {
  return static_cast<long>(reinterpret_cast<uintptr_t>(pointer));
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

long raw_ioctl(int fd, uint32_t command, uintptr_t argument) {
  return syscall3(kSysIoctl, fd, static_cast<long>(command),
                  static_cast<long>(argument));
}

long statx(int directory_fd, const char *path, int flags, uint32_t mask,
           Statx *result) {
  return syscall5(kSysStatx, directory_fd, pointer_bits(path), flags, mask,
                  pointer_bits(result));
}

int semaphore_fd(const FileIfDirectInput *file) {
  return static_cast<int>(file->semaphore_fd_plus_one - 1u);
}

int memory_fd(const FileIfDirectInput *file) {
  return static_cast<int>(file->memory_fd_plus_one - 1u);
}

int isc_fd(const FileIfDirectInput *file) {
  return static_cast<int>(file->isc_fd_plus_one - 1u);
}

uint32_t rtos_address(uint32_t offset) {
  return kRtosSharedBase + offset;
}

void clear_bytes(void *destination, size_t size) {
  uint8_t *const bytes = static_cast<uint8_t *>(destination);
  for (size_t index = 0; index < size; ++index) bytes[index] = 0;
}

// The decoder requests 32 KiB blocks.  Copying them out of the camera-owned
// window with caller-saved NEON registers avoids making the direct path spend
// a material fraction of its time in the freestanding byte-at-a-time memcpy.
void copy_bytes(uint8_t *destination, const uint8_t *source, size_t size) {
  while (size >= 64u) {
    asm volatile(
        "pld [%[source], #256]\n"
        "vld1.8 {d0-d3}, [%[source]]!\n"
        "vld1.8 {d4-d7}, [%[source]]!\n"
        "vst1.8 {d0-d3}, [%[destination]]!\n"
        "vst1.8 {d4-d7}, [%[destination]]!\n"
        : [destination] "+r"(destination), [source] "+r"(source)
        :
        : "d0", "d1", "d2", "d3", "d4", "d5", "d6", "d7", "memory");
    size -= 64u;
  }
  while (size >= 16u) {
    asm volatile("vld1.8 {d0-d1}, [%[source]]!\n"
                 "vst1.8 {d0-d1}, [%[destination]]!\n"
                 : [destination] "+r"(destination), [source] "+r"(source)
                 :
                 : "d0", "d1", "memory");
    size -= 16u;
  }
  while (size-- != 0) *destination++ = *source++;
}

bool starts_with(const char *text, const char *prefix) {
  while (*prefix != '\0') {
    if (*text++ != *prefix++) return false;
  }
  return true;
}

bool safe_path_character(char value) {
  return (value >= 'A' && value <= 'Z') ||
         (value >= 'a' && value <= 'z') ||
         (value >= '0' && value <= '9') || value == '/' || value == '.' ||
         value == '_' || value == '-';
}

bool valid_path(const char *path, size_t *path_size, Error *error) {
  if (path == nullptr || path_size == nullptr ||
      !starts_with(path, "/DCIM/")) {
    return fail(error, "fileif path must start with /DCIM/");
  }

  size_t size = 0;
  size_t segment_begin = 1;
  for (;;) {
    const char value = path[size];
    if (value == '\0') break;
    if (size >= kMaximumCanonicalPathBytes) {
      return fail(error, "fileif path exceeds shared buffer");
    }
    if (!safe_path_character(value)) {
      return fail(error, "fileif path contains an unsafe character");
    }
    if (value == '/') {
      if (size == 0) {
        ++size;
        continue;
      }
      const size_t segment_size = size - segment_begin;
      if (segment_size == 0 ||
          (segment_size == 1 && path[segment_begin] == '.') ||
          (segment_size == 2 && path[segment_begin] == '.' &&
           path[segment_begin + 1] == '.')) {
        return fail(error, "fileif path has an unsafe segment");
      }
      segment_begin = size + 1;
    }
    ++size;
  }

  const size_t final_size = size - segment_begin;
  if (final_size == 0 ||
      (final_size == 1 && path[segment_begin] == '.') ||
      (final_size == 2 && path[segment_begin] == '.' &&
       path[segment_begin + 1] == '.')) {
    return fail(error, "fileif path has an unsafe final segment");
  }
  if (!string_ends_with_ascii_case(path, ".ORF")) {
    return fail(error, "fileif path must name an ORF file");
  }
  *path_size = size;
  return true;
}

bool statx_has_identity(const Statx &value) {
  return (value.mask & kStatxWanted) == kStatxWanted &&
         (value.mode & kFileModeTypeMask) == kFileModeRegular &&
         value.size == 16u;
}

bool capture_semaphore_identity(FileIfDirectInput *file, Error *error) {
  Statx descriptor = {};
  Statx pathname = {};
  const long descriptor_result =
      statx(semaphore_fd(file), "", kAtEmptyPath, kStatxWanted, &descriptor);
  if (os::failed(descriptor_result)) {
    return fail_u64(error, "fileif semaphore fstatx errno ",
                    static_cast<uint64_t>(os::error_number(descriptor_result)));
  }
  const long pathname_result =
      statx(kAtCurrentWorkingDirectory, kSemaphorePath, kAtSymlinkNoFollow,
            kStatxWanted, &pathname);
  if (os::failed(pathname_result)) {
    return fail_u64(error, "fileif semaphore statx errno ",
                    static_cast<uint64_t>(os::error_number(pathname_result)));
  }
  if (!statx_has_identity(descriptor) || !statx_has_identity(pathname) ||
      descriptor.inode != pathname.inode ||
      descriptor.dev_major != pathname.dev_major ||
      descriptor.dev_minor != pathname.dev_minor) {
    return fail(error, "fileif semaphore is not the live 16-byte object");
  }
  file->semaphore_inode = descriptor.inode;
  file->semaphore_device_major = descriptor.dev_major;
  file->semaphore_device_minor = descriptor.dev_minor;
  return true;
}

bool semaphore_identity_matches(const FileIfDirectInput *file,
                                Error *error) {
  Statx pathname = {};
  const long result =
      statx(kAtCurrentWorkingDirectory, kSemaphorePath, kAtSymlinkNoFollow,
            kStatxWanted, &pathname);
  if (os::failed(result)) {
    return fail_u64(error, "fileif semaphore disappeared; errno ",
                    static_cast<uint64_t>(os::error_number(result)));
  }
  if (!statx_has_identity(pathname) ||
      pathname.inode != file->semaphore_inode ||
      pathname.dev_major != file->semaphore_device_major ||
      pathname.dev_minor != file->semaphore_device_minor) {
    return fail(error, "fileif semaphore was replaced by service restart");
  }
  return true;
}

bool acquire_semaphore(FileIfDirectInput *file, Error *error) {
  if (file->semaphore_mapping[1] != kSemaphoreSharedFutex) {
    return fail(error, "fileif semaphore has an unknown glibc ABI");
  }

  uint64_t start = 0;
  if (os::failed(os::monotonic_milliseconds(&start))) {
    return fail(error, "fileif could not read monotonic clock");
  }
  const uint64_t deadline =
      start > UINT64_MAX - kFileIfSemaphoreTimeoutMilliseconds
          ? UINT64_MAX
          : start + kFileIfSemaphoreTimeoutMilliseconds;

  for (;;) {
    uint32_t observed =
        __atomic_load_n(file->semaphore_mapping, __ATOMIC_ACQUIRE);
    if ((observed >> 1) > 1u) {
      return fail(error, "fileif semaphore count is corrupt");
    }
    if ((observed >> 1) != 0) {
      const uint32_t desired = observed - 2u;
      if (__atomic_compare_exchange_n(file->semaphore_mapping, &observed,
                                      desired, true, __ATOMIC_ACQUIRE,
                                      __ATOMIC_RELAXED)) {
        file->owns_semaphore = 1;
        return true;
      }
      continue;
    }

    uint64_t now = 0;
    if (os::failed(os::monotonic_milliseconds(&now))) {
      return fail(error, "fileif could not update semaphore deadline");
    }
    if (now >= deadline) {
      return fail(error, "fileif semaphore acquisition timed out");
    }
    (void)os::nanosleep_ms(1);
    if (file->semaphore_mapping[1] != kSemaphoreSharedFutex) {
      return fail(error, "fileif semaphore ABI changed while waiting");
    }
  }
}

void release_semaphore(FileIfDirectInput *file) {
  if (file->owns_semaphore == 0 || file->semaphore_mapping == nullptr) return;
  const uint32_t previous =
      __atomic_fetch_add(file->semaphore_mapping, 2u, __ATOMIC_RELEASE);
  file->owns_semaphore = 0;
  if ((previous & 1u) != 0) {
    (void)os::futex(file->semaphore_mapping, static_cast<int>(kFutexWake), 1,
                    nullptr);
  }
}

bool cache_range(FileIfDirectInput *file, void *address, uint32_t size,
                 uint32_t command, Error *error) {
  if (size == 0) return true;
  const uintptr_t begin = reinterpret_cast<uintptr_t>(address);
  const uintptr_t aligned_begin = begin & ~(kCacheLineBytes - 1u);
  const uintptr_t end = begin + size;
  const uintptr_t aligned_end =
      (end + kCacheLineBytes - 1u) & ~(kCacheLineBytes - 1u);
  if (aligned_end < end || aligned_end - aligned_begin > UINT32_MAX) {
    return fail(error, "fileif cache range overflow");
  }
  const IscCacheRange range = {
      static_cast<uint64_t>(aligned_begin),
      static_cast<uint32_t>(aligned_end - aligned_begin),
      0,
  };
  const long result =
      raw_ioctl(isc_fd(file), command, reinterpret_cast<uintptr_t>(&range));
  if (result != 0) {
    return fail_u64(error, "fileif cache ioctl failed; errno ",
                    static_cast<uint64_t>(os::error_number(result)));
  }
  return true;
}

bool cache_clean(FileIfDirectInput *file, void *address, uint32_t size,
                 Error *error) {
  return cache_range(file, address, size, kIoctlCacheClean, error);
}

bool cache_clean_invalidate(FileIfDirectInput *file, void *address,
                            uint32_t size, Error *error) {
  return cache_range(file, address, size, kIoctlCacheCleanInvalidate, error);
}

void cancel_reply_channel(FileIfDirectInput *file) {
  if (file->isc_fd_plus_one == 0) return;
  (void)raw_ioctl(isc_fd(file), kIoctlReceiveCancel, kIscReplyChannel);
}

bool exchange(FileIfDirectInput *file, const FileMessage &request,
              FileMessage *reply, Error *error) {
  if (!semaphore_identity_matches(file, error)) {
    file->transport_poisoned = 1;
    return false;
  }

  // Stock isc_file puts the Linux address of its outstanding request at +4.
  // The RTOS treats it as an opaque correlation cookie and echoes it.  Use a
  // private wire copy so callers cannot accidentally retain a stack address.
  FileMessage wire_request = request;
  wire_request.cookie =
      static_cast<uint32_t>(reinterpret_cast<uintptr_t>(&wire_request));
  const IscTransfer send = {
      kIscDestination,
      kIscRequestChannel,
      static_cast<uint64_t>(reinterpret_cast<uintptr_t>(&wire_request)),
      kIscMessageBytes,
      0,
  };
  long send_result = 0;
  do {
    send_result = raw_ioctl(isc_fd(file), kIoctlSendTimeout,
                            reinterpret_cast<uintptr_t>(&send));
    if (send_result == kDriverSendRetry) (void)os::nanosleep_ms(1);
  } while (send_result == kDriverSendRetry);
  if (send_result != static_cast<long>(kIscMessageBytes)) {
    if (os::failed(send_result)) {
      return fail_u64(error, "fileif send ioctl failed; errno ",
                      static_cast<uint64_t>(os::error_number(send_result)));
    }
    return fail_u64(error, "fileif send length was ",
                    static_cast<uint64_t>(send_result));
  }

  clear_bytes(reply, sizeof(*reply));
  const IscTransfer receive = {
      kIscReplyChannel,
      0,
      static_cast<uint64_t>(reinterpret_cast<uintptr_t>(reply)),
      kIscMessageBytes,
      0,
  };
  long receive_result = 0;
  for (;;) {
    receive_result = raw_ioctl(isc_fd(file), kIoctlReceiveWait,
                               reinterpret_cast<uintptr_t>(&receive));
    if (receive_result != -os::kErrorInterrupted &&
        receive_result != kDriverReceiveInterrupted) {
      break;
    }
  }
  if (receive_result != static_cast<long>(kIscMessageBytes)) {
    file->transport_poisoned = 1;
    file->reply_cancel_needed = 1;
    if (os::failed(receive_result)) {
      return fail_u64(error, "fileif receive ioctl failed; errno ",
                      static_cast<uint64_t>(os::error_number(receive_result)));
    }
    return fail_u64(error, "fileif reply length was ",
                    static_cast<uint64_t>(receive_result));
  }
  if (reply->type != kReplyType ||
      reply->operation != wire_request.operation ||
      reply->cookie != wire_request.cookie) {
    file->transport_poisoned = 1;
    file->reply_cancel_needed = 1;
    return fail(error, "fileif reply type/operation/cookie mismatch");
  }
  return true;
}

bool close_remote(FileIfDirectInput *file, Error *error) {
  if (file->remote_is_open == 0) return true;
  if (file->transport_poisoned != 0) {
    file->remote_is_open = 0;
    return fail(error, "fileif transport poisoned before remote close");
  }
  FileMessage request = {};
  request.type = kRequestType;
  request.operation = kOperationClose;
  request.word[0] = file->remote_handle;
  request.word[1] = UINT32_MAX;
  FileMessage reply = {};
  const bool exchanged = exchange(file, request, &reply, error);
  file->remote_is_open = 0;
  file->remote_handle = 0;
  if (!exchanged) return false;
  if (static_cast<int32_t>(reply.word[1]) != 0) {
    return fail_u64(error, "fileif fclose status ", reply.word[1]);
  }
  return true;
}

void release_local_resources(FileIfDirectInput *file) {
  if (file->reply_cancel_needed != 0) {
    cancel_reply_channel(file);
    file->reply_cancel_needed = 0;
  }

  if (file->shared_mapping != nullptr) {
    (void)os::munmap(file->shared_mapping, kFileIfSharedBytes);
    file->shared_mapping = nullptr;
  }
  if (file->memory_fd_plus_one != 0) {
    (void)os::close(memory_fd(file));
    file->memory_fd_plus_one = 0;
  }
  if (file->isc_fd_plus_one != 0) {
    (void)os::close(isc_fd(file));
    file->isc_fd_plus_one = 0;
  }

  release_semaphore(file);
  if (file->semaphore_mapping != nullptr) {
    (void)os::munmap(file->semaphore_mapping, 16);
    file->semaphore_mapping = nullptr;
  }
  if (file->semaphore_fd_plus_one != 0) {
    (void)os::close(semaphore_fd(file));
    file->semaphore_fd_plus_one = 0;
  }
}

bool finish_stream(FileIfDirectInput *file, Error *error) {
  const bool closed = close_remote(file, error);
  release_local_resources(file);
  return closed;
}

bool read_file_size(FileIfDirectInput *file, Error *error) {
  FileMessage request = {};
  request.type = kRequestType;
  request.operation = kOperationFileSize;
  request.word[0] = rtos_address(kPathOffset);
  // Stock's wrapper initializes these scalar result slots to zero, then
  // copies reply word 1 (attributes) and word 2 (size) into its local +0x400
  // and +0x600 scratch words.  They are inline results, not RTOS pointers.
  request.word[1] = 0;
  request.word[2] = 0;
  request.word[3] = UINT32_MAX;
  FileMessage reply = {};
  if (!exchange(file, request, &reply, error)) return false;
  if (static_cast<int32_t>(reply.word[3]) != 0) {
    return fail_u64(error, "fileif filesize status ", reply.word[3]);
  }
  const uint32_t size = reply.word[2];
  if (size < 8u || size > kFileIfMaximumFileBytes || size == UINT32_MAX) {
    return fail_u64(error, "fileif rejected file size ", size);
  }
  file->total_size = size;
  return true;
}

bool open_remote(FileIfDirectInput *file, Error *error) {
  FileMessage request = {};
  request.type = kRequestType;
  request.operation = kOperationOpen;
  request.word[0] = rtos_address(kPathOffset);
  request.word[1] = rtos_address(kModeOffset);
  request.word[2] = 0;
  FileMessage reply = {};
  if (!exchange(file, request, &reply, error)) return false;
  if (reply.word[2] == 0) return fail(error, "fileif fopen returned null");
  file->remote_handle = reply.word[2];
  file->remote_is_open = 1;
  return true;
}

bool fill_buffer(FileIfDirectInput *file, Error *error) {
  if (file->buffered_begin != file->buffered_end) {
    return fail(error, "fileif attempted to overwrite buffered bytes");
  }
  if (file->bytes_delivered >= file->total_size) {
    return fail(error, "fileif attempted to read after declared EOF");
  }
  uint32_t wanted = file->total_size - file->bytes_delivered;
  if (wanted > kFileIfPayloadBytes) wanted = kFileIfPayloadBytes;
  uint8_t *const payload = file->shared_mapping + kPayloadOffset;
  if (!cache_clean_invalidate(file, payload, wanted, error)) return false;

  FileMessage request = {};
  request.type = kRequestType;
  request.operation = kOperationRead;
  request.word[0] = rtos_address(kPayloadOffset);
  request.word[1] = 1;
  request.word[2] = wanted;
  request.word[3] = file->remote_handle;
  request.word[4] = 0;
  FileMessage reply = {};
  if (!exchange(file, request, &reply, error)) return false;
  if (reply.word[4] != wanted) {
    return fail_u64(error, "fileif short fread returned ", reply.word[4]);
  }
  if (!cache_clean_invalidate(file, payload, wanted, error)) return false;
  file->buffered_begin = 0;
  file->buffered_end = wanted;
  return true;
}

long fileif_read(void *context, void *destination, size_t size, Error *error) {
  FileIfDirectInput *const file =
      static_cast<FileIfDirectInput *>(context);
  if (file == nullptr || destination == nullptr) {
    if (size == 0) return 0;
    if (file != nullptr) close_fileif_direct_input(file);
    fail(error, "invalid fileif read arguments");
    return -1;
  }
  if (size == 0 || file->bytes_delivered == file->total_size) return 0;
  if (file->remote_is_open == 0 || file->shared_mapping == nullptr ||
      file->owns_semaphore == 0) {
    fail(error, "fileif input is closed before EOF");
    close_fileif_direct_input(file);
    return -1;
  }

  const uint32_t remaining = file->total_size - file->bytes_delivered;
  if (size > remaining) size = remaining;
  uint8_t *const output = static_cast<uint8_t *>(destination);
  size_t copied = 0;
  while (copied != size) {
    if (file->buffered_begin == file->buffered_end &&
        !fill_buffer(file, error)) {
      close_fileif_direct_input(file);
      return -1;
    }
    uint32_t available = file->buffered_end - file->buffered_begin;
    const size_t needed = size - copied;
    if (available > needed) available = static_cast<uint32_t>(needed);
    const uint8_t *const source =
        file->shared_mapping + kPayloadOffset + file->buffered_begin;
    copy_bytes(output + copied, source, available);
    file->buffered_begin += available;
    file->bytes_delivered += available;
    copied += available;
  }

  if (file->bytes_delivered == file->total_size &&
      !finish_stream(file, error)) {
    return -1;
  }
  return static_cast<long>(copied);
}

}  // namespace

void close_fileif_direct_input(FileIfDirectInput *file) {
  if (file == nullptr) return;
  (void)close_remote(file, nullptr);
  release_local_resources(file);
  file->remote_handle = 0;
  file->remote_is_open = 0;
  file->transport_poisoned = 0;
  file->buffered_begin = 0;
  file->buffered_end = 0;
}

bool open_fileif_direct_input(FileIfDirectInput *file, uint8_t slot_index,
                              const char *path, InputStream *input,
                              Error *error) {
  if (file == nullptr || input == nullptr) {
    return fail(error, "invalid fileif input arguments");
  }
  clear_error(error);
  close_fileif_direct_input(file);
  *file = {};
  input->context = nullptr;
  input->read = nullptr;
  input->total_size = 0;

  if (slot_index > 1) return fail(error, "fileif slot must be zero or one");
  size_t path_size = 0;
  if (!valid_path(path, &path_size, error)) return false;

  const long semaphore_result =
      os::open(kSemaphorePath,
               os::kOpenReadWrite | os::kOpenNoFollow |
                   os::kOpenCloseOnExec);
  if (os::failed(semaphore_result)) {
    return fail_u64(error, "fileif could not open existing semaphore; errno ",
                    static_cast<uint64_t>(os::error_number(semaphore_result)));
  }
  file->semaphore_fd_plus_one =
      static_cast<uint32_t>(semaphore_result) + 1u;
  if (!capture_semaphore_identity(file, error)) {
    close_fileif_direct_input(file);
    return false;
  }

  const long semaphore_mapping =
      os::mmap2(nullptr, 16, os::kProtectionRead | os::kProtectionWrite,
                os::kMapShared, semaphore_fd(file), 0);
  if (os::failed(semaphore_mapping)) {
    close_fileif_direct_input(file);
    return fail_u64(error, "fileif semaphore mmap errno ",
                    static_cast<uint64_t>(os::error_number(semaphore_mapping)));
  }
  file->semaphore_mapping = reinterpret_cast<uint32_t *>(semaphore_mapping);
  if (!acquire_semaphore(file, error) ||
      !semaphore_identity_matches(file, error)) {
    close_fileif_direct_input(file);
    return false;
  }

  const long isc_result =
      os::open(kIscPath, os::kOpenReadWrite | os::kOpenCloseOnExec);
  if (os::failed(isc_result)) {
    close_fileif_direct_input(file);
    return fail_u64(error, "fileif /dev/isc open errno ",
                    static_cast<uint64_t>(os::error_number(isc_result)));
  }
  file->isc_fd_plus_one = static_cast<uint32_t>(isc_result) + 1u;

  const long memory_result =
      os::open(kMemoryPath,
               os::kOpenReadWrite | os::kOpenSync | os::kOpenCloseOnExec);
  if (os::failed(memory_result)) {
    close_fileif_direct_input(file);
    return fail_u64(error, "fileif /dev/mem open errno ",
                    static_cast<uint64_t>(os::error_number(memory_result)));
  }
  file->memory_fd_plus_one = static_cast<uint32_t>(memory_result) + 1u;

  const long shared_mapping =
      os::mmap2(nullptr, kFileIfSharedBytes,
                os::kProtectionRead | os::kProtectionWrite, os::kMapShared,
                memory_fd(file), kSharedPhysicalPage);
  if (os::failed(shared_mapping)) {
    close_fileif_direct_input(file);
    return fail_u64(error, "fileif shared mmap errno ",
                    static_cast<uint64_t>(os::error_number(shared_mapping)));
  }
  file->shared_mapping = reinterpret_cast<uint8_t *>(shared_mapping);

  clear_bytes(file->shared_mapping, kPayloadOffset);
  char *const remote_path =
      reinterpret_cast<char *>(file->shared_mapping + kPathOffset);
  remote_path[0] = static_cast<char>('C' + slot_index);
  remote_path[1] = ':';
  for (size_t index = 0; index < path_size; ++index) {
    remote_path[index + 2] = path[index] == '/' ? '\\' : path[index];
  }
  remote_path[path_size + 2] = '\0';
  char *const mode =
      reinterpret_cast<char *>(file->shared_mapping + kModeOffset);
  mode[0] = 'r';
  mode[1] = 'b';
  mode[2] = '\0';
  if (!cache_clean(file, remote_path,
                   static_cast<uint32_t>(path_size + 3u), error) ||
      !cache_clean(file, mode, 3, error) || !read_file_size(file, error) ||
      !open_remote(file, error) || !fill_buffer(file, error)) {
    close_fileif_direct_input(file);
    return false;
  }

  const uint8_t *const header = file->shared_mapping + kPayloadOffset;
  if (header[0] != 'I' || header[1] != 'I' || header[2] != 'R' ||
      header[3] != 'O' || header[4] != 8 || header[5] != 0 ||
      header[6] != 0 || header[7] != 0) {
    fail(error, "fileif payload is not an ordinary little-endian ORF");
    close_fileif_direct_input(file);
    return false;
  }

  input->context = file;
  input->read = fileif_read;
  input->total_size = file->total_size;
  return true;
}

}  // namespace om1_stars
