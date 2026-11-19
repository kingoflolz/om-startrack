#include "ptp.h"

#include "orf.h"
#include "syscalls.h"
#include "util.h"

#include <stddef.h>
#include <stdint.h>

namespace om1_stars {

namespace {

constexpr uint32_t kPtpPort = 15740;
constexpr uint32_t kLoopbackHostOrder = UINT32_C(0x7f000001);
constexpr uint32_t kProtocolVersion = UINT32_C(0x00010000);
constexpr uint32_t kMaximumPacketLength = UINT32_C(20000000);
constexpr uint64_t kMaximumOperationData = UINT64_C(32) * 1024u * 1024u;
constexpr uint64_t kMaximumTransferData = UINT64_C(128) * 1024u * 1024u;
constexpr uint32_t kCancelTimeoutMs = 1000;
constexpr uint32_t kMaximumCoalescedOutgoing = 64;
// EndData prepends its four-byte transaction identifier to the outgoing
// payload.  Size the generic one-packet buffer for that complete body so every
// payload admitted by kMaximumCoalescedOutgoing still reaches send_exact as a
// single contiguous PTP/IP packet.
constexpr uint32_t kMaximumCoalescedPacketBody =
    kMaximumCoalescedOutgoing + 4u;
// A small initiator-to-camera operation consists of three independently
// framed PTP/IP packets.  They are consecutive bytes on one TCP stream, so
// emit the complete request/start/end sequence with one send_exact call
// (which may internally retry a partial kernel send).  This does not pipeline
// transactions: the response is still consumed before the caller can reserve
// or post the next operation.
constexpr uint32_t kMaximumCoalescedOperationBytes =
    (8u + 30u) + (8u + 12u) +
    (8u + kMaximumCoalescedPacketBody);
constexpr int kMessageDontWait = 0x40;
constexpr int kErrorAlready = 114;

constexpr uint32_t kPacketInitCommandRequest = 1;
constexpr uint32_t kPacketInitCommandAck = 2;
constexpr uint32_t kPacketInitEventRequest = 3;
constexpr uint32_t kPacketInitEventAck = 4;
constexpr uint32_t kPacketInitFail = 5;
constexpr uint32_t kPacketOperationRequest = 6;
constexpr uint32_t kPacketOperationResponse = 7;
constexpr uint32_t kPacketStartData = 9;
constexpr uint32_t kPacketData = 10;
constexpr uint32_t kPacketCancel = 11;
constexpr uint32_t kPacketEndData = 12;

constexpr uint16_t kOperationOpenSession = 0x1002;
constexpr uint16_t kOperationCloseSession = 0x1003;
constexpr uint16_t kOperationTransferInput = 0x94de;

void put_le16(uint8_t *output, uint16_t value) {
  output[0] = static_cast<uint8_t>(value);
  output[1] = static_cast<uint8_t>(value >> 8);
}

void put_le32(uint8_t *output, uint32_t value) {
  output[0] = static_cast<uint8_t>(value);
  output[1] = static_cast<uint8_t>(value >> 8);
  output[2] = static_cast<uint8_t>(value >> 16);
  output[3] = static_cast<uint8_t>(value >> 24);
}

void put_le64(uint8_t *output, uint64_t value) {
  put_le32(output, static_cast<uint32_t>(value));
  put_le32(output + 4, static_cast<uint32_t>(value >> 32));
}

size_t append_packet(uint8_t *output, uint32_t packet_type,
                     const uint8_t *body, uint32_t body_size) {
  put_le32(output, body_size + 8u);
  put_le32(output + 4u, packet_type);
  for (uint32_t index = 0; index < body_size; ++index) {
    output[8u + index] = body[index];
  }
  return static_cast<size_t>(body_size) + 8u;
}

uint16_t get_le16(const uint8_t *input) {
  return static_cast<uint16_t>(input[0]) |
         static_cast<uint16_t>(static_cast<uint16_t>(input[1]) << 8);
}

uint32_t get_le32(const uint8_t *input) {
  return static_cast<uint32_t>(input[0]) |
         (static_cast<uint32_t>(input[1]) << 8) |
         (static_cast<uint32_t>(input[2]) << 16) |
         (static_cast<uint32_t>(input[3]) << 24);
}

uint64_t get_le64(const uint8_t *input) {
  return static_cast<uint64_t>(get_le32(input)) |
         (static_cast<uint64_t>(get_le32(input + 4)) << 32);
}

bool make_deadline(uint32_t timeout_ms, uint64_t *deadline, Error *error) {
  uint64_t now = 0;
  const long status = os::monotonic_milliseconds(&now);
  if (os::failed(status)) return fail(error, "CLOCK_MONOTONIC failed");
  *deadline = now + timeout_ms;
  if (*deadline < now) *deadline = UINT64_MAX;
  return true;
}

bool remaining_timeout(uint64_t deadline, int *timeout_ms, Error *error) {
  uint64_t now = 0;
  const long status = os::monotonic_milliseconds(&now);
  if (os::failed(status)) return fail(error, "CLOCK_MONOTONIC failed");
  if (now >= deadline) return fail(error, "PTP/IP timeout");
  uint64_t remaining = deadline - now;
  if (remaining > static_cast<uint64_t>(INT32_MAX)) remaining = INT32_MAX;
  *timeout_ms = static_cast<int>(remaining);
  return true;
}

bool wait_fd(int fd, int16_t requested, uint64_t deadline, Error *error) {
  for (;;) {
    int timeout_ms = 0;
    if (!remaining_timeout(deadline, &timeout_ms, error)) return false;
    os::PollFd descriptor = {fd, requested, 0};
    const long status = os::poll(&descriptor, 1, timeout_ms);
    if (status == -os::kErrorInterrupted) continue;
    if (os::failed(status)) return fail(error, "PTP/IP poll failed");
    if (status == 0) return fail(error, "PTP/IP timeout");
    if ((descriptor.revents & requested) != 0) return true;
    if ((descriptor.revents &
         (os::kPollError | os::kPollHangup | os::kPollInvalid)) != 0) {
      return fail(error, "PTP/IP socket closed");
    }
  }
}

bool connect_with_deadline(int fd, const os::SockAddrIn &address,
                           uint64_t deadline, Error *error) {
  const long connected = os::connect(fd, &address, sizeof(address));
  if (connected == 0) return true;
  if (connected != -os::kErrorInProgress && connected != -kErrorAlready &&
      connected != -os::kErrorInterrupted) {
    return fail_u64(error, "PTP/IP connect failed with errno ",
                    os::error_number(connected));
  }
  if (!wait_fd(fd, os::kPollOut, deadline, error)) return false;
  int32_t socket_error = 0;
  uint32_t error_size = sizeof(socket_error);
  const long status = os::getsockopt(fd, os::kSocketLevel, os::kSocketError,
                                     &socket_error, &error_size);
  if (os::failed(status) || error_size != sizeof(socket_error)) {
    return fail(error, "PTP/IP SO_ERROR query failed");
  }
  if (socket_error != 0) {
    return fail_u64(error, "PTP/IP connect failed with errno ",
                    static_cast<uint32_t>(socket_error));
  }
  return true;
}

bool disable_tcp_coalescing_delay(int fd, Error *error) {
  const int32_t enabled = 1;
  const long status =
      os::setsockopt(fd, os::kIpProtocolTcp, os::kTcpNoDelay, &enabled,
                     static_cast<uint32_t>(sizeof(enabled)));
  if (os::failed(status)) {
    return fail_u64(error, "PTP/IP TCP_NODELAY failed with errno ",
                    os::error_number(status));
  }
  return true;
}

bool send_exact(int fd, const uint8_t *data, size_t size, uint64_t deadline,
                Error *error) {
  while (size != 0) {
    if (!wait_fd(fd, os::kPollOut, deadline, error)) return false;
    const long count = os::send(fd, data, size,
                                os::kMessageNoSignal | kMessageDontWait);
    if (count == -os::kErrorInterrupted || count == -os::kErrorAgain) continue;
    if (os::failed(count)) return fail(error, "PTP/IP send failed");
    if (count == 0) return fail(error, "PTP/IP zero-length send");
    const size_t consumed = static_cast<size_t>(count);
    data += consumed;
    size -= consumed;
  }
  return true;
}

bool receive_exact(int fd, uint8_t *data, size_t size, uint64_t deadline,
                   Error *error) {
  while (size != 0) {
    if (!wait_fd(fd, os::kPollIn, deadline, error)) return false;
    const long count = os::recv(fd, data, size, kMessageDontWait);
    if (count == -os::kErrorInterrupted || count == -os::kErrorAgain) continue;
    if (os::failed(count)) return fail(error, "PTP/IP receive failed");
    if (count == 0) return fail(error, "unexpected PTP/IP EOF");
    const size_t consumed = static_cast<size_t>(count);
    data += consumed;
    size -= consumed;
  }
  return true;
}

bool send_header(int fd, uint32_t packet_type, uint32_t body_size,
                 uint64_t deadline, Error *error) {
  if (body_size > UINT32_MAX - 8u) return fail(error, "PTP/IP packet too large");
  uint8_t header[8];
  put_le32(header, body_size + 8u);
  put_le32(header + 4, packet_type);
  return send_exact(fd, header, sizeof(header), deadline, error);
}

bool send_packet(int fd, uint32_t packet_type, const uint8_t *body,
                 uint32_t body_size, uint64_t deadline, Error *error) {
  // Every production control packet is tiny.  Keep its length/type and body in
  // one contiguous send_exact call so loopback PTP/IP cannot impose an
  // avoidable delayed-ACK boundary between an eight-byte header and a few
  // payload bytes.  send_exact still handles a legitimate partial send.
  if (body_size <= kMaximumCoalescedPacketBody) {
    uint8_t packet[8u + kMaximumCoalescedPacketBody];
    put_le32(packet, body_size + 8u);
    put_le32(packet + 4u, packet_type);
    for (uint32_t i = 0; i < body_size; ++i) packet[8u + i] = body[i];
    return send_exact(fd, packet, 8u + body_size, deadline, error);
  }
  if (!send_header(fd, packet_type, body_size, deadline, error)) return false;
  return body_size == 0 || send_exact(fd, body, body_size, deadline, error);
}

bool receive_header(int fd, uint32_t *packet_type, uint32_t *body_size,
                    uint64_t deadline, Error *error) {
  uint8_t header[8];
  if (!receive_exact(fd, header, sizeof(header), deadline, error)) return false;
  const uint32_t length = get_le32(header);
  if (length < 8 || length > kMaximumPacketLength) {
    return fail_u64(error, "invalid PTP/IP packet length ", length);
  }
  *packet_type = get_le32(header + 4);
  *body_size = length - 8u;
  return true;
}

bool discard_exact(int fd, uint32_t size, uint64_t deadline, Error *error) {
  uint8_t scratch[256];
  while (size != 0) {
    uint32_t amount = size;
    if (amount > sizeof(scratch)) amount = sizeof(scratch);
    if (!receive_exact(fd, scratch, amount, deadline, error)) return false;
    size -= amount;
  }
  return true;
}

bool fail_response(Error *error, const char *operation, uint16_t response) {
  if (error == nullptr) return false;
  TextBuffer message(error->text, sizeof(error->text));
  message.append(operation);
  message.append(" returned 0x");
  message.append_hex(response, 4);
  return false;
}

bool receive_response_body(int fd, uint32_t body_size, uint64_t deadline,
                           uint16_t *response_code, uint32_t *transaction_id,
                           uint32_t parameters[5],
                           uint32_t *parameter_count,
                           Error *error) {
  uint8_t response[26] = {};
  if (body_size < 6 || body_size > sizeof(response) ||
      ((body_size - 6u) & 3u) != 0) {
    return fail(error, "malformed PTP OperationResponse");
  }
  if (!receive_exact(fd, response, body_size, deadline, error)) return false;
  *response_code = get_le16(response);
  *transaction_id = get_le32(response + 2);
  *parameter_count = (body_size - 6u) / 4u;
  for (unsigned i = 0; i < 5; ++i) parameters[i] = 0;
  for (uint32_t i = 0; i < *parameter_count; ++i) {
    parameters[i] = get_le32(response + 6u + i * 4u);
  }
  return true;
}

void clear_result(PtpOperationResult *result, uint32_t transaction) {
  result->response_code = 0;
  result->transaction_id = transaction;
  for (uint32_t &parameter : result->parameters) parameter = 0;
  result->parameter_count = 0;
  result->data_size = 0;
  result->declared_data_size = 0;
}

}  // namespace

void PtpInputState::initialize() {
  owner_ = nullptr;
  transaction_id_ = 0;
  packet_remaining_ = 0;
  declared_size_ = 0;
  received_size_ = 0;
  deadline_ = 0;
  io_timeout_ms_ = 0;
  active_ = false;
  complete_ = false;
  saw_end_ = false;
  failed_ = false;
}

void PtpIpClient::initialize() {
  command_fd_ = -1;
  event_fd_ = -1;
  next_transaction_ = 1;
  leased_input_ = nullptr;
  session_open_ = false;
  command_healthy_ = false;
}

bool PtpIpClient::connect_loopback(const uint8_t guid_tail[6],
                                   uint32_t timeout_ms, Error *error) {
  clear_error(error);
  if (guid_tail == nullptr) return fail(error, "missing PTP GUID tail");
  if (command_fd_ >= 0 || event_fd_ >= 0) {
    return fail(error, "PTP/IP client is already connected");
  }

  uint64_t deadline = 0;
  if (!make_deadline(timeout_ms, &deadline, error)) return false;

  os::SockAddrIn address = {};
  address.family = os::kAddressFamilyInet;
  address.port = os::host_to_network16(static_cast<uint16_t>(kPtpPort));
  address.address = os::host_to_network32(kLoopbackHostOrder);

  long descriptor =
      os::socket(os::kAddressFamilyInet,
                 os::kSocketStream | os::kSocketNonBlock |
                     os::kSocketCloseOnExec,
                 0);
  if (os::failed(descriptor)) return fail(error, "PTP command socket failed");
  command_fd_ = static_cast<int>(descriptor);
  if (!connect_with_deadline(command_fd_, address, deadline, error)) {
    Error ignored = {};
    (void)close(&ignored);
    return false;
  }
  if (!disable_tcp_coalescing_delay(command_fd_, error)) {
    Error ignored = {};
    (void)close(&ignored);
    return false;
  }
  command_healthy_ = true;

  uint8_t init_body[32] = {};
  for (unsigned i = 0; i < 6; ++i) init_body[10 + i] = guid_tail[i];
  static constexpr uint8_t kLinuxName[] = {
      'L', 0, 'i', 0, 'n', 0, 'u', 0, 'x', 0, 0, 0,
  };
  for (unsigned i = 0; i < sizeof(kLinuxName); ++i) {
    init_body[16 + i] = kLinuxName[i];
  }
  put_le32(init_body + 28, kProtocolVersion);
  if (!send_packet(command_fd_, kPacketInitCommandRequest, init_body,
                   sizeof(init_body), deadline, error)) {
    command_healthy_ = false;
    Error ignored = {};
    (void)close(&ignored);
    return false;
  }

  uint32_t packet_type = 0;
  uint32_t body_size = 0;
  if (!receive_header(command_fd_, &packet_type, &body_size, deadline, error)) {
    command_healthy_ = false;
    Error ignored = {};
    (void)close(&ignored);
    return false;
  }
  uint8_t acknowledgement[104] = {};
  if (body_size > sizeof(acknowledgement)) {
    command_healthy_ = false;
    Error ignored = {};
    (void)close(&ignored);
    return fail(error, "oversized PTP InitCommand response");
  }
  if (!receive_exact(command_fd_, acknowledgement, body_size, deadline,
                     error)) {
    command_healthy_ = false;
    Error ignored = {};
    (void)close(&ignored);
    return false;
  }
  if (packet_type == kPacketInitFail) {
    const uint32_t reason = body_size == 4 ? get_le32(acknowledgement) : 0;
    Error ignored = {};
    (void)close(&ignored);
    return fail_u64(error, "PTP/IP authorization failed, reason ", reason);
  }
  const bool valid_ack =
      packet_type == kPacketInitCommandAck && body_size >= 26u &&
      ((body_size - 24u) & 1u) == 0 &&
      acknowledgement[body_size - 6u] == 0 &&
      acknowledgement[body_size - 5u] == 0 &&
      get_le32(acknowledgement + body_size - 4u) == kProtocolVersion;
  if (!valid_ack) {
    command_healthy_ = false;
    Error ignored = {};
    (void)close(&ignored);
    return fail(error, "malformed PTP InitCommandAck");
  }
  const uint32_t connection_number = get_le32(acknowledgement);

  descriptor = os::socket(os::kAddressFamilyInet,
                          os::kSocketStream | os::kSocketNonBlock |
                              os::kSocketCloseOnExec,
                          0);
  if (os::failed(descriptor)) {
    Error ignored = {};
    (void)close(&ignored);
    return fail(error, "PTP event socket failed");
  }
  event_fd_ = static_cast<int>(descriptor);
  if (!connect_with_deadline(event_fd_, address, deadline, error)) {
    Error ignored = {};
    (void)close(&ignored);
    return false;
  }
  if (!disable_tcp_coalescing_delay(event_fd_, error)) {
    Error ignored = {};
    (void)close(&ignored);
    return false;
  }

  uint8_t event_request[4];
  put_le32(event_request, connection_number);
  if (!send_packet(event_fd_, kPacketInitEventRequest, event_request,
                   sizeof(event_request), deadline, error) ||
      !receive_header(event_fd_, &packet_type, &body_size, deadline, error)) {
    Error ignored = {};
    (void)close(&ignored);
    return false;
  }
  if (packet_type != kPacketInitEventAck || body_size != 0) {
    if (!discard_exact(event_fd_, body_size, deadline, error)) {
      Error ignored = {};
      (void)close(&ignored);
      return false;
    }
    Error ignored = {};
    (void)close(&ignored);
    return fail(error, "expected PTP InitEventAck");
  }

  const uint32_t session_id = 1;
  PtpOperationResult opened = {};
  if (!operation_with_transaction(1, kOperationOpenSession, &session_id, 1,
                                  nullptr, 0, nullptr, 0, timeout_ms, 0,
                                  &opened, error)) {
    Error ignored = {};
    (void)close(&ignored);
    return false;
  }
  if (opened.response_code == kPtpResponseSessionAlreadyOpen) {
    PtpOperationResult closed = {};
    if (!operation_with_transaction(1, kOperationCloseSession, nullptr, 0,
                                    nullptr, 0, nullptr, 0, timeout_ms,
                                    UINT32_C(0xfffffffe), &closed, error) ||
        closed.response_code != kPtpResponseOk) {
      if (error != nullptr && error->text[0] == '\0') {
        (void)fail_response(error, "PTP CloseSession recovery",
                            closed.response_code);
      }
      Error ignored = {};
      (void)close(&ignored);
      return false;
    }
    if (!operation_with_transaction(1, kOperationOpenSession, &session_id, 1,
                                    nullptr, 0, nullptr, 0, timeout_ms, 0,
                                    &opened, error)) {
      Error ignored = {};
      (void)close(&ignored);
      return false;
    }
  }
  if (opened.response_code != kPtpResponseOk) {
    const uint16_t response = opened.response_code;
    Error ignored = {};
    (void)close(&ignored);
    return fail_response(error, "PTP OpenSession", response);
  }
  session_open_ = true;
  next_transaction_ = 1;
  return true;
}

uint32_t PtpIpClient::reserve_transaction() {
  const uint32_t transaction = next_transaction_++;
  if (next_transaction_ == 0 || next_transaction_ == UINT32_C(0xfffffffe)) {
    next_transaction_ = 1;
  }
  return transaction;
}

bool PtpIpClient::operation(uint32_t data_phase, uint16_t opcode,
                            const uint32_t *parameters,
                            uint32_t parameter_count,
                            const uint8_t *outgoing, size_t outgoing_size,
                            uint8_t *incoming, size_t incoming_capacity,
                            uint32_t timeout_ms, PtpOperationResult *result,
                            Error *error) {
  if (leased_input_ != nullptr) {
    clear_error(error);
    return fail(error, "PTP command socket has an active input lease");
  }
  const uint32_t transaction = reserve_transaction();
  return operation_with_transaction(
      data_phase, opcode, parameters, parameter_count, outgoing, outgoing_size,
      incoming, incoming_capacity, timeout_ms, transaction, result, error);
}

bool PtpIpClient::operation_with_transaction(
    uint32_t data_phase, uint16_t opcode, const uint32_t *parameters,
    uint32_t parameter_count, const uint8_t *outgoing, size_t outgoing_size,
    uint8_t *incoming, size_t incoming_capacity, uint32_t timeout_ms,
    uint32_t transaction_id, PtpOperationResult *result, Error *error) {
  clear_error(error);
  if (result == nullptr) return fail(error, "missing PTP operation result");
  clear_result(result, transaction_id);
  if (command_fd_ < 0 || !command_healthy_) {
    return fail(error, "PTP command connection is not usable");
  }
  if (leased_input_ != nullptr) {
    return fail(error, "PTP command socket has an active input lease");
  }
  if ((data_phase != 1 && data_phase != 2) || parameter_count > 5 ||
      (parameter_count != 0 && parameters == nullptr) ||
      (outgoing_size != 0 && outgoing == nullptr) ||
      (data_phase != 2 && outgoing_size != 0) ||
      (incoming_capacity != 0 && incoming == nullptr) ||
      outgoing_size > UINT32_MAX - 4u) {
    return fail(error, "invalid PTP operation arguments");
  }

  uint64_t deadline = 0;
  if (!make_deadline(timeout_ms, &deadline, error)) return false;

  uint8_t request[30] = {};
  put_le32(request, data_phase);
  put_le16(request + 4, opcode);
  put_le32(request + 6, transaction_id);
  for (uint32_t i = 0; i < parameter_count; ++i) {
    put_le32(request + 10 + i * 4, parameters[i]);
  }
  const uint32_t request_size = 10u + parameter_count * 4u;
  if (data_phase == 2 && outgoing_size <= kMaximumCoalescedOutgoing) {
    uint8_t start[12];
    put_le32(start, transaction_id);
    put_le64(start + 4, outgoing_size);
    uint8_t end[kMaximumCoalescedPacketBody];
    put_le32(end, transaction_id);
    for (size_t i = 0; i < outgoing_size; ++i) end[4u + i] = outgoing[i];

    uint8_t operation[kMaximumCoalescedOperationBytes];
    size_t operation_size = 0;
    operation_size += append_packet(operation + operation_size,
                                    kPacketOperationRequest, request,
                                    request_size);
    operation_size += append_packet(operation + operation_size,
                                    kPacketStartData, start, sizeof(start));
    operation_size += append_packet(
        operation + operation_size, kPacketEndData, end,
        static_cast<uint32_t>(outgoing_size) + 4u);
    if (!send_exact(command_fd_, operation, operation_size, deadline, error)) {
      command_healthy_ = false;
      return false;
    }
  } else {
    if (!send_packet(command_fd_, kPacketOperationRequest, request,
                     request_size, deadline, error)) {
      command_healthy_ = false;
      return false;
    }

    if (data_phase == 2) {
      uint8_t start[12];
      put_le32(start, transaction_id);
      put_le64(start + 4, outgoing_size);
      if (!send_packet(command_fd_, kPacketStartData, start, sizeof(start),
                       deadline, error)) {
        command_healthy_ = false;
        return false;
      }
      uint8_t end_prefix[4];
      put_le32(end_prefix, transaction_id);
      if (!send_header(command_fd_, kPacketEndData,
                       static_cast<uint32_t>(outgoing_size) + 4u, deadline,
                       error) ||
          !send_exact(command_fd_, end_prefix, sizeof(end_prefix), deadline,
                      error) ||
          (outgoing_size != 0 && !send_exact(command_fd_, outgoing,
                                             outgoing_size, deadline,
                                             error))) {
        command_healthy_ = false;
        return false;
      }
    }
  }

  bool saw_start = false;
  bool saw_end = false;
  bool overflow = false;
  uint64_t received_data = 0;
  size_t copied_data = 0;

  for (;;) {
    uint32_t packet_type = 0;
    uint32_t body_size = 0;
    if (!receive_header(command_fd_, &packet_type, &body_size, deadline,
                        error)) {
      command_healthy_ = false;
      return false;
    }

    if (packet_type == kPacketOperationResponse) {
      uint8_t response[26] = {};
      if (body_size < 6 || body_size > sizeof(response) ||
          ((body_size - 6u) & 3u) != 0) {
        if (!discard_exact(command_fd_, body_size, deadline, error)) {
          command_healthy_ = false;
          return false;
        }
        command_healthy_ = false;
        return fail(error, "malformed PTP OperationResponse");
      }
      if (!receive_exact(command_fd_, response, body_size, deadline, error)) {
        command_healthy_ = false;
        return false;
      }
      result->response_code = get_le16(response);
      result->transaction_id = get_le32(response + 2);
      result->parameter_count = (body_size - 6u) / 4u;
      for (uint32_t i = 0; i < result->parameter_count; ++i) {
        result->parameters[i] = get_le32(response + 6 + i * 4);
      }
      result->data_size = copied_data;
      if (result->transaction_id != transaction_id) {
        command_healthy_ = false;
        return fail(error, "PTP response transaction mismatch");
      }
      if (saw_start && !saw_end) {
        command_healthy_ = false;
        return fail(error, "PTP response arrived before EndData");
      }
      if (saw_start && received_data != result->declared_data_size) {
        command_healthy_ = false;
        return fail(error, "PTP data length mismatch");
      }
      if (overflow) return fail(error, "PTP data exceeds fixed buffer");
      return true;
    }

    if (packet_type == kPacketStartData) {
      uint8_t start[12];
      if (body_size != sizeof(start) || saw_start || saw_end) {
        if (!discard_exact(command_fd_, body_size, deadline, error)) {
          command_healthy_ = false;
          return false;
        }
        command_healthy_ = false;
        return fail(error, "malformed PTP StartData");
      }
      if (!receive_exact(command_fd_, start, sizeof(start), deadline, error)) {
        command_healthy_ = false;
        return false;
      }
      if (get_le32(start) != transaction_id) {
        command_healthy_ = false;
        return fail(error, "PTP StartData transaction mismatch");
      }
      result->declared_data_size = get_le64(start + 4);
      if (result->declared_data_size > kMaximumOperationData) {
        command_healthy_ = false;
        return fail(error, "PTP operation data declaration is too large");
      }
      saw_start = true;
      continue;
    }

    if (packet_type == kPacketData || packet_type == kPacketEndData) {
      if (body_size < 4 || !saw_start || saw_end) {
        if (!discard_exact(command_fd_, body_size, deadline, error)) {
          command_healthy_ = false;
          return false;
        }
        command_healthy_ = false;
        return fail(error, "out-of-sequence PTP data packet");
      }
      uint8_t transaction_bytes[4];
      if (!receive_exact(command_fd_, transaction_bytes,
                         sizeof(transaction_bytes), deadline, error)) {
        command_healthy_ = false;
        return false;
      }
      uint32_t payload_size = body_size - 4u;
      if (get_le32(transaction_bytes) != transaction_id) {
        if (!discard_exact(command_fd_, payload_size, deadline, error)) {
          command_healthy_ = false;
          return false;
        }
        command_healthy_ = false;
        return fail(error, "PTP data transaction mismatch");
      }
      if (received_data + payload_size < received_data ||
          received_data + payload_size > result->declared_data_size) {
        if (!discard_exact(command_fd_, payload_size, deadline, error)) {
          command_healthy_ = false;
          return false;
        }
        command_healthy_ = false;
        return fail(error, "PTP data exceeds declared length");
      }

      uint32_t remaining = payload_size;
      while (remaining != 0) {
        if (copied_data < incoming_capacity) {
          size_t amount = incoming_capacity - copied_data;
          if (amount > remaining) amount = remaining;
          if (!receive_exact(command_fd_, incoming + copied_data, amount,
                             deadline, error)) {
            command_healthy_ = false;
            return false;
          }
          copied_data += amount;
          remaining -= static_cast<uint32_t>(amount);
        } else {
          overflow = true;
          if (!discard_exact(command_fd_, remaining, deadline, error)) {
            command_healthy_ = false;
            return false;
          }
          remaining = 0;
        }
      }
      received_data += payload_size;
      if (packet_type == kPacketEndData) saw_end = true;
      continue;
    }

    if (!discard_exact(command_fd_, body_size, deadline, error)) {
      command_healthy_ = false;
      return false;
    }
    command_healthy_ = false;
    return fail(error, "unexpected PTP command packet type");
  }
}

bool PtpIpClient::fail_transfer_input(PtpInputState *state,
                                      const char *message, Error *error) {
  if (error == nullptr || error->text[0] == '\0') (void)fail(error, message);
  if (leased_input_ == state) leased_input_ = nullptr;
  if (state != nullptr) {
    state->active_ = false;
    state->complete_ = false;
    state->failed_ = true;
    state->packet_remaining_ = 0;
  }
  command_healthy_ = false;
  session_open_ = false;
  if (command_fd_ >= 0) (void)os::close(command_fd_);
  command_fd_ = -1;
  return false;
}

bool PtpIpClient::finish_transfer_input(PtpInputState *state, Error *error) {
  if (state == nullptr || state->owner_ != this || leased_input_ != state ||
      !state->active_ || state->packet_remaining_ != 0 ||
      state->received_size_ != state->declared_size_) {
    return fail_transfer_input(state, "invalid PTP input completion state",
                               error);
  }

  // Some senders place the final bytes in Data and follow them with an empty
  // EndData.  Consume only zero-length packets once the declared byte count
  // has been reached; any additional payload is a framing violation.
  while (!state->saw_end_) {
    uint32_t packet_type = 0;
    uint32_t body_size = 0;
    if (!receive_header(command_fd_, &packet_type, &body_size,
                        state->deadline_, error)) {
      return fail_transfer_input(state, "PTP input ended before EndData",
                                 error);
    }
    if ((packet_type != kPacketData && packet_type != kPacketEndData) ||
        body_size < 4) {
      return fail_transfer_input(state, "expected final PTP EndData", error);
    }
    uint8_t transaction_bytes[4];
    if (!receive_exact(command_fd_, transaction_bytes,
                       sizeof(transaction_bytes), state->deadline_, error)) {
      return fail_transfer_input(state, "truncated final PTP data packet",
                                 error);
    }
    if (get_le32(transaction_bytes) != state->transaction_id_ ||
        body_size != sizeof(transaction_bytes)) {
      return fail_transfer_input(state, "invalid final PTP data packet",
                                 error);
    }
    if (packet_type == kPacketEndData) state->saw_end_ = true;
  }

  uint32_t packet_type = 0;
  uint32_t body_size = 0;
  if (!receive_header(command_fd_, &packet_type, &body_size, state->deadline_,
                      error)) {
    return fail_transfer_input(state,
                               "PTP input omitted OperationResponse", error);
  }
  if (packet_type != kPacketOperationResponse) {
    return fail_transfer_input(state,
                               "expected PTP input OperationResponse", error);
  }
  uint16_t response_code = 0;
  uint32_t transaction_id = 0;
  uint32_t response_parameters[5] = {};
  uint32_t response_parameter_count = 0;
  if (!receive_response_body(command_fd_, body_size, state->deadline_,
                             &response_code, &transaction_id,
                             response_parameters, &response_parameter_count,
                             error)) {
    return fail_transfer_input(state,
                               "malformed PTP input OperationResponse", error);
  }
  if (transaction_id != state->transaction_id_) {
    return fail_transfer_input(state,
                               "PTP input response transaction mismatch",
                               error);
  }

  leased_input_ = nullptr;
  state->active_ = false;
  if (response_code != kPtpResponseOk) {
    state->failed_ = true;
    return fail_response(error, "PTP 94DE input", response_code);
  }
  // The v1.9 transport always emits five response slots.  The 94DE handler
  // copies request P3 into the first and leaves the remaining four zero.  On
  // success P3 must therefore echo the exact byte count just delivered;
  // accepting a missing/different count or nonzero padding would weaken the
  // independent 94DD/StartData/EndData length agreement.
  bool response_parameters_valid =
      response_parameter_count == 5 &&
      response_parameters[0] ==
          static_cast<uint32_t>(state->declared_size_);
  for (unsigned i = 1; i < 5; ++i) {
    if (response_parameters[i] != 0) response_parameters_valid = false;
  }
  if (!response_parameters_valid) {
    // Framing and transaction ownership are nevertheless complete here, so
    // keep the command socket usable for the caller's picture-transfer-state
    // verification and CloseSession while withholding the final bytes.
    state->failed_ = true;
    return fail(error, "PTP 94DE response count mismatch");
  }
  state->complete_ = true;
  return true;
}

long PtpIpClient::input_read(void *context, void *destination, size_t size,
                             Error *error) {
  PtpInputState *const state = static_cast<PtpInputState *>(context);
  if (state == nullptr || state->owner_ == nullptr) {
    (void)fail(error, "invalid PTP input stream context");
    return -1;
  }
  return state->owner_->read_transfer_input(state, destination, size, error);
}

long PtpIpClient::read_transfer_input(PtpInputState *state, void *destination,
                                      size_t size, Error *error) {
  clear_error(error);
  if (state == nullptr || state->owner_ != this) {
    (void)fail(error, "invalid PTP input stream state");
    return -1;
  }
  if (size == 0) return 0;
  if (destination == nullptr) {
    (void)fail(error, "missing PTP input destination");
    return -1;
  }
  if (state->complete_) return 0;
  if (state->failed_ || !state->active_ || leased_input_ != state ||
      command_fd_ < 0 || !command_healthy_) {
    (void)fail(error, "PTP input stream is not usable");
    return -1;
  }

  // The decoder deliberately streams and processes one bounded chunk at a
  // time.  Its CPU work (including cold executable/BSS page faults) is not a
  // transport stall and must not consume the next socket-read budget.  A
  // fresh deadline here still bounds all local PTP/IP receive work in this
  // InputStream read, including terminal EndData/OperationResponse work in the
  // final read.
  if (!make_deadline(state->io_timeout_ms_, &state->deadline_, error)) {
    (void)fail_transfer_input(state,
                              "could not refresh PTP input deadline", error);
    return -1;
  }

  uint64_t remaining = state->declared_size_ - state->received_size_;
  if (remaining == 0) {
    if (!finish_transfer_input(state, error)) return -1;
    return 0;
  }
  if (static_cast<uint64_t>(size) > remaining) {
    size = static_cast<size_t>(remaining);
  }
  if (size > static_cast<size_t>(INT32_MAX)) {
    size = static_cast<size_t>(INT32_MAX);
  }

  uint8_t *const output = static_cast<uint8_t *>(destination);
  size_t copied = 0;
  while (copied != size) {
    if (state->packet_remaining_ == 0) {
      if (state->saw_end_) {
        (void)fail_transfer_input(state,
                                  "PTP EndData arrived before declared size",
                                  error);
        return -1;
      }
      uint32_t packet_type = 0;
      uint32_t body_size = 0;
      if (!receive_header(command_fd_, &packet_type, &body_size,
                          state->deadline_, error)) {
        (void)fail_transfer_input(state, "truncated PTP input packet", error);
        return -1;
      }
      if ((packet_type != kPacketData && packet_type != kPacketEndData) ||
          body_size < 4) {
        (void)fail_transfer_input(state,
                                  "unexpected PTP input packet type", error);
        return -1;
      }
      uint8_t transaction_bytes[4];
      if (!receive_exact(command_fd_, transaction_bytes,
                         sizeof(transaction_bytes), state->deadline_, error)) {
        (void)fail_transfer_input(state,
                                  "truncated PTP input transaction", error);
        return -1;
      }
      const uint32_t payload_size = body_size - 4u;
      if (get_le32(transaction_bytes) != state->transaction_id_ ||
          static_cast<uint64_t>(payload_size) >
              state->declared_size_ - state->received_size_) {
        (void)fail_transfer_input(state, "invalid PTP input data packet",
                                  error);
        return -1;
      }
      state->packet_remaining_ = payload_size;
      if (packet_type == kPacketEndData) state->saw_end_ = true;
      if (payload_size == 0) continue;
    }

    size_t amount = size - copied;
    if (amount > state->packet_remaining_) amount = state->packet_remaining_;
    if (!receive_exact(command_fd_, output + copied, amount, state->deadline_,
                       error)) {
      (void)fail_transfer_input(state, "truncated PTP input body", error);
      return -1;
    }
    copied += amount;
    state->packet_remaining_ -= static_cast<uint32_t>(amount);
    state->received_size_ += amount;
  }

  // Do not expose the final bytes as successful until the corresponding
  // EndData and OperationResponse have both been validated.
  if (state->received_size_ == state->declared_size_ &&
      !finish_transfer_input(state, error)) {
    return -1;
  }
  return static_cast<long>(copied);
}

bool PtpIpClient::open_transfer_input(uint32_t transfer_id,
                                      uint64_t expected_size,
                                      uint32_t timeout_ms,
                                      PtpInputState *state,
                                      InputStream *input, Error *error) {
  clear_error(error);
  if (input != nullptr) {
    input->context = nullptr;
    input->read = nullptr;
    input->total_size = 0;
  }
  if (state == nullptr || input == nullptr) {
    return fail(error, "invalid PTP input arguments");
  }
  if (state->active_ || leased_input_ != nullptr) {
    return fail(error, "PTP command socket already has an input lease");
  }
  if (command_fd_ < 0 || !command_healthy_ || !session_open_) {
    return fail(error, "PTP command connection is not usable");
  }
  if (transfer_id == 0 || expected_size == 0 ||
      expected_size > kMaximumTransferData || expected_size > UINT32_MAX) {
    return fail(error, "invalid PTP 94DE transfer metadata");
  }

  state->initialize();
  state->owner_ = this;
  state->transaction_id_ = reserve_transaction();
  state->declared_size_ = expected_size;
  state->io_timeout_ms_ = timeout_ms;
  state->active_ = true;
  leased_input_ = state;
  if (!make_deadline(timeout_ms, &state->deadline_, error)) {
    return fail_transfer_input(state, "could not start PTP input deadline",
                               error);
  }

  // 94DE is the vendor partial-input operation even for a whole-file read:
  // P1=transfer record, P2/P5=64-bit offset (zero), P3=count, P4=zero.
  uint8_t request[30] = {};
  put_le32(request, 1);
  put_le16(request + 4, kOperationTransferInput);
  put_le32(request + 6, state->transaction_id_);
  put_le32(request + 10, transfer_id);
  put_le32(request + 14, 0);
  put_le32(request + 18, static_cast<uint32_t>(expected_size));
  put_le32(request + 22, 0);
  put_le32(request + 26, 0);
  if (!send_packet(command_fd_, kPacketOperationRequest, request,
                   sizeof(request), state->deadline_, error)) {
    return fail_transfer_input(state, "sending PTP 94DE request failed",
                               error);
  }

  uint32_t packet_type = 0;
  uint32_t body_size = 0;
  if (!receive_header(command_fd_, &packet_type, &body_size, state->deadline_,
                      error)) {
    return fail_transfer_input(state, "PTP 94DE returned no response", error);
  }
  if (packet_type == kPacketOperationResponse) {
    uint16_t response_code = 0;
    uint32_t transaction_id = 0;
    uint32_t response_parameters[5] = {};
    uint32_t response_parameter_count = 0;
    if (!receive_response_body(command_fd_, body_size, state->deadline_,
                               &response_code, &transaction_id,
                               response_parameters,
                               &response_parameter_count, error)) {
      return fail_transfer_input(state,
                                 "malformed PTP 94DE OperationResponse",
                                 error);
    }
    if (transaction_id != state->transaction_id_) {
      return fail_transfer_input(state,
                                 "PTP 94DE response transaction mismatch",
                                 error);
    }
    leased_input_ = nullptr;
    state->active_ = false;
    state->failed_ = true;
    if (response_code == kPtpResponseOk) {
      return fail_transfer_input(state,
                                 "PTP 94DE omitted its data phase", error);
    }
    return fail_response(error, "PTP 94DE", response_code);
  }
  if (packet_type != kPacketStartData || body_size != 12) {
    return fail_transfer_input(state, "expected PTP 94DE StartData", error);
  }
  uint8_t start[12];
  if (!receive_exact(command_fd_, start, sizeof(start), state->deadline_,
                     error)) {
    return fail_transfer_input(state, "truncated PTP 94DE StartData", error);
  }
  if (get_le32(start) != state->transaction_id_ ||
      get_le64(start + 4) != expected_size) {
    return fail_transfer_input(state,
                               "PTP 94DE declared an unexpected data size",
                               error);
  }

  input->context = state;
  input->read = input_read;
  input->total_size = static_cast<uint32_t>(expected_size);
  return true;
}

bool PtpIpClient::cancel_input(PtpInputState *state, Error *error) {
  clear_error(error);
  if (state == nullptr) return fail(error, "missing PTP input state");
  if (!state->active_) return true;
  if (state->owner_ != this || leased_input_ != state) {
    return fail(error, "PTP input lease owner mismatch");
  }

  bool ok = true;
  Error first = {};
  if (command_fd_ >= 0 && command_healthy_) {
    uint8_t body[4];
    put_le32(body, state->transaction_id_);
    Error local = {};
    uint64_t cancel_deadline = 0;
    // Cancellation has its own short bound.  The stream's current read
    // deadline may already be expired on this path, while reusing a freshly
    // renewed one would make teardown wait much longer than intended.
    if (!make_deadline(kCancelTimeoutMs, &cancel_deadline, &local) ||
        !send_packet(command_fd_, kPacketCancel, body, sizeof(body),
                     cancel_deadline, &local)) {
      first = local;
      ok = false;
    }
  }
  if (command_fd_ >= 0) {
    const long status = os::close(command_fd_);
    if (os::failed(status) && ok) {
      (void)fail(&first, "closing cancelled PTP command socket failed");
      ok = false;
    }
  }
  command_fd_ = -1;
  command_healthy_ = false;
  session_open_ = false;
  leased_input_ = nullptr;
  state->active_ = false;
  state->complete_ = false;
  state->failed_ = true;
  state->packet_remaining_ = 0;
  if (!ok && error != nullptr) *error = first;
  return ok;
}

bool PtpIpClient::close(Error *error) {
  clear_error(error);
  bool ok = true;
  Error first = {};
  if (leased_input_ != nullptr) {
    Error local = {};
    if (!cancel_input(leased_input_, &local)) {
      first = local;
      ok = false;
    }
  }
  if (command_fd_ >= 0 && command_healthy_ && session_open_) {
    PtpOperationResult result = {};
    Error local = {};
    if (!operation_with_transaction(1, kOperationCloseSession, nullptr, 0,
                                    nullptr, 0, nullptr, 0, 3000,
                                    UINT32_C(0xfffffffe), &result, &local) ||
        result.response_code != kPtpResponseOk) {
      if (local.text[0] == '\0') {
        (void)fail_response(&local, "PTP CloseSession", result.response_code);
      }
      first = local;
      ok = false;
    }
  }
  session_open_ = false;
  if (event_fd_ >= 0 && os::failed(os::close(event_fd_)) && ok) {
    (void)fail(&first, "closing PTP event socket failed");
    ok = false;
  }
  if (command_fd_ >= 0 && os::failed(os::close(command_fd_)) && ok) {
    (void)fail(&first, "closing PTP command socket failed");
    ok = false;
  }
  event_fd_ = -1;
  command_fd_ = -1;
  command_healthy_ = false;
  leased_input_ = nullptr;
  next_transaction_ = 1;
  if (!ok && error != nullptr) *error = first;
  return ok;
}

}  // namespace om1_stars
