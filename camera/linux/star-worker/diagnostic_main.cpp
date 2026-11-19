#include "camera.h"
#include "detector.h"
#include "fileif_direct.h"
#include "http.h"
#include "orf.h"
#include "syscalls.h"
#include "types.h"
#include "util.h"

#include <stddef.h>
#include <stdint.h>

namespace om1_stars {
namespace {

constexpr DetectorOptions kDetectorOptions = {
    5u * 256u,
    3u,
    1024u,
    3u * 256u,
    230u,
    1u,
};
constexpr uint32_t kCaptureTimeoutMilliseconds = 30u * 1000u;
constexpr uint32_t kTransferTimeoutMilliseconds = 10u * 1000u;

CameraController g_camera;
Star g_output_stars[kMaxStars];

struct DetectionBridge {
  const RawInfo *raw_info;
  bool started;
};

void print_error(const char *prefix, const Error &error) {
  char storage[384];
  TextBuffer line(storage, sizeof(storage));
  line.append("om1-stars-diag: ");
  line.append(prefix);
  if (error.text[0] != '\0') {
    line.append(": ");
    line.append(error.text);
  }
  line.append_char('\n');
  if (line.ok()) (void)write_all(2, line.data(), line.size());
}

bool print_star(const Star &star) {
  char storage[128];
  TextBuffer line(storage, sizeof(storage));
  line.append_q8(star.x_q8, 3);
  line.append_char(',');
  line.append_q8(star.y_q8, 3);
  line.append_char(',');
  line.append_u64(star.flux);
  line.append_char('\n');
  return line.ok() && write_all(1, line.data(), line.size());
}

bool print_decode_summary(const RawInfo &info, const DecodeStats &stats) {
  char storage[448];
  TextBuffer line(storage, sizeof(storage));
  line.append("om1-stars-diag: raw=");
  line.append_u64(info.width);
  line.append_char('x');
  line.append_u64(info.height);
  line.append(" crop=");
  line.append_u64(info.crop_x);
  line.append_char(',');
  line.append_u64(info.crop_y);
  line.append_char('+');
  line.append_u64(info.crop_width);
  line.append_char('x');
  line.append_u64(info.crop_height);
  line.append(" pixels=");
  line.append_u64(stats.pixels_decoded);
  line.append(" strip_bytes=");
  line.append_u64(stats.strip_bytes_read);
  line.append(" raw_passes=");
  line.append_u64(stats.raw_passes);
  line.append(" sum=");
  line.append_u64(stats.sample_sum);
  line.append(" fnv1a=");
  line.append_hex(stats.sample_fnv1a, 16);
  line.append_char('\n');
  return line.ok() && write_all(2, line.data(), line.size());
}

bool detection_reduced_row(void *context, uint32_t row, const uint32_t *sums,
                           const uint8_t *saturated, uint32_t width,
                           Error *error) {
  DetectionBridge *const bridge = static_cast<DetectionBridge *>(context);
  if (!bridge->started) {
    if (row != 0 ||
        !detector_begin(*bridge->raw_info, kDetectorOptions, error)) {
      return false;
    }
    bridge->started = true;
  }
  return detector_consume_luma_row(nullptr, row, sums, saturated, width,
                                   error);
}

bool discard_row(void *, uint32_t, const uint16_t *, uint32_t, Error *) {
  return true;
}

int analyze_input(InputStream *input, bool detect) {
  Error error = {};
  RawInfo info = {};
  DecodeStats stats = {};
  if (!detect) {
    if (!process_orf(input, discard_row, nullptr, &info, &stats, &error)) {
      print_error("ORF processing failed", error);
      return 3;
    }
    return print_decode_summary(info, stats) ? 0 : 5;
  }

  DetectionBridge bridge = {&info, false};
  if (!process_orf_reduced(input, detection_reduced_row, &bridge, &info,
                           nullptr, &error)) {
    print_error("ORF processing failed", error);
    return 3;
  }
  if (!bridge.started) {
    fail(&error, "ORF decoder produced no rows");
    print_error("detection failed", error);
    return 4;
  }
  uint32_t star_count = 0;
  if (!detector_finish(g_output_stars, &star_count, &error)) {
    print_error("detection failed", error);
    return 4;
  }
  static constexpr char kHeader[] = "x,y,flux\n";
  if (!write_all(1, kHeader, sizeof(kHeader) - 1u)) return 5;
  for (uint32_t index = 0; index < star_count; ++index) {
    if (!print_star(g_output_stars[index])) return 5;
  }
  return 0;
}

int analyze_http_path(const char *path, bool detect) {
  Error error = {};
  HttpInputStream http = {};
  InputStream input = {};
  if (!open_http_input(&http, path, &input, &error)) {
    print_error("could not open isc_file stream", error);
    return 2;
  }
  const int result = analyze_input(&input, detect);
  close_http_input(&http);
  return result;
}

int analyze_direct_path(const char *path, uint8_t slot_index) {
  Error error = {};
  FileIfDirectInput file = {};
  InputStream input = {};
  if (!open_fileif_direct_input(&file, slot_index, path, &input, &error)) {
    print_error("could not open direct fileif stream", error);
    close_fileif_direct_input(&file);
    return 2;
  }
  const int result = analyze_input(&input, true);
  close_fileif_direct_input(&file);
  return result;
}

void print_pending_camera_state() {
  Error state = {};
  if (g_camera.shutter_release_pending()) {
    fail(&state, "camera shutter-release event remains pending");
    print_error("manual recovery required", state);
  }
  if (g_camera.settings_pending_restore()) {
    fail(&state, "camera exposure settings remain unrestored");
    print_error("manual recovery required", state);
  }
  if (g_camera.picture_transfer_cleanup_unverified()) {
    fail(&state, "camera picture-transfer state cleanup is unverified");
    print_error("manual recovery required", state);
  }
  if (g_camera.gate_pending_restore()) {
    fail(&state, "PTP inner gate remains unrestored");
    print_error("manual recovery required", state);
  }
}

int camera_failure(const char *stage, const Error &primary) {
  Error cleanup = {};
  const bool closed = g_camera.close(&cleanup);
  print_error(stage, primary);
  if (!closed) print_error("camera cleanup also failed", cleanup);
  if (!closed) print_pending_camera_state();
  return 6;
}

int capture_and_analyze() {
  static constexpr int16_t kDefaultSettings[3] = {512, 1024, 2304};
  Error error = {};
  CaptureInfo capture = {};
  g_camera.initialize();
  if (!g_camera.open(kDefaultPtpGuidTail, &error))
    return camera_failure("could not open camera control", error);
  if (!g_camera.apply_capture_settings(kDefaultSettings, &error))
    return camera_failure("could not apply capture settings", error);
  if (!g_camera.take_photo(kCaptureTimeoutMilliseconds, &capture, &error))
    return camera_failure("could not take photograph", error);
  if (!g_camera.restore_settings(&error))
    return camera_failure("could not restore camera settings", error);
  if (!g_camera.read_transfer_metadata(&capture, &error))
    return camera_failure("could not read captured ORF metadata", error);
  InputStream input = {};
  if (!g_camera.open_transfer_input(capture, kTransferTimeoutMilliseconds,
                                    &input, &error)) {
    return camera_failure("could not open captured ORF transfer", error);
  }
  const int analysis = analyze_input(&input, true);
  Error transfer_cleanup = {};
  const bool released = g_camera.cancel_transfer_input(&transfer_cleanup);
  Error close_error = {};
  const bool closed = g_camera.close(&close_error);
  if (!released)
    print_error("could not cancel failed ORF transfer", transfer_cleanup);
  if (!closed) print_error("could not close camera control", close_error);
  if (!released || !closed) {
    print_pending_camera_state();
    return 6;
  }
  return analysis;
}

int usage(int fd, int status) {
  static constexpr char kUsage[] =
      "usage:\n"
      "  om1-stars-diag --raw /DCIM/NNNXXXXX/XXXXXXXX.ORF\n"
      "  om1-stars-diag --raw-direct /DCIM/NNNXXXXX/XXXXXXXX.ORF\n"
      "  om1-stars-diag --raw-direct-slot2 /DCIM/NNNXXXXX/XXXXXXXX.ORF\n"
      "  om1-stars-diag --decode-only /DCIM/NNNXXXXX/XXXXXXXX.ORF\n"
      "  om1-stars-diag\n"
      "  om1-stars-diag --help\n";
  (void)write_all(fd, kUsage, sizeof(kUsage) - 1u);
  return status;
}

}  // namespace
}  // namespace om1_stars

extern "C" int main(int argc, char **argv) {
  using namespace om1_stars;
  if (argc == 2 && string_equal(argv[1], "--help")) return usage(1, 0);
  if (argc == 3 && string_equal(argv[1], "--raw"))
    return analyze_http_path(argv[2], true);
  if (argc == 3 && string_equal(argv[1], "--raw-direct"))
    return analyze_direct_path(argv[2], 0);
  if (argc == 3 && string_equal(argv[1], "--raw-direct-slot2"))
    return analyze_direct_path(argv[2], 1);
  if (argc == 3 && string_equal(argv[1], "--decode-only"))
    return analyze_http_path(argv[2], false);
  if (argc == 1) return capture_and_analyze();
  return usage(2, 64);
}
