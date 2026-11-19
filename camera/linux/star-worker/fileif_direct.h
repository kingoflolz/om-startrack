#ifndef OM1_STARS_FILEIF_DIRECT_H
#define OM1_STARS_FILEIF_DIRECT_H

#include "orf.h"

#include <stddef.h>
#include <stdint.h>

namespace om1_stars {

// Reverse-engineered stock isc_file limits and layout.  The 512 KiB payload
// window is camera-owned shared memory; this object itself contains no image
// storage and needs no heap allocation.
constexpr uint32_t kFileIfSharedBytes = 0x80800u;
constexpr uint32_t kFileIfPayloadBytes = 0x80000u;
constexpr uint32_t kFileIfMaximumFileBytes = 128u * 1024u * 1024u;
constexpr uint32_t kFileIfSemaphoreTimeoutMilliseconds = 10u * 1000u;

// Fixed state for one direct RTOS file-proxy stream.  Initialize it with {}
// before its first use; the all-zero value is the closed state.  Keep it alive
// until EOF or close_fileif_direct_input.
//
// The descriptors use fd + 1 so descriptor zero remains representable while
// preserving the all-zero closed state.  The semaphore identity fields detect
// unlink/recreate of /sem_fileif between RPCs.  A POSIX named semaphore has no
// owner-PID field, so a process restart cannot be locked out atomically; callers
// must not deliberately restart isc_file while a direct stream is active.  It
// is a non-robust semaphore: killing this process while it owns the semaphore
// requires a service/camera restart rather than manual unlink/reinitialization.
struct FileIfDirectInput {
  uint8_t *shared_mapping;
  uint32_t *semaphore_mapping;
  uint32_t semaphore_fd_plus_one;
  uint32_t memory_fd_plus_one;
  uint32_t isc_fd_plus_one;
  uint32_t remote_handle;
  uint32_t total_size;
  uint32_t bytes_delivered;
  uint32_t buffered_begin;
  uint32_t buffered_end;
  uint64_t semaphore_inode;
  uint32_t semaphore_device_major;
  uint32_t semaphore_device_minor;
  uint8_t owns_semaphore;
  uint8_t remote_is_open;
  uint8_t transport_poisoned;
  uint8_t reply_cancel_needed;
};

// Open a read-only RTOS file handle for a canonical media path such as
// /DCIM/108OMSYS/P8153896.ORF.  slot_index 0 and 1 select the stock C: and D:
// volume names respectively.  The existing /sem_fileif is opened without
// O_CREAT and is held across filesize, fopen, every fread, and fclose.
//
// This maps /dev/mem read/write because the protocol necessarily writes the
// pathname/mode into shared memory and the RTOS writes sizes and file bytes
// back into the same mapping.  The remote file mode is always exactly "rb".
bool open_fileif_direct_input(FileIfDirectInput *file, uint8_t slot_index,
                              const char *path, InputStream *input,
                              Error *error);

// Close an unfinished remote read and release the stock semaphore.  Calling
// this on an all-zero, completed, or already-closed object is harmless.
void close_fileif_direct_input(FileIfDirectInput *file);

}  // namespace om1_stars

#endif
