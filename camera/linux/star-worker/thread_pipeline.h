#ifndef OM1_STARS_THREAD_PIPELINE_H
#define OM1_STARS_THREAD_PIPELINE_H

#include "types.h"

#include <stddef.h>
#include <stdint.h>

namespace om1_stars {

using FixedThreadEntry = void (*)(void *argument);

// One heap-free Linux thread.  The caller owns both this handle and the
// fixed stack supplied to start_fixed_thread(); neither may be reused until
// join_fixed_thread() succeeds.  An all-zero handle is an idle state.
struct alignas(4) FixedThread {
  // CLONE_CHILD_CLEARTID makes the kernel clear and wake this word only after
  // the child has actually exited, so it is also the join primitive.
  uint32_t clear_tid;
};

// Start entry(argument) on caller-owned stack storage.  The raw clone uses a
// shared address space and file table but no TLS or C/C++ runtime facilities.
// The entry function must return normally; the assembly trampoline then uses
// exit(2), which terminates only the worker rather than the whole thread
// group.  stack_size includes the eight bytes reserved by the trampoline.
bool start_fixed_thread(FixedThread *thread, FixedThreadEntry entry,
                        void *argument, void *stack, size_t stack_size,
                        Error *error);

// Wait until CLONE_CHILD_CLEARTID reports actual worker exit.  This is safe
// to call on an all-zero or already-joined handle.
bool join_fixed_thread(FixedThread *thread, Error *error);

// Futex helpers for fixed SPSC queues.  Wait on a monotonic event word, not
// directly on a data/space index when a separate done, cancel, or failure flag
// can also satisfy the wait: snapshot event, recheck every predicate, then
// wait on that snapshot.  Every relevant transition must release-increment
// the same event before waking it.  This prevents a terminal wake from landing
// between predicate recheck and FUTEX_WAIT while the waited value is unchanged.
// EAGAIN, EINTR, and the internal one-second timeout are successful wakeups
// because the caller always rechecks; the timeout bounds recovery even if a
// FUTEX_WAKE syscall itself fails.  It is not a pipeline progress deadline.
// These helpers allocate no userspace storage.
bool wait_private_word(uint32_t *word, uint32_t expected, Error *error);
bool wake_private_word(uint32_t *word, Error *error);

}  // namespace om1_stars

#endif
