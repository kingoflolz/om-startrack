/*
 * Unified STAR STATE and Astro Rate v2 GATT companion for the blebridge
 * LD_PRELOAD broker.
 *
 * The stock shim remains the sole libbsa owner. This process translates the
 * public, connection-serialized GATT service to the private framed v11 worker
 * pipe. Camera policy, label resolution, capture, restoration, and state
 * observation all remain in the isolated om1-stars worker. The independent
 * write-only Astro Rate v2 service converts validated Q16.16 body commands
 * directly to the Linux/MAIN mailbox. Legacy public services are not
 * registered; their headers remain only for private worker/sampler ABI reuse.
 */

#define _LARGEFILE64_SOURCE
#define _POSIX_C_SOURCE 200809L

#include "star_telemetry.h"
#include "astro_rate_gatt_service_protocol_v2.h"
#include "astro_preposition_mailbox.h"
#include "bsa_gatt_proxy_client.h"
#include "gyro_attitude_source.h"
#include "star_capture_protocol.h"
#include "star_protocol.h"
#include "star_worker_protocol.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define STAR_ATT_SUCCESS UINT8_C(0x00)
#define STAR_ATT_INVALID_HANDLE UINT8_C(0x01)
#define STAR_ATT_READ_NOT_PERMITTED UINT8_C(0x02)
#define STAR_ATT_WRITE_NOT_PERMITTED UINT8_C(0x03)
#define STAR_ATT_REQUEST_NOT_SUPPORTED UINT8_C(0x06)
#define STAR_ATT_INVALID_OFFSET UINT8_C(0x07)
#define STAR_ATT_INVALID_ATTRIBUTE_LENGTH UINT8_C(0x0d)
#define STAR_ATT_UNLIKELY_ERROR UINT8_C(0x0e)
#define STAR_ATT_VALUE_NOT_ALLOWED UINT8_C(0x13)
#define STAR_ATT_BUSY UINT8_C(0x80)
#define STAR_ATT_CCCD_IMPROPERLY_CONFIGURED UINT8_C(0xfd)

#define STAR_PERMISSION_READ UINT16_C(0x0001)
#define STAR_PERMISSION_WRITE UINT16_C(0x0010)
#define STAR_PROPERTY_READ UINT8_C(0x02)
#define STAR_PROPERTY_WRITE UINT8_C(0x08)
#define STAR_PROPERTY_NOTIFY UINT8_C(0x10)
#define STAR_PROPERTY_INDICATE UINT8_C(0x20)
#define STAR_TRANSPORT_LE UINT8_C(2)
#define STAR_SERVICE_HANDLES UINT16_C(16)
#define ASTRO_RATE_V2_SERVICE_HANDLES UINT16_C(6)
#define STAR_NO_CONNECTION UINT16_MAX
#define STAR_REQUIRED_STATE                                                \
    (BGP_STATE_MGT_OPEN | BGP_STATE_BLE_ENABLED |                         \
     BGP_STATE_STOCK_APP_REGISTERED | BGP_STATE_STOCK_APP_STARTED)

#define STAR_DETECTOR_DEFAULT "/usr/bin/om1-stars"
#define STAR_DETECTOR_LOCK_DEFAULT "/var/run/om1-stars.lock"
#define STAR_PROC_NET_TCP_DEFAULT "/proc/net/tcp"
#define STAR_LAUNCH_ERROR (-1)
#define STAR_LAUNCH_BUSY (-2)
#define STAR_WORKER_ATTEMPT_INTERVAL_MS INT64_C(500)
#define STAR_RECOVERY_ATTEMPT_MAX_MS INT64_C(8000)

#define ASTRO_RATE_MAILBOX_PHYSICAL UINT64_C(0x2a2985fa4)
#define ASTRO_RATE_MAILBOX_PAYLOAD_SIZE 20u
#define ASTRO_RATE_MAILBOX_WHOLE_OFFSET 12u
#define ASTRO_RATE_MAILBOX_TTL_OFFSET 18u
#define ASTRO_RATE_MAILBOX_FOCAL_MIN UINT16_C(1)
#define ASTRO_RATE_MAILBOX_FOCAL_MAX UINT16_C(31368)
#define ASTRO_RATE_MAILBOX_READY_OFFSET ASTRO_RATE_MAILBOX_PAYLOAD_SIZE
#define ASTRO_RATE_MAILBOX_SIZE \
    (ASTRO_PREPOSITION_RATE_RELATIVE_OFFSET + ASTRO_PREPOSITION_MAILBOX_SIZE)
#define ASTRO_RATE_DEVMEM_DEFAULT "/dev/mem"

#define ASTRO_ASSIST_ENABLE_PHYSICAL UINT64_C(0x2a3209010)
#define ASTRO_ASSIST_MIRROR_PHYSICAL UINT64_C(0x2a320b108)
#define ASTRO_ASSIST_MIRROR_ENABLE_MASK UINT8_C(0x08)
#define ASTRO_ASSIST_MIRROR_BYTES 6u
#define ASTRO_ASSIST_IBIS_POLL_MS INT64_C(33)
#define ASTRO_ASSIST_ACCEL_POLL_MS INT64_C(100)
#define ASTRO_ASSIST_ENABLE_TEST_ENV "OM1_ASTRO_ASSIST_ENABLE_FILE"
#define ASTRO_ASSIST_MIRROR_TEST_ENV "OM1_ASTRO_ASSIST_MIRROR_FILE"

/* Stock OM-1 v1.9 main-CPU per_slope ring.  Linux only observes these
 * physical aliases; it never writes or configures the sensor. */
#define ASTRO_ASSIST_ACCEL_SOURCE_PHYSICAL UINT64_C(0x2a034e170)
#define ASTRO_ASSIST_ACCEL_SOURCE_SIZE 0x20u
#define ASTRO_ASSIST_ACCEL_MAPPER_X_OFFSET 0x00u
#define ASTRO_ASSIST_ACCEL_MAPPER_Y_OFFSET 0x04u
#define ASTRO_ASSIST_ACCEL_MAPPER_Z_OFFSET 0x08u
#define ASTRO_ASSIST_ACCEL_SLOT0_OFFSET 0x0cu
#define ASTRO_ASSIST_ACCEL_SLOT_SIZE 6u
#define ASTRO_ASSIST_ACCEL_NEXT_OFFSET 0x18u
#define ASTRO_ASSIST_ACCEL_GENERATION_OFFSET 0x1cu
#define ASTRO_ASSIST_ACCEL_CODE_PHYSICAL UINT64_C(0x2a0080d98)
#define ASTRO_ASSIST_ACCEL_CODE_SIZE 0x224u
#define ASTRO_ASSIST_ACCEL_COMMIT_ANCHOR_OFFSET 0x000u
#define ASTRO_ASSIST_ACCEL_REMAP_ANCHOR_OFFSET 0x204u
#define ASTRO_ASSIST_ACCEL_STALE_MS INT64_C(500)
#define ASTRO_ASSIST_ACCEL_SOURCE_TEST_ENV \
    "OM1_ASTRO_ASSIST_ACCEL_SOURCE_FILE"
#define ASTRO_ASSIST_ACCEL_CODE_TEST_ENV \
    "OM1_ASTRO_ASSIST_ACCEL_CODE_FILE"

#if STAR_GATT_MAX_PEAKS != STAR_WORKER_MAX_PEAKS
#error "STAR GATT and worker peak ceilings disagree"
#endif
#if STAR_GATT_SETTING_COUNT != STAR_WORKER_SETTING_COUNT
#error "STAR GATT and worker setting counts disagree"
#endif
#if STAR_GATT_SETTING_LABEL_MAX != STAR_WORKER_SETTING_LABEL_MAX
#error "STAR GATT and worker label bounds disagree"
#endif
#if STAR_GATT_LENS_NAME_MAX != STAR_WORKER_LENS_NAME_MAX
#error "STAR GATT and worker lens bounds disagree"
#endif
#if STAR_GATT_RECIPE_MIN_SIZE != STAR_WORKER_SET_PAYLOAD_MIN || \
    STAR_GATT_RECIPE_MAX_SIZE != STAR_WORKER_SET_PAYLOAD_MAX
#error "STAR public and worker label tuple bounds disagree"
#endif
#if ASTRO_ASSIST_GATT_DETECTOR_CONFIG_SIZE != \
        STAR_WORKER_DETECTOR_OPTIONS_SIZE || \
    STAR_WORKER_CAPTURE_PAYLOAD_MIN !=                         \
        STAR_WORKER_DETECTOR_OPTIONS_SIZE + STAR_GATT_RECIPE_MIN_SIZE || \
    STAR_WORKER_CAPTURE_PAYLOAD_MAX !=                         \
        STAR_WORKER_DETECTOR_OPTIONS_SIZE + STAR_GATT_RECIPE_MAX_SIZE
#error "Astro Assist config and worker capture bounds disagree"
#endif
#if STAR_GATT_CATALOG_MAX_SIZE != STAR_WORKER_CATALOG_VALUE_MAX
#error "STAR public and worker catalog bounds disagree"
#endif
#if STAR_GATT_STATE_MIN_SIZE != STAR_WORKER_STATE_VALUE_MIN || \
    STAR_GATT_STATE_MAX_SIZE != STAR_WORKER_STATE_VALUE_MAX
#error "STAR public and worker state bounds disagree"
#endif
#if STAR_GATT_PEAKS_PREFIX_SIZE +                                  \
        STAR_GATT_PEAKS_MAX_PER_VALUE * STAR_GATT_PEAK_WIRE_SIZE > \
    STAR_GATT_ATT_ATTRIBUTE_VALUE_MAX
#error "STAR peak value exceeds the ATT Attribute Value ceiling"
#endif
#if STAR_GATT_DONE_OK_MAX_SIZE > STAR_GATT_ATT_ATTRIBUTE_VALUE_MAX
#error "STAR DONE value exceeds the ATT Attribute Value ceiling"
#endif

_Static_assert(STAR_STATE_LENS_OFFSET ==
                   STAR_STATE_PREFIX_SIZE,
               "STAR fixed STATE prefix changed");
_Static_assert(STAR_STATE_PREFIX_SIZE +
                       STAR_LENS_MAX_SIZE ==
                   STAR_STATE_PREFIX_MAX_SIZE,
               "STAR maximum STATE size changed");
_Static_assert(STAR_STATE_MAX_SIZE +
                       STAR_GATT_ATT_VALUE_OVERHEAD ==
                   STAR_MIN_ATT_MTU,
               "STAR minimum MTU no longer fits maximum STATE");
_Static_assert(STAR_WRITE_DETECTOR_OFFSET +
                       ASTRO_ASSIST_GATT_DETECTOR_CONFIG_SIZE ==
                   STAR_STATE_WRITE_SIZE,
               "STAR detector must terminate STATE write");
_Static_assert(STAR_STATE_MAX_SIZE == 157u &&
                   STAR_MIN_ATT_MTU == 160u,
               "STAR attitude trailer changed the wire bounds");

_Static_assert(ASTRO_RATE_GATT_V2_VALUE_SIZE == 18u,
               "Astro Rate v2 value size changed");
_Static_assert(ASTRO_RATE_GATT_V2_GAVE1_OFFSET + sizeof(int16_t) ==
                   ASTRO_RATE_GATT_V2_VALUE_SIZE,
               "Astro Rate v2 GAVE lanes must terminate the value");
_Static_assert(ASTRO_RATE_MAILBOX_TTL_OFFSET + sizeof(uint16_t) ==
                   ASTRO_RATE_MAILBOX_PAYLOAD_SIZE,
               "Native RATE TTL must terminate the command body");
_Static_assert((ASTRO_RATE_MAILBOX_PHYSICAL & UINT64_C(3)) == 0u,
               "Astro Rate mailbox must support aligned u32 stores");
_Static_assert(ASTRO_ASSIST_GATT_IBIS_ROLL_Q10_OFFSET + sizeof(int16_t) ==
                   ASTRO_ASSIST_GATT_IBIS_STATE_SIZE,
               "Astro Assist IBIS lanes must terminate the value");
_Static_assert(ASTRO_ASSIST_GATT_ACCEL_Z_MG_OFFSET + sizeof(int16_t) ==
                   ASTRO_ASSIST_GATT_FOCUS_RESOURCE_FLAGS_OFFSET,
               "Astro Assist acceleration lanes must precede legacy focus state");
_Static_assert(ASTRO_ASSIST_GATT_FOCUS_RESOURCE_STATE_OFFSET +
                       sizeof(uint8_t) ==
                   ASTRO_ASSIST_GATT_ACCELERATION_STATE_SIZE,
               "Astro Assist legacy focus state must terminate the value");
_Static_assert(((ASTRO_ASSIST_ACCEL_SOURCE_PHYSICAL +
                 ASTRO_ASSIST_ACCEL_GENERATION_OFFSET) &
                UINT64_C(3)) == 0u,
               "Astro Assist acceleration generation must be u32 aligned");
_Static_assert(STAR_STATE_LENS_COMMAND_GENERATION_OFFSET +
                       sizeof(uint32_t) ==
                   STAR_STATE_LENS_MOVESTEP_OFFSET,
               "STAR lens command generation must precede its lanes");
_Static_assert(STAR_STATE_LENS_MOVESTEP_OFFSET +
                       2u * sizeof(int16_t) ==
                   STAR_STATE_ACCEL_FLAGS_OFFSET,
               "STAR lens command lanes must terminate the IBIS block");
_Static_assert(STAR_STATE_ACCEL_MG_OFFSET +
                       3u * sizeof(int16_t) ==
                   STAR_STATE_LENS_LENGTH_OFFSET,
               "STAR acceleration lanes must precede lens length");
_Static_assert(STAR_STATE_LENS_LENGTH_OFFSET + sizeof(uint8_t) ==
                   STAR_STATE_PREFIX_SIZE,
               "STAR lens length must terminate the fixed STATE prefix");
_Static_assert(STAR_IBIS_Q10_SOURCE_MAPPED ==
                   ASTRO_ASSIST_GATT_IBIS_SOURCE_MAPPED,
               "STAR and private Q10 source flags disagree");
_Static_assert(ASTRO_ASSIST_GATT_CONFIG_BORDER_MARGIN_OFFSET +
                       sizeof(uint16_t) ==
                   ASTRO_ASSIST_GATT_DETECTOR_CONFIG_SIZE,
               "Astro Assist config fields must terminate the value");
_Static_assert(ASTRO_ASSIST_GATT_KEEP_AWAKE_TTL_SECONDS_OFFSET +
                       sizeof(uint16_t) ==
                   ASTRO_ASSIST_GATT_KEEP_AWAKE_LEASE_SIZE,
               "Astro Assist keep-awake TTL must terminate the value");

static volatile sig_atomic_t stop_requested;
extern char **environ;

/* Canonical UUID byte order. BGP1 performs the target BSA conversion. */
static const uint8_t application_uuid_bytes[16] = {
    0x7e, 0x6d, 0x7a, 0x50, 0x2c, 0x56, 0x4f, 0x17,
    0x9c, 0x51, 0x9a, 0x5f, 0x64, 0xb0, 0x24, 0x00
};
static const uint8_t service_uuid_bytes[16] = {
    0x7e, 0x6d, 0x7a, 0x50, 0x2c, 0x56, 0x4f, 0x17,
    0x9c, 0x51, 0x9a, 0x5f, 0x64, 0xb0, 0x24, 0x50
};
static const uint8_t control_uuid_bytes[16] = {
    0x7e, 0x6d, 0x7a, 0x50, 0x2c, 0x56, 0x4f, 0x17,
    0x9c, 0x51, 0x9a, 0x5f, 0x64, 0xb0, 0x24, 0x51
};
static const uint8_t state_uuid_bytes[16] = {
    0x7e, 0x6d, 0x7a, 0x50, 0x2c, 0x56, 0x4f, 0x17,
    0x9c, 0x51, 0x9a, 0x5f, 0x64, 0xb0, 0x24, 0x52
};
static const uint8_t catalog_uuid_bytes[STAR_GATT_SETTING_COUNT][16] = {
    {0x7e, 0x6d, 0x7a, 0x50, 0x2c, 0x56, 0x4f, 0x17,
     0x9c, 0x51, 0x9a, 0x5f, 0x64, 0xb0, 0x24, 0x53},
    {0x7e, 0x6d, 0x7a, 0x50, 0x2c, 0x56, 0x4f, 0x17,
     0x9c, 0x51, 0x9a, 0x5f, 0x64, 0xb0, 0x24, 0x54},
    {0x7e, 0x6d, 0x7a, 0x50, 0x2c, 0x56, 0x4f, 0x17,
     0x9c, 0x51, 0x9a, 0x5f, 0x64, 0xb0, 0x24, 0x55}
};
static const uint8_t lease_uuid_bytes[16] = {
    0x7e, 0x6d, 0x7a, 0x50, 0x2c, 0x56, 0x4f, 0x17,
    0x9c, 0x51, 0x9a, 0x5f, 0x64, 0xb0, 0x24, 0x56
};
static const uint8_t astro_rate_v2_service_uuid_bytes[16] = {
    0x7e, 0x6d, 0x7a, 0x50, 0x2c, 0x56, 0x4f, 0x17,
    0x9c, 0x51, 0x9a, 0x5f, 0x64, 0xb0, 0x24, 0x40
};
static const uint8_t astro_rate_v2_value_uuid_bytes[16] = {
    0x7e, 0x6d, 0x7a, 0x50, 0x2c, 0x56, 0x4f, 0x17,
    0x9c, 0x51, 0x9a, 0x5f, 0x64, 0xb0, 0x24, 0x41
};
static const uint8_t astro_preposition_uuid_bytes[16] = {
    0x7e, 0x6d, 0x7a, 0x50, 0x2c, 0x56, 0x4f, 0x17,
    0x9c, 0x51, 0x9a, 0x5f, 0x64, 0xb0, 0x24, 0x42
};

struct astro_rate_mailbox {
    int fd;
    void *mapping;
    size_t mapping_size;
    volatile uint8_t *bytes;
    uint8_t queued_value[ASTRO_RATE_MAILBOX_PAYLOAD_SIZE];
    int queued;
    int cancel_pending;
    uint8_t inflight;
    int inflight_preposition_cancel;
    uint16_t last_valid_focal_uq11_5;
    int last_valid_focal_available;
    int active;
};

struct astro_preposition_request {
    int active;
    int staged_intent;
    int capture_owned;
    int queued;
    uint8_t queued_value[ASTRO_PREPOSITION_NATIVE_SIZE];
    int cancel_pending;
    uint32_t pending_cookie;
    int pending_failed;
    int pending_published;
    int64_t deadline_ms;
};

struct read_only_mapping {
    int fd;
    void *mapping;
    size_t mapping_size;
    const volatile uint8_t *bytes;
};

struct astro_assist_ibis {
    struct read_only_mapping enable;
    struct read_only_mapping mirror;
    uint8_t value[ASTRO_ASSIST_GATT_IBIS_STATE_SIZE];
    uint8_t baseline[ASTRO_ASSIST_MIRROR_BYTES];
    uint16_t sequence;
    int baseline_valid;
    int notify_enabled;
    int dirty;
    int64_t next_poll_ms;
};

struct astro_assist_acceleration {
    struct read_only_mapping source;
    struct read_only_mapping code;
    uint8_t value[ASTRO_ASSIST_GATT_ACCELERATION_STATE_SIZE];
    uint16_t linux_sequence;
    uint32_t observed_generation;
    int observed_generation_valid;
    int layout_qualified;
    int liveness_observed;
    int notify_enabled;
    int dirty;
    int64_t last_advance_ms;
    int64_t next_poll_ms;
};

struct astro_assist_keep_awake {
    int active;
    int in_flight;
    int64_t deadline_ms;
    int64_t next_rearm_ms;
};

struct gyro_attitude_telemetry {
    struct read_only_mapping mirror;
    struct read_only_mapping lease;
    struct gyro_attitude_source source;
    int lease_active;
    int64_t next_poll_ms;
    int64_t next_lease_ms;
};

struct label_ref {
    const uint8_t *bytes;
    uint8_t length;
};

struct label_tuple_view {
    struct label_ref label[STAR_GATT_SETTING_COUNT];
};

struct state_view {
    struct label_ref current[STAR_GATT_SETTING_COUNT];
    struct label_ref aperture_min;
    struct label_ref aperture_max;
};

struct public_value {
    int valid;
    uint16_t value_len;
    uint8_t value[STAR_GATT_ATT_ATTRIBUTE_VALUE_MAX];
};

struct public_catalog_set {
    int valid;
    struct public_value kind[STAR_GATT_SETTING_COUNT];
};

struct public_recipe {
    int valid;
    uint16_t value_len;
    uint8_t value[STAR_GATT_RECIPE_MAX_SIZE];
};

struct public_read_snapshot {
    int valid;
    uint16_t attr_id;
    uint16_t value_len;
    uint8_t value[STAR_GATT_ATT_ATTRIBUTE_VALUE_MAX];
};

struct pending_state_write {
    int active;
    struct public_recipe live_target;
    struct public_recipe recipe_target;
    uint8_t detector[ASTRO_ASSIST_GATT_DETECTOR_CONFIG_SIZE];
};

enum star_worker_lifecycle {
    STAR_WORKER_ABSENT = 0,
    STAR_WORKER_STARTING,
    STAR_WORKER_RUNNING,
    STAR_WORKER_DRAINING
};

enum star_operation_phase {
    STAR_OPERATION_IDLE = 0,
    STAR_OPERATION_SET_PENDING,
    STAR_OPERATION_CAPTURE_PENDING,
    STAR_OPERATION_CAPTURE_STREAM
};

enum star_stream_phase {
    STAR_STREAM_NONE = 0,
    STAR_STREAM_PEAK_READY,
    STAR_STREAM_WAIT_PEAK_CONFIRM,
    STAR_STREAM_DONE_READY,
    STAR_STREAM_WAIT_DONE_CONFIRM,
    STAR_STREAM_STALLED
};

struct detector_worker {
    pid_t pid;
    int command_fd;
    int event_fd;
    int lease_fd;
    enum star_worker_lifecycle lifecycle;
    int startup_complete;
    int restart_suppressed;
    uint8_t frame[STAR_WORKER_MAX_FRAME_SIZE];
    size_t frame_len;
    size_t frame_expected;
};

struct capture_stream {
    uint8_t outcome;
    uint16_t total;
    uint16_t next_peak;
    uint8_t pending_peak_count;
    int16_t settings[STAR_GATT_SETTING_COUNT];
    uint32_t focal_length_f32_bits;
    uint8_t lens_name_length;
    char lens_name[STAR_GATT_LENS_NAME_MAX];
    struct star_worker_peak peaks[STAR_GATT_MAX_PEAKS];
};

struct star_service {
    struct bsa_gatt_proxy_client *client;
    uint32_t expected_epoch;
    uint16_t control_attr;
    uint16_t control_cccd_attr;
    uint16_t state_attr;
    uint16_t state_cccd_attr;
    uint16_t catalog_attr[STAR_GATT_SETTING_COUNT];
    uint16_t recipe_attr;
    uint16_t astro_rate_v2_attr;
    uint16_t astro_preposition_attr;
    uint16_t astro_assist_config_attr;
    uint16_t astro_assist_ibis_attr;
    uint16_t astro_assist_ibis_cccd_attr;
    uint16_t astro_assist_acceleration_attr;
    uint16_t astro_assist_acceleration_cccd_attr;
    uint16_t astro_assist_keep_awake_attr;
    uint16_t active_conn;
    uint16_t active_mtu;
    int control_indicate_enabled;
    int state_notify_enabled;
    int congested;
    int64_t next_worker_attempt_ms;
    int ptp_listener_wait_reported;
    int capture_recovery_pending;
    int capture_recovery_repairing;
    int capture_recovery_restore_in_flight;
    int64_t capture_recovery_attempt_ms;
    uint32_t worker_generation;
    uint64_t capture_sequence;
    uint64_t unlikely_read_count;
    uint32_t publication_generation;
    uint32_t config_generation;
    uint32_t catalog_generation;
    int detector_config_valid;
    int64_t last_state_notify_ms;
    int state_dirty;
    uint8_t capture_state_reason;
    enum star_operation_phase operation;
    enum star_stream_phase stream;
    struct public_value state;
    struct public_value unified_state;
    struct public_catalog_set catalog;
    struct public_catalog_set staged_catalog;
    struct public_recipe recipe;
    struct public_value capture_recovery_state;
    struct public_catalog_set capture_recovery_catalog;
    struct public_recipe capture_recovery_recipe;
    struct public_recipe capture_recovery_tuple;
    struct public_value capture_recovery_observed_state;
    struct public_catalog_set capture_recovery_observed_catalog;
    struct public_read_snapshot read_snapshot;
    struct pending_state_write pending_state_write;
    struct capture_stream capture;
    struct detector_worker worker;
    struct astro_rate_mailbox astro_rate;
    struct astro_preposition_request preposition;
    uint8_t detector_config[ASTRO_ASSIST_GATT_DETECTOR_CONFIG_SIZE];
    struct astro_assist_ibis astro_assist;
    struct astro_assist_acceleration acceleration;
    struct astro_assist_keep_awake keep_awake;
    struct gyro_attitude_telemetry gyro;
};

static void pump_control_stream(struct star_service *service);
static void maybe_publish_state(struct star_service *service);
static void maybe_publish_astro_assist(struct star_service *service);
static void close_fd(int *fd);
static int worker_accepts_command(const struct star_service *service);
static int validate_state_value(const uint8_t *value, uint16_t value_len,
                                const struct public_catalog_set *catalog,
                                struct state_view *view);

static void handle_signal(int signal_number)
{
    (void)signal_number;
    stop_requested = 1;
}

static int64_t milliseconds_now(void)
{
    struct timespec now;

    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0)
        return -1;
    return (int64_t)now.tv_sec * INT64_C(1000) +
           now.tv_nsec / INT64_C(1000000);
}

static void initialize_astro_assist_keep_awake(
    struct astro_assist_keep_awake *keep_awake)
{
    memset(keep_awake, 0, sizeof(*keep_awake));
}

static void cancel_astro_assist_keep_awake(
    struct astro_assist_keep_awake *keep_awake, const char *reason)
{
    if (keep_awake->active && reason != NULL)
        printf("ASTRO ASSIST keep-awake lease cancelled: %s\n", reason);
    keep_awake->active = 0;
    keep_awake->deadline_ms = 0;
    keep_awake->next_rearm_ms = 0;
}

static void reset_astro_assist_keep_awake_worker_attempt(
    struct astro_assist_keep_awake *keep_awake)
{
    keep_awake->in_flight = 0;
    if (keep_awake->active)
        keep_awake->next_rearm_ms = 0;
}

static void close_astro_assist_keep_awake(
    struct astro_assist_keep_awake *keep_awake)
{
    cancel_astro_assist_keep_awake(keep_awake, NULL);
    initialize_astro_assist_keep_awake(keep_awake);
}

static uint16_t get_le16(const uint8_t *bytes)
{
    return (uint16_t)((uint16_t)bytes[0] | ((uint16_t)bytes[1] << 8));
}

static uint32_t get_le32(const uint8_t *bytes)
{
    return (uint32_t)bytes[0] | ((uint32_t)bytes[1] << 8) |
           ((uint32_t)bytes[2] << 16) | ((uint32_t)bytes[3] << 24);
}

static void put_le16(uint8_t *bytes, uint16_t value)
{
    bytes[0] = (uint8_t)value;
    bytes[1] = (uint8_t)(value >> 8);
}

static void put_le32(uint8_t *bytes, uint32_t value)
{
    bytes[0] = (uint8_t)value;
    bytes[1] = (uint8_t)(value >> 8);
    bytes[2] = (uint8_t)(value >> 16);
    bytes[3] = (uint8_t)(value >> 24);
}

static int bytes_zero(const uint8_t *bytes, size_t size)
{
    size_t index;

    for (index = 0u; index < size; ++index) {
        if (bytes[index] != 0u)
            return 0;
    }
    return 1;
}

static void initialize_astro_rate_mailbox(struct astro_rate_mailbox *mailbox)
{
    memset(mailbox, 0, sizeof(*mailbox));
    mailbox->fd = -1;
    mailbox->mapping = MAP_FAILED;
}

static void close_astro_rate_mailbox(struct astro_rate_mailbox *mailbox)
{
    if (mailbox->mapping != MAP_FAILED) {
        (void)munmap(mailbox->mapping, mailbox->mapping_size);
        mailbox->mapping = MAP_FAILED;
    }
    close_fd(&mailbox->fd);
    mailbox->bytes = NULL;
    mailbox->mapping_size = 0u;
    mailbox->last_valid_focal_uq11_5 = 0u;
    mailbox->last_valid_focal_available = 0;
    mailbox->active = 0;
}

static int open_astro_rate_mailbox(struct astro_rate_mailbox *mailbox)
{
    const char *test_path = getenv("OM1_ASTRO_RATE_MAILBOX_FILE");
    const char *path = ASTRO_RATE_DEVMEM_DEFAULT;
    struct stat status;
    off64_t mapping_offset;
    size_t byte_offset;
    size_t mapping_size;
    long page_size;
    int flags = O_RDWR | O_CLOEXEC;

    initialize_astro_rate_mailbox(mailbox);
    if (test_path != NULL && test_path[0] != '\0') {
        path = test_path;
        flags |= O_NOFOLLOW;
        mapping_offset = (off64_t)0;
        byte_offset = 0u;
        mapping_size = ASTRO_RATE_MAILBOX_SIZE;
    } else {
        uint64_t page_mask;
        uint64_t physical_page;

        page_size = sysconf(_SC_PAGESIZE);
        if (page_size <= 0 || ((uint64_t)page_size &
                              ((uint64_t)page_size - UINT64_C(1))) != 0u) {
            errno = EINVAL;
            return -1;
        }
        page_mask = (uint64_t)page_size - UINT64_C(1);
        physical_page = ASTRO_RATE_MAILBOX_PHYSICAL & ~page_mask;
        byte_offset = (size_t)(ASTRO_RATE_MAILBOX_PHYSICAL - physical_page);
        if (byte_offset + ASTRO_RATE_MAILBOX_SIZE > (size_t)page_size ||
            physical_page > (uint64_t)INT64_MAX) {
            errno = EOVERFLOW;
            return -1;
        }
        mapping_offset = (off64_t)physical_page;
        mapping_size = (size_t)page_size;
        flags |= O_SYNC;
    }

    mailbox->fd = open(path, flags);
    if (mailbox->fd < 0)
        return -1;
    if (fstat(mailbox->fd, &status) != 0)
        goto fail;
    if (test_path != NULL && test_path[0] != '\0') {
        if (!S_ISREG(status.st_mode) || status.st_uid != geteuid() ||
            (status.st_mode & 0022) != 0 ||
            status.st_size < (off_t)ASTRO_RATE_MAILBOX_SIZE) {
            errno = EPERM;
            goto fail;
        }
    } else if (!S_ISCHR(status.st_mode)) {
        errno = ENODEV;
        goto fail;
    }

    mailbox->mapping = mmap64(NULL, mapping_size, PROT_READ | PROT_WRITE,
                              MAP_SHARED, mailbox->fd, mapping_offset);
    if (mailbox->mapping == MAP_FAILED)
        goto fail;
    mailbox->mapping_size = mapping_size;
    mailbox->bytes =
        (volatile uint8_t *)mailbox->mapping + byte_offset;
    return 0;

fail:
    {
        int saved_errno = errno;

        close_astro_rate_mailbox(mailbox);
        errno = saved_errno;
    }
    return -1;
}

static void initialize_read_only_mapping(struct read_only_mapping *mapping)
{
    memset(mapping, 0, sizeof(*mapping));
    mapping->fd = -1;
    mapping->mapping = MAP_FAILED;
}

static void close_read_only_mapping(struct read_only_mapping *mapping)
{
    if (mapping->mapping != MAP_FAILED) {
        (void)munmap(mapping->mapping, mapping->mapping_size);
        mapping->mapping = MAP_FAILED;
    }
    close_fd(&mapping->fd);
    mapping->mapping_size = 0u;
    mapping->bytes = NULL;
}

static int open_memory_mapping(struct read_only_mapping *mapping,
                               const char *test_path,
                               uint64_t physical_address,
                               size_t required_size, int writable)
{
    const char *path = ASTRO_RATE_DEVMEM_DEFAULT;
    struct stat status;
    off64_t mapping_offset;
    size_t byte_offset;
    size_t mapping_size;
    int flags = (writable ? O_RDWR : O_RDONLY) | O_CLOEXEC;

    initialize_read_only_mapping(mapping);
    if (test_path != NULL) {
        path = test_path;
        flags |= O_NOFOLLOW;
        mapping_offset = (off64_t)0;
        byte_offset = 0u;
        mapping_size = required_size;
    } else {
        uint64_t page_mask;
        uint64_t physical_page;
        long page_size = sysconf(_SC_PAGESIZE);

        if (page_size <= 0 ||
            ((uint64_t)page_size &
             ((uint64_t)page_size - UINT64_C(1))) != 0u) {
            errno = EINVAL;
            return -1;
        }
        page_mask = (uint64_t)page_size - UINT64_C(1);
        physical_page = physical_address & ~page_mask;
        byte_offset = (size_t)(physical_address - physical_page);
        if (byte_offset + required_size > (size_t)page_size ||
            physical_page > (uint64_t)INT64_MAX) {
            errno = EOVERFLOW;
            return -1;
        }
        mapping_offset = (off64_t)physical_page;
        mapping_size = (size_t)page_size;
        flags |= O_SYNC;
    }

    mapping->fd = open(path, flags);
    if (mapping->fd < 0)
        return -1;
    if (fstat(mapping->fd, &status) != 0)
        goto fail;
    if (test_path != NULL) {
        if (!S_ISREG(status.st_mode) || status.st_uid != geteuid() ||
            (status.st_mode & 0022) != 0 ||
            status.st_size < (off_t)required_size) {
            errno = EPERM;
            goto fail;
        }
    } else if (!S_ISCHR(status.st_mode)) {
        errno = ENODEV;
        goto fail;
    }

    mapping->mapping = mmap64(NULL, mapping_size,
                              PROT_READ | (writable ? PROT_WRITE : 0), MAP_SHARED,
                              mapping->fd, mapping_offset);
    if (mapping->mapping == MAP_FAILED)
        goto fail;
    mapping->mapping_size = mapping_size;
    mapping->bytes =
        (const volatile uint8_t *)mapping->mapping + byte_offset;
    return 0;

fail:
    {
        int saved_errno = errno;

        close_read_only_mapping(mapping);
        errno = saved_errno;
    }
    return -1;
}

static int open_read_only_mapping(struct read_only_mapping *mapping,
                                  const char *test_path,
                                  uint64_t physical_address,
                                  size_t required_size)
{
    return open_memory_mapping(mapping, test_path, physical_address,
                               required_size, 0);
}

static void initialize_gyro_attitude(struct gyro_attitude_telemetry *gyro)
{
    memset(gyro, 0, sizeof(*gyro));
    initialize_read_only_mapping(&gyro->mirror);
    initialize_read_only_mapping(&gyro->lease);
    gyro_attitude_source_reset(&gyro->source);
}

static void reset_gyro_attitude_session(struct gyro_attitude_telemetry *gyro)
{
    gyro_attitude_source_reset(&gyro->source);
    gyro->next_poll_ms = 0;
}

static int open_gyro_attitude(struct gyro_attitude_telemetry *gyro)
{
    const char *mirror = getenv("OM1_GYRO_ATTITUDE_MIRROR_FILE");
    const char *lease = getenv("OM1_GYRO_ATTITUDE_LEASE_FILE");

    if (mirror != NULL && mirror[0] == '\0')
        mirror = NULL;
    if (lease != NULL && lease[0] == '\0')
        lease = NULL;
    if ((mirror == NULL) != (lease == NULL)) {
        errno = EINVAL;
        return -1;
    }
    if (open_read_only_mapping(&gyro->mirror, mirror,
                               GYRO_ATTITUDE_MIRROR_PHYSICAL,
                               GYRO_ATTITUDE_MIRROR_SIZE) != 0)
        return -1;
    if (open_memory_mapping(&gyro->lease, lease,
                            GYRO_ATTITUDE_LEASE_PHYSICAL,
                            GYRO_ATTITUDE_LEASE_SIZE, 1) != 0) {
        int saved_errno = errno;

        close_read_only_mapping(&gyro->mirror);
        errno = saved_errno;
        return -1;
    }
    return 0;
}

/* GYL1 is installed by the native patch. Never write the stock erased cave.
 * Publishing a new heartbeat commits an enabled lease; native owns its TTL. */
static int write_gyro_attitude_lease(struct gyro_attitude_telemetry *gyro,
                                     int enabled)
{
    volatile uint32_t *words = (volatile uint32_t *)gyro->lease.bytes;
    uint32_t heartbeat;

    if (words == NULL || words[0] != GYRO_ATTITUDE_LEASE_MAGIC || words[3] != 0u)
        return 0;
    if (!enabled) {
        words[2] = 0u;
        __sync_synchronize();
        gyro->lease_active = 0;
        gyro->next_lease_ms = 0;
        return 1;
    }
    heartbeat = words[1] + 1u;
    if (heartbeat == 0u)
        heartbeat = 1u;
    words[2] = 1u;
    __sync_synchronize();
    words[1] = heartbeat;
    __sync_synchronize();
    gyro->lease_active = 1;
    return 1;
}

static void close_gyro_attitude(struct gyro_attitude_telemetry *gyro)
{
    (void)write_gyro_attitude_lease(gyro, 0);
    close_read_only_mapping(&gyro->lease);
    close_read_only_mapping(&gyro->mirror);
    initialize_gyro_attitude(gyro);
}

static void sample_gyro_attitude(struct gyro_attitude_telemetry *gyro)
{
    (void)gyro_attitude_source_sample(&gyro->source, gyro->mirror.bytes,
                                     milliseconds_now());
}

static void update_gyro_attitude_lease(struct star_service *service)
{
    struct gyro_attitude_telemetry *gyro = &service->gyro;
    int64_t now = milliseconds_now();
    int enabled = now >= 0 && service->active_conn != STAR_NO_CONNECTION &&
        service->state_notify_enabled && service->keep_awake.active &&
        now < service->keep_awake.deadline_ms;
    int was_active = gyro->lease_active;

    if (!enabled) {
        if (was_active)
            (void)write_gyro_attitude_lease(gyro, 0);
    } else if (!was_active || now >= gyro->next_lease_ms) {
        if (write_gyro_attitude_lease(gyro, 1)) {
            gyro->next_lease_ms = now + GYRO_ATTITUDE_POLL_MS;
            if (!was_active)
                service->keep_awake.next_rearm_ms = 0;
        }
    }
}

static void initialize_astro_assist_ibis(struct astro_assist_ibis *ibis)
{
    memset(ibis, 0, sizeof(*ibis));
    initialize_read_only_mapping(&ibis->enable);
    initialize_read_only_mapping(&ibis->mirror);
    ibis->value[ASTRO_ASSIST_GATT_IBIS_VERSION_OFFSET] =
        ASTRO_ASSIST_GATT_VERSION;
}

static void close_astro_assist_ibis(struct astro_assist_ibis *ibis)
{
    close_read_only_mapping(&ibis->mirror);
    close_read_only_mapping(&ibis->enable);
    initialize_astro_assist_ibis(ibis);
}

static int open_astro_assist_ibis(struct astro_assist_ibis *ibis)
{
    const char *enable_test = getenv(ASTRO_ASSIST_ENABLE_TEST_ENV);
    const char *mirror_test = getenv(ASTRO_ASSIST_MIRROR_TEST_ENV);

    initialize_astro_assist_ibis(ibis);
    if (enable_test != NULL && enable_test[0] == '\0')
        enable_test = NULL;
    if (mirror_test != NULL && mirror_test[0] == '\0')
        mirror_test = NULL;
    if ((enable_test == NULL) != (mirror_test == NULL)) {
        errno = EINVAL;
        return -1;
    }
    if (open_read_only_mapping(&ibis->enable, enable_test,
                               ASTRO_ASSIST_ENABLE_PHYSICAL, 1u) != 0)
        return -1;
    if (open_read_only_mapping(&ibis->mirror, mirror_test,
                               ASTRO_ASSIST_MIRROR_PHYSICAL,
                               ASTRO_ASSIST_MIRROR_BYTES) != 0) {
        int saved_errno = errno;

        close_read_only_mapping(&ibis->enable);
        errno = saved_errno;
        return -1;
    }
    ibis->value[ASTRO_ASSIST_GATT_IBIS_FLAGS_OFFSET] =
        ASTRO_ASSIST_GATT_IBIS_SOURCE_MAPPED;
    return 0;
}

static int astro_assist_source_mapped(const struct astro_assist_ibis *ibis)
{
    return ibis->enable.bytes != NULL && ibis->mirror.bytes != NULL;
}

static void copy_volatile_bytes(uint8_t *destination,
                                const volatile uint8_t *source,
                                size_t size)
{
    size_t index;

    for (index = 0u; index < size; ++index)
        destination[index] = source[index];
}

extern uint8_t const astro_assist_acceleration_commit_anchor[32];
extern uint8_t const astro_assist_acceleration_remap_anchor[32];
static void initialize_astro_assist_acceleration(
    struct astro_assist_acceleration *acceleration)
{
    memset(acceleration, 0, sizeof(*acceleration));
    initialize_read_only_mapping(&acceleration->source);
    initialize_read_only_mapping(&acceleration->code);
    acceleration->value[ASTRO_ASSIST_GATT_ACCEL_VERSION_OFFSET] =
        ASTRO_ASSIST_GATT_VERSION;
}

static void close_astro_assist_acceleration(
    struct astro_assist_acceleration *acceleration)
{
    close_read_only_mapping(&acceleration->code);
    close_read_only_mapping(&acceleration->source);
    initialize_astro_assist_acceleration(acceleration);
}

static int astro_assist_acceleration_source_mapped(
    const struct astro_assist_acceleration *acceleration)
{
    return acceleration->source.bytes != NULL;
}

static uint8_t astro_assist_acceleration_base_flags(
    const struct astro_assist_acceleration *acceleration)
{
    uint8_t flags = 0u;

    if (astro_assist_acceleration_source_mapped(acceleration))
        flags |= ASTRO_ASSIST_GATT_ACCEL_SOURCE_MAPPED;
    if (acceleration->layout_qualified)
        flags |= ASTRO_ASSIST_GATT_ACCEL_LAYOUT_QUALIFIED;
    return flags;
}

static int qualify_astro_assist_acceleration_layout(
    const struct astro_assist_acceleration *acceleration)
{
    uint8_t mapper[12];
    uint8_t commit_anchor[sizeof(astro_assist_acceleration_commit_anchor)];
    uint8_t remap_anchor[sizeof(astro_assist_acceleration_remap_anchor)];

    if (acceleration->source.bytes == NULL || acceleration->code.bytes == NULL)
        return 0;
    copy_volatile_bytes(mapper, acceleration->source.bytes, sizeof(mapper));
    copy_volatile_bytes(
        commit_anchor,
        acceleration->code.bytes + ASTRO_ASSIST_ACCEL_COMMIT_ANCHOR_OFFSET,
        sizeof(commit_anchor));
    copy_volatile_bytes(
        remap_anchor,
        acceleration->code.bytes + ASTRO_ASSIST_ACCEL_REMAP_ANCHOR_OFFSET,
        sizeof(remap_anchor));
    __sync_synchronize();
    return (int32_t)get_le32(mapper + ASTRO_ASSIST_ACCEL_MAPPER_X_OFFSET) ==
               INT32_C(1) &&
           (int32_t)get_le32(mapper + ASTRO_ASSIST_ACCEL_MAPPER_Y_OFFSET) ==
               INT32_C(-2) &&
           (int32_t)get_le32(mapper + ASTRO_ASSIST_ACCEL_MAPPER_Z_OFFSET) ==
               INT32_C(3) &&
           memcmp(commit_anchor, astro_assist_acceleration_commit_anchor,
                  sizeof(commit_anchor)) == 0 &&
           memcmp(remap_anchor, astro_assist_acceleration_remap_anchor,
                  sizeof(remap_anchor)) == 0;
}

static int open_astro_assist_acceleration(
    struct astro_assist_acceleration *acceleration)
{
    const char *source_test = getenv(ASTRO_ASSIST_ACCEL_SOURCE_TEST_ENV);
    const char *code_test = getenv(ASTRO_ASSIST_ACCEL_CODE_TEST_ENV);
    int result = 0;
    int saved_errno = 0;

    initialize_astro_assist_acceleration(acceleration);
    if (source_test != NULL && source_test[0] == '\0')
        source_test = NULL;
    if (code_test != NULL && code_test[0] == '\0')
        code_test = NULL;
    if ((source_test == NULL) != (code_test == NULL)) {
        result = -1;
        saved_errno = EINVAL;
    } else {
        if (open_read_only_mapping(&acceleration->source, source_test,
                                   ASTRO_ASSIST_ACCEL_SOURCE_PHYSICAL,
                                   ASTRO_ASSIST_ACCEL_SOURCE_SIZE) != 0) {
            result = -1;
            saved_errno = errno;
        }
        if (open_read_only_mapping(&acceleration->code, code_test,
                                   ASTRO_ASSIST_ACCEL_CODE_PHYSICAL,
                                   ASTRO_ASSIST_ACCEL_CODE_SIZE) != 0) {
            if (result == 0)
                saved_errno = errno;
            result = -1;
        }
    }
    acceleration->layout_qualified =
        qualify_astro_assist_acceleration_layout(acceleration);
    acceleration->value[ASTRO_ASSIST_GATT_ACCEL_FLAGS_OFFSET] =
        astro_assist_acceleration_base_flags(acceleration);
    if (result != 0)
        errno = saved_errno;
    return result;
}

static void install_astro_assist_acceleration_value(
    struct astro_assist_acceleration *acceleration, uint8_t flags,
    uint32_t main_generation,
    const uint8_t lanes[ASTRO_ASSIST_ACCEL_SLOT_SIZE])
{
    uint8_t *value = acceleration->value;
    int changed =
        value[ASTRO_ASSIST_GATT_ACCEL_FLAGS_OFFSET] != flags ||
        get_le32(value + ASTRO_ASSIST_GATT_ACCEL_MAIN_GENERATION_OFFSET) !=
            main_generation ||
        memcmp(value + ASTRO_ASSIST_GATT_ACCEL_X_MG_OFFSET, lanes,
               ASTRO_ASSIST_ACCEL_SLOT_SIZE) != 0;

    if (!changed)
        return;
    ++acceleration->linux_sequence;
    value[ASTRO_ASSIST_GATT_ACCEL_VERSION_OFFSET] =
        ASTRO_ASSIST_GATT_VERSION;
    value[ASTRO_ASSIST_GATT_ACCEL_FLAGS_OFFSET] = flags;
    put_le16(value + ASTRO_ASSIST_GATT_ACCEL_LINUX_SEQUENCE_OFFSET,
             acceleration->linux_sequence);
    put_le32(value + ASTRO_ASSIST_GATT_ACCEL_MAIN_GENERATION_OFFSET,
             main_generation);
    memcpy(value + ASTRO_ASSIST_GATT_ACCEL_X_MG_OFFSET, lanes,
           ASTRO_ASSIST_ACCEL_SLOT_SIZE);
    value[ASTRO_ASSIST_GATT_FOCUS_RESOURCE_FLAGS_OFFSET] = 0u;
    value[ASTRO_ASSIST_GATT_FOCUS_RESOURCE_STATE_OFFSET] = 0u;
    acceleration->dirty = 1;
}

static void reset_astro_assist_acceleration_session(
    struct astro_assist_acceleration *acceleration)
{
    static const uint8_t zero[ASTRO_ASSIST_ACCEL_SLOT_SIZE];

    acceleration->observed_generation = 0u;
    acceleration->observed_generation_valid = 0;
    acceleration->liveness_observed = 0;
    acceleration->last_advance_ms = 0;
    acceleration->next_poll_ms = 0;
    install_astro_assist_acceleration_value(
        acceleration, astro_assist_acceleration_base_flags(acceleration), 0u,
        zero);
}

static int read_astro_assist_acceleration_snapshot(
    const struct astro_assist_acceleration *acceleration,
    uint32_t generation_first,
    uint32_t *main_generation,
    uint8_t lanes[ASTRO_ASSIST_ACCEL_SLOT_SIZE])
{
    const volatile uint8_t *source = acceleration->source.bytes;
    uint8_t next_bytes[2];
    uint8_t first[ASTRO_ASSIST_ACCEL_SLOT_SIZE];
    uint8_t second[ASTRO_ASSIST_ACCEL_SLOT_SIZE];
    uint32_t generation_second;
    uint16_t next;
    size_t slot_offset;

    if (!acceleration->layout_qualified || source == NULL)
        return 0;
    __sync_synchronize();
    copy_volatile_bytes(next_bytes,
                        source + ASTRO_ASSIST_ACCEL_NEXT_OFFSET,
                        sizeof(next_bytes));
    slot_offset = ASTRO_ASSIST_ACCEL_SLOT0_OFFSET +
                  (size_t)((generation_first - UINT32_C(1)) & UINT32_C(1)) *
                      ASTRO_ASSIST_ACCEL_SLOT_SIZE;
    copy_volatile_bytes(first, source + slot_offset, sizeof(first));
    __sync_synchronize();
    copy_volatile_bytes(second, source + slot_offset, sizeof(second));
    __sync_synchronize();
    generation_second =
        *(const volatile uint32_t *)(const void *)(
            source + ASTRO_ASSIST_ACCEL_GENERATION_OFFSET);
    __sync_synchronize();

    next = get_le16(next_bytes);
    if (generation_first == 0u || generation_first != generation_second ||
        next != (uint16_t)(generation_first & UINT32_C(1)) ||
        memcmp(first, second, sizeof(first)) != 0)
        return 0;
    *main_generation = generation_first;
    memcpy(lanes, second, sizeof(second));
    return 1;
}

static uint32_t load_astro_assist_acceleration_generation(
    const struct astro_assist_acceleration *acceleration)
{
    return *(const volatile uint32_t *)(const void *)(
        acceleration->source.bytes +
        ASTRO_ASSIST_ACCEL_GENERATION_OFFSET);
}

static void sample_astro_assist_acceleration(
    struct astro_assist_acceleration *acceleration)
{
    static const uint8_t zero[ASTRO_ASSIST_ACCEL_SLOT_SIZE];
    uint8_t retained_lanes[ASTRO_ASSIST_ACCEL_SLOT_SIZE];
    uint8_t lanes[ASTRO_ASSIST_ACCEL_SLOT_SIZE];
    uint8_t flags;
    uint32_t retained_generation = get_le32(
        acceleration->value +
        ASTRO_ASSIST_GATT_ACCEL_MAIN_GENERATION_OFFSET);
    uint32_t main_generation = 0u;
    int64_t now = milliseconds_now();

    memcpy(retained_lanes,
           acceleration->value + ASTRO_ASSIST_GATT_ACCEL_X_MG_OFFSET,
           sizeof(retained_lanes));
    if (!acceleration->layout_qualified)
        acceleration->layout_qualified =
            qualify_astro_assist_acceleration_layout(acceleration);
    flags = astro_assist_acceleration_base_flags(acceleration);
    if (!acceleration->layout_qualified) {
        acceleration->observed_generation = 0u;
        acceleration->observed_generation_valid = 0;
        acceleration->liveness_observed = 0;
        acceleration->last_advance_ms = 0;
        install_astro_assist_acceleration_value(
            acceleration, flags, 0u, zero);
        return;
    }
    main_generation =
        load_astro_assist_acceleration_generation(acceleration);
    if (acceleration->observed_generation_valid && main_generation != 0u &&
        main_generation == acceleration->observed_generation) {
        flags |= ASTRO_ASSIST_GATT_ACCEL_SNAPSHOT_COHERENT;
        if (acceleration->liveness_observed && now >= 0 &&
            acceleration->last_advance_ms >= 0 &&
            now - acceleration->last_advance_ms <=
                ASTRO_ASSIST_ACCEL_STALE_MS)
            flags |= ASTRO_ASSIST_GATT_ACCEL_SOURCE_FRESH;
        install_astro_assist_acceleration_value(
            acceleration, flags, retained_generation, retained_lanes);
        return;
    }
    if (!read_astro_assist_acceleration_snapshot(
            acceleration, main_generation, &main_generation, lanes)) {
        install_astro_assist_acceleration_value(
            acceleration, flags, retained_generation, retained_lanes);
        return;
    }
    flags |= ASTRO_ASSIST_GATT_ACCEL_SNAPSHOT_COHERENT;
    if (!acceleration->observed_generation_valid) {
        acceleration->observed_generation = main_generation;
        acceleration->observed_generation_valid = 1;
    } else if (acceleration->observed_generation != main_generation) {
        acceleration->observed_generation = main_generation;
        acceleration->liveness_observed = 1;
        acceleration->last_advance_ms = now;
    }
    if (acceleration->liveness_observed && now >= 0 &&
        acceleration->last_advance_ms >= 0 &&
        now - acceleration->last_advance_ms <=
            ASTRO_ASSIST_ACCEL_STALE_MS)
        flags |= ASTRO_ASSIST_GATT_ACCEL_SOURCE_FRESH;
    if (main_generation == retained_generation)
        memcpy(lanes, retained_lanes, sizeof(lanes));
    install_astro_assist_acceleration_value(
        acceleration, flags, main_generation, lanes);
}

static void install_astro_assist_ibis_value(
    struct astro_assist_ibis *ibis, uint8_t flags,
    const uint8_t lanes[ASTRO_ASSIST_MIRROR_BYTES])
{
    uint8_t *value = ibis->value;
    int changed = value[ASTRO_ASSIST_GATT_IBIS_FLAGS_OFFSET] != flags ||
                  memcmp(value + ASTRO_ASSIST_GATT_IBIS_X_Q10_OFFSET,
                         lanes, ASTRO_ASSIST_MIRROR_BYTES) != 0;

    if (!changed)
        return;
    ++ibis->sequence;
    value[ASTRO_ASSIST_GATT_IBIS_VERSION_OFFSET] =
        ASTRO_ASSIST_GATT_VERSION;
    value[ASTRO_ASSIST_GATT_IBIS_FLAGS_OFFSET] = flags;
    put_le16(value + ASTRO_ASSIST_GATT_IBIS_SEQUENCE_OFFSET,
             ibis->sequence);
    memcpy(value + ASTRO_ASSIST_GATT_IBIS_X_Q10_OFFSET, lanes,
           ASTRO_ASSIST_MIRROR_BYTES);
    ibis->dirty = 1;
}

static void reset_astro_assist_ibis_session(struct astro_assist_ibis *ibis)
{
    static const uint8_t zero[ASTRO_ASSIST_MIRROR_BYTES];
    uint8_t flags = astro_assist_source_mapped(ibis)
                        ? ASTRO_ASSIST_GATT_IBIS_SOURCE_MAPPED
                        : 0u;

    memset(ibis->baseline, 0, sizeof(ibis->baseline));
    ibis->baseline_valid = 0;
    ibis->next_poll_ms = 0;
    install_astro_assist_ibis_value(ibis, flags, zero);
}

static int astro_assist_lanes_in_range(
    const uint8_t lanes[ASTRO_ASSIST_MIRROR_BYTES])
{
    size_t offset;

    for (offset = 0u; offset < ASTRO_ASSIST_MIRROR_BYTES; offset += 2u) {
        int16_t value = (int16_t)get_le16(lanes + offset);

        if (value < -ASTRO_ASSIST_GATT_IBIS_Q10_LIMIT ||
            value > ASTRO_ASSIST_GATT_IBIS_Q10_LIMIT)
            return 0;
    }
    return 1;
}

static void sample_astro_assist_ibis(struct astro_assist_ibis *ibis)
{
    uint8_t first[ASTRO_ASSIST_MIRROR_BYTES];
    uint8_t second[ASTRO_ASSIST_MIRROR_BYTES];
    uint8_t lanes[ASTRO_ASSIST_MIRROR_BYTES];
    uint8_t previous_flags =
        ibis->value[ASTRO_ASSIST_GATT_IBIS_FLAGS_OFFSET];
    uint8_t flags = 0u;
    int enabled;

    if (!astro_assist_source_mapped(ibis)) {
        reset_astro_assist_ibis_session(ibis);
        return;
    }
    flags = ASTRO_ASSIST_GATT_IBIS_SOURCE_MAPPED;
    enabled = (ibis->enable.bytes[0] &
               ASTRO_ASSIST_MIRROR_ENABLE_MASK) != 0u;
    __sync_synchronize();
    if (!enabled) {
        reset_astro_assist_ibis_session(ibis);
        return;
    }
    flags |= ASTRO_ASSIST_GATT_IBIS_MIRROR_ENABLED;
    if ((previous_flags & ASTRO_ASSIST_GATT_IBIS_MIRROR_ENABLED) == 0u) {
        memset(ibis->baseline, 0, sizeof(ibis->baseline));
        ibis->baseline_valid = 0;
    } else {
        flags |= previous_flags &
                 ASTRO_ASSIST_GATT_IBIS_OBSERVED_CHANGE;
    }

    copy_volatile_bytes(first, ibis->mirror.bytes, sizeof(first));
    __sync_synchronize();
    copy_volatile_bytes(second, ibis->mirror.bytes, sizeof(second));
    __sync_synchronize();
    if ((ibis->enable.bytes[0] & ASTRO_ASSIST_MIRROR_ENABLE_MASK) == 0u) {
        reset_astro_assist_ibis_session(ibis);
        return;
    }
    memcpy(lanes,
           ibis->value + ASTRO_ASSIST_GATT_IBIS_X_Q10_OFFSET,
           sizeof(lanes));
    if (memcmp(first, second, sizeof(first)) == 0 &&
        astro_assist_lanes_in_range(second)) {
        flags |= ASTRO_ASSIST_GATT_IBIS_DOUBLE_READ_STABLE;
        memcpy(lanes, second, sizeof(lanes));
        if (!ibis->baseline_valid) {
            memcpy(ibis->baseline, second, sizeof(ibis->baseline));
            ibis->baseline_valid = 1;
        } else if (memcmp(ibis->baseline, second,
                          sizeof(ibis->baseline)) != 0) {
            flags |= ASTRO_ASSIST_GATT_IBIS_OBSERVED_CHANGE;
        }
    }
    install_astro_assist_ibis_value(ibis, flags, lanes);
}

/* The length byte transfers ownership of one immutable body to MAIN. Only
 * this server publishes it; the worker owns no command data. */
static void publish_native_command(struct astro_rate_mailbox *mailbox,
                                   const uint8_t *value, uint8_t length)
{
    size_t index;

    for (index = 0u; index < length; ++index)
        mailbox->bytes[index] = value[index];
    __sync_synchronize();
    mailbox->bytes[ASTRO_RATE_MAILBOX_READY_OFFSET] = length;
    __sync_synchronize();
    mailbox->inflight = length;
}

static volatile uint8_t *astro_preposition_bytes(struct star_service *service)
{
    return service->astro_rate.bytes + ASTRO_PREPOSITION_RATE_RELATIVE_OFFSET;
}

static void set_preposition_state(struct star_service *service, uint8_t state)
{
    *astro_preposition_bytes(service) = state;
    __sync_synchronize();
}

static int convert_astro_preposition(const uint8_t *value, uint8_t *native)
{
    size_t axis;

    for (axis = 0u; axis < 3u; ++axis) {
        int32_t bias;
        if (!astro_preposition_native_axis(
                (int16_t)get_le16(value + axis * 2u), (unsigned int)axis, &bias))
            return 0;
        put_le32(native + axis * 4u, (uint32_t)bias);
    }
    put_le16(native + 12u, ASTRO_PREPOSITION_NATIVE_TAG);
    return 1;
}

static void cancel_astro_preposition(struct star_service *service,
                                     const char *reason)
{
    struct astro_preposition_request *request = &service->preposition;

    if (request->active && service->astro_rate.bytes != NULL) {
        request->cancel_pending = 1;
        request->queued = 0;
        set_preposition_state(service, ASTRO_PREPOSITION_CANCELLED);
        printf("ASTRO PREPOSITION cancelled: %s\n", reason);
    }
    request->active = 0;
    request->staged_intent = 0;
    request->capture_owned = 0;
    if (request->pending_cookie != 0u)
        request->pending_failed = 1;
}

/* Cancellation and a requested preposition get the next free carrier.
 * A staged command stays local, so ordinary RATE renewals continue. Coalesce
 * pending RATE renewals, but never replace a command already owned by MAIN. */
static void flush_native_commands(struct star_service *service)
{
    struct astro_rate_mailbox *mailbox = &service->astro_rate;
    struct astro_preposition_request *request = &service->preposition;
    static const uint8_t zero_rate[ASTRO_RATE_MAILBOX_PAYLOAD_SIZE];
    static const uint8_t zero_preposition[ASTRO_PREPOSITION_NATIVE_SIZE] = {
        [12] = 0x51u, [13] = 0x50u
    };
    uint8_t state;

    if (mailbox->bytes == NULL)
        return;
    state = *astro_preposition_bytes(service);
    if (state == ASTRO_PREPOSITION_CANCELLED && request->active)
        cancel_astro_preposition(service, "capture worker released preposition");
    if (mailbox->bytes[ASTRO_RATE_MAILBOX_READY_OFFSET] != 0u)
        return;
    __sync_synchronize();
    if (mailbox->inflight == ASTRO_PREPOSITION_NATIVE_SIZE) {
        if (mailbox->inflight_preposition_cancel) {
            if (state == ASTRO_PREPOSITION_CANCELLED && !request->cancel_pending)
                set_preposition_state(service, ASTRO_PREPOSITION_EMPTY);
        } else if (state == ASTRO_PREPOSITION_PUBLISHED) {
            set_preposition_state(service, ASTRO_PREPOSITION_CONSUMED);
        }
    }
    mailbox->inflight = 0u;
    state = *astro_preposition_bytes(service);
    if (request->cancel_pending) {
        request->cancel_pending = 0;
        mailbox->inflight_preposition_cancel = 1;
        publish_native_command(mailbox, zero_preposition,
                               ASTRO_PREPOSITION_NATIVE_SIZE);
    } else if (mailbox->cancel_pending) {
        mailbox->cancel_pending = 0;
        publish_native_command(mailbox, zero_rate,
                               ASTRO_RATE_MAILBOX_PAYLOAD_SIZE);
    } else if (request->queued && state == ASTRO_PREPOSITION_REQUESTED) {
        set_preposition_state(service, ASTRO_PREPOSITION_PUBLISHED);
        request->queued = 0;
        mailbox->inflight_preposition_cancel = 0;
        publish_native_command(mailbox, request->queued_value,
                               ASTRO_PREPOSITION_NATIVE_SIZE);
        if (request->pending_cookie != 0u && !request->pending_failed)
            request->pending_published = 1;
    } else if (mailbox->queued) {
        mailbox->queued = 0;
        publish_native_command(mailbox, mailbox->queued_value,
                               ASTRO_RATE_MAILBOX_PAYLOAD_SIZE);
    }
}

static void queue_astro_rate_mailbox(struct astro_rate_mailbox *mailbox,
                                    const uint8_t *value, int is_cancel)
{
    if (is_cancel) {
        mailbox->cancel_pending = 1;
        mailbox->queued = 0;
    } else {
        memcpy(mailbox->queued_value, value, ASTRO_RATE_MAILBOX_PAYLOAD_SIZE);
        mailbox->queued = 1;
    }
}

static void convert_astro_rate(const uint8_t *value, uint16_t focal,
                              uint8_t *native)
{
    int32_t gain = -(23 * (int32_t)focal +
        (int32_t)((UINT32_C(30694) * focal + UINT32_C(32768)) >> 16));
    size_t axis;

    for (axis = 0u; axis < 3u; ++axis) {
        int32_t rate = (int32_t)get_le32(value + axis * 4u);
        int64_t step;
        int64_t whole;

        if (rate > ASTRO_RATE_GATT_V2_RATE_LIMIT_Q16)
            rate = ASTRO_RATE_GATT_V2_RATE_LIMIT_Q16;
        if (rate < -ASTRO_RATE_GATT_V2_RATE_LIMIT_Q16)
            rate = -ASTRO_RATE_GATT_V2_RATE_LIMIT_Q16;
        step = (int64_t)rate * (axis == 2u ? -INT32_C(192253) : gain);
        if (axis == 2u) {
            /* Q43 -> Q32, nearest with ties away from zero. */
            step = step >= 0 ? (step + 1024) / 2048 :
                              -((-step + 1024) / 2048);
        }
        /* Encode floor(step / 2^32) plus its unsigned fractional remainder. */
        whole = step / INT64_C(4294967296);
        if (step < 0 && (uint32_t)step != 0u)
            --whole;
        put_le32(native + axis * 4u, (uint32_t)step);
        put_le16(native + ASTRO_RATE_MAILBOX_WHOLE_OFFSET + axis * 2u,
                 (uint16_t)(int16_t)whole);
    }
    put_le16(native + ASTRO_RATE_MAILBOX_TTL_OFFSET,
             get_le16(value + ASTRO_RATE_GATT_V2_TTL_OFFSET));
}

static void clear_astro_rate_active_value(struct astro_rate_mailbox *mailbox)
{
    mailbox->active = 0;
}

static void clear_astro_rate_connection_focal(
    struct astro_rate_mailbox *mailbox)
{
    mailbox->last_valid_focal_uq11_5 = 0u;
    mailbox->last_valid_focal_available = 0;
}

static void cancel_astro_rate(struct star_service *service,
                              const char *reason)
{
    static const uint8_t zero[ASTRO_RATE_MAILBOX_PAYLOAD_SIZE];
    int was_active = service->astro_rate.active;

    if (was_active)
        queue_astro_rate_mailbox(&service->astro_rate, zero, 1);
    clear_astro_rate_active_value(&service->astro_rate);
    if (was_active)
        printf("ASTRO RATE cancelled: %s\n", reason);
}

static uint8_t validate_astro_rate_v2_value(const uint8_t *value,
                                            uint16_t value_len,
                                            int *is_cancel)
{
    int32_t yaw;
    int32_t pitch;
    int32_t roll;
    int16_t gave0;
    int16_t gave1;
    uint16_t ttl;

    if (value_len != ASTRO_RATE_GATT_V2_VALUE_SIZE)
        return STAR_ATT_INVALID_ATTRIBUTE_LENGTH;
    *is_cancel = bytes_zero(value, ASTRO_RATE_GATT_V2_VALUE_SIZE);
    if (*is_cancel)
        return STAR_ATT_SUCCESS;
    yaw = (int32_t)get_le32(value + ASTRO_RATE_GATT_V2_YAW_OFFSET);
    pitch = (int32_t)get_le32(value + ASTRO_RATE_GATT_V2_PITCH_OFFSET);
    roll = (int32_t)get_le32(value + ASTRO_RATE_GATT_V2_ROLL_OFFSET);
    ttl = get_le16(value + ASTRO_RATE_GATT_V2_TTL_OFFSET);
    gave0 = (int16_t)get_le16(value + ASTRO_RATE_GATT_V2_GAVE0_OFFSET);
    gave1 = (int16_t)get_le16(value + ASTRO_RATE_GATT_V2_GAVE1_OFFSET);
    if (yaw < -ASTRO_RATE_GATT_V2_RATE_LIMIT_Q16 ||
        yaw > ASTRO_RATE_GATT_V2_RATE_LIMIT_Q16 ||
        pitch < -ASTRO_RATE_GATT_V2_RATE_LIMIT_Q16 ||
        pitch > ASTRO_RATE_GATT_V2_RATE_LIMIT_Q16 ||
        roll < -ASTRO_RATE_GATT_V2_RATE_LIMIT_Q16 ||
        roll > ASTRO_RATE_GATT_V2_RATE_LIMIT_Q16 ||
        gave0 != 0 || gave1 != 0 ||
        (yaw == 0 && pitch == 0 && roll == 0 && gave0 == 0 && gave1 == 0) ||
        ttl < ASTRO_RATE_GATT_V2_TTL_MIN)
        return STAR_ATT_VALUE_NOT_ALLOWED;
    return STAR_ATT_SUCCESS;
}

static int focal_length_valid(uint32_t bits)
{
    return bits >= UINT32_C(0x3dcccccd) &&
           (bits & UINT32_C(0x80000000)) == 0u &&
           (bits & UINT32_C(0x7f800000)) != UINT32_C(0x7f800000) &&
           bits <= UINT32_C(0x44ffff00);
}

enum live_focal_status {
    LIVE_FOCAL_OK = 0,
    LIVE_FOCAL_UNAVAILABLE,
    LIVE_FOCAL_OUT_OF_RANGE
};

/*
 * Convert the positive normal IEEE-754 binary32 STATE focal exactly.  With
 * biased exponent E, mm * 32 is significand * 2^(E - 145); the validated
 * STATE range makes 145 - E an 8..22-bit right shift.  That shift is exactly
 * floor(mm * 32), without target floating-point or rounding-mode dependencies.
 */
static enum live_focal_status sample_cached_focal_uq11_5(
    const struct star_service *service, uint16_t *focal)
{
    uint32_t bits;
    uint32_t biased_exponent;
    uint32_t significand;
    uint32_t raw;
    int shift;

    if (service == NULL || focal == NULL || !service->state.valid ||
        service->state.value_len < 10u)
        return LIVE_FOCAL_UNAVAILABLE;
    bits = get_le32(service->state.value + 6u);
    if (!focal_length_valid(bits))
        return LIVE_FOCAL_UNAVAILABLE;

    biased_exponent = (bits >> 23) & UINT32_C(0xff);
    significand = UINT32_C(0x800000) |
                  (bits & UINT32_C(0x7fffff));
    shift = 145 - (int)biased_exponent;
    if (shift < 8 || shift > 22)
        return LIVE_FOCAL_OUT_OF_RANGE;
    raw = significand >> shift;
    if (raw < ASTRO_RATE_MAILBOX_FOCAL_MIN ||
        raw > ASTRO_RATE_MAILBOX_FOCAL_MAX)
        return LIVE_FOCAL_OUT_OF_RANGE;
    *focal = (uint16_t)raw;
    return LIVE_FOCAL_OK;
}

static enum live_focal_status sample_live_focal_uq11_5(
    const struct star_service *service, uint16_t *focal)
{
    if (service == NULL || !worker_accepts_command(service))
        return LIVE_FOCAL_UNAVAILABLE;
    return sample_cached_focal_uq11_5(service, focal);
}

static int printable_label(const uint8_t *bytes, uint8_t length)
{
    size_t index;

    if (bytes == NULL || length == 0u ||
        length > STAR_GATT_SETTING_LABEL_MAX)
        return 0;
    for (index = 0u; index < length; ++index) {
        if (bytes[index] < UINT8_C(0x21) || bytes[index] > UINT8_C(0x7e))
            return 0;
    }
    return 1;
}

static int printable_lens(const uint8_t *bytes, uint8_t length)
{
    size_t index;

    if (length > STAR_GATT_LENS_NAME_MAX)
        return 0;
    for (index = 0u; index < length; ++index) {
        if (bytes[index] < UINT8_C(0x20) || bytes[index] > UINT8_C(0x7e))
            return 0;
    }
    return 1;
}

static uint8_t parse_label_tuple(const uint8_t *value, size_t value_len,
                                 struct label_tuple_view *tuple)
{
    size_t offset = 0u;
    unsigned int kind;

    if (value == NULL || tuple == NULL ||
        value_len < STAR_GATT_SETTINGS_WRITE_MIN_SIZE ||
        value_len > STAR_GATT_SETTINGS_WRITE_MAX_SIZE)
        return STAR_ATT_INVALID_ATTRIBUTE_LENGTH;
    memset(tuple, 0, sizeof(*tuple));
    for (kind = 0u; kind < STAR_GATT_SETTING_COUNT; ++kind) {
        uint8_t length;

        if (offset >= value_len)
            return STAR_ATT_INVALID_ATTRIBUTE_LENGTH;
        length = value[offset++];
        if (length == 0u || length > STAR_GATT_SETTING_LABEL_MAX)
            return STAR_ATT_VALUE_NOT_ALLOWED;
        if (offset + length > value_len)
            return STAR_ATT_INVALID_ATTRIBUTE_LENGTH;
        if (!printable_label(value + offset, length))
            return STAR_ATT_VALUE_NOT_ALLOWED;
        tuple->label[kind].bytes = value + offset;
        tuple->label[kind].length = length;
        offset += length;
    }
    return offset == value_len ? STAR_ATT_SUCCESS
                               : STAR_ATT_INVALID_ATTRIBUTE_LENGTH;
}

static int validate_catalog_value(const uint8_t *value, uint16_t value_len)
{
    size_t offsets[STAR_GATT_CATALOG_COUNT_MAX];
    size_t offset = 1u;
    unsigned int count;
    unsigned int index;

    if (value == NULL || value_len < STAR_GATT_CATALOG_MIN_SIZE ||
        value_len > STAR_GATT_CATALOG_MAX_SIZE)
        return 0;
    count = value[0];
    if (count < STAR_GATT_CATALOG_COUNT_MIN ||
        count > STAR_GATT_CATALOG_COUNT_MAX)
        return 0;
    for (index = 0u; index < count; ++index) {
        uint8_t length;
        unsigned int prior;

        if (offset >= value_len)
            return 0;
        offsets[index] = offset;
        length = value[offset++];
        if (offset + length > value_len ||
            !printable_label(value + offset, length))
            return 0;
        for (prior = 0u; prior < index; ++prior) {
            size_t prior_offset = offsets[prior];
            uint8_t prior_length = value[prior_offset++];

            if (prior_length == length &&
                memcmp(value + prior_offset, value + offset, length) == 0)
                return 0;
        }
        offset += length;
    }
    return offset == value_len;
}

static int catalog_label_index(const struct public_value *catalog,
                               const struct label_ref *label,
                               unsigned int *match_index)
{
    size_t offset = 1u;
    unsigned int count;
    unsigned int index;

    if (catalog == NULL || !catalog->valid || label == NULL ||
        match_index == NULL)
        return -1;
    count = catalog->value[0];
    for (index = 0u; index < count; ++index) {
        uint8_t length = catalog->value[offset++];

        if (length == label->length &&
            memcmp(catalog->value + offset, label->bytes, length) == 0) {
            *match_index = index;
            return 0;
        }
        offset += length;
    }
    return -1;
}

static uint32_t next_nonzero_generation(uint32_t generation)
{
    ++generation;
    if (generation == 0u)
        ++generation;
    return generation;
}

static int catalog_label_at(const struct public_value *catalog,
                            unsigned int wanted,
                            struct label_ref *label)
{
    size_t offset = 1u;
    unsigned int count;
    unsigned int index;

    if (catalog == NULL || !catalog->valid || label == NULL)
        return -1;
    count = catalog->value[0];
    if (wanted >= count)
        return -1;
    for (index = 0u; index < count; ++index) {
        uint8_t length = catalog->value[offset++];

        if (index == wanted) {
            label->bytes = catalog->value + offset;
            label->length = length;
            return 0;
        }
        offset += length;
    }
    return -1;
}

static int tuple_from_indices(const struct public_catalog_set *catalog,
                              const uint8_t indices[STAR_GATT_SETTING_COUNT],
                              struct public_recipe *tuple)
{
    size_t offset = 0u;
    unsigned int kind;

    if (catalog == NULL || !catalog->valid || indices == NULL || tuple == NULL)
        return -1;
    memset(tuple, 0, sizeof(*tuple));
    for (kind = 0u; kind < STAR_GATT_SETTING_COUNT; ++kind) {
        struct label_ref label;

        memset(&label, 0, sizeof(label));
        if (catalog_label_at(&catalog->kind[kind], indices[kind], &label) != 0)
            return -1;
        tuple->value[offset++] = label.length;
        memcpy(tuple->value + offset, label.bytes, label.length);
        offset += label.length;
    }
    tuple->valid = 1;
    tuple->value_len = (uint16_t)offset;
    return 0;
}

static int state_matches_tuple(const struct public_value *state,
                               const struct public_catalog_set *catalog,
                               const struct public_recipe *tuple)
{
    struct state_view state_view;
    struct label_tuple_view tuple_view;
    unsigned int kind;

    if (state == NULL || catalog == NULL || tuple == NULL || !tuple->valid ||
        !validate_state_value(state->value, state->value_len, catalog,
                              &state_view) ||
        parse_label_tuple(tuple->value, tuple->value_len, &tuple_view) !=
            STAR_ATT_SUCCESS)
        return 0;
    for (kind = 0u; kind < STAR_GATT_SETTING_COUNT; ++kind) {
        if (state_view.current[kind].length !=
                tuple_view.label[kind].length ||
            memcmp(state_view.current[kind].bytes,
                   tuple_view.label[kind].bytes,
                   state_view.current[kind].length) != 0)
            return 0;
    }
    return 1;
}

static int legacy_state_lens(const struct public_value *state,
                             const uint8_t **lens, uint8_t *lens_length)
{
    size_t offset = 10u;
    unsigned int field;

    if (state == NULL || !state->valid || lens == NULL ||
        lens_length == NULL)
        return -1;
    for (field = 0u; field < STAR_GATT_STATE_LABEL_COUNT; ++field) {
        uint8_t length;

        if (offset >= state->value_len)
            return -1;
        length = state->value[offset++];
        if (offset + length > state->value_len)
            return -1;
        offset += length;
    }
    if (offset >= state->value_len)
        return -1;
    *lens_length = state->value[offset++];
    if (offset + *lens_length != state->value_len)
        return -1;
    *lens = state->value + offset;
    return 0;
}

static void rebuild_unified_state(struct star_service *service)
{
    struct public_value next;
    struct state_view actual_view;
    struct label_tuple_view recipe_view;
    uint8_t flags = 0u;
    uint8_t lens_length = 0u;
    const uint8_t *lens = NULL;
    unsigned int indices[STAR_GATT_SETTING_COUNT];
    unsigned int aperture_min;
    unsigned int aperture_max;
    unsigned int kind;
    int actual_available = 0;
    int recipe_available = 0;
    int detector_available = service->detector_config_valid;
    int complete_config;
    int prior_complete = 0;
    int config_changed = 0;
    int changed;

    memset(&next, 0, sizeof(next));
    next.valid = 1;
    next.value_len = STAR_STATE_PREFIX_SIZE;
    next.value[STAR_STATE_VERSION_OFFSET] = STAR_STATE_VERSION;
    memset(next.value + STAR_STATE_ACTUAL_INDICES_OFFSET,
           STAR_UNAVAILABLE_INDEX, STAR_GATT_SETTING_COUNT);
    memset(next.value + STAR_STATE_RECIPE_INDICES_OFFSET,
           STAR_UNAVAILABLE_INDEX, STAR_GATT_SETTING_COUNT);
    memset(next.value + STAR_STATE_APERTURE_BOUNDS_OFFSET,
           STAR_UNAVAILABLE_INDEX, 2u);
    put_le32(next.value + STAR_STATE_WORKER_GENERATION_OFFSET,
             (uint32_t)service->worker_generation);

    if (service->catalog.valid) {
        if (service->catalog_generation == 0u)
            service->catalog_generation = 1u;
        flags |= STAR_STATE_FLAG_CATALOG;
        put_le32(next.value + STAR_STATE_CATALOG_GENERATION_OFFSET,
                 service->catalog_generation);
    }
    if (service->state.valid && service->catalog.valid &&
        validate_state_value(service->state.value, service->state.value_len,
                             &service->catalog, &actual_view) &&
        legacy_state_lens(&service->state, &lens, &lens_length) == 0) {
        actual_available = 1;
        for (kind = 0u; kind < STAR_GATT_SETTING_COUNT; ++kind) {
            if (catalog_label_index(&service->catalog.kind[kind],
                                    &actual_view.current[kind],
                                    &indices[kind]) != 0) {
                actual_available = 0;
                break;
            }
        }
        if (actual_available &&
            (catalog_label_index(&service->catalog.kind[1],
                                 &actual_view.aperture_min,
                                 &aperture_min) != 0 ||
             catalog_label_index(&service->catalog.kind[1],
                                 &actual_view.aperture_max,
                                 &aperture_max) != 0))
            actual_available = 0;
    }
    if (actual_available) {
        flags |= STAR_STATE_FLAG_ACTUAL;
        memcpy(next.value + STAR_STATE_ACTUAL_NATIVE_OFFSET,
               service->state.value, 6u);
        for (kind = 0u; kind < STAR_GATT_SETTING_COUNT; ++kind)
            next.value[STAR_STATE_ACTUAL_INDICES_OFFSET + kind] =
                (uint8_t)indices[kind];
        next.value[STAR_STATE_APERTURE_BOUNDS_OFFSET] =
            (uint8_t)aperture_min;
        next.value[STAR_STATE_APERTURE_BOUNDS_OFFSET + 1u] =
            (uint8_t)aperture_max;
        memcpy(next.value + STAR_STATE_FOCAL_BITS_OFFSET,
               service->state.value + 6u, 4u);
        next.value[STAR_STATE_LENS_LENGTH_OFFSET] = lens_length;
        if (lens_length != 0u)
            memcpy(next.value + STAR_STATE_LENS_OFFSET, lens,
                   lens_length);
        next.value_len =
            (uint16_t)(STAR_STATE_PREFIX_SIZE + lens_length);
    }

    if (service->recipe.valid && service->catalog.valid &&
        parse_label_tuple(service->recipe.value, service->recipe.value_len,
                          &recipe_view) == STAR_ATT_SUCCESS) {
        recipe_available = 1;
        for (kind = 0u; kind < STAR_GATT_SETTING_COUNT; ++kind) {
            if (catalog_label_index(&service->catalog.kind[kind],
                                    &recipe_view.label[kind],
                                    &indices[kind]) != 0) {
                recipe_available = 0;
                break;
            }
            next.value[STAR_STATE_RECIPE_INDICES_OFFSET + kind] =
                (uint8_t)indices[kind];
        }
        if (recipe_available && actual_available &&
            (indices[1] < aperture_min || indices[1] > aperture_max))
            recipe_available = 0;
    }
    if (recipe_available) {
        flags |= STAR_STATE_FLAG_RECIPE;
    } else {
        memset(next.value + STAR_STATE_RECIPE_INDICES_OFFSET,
               STAR_UNAVAILABLE_INDEX, STAR_GATT_SETTING_COUNT);
    }
    if (detector_available) {
        flags |= STAR_STATE_FLAG_DETECTOR;
        memcpy(next.value + STAR_STATE_DETECTOR_OFFSET,
               service->detector_config,
               ASTRO_ASSIST_GATT_DETECTOR_CONFIG_SIZE);
    }

    next.value[STAR_STATE_IBIS_FLAGS_OFFSET] =
        service->astro_assist.value[ASTRO_ASSIST_GATT_IBIS_FLAGS_OFFSET];
    memcpy(next.value + STAR_STATE_IBIS_SEQUENCE_OFFSET,
           service->astro_assist.value +
               ASTRO_ASSIST_GATT_IBIS_SEQUENCE_OFFSET,
           8u);
    /* MOVESTEP telemetry is unavailable; its reserved wire fields stay zero. */
    if ((next.value[STAR_STATE_IBIS_FLAGS_OFFSET] &
         STAR_IBIS_Q10_SOURCE_MAPPED) != 0u)
        flags |= STAR_STATE_FLAG_IBIS;

    next.value[STAR_STATE_ACCEL_FLAGS_OFFSET] =
        service->acceleration.value[ASTRO_ASSIST_GATT_ACCEL_FLAGS_OFFSET];
    memcpy(next.value + STAR_STATE_ACCEL_MAIN_GENERATION_OFFSET,
           service->acceleration.value +
               ASTRO_ASSIST_GATT_ACCEL_MAIN_GENERATION_OFFSET,
           10u);
    if ((next.value[STAR_STATE_ACCEL_FLAGS_OFFSET] &
         ASTRO_ASSIST_GATT_ACCEL_SOURCE_MAPPED) != 0u)
        flags |= STAR_STATE_FLAG_ACCELERATION;

    if (service->capture_recovery_pending && actual_available)
        flags |= STAR_STATE_FLAG_RECOVERY_TARGET;
    next.value[STAR_STATE_FLAGS_OFFSET] = flags;
    memcpy(next.value + next.value_len, service->gyro.source.value,
           GYRO_ATTITUDE_VALUE_SIZE);
    next.value_len = (uint16_t)(next.value_len + GYRO_ATTITUDE_VALUE_SIZE);

    complete_config = actual_available && recipe_available &&
                      detector_available;
    if (service->unified_state.valid) {
        uint8_t prior_flags =
            service->unified_state.value[STAR_STATE_FLAGS_OFFSET];

        prior_complete =
            (prior_flags & (STAR_STATE_FLAG_ACTUAL |
                            STAR_STATE_FLAG_RECIPE |
                            STAR_STATE_FLAG_DETECTOR)) ==
            (STAR_STATE_FLAG_ACTUAL |
             STAR_STATE_FLAG_RECIPE |
             STAR_STATE_FLAG_DETECTOR);
        config_changed =
            !prior_complete ||
            memcmp(service->unified_state.value +
                       STAR_STATE_ACTUAL_NATIVE_OFFSET,
                   next.value + STAR_STATE_ACTUAL_NATIVE_OFFSET,
                   12u) != 0 ||
            memcmp(service->unified_state.value +
                       STAR_STATE_DETECTOR_OFFSET,
                   next.value + STAR_STATE_DETECTOR_OFFSET,
                   ASTRO_ASSIST_GATT_DETECTOR_CONFIG_SIZE) != 0;
    } else {
        config_changed = 1;
    }
    if (complete_config) {
        if (config_changed || service->config_generation == 0u)
            service->config_generation =
                next_nonzero_generation(service->config_generation);
        put_le32(next.value + STAR_STATE_CONFIG_GENERATION_OFFSET,
                 service->config_generation);
    }

    changed = !service->unified_state.valid ||
              service->unified_state.value_len != next.value_len ||
              memcmp(service->unified_state.value, next.value, 2u) != 0 ||
              memcmp(service->unified_state.value + 6u, next.value + 6u,
                     next.value_len - 6u) != 0;
    if (changed) {
        service->publication_generation =
            next_nonzero_generation(service->publication_generation);
        put_le32(next.value +
                     STAR_STATE_PUBLICATION_GENERATION_OFFSET,
                 service->publication_generation);
        service->unified_state = next;
        service->state_dirty = 1;
    }
}

static int validate_state_value(const uint8_t *value, uint16_t value_len,
                                const struct public_catalog_set *catalog,
                                struct state_view *view)
{
    size_t offset = 10u;
    struct label_ref fields[STAR_GATT_STATE_LABEL_COUNT];
    unsigned int current_index[STAR_GATT_SETTING_COUNT];
    unsigned int minimum_index;
    unsigned int maximum_index;
    unsigned int field;
    uint8_t lens_length;
    uint32_t focal_bits;

    if (value == NULL || catalog == NULL || !catalog->valid || view == NULL ||
        value_len < STAR_GATT_STATE_MIN_SIZE ||
        value_len > STAR_GATT_STATE_MAX_SIZE)
        return 0;
    focal_bits = get_le32(value + 6u);
    if (focal_bits != 0u && !focal_length_valid(focal_bits))
        return 0;
    memset(view, 0, sizeof(*view));
    memset(fields, 0, sizeof(fields));
    for (field = 0u; field < STAR_GATT_STATE_LABEL_COUNT; ++field) {
        uint8_t length;

        if (offset >= value_len)
            return 0;
        length = value[offset++];
        if (offset + length > value_len ||
            !printable_label(value + offset, length))
            return 0;
        fields[field].bytes = value + offset;
        fields[field].length = length;
        offset += length;
    }
    if (offset >= value_len)
        return 0;
    lens_length = value[offset++];
    if (offset + lens_length != value_len ||
        !printable_lens(value + offset, lens_length))
        return 0;
    for (field = 0u; field < STAR_GATT_SETTING_COUNT; ++field) {
        view->current[field] = fields[field];
        if (catalog_label_index(&catalog->kind[field], &fields[field],
                                &current_index[field]) != 0)
            return 0;
    }
    view->aperture_min = fields[STAR_GATT_SETTING_COUNT];
    view->aperture_max = fields[STAR_GATT_SETTING_COUNT + 1u];
    if (catalog_label_index(&catalog->kind[1], &view->aperture_min,
                            &minimum_index) != 0 ||
        catalog_label_index(&catalog->kind[1], &view->aperture_max,
                            &maximum_index) != 0 ||
        minimum_index > maximum_index || current_index[1] < minimum_index ||
        current_index[1] > maximum_index)
        return 0;
    return 1;
}

static int tuple_legal_for_snapshot(
    const uint8_t *value, uint16_t value_len,
    const struct public_catalog_set *catalog, const struct public_value *state)
{
    struct label_tuple_view tuple;
    struct state_view state_labels;
    unsigned int selected[STAR_GATT_SETTING_COUNT];
    unsigned int minimum;
    unsigned int maximum;
    unsigned int kind;

    if (catalog == NULL || !catalog->valid || state == NULL || !state->valid ||
        parse_label_tuple(value, value_len, &tuple) != STAR_ATT_SUCCESS ||
        !validate_state_value(state->value, state->value_len, catalog,
                              &state_labels))
        return 0;
    for (kind = 0u; kind < STAR_GATT_SETTING_COUNT; ++kind) {
        if (catalog_label_index(&catalog->kind[kind], &tuple.label[kind],
                                &selected[kind]) != 0)
            return 0;
    }
    if (catalog_label_index(&catalog->kind[1], &state_labels.aperture_min,
                            &minimum) != 0 ||
        catalog_label_index(&catalog->kind[1], &state_labels.aperture_max,
                            &maximum) != 0)
        return 0;
    return selected[1] >= minimum && selected[1] <= maximum;
}

static int recipe_from_state(const struct public_value *state,
                             const struct public_catalog_set *catalog,
                             struct public_recipe *recipe)
{
    struct state_view view;
    size_t offset = 0u;
    unsigned int kind;

    if (state == NULL || catalog == NULL || recipe == NULL ||
        !validate_state_value(state->value, state->value_len, catalog, &view))
        return -1;
    memset(recipe, 0, sizeof(*recipe));
    for (kind = 0u; kind < STAR_GATT_SETTING_COUNT; ++kind) {
        uint8_t length = view.current[kind].length;

        recipe->value[offset++] = length;
        memcpy(recipe->value + offset, view.current[kind].bytes, length);
        offset += length;
    }
    recipe->valid = 1;
    recipe->value_len = (uint16_t)offset;
    return 0;
}

static int catalog_sets_equal(const struct public_catalog_set *left,
                              const struct public_catalog_set *right)
{
    unsigned int kind;

    if (left->valid != right->valid)
        return 0;
    if (!left->valid)
        return 1;
    for (kind = 0u; kind < STAR_GATT_SETTING_COUNT; ++kind) {
        if (left->kind[kind].value_len != right->kind[kind].value_len ||
            memcmp(left->kind[kind].value, right->kind[kind].value,
                   left->kind[kind].value_len) != 0)
            return 0;
    }
    return 1;
}

static int replace_public_catalog(
    struct star_service *service, const struct public_catalog_set *catalog)
{
    int changed = !catalog_sets_equal(&service->catalog, catalog);

    /* A rollback is still a new public bank transition; never reuse its
     * predecessor's generation for different catalog bytes. */
    if (changed)
        service->catalog_generation =
            next_nonzero_generation(service->catalog_generation);
    service->catalog = *catalog;
    return changed;
}

static void invalidate_public_snapshot(struct star_service *service)
{
    memset(&service->state, 0, sizeof(service->state));
    memset(&service->catalog, 0, sizeof(service->catalog));
    memset(&service->staged_catalog, 0, sizeof(service->staged_catalog));
    service->state_dirty = 0;
}

static void commit_public_snapshot(struct star_service *service,
                                   const struct public_catalog_set *catalog,
                                   const uint8_t *state_value,
                                   uint16_t state_value_len,
                                   int force_notification)
{
    struct public_value next_state;
    int changed;
    int catalog_changed;

    memset(&next_state, 0, sizeof(next_state));
    next_state.valid = 1;
    next_state.value_len = state_value_len;
    memcpy(next_state.value, state_value, state_value_len);
    changed = !service->state.valid ||
              service->state.value_len != next_state.value_len ||
              memcmp(service->state.value, next_state.value,
                     next_state.value_len) != 0;
    catalog_changed = replace_public_catalog(service, catalog);
    service->state = next_state;
    memset(&service->staged_catalog, 0, sizeof(service->staged_catalog));
    if (!service->recipe.valid ||
        !tuple_legal_for_snapshot(service->recipe.value,
                                  service->recipe.value_len,
                                  &service->catalog, &service->state)) {
        if (recipe_from_state(&service->state, &service->catalog,
                              &service->recipe) == 0)
            printf("STAR RECIPE seeded from authoritative STATE\n");
    }
    if (changed || catalog_changed || force_notification)
        service->state_dirty = 1;
}

static void clear_capture_stream(struct star_service *service)
{
    memset(&service->capture, 0, sizeof(service->capture));
    service->capture_state_reason = 0u;
    service->stream = STAR_STREAM_NONE;
}

static int report_client_error(struct bsa_gatt_proxy_client *client,
                               int status, const char *operation)
{
    fprintf(stderr, "%s: %s", operation,
            bsa_gatt_proxy_client_status_string(status));
    if (status == BSA_GATT_PROXY_CLIENT_IO) {
        int saved_errno = bsa_gatt_proxy_last_errno(client);

        if (saved_errno != 0)
            fprintf(stderr, ": %s", strerror(saved_errno));
    }
    fputc('\n', stderr);
    return -1;
}

static int result_ok(const struct bgp_result *result, const char *operation)
{
    if (result->proxy_status == BGP_STATUS_OK && result->bsa_status == 0u)
        return 1;
    fprintf(stderr, "%s rejected: proxy status %ld, BSA status %u\n",
            operation, (long)result->proxy_status,
            (unsigned int)result->bsa_status);
    return 0;
}

static int next_event(struct bsa_gatt_proxy_client *client,
                      struct bsa_gatt_proxy_event *event, int timeout_ms)
{
    int status = bsa_gatt_proxy_receive_event(client, event, timeout_ms);

    if (status == BSA_GATT_PROXY_CLIENT_TIMEOUT)
        return 0;
    if (status != BSA_GATT_PROXY_CLIENT_OK)
        return report_client_error(client, status, "receive event");
    return 1;
}

static int state_is_usable(const struct bgp_state_event *state,
                           uint32_t expected_epoch)
{
    return state->epoch == expected_epoch && state->reason == 0 &&
           (state->state_flags & STAR_REQUIRED_STATE) == STAR_REQUIRED_STATE;
}

static int wait_for_event(struct bsa_gatt_proxy_client *client,
                          uint16_t wanted_type, uint32_t expected_epoch,
                          struct bsa_gatt_proxy_event *event)
{
    while (!stop_requested) {
        int status = next_event(client, event, 1000);

        if (status < 0)
            return -1;
        if (status == 0)
            continue;
        if (event->type == wanted_type)
            return 0;
        if (event->type == BGP_EVT_STATE &&
            !state_is_usable(&event->payload.state, expected_epoch))
            return -1;
    }
    return -1;
}

static int wait_for_cleanup_event(struct bsa_gatt_proxy_client *client,
                                  uint16_t wanted_type,
                                  struct bsa_gatt_proxy_event *event)
{
    unsigned int attempt;

    for (attempt = 0u; attempt < 20u; ++attempt) {
        int status = next_event(client, event, 250);

        if (status < 0)
            return -1;
        if (status == 0)
            continue;
        if (event->type == wanted_type)
            return 0;
        if (event->type == BGP_EVT_STATE && event->payload.state.reason != 0)
            return -1;
    }
    fprintf(stderr, "timed out waiting for event 0x%04x\n",
            (unsigned int)wanted_type);
    return -1;
}

static int send_att_response(struct bsa_gatt_proxy_client *client,
                             uint32_t cookie, uint8_t att_status,
                             const void *value, uint16_t value_len)
{
    struct bgp_result response;
    int status = bsa_gatt_proxy_send_response(
        client, cookie, att_status, 0u, value, value_len, &response);

    if (status != BSA_GATT_PROXY_CLIENT_OK) {
        fprintf(stderr,
                "ATT response transport failure cookie=%lu status=0x%02x\n",
                (unsigned long)cookie, (unsigned int)att_status);
        return report_client_error(client, status, "send ATT response");
    }
    if (response.proxy_status == BGP_STATUS_STALE) {
        fprintf(stderr, "ATT response cookie=%lu status=0x%02x became "
                        "stale on disconnect\n",
                (unsigned long)cookie, (unsigned int)att_status);
        return 0;
    }
    if (!result_ok(&response, "send ATT response")) {
        fprintf(stderr,
                "ATT response rejected cookie=%lu status=0x%02x\n",
                (unsigned long)cookie, (unsigned int)att_status);
        return -1;
    }
    return 0;
}

static int send_gatt_value(struct star_service *service, uint16_t attr_id,
                           const uint8_t *value, uint16_t value_len,
                           int need_confirm, const char *label)
{
    struct bgp_result response;
    int status = bsa_gatt_proxy_send_indication(
        service->client, service->active_conn, attr_id, value, value_len,
        need_confirm, &response);

    if (status != BSA_GATT_PROXY_CLIENT_OK)
        return report_client_error(service->client, status, label);
    return result_ok(&response, label) ? 0 : -1;
}

static int reserve_standard_streams(void)
{
    int descriptor;

    for (descriptor = STDIN_FILENO; descriptor <= STDERR_FILENO;
         ++descriptor) {
        int replacement;
        int access_mode;

        if (fcntl(descriptor, F_GETFD) >= 0)
            continue;
        if (errno != EBADF)
            return -1;
        access_mode = descriptor == STDIN_FILENO ? O_RDONLY : O_WRONLY;
        replacement = open("/dev/null", access_mode);
        if (replacement < 0)
            return -1;
        if (replacement != descriptor) {
            if (dup2(replacement, descriptor) < 0) {
                (void)close(replacement);
                return -1;
            }
            (void)close(replacement);
        }
    }
    return 0;
}

static int set_fd_flags(int fd, int command, int flag)
{
    int current = fcntl(fd, command);

    if (current < 0)
        return -1;
    if (command == F_GETFD)
        return fcntl(fd, F_SETFD, current | flag);
    return fcntl(fd, F_SETFL, current | flag);
}

static void close_fd(int *fd)
{
    if (*fd >= 0) {
        (void)close(*fd);
        *fd = -1;
    }
}

static int ascii_hex_equal(char left, char right)
{
    if (left >= 'a' && left <= 'f')
        left = (char)(left - ('a' - 'A'));
    if (right >= 'a' && right <= 'f')
        right = (char)(right - ('a' - 'A'));
    return left == right;
}

static int ascii_token_equal(const char *token, size_t token_size,
                             const char *expected)
{
    size_t index;

    for (index = 0u; index < token_size; ++index) {
        if (expected[index] == '\0' ||
            !ascii_hex_equal(token[index], expected[index]))
            return 0;
    }
    return expected[token_size] == '\0';
}

static int proc_tcp_line_is_loopback_ptp_listener(const char *line)
{
    static const char wildcard_listener[] = "00000000:3D7C";
    static const char loopback_listener[] = "0100007F:3D7C";
    const char *cursor = line;
    const char *local = NULL;
    const char *state;
    size_t local_size = 0u;
    size_t state_size;
    unsigned int field;

    if (line == NULL)
        return 0;
    while (*cursor == ' ' || *cursor == '\t')
        ++cursor;
    /* Skip the slot, local endpoint, and remote endpoint tokens in order. */
    for (field = 0u; field < 3u; ++field) {
        const char *start = cursor;

        while (*cursor != '\0' && *cursor != ' ' && *cursor != '\t' &&
               *cursor != '\n' && *cursor != '\r')
            ++cursor;
        if (cursor == start)
            return 0;
        if (field == 1u) {
            local = start;
            local_size = (size_t)(cursor - start);
        }
        while (*cursor == ' ' || *cursor == '\t')
            ++cursor;
    }
    state = cursor;
    while (*cursor != '\0' && *cursor != ' ' && *cursor != '\t' &&
           *cursor != '\n' && *cursor != '\r')
        ++cursor;
    state_size = (size_t)(cursor - state);
    if (!ascii_token_equal(state, state_size, "0A"))
        return 0;
    return ascii_token_equal(local, local_size, wildcard_listener) ||
           ascii_token_equal(local, local_size, loopback_listener);
}

static int loopback_ptp_listener_ready(void)
{
    const char *path = getenv("OM1_STARS_PROC_NET_TCP");
    char line[512];
    FILE *stream;
    int ready = 0;

    if (path == NULL || path[0] == '\0')
        path = STAR_PROC_NET_TCP_DEFAULT;
    stream = fopen(path, "r");
    if (stream == NULL)
        return 0;
    while (fgets(line, sizeof(line), stream) != NULL) {
        if (proc_tcp_line_is_loopback_ptp_listener(line)) {
            ready = 1;
            break;
        }
    }
    (void)fclose(stream);
    return ready;
}

static void initialize_worker(struct detector_worker *worker)
{
    memset(worker, 0, sizeof(*worker));
    worker->pid = (pid_t)-1;
    worker->command_fd = -1;
    worker->event_fd = -1;
    worker->lease_fd = -1;
    worker->lifecycle = STAR_WORKER_ABSENT;
}

static int acquire_detector_lease(void)
{
    const char *path = getenv("OM1_STARS_LOCK");
    struct stat status;
    int fd;

    if (path == NULL || path[0] == '\0')
        path = STAR_DETECTOR_LOCK_DEFAULT;
    fd = open(path, O_CREAT | O_RDWR | O_NOFOLLOW, 0600);
    if (fd < 0)
        return STAR_LAUNCH_ERROR;
    if (fstat(fd, &status) != 0 || !S_ISREG(status.st_mode) ||
        status.st_uid != geteuid() || (status.st_mode & 0077) != 0) {
        (void)close(fd);
        errno = EPERM;
        return STAR_LAUNCH_ERROR;
    }
    if (flock(fd, LOCK_EX | LOCK_NB) != 0) {
        int saved_errno = errno;

        (void)close(fd);
        errno = saved_errno;
        return saved_errno == EWOULDBLOCK || saved_errno == EAGAIN
                   ? STAR_LAUNCH_BUSY
                   : STAR_LAUNCH_ERROR;
    }
    return fd;
}

static void release_worker_resources(struct detector_worker *worker)
{
    close_fd(&worker->command_fd);
    close_fd(&worker->event_fd);
    close_fd(&worker->lease_fd);
    initialize_worker(worker);
}

static int launch_worker(struct detector_worker *worker)
{
    const char *path = getenv("OM1_STARS_BINARY");
    int command_pipe[2];
    int event_pipe[2];
    int lease_fd;
    pid_t pid;
    sigset_t protected_signals;
    sigset_t original_mask;

    if (path == NULL || path[0] == '\0')
        path = STAR_DETECTOR_DEFAULT;
    lease_fd = acquire_detector_lease();
    if (lease_fd < 0)
        return lease_fd;
    if (pipe(command_pipe) != 0) {
        (void)close(lease_fd);
        return STAR_LAUNCH_ERROR;
    }
    if (pipe(event_pipe) != 0) {
        (void)close(command_pipe[0]);
        (void)close(command_pipe[1]);
        (void)close(lease_fd);
        return STAR_LAUNCH_ERROR;
    }
    if (set_fd_flags(command_pipe[0], F_GETFD, FD_CLOEXEC) != 0 ||
        set_fd_flags(command_pipe[1], F_GETFD, FD_CLOEXEC) != 0 ||
        set_fd_flags(event_pipe[0], F_GETFD, FD_CLOEXEC) != 0 ||
        set_fd_flags(event_pipe[1], F_GETFD, FD_CLOEXEC) != 0 ||
        set_fd_flags(event_pipe[0], F_GETFL, O_NONBLOCK) != 0) {
        (void)close(command_pipe[0]);
        (void)close(command_pipe[1]);
        (void)close(event_pipe[0]);
        (void)close(event_pipe[1]);
        (void)close(lease_fd);
        return STAR_LAUNCH_ERROR;
    }

    (void)sigemptyset(&protected_signals);
    (void)sigaddset(&protected_signals, SIGINT);
    (void)sigaddset(&protected_signals, SIGTERM);
    if (sigprocmask(SIG_BLOCK, &protected_signals, &original_mask) != 0) {
        (void)close(command_pipe[0]);
        (void)close(command_pipe[1]);
        (void)close(event_pipe[0]);
        (void)close(event_pipe[1]);
        (void)close(lease_fd);
        return STAR_LAUNCH_ERROR;
    }

    pid = fork();
    if (pid < 0) {
        (void)sigprocmask(SIG_SETMASK, &original_mask, NULL);
        (void)close(command_pipe[0]);
        (void)close(command_pipe[1]);
        (void)close(event_pipe[0]);
        (void)close(event_pipe[1]);
        (void)close(lease_fd);
        return STAR_LAUNCH_ERROR;
    }
    if (pid == 0) {
        char *const arguments[] = {(char *)path, (char *)"--worker", NULL};
        struct sigaction ignored;

        (void)close(command_pipe[1]);
        (void)close(event_pipe[0]);
        if (setsid() < 0)
            _exit(127);
        memset(&ignored, 0, sizeof(ignored));
        ignored.sa_handler = SIG_IGN;
        (void)sigemptyset(&ignored.sa_mask);
        if (sigaction(SIGINT, &ignored, NULL) != 0 ||
            sigaction(SIGTERM, &ignored, NULL) != 0 ||
            sigaction(SIGPIPE, &ignored, NULL) != 0 ||
            sigprocmask(SIG_SETMASK, &original_mask, NULL) != 0 ||
            dup2(command_pipe[0], STDIN_FILENO) < 0 ||
            dup2(event_pipe[1], STDOUT_FILENO) < 0)
            _exit(127);
        (void)close(command_pipe[0]);
        (void)close(event_pipe[1]);
        execve(path, arguments, environ);
        _exit(127);
    }

    (void)sigprocmask(SIG_SETMASK, &original_mask, NULL);
    (void)close(command_pipe[0]);
    (void)close(event_pipe[1]);
    initialize_worker(worker);
    worker->pid = pid;
    worker->command_fd = command_pipe[1];
    worker->event_fd = event_pipe[0];
    /* The worker inherits this lease across exec and owns it until exit. */
    worker->lease_fd = lease_fd;
    worker->lifecycle = STAR_WORKER_STARTING;
    printf("STAR worker started pid=%ld; awaiting startup snapshot\n",
           (long)pid);
    return 0;
}

static int send_worker_frame(struct detector_worker *worker, uint8_t type,
                             const uint8_t *payload, uint16_t payload_size)
{
    uint8_t frame[STAR_WORKER_FRAME_HEADER_SIZE +
                  STAR_WORKER_COMMAND_PAYLOAD_MAX];
    size_t frame_size = STAR_WORKER_FRAME_HEADER_SIZE + payload_size;
    ssize_t count;

    if (worker->command_fd < 0 ||
        payload_size > STAR_WORKER_COMMAND_PAYLOAD_MAX ||
        (payload_size != 0u && payload == NULL))
        return -1;
    put_le32(frame, STAR_WORKER_MAGIC);
    put_le16(frame + 4u, payload_size);
    frame[6] = STAR_WORKER_VERSION;
    frame[7] = type;
    if (payload_size != 0u)
        memcpy(frame + STAR_WORKER_FRAME_HEADER_SIZE, payload, payload_size);
    do {
        count = write(worker->command_fd, frame, frame_size);
    } while (count < 0 && errno == EINTR);
    return count == (ssize_t)frame_size ? 0 : -1;
}

static uint8_t failure_outcome(uint8_t safety)
{
    if (safety == STAR_WORKER_FAILURE_NO_MUTATION)
        return STAR_GATT_CAPTURE_REJECTED_NO_MUTATION;
    if (safety == STAR_WORKER_FAILURE_RESTORED)
        return STAR_GATT_CAPTURE_FAILED_SAFE;
    return STAR_GATT_CAPTURE_FAILED_UNSAFE;
}

static void clear_capture_recovery_observation(struct star_service *service)
{
    memset(&service->capture_recovery_observed_state, 0,
           sizeof(service->capture_recovery_observed_state));
    memset(&service->capture_recovery_observed_catalog, 0,
           sizeof(service->capture_recovery_observed_catalog));
}

static void retain_capture_recovery_target(struct star_service *service)
{
    if (!service->capture_recovery_pending ||
        !service->capture_recovery_state.valid ||
        !service->capture_recovery_catalog.valid)
        return;
    service->state = service->capture_recovery_state;
    (void)replace_public_catalog(service,
                                 &service->capture_recovery_catalog);
    /* A changed terminal bank may have reseeded the live recipe before an
     * uncertain failure. Restore the recipe captured with this target. */
    service->recipe = service->capture_recovery_recipe;
    if (!service->recipe.valid ||
        !tuple_legal_for_snapshot(
            service->recipe.value, service->recipe.value_len,
            &service->catalog, &service->state)) {
        if (recipe_from_state(&service->state, &service->catalog,
                              &service->recipe) != 0)
            memset(&service->recipe, 0, sizeof(service->recipe));
    }
    memset(&service->staged_catalog, 0, sizeof(service->staged_catalog));
    service->read_snapshot.valid = 0;
    service->state_dirty = 1;
}

static int state_exposure_matches(
    const struct public_value *left,
    const struct public_catalog_set *left_catalog,
    const struct public_value *right,
    const struct public_catalog_set *right_catalog)
{
    struct state_view left_view;
    struct state_view right_view;
    unsigned int kind;

    if (left == NULL || right == NULL || left_catalog == NULL ||
        right_catalog == NULL || !left->valid || !right->valid ||
        !validate_state_value(left->value, left->value_len, left_catalog,
                              &left_view) ||
        !validate_state_value(right->value, right->value_len, right_catalog,
                              &right_view))
        return 0;
    if (memcmp(left->value, right->value,
               2u * STAR_GATT_SETTING_COUNT) != 0)
        return 0;
    for (kind = 0u; kind < STAR_GATT_SETTING_COUNT; ++kind) {
        if (left_view.current[kind].length !=
                right_view.current[kind].length ||
            memcmp(left_view.current[kind].bytes,
                   right_view.current[kind].bytes,
                   left_view.current[kind].length) != 0)
            return 0;
    }
    return 1;
}

static void arm_capture_recovery(struct star_service *service)
{
    service->capture_recovery_pending = 1;
    service->capture_recovery_repairing = 0;
    service->capture_recovery_restore_in_flight = 0;
    service->capture_recovery_attempt_ms = STAR_WORKER_ATTEMPT_INTERVAL_MS;
    service->capture_recovery_state = service->state;
    service->capture_recovery_catalog = service->catalog;
    service->capture_recovery_recipe = service->recipe;
    memset(&service->capture_recovery_tuple, 0,
           sizeof(service->capture_recovery_tuple));
    if (recipe_from_state(&service->capture_recovery_state,
                          &service->capture_recovery_catalog,
                          &service->capture_recovery_tuple) != 0)
        fprintf(stderr,
                "STAR invariant failure: capture recovery target is not "
                "encodable\n");
    clear_capture_recovery_observation(service);
}

static void certify_capture_recovery(struct star_service *service)
{
    service->capture_recovery_pending = 0;
    service->capture_recovery_repairing = 0;
    service->capture_recovery_restore_in_flight = 0;
    service->capture_recovery_attempt_ms = STAR_WORKER_ATTEMPT_INTERVAL_MS;
    memset(&service->capture_recovery_state, 0,
           sizeof(service->capture_recovery_state));
    memset(&service->capture_recovery_catalog, 0,
           sizeof(service->capture_recovery_catalog));
    memset(&service->capture_recovery_recipe, 0,
           sizeof(service->capture_recovery_recipe));
    memset(&service->capture_recovery_tuple, 0,
           sizeof(service->capture_recovery_tuple));
    clear_capture_recovery_observation(service);
}

static void begin_capture_recovery_repair(struct star_service *service)
{
    if (!service->capture_recovery_pending)
        return;
    service->capture_recovery_repairing = 1;
    service->capture_recovery_restore_in_flight = 0;
    clear_capture_recovery_observation(service);
    retain_capture_recovery_target(service);
}

static void begin_failure_stream(struct star_service *service, uint8_t safety)
{
    clear_capture_stream(service);
    service->capture.outcome = failure_outcome(safety);
    service->operation = STAR_OPERATION_CAPTURE_STREAM;
    service->stream = STAR_STREAM_DONE_READY;
}

static void begin_worker_draining(struct star_service *service,
                                  uint8_t failure_safety,
                                  const char *reason, int close_event)
{
    struct detector_worker *worker = &service->worker;
    int recovery_repair;

    recovery_repair = service->capture_recovery_repairing ||
                      (service->capture_recovery_pending &&
                       failure_safety == STAR_WORKER_FAILURE_UNCERTAIN);
    if (recovery_repair)
        begin_capture_recovery_repair(service);
    reset_astro_assist_keep_awake_worker_attempt(&service->keep_awake);
    if (worker->lifecycle == STAR_WORKER_DRAINING)
        return;
    fprintf(stderr, "STAR worker generation=%llu draining capture=%llu; "
                    "replacement %s: %s\n",
            (unsigned long long)service->worker_generation,
            (unsigned long long)service->capture_sequence,
            service->capture_recovery_repairing
                ? "will restore retained capture target"
                : "will refresh authority",
            reason);
    if (service->operation == STAR_OPERATION_CAPTURE_PENDING &&
        service->active_conn != STAR_NO_CONNECTION) {
        begin_failure_stream(service, failure_safety);
    } else if (service->operation == STAR_OPERATION_SET_PENDING) {
        service->operation = STAR_OPERATION_IDLE;
    }
    memset(&service->pending_state_write, 0,
           sizeof(service->pending_state_write));
    service->capture_recovery_restore_in_flight = 0;
    cancel_astro_rate(service, "STAR worker/focal authority lost");
    cancel_astro_preposition(service, "STAR worker authority lost");
    if (service->capture_recovery_repairing)
        retain_capture_recovery_target(service);
    else
        invalidate_public_snapshot(service);
    clear_capture_recovery_observation(service);
    worker->lifecycle = STAR_WORKER_DRAINING;
    close_fd(&worker->command_fd);
    if (close_event)
        close_fd(&worker->event_fd);
}

static void store_capture_recovery_observation(
    struct star_service *service,
    const struct public_catalog_set *catalog,
    const uint8_t *state_value, uint16_t state_value_len)
{
    struct public_value *state = &service->capture_recovery_observed_state;

    service->capture_recovery_observed_catalog = *catalog;
    memset(state, 0, sizeof(*state));
    state->valid = 1;
    state->value_len = state_value_len;
    memcpy(state->value, state_value, state_value_len);
    memset(&service->staged_catalog, 0, sizeof(service->staged_catalog));
}

static void adopt_capture_recovery_observation(struct star_service *service,
                                               const char *reason)
{
    commit_public_snapshot(
        service, &service->capture_recovery_observed_catalog,
        service->capture_recovery_observed_state.value,
        service->capture_recovery_observed_state.value_len, 1);
    printf("STAR automatic recovery complete: %s\n", reason);
    certify_capture_recovery(service);
}

static void maybe_start_capture_recovery(struct star_service *service)
{
    if (!service->capture_recovery_repairing ||
        service->capture_recovery_restore_in_flight ||
        service->worker.lifecycle != STAR_WORKER_RUNNING ||
        service->operation != STAR_OPERATION_IDLE ||
        !service->capture_recovery_observed_state.valid ||
        !service->capture_recovery_observed_catalog.valid)
        return;

    if (state_exposure_matches(
            &service->capture_recovery_state,
            &service->capture_recovery_catalog,
            &service->capture_recovery_observed_state,
            &service->capture_recovery_observed_catalog)) {
        adopt_capture_recovery_observation(
            service, "replacement STARTUP already matches target");
        return;
    }
    if (!service->capture_recovery_tuple.valid) {
        adopt_capture_recovery_observation(
            service, "saved target could not be encoded; adopting fresh state");
        return;
    }

    service->operation = STAR_OPERATION_SET_PENDING;
    service->capture_recovery_restore_in_flight = 1;
    if (send_worker_frame(&service->worker, STAR_WORKER_COMMAND_SET,
                          service->capture_recovery_tuple.value,
                          service->capture_recovery_tuple.value_len) != 0) {
        begin_worker_draining(service, STAR_WORKER_FAILURE_UNCERTAIN,
                              "automatic recovery SET write failed", 0);
        return;
    }
    printf("STAR automatic recovery SET submitted\n");
}

static size_t build_done_value(const struct capture_stream *capture,
                               uint8_t value[STAR_GATT_DONE_OK_MAX_SIZE])
{
    size_t offset = 0u;
    unsigned int kind;

    memset(value, 0, STAR_GATT_DONE_OK_MAX_SIZE);
    value[offset++] = STAR_GATT_VALUE_DONE;
    value[offset++] = capture->outcome;
    put_le16(value + offset,
             capture->outcome == STAR_GATT_CAPTURE_OK ? capture->total : 0u);
    offset += 2u;
    if (capture->outcome != STAR_GATT_CAPTURE_OK)
        return STAR_GATT_DONE_FAILURE_SIZE;
    for (kind = 0u; kind < STAR_GATT_SETTING_COUNT; ++kind) {
        put_le16(value + offset, (uint16_t)capture->settings[kind]);
        offset += 2u;
    }
    put_le32(value + offset, capture->focal_length_f32_bits);
    offset += 4u;
    value[offset++] = capture->lens_name_length;
    if (capture->lens_name_length != 0u) {
        memcpy(value + offset, capture->lens_name,
               capture->lens_name_length);
        offset += capture->lens_name_length;
    }
    return offset;
}

static size_t build_peaks_value(
    const struct capture_stream *capture,
    uint8_t value[STAR_GATT_ATT_ATTRIBUTE_VALUE_MAX], uint8_t count)
{
    size_t offset = STAR_GATT_PEAKS_PREFIX_SIZE;
    unsigned int index;

    memset(value, 0, STAR_GATT_ATT_ATTRIBUTE_VALUE_MAX);
    value[0] = STAR_GATT_VALUE_PEAKS;
    for (index = 0u; index < count; ++index) {
        const struct star_worker_peak *peak =
            &capture->peaks[capture->next_peak + index];

        put_le16(value + offset, peak->x);
        put_le16(value + offset + 2u, peak->y);
        put_le32(value + offset + 4u, peak->flux);
        offset += STAR_GATT_PEAK_WIRE_SIZE;
    }
    return offset;
}

static void stall_control_stream(struct star_service *service,
                                 const char *reason)
{
    fprintf(stderr, "STAR CONTROL stream stalled: %s\n", reason);
    service->stream = STAR_STREAM_STALLED;
}

static void pump_control_stream(struct star_service *service)
{
    struct capture_stream *capture = &service->capture;
    uint8_t value[STAR_GATT_ATT_ATTRIBUTE_VALUE_MAX];
    size_t value_len;

    if (service->operation != STAR_OPERATION_CAPTURE_STREAM ||
        service->congested || service->active_conn == STAR_NO_CONNECTION ||
        !service->control_indicate_enabled)
        return;

    if (service->stream == STAR_STREAM_PEAK_READY) {
        size_t att_capacity;
        size_t peak_capacity;
        uint16_t remaining;
        uint8_t count;

        if (capture->next_peak >= capture->total) {
            service->stream = STAR_STREAM_DONE_READY;
            return;
        }
        if (service->active_mtu <= STAR_GATT_ATT_VALUE_OVERHEAD)
            return;
        att_capacity = service->active_mtu - STAR_GATT_ATT_VALUE_OVERHEAD;
        if (att_capacity > STAR_GATT_ATT_ATTRIBUTE_VALUE_MAX)
            att_capacity = STAR_GATT_ATT_ATTRIBUTE_VALUE_MAX;
        if (att_capacity <= STAR_GATT_PEAKS_PREFIX_SIZE)
            return;
        peak_capacity =
            (att_capacity - STAR_GATT_PEAKS_PREFIX_SIZE) /
            STAR_GATT_PEAK_WIRE_SIZE;
        if (peak_capacity > STAR_GATT_PEAKS_MAX_PER_VALUE)
            peak_capacity = STAR_GATT_PEAKS_MAX_PER_VALUE;
        if (peak_capacity == 0u)
            return;
        remaining = (uint16_t)(capture->total - capture->next_peak);
        count = (uint8_t)(remaining < peak_capacity ? remaining
                                                    : peak_capacity);
        value_len = build_peaks_value(capture, value, count);
        if (send_gatt_value(service, service->control_attr, value,
                            (uint16_t)value_len, 1,
                            "send STAR PEAKS indication") != 0) {
            stall_control_stream(service, "PEAKS submission failed");
            return;
        }
        capture->pending_peak_count = count;
        service->stream = STAR_STREAM_WAIT_PEAK_CONFIRM;
        return;
    }

    if (service->stream != STAR_STREAM_DONE_READY)
        return;
    value_len = build_done_value(capture, value);
    if (value_len + STAR_GATT_ATT_VALUE_OVERHEAD > service->active_mtu) {
        stall_control_stream(service, "DONE exceeds negotiated MTU");
        return;
    }
    if (send_gatt_value(service, service->control_attr, value,
                        (uint16_t)value_len, 1,
                        "send STAR DONE indication") != 0) {
        stall_control_stream(service, "DONE submission failed");
        return;
    }
    service->stream = STAR_STREAM_WAIT_DONE_CONFIRM;
}

static void complete_control_confirmation(
    struct star_service *service, const struct bgp_confirm_event *confirm)
{
    struct capture_stream *capture = &service->capture;

    if (service->operation != STAR_OPERATION_CAPTURE_STREAM ||
        confirm->conn_id != service->active_conn ||
        confirm->attr_id != service->control_attr ||
        (service->stream != STAR_STREAM_WAIT_PEAK_CONFIRM &&
         service->stream != STAR_STREAM_WAIT_DONE_CONFIRM))
        return;
    if (confirm->bsa_status != 0u) {
        stall_control_stream(service, "indication confirmation failed");
        return;
    }
    if (service->stream == STAR_STREAM_WAIT_PEAK_CONFIRM) {
        capture->next_peak =
            (uint16_t)(capture->next_peak + capture->pending_peak_count);
        capture->pending_peak_count = 0u;
        service->stream = capture->next_peak < capture->total
                              ? STAR_STREAM_PEAK_READY
                              : STAR_STREAM_DONE_READY;
        return;
    }
    printf("STAR capture complete outcome=%u total=%u\n",
           (unsigned int)capture->outcome, (unsigned int)capture->total);
    clear_capture_stream(service);
    service->operation = STAR_OPERATION_IDLE;
    if (service->state.valid)
        service->state_dirty = 1;
}

static void maybe_publish_state(struct star_service *service)
{
    int64_t now;
    int64_t minimum_interval_ms;

    rebuild_unified_state(service);
    if (!service->unified_state.valid || !service->state_dirty ||
        !service->state_notify_enabled || service->congested ||
        service->active_conn == STAR_NO_CONNECTION ||
        service->unified_state.value_len + STAR_GATT_ATT_VALUE_OVERHEAD >
            service->active_mtu)
        return;
    now = milliseconds_now();
    minimum_interval_ms =
        (service->keep_awake.active && now >= 0 &&
         now < service->keep_awake.deadline_ms) ||
                service->operation != STAR_OPERATION_IDLE ||
                service->capture_recovery_pending
            ? INT64_C(100)
            : INT64_C(1000);
    if (now < 0 ||
        (service->last_state_notify_ms != 0 &&
         now - service->last_state_notify_ms < minimum_interval_ms))
        return;
    if (send_gatt_value(service, service->state_attr,
                        service->unified_state.value,
                        service->unified_state.value_len, 0,
                        "send STAR STATE notification") == 0) {
        service->state_dirty = 0;
        service->last_state_notify_ms = now;
    }
}

static void maybe_sample_astro_assist(struct star_service *service)
{
    struct astro_assist_ibis *ibis = &service->astro_assist;
    int64_t now;

    if (service->active_conn == STAR_NO_CONNECTION ||
        !astro_assist_source_mapped(ibis)) {
        ibis->next_poll_ms = 0;
        return;
    }
    now = milliseconds_now();
    if (now < 0)
        return;
    if (ibis->next_poll_ms != 0 && now < ibis->next_poll_ms)
        return;
    sample_astro_assist_ibis(ibis);
    ibis->next_poll_ms = now + ASTRO_ASSIST_IBIS_POLL_MS;
}

static void maybe_publish_astro_assist(struct star_service *service)
{
    struct astro_assist_ibis *ibis = &service->astro_assist;

    if (!ibis->dirty || !ibis->notify_enabled || service->congested ||
        service->active_conn == STAR_NO_CONNECTION)
        return;
    if (send_gatt_value(service, service->astro_assist_ibis_attr,
                        ibis->value, ASTRO_ASSIST_GATT_IBIS_STATE_SIZE, 0,
                        "send ASTRO ASSIST IBIS notification") == 0)
        ibis->dirty = 0;
}

static void maybe_sample_astro_assist_acceleration(
    struct star_service *service)
{
    struct astro_assist_acceleration *acceleration =
        &service->acceleration;
    int64_t now;

    if (service->active_conn == STAR_NO_CONNECTION ||
        !astro_assist_acceleration_source_mapped(acceleration)) {
        acceleration->next_poll_ms = 0;
        return;
    }
    now = milliseconds_now();
    if (now < 0)
        return;
    if (acceleration->next_poll_ms != 0 &&
        now < acceleration->next_poll_ms)
        return;
    sample_astro_assist_acceleration(acceleration);
    acceleration->next_poll_ms = now + ASTRO_ASSIST_ACCEL_POLL_MS;
}

static void maybe_publish_astro_assist_acceleration(
    struct star_service *service)
{
    struct astro_assist_acceleration *acceleration =
        &service->acceleration;

    if (!acceleration->dirty || !acceleration->notify_enabled ||
        service->congested || service->active_conn == STAR_NO_CONNECTION)
        return;
    if (send_gatt_value(
            service, service->astro_assist_acceleration_attr,
            acceleration->value,
            ASTRO_ASSIST_GATT_ACCELERATION_STATE_SIZE, 0,
            "send ASTRO ASSIST CAMERA ACCELERATION notification") == 0)
        acceleration->dirty = 0;
}

static void maybe_sample_gyro_attitude(struct star_service *service)
{
    struct gyro_attitude_telemetry *gyro = &service->gyro;
    int64_t now = milliseconds_now();

    if (service->active_conn == STAR_NO_CONNECTION || now < 0)
        return;
    if (gyro->next_poll_ms != 0 && now < gyro->next_poll_ms)
        return;
    sample_gyro_attitude(gyro);
    gyro->next_poll_ms = now + GYRO_ATTITUDE_POLL_MS;
}

static int process_worker_catalog(struct star_service *service,
                                  const uint8_t *payload,
                                  uint16_t payload_size)
{
    struct public_catalog_set candidate;
    size_t offset = 6u;
    unsigned int kind;

    if (service->staged_catalog.valid || payload_size < 15u)
        return -1;
    memset(&candidate, 0, sizeof(candidate));
    candidate.valid = 1;
    for (kind = 0u; kind < STAR_GATT_SETTING_COUNT; ++kind) {
        uint16_t size = get_le16(payload + 2u * kind);

        if (size < STAR_GATT_CATALOG_MIN_SIZE ||
            size > STAR_GATT_CATALOG_MAX_SIZE ||
            offset + size > payload_size ||
            !validate_catalog_value(payload + offset, size))
            return -1;
        candidate.kind[kind].valid = 1;
        candidate.kind[kind].value_len = size;
        memcpy(candidate.kind[kind].value, payload + offset, size);
        offset += size;
    }
    if (offset != payload_size)
        return -1;
    service->staged_catalog = candidate;
    return 0;
}

static int process_worker_state(struct star_service *service,
                                const uint8_t *payload,
                                uint16_t payload_size)
{
    const struct public_catalog_set *catalog;
    struct state_view view;
    uint16_t value_size;
    uint8_t reason;

    if (payload_size < 3u)
        return -1;
    value_size = get_le16(payload);
    reason = payload[2];
    if ((size_t)value_size + 3u != payload_size ||
        value_size < STAR_GATT_STATE_MIN_SIZE ||
        value_size > STAR_GATT_STATE_MAX_SIZE)
        return -1;
    if (service->staged_catalog.valid)
        catalog = &service->staged_catalog;
    else if (service->capture_recovery_repairing &&
             service->capture_recovery_observed_catalog.valid)
        catalog = &service->capture_recovery_observed_catalog;
    else
        catalog = &service->catalog;
    if (!validate_state_value(payload + 3u, value_size, catalog, &view))
        return -1;

    if (service->worker.lifecycle == STAR_WORKER_STARTING) {
        if (reason != STAR_WORKER_STATE_STARTUP ||
            !service->staged_catalog.valid ||
            (service->operation != STAR_OPERATION_IDLE &&
             service->operation != STAR_OPERATION_CAPTURE_STREAM))
            return -1;
        service->worker.lifecycle = STAR_WORKER_RUNNING;
        service->worker.startup_complete = 1;
        service->next_worker_attempt_ms = 0;
        if (service->capture_recovery_repairing) {
            store_capture_recovery_observation(
                service, catalog, payload + 3u, value_size);
            retain_capture_recovery_target(service);
            printf("STAR recovery worker STARTUP retained privately\n");
            maybe_start_capture_recovery(service);
        } else {
            commit_public_snapshot(service, catalog, payload + 3u,
                                   value_size, 1);
            printf("STAR worker startup snapshot installed\n");
        }
        return 0;
    }
    if (service->worker.lifecycle != STAR_WORKER_RUNNING)
        return 0;

    if (reason == STAR_WORKER_STATE_OBSERVED) {
        if (service->capture_recovery_repairing) {
            store_capture_recovery_observation(
                service, catalog, payload + 3u, value_size);
            retain_capture_recovery_target(service);
            maybe_start_capture_recovery(service);
        } else {
            /* Busy operations suppress publication until terminal STATE. */
            commit_public_snapshot(service, catalog, payload + 3u,
                                   value_size, 0);
        }
        return 0;
    }

    if (reason == STAR_WORKER_STATE_SET_APPLIED ||
        reason == STAR_WORKER_STATE_SET_REJECTED) {
        if (service->operation != STAR_OPERATION_SET_PENDING)
            return -1;
        if (service->capture_recovery_restore_in_flight) {
            if (!service->capture_recovery_repairing)
                return -1;
            store_capture_recovery_observation(
                service, catalog, payload + 3u, value_size);
            service->operation = STAR_OPERATION_IDLE;
            service->capture_recovery_restore_in_flight = 0;
            if (reason == STAR_WORKER_STATE_SET_REJECTED) {
                adopt_capture_recovery_observation(
                    service,
                    "SET rejected coherently; adopted changed camera context");
                return 0;
            }
            if (state_exposure_matches(
                    &service->capture_recovery_state,
                    &service->capture_recovery_catalog,
                    &service->capture_recovery_observed_state,
                    &service->capture_recovery_observed_catalog)) {
                adopt_capture_recovery_observation(
                    service, "SET_APPLIED exactly verified target");
                return 0;
            }
            fprintf(stderr,
                    "STAR automatic recovery SET_APPLIED mismatch; "
                    "retrying with retained target\n");
            begin_worker_draining(
                service, STAR_WORKER_FAILURE_UNCERTAIN,
                "automatic recovery SET_APPLIED did not match target", 0);
            return 0;
        }
        if (service->capture_recovery_repairing)
            return -1;
        commit_public_snapshot(service, catalog, payload + 3u, value_size, 1);
        if (service->pending_state_write.active) {
            if (reason == STAR_WORKER_STATE_SET_APPLIED &&
                state_matches_tuple(&service->state, &service->catalog,
                                    &service->pending_state_write.live_target)) {
                service->recipe =
                    service->pending_state_write.recipe_target;
                memcpy(service->detector_config,
                       service->pending_state_write.detector,
                       sizeof(service->detector_config));
                service->detector_config_valid = 1;
                printf("STAR STATE write live target certified; "
                       "recipe and detector committed\n");
            } else {
                printf("STAR STATE write did not certify live target; "
                       "prior recipe and detector retained\n");
            }
            memset(&service->pending_state_write, 0,
                   sizeof(service->pending_state_write));
        }
        service->operation = STAR_OPERATION_IDLE;
        rebuild_unified_state(service);
        printf("STAR settings terminal STATE installed reason=%u\n",
               (unsigned int)reason);
        return 0;
    }

    if (reason == STAR_WORKER_STATE_CAPTURE_RESTORED ||
        reason == STAR_WORKER_STATE_CAPTURE_REJECTED) {
        if (service->operation != STAR_OPERATION_CAPTURE_PENDING ||
            service->capture_state_reason != 0u)
            return -1;
        commit_public_snapshot(service, catalog, payload + 3u, value_size, 1);
        service->capture_state_reason = reason;
        if (service->preposition.capture_owned)
            cancel_astro_preposition(service, "STAR capture completed/rejected");
        return 0;
    }
    return -1;
}

static int process_worker_capture_result(struct star_service *service,
                                         const uint8_t *payload,
                                         uint16_t payload_size)
{
    struct capture_stream capture;
    uint8_t outcome;
    uint8_t lens_length;
    uint16_t peak_count;
    uint32_t focal_bits;
    size_t expected;
    size_t peak_offset = STAR_WORKER_CAPTURE_RESULT_PREFIX_SIZE;
    unsigned int kind;
    unsigned int index;

    if (service->worker.lifecycle != STAR_WORKER_RUNNING ||
        service->operation != STAR_OPERATION_CAPTURE_PENDING ||
        service->staged_catalog.valid ||
        payload_size < STAR_WORKER_CAPTURE_RESULT_PREFIX_SIZE)
        return -1;
    outcome = payload[0];
    peak_count = get_le16(payload + 1u);
    lens_length = payload[9];
    focal_bits = get_le32(payload + 10u);
    if (outcome == STAR_WORKER_CAPTURE_REJECTED_NO_MUTATION) {
        if (service->capture_state_reason !=
                STAR_WORKER_STATE_CAPTURE_REJECTED ||
            payload_size != STAR_WORKER_CAPTURE_RESULT_PREFIX_SIZE ||
            !bytes_zero(payload + 1u,
                        STAR_WORKER_CAPTURE_RESULT_PREFIX_SIZE - 1u))
            return -1;
        clear_capture_stream(service);
        service->capture.outcome = STAR_GATT_CAPTURE_REJECTED_NO_MUTATION;
        certify_capture_recovery(service);
        service->operation = STAR_OPERATION_CAPTURE_STREAM;
        service->stream = STAR_STREAM_DONE_READY;
        return 0;
    }
    if (outcome != STAR_WORKER_CAPTURE_OK ||
        service->capture_state_reason != STAR_WORKER_STATE_CAPTURE_RESTORED ||
        peak_count > STAR_GATT_MAX_PEAKS ||
        lens_length > STAR_GATT_LENS_NAME_MAX ||
        (focal_bits != 0u && !focal_length_valid(focal_bits)))
        return -1;
    expected = STAR_WORKER_CAPTURE_RESULT_PREFIX_SIZE +
               (size_t)peak_count * STAR_GATT_PEAK_WIRE_SIZE + lens_length;
    if (expected != payload_size ||
        !printable_lens(payload + STAR_WORKER_CAPTURE_RESULT_PREFIX_SIZE +
                            (size_t)peak_count * STAR_GATT_PEAK_WIRE_SIZE,
                        lens_length))
        return -1;

    memset(&capture, 0, sizeof(capture));
    capture.outcome = STAR_GATT_CAPTURE_OK;
    capture.total = peak_count;
    for (kind = 0u; kind < STAR_GATT_SETTING_COUNT; ++kind)
        capture.settings[kind] = (int16_t)get_le16(payload + 3u + 2u * kind);
    capture.focal_length_f32_bits = focal_bits;
    capture.lens_name_length = lens_length;
    for (index = 0u; index < peak_count; ++index) {
        struct star_worker_peak *peak = &capture.peaks[index];

        peak->x = get_le16(payload + peak_offset);
        peak->y = get_le16(payload + peak_offset + 2u);
        peak->flux = get_le32(payload + peak_offset + 4u);
        if (peak->x >= STAR_GATT_Y_WIDTH || peak->y >= STAR_GATT_Y_HEIGHT ||
            (index > 0u && peak->flux > capture.peaks[index - 1u].flux))
            return -1;
        peak_offset += STAR_GATT_PEAK_WIRE_SIZE;
    }
    if (lens_length != 0u)
        memcpy(capture.lens_name, payload + peak_offset, lens_length);
    service->capture = capture;
    certify_capture_recovery(service);
    service->capture_state_reason = 0u;
    service->operation = STAR_OPERATION_CAPTURE_STREAM;
    service->stream = peak_count == 0u ? STAR_STREAM_DONE_READY
                                      : STAR_STREAM_PEAK_READY;
    printf("STAR worker terminal capture result total=%u\n",
           (unsigned int)peak_count);
    return 0;
}

static int process_worker_failed(struct star_service *service,
                                 const uint8_t *payload,
                                 uint16_t payload_size)
{
    uint8_t safety;

    if (payload_size != STAR_WORKER_FAILED_PAYLOAD_SIZE ||
        service->worker.lifecycle == STAR_WORKER_DRAINING)
        return -1;
    safety = payload[0];
    if (safety != STAR_WORKER_FAILURE_NO_MUTATION &&
        safety != STAR_WORKER_FAILURE_RESTORED &&
        safety != STAR_WORKER_FAILURE_UNCERTAIN)
        return -1;
    if (safety != STAR_WORKER_FAILURE_UNCERTAIN &&
        !service->capture_recovery_repairing)
        certify_capture_recovery(service);
    begin_worker_draining(service, safety, "terminal FAILED event", 0);
    return 0;
}

static int process_worker_keep_awake_result(
    struct star_service *service, const uint8_t *payload,
    uint16_t payload_size)
{
    uint8_t result;

    if (service->worker.lifecycle != STAR_WORKER_RUNNING ||
        !service->keep_awake.in_flight ||
        payload_size != STAR_WORKER_KEEP_AWAKE_RESULT_PAYLOAD_SIZE)
        return -1;
    result = payload[0];
    if (result != STAR_WORKER_KEEP_AWAKE_OK &&
        result != STAR_WORKER_KEEP_AWAKE_RECOVERED)
        return -1;
    service->keep_awake.in_flight = 0;
    printf("ASTRO ASSIST keep-awake worker result=%s\n",
           result == STAR_WORKER_KEEP_AWAKE_RECOVERED ? "recovered" : "ok");
    return 0;
}

static int worker_payload_size_valid(uint8_t type, uint16_t payload_size)
{
    switch (type) {
    case STAR_WORKER_EVENT_CATALOG:
        return payload_size >= 15u &&
               payload_size <= STAR_WORKER_CATALOG_PAYLOAD_MAX;
    case STAR_WORKER_EVENT_STATE:
        return payload_size >= STAR_WORKER_STATE_PAYLOAD_MIN &&
               payload_size <= STAR_WORKER_STATE_PAYLOAD_MAX;
    case STAR_WORKER_EVENT_CAPTURE_RESULT:
        return payload_size >= STAR_WORKER_CAPTURE_RESULT_PREFIX_SIZE &&
               payload_size <= STAR_WORKER_CAPTURE_RESULT_PAYLOAD_MAX;
    case STAR_WORKER_EVENT_FAILED:
        return payload_size == STAR_WORKER_FAILED_PAYLOAD_SIZE;
    case STAR_WORKER_EVENT_KEEP_AWAKE_RESULT:
        return payload_size == STAR_WORKER_KEEP_AWAKE_RESULT_PAYLOAD_SIZE;
    default:
        return 0;
    }
}

static int process_worker_frame(struct star_service *service,
                                const uint8_t *frame, size_t frame_size)
{
    uint16_t payload_size = get_le16(frame + 4u);
    uint8_t type = frame[7];
    const uint8_t *payload = frame + STAR_WORKER_FRAME_HEADER_SIZE;

    if (frame_size != STAR_WORKER_FRAME_HEADER_SIZE + payload_size)
        return -1;
    if (service->worker.lifecycle == STAR_WORKER_DRAINING) {
        if (type == STAR_WORKER_EVENT_FAILED &&
            payload_size == STAR_WORKER_FAILED_PAYLOAD_SIZE) {
            uint8_t safety = payload[0];

            if (safety != STAR_WORKER_FAILURE_NO_MUTATION &&
                safety != STAR_WORKER_FAILURE_RESTORED &&
                safety != STAR_WORKER_FAILURE_UNCERTAIN)
                return -1;
            if (safety == STAR_WORKER_FAILURE_UNCERTAIN ||
                service->capture_recovery_repairing)
                begin_capture_recovery_repair(service);
            else
                certify_capture_recovery(service);
        }
        return 0;
    }
    if (service->staged_catalog.valid && type != STAR_WORKER_EVENT_STATE &&
        type != STAR_WORKER_EVENT_FAILED)
        return -1;
    switch (type) {
    case STAR_WORKER_EVENT_CATALOG:
        return process_worker_catalog(service, payload, payload_size);
    case STAR_WORKER_EVENT_STATE:
        return process_worker_state(service, payload, payload_size);
    case STAR_WORKER_EVENT_CAPTURE_RESULT:
        return process_worker_capture_result(service, payload, payload_size);
    case STAR_WORKER_EVENT_FAILED:
        return process_worker_failed(service, payload, payload_size);
    case STAR_WORKER_EVENT_KEEP_AWAKE_RESULT:
        return process_worker_keep_awake_result(service, payload,
                                                payload_size);
    default:
        return -1;
    }
}

static int drain_worker_events(struct star_service *service)
{
    struct detector_worker *worker = &service->worker;

    while (worker->event_fd >= 0) {
        size_t wanted;
        ssize_t count;

        if (worker->frame_expected == 0u)
            wanted = STAR_WORKER_FRAME_HEADER_SIZE - worker->frame_len;
        else
            wanted = worker->frame_expected - worker->frame_len;
        count = read(worker->event_fd, worker->frame + worker->frame_len,
                     wanted);
        if (count > 0) {
            worker->frame_len += (size_t)count;
            if (worker->frame_expected == 0u &&
                worker->frame_len == STAR_WORKER_FRAME_HEADER_SIZE) {
                uint16_t payload_size = get_le16(worker->frame + 4u);
                uint8_t type = worker->frame[7];

                if (get_le32(worker->frame) != STAR_WORKER_MAGIC ||
                    worker->frame[6] != STAR_WORKER_VERSION ||
                    !worker_payload_size_valid(type, payload_size) ||
                    STAR_WORKER_FRAME_HEADER_SIZE + payload_size >
                        sizeof(worker->frame)) {
                    begin_worker_draining(
                        service, STAR_WORKER_FAILURE_UNCERTAIN,
                        "invalid worker frame header", 1);
                    return -1;
                }
                worker->frame_expected =
                    STAR_WORKER_FRAME_HEADER_SIZE + payload_size;
            }
            if (worker->frame_expected != 0u &&
                worker->frame_len == worker->frame_expected) {
                size_t frame_size = worker->frame_expected;

                if (process_worker_frame(service, worker->frame,
                                         frame_size) != 0) {
                    begin_worker_draining(
                        service, STAR_WORKER_FAILURE_UNCERTAIN,
                        "malformed or out-of-order worker frame", 1);
                    return -1;
                }
                worker->frame_len = 0u;
                worker->frame_expected = 0u;
            }
            continue;
        }
        if (count == 0) {
            int partial = worker->frame_len != 0u;

            close_fd(&worker->event_fd);
            if (worker->lifecycle != STAR_WORKER_DRAINING || partial) {
                begin_worker_draining(
                    service, STAR_WORKER_FAILURE_UNCERTAIN,
                    partial ? "partial worker frame at EOF"
                            : "unexpected worker event EOF",
                    0);
                return -1;
            }
            return 0;
        }
        if (errno == EINTR)
            continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK)
            return 0;
        begin_worker_draining(service, STAR_WORKER_FAILURE_UNCERTAIN,
                              "worker event pipe read failed", 1);
        return -1;
    }
    return 0;
}

static int64_t schedule_worker_attempt(struct star_service *service,
                                       int64_t now,
                                       int advance_recovery_backoff)
{
    int64_t delay = STAR_WORKER_ATTEMPT_INTERVAL_MS;

    if (service->capture_recovery_repairing) {
        delay = service->capture_recovery_attempt_ms;
        if (delay < STAR_WORKER_ATTEMPT_INTERVAL_MS)
            delay = STAR_WORKER_ATTEMPT_INTERVAL_MS;
        if (delay > STAR_RECOVERY_ATTEMPT_MAX_MS)
            delay = STAR_RECOVERY_ATTEMPT_MAX_MS;
        if (advance_recovery_backoff) {
            if (delay >= STAR_RECOVERY_ATTEMPT_MAX_MS / 2)
                service->capture_recovery_attempt_ms =
                    STAR_RECOVERY_ATTEMPT_MAX_MS;
            else
                service->capture_recovery_attempt_ms = delay * 2;
        }
    }
    service->next_worker_attempt_ms = now >= 0 ? now + delay : 0;
    return delay;
}

static void update_worker(struct star_service *service)
{
    struct detector_worker *worker = &service->worker;
    int restart_suppressed;
    int startup_complete;
    int wait_status;
    pid_t status;

    (void)drain_worker_events(service);
    if (worker->pid < 0)
        return;
    do {
        status = waitpid(worker->pid, &wait_status, WNOHANG);
    } while (status < 0 && errno == EINTR);
    if (status == 0)
        return;
    if (status == worker->pid) {
        pid_t reaped = worker->pid;

        (void)drain_worker_events(service);
        if (worker->lifecycle != STAR_WORKER_DRAINING)
            begin_worker_draining(service, STAR_WORKER_FAILURE_UNCERTAIN,
                                  "worker exited without terminal EOF", 1);
        if (WIFEXITED(wait_status))
            printf("STAR worker exited pid=%ld code=%d status=0x%x\n",
                   (long)reaped, WEXITSTATUS(wait_status), wait_status);
        else if (WIFSIGNALED(wait_status))
            printf("STAR worker exited pid=%ld signal=%d core=%d "
                   "status=0x%x\n",
                   (long)reaped, WTERMSIG(wait_status),
#ifdef WCOREDUMP
                   WCOREDUMP(wait_status) != 0,
#else
                   0,
#endif
                   wait_status);
        else
            printf("STAR worker exited pid=%ld status=0x%x\n",
                   (long)reaped, wait_status);
    } else {
        begin_worker_draining(service, STAR_WORKER_FAILURE_UNCERTAIN,
                              "waitpid failed", 1);
    }
    if (service->capture_recovery_pending)
        begin_capture_recovery_repair(service);
    /* The final drain above can install a coherent STARTUP after waitpid. */
    startup_complete = worker->startup_complete;
    restart_suppressed = worker->restart_suppressed;
    release_worker_resources(worker);
    if (!restart_suppressed &&
        service->active_conn != STAR_NO_CONNECTION && !stop_requested) {
        int64_t now = milliseconds_now();
        int64_t delay;

        delay = schedule_worker_attempt(service, now, 1);
        fprintf(stderr, "STAR worker exited %s coherent STARTUP; retry in "
                        "%lld ms\n",
                startup_complete ? "after" : "before",
                (long long)delay);
    }
}

static void ensure_worker_for_peer(struct star_service *service)
{
    int64_t now;
    int launch_status;

    if (service->active_conn == STAR_NO_CONNECTION || stop_requested ||
        service->worker.lifecycle != STAR_WORKER_ABSENT)
        return;
    now = milliseconds_now();
    if (now >= 0 && service->next_worker_attempt_ms > now)
        return;
    if (!loopback_ptp_listener_ready()) {
        if (!service->ptp_listener_wait_reported) {
            fprintf(stderr,
                    "STAR worker waiting for 127.0.0.1:15740 LISTEN; "
                    "recheck in %lld ms\n",
                    (long long)STAR_WORKER_ATTEMPT_INTERVAL_MS);
            service->ptp_listener_wait_reported = 1;
        }
        (void)schedule_worker_attempt(service, now, 0);
        return;
    }
    if (service->ptp_listener_wait_reported) {
        printf("STAR loopback PTP listener is ready\n");
        service->ptp_listener_wait_reported = 0;
    }
    launch_status = launch_worker(&service->worker);
    if (launch_status == 0) {
        service->worker_generation =
            next_nonzero_generation(service->worker_generation);
        service->next_worker_attempt_ms = 0;
        printf("STAR worker generation=%llu pid=%ld recovery=%d\n",
               (unsigned long long)service->worker_generation,
               (long)service->worker.pid,
               service->capture_recovery_repairing);
        return;
    }
    {
        int64_t delay = schedule_worker_attempt(service, now, 1);

        if (launch_status == STAR_LAUNCH_BUSY)
            fprintf(stderr, "STAR worker lock is already held; retry in "
                            "%lld ms\n",
                    (long long)delay);
        else {
            perror("launch om1-stars --worker");
            fprintf(stderr, "STAR worker launch retry in %lld ms\n",
                    (long long)delay);
        }
    }
}

static int worker_attempt_poll_timeout_ms(
    const struct star_service *service, int current_timeout_ms)
{
    int64_t now;
    int64_t remaining = STAR_WORKER_ATTEMPT_INTERVAL_MS;

    if (service->active_conn == STAR_NO_CONNECTION || stop_requested ||
        service->worker.lifecycle != STAR_WORKER_ABSENT)
        return current_timeout_ms;
    now = milliseconds_now();
    if (now >= 0 && service->next_worker_attempt_ms != 0) {
        remaining = service->next_worker_attempt_ms - now;
        if (remaining <= 0)
            return 0;
    }
    return remaining < current_timeout_ms ? (int)remaining
                                          : current_timeout_ms;
}

static int worker_accepts_command(const struct star_service *service)
{
    return service->worker.lifecycle == STAR_WORKER_RUNNING &&
           service->preposition.pending_cookie == 0u &&
           service->operation == STAR_OPERATION_IDLE &&
           !service->staged_catalog.valid &&
           !service->capture_recovery_pending;
}

static int worker_accepts_keep_awake_command(
    const struct star_service *service)
{
    return service->worker.lifecycle == STAR_WORKER_RUNNING &&
           (service->operation == STAR_OPERATION_IDLE ||
            service->operation == STAR_OPERATION_CAPTURE_STREAM) &&
           !service->staged_catalog.valid &&
           !service->capture_recovery_repairing;
}

static void expire_astro_assist_keep_awake(struct star_service *service)
{
    struct astro_assist_keep_awake *keep_awake = &service->keep_awake;
    int64_t now;

    if (service->active_conn == STAR_NO_CONNECTION || !keep_awake->active)
        return;
    now = milliseconds_now();
    if (now >= 0 && now >= keep_awake->deadline_ms)
        cancel_astro_assist_keep_awake(keep_awake, "lease expired");
}

static void maybe_rearm_astro_assist_keep_awake(
    struct star_service *service)
{
    struct astro_assist_keep_awake *keep_awake = &service->keep_awake;
    int64_t now;

    if (service->active_conn == STAR_NO_CONNECTION || !keep_awake->active)
        return;
    now = milliseconds_now();
    if (now < 0)
        return;
    if (now >= keep_awake->deadline_ms) {
        cancel_astro_assist_keep_awake(keep_awake, "lease expired");
        return;
    }
    if (keep_awake->in_flight || now < keep_awake->next_rearm_ms ||
        !worker_accepts_keep_awake_command(service))
        return;

    if (send_worker_frame(&service->worker, STAR_WORKER_COMMAND_KEEP_AWAKE,
                          NULL, 0u) != 0) {
        begin_worker_draining(service, STAR_WORKER_FAILURE_NO_MUTATION,
                              "KEEP_AWAKE command write failed", 0);
        return;
    }
    keep_awake->in_flight = 1;
    keep_awake->next_rearm_ms =
        now + (int64_t)ASTRO_ASSIST_GATT_KEEP_AWAKE_REARM_SECONDS *
                  INT64_C(1000);
    printf("ASTRO ASSIST keep-awake worker command submitted\n");
}

static uint8_t select_public_read_value(
    struct star_service *service, uint16_t attr_id, int live_valid,
    const uint8_t *live_value, uint16_t live_len, uint16_t offset,
    const uint8_t **value, uint16_t *value_len)
{
    struct public_read_snapshot *snapshot = &service->read_snapshot;

    if (offset == 0u) {
        snapshot->valid = 0;
        if (!live_valid || live_value == NULL ||
            live_len > sizeof(snapshot->value))
            return STAR_ATT_UNLIKELY_ERROR;
        snapshot->valid = 1;
        snapshot->attr_id = attr_id;
        snapshot->value_len = live_len;
        memcpy(snapshot->value, live_value, live_len);
    } else if (!snapshot->valid || snapshot->attr_id != attr_id) {
        return STAR_ATT_INVALID_OFFSET;
    }
    if (offset > snapshot->value_len)
        return STAR_ATT_INVALID_OFFSET;
    *value = snapshot->value + offset;
    *value_len = (uint16_t)(snapshot->value_len - offset);
    return STAR_ATT_SUCCESS;
}

static int process_read(struct star_service *service,
                        const struct bgp_read_event *read)
{
    static const uint8_t unavailable_catalog[1] = {0u};
    const uint8_t *value = NULL;
    const char *unlikely_reason = NULL;
    uint16_t value_len = 0u;
    uint8_t cccd[2];
    uint8_t att_status = STAR_ATT_SUCCESS;
    int catalog_kind = -1;
    unsigned int kind;

    for (kind = 0u; kind < STAR_GATT_SETTING_COUNT; ++kind) {
        if (read->handle == service->catalog_attr[kind]) {
            catalog_kind = (int)kind;
            break;
        }
    }
    if (read->bsa_status != 0u ||
        service->active_conn == STAR_NO_CONNECTION ||
        read->conn_id != service->active_conn) {
        att_status = STAR_ATT_UNLIKELY_ERROR;
        unlikely_reason = "event or connection mismatch";
    } else if (read->handle == service->state_attr) {
        if (read->offset == 0u) {
            sample_astro_assist_ibis(&service->astro_assist);
            sample_astro_assist_acceleration(&service->acceleration);
            sample_gyro_attitude(&service->gyro);
            rebuild_unified_state(service);
        }
        att_status = select_public_read_value(
            service, service->state_attr, 1,
            service->unified_state.value,
            service->unified_state.value_len, read->offset,
            &value, &value_len);
    } else if (read->handle == service->recipe_attr) {
        att_status = select_public_read_value(
            service, service->recipe_attr, service->recipe.valid,
            service->recipe.value, service->recipe.value_len, read->offset,
            &value, &value_len);
        if (att_status == STAR_ATT_UNLIKELY_ERROR)
            unlikely_reason = "RECIPE snapshot unavailable";
    } else if (read->handle == service->astro_assist_config_attr) {
        att_status = select_public_read_value(
            service, service->astro_assist_config_attr, 1,
            service->detector_config,
            ASTRO_ASSIST_GATT_DETECTOR_CONFIG_SIZE, read->offset,
            &value, &value_len);
    } else if (read->handle == service->astro_assist_ibis_attr) {
        if (read->offset == 0u)
            sample_astro_assist_ibis(&service->astro_assist);
        att_status = select_public_read_value(
            service, service->astro_assist_ibis_attr, 1,
            service->astro_assist.value,
            ASTRO_ASSIST_GATT_IBIS_STATE_SIZE, read->offset,
            &value, &value_len);
    } else if (read->handle == service->astro_assist_acceleration_attr) {
        if (read->offset == 0u)
            sample_astro_assist_acceleration(&service->acceleration);
        att_status = select_public_read_value(
            service, service->astro_assist_acceleration_attr, 1,
            service->acceleration.value,
            ASTRO_ASSIST_GATT_ACCELERATION_STATE_SIZE, read->offset,
            &value, &value_len);
    } else if (catalog_kind >= 0) {
        const struct public_value *catalog =
            &service->catalog.kind[catalog_kind];

        att_status = select_public_read_value(
            service, service->catalog_attr[catalog_kind],
            1,
            service->catalog.valid && catalog->valid
                ? catalog->value
                : unavailable_catalog,
            service->catalog.valid && catalog->valid
                ? catalog->value_len
                : (uint16_t)sizeof(unavailable_catalog),
            read->offset, &value, &value_len);
    } else if (read->handle == service->control_cccd_attr) {
        if (read->offset != 0u) {
            att_status = STAR_ATT_INVALID_OFFSET;
        } else {
            put_le16(cccd, service->control_indicate_enabled ? 2u : 0u);
            value = cccd;
            value_len = (uint16_t)sizeof(cccd);
        }
    } else if (read->handle == service->state_cccd_attr) {
        if (read->offset != 0u) {
            att_status = STAR_ATT_INVALID_OFFSET;
        } else {
            put_le16(cccd, service->state_notify_enabled ? 1u : 0u);
            value = cccd;
            value_len = (uint16_t)sizeof(cccd);
        }
    } else if (read->handle == service->astro_assist_ibis_cccd_attr) {
        if (read->offset != 0u) {
            att_status = STAR_ATT_INVALID_OFFSET;
        } else {
            put_le16(cccd,
                     service->astro_assist.notify_enabled ? 1u : 0u);
            value = cccd;
            value_len = (uint16_t)sizeof(cccd);
        }
    } else if (read->handle ==
               service->astro_assist_acceleration_cccd_attr) {
        if (read->offset != 0u) {
            att_status = STAR_ATT_INVALID_OFFSET;
        } else {
            put_le16(cccd,
                     service->acceleration.notify_enabled ? 1u : 0u);
            value = cccd;
            value_len = (uint16_t)sizeof(cccd);
        }
    } else if (read->handle == service->control_attr ||
               read->handle == service->astro_rate_v2_attr ||
               read->handle == service->astro_preposition_attr ||
               read->handle == service->astro_assist_keep_awake_attr) {
        att_status = STAR_ATT_READ_NOT_PERMITTED;
    } else {
        att_status = STAR_ATT_INVALID_HANDLE;
    }

    if (att_status == STAR_ATT_UNLIKELY_ERROR) {
        ++service->unlikely_read_count;
        fprintf(stderr,
                "STAR ATT 0x0e read=%llu reason=%s handle=%u offset=%u "
                "event_conn=%u active_conn=%u bsa=%u worker=%u op=%u "
                "state=%d catalog=%d recipe=%d recovery=%d/%d\n",
                (unsigned long long)service->unlikely_read_count,
                unlikely_reason != NULL ? unlikely_reason : "unspecified",
                (unsigned int)read->handle, (unsigned int)read->offset,
                (unsigned int)read->conn_id,
                (unsigned int)service->active_conn,
                (unsigned int)read->bsa_status,
                (unsigned int)service->worker.lifecycle,
                (unsigned int)service->operation, service->state.valid,
                service->catalog.valid, service->recipe.valid,
                service->capture_recovery_pending,
                service->capture_recovery_repairing);
    }
    if (att_status != STAR_ATT_SUCCESS)
        return send_att_response(service->client, read->cookie, att_status,
                                 NULL, 0u);
    return send_att_response(service->client, read->cookie, STAR_ATT_SUCCESS,
                             value, value_len);
}

/* MAIN's existing publication flag is only an internal consumption fence.
 * Keep the ATT request pending without blocking RATE renewals, disconnects,
 * or worker supervision while the half-press carrier forwards the command. */
static int finish_astro_preposition_write(struct star_service *service)
{
    struct astro_preposition_request *request = &service->preposition;
    uint32_t cookie;
    uint8_t status = STAR_ATT_SUCCESS;
    int64_t now;

    if (request->pending_cookie == 0u)
        return 0;
    now = milliseconds_now();
    if (request->pending_failed || now < 0 || now >= request->deadline_ms)
        status = STAR_ATT_UNLIKELY_ERROR;
    else if (!request->pending_published ||
             astro_preposition_bytes(service)[ASTRO_PREPOSITION_READY_OFFSET]
                 != ASTRO_PREPOSITION_CONSUMED)
        return 0;
    cookie = request->pending_cookie;
    request->pending_cookie = 0u;
    request->pending_failed = 0;
    request->pending_published = 0;
    if (status != STAR_ATT_SUCCESS)
        cancel_astro_preposition(service, "MAIN consumption failed/timed out");
    return send_att_response(service->client, cookie, status, NULL, 0u);
}

static int process_astro_preposition_write(
    struct star_service *service, const struct bgp_write_event *write)
{
    uint8_t native_value[ASTRO_PREPOSITION_NATIVE_SIZE];
    uint8_t status = STAR_ATT_SUCCESS;
    uint16_t action = 0u;
    int64_t now = 0;

    if (write->offset != 0u)
        status = STAR_ATT_INVALID_OFFSET;
    else if (write->value_len != ASTRO_PREPOSITION_PAYLOAD_SIZE)
        status = STAR_ATT_INVALID_ATTRIBUTE_LENGTH;
    else {
        action = get_le16(write->value + ASTRO_PREPOSITION_ACTION_OFFSET);
        if (action > ASTRO_PREPOSITION_APPLY ||
            (action == ASTRO_PREPOSITION_CANCEL &&
             !bytes_zero(write->value, ASTRO_PREPOSITION_ACTION_OFFSET)) ||
            !convert_astro_preposition(write->value, native_value))
            status = STAR_ATT_VALUE_NOT_ALLOWED;
    }
    if (status == STAR_ATT_SUCCESS && stop_requested)
        status = STAR_ATT_UNLIKELY_ERROR;
    if (status == STAR_ATT_SUCCESS && action != ASTRO_PREPOSITION_CANCEL &&
        !worker_accepts_command(service))
        status = STAR_ATT_BUSY;
    if (status == STAR_ATT_SUCCESS && action != ASTRO_PREPOSITION_CANCEL &&
        (service->preposition.capture_owned ||
         service->preposition.pending_cookie != 0u ||
         *astro_preposition_bytes(service) == ASTRO_PREPOSITION_REQUESTED ||
         *astro_preposition_bytes(service) == ASTRO_PREPOSITION_PUBLISHED ||
         *astro_preposition_bytes(service) == ASTRO_PREPOSITION_CONSUMED))
        status = STAR_ATT_BUSY;
    if (status == STAR_ATT_SUCCESS && action == ASTRO_PREPOSITION_APPLY) {
        now = milliseconds_now();
        if (now < 0)
            status = STAR_ATT_UNLIKELY_ERROR;
    }
    if (status != STAR_ATT_SUCCESS)
        return send_att_response(service->client, write->cookie, status,
                                 NULL, 0u);

    if (action == ASTRO_PREPOSITION_CANCEL) {
        /* Explicit cancellation is published even without a retained lease. */
        service->preposition.active = 1;
        cancel_astro_preposition(service, "peer request");
    } else {
        memcpy(service->preposition.queued_value, native_value, sizeof(native_value));
        service->preposition.queued = 1;
        service->preposition.active = 1;
        service->preposition.staged_intent = action == ASTRO_PREPOSITION_STAGE;
        service->preposition.capture_owned = 0;
        if (action == ASTRO_PREPOSITION_APPLY) {
            service->preposition.pending_cookie = write->cookie;
            service->preposition.pending_failed = 0;
            service->preposition.pending_published = 0;
            service->preposition.deadline_ms =
                now + ASTRO_PREPOSITION_CONSUME_TIMEOUT_MS;
        }
        set_preposition_state(service, action == ASTRO_PREPOSITION_STAGE
                                ? ASTRO_PREPOSITION_STAGED
                                : ASTRO_PREPOSITION_REQUESTED);
        flush_native_commands(service);
        if (action == ASTRO_PREPOSITION_APPLY)
            return 0;
    }
    return send_att_response(service->client, write->cookie, STAR_ATT_SUCCESS,
                             NULL, 0u);
}

static int process_astro_rate_v2_write(struct star_service *service,
                                       const struct bgp_write_event *write)
{
    uint8_t mailbox_value[ASTRO_RATE_MAILBOX_PAYLOAD_SIZE];
    uint8_t att_status;
    uint16_t focal = 0u;
    int capture_update = 0;
    int is_cancel = 0;

    memset(mailbox_value, 0, sizeof(mailbox_value));
    if (write->offset != 0u)
        att_status = STAR_ATT_INVALID_OFFSET;
    else if (stop_requested)
        att_status = STAR_ATT_UNLIKELY_ERROR;
    else
        att_status = validate_astro_rate_v2_value(
            write->value, write->value_len, &is_cancel);
    if (att_status == STAR_ATT_SUCCESS && !is_cancel &&
        (service->operation == STAR_OPERATION_CAPTURE_PENDING ||
         service->operation == STAR_OPERATION_CAPTURE_STREAM)) {
        enum live_focal_status focal_status =
            sample_cached_focal_uq11_5(service, &focal);

        capture_update = 1;
        if (focal_status == LIVE_FOCAL_OK) {
            service->astro_rate.last_valid_focal_uq11_5 = focal;
            service->astro_rate.last_valid_focal_available = 1;
        } else if (focal_status == LIVE_FOCAL_UNAVAILABLE &&
                   service->astro_rate.last_valid_focal_available) {
            focal = service->astro_rate.last_valid_focal_uq11_5;
        } else if (focal_status == LIVE_FOCAL_UNAVAILABLE) {
            att_status = STAR_ATT_UNLIKELY_ERROR;
        } else {
            att_status = STAR_ATT_VALUE_NOT_ALLOWED;
        }
    } else if (att_status == STAR_ATT_SUCCESS && !is_cancel) {
        enum live_focal_status focal_status =
            sample_live_focal_uq11_5(service, &focal);

        if (focal_status == LIVE_FOCAL_OK) {
            service->astro_rate.last_valid_focal_uq11_5 = focal;
            service->astro_rate.last_valid_focal_available = 1;
        } else if (focal_status == LIVE_FOCAL_UNAVAILABLE) {
            att_status = STAR_ATT_UNLIKELY_ERROR;
        } else if (focal_status == LIVE_FOCAL_OUT_OF_RANGE) {
            att_status = STAR_ATT_VALUE_NOT_ALLOWED;
        }
    }
    if (att_status == STAR_ATT_SUCCESS) {
        if (!is_cancel)
            convert_astro_rate(write->value, focal, mailbox_value);
        queue_astro_rate_mailbox(&service->astro_rate, mailbox_value, is_cancel);
        flush_native_commands(service);
        if (is_cancel)
            clear_astro_rate_active_value(&service->astro_rate);
        else
            service->astro_rate.active = 1;
        printf("ASTRO RATE v2 %s yaw_q16=%ld pitch_q16=%ld "
               "roll_q16=%ld focal=%u ttl=%u\n",
               is_cancel ? "cancel" :
                   (capture_update ? "capture-updated" : "accepted"),
               (long)(int32_t)get_le32(write->value),
               (long)(int32_t)get_le32(write->value + 4u),
               (long)(int32_t)get_le32(write->value + 8u),
               (unsigned int)focal,
               (unsigned int)get_le16(
                   write->value + ASTRO_RATE_GATT_V2_TTL_OFFSET));
    }
    return send_att_response(service->client, write->cookie, att_status,
                             NULL, 0u);
}

static int process_astro_assist_config_write(
    struct star_service *service, const struct bgp_write_event *write)
{
    struct star_worker_detector_options options;
    uint8_t att_status = STAR_ATT_SUCCESS;

    memset(&options, 0, sizeof(options));
    if (write->offset != 0u)
        att_status = STAR_ATT_INVALID_OFFSET;
    else if (write->value_len !=
             ASTRO_ASSIST_GATT_DETECTOR_CONFIG_SIZE)
        att_status = STAR_ATT_INVALID_ATTRIBUTE_LENGTH;
    else if (stop_requested)
        att_status = STAR_ATT_UNLIKELY_ERROR;
    else if (service->operation != STAR_OPERATION_IDLE)
        att_status = STAR_ATT_BUSY;
    else if (!star_worker_decode_detector_options(
                 write->value, write->value_len, &options))
        att_status = STAR_ATT_VALUE_NOT_ALLOWED;

    if (att_status != STAR_ATT_SUCCESS)
        return send_att_response(service->client, write->cookie, att_status,
                                 NULL, 0u);

    /* The ATT success is the commit acknowledgement: install the complete
     * validated value before making that acknowledgement observable. */
    memcpy(service->detector_config, write->value,
           ASTRO_ASSIST_GATT_DETECTOR_CONFIG_SIZE);
    printf("ASTRO ASSIST detector config committed "
           "sigma=%u area=%u..%u elongation=%u sharpness=%u border=%u\n",
           (unsigned int)options.sigma_q8,
           (unsigned int)options.min_area,
           (unsigned int)options.max_area,
           (unsigned int)options.max_elongation_q8,
           (unsigned int)options.max_sharpness_q8,
           (unsigned int)options.border_margin);
    return send_att_response(service->client, write->cookie, STAR_ATT_SUCCESS,
                             NULL, 0u);
}

static int process_astro_assist_keep_awake_write(
    struct star_service *service, const struct bgp_write_event *write)
{
    struct astro_assist_keep_awake *keep_awake = &service->keep_awake;
    uint8_t att_status = STAR_ATT_SUCCESS;
    uint16_t ttl_seconds = 0u;
    int64_t now = -1;
    int was_active = 0;

    if (write->offset != 0u) {
        att_status = STAR_ATT_INVALID_OFFSET;
    } else if (write->value_len !=
               ASTRO_ASSIST_GATT_KEEP_AWAKE_LEASE_SIZE) {
        att_status = STAR_ATT_INVALID_ATTRIBUTE_LENGTH;
    } else {
        ttl_seconds = get_le16(
            write->value + ASTRO_ASSIST_GATT_KEEP_AWAKE_TTL_SECONDS_OFFSET);
        if (ttl_seconds != ASTRO_ASSIST_GATT_KEEP_AWAKE_CANCEL_SECONDS &&
            (ttl_seconds < ASTRO_ASSIST_GATT_KEEP_AWAKE_TTL_MIN_SECONDS ||
             ttl_seconds > ASTRO_ASSIST_GATT_KEEP_AWAKE_TTL_MAX_SECONDS))
            att_status = STAR_ATT_VALUE_NOT_ALLOWED;
    }
    if (att_status == STAR_ATT_SUCCESS && stop_requested)
        att_status = STAR_ATT_UNLIKELY_ERROR;
    if (att_status == STAR_ATT_SUCCESS &&
        ttl_seconds != ASTRO_ASSIST_GATT_KEEP_AWAKE_CANCEL_SECONDS &&
        service->capture_recovery_repairing)
        att_status = STAR_ATT_BUSY;
    if (att_status == STAR_ATT_SUCCESS) {
        now = milliseconds_now();
        if (now < 0)
            att_status = STAR_ATT_UNLIKELY_ERROR;
    }

    if (att_status == STAR_ATT_SUCCESS) {
        was_active = keep_awake->active && now < keep_awake->deadline_ms;
        if (ttl_seconds == ASTRO_ASSIST_GATT_KEEP_AWAKE_CANCEL_SECONDS) {
            cancel_astro_assist_keep_awake(keep_awake, "peer cancellation");
        } else {
            keep_awake->active = 1;
            keep_awake->deadline_ms =
                now + (int64_t)ttl_seconds * INT64_C(1000);
            if (!was_active)
                keep_awake->next_rearm_ms = now;
            /* ATT success acknowledges only this monotonic lease update.
             * The worker command and its result remain asynchronous. */
            printf("ASTRO ASSIST keep-awake lease %s ttl=%u\n",
                   was_active ? "renewed" : "accepted",
                   (unsigned int)ttl_seconds);
        }
    }
    update_gyro_attitude_lease(service);
    return send_att_response(service->client, write->cookie, att_status,
                             NULL, 0u);
}

static int process_astro_assist_cccd_write(
    struct star_service *service, const struct bgp_write_event *write)
{
    uint8_t status = STAR_ATT_SUCCESS;
    uint16_t cccd = 0u;

    if (write->offset != 0u)
        status = STAR_ATT_INVALID_OFFSET;
    else if (write->value_len != 2u)
        status = STAR_ATT_INVALID_ATTRIBUTE_LENGTH;
    else {
        cccd = get_le16(write->value);
        if (cccd != 0u && cccd != 1u)
            status = STAR_ATT_VALUE_NOT_ALLOWED;
    }
    if (send_att_response(service->client, write->cookie, status, NULL, 0u) !=
        0)
        return -1;
    if (status != STAR_ATT_SUCCESS)
        return 0;
    service->astro_assist.notify_enabled = cccd == 1u;
    service->astro_assist.next_poll_ms = 0;
    if (service->astro_assist.notify_enabled) {
        sample_astro_assist_ibis(&service->astro_assist);
        service->astro_assist.dirty = 1;
    } else {
        service->astro_assist.dirty = 0;
    }
    return 0;
}

static int process_astro_assist_acceleration_cccd_write(
    struct star_service *service, const struct bgp_write_event *write)
{
    uint8_t status = STAR_ATT_SUCCESS;
    uint16_t cccd = 0u;

    if (write->offset != 0u)
        status = STAR_ATT_INVALID_OFFSET;
    else if (write->value_len != 2u)
        status = STAR_ATT_INVALID_ATTRIBUTE_LENGTH;
    else {
        cccd = get_le16(write->value);
        if (cccd != 0u && cccd != 1u)
            status = STAR_ATT_VALUE_NOT_ALLOWED;
    }
    if (send_att_response(service->client, write->cookie, status, NULL, 0u) !=
        0)
        return -1;
    if (status != STAR_ATT_SUCCESS)
        return 0;
    service->acceleration.notify_enabled = cccd == 1u;
    service->acceleration.next_poll_ms = 0;
    if (service->acceleration.notify_enabled) {
        sample_astro_assist_acceleration(&service->acceleration);
        service->acceleration.dirty = 1;
    } else {
        service->acceleration.dirty = 0;
    }
    return 0;
}

static int process_cccd_write(struct star_service *service,
                              const struct bgp_write_event *write,
                              uint16_t enabled_value, int *enabled,
                              int is_control)
{
    uint8_t status = STAR_ATT_SUCCESS;
    uint16_t cccd = 0u;

    if (write->offset != 0u)
        status = STAR_ATT_INVALID_OFFSET;
    else if (write->value_len != 2u)
        status = STAR_ATT_INVALID_ATTRIBUTE_LENGTH;
    else {
        cccd = get_le16(write->value);
        if (cccd != 0u && cccd != enabled_value)
            status = STAR_ATT_VALUE_NOT_ALLOWED;
        else if (!is_control && cccd == enabled_value &&
                 service->active_mtu < STAR_MIN_ATT_MTU)
            status = STAR_ATT_VALUE_NOT_ALLOWED;
        else if (is_control &&
                 (service->operation == STAR_OPERATION_CAPTURE_PENDING ||
                  service->operation == STAR_OPERATION_CAPTURE_STREAM) &&
                 cccd == 0u)
            status = STAR_ATT_BUSY;
    }
    if (send_att_response(service->client, write->cookie, status, NULL, 0u) !=
        0)
        return -1;
    if (status != STAR_ATT_SUCCESS)
        return 0;
    *enabled = cccd == enabled_value;
    update_gyro_attitude_lease(service);
    if (!is_control && *enabled) {
        sample_gyro_attitude(&service->gyro);
        rebuild_unified_state(service);
        service->state_dirty = 1;
        service->last_state_notify_ms = 0;
    }
    return 0;
}

static int process_control_write(struct star_service *service,
                                 const struct bgp_write_event *write)
{
    uint8_t capture_payload[STAR_WORKER_CAPTURE_PAYLOAD_MAX];
    uint16_t capture_payload_size;
    uint8_t command = STAR_WORKER_COMMAND_CAPTURE;
    uint8_t att_status = STAR_ATT_SUCCESS;

    if (write->offset != 0u)
        att_status = STAR_ATT_INVALID_OFFSET;
    else if (!service->control_indicate_enabled)
        att_status = STAR_ATT_CCCD_IMPROPERLY_CONFIGURED;
    else if (write->value_len != STAR_GATT_CAPTURE_REQUEST_SIZE)
        att_status = STAR_ATT_INVALID_ATTRIBUTE_LENGTH;
    else if (write->value[0] != STAR_GATT_COMMAND_CAPTURE_RECIPE)
        att_status = STAR_ATT_VALUE_NOT_ALLOWED;
    else if (stop_requested)
        att_status = STAR_ATT_UNLIKELY_ERROR;
    else if (!worker_accepts_command(service))
        att_status = STAR_ATT_BUSY;
    else if (!service->recipe.valid || !service->state.valid ||
             !service->catalog.valid)
        att_status = STAR_ATT_UNLIKELY_ERROR;
    else if (!tuple_legal_for_snapshot(
                 service->recipe.value, service->recipe.value_len,
                 &service->catalog, &service->state))
        att_status = STAR_ATT_VALUE_NOT_ALLOWED;
    else if (service->active_mtu < STAR_GATT_CAPTURE_MIN_ATT_MTU)
        att_status = STAR_ATT_VALUE_NOT_ALLOWED;

    if (send_att_response(service->client, write->cookie, att_status, NULL,
                          0u) != 0)
        return -1;
    if (att_status != STAR_ATT_SUCCESS) {
        if (service->preposition.staged_intent &&
            service->operation == STAR_OPERATION_IDLE)
            cancel_astro_preposition(service, "STAR capture admission rejected");
        return 0;
    }
    memcpy(capture_payload, service->detector_config,
           ASTRO_ASSIST_GATT_DETECTOR_CONFIG_SIZE);
    memcpy(capture_payload + ASTRO_ASSIST_GATT_DETECTOR_CONFIG_SIZE,
           service->recipe.value, service->recipe.value_len);
    capture_payload_size =
        (uint16_t)(ASTRO_ASSIST_GATT_DETECTOR_CONFIG_SIZE +
                   service->recipe.value_len);
    clear_capture_stream(service);
    if (service->preposition.active && service->preposition.staged_intent) {
        command = STAR_WORKER_COMMAND_CAPTURE_PREPOSITION;
        service->preposition.capture_owned = 1;
        service->preposition.staged_intent = 0;
    }
    service->operation = STAR_OPERATION_CAPTURE_PENDING;
    arm_capture_recovery(service);
    ++service->capture_sequence;
    printf("STAR capture=%llu admitted worker_generation=%llu\n",
           (unsigned long long)service->capture_sequence,
           (unsigned long long)service->worker_generation);
    if (send_worker_frame(&service->worker, command,
                          capture_payload, capture_payload_size) != 0) {
        begin_worker_draining(service, STAR_WORKER_FAILURE_UNCERTAIN,
                              "CAPTURE command write failed", 0);
    }
    return 0;
}

static int process_state_write(struct star_service *service,
                               const struct bgp_write_event *write)
{
    struct pending_state_write pending;
    struct star_worker_detector_options detector;
    const uint8_t *live_indices =
        write->value + STAR_WRITE_LIVE_INDICES_OFFSET;
    const uint8_t *recipe_indices =
        write->value + STAR_WRITE_RECIPE_INDICES_OFFSET;
    uint8_t att_status = STAR_ATT_SUCCESS;
    uint32_t expected_config = 0u;
    uint32_t expected_catalog = 0u;
    unsigned int kind;

    memset(&pending, 0, sizeof(pending));
    memset(&detector, 0, sizeof(detector));
    rebuild_unified_state(service);
    if (write->offset != 0u)
        att_status = STAR_ATT_INVALID_OFFSET;
    else if (write->value_len != STAR_STATE_WRITE_SIZE)
        att_status = STAR_ATT_INVALID_ATTRIBUTE_LENGTH;
    else if (write->value[STAR_WRITE_VERSION_OFFSET] !=
             STAR_CONFIG_VERSION)
        att_status = STAR_ATT_VALUE_NOT_ALLOWED;
    else {
        expected_config = get_le32(
            write->value +
            STAR_WRITE_EXPECTED_CONFIG_GENERATION_OFFSET);
        expected_catalog = get_le32(
            write->value +
            STAR_WRITE_EXPECTED_CATALOG_GENERATION_OFFSET);
        if (expected_config == 0u || expected_catalog == 0u)
            att_status = STAR_ATT_VALUE_NOT_ALLOWED;
    }
    if (att_status == STAR_ATT_SUCCESS &&
        (stop_requested || !worker_accepts_command(service) ||
         service->capture_recovery_pending ||
         service->pending_state_write.active))
        att_status = STAR_ATT_BUSY;
    if (att_status == STAR_ATT_SUCCESS &&
        (!service->catalog.valid || !service->state.valid ||
         !service->recipe.valid || !service->detector_config_valid))
        att_status = STAR_ATT_BUSY;
    if (att_status == STAR_ATT_SUCCESS &&
        (expected_config != service->config_generation ||
         expected_catalog != service->catalog_generation))
        att_status = STAR_ATT_BUSY;
    if (att_status == STAR_ATT_SUCCESS) {
        uint8_t aperture_min =
            service->unified_state
                .value[STAR_STATE_APERTURE_BOUNDS_OFFSET];
        uint8_t aperture_max =
            service->unified_state
                .value[STAR_STATE_APERTURE_BOUNDS_OFFSET + 1u];

        for (kind = 0u; kind < STAR_GATT_SETTING_COUNT; ++kind) {
            unsigned int count = service->catalog.kind[kind].value[0];

            if (live_indices[kind] >= count ||
                recipe_indices[kind] >= count) {
                att_status = STAR_ATT_VALUE_NOT_ALLOWED;
                break;
            }
        }
        if (att_status == STAR_ATT_SUCCESS &&
            (aperture_min == STAR_UNAVAILABLE_INDEX ||
             live_indices[1] < aperture_min ||
             live_indices[1] > aperture_max ||
             recipe_indices[1] < aperture_min ||
             recipe_indices[1] > aperture_max))
            att_status = STAR_ATT_VALUE_NOT_ALLOWED;
    }
    if (att_status == STAR_ATT_SUCCESS &&
        (tuple_from_indices(&service->catalog, live_indices,
                            &pending.live_target) != 0 ||
         tuple_from_indices(&service->catalog, recipe_indices,
                            &pending.recipe_target) != 0 ||
         !tuple_legal_for_snapshot(pending.live_target.value,
                                   pending.live_target.value_len,
                                   &service->catalog, &service->state) ||
         !tuple_legal_for_snapshot(pending.recipe_target.value,
                                   pending.recipe_target.value_len,
                                   &service->catalog, &service->state)))
        att_status = STAR_ATT_VALUE_NOT_ALLOWED;
    if (att_status == STAR_ATT_SUCCESS &&
        !star_worker_decode_detector_options(
            write->value + STAR_WRITE_DETECTOR_OFFSET,
            ASTRO_ASSIST_GATT_DETECTOR_CONFIG_SIZE, &detector))
        att_status = STAR_ATT_VALUE_NOT_ALLOWED;
    if (att_status == STAR_ATT_SUCCESS) {
        pending.active = 1;
        memcpy(pending.detector,
               write->value + STAR_WRITE_DETECTOR_OFFSET,
               sizeof(pending.detector));
        service->pending_state_write = pending;
        service->operation = STAR_OPERATION_SET_PENDING;
    }

    if (send_att_response(service->client, write->cookie, att_status, NULL,
                          0u) != 0) {
        if (att_status == STAR_ATT_SUCCESS) {
            memset(&service->pending_state_write, 0,
                   sizeof(service->pending_state_write));
            service->operation = STAR_OPERATION_IDLE;
        }
        return -1;
    }
    if (att_status != STAR_ATT_SUCCESS)
        return 0;
    if (send_worker_frame(&service->worker, STAR_WORKER_COMMAND_SET,
                          service->pending_state_write.live_target.value,
                          service->pending_state_write.live_target.value_len) !=
        0) {
        begin_worker_draining(service, STAR_WORKER_FAILURE_UNCERTAIN,
                              "SET command write failed", 0);
    }
    return 0;
}

static int process_recipe_write(struct star_service *service,
                                const struct bgp_write_event *write)
{
    struct label_tuple_view tuple;
    uint8_t att_status;

    memset(&tuple, 0, sizeof(tuple));
    if (write->offset != 0u)
        att_status = STAR_ATT_INVALID_OFFSET;
    else if (write->value_len + STAR_GATT_ATT_VALUE_OVERHEAD >
             service->active_mtu)
        att_status = STAR_ATT_INVALID_ATTRIBUTE_LENGTH;
    else
        att_status = parse_label_tuple(write->value, write->value_len, &tuple);
    if (att_status == STAR_ATT_SUCCESS && stop_requested)
        att_status = STAR_ATT_UNLIKELY_ERROR;
    if (att_status == STAR_ATT_SUCCESS && !worker_accepts_command(service))
        att_status = STAR_ATT_BUSY;
    if (att_status == STAR_ATT_SUCCESS &&
        (!service->state.valid || !service->catalog.valid))
        att_status = STAR_ATT_UNLIKELY_ERROR;
    if (att_status == STAR_ATT_SUCCESS &&
        !tuple_legal_for_snapshot(write->value, write->value_len,
                                  &service->catalog, &service->state))
        att_status = STAR_ATT_VALUE_NOT_ALLOWED;
    if (send_att_response(service->client, write->cookie, att_status, NULL,
                          0u) != 0)
        return -1;
    if (att_status == STAR_ATT_SUCCESS) {
        memset(&service->recipe, 0, sizeof(service->recipe));
        service->recipe.valid = 1;
        service->recipe.value_len = write->value_len;
        memcpy(service->recipe.value, write->value, write->value_len);
        printf("STAR RECIPE write committed\n");
    }
    return 0;
}

static int process_write(struct star_service *service,
                         const struct bgp_write_event *write)
{
    uint8_t early_status = STAR_ATT_SUCCESS;

    /* Every writable STAR attribute requires an ATT write response. */
    if (!write->need_response || write->cookie == 0u)
        return 0;
    if (write->bsa_status != 0u ||
        service->active_conn == STAR_NO_CONNECTION ||
        write->conn_id != service->active_conn)
        early_status = STAR_ATT_UNLIKELY_ERROR;
    else if (write->is_prepare)
        early_status = STAR_ATT_REQUEST_NOT_SUPPORTED;
    else if (write->handle == service->astro_assist_ibis_attr ||
             write->handle == service->astro_assist_acceleration_attr)
        early_status = STAR_ATT_WRITE_NOT_PERMITTED;
    else if (write->handle != service->control_attr &&
             write->handle != service->state_attr &&
             write->handle != service->recipe_attr &&
             write->handle != service->control_cccd_attr &&
             write->handle != service->state_cccd_attr &&
             write->handle != service->astro_rate_v2_attr &&
             write->handle != service->astro_preposition_attr &&
             write->handle != service->astro_assist_config_attr &&
             write->handle != service->astro_assist_keep_awake_attr &&
             write->handle != service->astro_assist_ibis_cccd_attr &&
             write->handle !=
                 service->astro_assist_acceleration_cccd_attr)
        early_status = STAR_ATT_INVALID_HANDLE;
    if (early_status != STAR_ATT_SUCCESS)
        return send_att_response(service->client, write->cookie, early_status,
                                 NULL, 0u);

    if (write->handle == service->control_cccd_attr)
        return process_cccd_write(service, write, 2u,
                                  &service->control_indicate_enabled, 1);
    if (write->handle == service->state_cccd_attr)
        return process_cccd_write(service, write, 1u,
                                  &service->state_notify_enabled, 0);
    if (write->handle == service->astro_assist_ibis_cccd_attr)
        return process_astro_assist_cccd_write(service, write);
    if (write->handle == service->astro_assist_acceleration_cccd_attr)
        return process_astro_assist_acceleration_cccd_write(service, write);
    if (write->handle == service->state_attr)
        return process_state_write(service, write);
    if (write->handle == service->recipe_attr)
        return process_recipe_write(service, write);
    if (write->handle == service->astro_rate_v2_attr)
        return process_astro_rate_v2_write(service, write);
    if (write->handle == service->astro_preposition_attr)
        return process_astro_preposition_write(service, write);
    if (write->handle == service->astro_assist_config_attr)
        return process_astro_assist_config_write(service, write);
    if (write->handle == service->astro_assist_keep_awake_attr)
        return process_astro_assist_keep_awake_write(service, write);
    return process_control_write(service, write);
}

static void detach_peer(struct star_service *service)
{
    cancel_astro_rate(service, "BLE peer detached");
    cancel_astro_preposition(service, "BLE peer detached");
    service->preposition.pending_cookie = 0u;
    clear_astro_rate_connection_focal(&service->astro_rate);
    cancel_astro_assist_keep_awake(&service->keep_awake,
                                   "BLE peer detached");
    service->active_conn = STAR_NO_CONNECTION;
    service->active_mtu = STAR_GATT_DEFAULT_ATT_MTU;
    service->control_indicate_enabled = 0;
    service->state_notify_enabled = 0;
    service->astro_assist.notify_enabled = 0;
    service->astro_assist.dirty = 0;
    reset_astro_assist_ibis_session(&service->astro_assist);
    service->acceleration.notify_enabled = 0;
    reset_astro_assist_acceleration_session(&service->acceleration);
    service->acceleration.dirty = 0;
    (void)write_gyro_attitude_lease(&service->gyro, 0);
    reset_gyro_attitude_session(&service->gyro);
    service->congested = 0;
    service->operation = STAR_OPERATION_IDLE;
    memset(&service->pending_state_write, 0,
           sizeof(service->pending_state_write));
    clear_capture_stream(service);
    if (service->capture_recovery_pending) {
        begin_capture_recovery_repair(service);
        retain_capture_recovery_target(service);
    } else {
        invalidate_public_snapshot(service);
    }
    service->read_snapshot.valid = 0;
    if (!service->capture_recovery_pending)
        service->next_worker_attempt_ms = 0;
    service->ptp_listener_wait_reported = 0;
    if (service->worker.pid >= 0) {
        service->worker.restart_suppressed = 1;
        if (service->worker.lifecycle != STAR_WORKER_DRAINING) {
            reset_astro_assist_keep_awake_worker_attempt(
                &service->keep_awake);
            service->worker.lifecycle = STAR_WORKER_DRAINING;
            close_fd(&service->worker.command_fd);
            printf("STAR worker command EOF requested\n");
        }
    }
}

static int handle_service_event(struct star_service *service,
                                const struct bsa_gatt_proxy_event *event)
{
    switch (event->type) {
    case BGP_EVT_STATE:
        if (!state_is_usable(&event->payload.state,
                             service->expected_epoch))
            return -1;
        break;
    case BGP_EVT_OPEN:
        if (service->active_conn == STAR_NO_CONNECTION) {
            clear_astro_rate_connection_focal(&service->astro_rate);
            service->active_conn = event->payload.connection.conn_id;
            service->active_mtu = STAR_GATT_DEFAULT_ATT_MTU;
            service->control_indicate_enabled = 0;
            service->state_notify_enabled = 0;
            service->astro_assist.notify_enabled = 0;
            service->astro_assist.dirty = 0;
            reset_astro_assist_ibis_session(&service->astro_assist);
            service->acceleration.notify_enabled = 0;
            reset_astro_assist_acceleration_session(
                &service->acceleration);
            service->acceleration.dirty = 0;
            (void)write_gyro_attitude_lease(&service->gyro, 0);
            reset_gyro_attitude_session(&service->gyro);
            cancel_astro_assist_keep_awake(&service->keep_awake, NULL);
            service->congested = 0;
            service->operation = STAR_OPERATION_IDLE;
            memset(&service->pending_state_write, 0,
                   sizeof(service->pending_state_write));
            clear_capture_stream(service);
            if (service->capture_recovery_pending) {
                begin_capture_recovery_repair(service);
                retain_capture_recovery_target(service);
            } else {
                invalidate_public_snapshot(service);
            }
            service->read_snapshot.valid = 0;
            if (!service->capture_recovery_pending)
                service->next_worker_attempt_ms = 0;
            service->ptp_listener_wait_reported = 0;
            printf("STAR BLE peer opened conn=%u\n",
                   (unsigned int)service->active_conn);
            ensure_worker_for_peer(service);
        } else if (service->active_conn !=
                   event->payload.connection.conn_id) {
            fprintf(stderr, "ignoring additional STAR conn=%u while conn=%u "
                            "is active\n",
                    (unsigned int)event->payload.connection.conn_id,
                    (unsigned int)service->active_conn);
        }
        break;
    case BGP_EVT_CLOSE:
        if (service->active_conn == event->payload.connection.conn_id) {
            printf("STAR BLE peer closed conn=%u reason=%u\n",
                   (unsigned int)service->active_conn,
                   (unsigned int)event->payload.connection.reason);
            detach_peer(service);
        }
        break;
    case BGP_EVT_READ:
        return process_read(service, &event->payload.read);
    case BGP_EVT_WRITE:
        return process_write(service, &event->payload.write);
    case BGP_EVT_EXEC_WRITE:
        if (event->payload.exec_write.cookie != 0u)
            return send_att_response(service->client,
                                     event->payload.exec_write.cookie,
                                     STAR_ATT_REQUEST_NOT_SUPPORTED,
                                     NULL, 0u);
        break;
    case BGP_EVT_MTU:
        if (event->payload.mtu.conn_id == service->active_conn &&
            event->payload.mtu.mtu >= STAR_GATT_DEFAULT_ATT_MTU)
            service->active_mtu = event->payload.mtu.mtu;
        printf("STAR MTU conn=%u mtu=%u\n",
               (unsigned int)event->payload.mtu.conn_id,
               (unsigned int)event->payload.mtu.mtu);
        break;
    case BGP_EVT_CONGESTION:
        if (event->payload.congestion.conn_id == service->active_conn)
            service->congested =
                event->payload.congestion.congested != 0u;
        printf("STAR congestion conn=%u state=%u\n",
               (unsigned int)event->payload.congestion.conn_id,
               (unsigned int)event->payload.congestion.congested);
        break;
    case BGP_EVT_CONFIRM:
        complete_control_confirmation(service, &event->payload.confirm);
        break;
    default:
        break;
    }
    return 0;
}

static int serve(struct star_service *service)
{
    int proxy_ok = 1;
    int result = 0;
    int shutdown_started = 0;

    while ((proxy_ok && !stop_requested) || service->worker.pid >= 0) {
        struct pollfd descriptors[2];
        nfds_t descriptor_count = 0u;
        int proxy_index = -1;
        int worker_index = -1;
        int poll_status;

        update_worker(service);
        flush_native_commands(service);
        if (proxy_ok && !stop_requested &&
            finish_astro_preposition_write(service) != 0) {
            proxy_ok = 0;
            result = -1;
            detach_peer(service);
        }
        rebuild_unified_state(service);
        expire_astro_assist_keep_awake(service);
        update_gyro_attitude_lease(service);
        if (stop_requested && !shutdown_started) {
            shutdown_started = 1;
            detach_peer(service);
        }
        if ((!proxy_ok || stop_requested) && service->worker.pid < 0)
            break;

        /* A queued primary write or lease cancellation always wins over a
         * due auxiliary state-query/rearm attempt. Process one proxy event
         * and restart the loop before considering KEEP_AWAKE. */
        if (proxy_ok && !stop_requested) {
            struct bsa_gatt_proxy_event event;
            int status = next_event(service->client, &event, 0);

            if (status < 0) {
                proxy_ok = 0;
                result = -1;
                detach_peer(service);
            } else if (status > 0 &&
                       handle_service_event(service, &event) != 0) {
                proxy_ok = 0;
                result = -1;
                detach_peer(service);
            }
            if (status != 0)
                continue;
        }

        pump_control_stream(service);
        maybe_start_capture_recovery(service);
        maybe_sample_astro_assist(service);
        maybe_sample_astro_assist_acceleration(service);
        maybe_sample_gyro_attitude(service);
        maybe_publish_state(service);
        maybe_publish_astro_assist(service);
        maybe_publish_astro_assist_acceleration(service);
        if (proxy_ok && !stop_requested)
            ensure_worker_for_peer(service);
        /* The nonblocking proxy read above proved its immediate queue empty.
         * Only now may the low-priority auxiliary command enter the worker
         * pipe. */
        maybe_rearm_astro_assist_keep_awake(service);

        if (proxy_ok) {
            proxy_index = (int)descriptor_count;
            descriptors[descriptor_count].fd =
                bsa_gatt_proxy_fd(service->client);
            descriptors[descriptor_count].events = POLLIN | POLLHUP;
            descriptors[descriptor_count].revents = 0;
            ++descriptor_count;
        }
        if (service->worker.event_fd >= 0) {
            worker_index = (int)descriptor_count;
            descriptors[descriptor_count].fd = service->worker.event_fd;
            descriptors[descriptor_count].events = POLLIN | POLLHUP;
            descriptors[descriptor_count].revents = 0;
            ++descriptor_count;
        }
        do {
            int timeout_ms = service->worker.pid >= 0 ? 100 : 1000;

            if (service->active_conn != STAR_NO_CONNECTION &&
                astro_assist_source_mapped(&service->astro_assist))
                timeout_ms = (int)ASTRO_ASSIST_IBIS_POLL_MS;
            if (service->active_conn != STAR_NO_CONNECTION &&
                astro_assist_acceleration_source_mapped(
                    &service->acceleration) &&
                ASTRO_ASSIST_ACCEL_POLL_MS < timeout_ms)
                timeout_ms = (int)ASTRO_ASSIST_ACCEL_POLL_MS;
            timeout_ms =
                worker_attempt_poll_timeout_ms(service, timeout_ms);
            if (service->active_conn != STAR_NO_CONNECTION &&
                GYRO_ATTITUDE_POLL_MS < timeout_ms)
                timeout_ms = (int)GYRO_ATTITUDE_POLL_MS;
            if ((service->preposition.pending_cookie != 0u ||
                 service->preposition.queued ||
                 service->preposition.cancel_pending ||
                 service->astro_rate.queued ||
                 service->astro_rate.cancel_pending ||
                 service->astro_rate.inflight) && timeout_ms > 10)
                timeout_ms = 10;
            poll_status = poll(descriptors, descriptor_count, timeout_ms);
        } while (poll_status < 0 && errno == EINTR && !stop_requested);
        if (poll_status < 0) {
            if (errno == EINTR)
                continue;
            perror("poll STAR service/worker");
            result = -1;
            proxy_ok = 0;
            detach_peer(service);
            continue;
        }
        if (worker_index >= 0 &&
            (descriptors[worker_index].revents & POLLNVAL) != 0) {
            begin_worker_draining(service, STAR_WORKER_FAILURE_UNCERTAIN,
                                  "invalid worker event fd", 1);
        }
        if (proxy_index >= 0 &&
            (descriptors[proxy_index].revents & POLLNVAL) != 0) {
            proxy_ok = 0;
            result = -1;
            detach_peer(service);
        }
    }
    return result == 0 ? 0 : -1;
}

static int add_attribute(struct bsa_gatt_proxy_client *client,
                         uint8_t server_if, uint16_t service_id,
                         const struct bgp_uuid *uuid, int is_descriptor,
                         uint8_t properties, uint16_t permissions,
                         uint32_t expected_epoch, const char *label,
                         uint16_t *attr_id)
{
    struct bgp_result response;
    struct bsa_gatt_proxy_event event;
    int status = bsa_gatt_proxy_add_attribute(
        client, service_id, uuid, is_descriptor, properties, permissions,
        &response);

    if (status != BSA_GATT_PROXY_CLIENT_OK)
        return report_client_error(client, status, label);
    if (!result_ok(&response, label) ||
        wait_for_event(client, BGP_EVT_ADD_ATTRIBUTE, expected_epoch,
                       &event) != 0)
        return -1;
    if (event.payload.add_attribute.bsa_status != 0u ||
        event.payload.add_attribute.server_if != server_if ||
        event.payload.add_attribute.service_id != service_id ||
        event.payload.add_attribute.is_descriptor != (is_descriptor != 0)) {
        fprintf(stderr, "%s completion mismatch/status=%u\n", label,
                (unsigned int)event.payload.add_attribute.bsa_status);
        return -1;
    }
    *attr_id = event.payload.add_attribute.attr_id;
    printf("added %s attr_id=%u\n", label, (unsigned int)*attr_id);
    return 0;
}

static void usage(const char *program)
{
    fprintf(stderr, "usage: %s [BGP_SOCKET]\n", program);
}

int main(int argc, char **argv)
{
    struct bsa_gatt_proxy_client *client = NULL;
    struct bgp_hello_response hello;
    struct bgp_register_response registration;
    struct bgp_result response;
    struct bsa_gatt_proxy_event event;
    struct bgp_uuid application_uuid;
    struct bgp_uuid service_uuid;
    struct bgp_uuid control_uuid;
    struct bgp_uuid state_uuid;
    struct bgp_uuid catalog_uuid[STAR_GATT_SETTING_COUNT];
    struct bgp_uuid lease_uuid;
    struct bgp_uuid astro_rate_v2_service_uuid;
    struct bgp_uuid astro_rate_v2_value_uuid;
    struct bgp_uuid astro_preposition_uuid;
    struct bgp_uuid cccd_uuid;
    struct star_service service;
    struct sigaction child_disposition;
    const char *socket_path = NULL;
    uint8_t server_if = 0u;
    uint16_t star_service_id = 0u;
    uint16_t astro_rate_v2_service_id = 0u;
    int registered = 0;
    int star_started = 0;
    int astro_rate_v2_started = 0;
    int exit_status = EXIT_FAILURE;
    int status;
    unsigned int kind;

    memset(&service, 0, sizeof(service));
    service.active_conn = STAR_NO_CONNECTION;
    service.active_mtu = STAR_GATT_DEFAULT_ATT_MTU;
    initialize_worker(&service.worker);
    initialize_astro_rate_mailbox(&service.astro_rate);
    initialize_astro_assist_ibis(&service.astro_assist);
    initialize_astro_assist_acceleration(&service.acceleration);
    initialize_astro_assist_keep_awake(&service.keep_awake);
    initialize_gyro_attitude(&service.gyro);
    star_worker_encode_default_detector_options(service.detector_config);
    service.detector_config_valid = 1;
    rebuild_unified_state(&service);

    if (argc > 2) {
        usage(argc > 0 ? argv[0] : "star_gatt_server");
        return EXIT_FAILURE;
    }
    if (reserve_standard_streams() != 0)
        return EXIT_FAILURE;
    if (argc == 2)
        socket_path = argv[1];
    memset(&child_disposition, 0, sizeof(child_disposition));
    child_disposition.sa_handler = SIG_DFL;
    if (sigemptyset(&child_disposition.sa_mask) != 0 ||
        sigaction(SIGCHLD, &child_disposition, NULL) != 0 ||
        signal(SIGINT, handle_signal) == SIG_ERR ||
        signal(SIGTERM, handle_signal) == SIG_ERR ||
        signal(SIGPIPE, SIG_IGN) == SIG_ERR) {
        perror("signal");
        return EXIT_FAILURE;
    }
    (void)setvbuf(stdout, NULL, _IOLBF, 0u);

    if (open_astro_rate_mailbox(&service.astro_rate) != 0) {
        perror("open astro-rate mailbox");
        goto cleanup;
    }
    /* Preserve any body already owned by MAIN across companion restarts. */
    service.astro_rate.cancel_pending = 1;
    clear_astro_rate_active_value(&service.astro_rate);
    service.preposition.active = 1;
    cancel_astro_preposition(&service, "server startup");
    flush_native_commands(&service);
    if (open_astro_assist_ibis(&service.astro_assist) != 0)
        perror("open read-only Astro Assist IBIS source");
    if (open_astro_assist_acceleration(&service.acceleration) != 0)
        perror("open read-only Astro Assist camera state sources");
    if (open_gyro_attitude(&service.gyro) != 0)
        perror("open gyro attitude mirror/lease");
    (void)write_gyro_attitude_lease(&service.gyro, 0);

    client = bsa_gatt_proxy_client_new();
    if (client == NULL) {
        perror("allocate proxy client");
        goto cleanup;
    }
    service.client = client;
    status = bsa_gatt_proxy_connect(client, socket_path);
    if (status != BSA_GATT_PROXY_CLIENT_OK) {
        (void)report_client_error(client, status, "connect proxy");
        goto cleanup;
    }
    status = bsa_gatt_proxy_hello(
        client, BGP_CAP_GATT_SERVER | BGP_CAP_READ_WRITE |
                    BGP_CAP_NOTIFY_INDICATE,
        &hello);
    if (status != BSA_GATT_PROXY_CLIENT_OK) {
        (void)report_client_error(client, status, "HELLO");
        goto cleanup;
    }
    if (!result_ok(&hello.result, "HELLO") ||
        (hello.state_flags & STAR_REQUIRED_STATE) != STAR_REQUIRED_STATE ||
        (hello.shim_capabilities &
         (BGP_CAP_GATT_SERVER | BGP_CAP_READ_WRITE |
          BGP_CAP_NOTIFY_INDICATE)) !=
            (BGP_CAP_GATT_SERVER | BGP_CAP_READ_WRITE |
             BGP_CAP_NOTIFY_INDICATE)) {
        fprintf(stderr, "stock BLE application/proxy capabilities not ready\n");
        goto cleanup;
    }

    if (bsa_gatt_proxy_uuid128(&application_uuid,
                               application_uuid_bytes) != 0 ||
        bsa_gatt_proxy_uuid128(&service_uuid, service_uuid_bytes) != 0 ||
        bsa_gatt_proxy_uuid128(&control_uuid, control_uuid_bytes) != 0 ||
        bsa_gatt_proxy_uuid128(&state_uuid, state_uuid_bytes) != 0 ||
        bsa_gatt_proxy_uuid128(&lease_uuid, lease_uuid_bytes) != 0 ||
        bsa_gatt_proxy_uuid128(&astro_rate_v2_service_uuid,
                               astro_rate_v2_service_uuid_bytes) != 0 ||
        bsa_gatt_proxy_uuid128(&astro_rate_v2_value_uuid,
                               astro_rate_v2_value_uuid_bytes) != 0 ||
        bsa_gatt_proxy_uuid128(&astro_preposition_uuid,
                               astro_preposition_uuid_bytes) != 0)
        goto cleanup;
    for (kind = 0u; kind < STAR_GATT_SETTING_COUNT; ++kind) {
        if (bsa_gatt_proxy_uuid128(&catalog_uuid[kind],
                                   catalog_uuid_bytes[kind]) != 0)
            goto cleanup;
    }
    bsa_gatt_proxy_uuid16(&cccd_uuid, UINT16_C(0x2902));

    status = bsa_gatt_proxy_register(client, &application_uuid,
                                     &registration);
    if (status != BSA_GATT_PROXY_CLIENT_OK) {
        (void)report_client_error(client, status, "register STAR application");
        goto cleanup;
    }
    if (!result_ok(&registration.result, "register STAR application"))
        goto cleanup;
    server_if = registration.server_if;
    registered = 1;
    service.expected_epoch = registration.epoch;
    printf("registered STAR server_if=%u epoch=%lu\n",
           (unsigned int)server_if, (unsigned long)registration.epoch);

    status = bsa_gatt_proxy_create_service(
        client, server_if, &service_uuid, 0u, 1, STAR_SERVICE_HANDLES,
        &response);
    if (status != BSA_GATT_PROXY_CLIENT_OK) {
        (void)report_client_error(client, status, "create STAR service");
        goto cleanup;
    }
    if (!result_ok(&response, "create STAR service") ||
        wait_for_event(client, BGP_EVT_CREATE_SERVICE, registration.epoch,
                       &event) != 0 ||
        event.payload.create_service.bsa_status != 0u ||
        event.payload.create_service.server_if != server_if)
        goto cleanup;
    star_service_id = event.payload.create_service.service_id;
    printf("created STAR service_id=%u\n", (unsigned int)star_service_id);

    if (add_attribute(client, server_if, star_service_id, &control_uuid, 0,
                      STAR_PROPERTY_WRITE | STAR_PROPERTY_INDICATE,
                      STAR_PERMISSION_WRITE, registration.epoch,
                      "STAR CONTROL characteristic", &service.control_attr) !=
            0 ||
        add_attribute(client, server_if, star_service_id, &cccd_uuid, 1, 0u,
                      STAR_PERMISSION_READ | STAR_PERMISSION_WRITE,
                      registration.epoch, "STAR CONTROL CCCD",
                      &service.control_cccd_attr) != 0 ||
        add_attribute(client, server_if, star_service_id, &state_uuid, 0,
                      STAR_PROPERTY_READ | STAR_PROPERTY_WRITE |
                          STAR_PROPERTY_NOTIFY,
                      STAR_PERMISSION_READ | STAR_PERMISSION_WRITE,
                      registration.epoch,
                      "STAR STATE characteristic", &service.state_attr) != 0 ||
        add_attribute(client, server_if, star_service_id, &cccd_uuid, 1, 0u,
                      STAR_PERMISSION_READ | STAR_PERMISSION_WRITE,
                      registration.epoch, "STAR STATE CCCD",
                      &service.state_cccd_attr) != 0)
        goto cleanup;
    for (kind = 0u; kind < STAR_GATT_SETTING_COUNT; ++kind) {
        static const char *const labels[STAR_GATT_SETTING_COUNT] = {
            "STAR SHUTTER_CATALOG characteristic",
            "STAR APERTURE_CATALOG characteristic",
            "STAR ISO_CATALOG characteristic"
        };

        if (add_attribute(client, server_if, star_service_id,
                          &catalog_uuid[kind], 0, STAR_PROPERTY_READ,
                          STAR_PERMISSION_READ, registration.epoch,
                          labels[kind], &service.catalog_attr[kind]) != 0)
            goto cleanup;
    }
    if (add_attribute(client, server_if, star_service_id, &lease_uuid, 0,
                      STAR_PROPERTY_WRITE, STAR_PERMISSION_WRITE,
                      registration.epoch, "STAR LEASE characteristic",
                      &service.astro_assist_keep_awake_attr) != 0)
        goto cleanup;

    status = bsa_gatt_proxy_create_service(
        client, server_if, &astro_rate_v2_service_uuid, 0u, 1,
        ASTRO_RATE_V2_SERVICE_HANDLES, &response);
    if (status != BSA_GATT_PROXY_CLIENT_OK) {
        (void)report_client_error(client, status,
                                  "create ASTRO RATE v2 service");
        goto cleanup;
    }
    if (!result_ok(&response, "create ASTRO RATE v2 service") ||
        wait_for_event(client, BGP_EVT_CREATE_SERVICE, registration.epoch,
                       &event) != 0 ||
        event.payload.create_service.bsa_status != 0u ||
        event.payload.create_service.server_if != server_if)
        goto cleanup;
    astro_rate_v2_service_id = event.payload.create_service.service_id;
    printf("created ASTRO RATE v2 service_id=%u\n",
           (unsigned int)astro_rate_v2_service_id);

    if (add_attribute(client, server_if, astro_rate_v2_service_id,
                      &astro_rate_v2_value_uuid, 0, STAR_PROPERTY_WRITE,
                      STAR_PERMISSION_WRITE, registration.epoch,
                      "ASTRO RATE v2 characteristic",
                      &service.astro_rate_v2_attr) != 0)
        goto cleanup;
    if (add_attribute(client, server_if, astro_rate_v2_service_id,
                      &astro_preposition_uuid, 0, STAR_PROPERTY_WRITE,
                      STAR_PERMISSION_WRITE, registration.epoch,
                      "ASTRO PREPOSITION characteristic",
                      &service.astro_preposition_attr) != 0)
        goto cleanup;

    status = bsa_gatt_proxy_start_service(client, astro_rate_v2_service_id,
                                          STAR_TRANSPORT_LE, &response);
    if (status != BSA_GATT_PROXY_CLIENT_OK) {
        (void)report_client_error(client, status,
                                  "start ASTRO RATE v2 service");
        goto cleanup;
    }
    if (!result_ok(&response, "start ASTRO RATE v2 service") ||
        wait_for_event(client, BGP_EVT_START_SERVICE, registration.epoch,
                       &event) != 0 ||
        event.payload.service.bsa_status != 0u ||
        event.payload.service.service_id != astro_rate_v2_service_id)
        goto cleanup;
    astro_rate_v2_started = 1;

    /* STAR starts last and is the discovery-readiness sentinel: once a peer
     * can discover STAR, Astro Rate v2 is already live. */
    status = bsa_gatt_proxy_start_service(client, star_service_id,
                                          STAR_TRANSPORT_LE, &response);
    if (status != BSA_GATT_PROXY_CLIENT_OK) {
        (void)report_client_error(client, status, "start STAR service");
        goto cleanup;
    }
    if (!result_ok(&response, "start STAR service") ||
        wait_for_event(client, BGP_EVT_START_SERVICE, registration.epoch,
                       &event) != 0 ||
        event.payload.service.bsa_status != 0u ||
        event.payload.service.service_id != star_service_id)
        goto cleanup;
    star_started = 1;
    printf("STAR service running; public STAR/private worker active; "
           "ASTRO RATE v2 active; legacy services not registered; "
           "reconnect BLE for discovery; press Ctrl-C to stop\n");
    exit_status = serve(&service) == 0 ? EXIT_SUCCESS : EXIT_FAILURE;

cleanup:
    cancel_astro_rate(&service, "server shutdown");
    cancel_astro_preposition(&service, "server shutdown");
    flush_native_commands(&service);
    if (client != NULL && bsa_gatt_proxy_is_connected(client)) {
        /* Withdraw the readiness sentinel before the service it covers. */
        if (star_started) {
            status = bsa_gatt_proxy_stop_service(client, star_service_id,
                                                 &response);
            if (status != BSA_GATT_PROXY_CLIENT_OK) {
                (void)report_client_error(client, status,
                                          "stop STAR service");
                exit_status = EXIT_FAILURE;
            } else if (!result_ok(&response, "stop STAR service") ||
                       wait_for_cleanup_event(client, BGP_EVT_STOP_SERVICE,
                                              &event) != 0 ||
                       event.payload.service.bsa_status != 0u ||
                       event.payload.service.service_id != star_service_id) {
                exit_status = EXIT_FAILURE;
            }
        }
        if (astro_rate_v2_started &&
            bsa_gatt_proxy_is_connected(client)) {
            status = bsa_gatt_proxy_stop_service(client,
                                                 astro_rate_v2_service_id,
                                                 &response);
            if (status != BSA_GATT_PROXY_CLIENT_OK) {
                (void)report_client_error(client, status,
                                          "stop ASTRO RATE v2 service");
                exit_status = EXIT_FAILURE;
            } else if (!result_ok(&response,
                                  "stop ASTRO RATE v2 service") ||
                       wait_for_cleanup_event(client, BGP_EVT_STOP_SERVICE,
                                              &event) != 0 ||
                       event.payload.service.bsa_status != 0u ||
                       event.payload.service.service_id !=
                           astro_rate_v2_service_id) {
                exit_status = EXIT_FAILURE;
            }
        }
        if (registered && bsa_gatt_proxy_is_connected(client)) {
            status = bsa_gatt_proxy_deregister(client, server_if, &response);
            if (status != BSA_GATT_PROXY_CLIENT_OK) {
                (void)report_client_error(client, status,
                                          "deregister STAR application");
                exit_status = EXIT_FAILURE;
            } else if (!result_ok(&response,
                                  "deregister STAR application") ||
                       wait_for_cleanup_event(client, BGP_EVT_DEREGISTER,
                                              &event) != 0 ||
                       event.payload.deregister.bsa_status != 0u) {
                exit_status = EXIT_FAILURE;
            }
        }
    }
    bsa_gatt_proxy_client_free(client);
    close_astro_assist_keep_awake(&service.keep_awake);
    close_gyro_attitude(&service.gyro);
    close_astro_assist_acceleration(&service.acceleration);
    close_astro_assist_ibis(&service.astro_assist);
    close_astro_rate_mailbox(&service.astro_rate);
    return exit_status;
}
