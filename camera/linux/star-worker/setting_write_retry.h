#ifndef OM1_STARS_SETTING_WRITE_RETRY_H
#define OM1_STARS_SETTING_WRITE_RETRY_H

#include <stddef.h>
#include <stdint.h>

namespace om1_stars {

enum class SettingWriteSnapshotClass : uint8_t {
  kPrewrite,
  kApplied,
  kTransitional,
  kUnexpected,
};

enum class SettingWriteDecision : uint8_t {
  kObserve,
  kRetry,
  kSuccess,
  kFail,
};

enum class B01TerminalResponseClass : uint8_t {
  kUnresponsive,
  kOther,
};

enum class UnresponsiveRetryDecision : uint8_t {
  kWait,
  kRetry,
  kSuccess,
  kStateChanged,
  kDeadline,
};

inline bool setting_write_line_equals(const char *data, size_t begin,
                                      size_t end, const char *literal) {
  if (data == nullptr || literal == nullptr || begin > end) return false;
  while (begin < end && (data[begin] == ' ' || data[begin] == '\t')) ++begin;
  while (end > begin &&
         (data[end - 1] == ' ' || data[end - 1] == '\t')) {
    --end;
  }
  size_t index = 0;
  while (begin + index < end && literal[index] != '\0') {
    if (data[begin + index] != literal[index]) return false;
    ++index;
  }
  return begin + index == end && literal[index] == '\0';
}

// Only the exact, prompt-complete firmware preflight rejection is eligible for
// the short retry path.  A label without the signed 0xf0000058 result, a
// different API error, or a transcript containing API_SUCCESS remains on the
// ordinary ambiguity policy.
inline B01TerminalResponseClass classify_b01_terminal_response(
    const char *data, size_t size) {
  if (data == nullptr) return B01TerminalResponseClass::kOther;
  bool result_seen = false;
  bool unresponsive_seen = false;
  bool other_api_seen = false;
  size_t begin = 0;
  while (begin < size) {
    size_t end = begin;
    while (end < size && data[end] != '\n' && data[end] != '\r') ++end;
    if (setting_write_line_equals(
            data, begin, end,
            "SettingController::setSetting, -268435368")) {
      if (result_seen) return B01TerminalResponseClass::kOther;
      result_seen = true;
    } else if (setting_write_line_equals(data, begin, end,
                                         "API_ERROR_UNRESPONSIVE")) {
      if (unresponsive_seen) return B01TerminalResponseClass::kOther;
      unresponsive_seen = true;
    } else {
      size_t trimmed = begin;
      while (trimmed < end &&
             (data[trimmed] == ' ' || data[trimmed] == '\t')) {
        ++trimmed;
      }
      if (end - trimmed >= 4 && data[trimmed] == 'A' &&
          data[trimmed + 1] == 'P' && data[trimmed + 2] == 'I' &&
          data[trimmed + 3] == '_') {
        other_api_seen = true;
      }
    }
    while (end < size && (data[end] == '\n' || data[end] == '\r')) ++end;
    begin = end;
  }
  return result_seen && unresponsive_seen && !other_api_seen
             ? B01TerminalResponseClass::kUnresponsive
             : B01TerminalResponseClass::kOther;
}

inline UnresponsiveRetryDecision decide_unresponsive_setting_write_retry(
    SettingWriteSnapshotClass snapshot, uint64_t now,
    uint64_t next_retry_at, uint64_t deadline) {
  if (snapshot == SettingWriteSnapshotClass::kApplied) {
    return UnresponsiveRetryDecision::kSuccess;
  }
  if (snapshot == SettingWriteSnapshotClass::kTransitional ||
      snapshot == SettingWriteSnapshotClass::kUnexpected) {
    return UnresponsiveRetryDecision::kStateChanged;
  }
  if (now >= deadline) return UnresponsiveRetryDecision::kDeadline;
  if (now >= next_retry_at) return UnresponsiveRetryDecision::kRetry;
  return UnresponsiveRetryDecision::kWait;
}

// Classify one strict active/selected exposure snapshot against the complete
// tuple immediately before and after an absolute SettingController write.
// Owned partial convergence is transitional: it may settle to the applied
// tuple, but it can never authorize a replay. Values outside the two complete
// endpoint tuples are unexpected.
inline SettingWriteSnapshotClass classify_setting_write_snapshot(
    const int32_t active[3], const int32_t selected[3],
    const int32_t prewrite[3], const int32_t applied[3]) {
  if (active == nullptr || selected == nullptr || prewrite == nullptr ||
      applied == nullptr) {
    return SettingWriteSnapshotClass::kUnexpected;
  }
  bool is_prewrite = true;
  bool is_applied = true;
  bool is_owned = true;
  for (size_t index = 0; index < 3; ++index) {
    is_prewrite &= active[index] == prewrite[index] &&
                   selected[index] == prewrite[index];
    is_applied &= active[index] == applied[index] &&
                  selected[index] == applied[index];
    is_owned &= (active[index] == prewrite[index] ||
                 active[index] == applied[index]) &&
                (selected[index] == prewrite[index] ||
                 selected[index] == applied[index]);
  }
  if (is_applied) return SettingWriteSnapshotClass::kApplied;
  if (is_prewrite) return SettingWriteSnapshotClass::kPrewrite;
  if (is_owned) return SettingWriteSnapshotClass::kTransitional;
  return SettingWriteSnapshotClass::kUnexpected;
}

inline SettingWriteDecision decide_setting_write_retry(
    SettingWriteSnapshotClass snapshot, bool api_success, unsigned attempt,
    unsigned maximum_attempts, bool observation_deadline_expired) {
  if (snapshot == SettingWriteSnapshotClass::kApplied) {
    return SettingWriteDecision::kSuccess;
  }
  if (snapshot == SettingWriteSnapshotClass::kUnexpected || attempt == 0 ||
      maximum_attempts == 0 || attempt > maximum_attempts) {
    return SettingWriteDecision::kFail;
  }
  if (!observation_deadline_expired) {
    return SettingWriteDecision::kObserve;
  }
  if (snapshot == SettingWriteSnapshotClass::kTransitional) {
    return SettingWriteDecision::kFail;
  }
  if (!api_success && attempt < maximum_attempts) {
    return SettingWriteDecision::kRetry;
  }
  return SettingWriteDecision::kFail;
}

}  // namespace om1_stars

#endif
