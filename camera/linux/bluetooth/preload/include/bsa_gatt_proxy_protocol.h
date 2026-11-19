#ifndef OLYMPUS_BSA_GATT_PROXY_PROTOCOL_H
#define OLYMPUS_BSA_GATT_PROXY_PROTOCOL_H

/*
 * Local protocol between the blebridge LD_PRELOAD broker and one external
 * GATT-server owner.  Multi-byte integers are little-endian on the wire.
 * The Olympus target and the supported build hosts are little-endian, so the
 * C SDK can copy these packed structures directly.
 */

#include <stddef.h>
#include <stdint.h>

#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error "bsa_gatt_proxy_protocol.h currently requires a little-endian host"
#endif

#if defined(__GNUC__) || defined(__clang__)
#define BGP_PACKED __attribute__((packed))
#else
#define BGP_PACKED
#pragma pack(push, 1)
#endif

#define BGP_MAGIC UINT32_C(0x31504742) /* "BGP1" in little-endian memory */
#define BGP_VERSION UINT16_C(1)
#define BGP_DEFAULT_SOCKET "/var/run/blebridge-gatt.sock"
#define BGP_SOCKET_ENV "BSA_GATT_PROXY_SOCKET"
#define BGP_MAX_PAYLOAD UINT32_C(1024)
#define BGP_MAX_VALUE UINT16_C(600)

#define BGP_RESPONSE_BIT UINT16_C(0x8000)
#define BGP_RESPONSE_TYPE(request_type) \
    ((uint16_t)((request_type) | BGP_RESPONSE_BIT))

enum bgp_message_type {
    BGP_REQ_HELLO = 0x0001,
    BGP_REQ_REGISTER = 0x0010,
    BGP_REQ_DEREGISTER = 0x0011,
    BGP_REQ_CREATE_SERVICE = 0x0020,
    BGP_REQ_ADD_ATTRIBUTE = 0x0021,
    BGP_REQ_START_SERVICE = 0x0022,
    BGP_REQ_STOP_SERVICE = 0x0023,
    BGP_REQ_SEND_RESPONSE = 0x0030,
    BGP_REQ_SEND_INDICATION = 0x0031,

    BGP_EVT_STATE = 0x4001,
    BGP_EVT_DEREGISTER = 0x4011,
    BGP_EVT_CREATE_SERVICE = 0x4020,
    BGP_EVT_ADD_ATTRIBUTE = 0x4021,
    BGP_EVT_START_SERVICE = 0x4022,
    BGP_EVT_STOP_SERVICE = 0x4023,
    BGP_EVT_READ = 0x4030,
    BGP_EVT_WRITE = 0x4031,
    BGP_EVT_EXEC_WRITE = 0x4032,
    BGP_EVT_OPEN = 0x4033,
    BGP_EVT_CLOSE = 0x4034,
    BGP_EVT_MTU = 0x4035,
    BGP_EVT_CONGESTION = 0x4036,
    BGP_EVT_CONFIRM = 0x4037
};

enum bgp_proxy_status {
    BGP_STATUS_OK = 0,
    BGP_STATUS_PROTOCOL = -1,
    BGP_STATUS_BAD_STATE = -2,
    BGP_STATUS_BUSY = -3,
    BGP_STATUS_BAD_ARGUMENT = -4,
    BGP_STATUS_IO = -5,
    BGP_STATUS_NO_RESOURCE = -6,
    BGP_STATUS_NOT_OWNER = -7,
    BGP_STATUS_UNSUPPORTED = -8,
    BGP_STATUS_BSA = -9,
    BGP_STATUS_NOT_READY = -10,
    BGP_STATUS_STALE = -11,
    BGP_STATUS_TOO_LARGE = -12
};

enum bgp_state_flags {
    BGP_STATE_MGT_OPEN = 1u << 0,
    BGP_STATE_BLE_ENABLED = 1u << 1,
    BGP_STATE_STOCK_APP_REGISTERED = 1u << 2,
    BGP_STATE_STOCK_APP_STARTED = 1u << 3,
    BGP_STATE_OWNER_CONNECTED = 1u << 4,
    BGP_STATE_EXTENSION_REGISTERED = 1u << 5
};

enum bgp_capabilities {
    BGP_CAP_GATT_SERVER = 1u << 0,
    BGP_CAP_READ_WRITE = 1u << 1,
    BGP_CAP_NOTIFY_INDICATE = 1u << 2,
    /* Prepared writes are deliberately not supported in protocol version 1. */
    BGP_CAP_PREPARED_WRITE = 1u << 3
};

struct BGP_PACKED bgp_frame_header {
    uint32_t magic;
    uint16_t version;
    uint16_t type;
    uint32_t request_id;
    uint32_t payload_len;
};

/* UUID bytes use canonical display order.  len is 2, 4, or 16. */
struct BGP_PACKED bgp_uuid {
    uint16_t len;
    uint16_t reserved;
    uint8_t value[16];
};

struct BGP_PACKED bgp_result {
    int32_t proxy_status;
    uint16_t bsa_status;
    uint16_t reserved;
};

struct BGP_PACKED bgp_hello_request {
    uint32_t client_capabilities;
};

struct BGP_PACKED bgp_hello_response {
    struct bgp_result result;
    uint32_t shim_capabilities;
    uint32_t state_flags;
    uint32_t epoch;
};

struct BGP_PACKED bgp_register_request {
    struct bgp_uuid application_uuid;
};

struct BGP_PACKED bgp_register_response {
    struct bgp_result result;
    uint8_t server_if;
    uint8_t reserved0;
    uint16_t reserved1;
    uint32_t epoch;
};

struct BGP_PACKED bgp_deregister_request {
    uint8_t server_if;
    uint8_t reserved0;
    uint16_t reserved1;
};

struct BGP_PACKED bgp_create_service_request {
    uint8_t server_if;
    uint8_t instance;
    uint8_t is_primary;
    uint8_t reserved0;
    uint16_t num_handles;
    uint16_t reserved1;
    struct bgp_uuid service_uuid;
};

struct BGP_PACKED bgp_add_attribute_request {
    uint16_t service_id;
    uint8_t is_descriptor;
    uint8_t properties;
    uint16_t permissions;
    uint16_t reserved;
    struct bgp_uuid attribute_uuid;
};

struct BGP_PACKED bgp_start_service_request {
    uint16_t service_id;
    uint8_t transport;
    uint8_t reserved;
};

struct BGP_PACKED bgp_stop_service_request {
    uint16_t service_id;
    uint16_t reserved;
};

/* cookie identifies a READ/WRITE event retained by the shim. */
struct BGP_PACKED bgp_send_response_request {
    uint32_t cookie;
    uint8_t att_status;
    uint8_t auth_req;
    uint16_t value_len;
    uint8_t value[BGP_MAX_VALUE];
};

struct BGP_PACKED bgp_send_indication_request {
    uint16_t conn_id;
    uint16_t attr_id;
    uint16_t value_len;
    uint8_t need_confirm;
    uint8_t reserved;
    uint8_t value[BGP_MAX_VALUE];
};

struct BGP_PACKED bgp_state_event {
    uint32_t state_flags;
    uint32_t epoch;
    int32_t reason;
    uint16_t bsa_status;
    uint16_t reserved;
};

struct BGP_PACKED bgp_deregister_event {
    uint16_t bsa_status;
    uint8_t server_if;
    uint8_t reserved;
};

struct BGP_PACKED bgp_create_service_event {
    uint16_t bsa_status;
    uint8_t server_if;
    uint8_t reserved0;
    uint16_t service_id;
    uint16_t reserved1;
};

struct BGP_PACKED bgp_add_attribute_event {
    uint16_t bsa_status;
    uint8_t server_if;
    uint8_t is_descriptor;
    uint16_t service_id;
    uint16_t attr_id;
};

struct BGP_PACKED bgp_service_event {
    uint16_t bsa_status;
    uint8_t server_if;
    uint8_t reserved0;
    uint16_t service_id;
    uint16_t reserved1;
};

struct BGP_PACKED bgp_read_event {
    uint32_t cookie;
    uint8_t remote_bda[6];
    uint16_t conn_id;
    uint16_t handle;
    uint16_t offset;
    uint16_t bsa_status;
    uint8_t is_long;
    uint8_t reserved;
};

struct BGP_PACKED bgp_write_event {
    uint32_t cookie; /* zero when need_response is false */
    uint8_t remote_bda[6];
    uint16_t conn_id;
    uint16_t handle;
    uint16_t offset;
    uint16_t value_len;
    uint16_t bsa_status;
    uint8_t need_response;
    uint8_t is_prepare;
    uint8_t value[BGP_MAX_VALUE];
};

struct BGP_PACKED bgp_exec_write_event {
    uint32_t cookie;
    uint8_t remote_bda[6];
    uint16_t conn_id;
    uint8_t flag;
    uint8_t reserved[3];
};

struct BGP_PACKED bgp_connection_event {
    uint8_t server_if;
    uint8_t remote_bda[6];
    uint8_t reserved0;
    uint16_t conn_id;
    uint16_t reason;
};

struct BGP_PACKED bgp_mtu_event {
    uint16_t conn_id;
    uint16_t mtu;
};

struct BGP_PACKED bgp_congestion_event {
    uint16_t conn_id;
    uint8_t congested;
    uint8_t reserved;
};

struct BGP_PACKED bgp_confirm_event {
    uint16_t conn_id;
    uint16_t bsa_status;
    uint16_t attr_id;
    uint16_t reserved;
};

#if !defined(__GNUC__) && !defined(__clang__)
#pragma pack(pop)
#endif

#if defined(__cplusplus)
#define BGP_STATIC_ASSERT(c, m) static_assert((c), m)
#else
#define BGP_STATIC_ASSERT(c, m) _Static_assert((c), m)
#endif

BGP_STATIC_ASSERT(sizeof(struct bgp_frame_header) == 16, "frame header");
BGP_STATIC_ASSERT(sizeof(struct bgp_uuid) == 20, "UUID");
BGP_STATIC_ASSERT(sizeof(struct bgp_result) == 8, "result");
BGP_STATIC_ASSERT(sizeof(struct bgp_hello_response) == 20, "hello response");
BGP_STATIC_ASSERT(sizeof(struct bgp_register_response) == 16, "register response");
BGP_STATIC_ASSERT(sizeof(struct bgp_create_service_request) == 28, "create request");
BGP_STATIC_ASSERT(sizeof(struct bgp_add_attribute_request) == 28, "add request");
BGP_STATIC_ASSERT(sizeof(struct bgp_send_response_request) == 608, "ATT response");
BGP_STATIC_ASSERT(sizeof(struct bgp_send_indication_request) == 608, "indication");
BGP_STATIC_ASSERT(sizeof(struct bgp_read_event) == 20, "read event");
BGP_STATIC_ASSERT(sizeof(struct bgp_write_event) == 622, "write event");
BGP_STATIC_ASSERT(sizeof(struct bgp_exec_write_event) == 16, "execute event");
BGP_STATIC_ASSERT(sizeof(struct bgp_connection_event) == 12, "connection event");
BGP_STATIC_ASSERT(sizeof(struct bgp_confirm_event) == 8, "confirm event");
BGP_STATIC_ASSERT(sizeof(struct bgp_write_event) <= BGP_MAX_PAYLOAD,
                  "largest event fits a frame");

#undef BGP_STATIC_ASSERT
#undef BGP_PACKED

#endif
