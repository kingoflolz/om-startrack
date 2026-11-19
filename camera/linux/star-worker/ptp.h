#ifndef OM1_STARS_PTP_H
#define OM1_STARS_PTP_H

#include "types.h"

#include <stddef.h>
#include <stdint.h>

namespace om1_stars {

constexpr uint16_t kPtpResponseOk = 0x2001;
constexpr uint16_t kPtpResponseSessionAlreadyOpen = 0x201e;

struct InputStream;
class PtpIpClient;

// Caller-owned fixed state for one camera-to-initiator data lease.  Call
// initialize() before first use and keep the object alive until its associated
// InputStream reaches EOF or cancel_input() returns.  It contains no payload
// buffer: 94DE bytes pass directly from the PTP/IP socket to the consumer.
class PtpInputState {
 public:
  void initialize();
  bool active() const { return active_; }
  bool complete() const { return complete_; }

 private:
  friend class PtpIpClient;

  PtpIpClient *owner_;
  uint32_t transaction_id_;
  uint32_t packet_remaining_;
  uint64_t declared_size_;
  uint64_t received_size_;
  uint64_t deadline_;
  uint32_t io_timeout_ms_;
  bool active_;
  bool complete_;
  bool saw_end_;
  bool failed_;
};

struct PtpOperationResult {
  uint16_t response_code;
  uint32_t transaction_id;
  uint32_t parameters[5];
  uint32_t parameter_count;
  size_t data_size;
  uint64_t declared_data_size;
};

// A single fixed-storage PTP/IP command/event pair.  initialize() must be
// called before every first use.  No method uses a heap or invokes libc;
// connect_loopback() opens its two fixed kernel socket resources at startup.
class PtpIpClient {
 public:
  void initialize();

  // Connect to the camera's own stock PTP/IP listener.  Only loopback is
  // supported deliberately: this helper is intended to execute on-camera.
  bool connect_loopback(const uint8_t guid_tail[6], uint32_t timeout_ms,
                        Error *error);

  // data_phase is the OM-1 wire value: 1 for no/camera-to-initiator data and
  // 2 for initiator-to-camera data.  For phase 2, StartData and EndData are
  // emitted even when outgoing_size is zero.  Incoming data is fully drained;
  // exceeding the caller's fixed buffer is reported as an error afterwards.
  bool operation(uint32_t data_phase, uint16_t opcode,
                 const uint32_t *parameters, uint32_t parameter_count,
                 const uint8_t *outgoing, size_t outgoing_size,
                 uint8_t *incoming, size_t incoming_capacity,
                 uint32_t timeout_ms, PtpOperationResult *result,
                 Error *error);

  // Begin one 94DE transfer without buffering its body.  expected_size must
  // be the exact 94DD size and fit InputStream::total_size.  While the lease
  // is active no other command-socket operation may run.  The read callback
  // validates StartData/Data/EndData, the declared length, transaction IDs,
  // and the final OperationResponse's five-slot P3 byte-count echo before
  // reporting the last body bytes. timeout_ms bounds all receive work in each
  // InputStream read, not CPU time spent by the caller between reads.
  bool open_transfer_input(uint32_t transfer_id, uint64_t expected_size,
                           uint32_t timeout_ms, PtpInputState *state,
                           InputStream *input, Error *error);

  // Abort an unfinished input lease.  A PTP/IP Cancel packet is attempted,
  // then the command socket is deliberately closed because a bounded cancel
  // cannot prove that an arbitrary partially received frame was drained.
  // Event-socket and ordinary object cleanup remain available through close().
  bool cancel_input(PtpInputState *state, Error *error);

  // Best-effort CloseSession followed by unconditional fd cleanup.  The
  // return value describes cleanup, and the object is reusable afterwards.
  bool close(Error *error);

  bool connected() const { return command_fd_ >= 0; }
  bool session_open() const { return session_open_; }

 private:
  bool operation_with_transaction(
      uint32_t data_phase, uint16_t opcode, const uint32_t *parameters,
      uint32_t parameter_count, const uint8_t *outgoing, size_t outgoing_size,
      uint8_t *incoming, size_t incoming_capacity, uint32_t timeout_ms,
      uint32_t transaction_id, PtpOperationResult *result, Error *error);

  static long input_read(void *context, void *destination, size_t size,
                         Error *error);
  long read_transfer_input(PtpInputState *state, void *destination,
                           size_t size, Error *error);
  bool finish_transfer_input(PtpInputState *state, Error *error);
  bool fail_transfer_input(PtpInputState *state, const char *message,
                           Error *error);
  uint32_t reserve_transaction();

  int command_fd_;
  int event_fd_;
  uint32_t next_transaction_;
  PtpInputState *leased_input_;
  bool session_open_;
  bool command_healthy_;
};

}  // namespace om1_stars

#endif
