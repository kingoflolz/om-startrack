#include "thread_pipeline.h"

#include "syscalls.h"
#include "util.h"

#include <stddef.h>
#include <stdint.h>

namespace om1_stars {
namespace {

constexpr uint32_t kCloneVm = 0x00000100u;
constexpr uint32_t kCloneFs = 0x00000200u;
constexpr uint32_t kCloneFiles = 0x00000400u;
constexpr uint32_t kCloneSighand = 0x00000800u;
constexpr uint32_t kCloneThread = 0x00010000u;
constexpr uint32_t kCloneSysvsem = 0x00040000u;
constexpr uint32_t kCloneChildCleartid = 0x00200000u;
constexpr uint32_t kThreadCloneFlags =
    kCloneVm | kCloneFs | kCloneFiles | kCloneSighand | kCloneThread |
    kCloneSysvsem | kCloneChildCleartid;

constexpr int kFutexWait = 0;
constexpr int kFutexWaitPrivate = 128;
constexpr int kFutexWakePrivate = 129;
constexpr uint32_t kWakeOne = 1u;
constexpr size_t kMinimumStackBytes = 1024u;
constexpr int kErrorTimedOut = 110;

// A relative timeout bounds recovery when a wake syscall itself fails.  A
// pipeline stage always rechecks its queue and terminal flags after this wait,
// so timeout is a benign periodic wake rather than a processing deadline.
struct KernelTimespec32 {
  int32_t seconds;
  int32_t nanoseconds;
};

constexpr KernelTimespec32 kQueueWaitTimeout = {1, 0};
static_assert(sizeof(KernelTimespec32) == 8,
              "AArch32 futex timespec ABI changed");

extern "C" long om1_clone_fixed_thread(FixedThreadEntry entry,
                                        void *argument, void *stack_top,
                                        uint32_t flags,
                                        uint32_t *clear_tid);

bool benign_wait_result(long result) {
  return result == 0 || result == -os::kErrorAgain ||
         result == -os::kErrorInterrupted || result == -kErrorTimedOut;
}

}  // namespace

bool start_fixed_thread(FixedThread *thread, FixedThreadEntry entry,
                        void *argument, void *stack, size_t stack_size,
                        Error *error) {
  if (thread == nullptr || entry == nullptr || stack == nullptr)
    return fail(error, "invalid fixed-thread start arguments");
  if (__atomic_load_n(&thread->clear_tid, __ATOMIC_ACQUIRE) != 0u)
    return fail(error, "fixed thread is already running");
  if (stack_size < kMinimumStackBytes)
    return fail(error, "fixed thread stack is too small");

  const uintptr_t stack_begin = reinterpret_cast<uintptr_t>(stack);
  if (stack_size > UINTPTR_MAX - stack_begin)
    return fail(error, "fixed thread stack range overflows");
  const uintptr_t stack_top = (stack_begin + stack_size) & ~uintptr_t{15u};
  if (stack_top < stack_begin + 8u)
    return fail(error, "fixed thread stack has no trampoline space");

  // The kernel clears this exact word and performs a non-private futex wake
  // after thread exit.  Initialize it before clone so even a child that exits
  // immediately cannot race join into treating the handle as idle.
  __atomic_store_n(&thread->clear_tid, 1u, __ATOMIC_RELEASE);
  const long result = om1_clone_fixed_thread(
      entry, argument, reinterpret_cast<void *>(stack_top),
      kThreadCloneFlags, &thread->clear_tid);
  if (os::failed(result)) {
    __atomic_store_n(&thread->clear_tid, 0u, __ATOMIC_RELEASE);
    return fail_u64(error, "clone failed with errno ",
                    static_cast<uint64_t>(os::error_number(result)));
  }
  clear_error(error);
  return true;
}

bool join_fixed_thread(FixedThread *thread, Error *error) {
  if (thread == nullptr) return fail(error, "invalid fixed-thread handle");
  for (;;) {
    const uint32_t observed =
        __atomic_load_n(&thread->clear_tid, __ATOMIC_ACQUIRE);
    if (observed == 0u) {
      clear_error(error);
      return true;
    }
    const long result =
        os::futex(&thread->clear_tid, kFutexWait, observed, nullptr);
    if (benign_wait_result(result)) continue;
    // Returning would let the caller reuse shared state and stack storage
    // while the CLONE_VM worker may still be alive.  An unexpected error on
    // this valid, aligned static word is therefore fail-stop; EAGAIN/EINTR
    // were handled above and the kernel-clear wake has no timeout.
    (void)fail_u64(error, "thread join futex failed with errno ",
                   static_cast<uint64_t>(os::error_number(result)));
    os::exit_group(70);
  }
}

bool wait_private_word(uint32_t *word, uint32_t expected, Error *error) {
  if (word == nullptr) return fail(error, "invalid private futex word");
  const long result = os::futex(word, kFutexWaitPrivate, expected,
                                &kQueueWaitTimeout);
  if (benign_wait_result(result)) return true;
  return fail_u64(error, "private futex wait failed with errno ",
                  static_cast<uint64_t>(os::error_number(result)));
}

bool wake_private_word(uint32_t *word, Error *error) {
  if (word == nullptr) return fail(error, "invalid private futex word");
  const long result =
      os::futex(word, kFutexWakePrivate, kWakeOne, nullptr);
  if (!os::failed(result)) return true;
  return fail_u64(error, "private futex wake failed with errno ",
                  static_cast<uint64_t>(os::error_number(result)));
}

}  // namespace om1_stars
