#ifndef OLYMPUS_BSA_WIRE_H
#define OLYMPUS_BSA_WIRE_H

/*
 * Reconstructed private libbsa.so <-> bsa_server control ABI.
 *
 * Target: BSA0107_00.28.00.olympus2, 32-bit ARM hard-float, little endian.
 * This is a compatibility description, not an original vendor header.
 * See ../../../firmware/BSA_SERVER_ABI.md for provenance, limitations, and
 * confidence.
 */

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define BSA_WIRE_BT_CONTROL_CHANNEL              3u
#define BSA_WIRE_BT_SOCKET_BASENAME              "./bt-daemon-socket"
#define BSA_WIRE_NFC_SOCKET_BASENAME             "./nfc-daemon-socket"
#define BSA_WIRE_MAX_CLIENTS                     5u

/* frame_length includes the two-byte message ID, but not its own two bytes. */
#define BSA_WIRE_FRAME_LENGTH_MIN                2u
#define BSA_WIRE_FRAME_LENGTH_MAX                2048u
#define BSA_WIRE_FRAME_HEADER_SIZE               4u
#define BSA_WIRE_DECODER_PAYLOAD_MAX             2046u

/*
 * The server decoder accepts 2,046 payload bytes, but its next-stage ARM32
 * tBSA_SV_CONTROL_CLIENT_MSG has data[1000]. Keep custom requests at or below
 * this value. All stock requests fit; the largest is SYS_PING at 996 bytes.
 */
#define BSA_WIRE_SAFE_REQUEST_PAYLOAD_MAX        1000u
#define BSA_WIRE_SYS_PING_DATA_SIZE              996u

#define BSA_WIRE_ID_BASE_MASK                    0x3fffu
#define BSA_WIRE_ID_ASYNC_FLAG                   0x4000u
#define BSA_WIRE_ID_RESPONSE_FLAG                0x8000u
#define BSA_WIRE_ID_FLAG_MASK                    0xc000u
#define BSA_WIRE_RESPONSE_ID(base_id) \
    ((uint16_t)((uint16_t)(base_id) | BSA_WIRE_ID_RESPONSE_FLAG))
#define BSA_WIRE_ASYNC_ID(base_id) \
    ((uint16_t)((uint16_t)(base_id) | BSA_WIRE_ID_ASYNC_FLAG))
#define BSA_WIRE_BASE_ID(wire_id) \
    ((uint16_t)((uint16_t)(wire_id) & BSA_WIRE_ID_BASE_MASK))

/* Four bytes at the start of every AF_UNIX SOCK_STREAM frame. */
typedef struct bsa_wire_frame_header {
    uint16_t frame_length_le;
    uint16_t message_id_le;
} bsa_wire_frame_header;

typedef struct bsa_wire_status_reply {
    uint16_t status_le;
} bsa_wire_status_reply;

typedef struct bsa_wire_sys_ping_request {
    uint8_t data[BSA_WIRE_SYS_PING_DATA_SIZE];
} bsa_wire_sys_ping_request;

typedef struct bsa_wire_sys_ping_reply {
    uint16_t status_le;
    uint8_t data[BSA_WIRE_SYS_PING_DATA_SIZE];
} bsa_wire_sys_ping_reply;

/* Client-library-only ARM32 structure. BSA_MgtOpen sends no wire message. */
typedef struct bsa_arm32_mgt_open {
    char uipc_path[100];
    uint32_t callback_fn;
} bsa_arm32_mgt_open;

/* UIPC callback metadata allocated locally; it is never the stream prefix. */
typedef struct bsa_arm32_bt_hdr {
    uint16_t event;
    uint16_t len;
    uint16_t offset;
    uint16_t layer_specific;
} bsa_arm32_bt_hdr;

/* Server-internal GKI envelope reconstructed from the matching DWARF build. */
typedef struct bsa_arm32_server_control_message {
    bsa_arm32_bt_hdr hdr;
    uint16_t message_id;
    uint16_t padding;
    uint8_t data[BSA_WIRE_SAFE_REQUEST_PAYLOAD_MAX];
} bsa_arm32_server_control_message;

typedef struct bsa_arm32_rx_stream {
    uint8_t data[BSA_WIRE_FRAME_LENGTH_MAX];
    int32_t received_data;
    int32_t decoded_length;
    int32_t decoded_message_id;
    int32_t state;
    int32_t flush_data;
} bsa_arm32_rx_stream;

/* Internal control-channel state; useful when auditing crash dumps. */
typedef struct bsa_arm32_uipc_server_control {
    uint32_t callback_fn;
    bsa_arm32_rx_stream rx_ctrl_stream[BSA_WIRE_MAX_CLIENTS];
    uint8_t socket_desc;
    uint8_t padding[3];
} bsa_arm32_uipc_server_control;

typedef struct bsa_arm32_uipc_client_control {
    uint8_t tx_mutex[24];
    uint32_t callback_fn;
    bsa_arm32_rx_stream rx_ctrl_stream;
    uint8_t socket_desc;
    uint8_t padding[3];
} bsa_arm32_uipc_client_control;

/* UIPC callback event values; these are local delivery events, not wire IDs. */
enum bsa_uipc_local_event {
    BSA_UIPC_LOCAL_OPEN = 1,
    BSA_UIPC_LOCAL_CLOSE = 2,
    BSA_UIPC_LOCAL_RX_DATA = 3
};

/* Request base IDs. A synchronous reply uses base_id | 0x8000. */
enum bsa_wire_command_id {
    BSA_WIRE_CMD_SYS_PING = 1,
    BSA_WIRE_CMD_SYS_KILL_SERVER = 2,

    BSA_WIRE_CMD_DM_GET_CONFIG = 100,
    BSA_WIRE_CMD_DM_SET_CONFIG = 101,
    BSA_WIRE_CMD_DM_GET_LOCAL_DI_RECORD = 102,
    BSA_WIRE_CMD_DM_SET_LOCAL_DI_RECORD = 103,

    BSA_WIRE_CMD_DISC_START = 200,
    BSA_WIRE_CMD_DISC_ABORT = 201,
    BSA_WIRE_CMD_DISC_GET_REMOTE_NAME = 202,

    BSA_WIRE_CMD_SEC_SET_SECURITY = 300,
    BSA_WIRE_CMD_SEC_BOND = 301,
    BSA_WIRE_CMD_SEC_BOND_CANCEL = 302,
    BSA_WIRE_CMD_SEC_PIN_CODE_REPLY = 303,
    BSA_WIRE_CMD_SEC_PASSKEY_REPLY = 304,
    BSA_WIRE_CMD_SEC_SP_CFM_REPLY = 305,
    BSA_WIRE_CMD_SEC_AUTHORIZE_REPLY = 306,
    BSA_WIRE_CMD_SEC_ADD_DEVICE = 307,
    BSA_WIRE_CMD_SEC_REMOVE_DEVICE = 308,
    BSA_WIRE_CMD_SEC_READ_OOB = 309,
    BSA_WIRE_CMD_SEC_SET_REMOTE_OOB = 310,
    BSA_WIRE_CMD_SEC_ADD_SI_DEVICE = 311,
    BSA_WIRE_CMD_SEC_CLOSE_ACL = 312,

    BSA_WIRE_CMD_OPS_ENABLE = 900,
    BSA_WIRE_CMD_OPS_DISABLE = 901,
    BSA_WIRE_CMD_OPS_CLOSE = 902,
    BSA_WIRE_CMD_OPS_ACCESS = 903,

    BSA_WIRE_CMD_OPC_ENABLE = 950,
    BSA_WIRE_CMD_OPC_DISABLE = 951,
    BSA_WIRE_CMD_OPC_PUSH = 952,
    BSA_WIRE_CMD_OPC_PULL_CARD = 953,
    BSA_WIRE_CMD_OPC_EXCHANGE_CARD = 954,
    BSA_WIRE_CMD_OPC_CLOSE = 955,

    BSA_WIRE_CMD_DG_ENABLE = 1200,
    BSA_WIRE_CMD_DG_DISABLE = 1201,
    BSA_WIRE_CMD_DG_LISTEN = 1202,
    BSA_WIRE_CMD_DG_OPEN = 1203,
    BSA_WIRE_CMD_DG_CLOSE = 1204,
    BSA_WIRE_CMD_DG_SHUTDOWN = 1205,
    BSA_WIRE_CMD_DG_FIND_SERVICE = 1206,
    BSA_WIRE_CMD_DG_GET_SDP = 1207,

    BSA_WIRE_CMD_TM_SET_TEST_MODE = 1400,
    BSA_WIRE_CMD_TM_GET_MEM_USAGE = 1401,
    BSA_WIRE_CMD_TM_VSC = 1402,
    BSA_WIRE_CMD_TM_PING = 1403,
    BSA_WIRE_CMD_TM_SET_TRACE_LEVEL = 1404,
    BSA_WIRE_CMD_TM_SET_TRACE_TEXT = 1405,
    BSA_WIRE_CMD_TM_READ_VERSION = 1406,
    BSA_WIRE_CMD_TM_VSE_REGISTER = 1407,
    BSA_WIRE_CMD_TM_VSE_DEREGISTER = 1408,
    BSA_WIRE_CMD_TM_GET_HANDLE = 1409,
    BSA_WIRE_CMD_TM_READ_RAW_RSSI = 1410,
    BSA_WIRE_CMD_TM_DISCONNECT = 1411,
    BSA_WIRE_CMD_TM_QOS = 1412,
    BSA_WIRE_CMD_TM_DIAG_STATS = 1413,
    BSA_WIRE_CMD_TM_LE_TEST = 1414,
    BSA_WIRE_CMD_TM_COEX_OP = 1415,
    BSA_WIRE_CMD_TM_HCI_DIRECT = 1416,

    BSA_WIRE_CMD_BLE_ENABLE = 1700,
    BSA_WIRE_CMD_BLE_DISABLE = 1701,
    BSA_WIRE_CMD_BLE_WAKE_CONFIG = 1702,
    BSA_WIRE_CMD_BLE_WAKE_ENABLE = 1703,
    BSA_WIRE_CMD_BLE_APCF_CONFIG = 1704,
    BSA_WIRE_CMD_BLE_APCF_ENABLE = 1705,
    BSA_WIRE_CMD_BLE_CL_REGISTER = 1706,
    BSA_WIRE_CMD_BLE_CL_DEREGISTER = 1707,
    BSA_WIRE_CMD_BLE_CL_OPEN = 1708,
    BSA_WIRE_CMD_BLE_CL_CLOSE = 1709,
    BSA_WIRE_CMD_BLE_CL_READ = 1710,
    BSA_WIRE_CMD_BLE_CL_WRITE = 1711,
    BSA_WIRE_CMD_BLE_CL_SEARCH = 1712,
    BSA_WIRE_CMD_BLE_CL_GET_FIRST_CHAR = 1713,
    BSA_WIRE_CMD_BLE_CL_GET_NEXT_CHAR = 1714,
    BSA_WIRE_CMD_BLE_CL_GET_FIRST_CHAR_DESCR = 1715,
    BSA_WIRE_CMD_BLE_CL_GET_NEXT_CHAR_DESCR = 1716,
    BSA_WIRE_CMD_BLE_CL_NOTIF_REGISTER = 1717,
    BSA_WIRE_CMD_BLE_CL_NOTIF_DEREGISTER = 1718,
    BSA_WIRE_CMD_BLE_CL_CACHE_LOAD = 1719,
    BSA_WIRE_CMD_BLE_CL_INDICATION_CONFIRM = 1720,
    BSA_WIRE_CMD_BLE_CL_REFRESH = 1721,
    BSA_WIRE_CMD_BLE_CL_CONFIG_MTU = 1722,
    BSA_WIRE_CMD_BLE_SE_REGISTER = 1723,
    BSA_WIRE_CMD_BLE_SE_DEREGISTER = 1724,
    BSA_WIRE_CMD_BLE_SE_OPEN = 1725,
    BSA_WIRE_CMD_BLE_SE_CLOSE = 1726,
    BSA_WIRE_CMD_BLE_SE_CREATE_SERVICE = 1727,
    BSA_WIRE_CMD_BLE_SE_ADD_CHAR = 1728,
    BSA_WIRE_CMD_BLE_SE_SEND_INDICATION = 1729,
    BSA_WIRE_CMD_BLE_SE_SEND_RESPONSE = 1730,
    BSA_WIRE_CMD_BLE_SE_START_SERVICE = 1731,
    BSA_WIRE_CMD_BLE_SE_STOP_SERVICE = 1732
};

/*
 * Asynchronous event base IDs. Their wire ID is base_id | 0x4000.
 * The callback number is the public callback enum value passed by libbsa.
 */
enum bsa_wire_event_id {
    BSA_WIRE_EVT_DM_3D_ANNOUNCEMENT = 104,       /* callback 0; client drops */

    BSA_WIRE_EVT_DISC_NEW = 203,                 /* callback 0 */
    BSA_WIRE_EVT_DISC_COMPLETE = 204,            /* callback 1 */
    BSA_WIRE_EVT_DISC_DEVICE_INFO = 205,         /* callback 2 */
    BSA_WIRE_EVT_DISC_REMOTE_NAME = 206,         /* callback 3 */

    BSA_WIRE_EVT_SEC_LINK_UP = 313,               /* callback 0 */
    BSA_WIRE_EVT_SEC_LINK_DOWN = 314,             /* callback 1 */
    BSA_WIRE_EVT_SEC_PIN_REQUEST = 315,           /* callback 2 */
    BSA_WIRE_EVT_SEC_AUTH_COMPLETE = 316,         /* callback 3 */
    BSA_WIRE_EVT_SEC_BOND_CANCEL_COMPLETE = 317,  /* callback 4 */
    BSA_WIRE_EVT_SEC_AUTHORIZE = 318,             /* callback 5 */
    BSA_WIRE_EVT_SEC_SP_CONFIRM_REQUEST = 319,    /* callback 6 */
    BSA_WIRE_EVT_SEC_SP_KEY_NOTIFICATION = 320,   /* callback 7 */
    BSA_WIRE_EVT_SEC_SP_KEY_REQUEST = 321,        /* callback 8 */
    BSA_WIRE_EVT_SEC_SP_REMOTE_OOB = 322,         /* callback 9 */
    BSA_WIRE_EVT_SEC_LOCAL_OOB_DATA = 323,        /* callback 10 */
    BSA_WIRE_EVT_SEC_SP_KEYPRESS = 324,           /* callback 11 */
    BSA_WIRE_EVT_SEC_BLE_KEY = 325,               /* callback 12 */
    BSA_WIRE_EVT_SEC_BLE_PASSKEY_REQUEST = 326,   /* callback 13 */
    BSA_WIRE_EVT_SEC_SUSPENDED = 327,             /* callback 14 */
    BSA_WIRE_EVT_SEC_RESUMED = 328,               /* callback 15 */
    BSA_WIRE_EVT_SEC_UNKNOWN_LINK_KEY = 329,       /* callback 17 */
    BSA_WIRE_EVT_SEC_RSSI = 330,                   /* callback 16 */

    BSA_WIRE_EVT_OPS_OPEN = 904,                  /* callback 1 */
    BSA_WIRE_EVT_OPS_PROGRESS = 905,              /* callback 2 */
    BSA_WIRE_EVT_OPS_OBJECT = 906,                /* callback 3 */
    BSA_WIRE_EVT_OPS_CLOSE = 907,                 /* callback 4 */
    BSA_WIRE_EVT_OPS_ACCESS = 908,                /* callback 5 */

    BSA_WIRE_EVT_OPC_ENABLE = 956,                /* defined; client drops */
    BSA_WIRE_EVT_OPC_DISABLE = 957,               /* defined; client drops */
    BSA_WIRE_EVT_OPC_OPEN = 958,                  /* callback 1 */
    BSA_WIRE_EVT_OPC_PROGRESS = 959,              /* callback 2 */
    BSA_WIRE_EVT_OPC_OBJECT = 960,                /* callback 3 */
    BSA_WIRE_EVT_OPC_OBJECT_PUSHED = 961,         /* callback 4 */
    BSA_WIRE_EVT_OPC_CLOSE = 962,                 /* callback 5 */

    BSA_WIRE_EVT_DG_OPEN = 1208,                  /* callback 0 */
    BSA_WIRE_EVT_DG_CLOSE = 1209,                 /* callback 1 */
    BSA_WIRE_EVT_DG_FIND_SERVICE = 1210,          /* callback 2 */
    BSA_WIRE_EVT_DG_GET_SDP = 1211,               /* callback 3 */

    BSA_WIRE_EVT_TM_VSE = 1417,                   /* callback 0 */

    BSA_WIRE_EVT_BLE_CL_DEREGISTER = 1733,        /* callback 0 */
    BSA_WIRE_EVT_BLE_CL_OPEN = 1734,              /* callback 1 */
    BSA_WIRE_EVT_BLE_CL_CLOSE = 1735,             /* callback 5 */
    BSA_WIRE_EVT_BLE_CL_READ = 1736,              /* callback 2 */
    BSA_WIRE_EVT_BLE_CL_WRITE = 1737,             /* callback 3 */
    BSA_WIRE_EVT_BLE_CL_EXEC_COMPLETE = 1738,     /* callback 4 */
    BSA_WIRE_EVT_BLE_CL_SEARCH_RESULT = 1739,     /* callback 7 */
    BSA_WIRE_EVT_BLE_CL_SEARCH_COMPLETE = 1740,   /* callback 6 */
    BSA_WIRE_EVT_BLE_CL_NOTIFICATION = 1741,      /* callback 8 */
    BSA_WIRE_EVT_BLE_CL_CONGESTION = 1742,        /* callback 9 */
    BSA_WIRE_EVT_BLE_CL_CACHE_SAVE = 1743,        /* callback 10 */
    BSA_WIRE_EVT_BLE_CL_CACHE_LOAD = 1744,        /* callback 11 */
    BSA_WIRE_EVT_BLE_CL_CONFIG_MTU = 1745,        /* callback 12 */
    BSA_WIRE_EVT_BLE_SE_DEREGISTER = 1746,        /* callback 13 */
    BSA_WIRE_EVT_BLE_SE_CREATE = 1747,            /* callback 14 */
    BSA_WIRE_EVT_BLE_SE_ADD_CHAR = 1748,          /* callback 15 */
    BSA_WIRE_EVT_BLE_SE_READ = 1749,              /* callback 22 */
    BSA_WIRE_EVT_BLE_SE_WRITE = 1750,             /* callback 18 */
    BSA_WIRE_EVT_BLE_SE_OPEN = 1751,              /* callback 23 */
    BSA_WIRE_EVT_BLE_SE_CLOSE = 1752,             /* callback 24 */
    BSA_WIRE_EVT_BLE_SE_MTU = 1753,               /* callback 20 */
    BSA_WIRE_EVT_BLE_SE_CONGESTION = 1754,        /* callback 21 */
    BSA_WIRE_EVT_BLE_SE_START = 1755,             /* callback 16 */
    BSA_WIRE_EVT_BLE_SE_STOP = 1756,              /* callback 17 */
    BSA_WIRE_EVT_BLE_SE_CONFIRM = 1757,           /* callback 25 */
    BSA_WIRE_EVT_BLE_SE_EXEC_WRITE = 1758,        /* callback 19 */
    BSA_WIRE_EVT_BLE_APCF_CONFIG = 1759,          /* callback 27 */
    BSA_WIRE_EVT_BLE_APCF_ENABLE = 1760,          /* callback 26 */

    BSA_WIRE_EVT_MGT_STATUS = 1001                /* callback 1 */
};

/* Values carried by a two-byte bsa_wire_status_reply. */
enum bsa_wire_status {
    BSA_WIRE_STATUS_SUCCESS = 0,

    BSA_WIRE_STATUS_CLIENT_BAD_RESPONSE_SIZE = 100,
    BSA_WIRE_STATUS_CLIENT_NOT_YET_IMPLEMENTED = 101,
    BSA_WIRE_STATUS_CLIENT_DISCOVERY_BUSY = 102,
    BSA_WIRE_STATUS_CLIENT_RESOURCE_ERROR = 103,
    BSA_WIRE_STATUS_CLIENT_BAD_PARAMETER = 104,
    BSA_WIRE_STATUS_CLIENT_NOT_CONNECTED = 105,
    BSA_WIRE_STATUS_CLIENT_BUSY = 106,
    BSA_WIRE_STATUS_CLIENT_UIPC_OPEN = 107,
    BSA_WIRE_STATUS_CLIENT_UIPC_SEND = 108,
    BSA_WIRE_STATUS_CLIENT_BAD_MESSAGE = 109,
    BSA_WIRE_STATUS_CLIENT_BAD_PING_RESPONSE = 110,
    BSA_WIRE_STATUS_CLIENT_NAME_TOO_LONG = 111,
    BSA_WIRE_STATUS_CLIENT_ALREADY_WAITING = 112,
    BSA_WIRE_STATUS_CLIENT_TASK_ERROR = 113,
    BSA_WIRE_STATUS_CLIENT_INTERNAL = 114,
    BSA_WIRE_STATUS_CLIENT_ALREADY_CONNECTED = 115,
    BSA_WIRE_STATUS_CLIENT_MEMORY_FULL = 116,
    BSA_WIRE_STATUS_CLIENT_UNEXPECTED_EVENT = 117,
    BSA_WIRE_STATUS_CLIENT_GKI_MAILBOX_ERROR = 118,
    BSA_WIRE_STATUS_CLIENT_TIMEOUT = 119,
    BSA_WIRE_STATUS_CLIENT_BAD_EVENT = 120,

    BSA_WIRE_STATUS_SERVER_BAD_PARAMETER = 200,
    BSA_WIRE_STATUS_SERVER_BAD_REQUEST_SIZE = 201,
    BSA_WIRE_STATUS_SERVER_BAD_MESSAGE_ID = 202,
    BSA_WIRE_STATUS_SERVER_ALREADY_ACTIVE = 203,
    BSA_WIRE_STATUS_SERVER_NOT_YET_IMPLEMENTED = 204,
    BSA_WIRE_STATUS_SERVER_BAD_CLIENT = 205,
    BSA_WIRE_STATUS_SERVER_MEMORY_FULL = 206,
    BSA_WIRE_STATUS_SERVER_HARDWARE_ERROR = 207,
    BSA_WIRE_STATUS_SERVER_BLUETOOTH_DISABLED = 208,
    BSA_WIRE_STATUS_SERVER_INTERNAL = 209,
    BSA_WIRE_STATUS_SERVER_NOT_COMPILED = 210,
    BSA_WIRE_STATUS_SERVER_WLAN_RESET = 211,
    BSA_WIRE_STATUS_SERVER_BUSY = 212,

    BSA_WIRE_STATUS_SECURITY_BOND_ACTIVE = 300,
    BSA_WIRE_STATUS_SECURITY_REMOVE_CONNECTED_DEVICE = 301,
    BSA_WIRE_STATUS_SECURITY_BOND_CANCEL_ERROR = 302,
    BSA_WIRE_STATUS_SECURITY_NOT_ALLOWED = 303,

    BSA_WIRE_STATUS_OPS_NO_ACCESS_PENDING = 500,

    BSA_WIRE_STATUS_OPC_FAILED = 520,
    BSA_WIRE_STATUS_OPC_NOT_FOUND = 521,
    BSA_WIRE_STATUS_OPC_NO_PERMISSION = 522,
    BSA_WIRE_STATUS_OPC_SERVER_UNAVAILABLE = 523,
    BSA_WIRE_STATUS_OPC_ALREADY_ACTIVE = 524,

    BSA_WIRE_STATUS_DISCOVERY_BUSY = 900,
    BSA_WIRE_STATUS_DEVICE_INFO_BUSY = 901,
    BSA_WIRE_STATUS_DISCOVERY_ABORT_ERROR = 902,

    BSA_WIRE_STATUS_TM_VSC_BUSY = 1100,
    BSA_WIRE_STATUS_TM_HCI_ERROR = 1101,
    BSA_WIRE_STATUS_TM_HCI_COMMAND_ERROR = 1102,

    BSA_WIRE_STATUS_DG_OPEN_FAILED = 1500,
    BSA_WIRE_STATUS_DG_CONNECTED = 1501,
    BSA_WIRE_STATUS_DG_FIND_SERVICE_FAILED = 1502,

    BSA_WIRE_STATUS_BLE_FAILED = 1600,
    BSA_WIRE_STATUS_BLE_NOT_ENABLED = 1601,
    BSA_WIRE_STATUS_BLE_NO_RESOURCE = 1602,
    BSA_WIRE_STATUS_BLE_INVALID_PARAMETER = 1603
};

static inline uint16_t bsa_wire_load_le16(const void *src)
{
    const uint8_t *p = (const uint8_t *)src;
    return (uint16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}

static inline void bsa_wire_store_le16(void *dst, uint16_t value)
{
    uint8_t *p = (uint8_t *)dst;
    p[0] = (uint8_t)(value & 0xffu);
    p[1] = (uint8_t)(value >> 8);
}

#if defined(__cplusplus) && __cplusplus >= 201103L
#define BSA_WIRE_STATIC_ASSERT(expr, message) static_assert((expr), message)
#elif defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
#define BSA_WIRE_STATIC_ASSERT(expr, message) _Static_assert((expr), message)
#else
#define BSA_WIRE_STATIC_ASSERT_GLUE_(a, b) a##b
#define BSA_WIRE_STATIC_ASSERT_GLUE(a, b) BSA_WIRE_STATIC_ASSERT_GLUE_(a, b)
#define BSA_WIRE_STATIC_ASSERT(expr, message) \
    typedef char BSA_WIRE_STATIC_ASSERT_GLUE(bsa_wire_assert_, __LINE__)[(expr) ? 1 : -1]
#endif

BSA_WIRE_STATIC_ASSERT(sizeof(bsa_wire_frame_header) == 4,
                       "wire frame header must be 4 bytes");
BSA_WIRE_STATIC_ASSERT(sizeof(bsa_wire_status_reply) == 2,
                       "wire status reply must be 2 bytes");
BSA_WIRE_STATIC_ASSERT(sizeof(bsa_wire_sys_ping_request) == 996,
                       "SYS_PING request must be 996 bytes");
BSA_WIRE_STATIC_ASSERT(sizeof(bsa_wire_sys_ping_reply) == 998,
                       "SYS_PING reply must be 998 bytes");
BSA_WIRE_STATIC_ASSERT(sizeof(bsa_arm32_mgt_open) == 104,
                       "ARM32 tBSA_MGT_OPEN must be 104 bytes");
BSA_WIRE_STATIC_ASSERT(sizeof(bsa_arm32_bt_hdr) == 8,
                       "ARM32 BT_HDR must be 8 bytes");
BSA_WIRE_STATIC_ASSERT(sizeof(bsa_arm32_server_control_message) == 1012,
                       "ARM32 server control envelope must be 1012 bytes");
BSA_WIRE_STATIC_ASSERT(sizeof(bsa_arm32_rx_stream) == 2068,
                       "ARM32 receive decoder state must be 2068 bytes");
BSA_WIRE_STATIC_ASSERT(sizeof(bsa_arm32_uipc_server_control) == 10348,
                       "ARM32 server control block must be 10348 bytes");
BSA_WIRE_STATIC_ASSERT(sizeof(bsa_arm32_uipc_client_control) == 2100,
                       "ARM32 client control block must be 2100 bytes");

#undef BSA_WIRE_STATIC_ASSERT

#ifdef __cplusplus
}
#endif

#endif /* OLYMPUS_BSA_WIRE_H */
