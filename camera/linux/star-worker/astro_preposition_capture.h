#ifndef OM1_ASTRO_PREPOSITION_CAPTURE_H
#define OM1_ASTRO_PREPOSITION_CAPTURE_H

#include "astro_preposition_mailbox.h"

namespace om1_stars::detail {

// The companion reports MAIN mailbox consumption through CONSUMED. The fixed
// allowance is an experimental capture delay, never physical position feedback.
class AstroPrepositionCaptureWait {
 public:
  enum class Result { kWaiting, kReady, kCancelled, kTimedOut };

  explicit AstroPrepositionCaptureWait(uint64_t now)
      : started_(now), consumed_at_(0), consumed_(false) {}

  Result observe(uint64_t now, uint8_t ready) {
    if ((ready != ASTRO_PREPOSITION_REQUESTED &&
         ready != ASTRO_PREPOSITION_PUBLISHED &&
         ready != ASTRO_PREPOSITION_CONSUMED) ||
        (consumed_ && ready != ASTRO_PREPOSITION_CONSUMED)) {
      return Result::kCancelled;
    }
    if (!consumed_) {
      if (ready == ASTRO_PREPOSITION_CONSUMED) {
        consumed_ = true;
        consumed_at_ = now;
      } else if (now - started_ >= ASTRO_PREPOSITION_CONSUME_TIMEOUT_MS) {
        return Result::kTimedOut;
      }
    }
    return consumed_ && now - consumed_at_ >= ASTRO_PREPOSITION_SETTLE_MS
               ? Result::kReady
               : Result::kWaiting;
  }

 private:
  uint64_t started_;
  uint64_t consumed_at_;
  bool consumed_;
};

}  // namespace om1_stars::detail

#endif
